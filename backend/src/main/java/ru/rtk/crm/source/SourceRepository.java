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

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;

@Repository
public class SourceRepository {
    private static final int MAX_ERROR_LENGTH = 500;
    private static final String RUN_SELECT = """
            SELECT id, source, status, updated_since, fetched_count, created_count, updated_count, skipped_count,
                   needs_mapping_count, failed_count, error_code, error_message, created_at, started_at, finished_at,
                   started_by
            FROM sync_runs
            """;

    private static final String RECORD_SELECT = """
            SELECT id, source, record_type, external_id, external_updated_at, external_status, payload, status, error,
                   organization_id, program_id, interaction_id, updated_at
            FROM source_records
            """;

    private static final String ORGANIZATION_SELECT = "SELECT id, name, owner_manager_id FROM organizations ";

    private static final String SNAPSHOT_SELECT = """
            SELECT s.source_record_id, s.organization_id, s.program_id, s.course_id, s.group_id, s.course_name, s.group_name,
                   s.participants_count, s.teachers_count, s.completed_count, s.not_completed_count, s.unknown_count,
                   s.groups_count, s.observed_at, s.changed_at, m.run_starts_on, m.run_ends_on
            FROM learning_snapshots s
            JOIN source_records r ON r.id = s.source_record_id
            LEFT JOIN source_mappings m ON m.source = r.source AND m.external_key = r.external_id
                AND m.kind = CASE WHEN s.group_id IS NULL THEN 'COURSE' ELSE 'GROUP' END
            """;

    private final JdbcClient jdbcClient;

    public SourceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    void insertRun(UUID id, SourceCode source, UUID startedBy, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO sync_runs (id, source, status, started_by, created_at)
                VALUES (:id, :source, 'PENDING', :startedBy, :createdAt)
                """)
                .param("id", id)
                .param("source", source.name())
                .param("startedBy", startedBy)
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
        return jdbcClient.sql(RUN_SELECT + "WHERE id = :id")
                .param("id", id)
                .query((resultSet, rowNumber) -> new StoredRun(mapRun(resultSet), resultSet.getObject("started_by", UUID.class)))
                .optional();
    }

    Optional<SyncRunView> findLatestRun(SourceCode source) {
        return jdbcClient.sql(RUN_SELECT + "WHERE source = :source ORDER BY created_at DESC, id DESC LIMIT 1")
                .param("source", source.name())
                .query((resultSet, rowNumber) -> mapRun(resultSet))
                .optional();
    }

    Optional<OffsetDateTime> findLastSuccessAt(SourceCode source) {
        return jdbcClient.sql("SELECT MAX(finished_at) FROM sync_runs WHERE source = :source AND status = 'SUCCEEDED'")
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
                    created_at, updated_at
                ) VALUES (
                    :id, :source, :recordType, :externalId, :externalUpdatedAt, :submittedAt, :externalStatus, :payload,
                    :status, :error, :organizationId, :programId, :applicationsCount, :interactionId, :runId,
                    :now, :now
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
                .params(resultParameters(result))
                .param("runId", runId)
                .param("now", now)
                .update();
    }

    void updateRecord(UUID id, RecordVersion item, ApplyResult result, UUID runId, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE source_records
                SET external_updated_at = :externalUpdatedAt, submitted_at = :submittedAt, external_status = :externalStatus,
                    payload = :payload, status = :status, error = :error, organization_id = :organizationId,
                    program_id = :programId, applications_count = :applicationsCount, interaction_id = :interactionId,
                    sync_run_id = COALESCE(:runId, sync_run_id), updated_at = :now
                WHERE id = :id
                """)
                .param("id", id)
                .param("externalUpdatedAt", item.updatedAt())
                .param("submittedAt", item.submittedAt())
                .param("externalStatus", item.externalStatus())
                .param("payload", item.payload())
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

