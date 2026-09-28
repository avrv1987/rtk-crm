package ru.rtk.crm.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.SearchPattern;

@Repository
public class AuditJournalRepository {
    public static final String SYSTEM_ACTOR = "Система";

    private static final String ROLE_LABEL = """
            CASE %s WHEN 'USER' THEN 'КАМ' WHEN 'LEADER' THEN 'руководитель' WHEN 'MANAGEMENT' THEN 'руководство' WHEN 'PARTNER' THEN 'представитель вуза' ELSE 'администратор' END""";

    private static final String JOURNAL = """
            SELECT a.id, a.occurred_at, a.category, a.action, a.actor_profile_id, a.actor_display_name,
                   a.object_type, a.object_id, a.object_name, a.details, a.request_id
            FROM audit_events a
            UNION ALL
            SELECT e.id, e.occurred_at, 'PROFILE',
                   CASE WHEN e.previous_enrolment_operator <> e.enrolment_operator
                             AND e.previous_display_name = e.display_name AND e.previous_role = e.role
                             AND e.previous_team_id IS NOT DISTINCT FROM e.team_id AND e.previous_active = e.active
                        THEN 'ENROLMENT_OPERATOR_CHANGED' ELSE 'PROFILE_CHANGED' END,
                   e.actor_profile_id, e.actor_display_name,
                   'PROFILE', e.profile_id, p.display_name,
                   CONCAT_WS('; ',
                       CASE WHEN e.previous_display_name <> e.display_name
                            THEN 'имя: ' || e.previous_display_name || ' → ' || e.display_name END,
                       CASE WHEN e.previous_role <> e.role
                            THEN 'роль: ' || %1$s || ' → ' || %2$s END,
                       CASE WHEN e.previous_team_id IS DISTINCT FROM e.team_id
                            THEN 'команда: ' || COALESCE(pt.name, 'без команды') || ' → ' || COALESCE(t.name, 'без команды') END,
                       CASE WHEN e.previous_active <> e.active
                            THEN CASE WHEN e.active THEN 'доступ открыт' ELSE 'доступ закрыт' END END,
                       CASE WHEN e.previous_enrolment_operator <> e.enrolment_operator
                            THEN CASE WHEN e.enrolment_operator THEN 'флаг «Оператор зачисления» назначен'
                                      ELSE 'флаг «Оператор зачисления» снят' END END),
                   e.request_id
            FROM crm_profile_events e
            JOIN crm_user_profiles p ON p.id = e.profile_id
            LEFT JOIN teams pt ON pt.id = e.previous_team_id
            LEFT JOIN teams t ON t.id = e.team_id
            UNION ALL
            SELECT e.id, e.occurred_at, 'ASSIGNMENT',
                   CASE WHEN e.owner_manager_id IS NULL THEN 'KAM_UNASSIGNED'
                        WHEN e.previous_owner_manager_id IS NULL THEN 'KAM_ASSIGNED'
                        ELSE 'KAM_CHANGED' END,
                   e.actor_profile_id, e.actor_display_name, 'ORGANIZATION', e.organization_id, o.name,
                   'КАМ: ' || COALESCE(e.previous_owner_manager_display_name, 'не назначен')
                       || ' → ' || COALESCE(e.new_owner_manager_display_name, 'не назначен'),
                   e.request_id
            FROM organization_assignment_events e
            JOIN organizations o ON o.id = e.organization_id
            UNION ALL
            SELECT e.id, e.occurred_at, 'ORGANIZATION', 'ORGANIZATION_TEAM_CHANGED', e.actor_profile_id, e.actor_display_name,
                   'ORGANIZATION', e.organization_id, o.name, 'команда: ' || pt.name || ' → ' || t.name, e.request_id
            FROM organization_team_events e
            JOIN organizations o ON o.id = e.organization_id
            JOIN teams pt ON pt.id = e.previous_team_id
            JOIN teams t ON t.id = e.team_id
            UNION ALL
            SELECT r.id, r.created_at, 'SYNC', 'SYNC_STARTED', r.started_by, p.display_name,
                   'SOURCE', CAST(NULL AS UUID),
                   CASE WHEN r.run_trigger = 'UPLOAD' THEN 'Сайт: загрузка файла оплат'
                        WHEN r.source = 'WEBSITE' THEN 'Сайт' ELSE 'Moodle' END,
                   CASE r.status WHEN 'SUCCEEDED' THEN 'выполнена' WHEN 'FAILED' THEN 'завершилась ошибкой'
                                 WHEN 'RUNNING' THEN 'выполняется' ELSE 'в очереди' END
                       || '; получено: ' || CAST(r.fetched_count AS VARCHAR(12))
                       || ', создано: ' || CAST(r.created_count AS VARCHAR(12))
                       || ', обновлено: ' || CAST(r.updated_count AS VARCHAR(12))
                       || ', на разбор: ' || CAST(r.needs_mapping_count AS VARCHAR(12)),
                   CAST(NULL AS VARCHAR(64))
            FROM sync_runs r
            JOIN crm_user_profiles p ON p.id = r.started_by
            """.formatted(ROLE_LABEL.formatted("e.previous_role"), ROLE_LABEL.formatted("e.role"));

