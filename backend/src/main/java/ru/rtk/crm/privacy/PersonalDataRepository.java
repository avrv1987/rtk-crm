package ru.rtk.crm.privacy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.privacy.SubjectTerms.SqlMatch;

@Repository
public class PersonalDataRepository {
    private static final String EVENT_SCOPE = "interaction_id IN (SELECT id FROM interactions WHERE organization_id = :organizationId)";
    private static final String OWNER_SCOPE = "organization_id = :organizationId";

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

    int replaceText(TextColumn column, SubjectTerms terms, String marker, UUID organizationId) {
        if (terms.isEmpty()) {
            return 0;
        }
        boolean attachmentName = column == TextColumn.ATTACHMENT_NAME;
        SqlMatch match = attachmentName ? terms.attachmentMatch(column.column()) : terms.textMatch(column.column());
        boolean scoped = organizationId != null && column.organizationScope() != null;
        JdbcClient.StatementSpec select = jdbcClient.sql(
                "SELECT id, " + column.column() + " AS text FROM " + column.table() + " WHERE " + match.sql()
                        + (scoped ? " AND " + column.organizationScope() : "")
        );
        match.params().forEach(select::param);
        if (scoped) {
            select.param("organizationId", organizationId);
        }
        List<StoredText> candidates = select
                .query((resultSet, rowNumber) -> new StoredText(resultSet.getObject("id", UUID.class), resultSet.getString("text")))
                .list();
        int changed = 0;
        for (StoredText candidate : candidates) {
            String replaced = attachmentName
                    ? terms.replaceInAttachmentName(candidate.text(), marker)
                    : column.json()
                    ? terms.replaceInJson(candidate.text(), marker, objectMapper)
                    : terms.replace(candidate.text(), marker);
            if (replaced == null || replaced.equals(candidate.text())) {
                continue;
            }
            jdbcClient.sql("UPDATE " + column.table() + " SET " + column.column() + " = :text"
                            + (column.versioned() ? ", version = version + 1" : "") + " WHERE id = :id")
                    .param("text", column.fit(replaced))
                    .param("id", candidate.id())
                    .update();
            changed++;
        }
        return changed;
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
        JdbcClient.StatementSpec select = jdbcClient.sql("""
                SELECT id, result_storage_key
                FROM report_jobs
                WHERE status = 'SUCCEEDED' AND result_storage_key IS NOT NULL
                """ + (finishedBefore == null ? "" : " AND finished_at < :finishedBefore"));
        if (finishedBefore != null) {
            select.param("finishedBefore", finishedBefore);
        }
        List<UUID> expired = new ArrayList<>();
        for (StoredFile candidate : select.query(this::mapReportFile).list()) {
            if (jdbcClient.sql("""
                    UPDATE report_jobs
                    SET result_storage_key = NULL, error_code = 'REPORT_RESULT_EXPIRED', error_message = :message
                    WHERE id = :jobId AND result_storage_key = :storageKey
                    """)
                    .param("jobId", candidate.id())
                    .param("storageKey", candidate.storageKey())
                    .param("message", message)
                    .update() == 1) {
                expired.add(candidate.storageKey());
            }
        }
        return expired;
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

    record ContactValues(String name, String position, String email, String phone) {
    }

    record ProfileState(UUID id, String displayName, String login, boolean active, boolean anonymized) {
    }

    enum TextKind {
        MENTION,
        SOURCE,
        TECHNICAL
    }

    enum TextColumn {
        EVENT_COMMENT("interaction_events", "comment", 0, false, TextKind.MENTION, EVENT_SCOPE),
        EVENT_NEXT_ACTION("interaction_events", "next_action", 500, false, TextKind.MENTION, EVENT_SCOPE),
        INTERACTION_NEXT_ACTION("interactions", "next_action", 500, true, TextKind.MENTION, OWNER_SCOPE),
        INTERACTION_TITLE("interactions", "title", 200, true, TextKind.MENTION, OWNER_SCOPE),
        ATTACHMENT_NAME("attachments", "original_name", 255, false, TextKind.MENTION, EVENT_SCOPE),
        SOURCE_PAYLOAD("source_records", "payload", 0, false, TextKind.SOURCE, OWNER_SCOPE, true),
        IMPORT_PLAN("catalog_import_rows", "plan_json", 0, false, TextKind.TECHNICAL, null, true),
        COMMAND_RESULT("command_idempotency_records", "result_json", 0, false, TextKind.TECHNICAL, null, true),
        AUDIT_OBJECT("audit_events", "object_name", 500, false, TextKind.TECHNICAL, null),
        AUDIT_DETAILS("audit_events", "details", 2000, false, TextKind.TECHNICAL, null);

        private final String table;
        private final String column;
        private final int maxLength;
        private final boolean versioned;
        private final TextKind kind;
        private final String organizationScope;
        private final boolean json;

        TextColumn(String table, String column, int maxLength, boolean versioned, TextKind kind, String organizationScope) {
            this(table, column, maxLength, versioned, kind, organizationScope, false);
        }

        TextColumn(
                String table,
                String column,
                int maxLength,
                boolean versioned,
                TextKind kind,
                String organizationScope,
                boolean json
        ) {
            this.table = table;
            this.column = column;
            this.maxLength = maxLength;
            this.versioned = versioned;
            this.kind = kind;
            this.organizationScope = organizationScope;
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

        String organizationScope() {
            return organizationScope;
        }

        boolean json() {
            return json;
        }

        String fit(String value) {
            return maxLength == 0 || value.length() <= maxLength ? value : value.substring(0, maxLength);
        }
    }
}
