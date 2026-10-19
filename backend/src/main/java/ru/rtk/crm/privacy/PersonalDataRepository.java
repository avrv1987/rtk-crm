package ru.rtk.crm.privacy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.privacy.SubjectTerms.SqlMatch;
import ru.rtk.crm.report.ReportKind;

@Repository
public class PersonalDataRepository {
    private static final String SCOPE_INTERACTIONS = "SELECT id FROM interactions WHERE organization_id IN (:scopeOrganizations)";
    // Отчёты этих видов собраны из чисел и справочников, ПДн в них нет.
    private static final List<String> REPORTS_WITHOUT_PERSONAL_DATA = List.of(ReportKind.DEMAND.name(), ReportKind.DURATION.name());

    static final String RECTIFIED_VALUES = "; было → стало: ";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public PersonalDataRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    Optional<ContactState> lockContact(UUID contactId) {
        return jdbcClient.sql("SELECT id FROM contacts WHERE id = :contactId FOR UPDATE")
                .param("contactId", contactId)
                .query(UUID.class)
                .optional()
                .flatMap(id -> jdbcClient.sql("""
                        SELECT c.id, c.organization_id, o.name AS organization_name, c.name, c.position, c.email, c.phone,
                               c.personal_data_status, c.version
                        FROM contacts c
                        JOIN organizations o ON o.id = c.organization_id
                        WHERE c.id = :contactId
                        """)
                        .param("contactId", id)
                        .query((resultSet, rowNumber) -> new ContactState(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("organization_id", UUID.class),
                                resultSet.getString("organization_name"),
                                resultSet.getString("name"),
                                resultSet.getString("position"),
                                resultSet.getString("email"),
                                resultSet.getString("phone"),
                                PersonalDataStatus.valueOf(resultSet.getString("personal_data_status")),
                                resultSet.getInt("version")
                        ))
                        .optional());
    }