    private final JdbcClient jdbcClient;

    public AuditJournalRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void record(
            AuditAction action,
            UUID actorProfileId,
            String objectType,
            UUID objectId,
            String objectName,
            String details,
            String requestId
    ) {
        jdbcClient.sql("""
                INSERT INTO audit_events (
                    id, category, action, actor_profile_id, actor_display_name,
                    object_type, object_id, object_name, details, request_id, occurred_at
                ) VALUES (
                    :id, :category, :action, :actorProfileId,
                    COALESCE((SELECT display_name FROM crm_user_profiles WHERE id = :actorProfileId), :systemActor),
                    :objectType, :objectId, :objectName, :details, :requestId, :occurredAt
                )
                """)
                .param("id", UUID.randomUUID())
                .param("category", action.category().name())
                .param("action", action.name())
                .param("actorProfileId", actorProfileId)
                .param("systemActor", SYSTEM_ACTOR)
                .param("objectType", objectType)
                .param("objectId", objectId)
                .param("objectName", limit(objectName, 500))
                .param("details", limit(details, 2000))
                .param("requestId", requestId)
                .param("occurredAt", OffsetDateTime.now())
                .update();
    }

    public void recordAttachmentAccess(AuditAction action, UUID actorProfileId, UUID attachmentId, String requestId) {
        jdbcClient.sql("""
                INSERT INTO audit_events (
                    id, category, action, actor_profile_id, actor_display_name,
                    object_type, object_id, object_name, details, request_id, occurred_at
                )
                SELECT :id, :category, :action, :actorProfileId, p.display_name,
                       'ATTACHMENT', a.id, a.original_name,
                       'вуз «' || o.name || '», карточка «' || i.title || '»', :requestId, :occurredAt
                FROM attachments a
                JOIN interactions i ON i.id = a.interaction_id
                JOIN organizations o ON o.id = i.organization_id
                JOIN crm_user_profiles p ON p.id = :actorProfileId
                WHERE a.id = :attachmentId
                """)
                .param("id", UUID.randomUUID())
                .param("category", action.category().name())
                .param("action", action.name())
                .param("actorProfileId", actorProfileId)
                .param("attachmentId", attachmentId)
                .param("requestId", requestId)
                .param("occurredAt", OffsetDateTime.now())
                .update();
    }

    public void recordReportDownload(UUID actorProfileId, UUID jobId, String requestId) {
        jdbcClient.sql("""
                INSERT INTO audit_events (
                    id, category, action, actor_profile_id, actor_display_name,
                    object_type, object_id, object_name, details, request_id, occurred_at
                )
                SELECT :id, :category, :action, :actorProfileId, p.display_name,
                       'REPORT', j.id, j.result_file_name,
                       'формат ' || j.format || ', строк: ' || CAST(COALESCE(j.row_count, 0) AS VARCHAR(12)), :requestId, :occurredAt
                FROM report_jobs j
                JOIN crm_user_profiles p ON p.id = :actorProfileId
                WHERE j.id = :jobId
                """)
                .param("id", UUID.randomUUID())
                .param("category", AuditAction.REPORT_DOWNLOADED.category().name())
                .param("action", AuditAction.REPORT_DOWNLOADED.name())
                .param("actorProfileId", actorProfileId)
                .param("jobId", jobId)
                .param("requestId", requestId)
                .param("occurredAt", OffsetDateTime.now())
                .update();
    }

    public void recordReportFileDownload(UUID actorProfileId, String fileName, String details, String requestId) {
        jdbcClient.sql("""
                INSERT INTO audit_events (
                    id, category, action, actor_profile_id, actor_display_name,
                    object_type, object_id, object_name, details, request_id, occurred_at
                )
                SELECT :id, :category, :action, p.id, p.display_name, 'REPORT', CAST(NULL AS UUID), :fileName, :details,
                       :requestId, :occurredAt
                FROM crm_user_profiles p
                WHERE p.id = :actorProfileId
                """)
                .param("id", UUID.randomUUID())
                .param("category", AuditAction.REPORT_DOWNLOADED.category().name())
                .param("action", AuditAction.REPORT_DOWNLOADED.name())
                .param("actorProfileId", actorProfileId)
                .param("fileName", fileName)
                .param("details", details)
                .param("requestId", requestId)
                .param("occurredAt", OffsetDateTime.now())
                .update();
    }

