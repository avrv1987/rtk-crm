package ru.rtk.crm.source;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.CatalogNames;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;

@Repository
public class SourceRepository {
    private static final int MAX_ERROR_LENGTH = 500;
    private static final ObjectMapper PAYLOADS = new ObjectMapper();
    private static final Pattern INTERACTION_ID = Pattern.compile("\"interactionId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
    private static final String RUN_SELECT = """
            SELECT r.id, r.source, r.status, r.updated_since, r.fetched_count, r.created_count, r.updated_count,
                   r.skipped_count, r.needs_mapping_count, r.failed_count, r.error_code, r.error_message, r.created_at,
                   r.started_at, r.finished_at, r.started_by, r.run_trigger, p.display_name AS started_by_name,
                   o.name AS organization_name
            FROM sync_runs r
            LEFT JOIN crm_user_profiles p ON p.id = r.started_by
            LEFT JOIN organizations o ON o.id = r.organization_id
            """;

    private static final String RECORD_SELECT = """
            SELECT id, source, record_type, external_id, external_updated_at, external_status, payload, status, error,
                   organization_id, program_id, interaction_id, updated_at, stream_no, payload_hash
            FROM source_records
            """;

    private static final String ORGANIZATION_SELECT = "SELECT id, name, owner_manager_id FROM organizations ";

    private static final String SNAPSHOT_SELECT = """
            SELECT s.mapping_id, s.source_record_id, s.organization_id, s.program_id, s.course_id, s.group_id,
                   s.course_name, s.group_name, s.participants_count, s.teachers_count, s.completed_count,
                   s.not_completed_count, s.unknown_count, s.groups_count, s.observed_at, s.changed_at, s.interaction_id,
                   m.run_starts_on, m.run_ends_on, m.run_kind
            FROM learning_snapshots s
            JOIN source_mappings m ON m.id = s.mapping_id
            """;

    private static final String TARGET_SELECT = """
            SELECT m.id AS mapping_id, o.id AS organization_id, o.owner_manager_id, p.id AS program_id, m.run_starts_on,
                   m.run_ends_on, m.run_kind
            FROM source_mappings m
            JOIN organizations o ON o.id = m.organization_id
            JOIN programs p ON p.id = m.program_id
            """;

    private static final String MAPPING_SELECT = """
            SELECT id, source, kind, external_key, organization_id, program_id, run_starts_on, run_ends_on, run_kind, version
            FROM source_mappings
            """;

    private static final String CYCLE_WINDOW = """
            LEFT JOIN interaction_cycles cycle_start ON cycle_start.interaction_id = i.id
            LEFT JOIN interaction_cycles cycle_next ON cycle_next.previous_interaction_id = i.id
            """;

    private static final String IN_CYCLE_WINDOW = """
            (cycle_start.starts_on IS NULL OR (m.run_starts_on IS NOT NULL AND m.run_starts_on >= cycle_start.starts_on))
            AND (cycle_next.starts_on IS NULL OR m.run_starts_on IS NULL OR m.run_starts_on < cycle_next.starts_on)
            """;

    private final JdbcClient jdbcClient;

    public SourceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    void insertRun(UUID id, SourceCode source, UUID startedBy, SyncTrigger trigger, UUID organizationId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO sync_runs (id, source, status, started_by, run_trigger, organization_id, created_at)
                VALUES (:id, :source, 'PENDING', :startedBy, :trigger, :organizationId, :createdAt)
                """)
                .param("id", id)
                .param("source", source.name())
                .param("startedBy", startedBy)
                .param("trigger", trigger.name())
                .param("organizationId", organizationId)
                .param("createdAt", now)
                .update();
    }

    boolean hasActiveRun(SourceCode source) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM sync_runs WHERE source = :source AND status IN ('PENDING', 'RUNNING')
                """)
                .param("source", source.name())
                .query(Long.class)
                .single() > 0;
    }

    boolean claimRun(UUID id, OffsetDateTime updatedSince, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE sync_runs
                SET status = 'RUNNING', started_at = :startedAt, updated_since = :updatedSince
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("id", id)
                .param("startedAt", now)
                .param("updatedSince", updatedSince)
                .update() == 1;
    }