    boolean updateContact(UUID contactId, int expectedVersion, ContactValues values, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE contacts
                SET name = :name, position = :position, email = :email, phone = :phone,
                    version = version + 1, updated_at = :updatedAt
                WHERE id = :contactId AND version = :expectedVersion AND personal_data_status <> 'ANONYMIZED'
                """)
                .param("contactId", contactId)
                .param("expectedVersion", expectedVersion)
                .param("name", values.name())
                .param("position", values.position())
                .param("email", values.email())
                .param("phone", values.phone())
                .param("updatedAt", now)
                .update() == 1;
    }

    boolean updateContactStatus(UUID contactId, int expectedVersion, PersonalDataStatus status, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE contacts
                SET personal_data_status = :status, version = version + 1, updated_at = :updatedAt
                WHERE id = :contactId AND version = :expectedVersion AND personal_data_status <> 'ANONYMIZED'
                """)
                .param("contactId", contactId)
                .param("expectedVersion", expectedVersion)
                .param("status", status.name())
                .param("updatedAt", now)
                .update() == 1;
    }

    void anonymizeContact(UUID contactId, String marker, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE contacts
                SET name = :marker, position = NULL, email = NULL, phone = NULL, external_key = NULL,
                    personal_data_status = 'ANONYMIZED', version = version + 1, updated_at = :updatedAt
                WHERE id = :contactId
                """)
                .param("contactId", contactId)
                .param("marker", marker)
                .param("updatedAt", now)
                .update();
        jdbcClient.sql("""
                UPDATE audit_events
                SET details = LEFT(details, POSITION(:separator IN details) - 1)
                WHERE object_type = 'CONTACT' AND object_id = :contactId AND action = 'CONTACT_RECTIFIED'
                  AND POSITION(:separator IN details) > 0
                """)
                .param("contactId", contactId)
                .param("separator", RECTIFIED_VALUES)
                .update();
    }

    Optional<ProfileState> lockProfile(UUID profileId) {
        return jdbcClient.sql("SELECT id FROM crm_user_profiles WHERE id = :profileId FOR UPDATE")
                .param("profileId", profileId)
                .query(UUID.class)
                .optional()
                .flatMap(id -> jdbcClient.sql("""
                        SELECT id, display_name, login, active, anonymized_at
                        FROM crm_user_profiles
                        WHERE id = :profileId
                        """)
                        .param("profileId", id)
                        .query((resultSet, rowNumber) -> new ProfileState(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("display_name"),
                                resultSet.getString("login"),
                                resultSet.getBoolean("active"),
                                resultSet.getObject("anonymized_at", OffsetDateTime.class) != null
                        ))
                        .optional());
    }

    void anonymizeProfile(UUID profileId, String marker, String previousMarker, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE crm_user_profiles
                SET display_name = :marker, login = NULL, anonymized_at = :now, version = version + 1, updated_at = :now
                WHERE id = :profileId
                """)
                .param("profileId", profileId)
                .param("marker", marker)
                .param("now", now)
                .update();
        jdbcClient.sql("""
                UPDATE crm_profile_events
                SET previous_display_name = CASE WHEN previous_display_name = display_name THEN :marker ELSE :previousMarker END,
                    display_name = :marker
                WHERE profile_id = :profileId
                """)
                .param("profileId", profileId)
                .param("marker", marker)
                .param("previousMarker", previousMarker)
                .update();
        for (String statement : List.of(
                "UPDATE crm_profile_events SET actor_display_name = :marker WHERE actor_profile_id = :profileId",
                "UPDATE organization_assignment_events SET previous_owner_manager_display_name = :marker WHERE previous_owner_manager_id = :profileId",
                "UPDATE organization_assignment_events SET new_owner_manager_display_name = :marker WHERE owner_manager_id = :profileId",
                "UPDATE organization_assignment_events SET actor_display_name = :marker WHERE actor_profile_id = :profileId",
                "UPDATE organization_team_events SET actor_display_name = :marker WHERE actor_profile_id = :profileId",
                "UPDATE audit_events SET actor_display_name = :marker WHERE actor_profile_id = :profileId"
        )) {
            jdbcClient.sql(statement).param("profileId", profileId).param("marker", marker).update();
        }
    }

    // Замена идёт только внутри области субъекта (scope): тексты взаимодействий его вузов, их вложения и записи
    // источников, записи журнала об этих объектах и служебные снимки, где есть идентификатор субъекта или вуза.
    // Без области ничего не меняется — глобальная замена по базе переименовывала чужие файлы.
    int replaceText(TextColumn column, SubjectTerms terms, String marker, Scope scope, boolean dryRun) {
        if (terms.isEmpty()) {
            return 0;
        }
        Map<String, Object> scopeParams = new LinkedHashMap<>();
        String scopeSql = scopeCondition(column, scope, scopeParams);
        if (scopeSql == null) {
            return 0;
        }
        boolean fileName = column.kind() == TextKind.FILE_NAME || column == TextColumn.AUDIT_OBJECT;
        SqlMatch match = fileName ? terms.attachmentMatch(column.column()) : terms.textMatch(column.column());
        JdbcClient.StatementSpec select = jdbcClient.sql(
                "SELECT id, " + column.column() + " AS text FROM " + column.table() + " WHERE " + match.sql() + " AND (" + scopeSql + ")"
        );
        match.params().forEach(select::param);
        scopeParams.forEach(select::param);
        List<StoredText> candidates = select
                .query((resultSet, rowNumber) -> new StoredText(resultSet.getObject("id", UUID.class), resultSet.getString("text")))
                .list();
        int changed = 0;
        for (StoredText candidate : candidates) {
            String replaced = fileName
                    ? terms.replaceInAttachmentName(candidate.text(), marker)
                    : column.json()
                    ? terms.replaceInJson(candidate.text(), marker, objectMapper)
                    : terms.replace(candidate.text(), marker);
            if (replaced == null || replaced.equals(candidate.text())) {
                continue;
            }
            if (!dryRun) {
                jdbcClient.sql("UPDATE " + column.table() + " SET " + column.column() + " = :text"
                                + (column.versioned() ? ", version = version + 1" : "") + " WHERE id = :id")
                        .param("text", column.fit(replaced))
                        .param("id", candidate.id())
                        .update();
            }
            changed++;
        }
        return changed;
    }

    private static String scopeCondition(TextColumn column, Scope scope, Map<String, Object> params) {
        boolean organizations = !scope.organizationIds().isEmpty();
        if (organizations) {
            params.put("scopeOrganizations", scope.organizationIds());
        }
        return switch (column.scope()) {
            case EVENT -> {
                if (!organizations) {
                    yield null;
                }
                // Файлы, отмеченные к удалению, не переименовываются: иначе один файл посчитан бы дважды.
                if (column.kind() == TextKind.FILE_NAME && !scope.deletedFiles().isEmpty()) {
                    params.put("deletedFiles", scope.deletedFiles());
                    yield "interaction_id IN (" + SCOPE_INTERACTIONS + ") AND id NOT IN (:deletedFiles)";
                }
                yield "interaction_id IN (" + SCOPE_INTERACTIONS + ")";
            }
            case OWNER -> organizations ? "organization_id IN (:scopeOrganizations)" : null;
            case AUDIT -> {
                params.put("scopeObjects", scope.objectIds());
                yield "object_id IN (:scopeObjects)" + (organizations
                        ? " OR object_id IN (:scopeOrganizations) OR object_id IN (" + SCOPE_INTERACTIONS + ")"
                        + " OR object_id IN (SELECT a.id FROM attachments a WHERE a.interaction_id IN (" + SCOPE_INTERACTIONS + "))"
                        : "");
            }
            // В служебных JSON нет внешних ключей: берём только снимки, где записан идентификатор субъекта или его вуза.
            case REFERENCE -> {
                List<String> parts = new ArrayList<>();
                List<UUID> ids = new ArrayList<>(scope.objectIds());
                ids.addAll(scope.organizationIds());
                for (int index = 0; index < ids.size(); index++) {
                    params.put("ref" + index, "%" + ids.get(index) + "%");
                    parts.add(column.column() + " LIKE :ref" + index);
                }
                yield parts.isEmpty() ? null : String.join(" OR ", parts);
            }
        };
    }

    // Вузы, с которыми работал сотрудник: был или стал ответственным, писал события. Его имя может быть только там.
    List<UUID> profileOrganizationIds(UUID profileId) {
        return jdbcClient.sql("""
                SELECT id FROM organizations WHERE owner_manager_id = :profileId
                UNION
                SELECT organization_id FROM organization_assignment_events
                WHERE previous_owner_manager_id = :profileId OR owner_manager_id = :profileId
                UNION
                SELECT organization_id FROM organization_team_events WHERE previous_owner_manager_id = :profileId
                UNION
                SELECT i.organization_id FROM interaction_events e JOIN interactions i ON i.id = e.interaction_id
                WHERE e.actor_profile_id = :profileId
                """)
                .param("profileId", profileId)
                .query(UUID.class)
                .list();
    }

    List<StoredFile> findAttachmentFiles(List<UUID> attachmentIds) {
        if (attachmentIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("SELECT id, storage_key FROM attachments WHERE id IN (:ids)")
                .param("ids", attachmentIds)
                .query(this::mapFile)
                .list();
    }

    void deleteAttachment(UUID attachmentId) {
        jdbcClient.sql("DELETE FROM attachments WHERE id = :id").param("id", attachmentId).update();
    }

    List<UUID> expireReportResults(OffsetDateTime finishedBefore, String message) {
        List<UUID> expired = new ArrayList<>();
        for (StoredFile candidate : jdbcClient.sql("""
                        SELECT id, result_storage_key
                        FROM report_jobs
                        WHERE status = 'SUCCEEDED' AND result_storage_key IS NOT NULL AND finished_at < :finishedBefore
                        """)
                .param("finishedBefore", finishedBefore)
                .query(this::mapReportFile)
                .list()) {
            if (expireReport(candidate, message)) {
                expired.add(candidate.storageKey());
            }
        }
        return expired;
    }

    // Кандидаты — готовые отчёты, где могли оказаться данные субъекта: виды с текстами и ФИО (не «Востребованность»
    // и не «Длительность») у тех, кто видел его вузы, — «Руководство», команды этих вузов, в том числе прежние,
    // и отчёты самого сотрудника. Отчёты других команд и администраторов (они не видят работу с вузами) не трогаются.
    // Удалять ли кандидата, решает проверка содержимого файла (ReportContentScanner).
    List<SubjectReport> findSubjectReports(List<UUID> organizationIds, List<UUID> profileIds) {
        List<UUID> teams = new ArrayList<>();
        if (!organizationIds.isEmpty()) {
            teams.addAll(jdbcClient.sql("""
                    SELECT team_id FROM organizations WHERE id IN (:organizations)
                    UNION
                    SELECT previous_team_id FROM organization_team_events WHERE organization_id IN (:organizations)
                    UNION
                    SELECT team_id FROM organization_team_events WHERE organization_id IN (:organizations)
                    """)
                    .param("organizations", organizationIds)
                    .query(UUID.class)
                    .list());
        }
        if (!profileIds.isEmpty()) {
            teams.addAll(jdbcClient.sql("SELECT team_id FROM crm_user_profiles WHERE id IN (:profiles) AND team_id IS NOT NULL")
                    .param("profiles", profileIds)
                    .query(UUID.class)
                    .list());
        }
        List<String> owners = new ArrayList<>(List.of("owner_role = 'MANAGEMENT'"));
        if (!teams.isEmpty()) {
            owners.add("(owner_role IN ('USER', 'LEADER') AND owner_team_id IN (:teams))");
        }
        if (!profileIds.isEmpty()) {
            owners.add("owner_profile_id IN (:profiles)");
        }
        JdbcClient.StatementSpec select = jdbcClient.sql("""
                SELECT id, result_storage_key, format, group_by
                FROM report_jobs
                WHERE status = 'SUCCEEDED' AND result_storage_key IS NOT NULL AND kind NOT IN (:safeKinds)
                  AND (%s)
                ORDER BY id
                """.formatted(String.join(" OR ", owners)))
                .param("safeKinds", REPORTS_WITHOUT_PERSONAL_DATA);
        if (!teams.isEmpty()) {
            select.param("teams", teams.stream().distinct().toList());
        }
        if (!profileIds.isEmpty()) {
            select.param("profiles", profileIds);
        }
        return select.query((resultSet, row) -> new SubjectReport(mapReportFile(resultSet, row), resultSet.getString("format"),
                resultSet.getString("group_by"))).list();
    }

    // Файл остаётся, но в списке выгрузок отчёт помечен: проверить его на данные субъекта не удалось.
    boolean markReportForRebuild(StoredFile report, String message) {
        return jdbcClient.sql("""
                UPDATE report_jobs
                SET error_code = 'REPORT_NEEDS_REBUILD', error_message = :message
                WHERE id = :jobId AND result_storage_key = :storageKey
                """)
                .param("jobId", report.id())
                .param("storageKey", report.storageKey())
                .param("message", message)
                .update() == 1;
    }

    // Условие по ключу файла: параллельное скачивание или повторный запуск не удалят файл дважды.
    boolean expireReport(StoredFile report, String message) {
        return jdbcClient.sql("""
                UPDATE report_jobs
                SET result_storage_key = NULL, error_code = 'REPORT_RESULT_EXPIRED', error_message = :message
                WHERE id = :jobId AND result_storage_key = :storageKey
                """)
                .param("jobId", report.id())
                .param("storageKey", report.storageKey())
                .param("message", message)
                .update() == 1;
    }

    List<UUID> findInactiveContactIds(OffsetDateTime cutoff, int limit) {
        return jdbcClient.sql("""
                SELECT c.id
                FROM contacts c
                WHERE c.personal_data_status <> 'ANONYMIZED'
                  AND c.updated_at < :cutoff
                  AND NOT EXISTS (
                      SELECT 1 FROM interactions i
                      WHERE i.organization_id = c.organization_id AND i.updated_at >= :cutoff
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM interaction_events e
                      JOIN interactions i ON i.id = e.interaction_id
                      WHERE i.organization_id = c.organization_id AND e.occurred_at >= :cutoff
                  )
                ORDER BY c.updated_at, c.id
                LIMIT :limit
                """)
                .param("cutoff", cutoff)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    // Условное UPDATE только из PENDING, как при открытии плана: параллельное «Записать» выигрывает, если успело первым.
    // decided_by у истёкшего пуст — его никто не решал.
    List<ExpiredPlan> expireAgentPlans(OffsetDateTime now) {
        List<ExpiredPlan> due = jdbcClient.sql("""
                        SELECT id, interaction_id, client_id, expected_version
                        FROM agent_change_plans
                        WHERE state = 'PENDING' AND expires_at <= :now
                        """)
                .param("now", now)
                .query((resultSet, rowNumber) -> new ExpiredPlan(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getString("client_id"),
                        resultSet.getInt("expected_version")
                ))
                .list();
        return due.stream()
                .filter(plan -> jdbcClient.sql("""
                                UPDATE agent_change_plans SET state = 'EXPIRED', decided_at = :now
                                WHERE id = :id AND state = 'PENDING'
                                """)
                        .param("now", now)
                        .param("id", plan.id())
                        .update() == 1)
                .toList();
    }

    int deleteDecidedAgentPlans(OffsetDateTime decidedBefore) {
        return jdbcClient.sql("""
                        DELETE FROM agent_change_plans
                        WHERE state <> 'PENDING' AND COALESCE(decided_at, expires_at) < :decidedBefore
                        """)
                .param("decidedBefore", decidedBefore)
                .update();
    }

    List<UUID> findDismissedProfileIds(OffsetDateTime cutoff, int limit) {
        return jdbcClient.sql("""
                SELECT p.id
                FROM crm_user_profiles p
                WHERE p.active = FALSE AND p.pending_activation = FALSE AND p.anonymized_at IS NULL
                  AND COALESCE((
                      SELECT MAX(e.occurred_at) FROM crm_profile_events e
                      WHERE e.profile_id = p.id AND e.previous_active = TRUE AND e.active = FALSE
                  ), p.updated_at) < :cutoff
                ORDER BY p.updated_at, p.id
                LIMIT :limit
                """)
                .param("cutoff", cutoff)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    private StoredFile mapFile(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredFile(resultSet.getObject("id", UUID.class), resultSet.getObject("storage_key", UUID.class));
    }

    private StoredFile mapReportFile(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredFile(resultSet.getObject("id", UUID.class), resultSet.getObject("result_storage_key", UUID.class));
    }

    private record StoredText(UUID id, String text) {
    }

    record SubjectReport(StoredFile file, String format, String groupBy) {
    }

    record StoredFile(UUID id, UUID storageKey) {
    }

    record ContactState(
            UUID id,
            UUID organizationId,
            String organizationName,
            String name,
            String position,
            String email,
            String phone,
            PersonalDataStatus status,
            int version
    ) {
    }

    record ExpiredPlan(UUID id, UUID interactionId, String clientId, int expectedVersion) {
    }

    record ContactValues(String name, String position, String email, String phone) {
    }

    record ProfileState(UUID id, String displayName, String login, boolean active, boolean anonymized) {
    }

    record Scope(List<UUID> organizationIds, List<UUID> objectIds, List<UUID> deletedFiles) {
    }

    enum TextKind {
        MENTION,
        FILE_NAME,
        SOURCE,
        TECHNICAL
    }

    enum ScopeKind {
        EVENT,
        OWNER,
        AUDIT,
        REFERENCE
    }

    enum TextColumn {
        EVENT_COMMENT("interaction_events", "comment", 0, false, TextKind.MENTION, ScopeKind.EVENT),
        EVENT_NEXT_ACTION("interaction_events", "next_action", 500, false, TextKind.MENTION, ScopeKind.EVENT),
        INTERACTION_NEXT_ACTION("interactions", "next_action", 500, true, TextKind.MENTION, ScopeKind.OWNER),
        INTERACTION_TITLE("interactions", "title", 200, true, TextKind.MENTION, ScopeKind.OWNER),
        AGENT_PLAN_NOTE("agent_change_plans", "note_text", 4000, false, TextKind.MENTION, ScopeKind.EVENT),
        AGENT_PLAN_NEXT_ACTION("agent_change_plans", "next_action", 500, false, TextKind.MENTION, ScopeKind.EVENT),
        ATTACHMENT_NAME("attachments", "original_name", 255, false, TextKind.FILE_NAME, ScopeKind.EVENT),
        SOURCE_PAYLOAD("source_records", "payload", 0, false, TextKind.SOURCE, ScopeKind.OWNER, true),
        IMPORT_PLAN("catalog_import_rows", "plan_json", 0, false, TextKind.TECHNICAL, ScopeKind.REFERENCE, true),
        COMMAND_RESULT("command_idempotency_records", "result_json", 0, false, TextKind.TECHNICAL, ScopeKind.REFERENCE, true),
        AUDIT_OBJECT("audit_events", "object_name", 500, false, TextKind.TECHNICAL, ScopeKind.AUDIT),
        AUDIT_DETAILS("audit_events", "details", 2000, false, TextKind.TECHNICAL, ScopeKind.AUDIT);

        private final String table;
        private final String column;
        private final int maxLength;
        private final boolean versioned;
        private final TextKind kind;
        private final ScopeKind scope;
        private final boolean json;

        TextColumn(String table, String column, int maxLength, boolean versioned, TextKind kind, ScopeKind scope) {
            this(table, column, maxLength, versioned, kind, scope, false);
        }

        TextColumn(
                String table,
                String column,
                int maxLength,
                boolean versioned,
                TextKind kind,
                ScopeKind scope,
                boolean json
        ) {
            this.table = table;
            this.column = column;
            this.maxLength = maxLength;
            this.versioned = versioned;
            this.kind = kind;
            this.scope = scope;
            this.json = json;
        }

        String table() {
            return table;
        }

        String column() {
            return column;
        }

        boolean versioned() {
            return versioned;
        }

        TextKind kind() {
            return kind;
        }

        ScopeKind scope() {
            return scope;
        }

        boolean json() {
            return json;
        }

        String fit(String value) {
            return maxLength == 0 || value.length() <= maxLength ? value : value.substring(0, maxLength);
        }
    }
}