    Optional<MappedTarget> findMappedTarget(SourceCode source, String kind, String externalKey) {
        return jdbcClient.sql("""
                SELECT o.id AS organization_id, o.owner_manager_id, p.id AS program_id, m.run_starts_on, m.run_ends_on
                FROM source_mappings m
                JOIN organizations o ON o.id = m.organization_id
                JOIN programs p ON p.id = m.program_id
                WHERE m.source = :source AND m.kind = :kind AND m.external_key = :externalKey AND p.archived = FALSE
                """)
                .param("source", source.name())
                .param("kind", kind)
                .param("externalKey", externalKey)
                .query((resultSet, rowNumber) -> new MappedTarget(
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getObject("owner_manager_id", UUID.class),
                        resultSet.getObject("program_id", UUID.class),
                        resultSet.getObject("run_starts_on", LocalDate.class),
                        resultSet.getObject("run_ends_on", LocalDate.class)
                ))
                .optional();
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

    Optional<StoredSnapshot> findSnapshot(UUID sourceRecordId) {
        return jdbcClient.sql(SNAPSHOT_SELECT + "WHERE s.source_record_id = :id")
                .param("id", sourceRecordId)
                .query(this::mapSnapshot)
                .optional();
    }

    void saveSnapshot(UUID sourceRecordId, LearningUnit unit, MappedTarget target, OffsetDateTime observedAt,
                      OffsetDateTime changedAt, UUID runId) {
        Map<String, Object> parameters = new HashMap<>();
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
                SET organization_id = :organizationId, program_id = :programId, course_id = :courseId, group_id = :groupId,
                    course_name = :courseName, group_name = :groupName, participants_count = :participants,
                    teachers_count = :teachers, completed_count = :completed, not_completed_count = :notCompleted,
                    unknown_count = :unknown, groups_count = :groupsCount, observed_at = :observedAt, changed_at = :changedAt, sync_run_id = COALESCE(:runId, sync_run_id)
                WHERE source_record_id = :id
                """)
                .params(parameters)
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO learning_snapshots (
                        source_record_id, organization_id, program_id, course_id, group_id, course_name, group_name,
                        participants_count, teachers_count, completed_count, not_completed_count, unknown_count,
                        groups_count, observed_at, changed_at, sync_run_id
                    ) VALUES (
                        :id, :organizationId, :programId, :courseId, :groupId, :courseName, :groupName,
                        :participants, :teachers, :completed, :notCompleted, :unknown,
                        :groupsCount, :observedAt, :changedAt, :runId
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
                WHERE i.id = :interactionId AND i.organization_id IN (SELECT id FROM organizations WHERE %s)
                ORDER BY s.course_name, s.course_id, s.group_id NULLS FIRST, s.group_name
                """.formatted(scope.condition()))
                .param("interactionId", interactionId)
                .params(scope.parameters())
                .query(this::mapSnapshot)
                .list();
    }

    void saveMapping(SourceCode source, String kind, String externalKey, UUID organizationId, UUID programId,
                     RunDates run, UUID actorProfileId, OffsetDateTime now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("source", source.name());
        parameters.put("kind", kind);
        parameters.put("externalKey", externalKey);
        parameters.put("organizationId", organizationId);
        parameters.put("programId", programId);
        parameters.put("runStartsOn", run == null ? null : run.startsOn());
        parameters.put("runEndsOn", run == null ? null : run.endsOn());
        parameters.put("actorProfileId", actorProfileId);
        parameters.put("now", now);
        int updated = jdbcClient.sql("""
                UPDATE source_mappings
                SET organization_id = :organizationId, program_id = :programId, run_starts_on = :runStartsOn,
                    run_ends_on = :runEndsOn, created_by = :actorProfileId, updated_at = :now
                WHERE source = :source AND kind = :kind AND external_key = :externalKey
                """)
                .params(parameters)
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO source_mappings (
                        id, source, kind, external_key, organization_id, program_id, run_starts_on, run_ends_on,
                        created_by, created_at, updated_at
                    ) VALUES (
                        :id, :source, :kind, :externalKey, :organizationId, :programId, :runStartsOn, :runEndsOn,
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
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getObject("owner_manager_id", UUID.class),
                        resultSet.getObject("program_id", UUID.class),
                        null,
                        null
                ))
                .optional();
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
                resultSet.getObject("finished_at", OffsetDateTime.class)
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
                resultSet.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private StoredSnapshot mapSnapshot(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredSnapshot(
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
                resultSet.getObject("run_ends_on", LocalDate.class)
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
            OffsetDateTime updatedAt
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
            String payload
    ) {
    }

    record MappedTarget(UUID organizationId, UUID ownerManagerId, UUID programId, LocalDate runStartsOn, LocalDate runEndsOn) {
    }

    record RunDates(LocalDate startsOn, LocalDate endsOn) {
    }

    record StoredSnapshot(
            UUID sourceRecordId,
            UUID organizationId,
            UUID programId,
            LearningUnit unit,
            OffsetDateTime observedAt,
            OffsetDateTime changedAt,
            LocalDate runStartsOn,
            LocalDate runEndsOn
    ) {
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