    void completeRun(UUID id, SyncTotals totals, String message, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE sync_runs
                SET status = 'SUCCEEDED', fetched_count = :fetched, created_count = :created, updated_count = :updated,
                    skipped_count = :skipped, needs_mapping_count = :needsMapping, failed_count = :failed,
                    error_message = :message, finished_at = :finishedAt
                WHERE id = :id AND status = 'RUNNING'
                """)
                .param("id", id)
                .param("fetched", totals.fetched())
                .param("created", totals.created())
                .param("updated", totals.updated())
                .param("skipped", totals.skipped())
                .param("needsMapping", totals.needsMapping())
                .param("failed", totals.failed())
                .param("message", message)
                .param("finishedAt", now)
                .update();
    }

    void failRun(UUID id, String code, String message, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE sync_runs
                SET status = 'FAILED', error_code = :code, error_message = :message, finished_at = :finishedAt
                WHERE id = :id AND status IN ('PENDING', 'RUNNING')
                """)
                .param("id", id)
                .param("code", code)
                .param("message", limitError(message))
                .param("finishedAt", now)
                .update();
    }

    void deleteRun(UUID id) {
        jdbcClient.sql("DELETE FROM sync_runs WHERE id = :id").param("id", id).update();
    }

    int failUnfinishedCreatedBefore(OffsetDateTime before, String code, String message) {
        return jdbcClient.sql("""
                UPDATE sync_runs
                SET status = 'FAILED', error_code = :code, error_message = :message, finished_at = :finishedAt
                WHERE status IN ('PENDING', 'RUNNING') AND created_at < :before
                """)
                .param("code", code)
                .param("message", message)
                .param("finishedAt", OffsetDateTime.now())
                .param("before", before)
                .update();
    }

    Optional<StoredRun> findRun(UUID id) {
        return jdbcClient.sql(RUN_SELECT + "WHERE r.id = :id")
                .param("id", id)
                .query((resultSet, rowNumber) -> new StoredRun(mapRun(resultSet), resultSet.getObject("started_by", UUID.class)))
                .optional();
    }

    Optional<SyncRunView> findLatestFullRun(SourceCode source) {
        return jdbcClient.sql(RUN_SELECT + """
                WHERE r.source = :source AND r.organization_id IS NULL AND r.run_trigger <> 'UPLOAD'
                ORDER BY r.created_at DESC, r.id DESC
                LIMIT 1
                """)
                .param("source", source.name())
                .query((resultSet, rowNumber) -> mapRun(resultSet))
                .optional();
    }

    List<SyncRunView> findRuns(SourceCode source, int limit) {
        return jdbcClient.sql(RUN_SELECT + "WHERE r.source = :source ORDER BY r.created_at DESC, r.id DESC LIMIT :limit")
                .param("source", source.name())
                .param("limit", limit)
                .query((resultSet, rowNumber) -> mapRun(resultSet))
                .list();
    }

    Optional<SyncRunView> findLatestFinishedRunFor(SourceCode source, UUID organizationId) {
        return jdbcClient.sql(RUN_SELECT + """
                WHERE r.source = :source AND r.status IN ('SUCCEEDED', 'FAILED') AND r.run_trigger <> 'UPLOAD'
                  AND (r.organization_id IS NULL OR r.organization_id = :organizationId)
                ORDER BY r.created_at DESC, r.id DESC
                LIMIT 1
                """)
                .param("source", source.name())
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> mapRun(resultSet))
                .optional();
    }

    Optional<OffsetDateTime> findLastSuccessAtFor(SourceCode source, UUID organizationId) {
        return jdbcClient.sql("""
                SELECT MAX(finished_at) FROM sync_runs
                WHERE source = :source AND status = 'SUCCEEDED' AND run_trigger <> 'UPLOAD'
                  AND (organization_id IS NULL OR organization_id = :organizationId)
                """)
                .param("source", source.name())
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> resultSet.getObject(1, OffsetDateTime.class))
                .optional();
    }

    Optional<OffsetDateTime> findLastFullSuccessStartedAt(SourceCode source) {
        return jdbcClient.sql("""
                SELECT MAX(started_at) FROM sync_runs
                WHERE source = :source AND status = 'SUCCEEDED' AND organization_id IS NULL AND run_trigger <> 'UPLOAD'
                """)
                .param("source", source.name())
                .query((resultSet, rowNumber) -> resultSet.getObject(1, OffsetDateTime.class))
                .optional();
    }

    Optional<OffsetDateTime> findLastFullSuccessAt(SourceCode source) {
        return jdbcClient.sql("""
                SELECT MAX(finished_at) FROM sync_runs
                WHERE source = :source AND status = 'SUCCEEDED' AND organization_id IS NULL AND run_trigger <> 'UPLOAD'
                """)
                .param("source", source.name())
                .query((resultSet, rowNumber) -> resultSet.getObject(1, OffsetDateTime.class))
                .optional();
    }

    Optional<OffsetDateTime> findUpdatedSince(SourceCode source) {
        return jdbcClient.sql("SELECT updated_since FROM sources WHERE code = :source")
                .param("source", source.name())
                .query((resultSet, rowNumber) -> resultSet.getObject("updated_since", OffsetDateTime.class))
                .optional();
    }

    void advanceUpdatedSince(SourceCode source, OffsetDateTime updatedSince, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE sources
                SET updated_since = :updatedSince, updated_at = :updatedAt
                WHERE code = :source AND (updated_since IS NULL OR updated_since < :updatedSince)
                """)
                .param("source", source.name())
                .param("updatedSince", updatedSince)
                .param("updatedAt", now)
                .update();
    }

    Optional<StoredRecord> findRecordForUpdate(SourceCode source, String recordType, String externalId) {
        return jdbcClient.sql(RECORD_SELECT + """
                WHERE source = :source AND record_type = :recordType AND external_id = :externalId
                FOR UPDATE
                """)
                .param("source", source.name())
                .param("recordType", recordType)
                .param("externalId", externalId)
                .query(this::mapRecord)
                .optional();
    }

    Optional<StoredRecord> findRecord(UUID id) {
        return jdbcClient.sql(RECORD_SELECT + "WHERE id = :id")
                .param("id", id)
                .query(this::mapRecord)
                .optional();
    }

    Optional<StoredRecord> findRecordForUpdate(UUID id) {
        return jdbcClient.sql(RECORD_SELECT + "WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(this::mapRecord)
                .optional();
    }

    List<StoredRecord> findProblemRecords(int limit) {
        return jdbcClient.sql(RECORD_SELECT + """
                WHERE status IN ('NEEDS_MAPPING', 'FAILED')
                ORDER BY updated_at DESC, id
                LIMIT :limit
                """)
                .param("limit", limit)
                .query(this::mapRecord)
                .list();
    }

    long countProblems(SourceCode source) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM source_records WHERE source = :source AND status IN ('NEEDS_MAPPING', 'FAILED')
                """)
                .param("source", source.name())
                .query(Long.class)
                .single();
    }

    List<StoredRecord> findPendingWebsiteRecords(VisibilityScope scope, boolean includeUnresolved, int limit) {
        return jdbcClient.sql(RECORD_SELECT + """
                WHERE source = 'WEBSITE' AND status IN ('NEEDS_MAPPING', 'FAILED')
                  AND record_type IN ('partnership_request', 'learning_application')
                  AND (organization_id IN (SELECT id FROM organizations WHERE %s)%s)
                ORDER BY submitted_at DESC, id
                LIMIT :limit
                """.formatted(scope.condition(), includeUnresolved ? " OR organization_id IS NULL" : ""))
                .params(scope.parameters())
                .param("limit", limit)
                .query(this::mapRecord)
                .list();
    }

    List<StoredRecord> findWebsiteRecordsLinkedTo(String column, UUID targetId, int limit) {
        return jdbcClient.sql(RECORD_SELECT + """
                WHERE source = 'WEBSITE' AND (%s = :targetId OR status = 'NEEDS_MAPPING')
                ORDER BY updated_at, id
                LIMIT :limit
                """.formatted(column.equals("program_id") ? "program_id" : "organization_id"))
                .param("targetId", targetId)
                .param("limit", limit)
                .query(this::mapRecord)
                .list();
    }

    List<StoredRecord> findMoodleRecordsOfCourse(String courseKey) {
        return jdbcClient.sql(RECORD_SELECT + """
                WHERE source = 'MOODLE' AND (external_id = :courseKey OR external_id LIKE :groups)
                ORDER BY external_id
                """)
                .param("courseKey", courseKey)
                .param("groups", courseKey + ":%")
                .query(this::mapRecord)
                .list();
    }

    Optional<StoredRecord> findMoodleRecordForUpdate(String kind, String externalKey) {
        return findRecordForUpdate(SourceCode.MOODLE, kind.equals("GROUP") ? LearningUnit.GROUP_RECORD : LearningUnit.COURSE_RECORD,
                externalKey);
    }

    List<UUID> findNeedsMappingIds(SourceCode source, int limit) {
        return jdbcClient.sql("""
                SELECT id FROM source_records
                WHERE source = :source AND status = 'NEEDS_MAPPING'
                ORDER BY updated_at, id
                LIMIT :limit
                """)
                .param("source", source.name())
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    void insertRecord(UUID id, SourceCode source, RecordVersion item, ApplyResult result, UUID runId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO source_records (
                    id, source, record_type, external_id, external_updated_at, submitted_at, external_status, payload,
                    status, error, organization_id, program_id, applications_count, interaction_id, sync_run_id,
                    stream_no, payload_hash, created_at, updated_at
                ) VALUES (
                    :id, :source, :recordType, :externalId, :externalUpdatedAt, :submittedAt, :externalStatus, :payload,
                    :status, :error, :organizationId, :programId, :applicationsCount, :interactionId, :runId,
                    :streamNo, :payloadHash, :now, :now
                )
                """)
                .param("id", id)
                .param("source", source.name())
                .param("recordType", item.type())
                .param("externalId", item.externalId())
                .param("externalUpdatedAt", item.updatedAt())
                .param("submittedAt", item.submittedAt())
                .param("externalStatus", item.externalStatus())
                .param("payload", item.payload())
                .param("streamNo", item.streamNo())
                .param("payloadHash", item.payloadHash())
                .params(resultParameters(result))
                .param("runId", runId)
                .param("now", now)
                .update();
    }

    void updateRecord(UUID id, RecordVersion item, ApplyResult result, UUID runId, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE source_records
                SET external_updated_at = CASE WHEN record_type = 'paid_order' THEN external_updated_at ELSE :externalUpdatedAt END,
                    submitted_at = CASE WHEN record_type = 'paid_order' THEN submitted_at ELSE :submittedAt END,
                    external_status = :externalStatus,
                    payload = :payload, status = :status, error = :error, organization_id = :organizationId,
                    program_id = :programId, applications_count = :applicationsCount, interaction_id = :interactionId,
                    stream_no = :streamNo, payload_hash = :payloadHash,
                    sync_run_id = COALESCE(:runId, sync_run_id), updated_at = :now
                WHERE id = :id
                """)
                .param("id", id)
                .param("externalUpdatedAt", item.updatedAt())
                .param("submittedAt", item.submittedAt())
                .param("externalStatus", item.externalStatus())
                .param("payload", item.payload())
                .param("streamNo", item.streamNo())
                .param("payloadHash", item.payloadHash())
                .params(resultParameters(result))
                .param("runId", runId)
                .param("now", now)
                .update();
    }

    Optional<SourceOrganization> findMappedOrganization(SourceCode source, String externalKey) {
        return jdbcClient.sql("""
                SELECT o.id, o.name, o.owner_manager_id
                FROM source_mappings m
                JOIN organizations o ON o.id = m.organization_id
                WHERE m.source = :source AND m.kind = 'ORGANIZATION' AND m.external_key = :externalKey
                """)
                .param("source", source.name())
                .param("externalKey", externalKey)
                .query(this::mapOrganization)
                .optional();
    }

    Optional<UUID> findMappedProgramId(SourceCode source, String externalKey) {
        return jdbcClient.sql("""
                SELECT m.program_id
                FROM source_mappings m
                JOIN programs p ON p.id = m.program_id
                WHERE m.source = :source AND m.kind = 'PROGRAM' AND m.external_key = :externalKey AND p.archived = FALSE
                """)
                .param("source", source.name())
                .param("externalKey", externalKey)
                .query(UUID.class)
                .optional();
    }

    List<MappedTarget> findLearningTargets(SourceCode source, String kind, String externalKey) {
        return jdbcClient.sql(TARGET_SELECT + """
                WHERE m.source = :source AND m.kind = :kind AND m.external_key = :externalKey AND p.archived = FALSE
                ORDER BY m.run_starts_on NULLS FIRST, m.id
                """)
                .param("source", source.name())
                .param("kind", kind)
                .param("externalKey", externalKey)
                .query(this::mapTarget)
                .list();
    }

    List<StoredMapping> findMappings(SourceCode source, String kind, String externalKey) {
        return jdbcClient.sql(MAPPING_SELECT + """
                WHERE source = :source AND kind = :kind AND external_key = :externalKey
                ORDER BY run_starts_on NULLS FIRST, id
                """)
                .param("source", source.name())
                .param("kind", kind)
                .param("externalKey", externalKey)
                .query(this::mapMapping)
                .list();
    }

    Optional<StoredMapping> findMapping(UUID id) {
        return jdbcClient.sql(MAPPING_SELECT + "WHERE id = :id")
                .param("id", id)
                .query(this::mapMapping)
                .optional();
    }

    UUID insertMapping(SourceCode source, String kind, String externalKey, UUID organizationId, UUID programId, RunDates run,
                       RunKind runKind, UUID actorProfileId, OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        Map<String, Object> parameters = mappingParameters(organizationId, programId, run, runKind, actorProfileId, now);
        parameters.put("id", id);
        parameters.put("source", source.name());
        parameters.put("kind", kind);
        parameters.put("externalKey", externalKey);
        jdbcClient.sql("""
                INSERT INTO source_mappings (
                    id, source, kind, external_key, organization_id, program_id, run_starts_on, run_ends_on, run_kind,
                    version, created_by, created_at, updated_at
                ) VALUES (
                    :id, :source, :kind, :externalKey, :organizationId, :programId, :runStartsOn, :runEndsOn, :runKind,
                    0, :actorProfileId, :now, :now
                )
                """)
                .params(parameters)
                .update();
        return id;
    }

    boolean updateMapping(UUID id, int expectedVersion, UUID organizationId, UUID programId, RunDates run, RunKind runKind,
                          UUID actorProfileId, OffsetDateTime now) {
        Map<String, Object> parameters = mappingParameters(organizationId, programId, run, runKind, actorProfileId, now);
        parameters.put("id", id);
        parameters.put("expectedVersion", expectedVersion);
        return jdbcClient.sql("""
                UPDATE source_mappings
                SET organization_id = :organizationId, program_id = :programId, run_starts_on = :runStartsOn,
                    run_ends_on = :runEndsOn, run_kind = :runKind, version = version + 1, created_by = :actorProfileId,
                    updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """)
                .params(parameters)
                .update() == 1;
    }

    void deleteMapping(UUID id) {
        jdbcClient.sql("DELETE FROM learning_observations WHERE mapping_id = :id").param("id", id).update();
        jdbcClient.sql("DELETE FROM learning_snapshots WHERE mapping_id = :id").param("id", id).update();
        jdbcClient.sql("DELETE FROM source_mappings WHERE id = :id").param("id", id).update();
    }

    List<SourceMappingView> findMappingViews(LocalDate today, OffsetDateTime lastFullSyncStartedAt) {
        return jdbcClient.sql("""
                SELECT m.id, m.source, m.kind, m.external_key, m.organization_id, o.name AS organization_name, m.program_id,
                       p.name AS program_name, m.run_starts_on, m.run_ends_on, m.run_kind, m.version, a.display_name,
                       m.updated_at, s.participants_count, s.observed_at,
                       (SELECT r.payload FROM source_records r
                        WHERE r.source = 'MOODLE' AND r.external_id = m.external_key
                          AND r.record_type = CASE WHEN m.kind = 'GROUP' THEN 'moodle_group' ELSE 'moodle_course' END) AS payload
                FROM source_mappings m
                LEFT JOIN organizations o ON o.id = m.organization_id
                LEFT JOIN programs p ON p.id = m.program_id
                LEFT JOIN crm_user_profiles a ON a.id = m.created_by
                LEFT JOIN learning_snapshots s ON s.mapping_id = m.id
                ORDER BY m.source, m.kind, m.external_key, m.run_starts_on NULLS FIRST, m.id
                """)
                .query((resultSet, rowNumber) -> {
                    LocalDate endsOn = resultSet.getObject("run_ends_on", LocalDate.class);
                    OffsetDateTime observedAt = resultSet.getObject("observed_at", OffsetDateTime.class);
                    boolean closed = endsOn != null && !today.isBefore(endsOn);
                    return new SourceMappingView(
                            resultSet.getObject("id", UUID.class),
                            SourceCode.valueOf(resultSet.getString("source")),
                            resultSet.getString("kind"),
                            resultSet.getString("external_key"),
                            mappingLabel(resultSet.getString("kind"), resultSet.getString("external_key"),
                                    resultSet.getString("payload")),
                            resultSet.getObject("organization_id", UUID.class),
                            resultSet.getString("organization_name"),
                            resultSet.getObject("program_id", UUID.class),
                            resultSet.getString("program_name"),
                            resultSet.getObject("run_starts_on", LocalDate.class),
                            endsOn,
                            RunKind.valueOf(resultSet.getString("run_kind")),
                            resultSet.getInt("version"),
                            resultSet.getString("display_name"),
                            resultSet.getObject("updated_at", OffsetDateTime.class),
                            resultSet.getObject("participants_count", Integer.class),
                            observedAt,
                            closed,
                            observedAt != null && !closed && lastFullSyncStartedAt != null
                                    && observedAt.isBefore(lastFullSyncStartedAt)
                    );
                })
                .list();
    }

    Optional<UUID> findCycleInteraction(UUID organizationId, UUID programId, LocalDate runStartsOn) {
        return jdbcClient.sql("""
                SELECT i.id
                FROM interactions i
                LEFT JOIN interaction_cycles cycle_start ON cycle_start.interaction_id = i.id
                LEFT JOIN interaction_cycles cycle_next ON cycle_next.previous_interaction_id = i.id
                WHERE i.organization_id = :organizationId AND i.program_id = :programId
                  AND (cycle_start.starts_on IS NULL OR :runStartsOn >= cycle_start.starts_on)
                  AND (cycle_next.starts_on IS NULL OR :runStartsOn < cycle_next.starts_on)
                ORDER BY CASE WHEN cycle_start.interaction_id IS NULL AND cycle_next.interaction_id IS NULL THEN 1 ELSE 0 END,
                         i.updated_at DESC, i.id
                LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("programId", programId)
                .param("runStartsOn", runStartsOn)
                .query(UUID.class)
                .optional();
    }

    Optional<UUID> findAppliedInteraction(List<String> keys, UUID organizationId) {
        for (String key : keys) {
            Optional<UUID> interactionId = jdbcClient.sql("""
                    SELECT c.result_json
                    FROM command_idempotency_records c
                    WHERE c.operation = 'APPLY_SOURCE_RECORD' AND c.idempotency_key = :key AND c.result_json IS NOT NULL
                    """)
                    .param("key", key)
                    .query(String.class)
                    .list()
                    .stream()
                    .map(SourceRepository::interactionIdOf)
                    .flatMap(Optional::stream)
                    .filter(id -> interactionBelongsTo(id, organizationId))
                    .findFirst();
            if (interactionId.isPresent()) {
                return interactionId;
            }
        }
        return Optional.empty();
    }

    boolean hasMapping(SourceCode source, String kind, String externalKeyPattern) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM source_mappings
                WHERE source = :source AND kind = :kind AND external_key LIKE :pattern
                """)
                .param("source", source.name())
                .param("kind", kind)
                .param("pattern", externalKeyPattern)
                .query(Long.class)
                .single() > 0;
    }

    Optional<StoredSnapshot> findSnapshot(UUID mappingId) {
        return jdbcClient.sql(SNAPSHOT_SELECT + "WHERE s.mapping_id = :mappingId")
                .param("mappingId", mappingId)
                .query(this::mapSnapshot)
                .optional();
    }

    boolean hasSnapshotForRecord(UUID sourceRecordId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM learning_snapshots WHERE source_record_id = :id")
                .param("id", sourceRecordId)
                .query(Long.class)
                .single() > 0;
    }

    int deleteSnapshot(UUID mappingId) {
        jdbcClient.sql("DELETE FROM learning_observations WHERE mapping_id = :mappingId").param("mappingId", mappingId).update();
        return jdbcClient.sql("DELETE FROM learning_snapshots WHERE mapping_id = :mappingId")
                .param("mappingId", mappingId)
                .update();
    }

    void moveSnapshot(UUID mappingId, UUID organizationId, UUID programId) {
        jdbcClient.sql("""
                UPDATE learning_snapshots SET organization_id = :organizationId, program_id = :programId, interaction_id = NULL
                WHERE mapping_id = :mappingId
                """)
                .param("mappingId", mappingId)
                .param("organizationId", organizationId)
                .param("programId", programId)
                .update();
    }

    void saveSnapshot(UUID sourceRecordId, LearningUnit unit, MappedTarget target, OffsetDateTime observedAt,
                      OffsetDateTime changedAt, UUID runId, UUID interactionId) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("mappingId", target.mappingId());
        parameters.put("interactionId", interactionId);
        parameters.put("id", sourceRecordId);
        parameters.put("organizationId", target.organizationId());
        parameters.put("programId", target.programId());
        parameters.put("courseId", unit.courseId());
        parameters.put("groupId", unit.groupId());
        parameters.put("courseName", unit.courseName());
        parameters.put("groupName", unit.groupName());
        parameters.put("participants", unit.participants());
        parameters.put("teachers", unit.teachers());
        parameters.put("completed", unit.completed());
        parameters.put("notCompleted", unit.notCompleted());
        parameters.put("unknown", unit.unknown());
        parameters.put("groupsCount", unit.groupsCount());
        parameters.put("observedAt", observedAt);
        parameters.put("changedAt", changedAt);
        parameters.put("runId", runId);
        int updated = jdbcClient.sql("""
                UPDATE learning_snapshots
                SET source_record_id = :id, organization_id = :organizationId, program_id = :programId, course_id = :courseId,
                    group_id = :groupId, course_name = :courseName, group_name = :groupName, participants_count = :participants,
                    teachers_count = :teachers, completed_count = :completed, not_completed_count = :notCompleted,
                    unknown_count = :unknown, groups_count = :groupsCount, observed_at = :observedAt,
                    changed_at = :changedAt, sync_run_id = COALESCE(:runId, sync_run_id),
                    interaction_id = COALESCE(:interactionId, interaction_id)
                WHERE mapping_id = :mappingId
                """)
                .params(parameters)
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO learning_snapshots (
                        mapping_id, source_record_id, organization_id, program_id, course_id, group_id, course_name,
                        group_name, participants_count, teachers_count, completed_count, not_completed_count, unknown_count,
                        groups_count, observed_at, changed_at, sync_run_id, interaction_id
                    ) VALUES (
                        :mappingId, :id, :organizationId, :programId, :courseId, :groupId, :courseName,
                        :groupName, :participants, :teachers, :completed, :notCompleted, :unknown,
                        :groupsCount, :observedAt, :changedAt, :runId, :interactionId
                    )
                    """)
                    .params(parameters)
                    .update();
        }
        saveObservation(parameters, observedAt.isAfter(changedAt) ? observedAt : changedAt, false);
    }

    Optional<OffsetDateTime> findFirstObservation(UUID mappingId) {
        return jdbcClient.sql("SELECT MIN(observed_from) FROM learning_observations WHERE mapping_id = :mappingId")
                .param("mappingId", mappingId)
                .query((resultSet, rowNumber) -> resultSet.getObject(1, OffsetDateTime.class))
                .optional();
    }

    boolean hasDemoObservations(UUID mappingId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM learning_observations WHERE mapping_id = :mappingId AND demo = TRUE")
                .param("mappingId", mappingId)
                .query(Long.class)
                .single() > 0;
    }

    void saveDemoObservation(UUID mappingId, LearningUnit counts, OffsetDateTime observedFrom, OffsetDateTime confirmedAt) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("mappingId", mappingId);
        parameters.put("changedAt", observedFrom);
        parameters.put("participants", counts.participants());
        parameters.put("teachers", counts.teachers());
        parameters.put("completed", counts.completed());
        parameters.put("notCompleted", counts.notCompleted());
        parameters.put("unknown", counts.unknown());
        parameters.put("groupsCount", counts.groupsCount());
        saveObservation(parameters, confirmedAt, true);
    }

    void moveDemoRunStart(UUID mappingId, LocalDate startsOn, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE source_mappings SET run_starts_on = :startsOn, version = version + 1, updated_at = :now
                WHERE id = :mappingId AND run_starts_on > :startsOn
                """)
                .param("mappingId", mappingId)
                .param("startsOn", startsOn)
                .param("now", now)
                .update();
    }

    private void saveObservation(Map<String, Object> parameters, OffsetDateTime confirmedAt, boolean demo) {
        parameters.put("confirmedAt", confirmedAt);
        parameters.put("demo", demo);
        int confirmed = jdbcClient.sql("""
                UPDATE learning_observations
                SET participants_count = :participants, teachers_count = :teachers, completed_count = :completed,
                    not_completed_count = :notCompleted, unknown_count = :unknown, groups_count = :groupsCount,
                    confirmed_at = CASE WHEN confirmed_at > :confirmedAt THEN confirmed_at ELSE :confirmedAt END
                WHERE mapping_id = :mappingId AND observed_from = :changedAt
                """)
                .params(parameters)
                .update();
        if (confirmed == 0) {
            jdbcClient.sql("""
                    INSERT INTO learning_observations (
                        mapping_id, observed_from, confirmed_at, participants_count, teachers_count, completed_count,
                        not_completed_count, unknown_count, groups_count, demo
                    ) VALUES (
                        :mappingId, :changedAt, :confirmedAt, :participants, :teachers, :completed,
                        :notCompleted, :unknown, :groupsCount, :demo
                    )
                    """)
                    .params(parameters)
                    .update();
        }
    }

    boolean interactionVisible(UUID interactionId, VisibilityScope scope) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM interactions
                WHERE id = :interactionId AND organization_id IN (SELECT id FROM organizations WHERE %s)
                """.formatted(scope.condition()))
                .param("interactionId", interactionId)
                .params(scope.parameters())
                .query(Long.class)
                .single() > 0;
    }

    List<StoredSnapshot> findInteractionSnapshots(UUID interactionId, VisibilityScope scope) {
        return jdbcClient.sql(SNAPSHOT_SELECT + """
                JOIN interactions i ON i.organization_id = s.organization_id AND i.program_id = s.program_id
                %s
                WHERE i.id = :interactionId AND i.organization_id IN (SELECT id FROM organizations WHERE %s)
                  AND %s
                ORDER BY m.run_kind, s.course_name, s.course_id, s.group_id NULLS FIRST, s.group_name, m.run_starts_on
                """.formatted(CYCLE_WINDOW, scope.condition(), IN_CYCLE_WINDOW))
                .param("interactionId", interactionId)
                .params(scope.parameters())
                .query(this::mapSnapshot)
                .list();
    }

    LearningCoverage findLearningCoverage(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT COUNT(m.id) AS mapped,
                       COALESCE(SUM(CASE WHEN %s THEN 1 ELSE 0 END), 0) AS in_cycle,
                       COALESCE(SUM(CASE WHEN m.run_starts_on IS NULL THEN 1 ELSE 0 END), 0) AS undated,
                       MAX(CASE WHEN r.status = 'SKIPPED' AND r.error IS NOT NULL THEN r.error END) AS skipped_reason
                FROM interactions i
                %s
                JOIN source_mappings m ON m.source = 'MOODLE' AND m.kind IN ('COURSE', 'GROUP')
                    AND m.organization_id = i.organization_id AND m.program_id = i.program_id
                LEFT JOIN source_records r ON r.source = 'MOODLE' AND r.external_id = m.external_key
                    AND r.record_type = CASE WHEN m.kind = 'GROUP' THEN 'moodle_group' ELSE 'moodle_course' END
                WHERE i.id = :interactionId
                """.formatted(IN_CYCLE_WINDOW, CYCLE_WINDOW))
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> new LearningCoverage(
                        resultSet.getInt("mapped"),
                        resultSet.getInt("in_cycle"),
                        resultSet.getInt("undated"),
                        resultSet.getString("skipped_reason")
                ))
                .single();
    }

    void saveMapping(SourceCode source, String kind, String externalKey, UUID organizationId, UUID programId,
                     RunDates run, UUID actorProfileId, OffsetDateTime now) {
        saveMapping(source, kind, externalKey, organizationId, programId, run, null, actorProfileId, now);
    }

    void saveMapping(SourceCode source, String kind, String externalKey, UUID organizationId, UUID programId,
                     RunDates run, RunKind runKind, UUID actorProfileId, OffsetDateTime now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("source", source.name());
        parameters.put("kind", kind);
        parameters.put("externalKey", externalKey);
        parameters.put("organizationId", organizationId);
        parameters.put("programId", programId);
        parameters.put("runStartsOn", run == null ? null : run.startsOn());
        parameters.put("runEndsOn", run == null ? null : run.endsOn());
        parameters.put("runKind", (runKind == null ? RunKind.STUDENTS : runKind).name());
        parameters.put("actorProfileId", actorProfileId);
        parameters.put("now", now);
        int updated = jdbcClient.sql("""
                UPDATE source_mappings
                SET organization_id = :organizationId, program_id = :programId, run_starts_on = :runStartsOn,
                    run_ends_on = :runEndsOn, run_kind = :runKind, version = version + 1, created_by = :actorProfileId,
                    updated_at = :now
                WHERE source = :source AND kind = :kind AND external_key = :externalKey
                """)
                .params(parameters)
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO source_mappings (
                        id, source, kind, external_key, organization_id, program_id, run_starts_on, run_ends_on, run_kind,
                        created_by, created_at, updated_at
                    ) VALUES (
                        :id, :source, :kind, :externalKey, :organizationId, :programId, :runStartsOn, :runEndsOn, :runKind,
                        :actorProfileId, :now, :now
                    )
                    """)
                    .params(parameters)
                    .param("id", UUID.randomUUID())
                    .update();
        }
    }

    void confirmUndatedRun(SourceCode source, String kind, String externalKey, RunDates run, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE source_mappings
                SET run_starts_on = :runStartsOn, run_ends_on = :runEndsOn, updated_at = :now
                WHERE source = :source AND kind = :kind AND external_key = :externalKey AND run_starts_on IS NULL
                """)
                .param("source", source.name())
                .param("kind", kind)
                .param("externalKey", externalKey)
                .param("runStartsOn", run.startsOn())
                .param("runEndsOn", run.endsOn())
                .param("now", now)
                .update();
    }

    Optional<SourceOrganization> findOrganizationByExternalKey(String externalKey) {
        return jdbcClient.sql(ORGANIZATION_SELECT + "WHERE external_key = :externalKey")
                .param("externalKey", externalKey)
                .query(this::mapOrganization)
                .optional();
    }

    Optional<SourceOrganization> findOrganizationByName(String name) {
        return jdbcClient.sql(ORGANIZATION_SELECT + "WHERE name = :name")
                .param("name", name)
                .query(this::mapOrganization)
                .optional();
    }

    Optional<SourceOrganization> findOrganizationById(UUID id) {
        return jdbcClient.sql(ORGANIZATION_SELECT + "WHERE id = :id")
                .param("id", id)
                .query(this::mapOrganization)
                .optional();
    }

    List<UUID> findActiveProgramIdsByNormalizedName(String name) {
        String key = CatalogNames.normalized(name);
        return jdbcClient.sql("SELECT id, name FROM programs WHERE archived = FALSE ORDER BY id")
                .query((resultSet, rowNumber) -> new SourceMappingOption(
                        resultSet.getObject("id", UUID.class), resultSet.getString("name")
                ))
                .list()
                .stream()
                .filter(program -> CatalogNames.normalized(program.name()).equals(key))
                .map(SourceMappingOption::id)
                .toList();
    }

    List<UUID> findActiveProgramIdsByName(String name) {
        return jdbcClient.sql("SELECT id FROM programs WHERE name = :name AND archived = FALSE")
                .param("name", name)
                .query(UUID.class)
                .list();
    }

    boolean activeProgramExists(UUID id) {
        return jdbcClient.sql("SELECT COUNT(*) FROM programs WHERE id = :id AND archived = FALSE")
                .param("id", id)
                .query(Long.class)
                .single() > 0;
    }

    List<UUID> findActiveProductIdsByName(String name) {
        return jdbcClient.sql("SELECT id FROM products WHERE name = :name AND archived = FALSE")
                .param("name", name)
                .query(UUID.class)
                .list();
    }

    Optional<UUID> findLatestInteraction(UUID organizationId, UUID programId, UUID productId) {
        String condition;
        if (programId != null) {
            condition = "i.program_id = :programId";
        } else if (productId != null) {
            condition = "EXISTS (SELECT 1 FROM product_agreements pa WHERE pa.interaction_id = i.id AND pa.product_id = :productId)";
        } else {
            condition = "i.program_id IS NULL AND NOT EXISTS (SELECT 1 FROM product_agreements pa WHERE pa.interaction_id = i.id)";
        }
        return jdbcClient.sql("""
                SELECT i.id
                FROM interactions i
                WHERE i.organization_id = :organizationId AND %s
                ORDER BY i.updated_at DESC, i.id
                LIMIT 1
                """.formatted(condition))
                .param("organizationId", organizationId)
                .param("programId", programId)
                .param("productId", productId)
                .query(UUID.class)
                .optional();
    }

    Optional<UUID> findScheduleActor(SourceCode source) {
        return jdbcClient.sql("""
                SELECT r.started_by
                FROM sync_runs r
                JOIN crm_user_profiles p ON p.id = r.started_by
                WHERE r.source = :source AND p.role = 'ADMIN' AND p.active = TRUE
                ORDER BY r.created_at DESC, r.id DESC
                LIMIT 1
                """)
                .param("source", source.name())
                .query(UUID.class)
                .optional();
    }

    Optional<MappedTarget> findInteractionProgramTarget(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT i.organization_id, o.owner_manager_id, i.program_id
                FROM interactions i
                JOIN organizations o ON o.id = i.organization_id
                WHERE i.id = :interactionId AND i.program_id IS NOT NULL
                """)
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> new MappedTarget(
                        null,
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getObject("owner_manager_id", UUID.class),
                        resultSet.getObject("program_id", UUID.class),
                        null,
                        null,
                        RunKind.STUDENTS
                ))
                .optional();
    }

    Optional<UUID> findInteractionOrganizationId(UUID interactionId) {
        return jdbcClient.sql("SELECT organization_id FROM interactions WHERE id = :id")
                .param("id", interactionId)
                .query(UUID.class)
                .optional();
    }

    boolean teamExists(UUID teamId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM teams WHERE id = :id")
                .param("id", teamId)
                .query(Long.class)
                .single() > 0;
    }

    boolean organizationNameTaken(String name) {
        return jdbcClient.sql("SELECT COUNT(*) FROM organizations WHERE LOWER(name) = LOWER(:name)")
                .param("name", name)
                .query(Long.class)
                .single() > 0;
    }

    void insertOrganization(UUID id, String name, String type, UUID teamId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO organizations (id, name, type, team_id, owner_manager_id, version, created_at, updated_at)
                VALUES (:id, :name, :type, :teamId, NULL, 0, :now, :now)
                """)
                .param("id", id)
                .param("name", name)
                .param("type", type)
                .param("teamId", teamId)
                .param("now", now)
                .update();
    }

    List<String> findLearningMappingKeys(UUID organizationId, UUID programId) {
        return jdbcClient.sql("""
                SELECT external_key
                FROM source_mappings
                WHERE source = 'MOODLE' AND kind IN ('COURSE', 'GROUP')
                  AND organization_id = :organizationId AND program_id = :programId
                ORDER BY external_key
                """)
                .param("organizationId", organizationId)
                .param("programId", programId)
                .query(String.class)
                .list();
    }

    boolean interactionBelongsTo(UUID interactionId, UUID organizationId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM interactions WHERE id = :id AND organization_id = :organizationId")
                .param("id", interactionId)
                .param("organizationId", organizationId)
                .query(Long.class)
                .single() > 0;
    }

    List<SourceMappingOption> findOrganizationOptions() {
        return jdbcClient.sql("SELECT id, name FROM organizations ORDER BY name, id")
                .query((resultSet, rowNumber) -> new SourceMappingOption(
                        resultSet.getObject("id", UUID.class), resultSet.getString("name")
                ))
                .list();
    }

    List<SourceMappingOption> findProgramOptions() {
        return jdbcClient.sql("""
                SELECT p.id, p.name, d.name AS direction_name
                FROM programs p
                JOIN directions d ON d.id = p.direction_id
                WHERE p.archived = FALSE
                ORDER BY p.name, d.name, p.id
                """)
                .query((resultSet, rowNumber) -> new SourceMappingOption(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name") + " (" + resultSet.getString("direction_name") + ")"
                ))
                .list();
    }

    private static Map<String, Object> mappingParameters(UUID organizationId, UUID programId, RunDates run, RunKind runKind,
                                                         UUID actorProfileId, OffsetDateTime now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("organizationId", organizationId);
        parameters.put("programId", programId);
        parameters.put("runStartsOn", run == null ? null : run.startsOn());
        parameters.put("runEndsOn", run == null ? null : run.endsOn());
        parameters.put("runKind", (runKind == null ? RunKind.STUDENTS : runKind).name());
        parameters.put("actorProfileId", actorProfileId);
        parameters.put("now", now);
        return parameters;
    }

    private static String mappingLabel(String kind, String externalKey, String payload) {
        if (payload != null) {
            return LearningUnit.parseStored(PAYLOADS, payload).label();
        }
        String value = externalKey.substring(externalKey.indexOf(':') + 1);
        return switch (kind) {
            case "ORGANIZATION" -> externalKey.startsWith("id:") ? "Вуз на сайте, внешний ID " + value : "Вуз на сайте «" + value + "»";
            case "PROGRAM" -> "Программа на сайте «" + value + "»";
            case "GROUP" -> "Группа Moodle " + externalKey;
            default -> "Курс Moodle " + externalKey;
        };
    }

    private static Optional<UUID> interactionIdOf(String resultJson) {
        Matcher matcher = INTERACTION_ID.matcher(resultJson);
        return matcher.find() ? Optional.of(UUID.fromString(matcher.group(1))) : Optional.empty();
    }

    private static Map<String, Object> resultParameters(ApplyResult result) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("status", result.status().name());
        parameters.put("error", limitError(result.error()));
        parameters.put("organizationId", result.organizationId());
        parameters.put("programId", result.programId());
        parameters.put("applicationsCount", result.applicationsCount());
        parameters.put("interactionId", result.interactionId());
        return parameters;
    }

    private static String limitError(String error) {
        return error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH - 1) + "…";
    }

    private SyncRunView mapRun(ResultSet resultSet) throws SQLException {
        return new SyncRunView(
                resultSet.getObject("id", UUID.class),
                SourceCode.valueOf(resultSet.getString("source")),
                SyncRunStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("updated_since", OffsetDateTime.class),
                resultSet.getInt("fetched_count"),
                resultSet.getInt("created_count"),
                resultSet.getInt("updated_count"),
                resultSet.getInt("skipped_count"),
                resultSet.getInt("needs_mapping_count"),
                resultSet.getInt("failed_count"),
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("started_at", OffsetDateTime.class),
                resultSet.getObject("finished_at", OffsetDateTime.class),
                SyncTrigger.valueOf(resultSet.getString("run_trigger")),
                resultSet.getString("started_by_name"),
                resultSet.getString("organization_name")
        );
    }

    private MappedTarget mapTarget(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MappedTarget(
                resultSet.getObject("mapping_id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("owner_manager_id", UUID.class),
                resultSet.getObject("program_id", UUID.class),
                resultSet.getObject("run_starts_on", LocalDate.class),
                resultSet.getObject("run_ends_on", LocalDate.class),
                RunKind.valueOf(resultSet.getString("run_kind"))
        );
    }

    private StoredMapping mapMapping(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredMapping(
                resultSet.getObject("id", UUID.class),
                SourceCode.valueOf(resultSet.getString("source")),
                resultSet.getString("kind"),
                resultSet.getString("external_key"),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("program_id", UUID.class),
                resultSet.getObject("run_starts_on", LocalDate.class),
                resultSet.getObject("run_ends_on", LocalDate.class),
                RunKind.valueOf(resultSet.getString("run_kind")),
                resultSet.getInt("version")
        );
    }

    private StoredRecord mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredRecord(
                resultSet.getObject("id", UUID.class),
                SourceCode.valueOf(resultSet.getString("source")),
                resultSet.getString("record_type"),
                resultSet.getString("external_id"),
                resultSet.getObject("external_updated_at", OffsetDateTime.class),
                resultSet.getString("external_status"),
                resultSet.getString("payload"),
                SourceRecordStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("error"),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("program_id", UUID.class),
                resultSet.getObject("interaction_id", UUID.class),
                resultSet.getObject("updated_at", OffsetDateTime.class),
                resultSet.getObject("stream_no", Integer.class),
                resultSet.getString("payload_hash")
        );
    }

    private StoredSnapshot mapSnapshot(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredSnapshot(
                resultSet.getObject("mapping_id", UUID.class),
                resultSet.getObject("source_record_id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("program_id", UUID.class),
                new LearningUnit(
                        resultSet.getLong("course_id"),
                        resultSet.getObject("group_id", Long.class),
                        null,
                        resultSet.getString("course_name"),
                        resultSet.getString("group_name"),
                        resultSet.getInt("participants_count"),
                        resultSet.getInt("teachers_count"),
                        resultSet.getObject("completed_count", Integer.class),
                        resultSet.getObject("not_completed_count", Integer.class),
                        resultSet.getInt("unknown_count"),
                        resultSet.getInt("groups_count")
                ),
                resultSet.getObject("observed_at", OffsetDateTime.class),
                resultSet.getObject("changed_at", OffsetDateTime.class),
                resultSet.getObject("run_starts_on", LocalDate.class),
                resultSet.getObject("run_ends_on", LocalDate.class),
                RunKind.valueOf(resultSet.getString("run_kind")),
                resultSet.getObject("interaction_id", UUID.class)
        );
    }

    private SourceOrganization mapOrganization(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SourceOrganization(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getObject("owner_manager_id", UUID.class)
        );
    }

    record StoredRun(SyncRunView run, UUID startedBy) {
    }

    record StoredRecord(
            UUID id,
            SourceCode source,
            String recordType,
            String externalId,
            OffsetDateTime externalUpdatedAt,
            String externalStatus,
            String payload,
            SourceRecordStatus status,
            String error,
            UUID organizationId,
            UUID programId,
            UUID interactionId,
            OffsetDateTime updatedAt,
            Integer streamNo,
            String payloadHash
    ) {
    }

    record SourceOrganization(UUID id, String name, UUID ownerManagerId) {
    }

    record RecordVersion(
            String type,
            String externalId,
            OffsetDateTime updatedAt,
            OffsetDateTime submittedAt,
            String externalStatus,
            String payload,
            Integer streamNo,
            String payloadHash
    ) {
    }

    record MappedTarget(
            UUID mappingId,
            UUID organizationId,
            UUID ownerManagerId,
            UUID programId,
            LocalDate runStartsOn,
            LocalDate runEndsOn,
            RunKind runKind
    ) {
    }

    record StoredMapping(
            UUID id,
            SourceCode source,
            String kind,
            String externalKey,
            UUID organizationId,
            UUID programId,
            LocalDate runStartsOn,
            LocalDate runEndsOn,
            RunKind runKind,
            int version
    ) {
        boolean learning() {
            return kind.equals("COURSE") || kind.equals("GROUP");
        }

        RunDates run() {
            return runStartsOn == null ? null : new RunDates(runStartsOn, runEndsOn);
        }
    }

    record RunDates(LocalDate startsOn, LocalDate endsOn) {
        boolean overlaps(RunDates other) {
            return startsOn.isBefore(other.endsOn()) && other.startsOn().isBefore(endsOn);
        }
    }

    record StoredSnapshot(
            UUID mappingId,
            UUID sourceRecordId,
            UUID organizationId,
            UUID programId,
            LearningUnit unit,
            OffsetDateTime observedAt,
            OffsetDateTime changedAt,
            LocalDate runStartsOn,
            LocalDate runEndsOn,
            RunKind runKind,
            UUID interactionId
    ) {
    }

    record LearningCoverage(int mapped, int inCycle, int undated, String skippedReason) {
    }

    record ApplyResult(
            SourceRecordStatus status,
            String error,
            UUID organizationId,
            UUID programId,
            int applicationsCount,
            UUID interactionId
    ) {
    }

    record SyncTotals(int fetched, int created, int updated, int skipped, int needsMapping, int failed) {
    }
}