    public AuditEntryPage findPage(AuditQuery query) {
        Filter filter = filter(query);
        List<AuditEntry> items = find(query, filter);
        long total = bind(jdbcClient.sql("SELECT COUNT(*) FROM (" + JOURNAL + ") journal WHERE " + filter.condition()), filter)
                .query(Long.class)
                .single();
        return new AuditEntryPage(items, query.page(), query.size(), total);
    }

    public List<AuditEntry> findRows(AuditQuery query) {
        return find(query, filter(query));
    }

    public Optional<AuditEntry> findLatest(AuditAction action) {
        return jdbcClient.sql("""
                SELECT id, occurred_at, category, action, actor_profile_id, actor_display_name,
                       object_type, object_id, object_name, details, request_id
                FROM audit_events
                WHERE action = :action
                ORDER BY occurred_at DESC, id DESC
                LIMIT 1
                """)
                .param("action", action.name())
                .query(this::mapEntry)
                .optional();
    }

    public List<AuditEntry> findByObject(String objectType, UUID objectId, int limit) {
        return jdbcClient.sql("""
                SELECT id, occurred_at, category, action, actor_profile_id, actor_display_name,
                       object_type, object_id, object_name, details, request_id
                FROM audit_events
                WHERE object_type = :objectType AND object_id = :objectId
                ORDER BY occurred_at DESC, id DESC
                LIMIT :limit
                """)
                .param("objectType", objectType)
                .param("objectId", objectId)
                .param("limit", limit)
                .query(this::mapEntry)
                .list();
    }

    public int deleteOlderThan(OffsetDateTime cutoff) {
        return jdbcClient.sql("DELETE FROM audit_events WHERE occurred_at < :cutoff")
                .param("cutoff", cutoff)
                .update();
    }

    private List<AuditEntry> find(AuditQuery query, Filter filter) {
        return bind(jdbcClient.sql("""
                SELECT * FROM (%s) journal
                WHERE %s
                ORDER BY occurred_at DESC, id DESC
                LIMIT :size OFFSET :offset
                """.formatted(JOURNAL, filter.condition())), filter)
                .param("size", query.size())
                .param("offset", query.offset())
                .query(this::mapEntry)
                .list();
    }

    private Filter filter(AuditQuery query) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        conditions.add("TRUE");
        if (query.fromInstant() != null) {
            conditions.add("occurred_at >= :from");
            params.put("from", query.fromInstant());
        }
        if (query.toInstant() != null) {
            conditions.add("occurred_at < :to");
            params.put("to", query.toInstant());
        }
        if (query.actor() != null) {
            conditions.add("LOWER(actor_display_name) LIKE :actor " + SearchPattern.LIKE_ESCAPE);
            params.put("actor", SearchPattern.contains(query.actor()));
        }
        if (query.object() != null) {
            conditions.add("(LOWER(COALESCE(object_name, '')) LIKE :object " + SearchPattern.LIKE_ESCAPE
                    + " OR LOWER(COALESCE(details, '')) LIKE :object " + SearchPattern.LIKE_ESCAPE + ")");
            params.put("object", SearchPattern.contains(query.object()));
        }
        if (query.category() != null) {
            conditions.add("category = :category");
            params.put("category", query.category().name());
        }
        return new Filter(String.join(" AND ", conditions), params);
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, Filter filter) {
        filter.params().forEach(statement::param);
        return statement;
    }

    private AuditEntry mapEntry(ResultSet resultSet, int rowNumber) throws SQLException {
        AuditAction action = AuditAction.valueOf(resultSet.getString("action"));
        return new AuditEntry(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("occurred_at", OffsetDateTime.class),
                AuditCategory.valueOf(resultSet.getString("category")),
                action,
                action.label(),
                resultSet.getObject("actor_profile_id", UUID.class),
                resultSet.getString("actor_display_name"),
                resultSet.getString("object_type"),
                resultSet.getObject("object_id", UUID.class),
                resultSet.getString("object_name"),
                resultSet.getString("details"),
                resultSet.getString("request_id")
        );
    }

    private static String limit(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length - 1) + "…";
    }

    private record Filter(String condition, Map<String, Object> params) {
    }
}
