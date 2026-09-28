package ru.rtk.crm.teacherroster;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.ContactRole;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterExport;

@Repository
public class TeacherRosterRepository {
    private static final String ROSTER_SELECT = """
            SELECT r.id, r.interaction_id, r.organization_id, r.lms_course, r.lms_group, r.created_at,
                   p.display_name AS created_by_name
            FROM teacher_rosters r
            LEFT JOIN crm_user_profiles p ON p.id = r.created_by
            """;

    private final JdbcClient jdbcClient;

    public TeacherRosterRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    Optional<WorkRow> findWork(UUID interactionId) {
        return jdbcClient.sql("SELECT id, organization_id, title FROM interactions WHERE id = :id")
                .param("id", interactionId)
                .query((resultSet, rowNumber) -> new WorkRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("title")
                ))
                .optional();
    }

    List<RosterRow> findRosters(UUID interactionId) {
        return jdbcClient.sql(ROSTER_SELECT + "WHERE r.interaction_id = :interactionId ORDER BY r.created_at, r.id")
                .param("interactionId", interactionId)
                .query(this::mapRoster)
                .list();
    }

    Optional<RosterRow> findRoster(UUID rosterId) {
        return jdbcClient.sql(ROSTER_SELECT + "WHERE r.id = :id")
                .param("id", rosterId)
                .query(this::mapRoster)
                .optional();
    }

    void lockRoster(UUID rosterId) {
        jdbcClient.sql("SELECT id FROM teacher_rosters WHERE id = :id FOR UPDATE")
                .param("id", rosterId)
                .query(UUID.class)
                .optional();
    }

    Optional<UUID> findExportRoster(UUID exportId) {
        return jdbcClient.sql("SELECT roster_id FROM teacher_roster_exports WHERE id = :id")
                .param("id", exportId)
                .query(UUID.class)
                .optional();
    }

    boolean rosterExists(UUID interactionId, String lmsCourse, String lmsGroup) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM teacher_rosters
                WHERE interaction_id = :interactionId
                  AND LOWER(lms_course) = LOWER(:course)
                  AND LOWER(COALESCE(lms_group, '')) = LOWER(:group)
                """)
                .param("interactionId", interactionId)
                .param("course", lmsCourse)
                .param("group", lmsGroup == null ? "" : lmsGroup)
                .query(Integer.class)
                .single() > 0;
    }

    void insertRoster(UUID id, WorkRow work, String lmsCourse, String lmsGroup, UUID actorId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO teacher_rosters (id, interaction_id, organization_id, lms_course, lms_group, created_by, created_at)
                VALUES (:id, :interactionId, :organizationId, :course, :group, :actorId, :now)
                """)
                .param("id", id)
                .param("interactionId", work.id())
                .param("organizationId", work.organizationId())
                .param("course", lmsCourse)
                .param("group", lmsGroup)
                .param("actorId", actorId)
                .param("now", now)
                .update();
    }

    boolean hasExports(UUID rosterId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM teacher_roster_exports WHERE roster_id = :id")
                .param("id", rosterId)
                .query(Integer.class)
                .single() > 0;
    }

    void deleteRoster(UUID rosterId) {
        jdbcClient.sql("DELETE FROM teacher_roster_members WHERE roster_id = :id").param("id", rosterId).update();
        jdbcClient.sql("DELETE FROM teacher_rosters WHERE id = :id").param("id", rosterId).update();
    }

    List<MemberRow> findMembers(List<UUID> rosterIds) {
        if (rosterIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                SELECT m.roster_id, m.contact_id, c.name, c.position, c.email, c.decision_role, c.inactive,
                       c.personal_data_status, m.export_id, e.exported_at, m.transferred_at
                FROM teacher_roster_members m
                JOIN contacts c ON c.id = m.contact_id
                LEFT JOIN teacher_roster_exports e ON e.id = m.export_id
                WHERE m.roster_id IN (:rosterIds)
                ORDER BY c.name, c.id
                """)
                .param("rosterIds", rosterIds)
                .query((resultSet, rowNumber) -> {
                    String role = resultSet.getString("decision_role");
                    return new MemberRow(
                            resultSet.getObject("roster_id", UUID.class),
                            resultSet.getObject("contact_id", UUID.class),
                            resultSet.getString("name"),
                            resultSet.getString("position"),
                            resultSet.getString("email"),
                            role == null ? null : ContactRole.valueOf(role),
                            resultSet.getBoolean("inactive"),
                            PersonalDataStatus.valueOf(resultSet.getString("personal_data_status")),
                            resultSet.getObject("export_id", UUID.class),
                            resultSet.getObject("exported_at", OffsetDateTime.class),
                            resultSet.getObject("transferred_at", OffsetDateTime.class)
                    );
                })
                .list();
    }

    List<ExportRow> findExports(List<UUID> rosterIds) {
        if (rosterIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                SELECT e.roster_id, e.id, e.rows_count, e.exported_at, e.transferred_at,
                       exporter.display_name AS exported_by_name, marker.display_name AS transferred_by_name
                FROM teacher_roster_exports e
                LEFT JOIN crm_user_profiles exporter ON exporter.id = e.exported_by
                LEFT JOIN crm_user_profiles marker ON marker.id = e.transferred_by
                WHERE e.roster_id IN (:rosterIds)
                ORDER BY e.exported_at DESC, e.id
                """)
                .param("rosterIds", rosterIds)
                .query((resultSet, rowNumber) -> new ExportRow(
                        resultSet.getObject("roster_id", UUID.class),
                        new TeacherRosterExport(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getInt("rows_count"),
                                resultSet.getString("exported_by_name"),
                                resultSet.getObject("exported_at", OffsetDateTime.class),
                                resultSet.getString("transferred_by_name"),
                                resultSet.getObject("transferred_at", OffsetDateTime.class)
                        )
                ))
                .list();
    }

    Optional<ContactRow> findContact(UUID organizationId, UUID contactId) {
        return jdbcClient.sql("""
                SELECT id, inactive, personal_data_status FROM contacts
                WHERE id = :contactId AND organization_id = :organizationId
                """)
                .param("contactId", contactId)
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> new ContactRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getBoolean("inactive"),
                        PersonalDataStatus.valueOf(resultSet.getString("personal_data_status"))
                ))
                .optional();
    }

    void insertMember(RosterRow roster, UUID contactId, UUID actorId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO teacher_roster_members (roster_id, organization_id, contact_id, added_by, added_at)
                SELECT :rosterId, :organizationId, :contactId, :actorId, :now
                WHERE NOT EXISTS (
                    SELECT 1 FROM teacher_roster_members WHERE roster_id = :rosterId AND contact_id = :contactId
                )
                """)
                .param("rosterId", roster.id())
                .param("organizationId", roster.organizationId())
                .param("contactId", contactId)
                .param("actorId", actorId)
                .param("now", now)
                .update();
    }

    int deleteMember(UUID rosterId, UUID contactId) {
        return jdbcClient.sql("""
                DELETE FROM teacher_roster_members
                WHERE roster_id = :rosterId AND contact_id = :contactId AND transferred_at IS NULL
                """)
                .param("rosterId", rosterId)
                .param("contactId", contactId)
                .update();
    }

    void insertExport(UUID exportId, UUID rosterId, int rows, UUID actorId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO teacher_roster_exports (id, roster_id, rows_count, exported_by, exported_at)
                VALUES (:id, :rosterId, :rows, :actorId, :now)
                """)
                .param("id", exportId)
                .param("rosterId", rosterId)
                .param("rows", rows)
                .param("actorId", actorId)
                .param("now", now)
                .update();
    }

    void markExported(UUID rosterId, List<UUID> contactIds, UUID exportId) {
        if (contactIds.isEmpty()) {
            return;
        }
        jdbcClient.sql("""
                UPDATE teacher_roster_members SET export_id = :exportId
                WHERE roster_id = :rosterId AND contact_id IN (:contactIds) AND transferred_at IS NULL
                """)
                .param("exportId", exportId)
                .param("rosterId", rosterId)
                .param("contactIds", contactIds)
                .update();
    }

    int markTransferred(UUID exportId, UUID actorId, OffsetDateTime now) {
        int marked = jdbcClient.sql("""
                UPDATE teacher_roster_members SET transferred_at = :now
                WHERE export_id = :exportId AND transferred_at IS NULL
                """)
                .param("exportId", exportId)
                .param("now", now)
                .update();
        jdbcClient.sql("""
                UPDATE teacher_roster_exports SET transferred_at = :now, transferred_by = :actorId
                WHERE id = :exportId AND transferred_at IS NULL
                """)
                .param("exportId", exportId)
                .param("actorId", actorId)
                .param("now", now)
                .update();
        return marked;
    }

    private RosterRow mapRoster(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RosterRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("interaction_id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getString("lms_course"),
                resultSet.getString("lms_group"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getString("created_by_name")
        );
    }

    record WorkRow(UUID id, UUID organizationId, String title) {
    }

    record RosterRow(
            UUID id,
            UUID interactionId,
            UUID organizationId,
            String lmsCourse,
            String lmsGroup,
            OffsetDateTime createdAt,
            String createdByName
    ) {
    }

    record MemberRow(
            UUID rosterId,
            UUID contactId,
            String name,
            String position,
            String email,
            ContactRole role,
            boolean inactive,
            PersonalDataStatus personalDataStatus,
            UUID exportId,
            OffsetDateTime exportedAt,
            OffsetDateTime transferredAt
    ) {
        @Override
        public String toString() {
            return "MemberRow[" + contactId + "]";
        }
    }

    record ExportRow(UUID rosterId, TeacherRosterExport export) {
    }

    record ContactRow(UUID id, boolean inactive, PersonalDataStatus personalDataStatus) {
    }
}
