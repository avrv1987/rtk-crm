package ru.rtk.crm.agreement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.agreement.AgreementModels.Activity;
import ru.rtk.crm.agreement.AgreementModels.ActivityKind;
import ru.rtk.crm.agreement.AgreementModels.ActivityStatus;
import ru.rtk.crm.agreement.AgreementModels.Agreement;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.AgreementSummary;
import ru.rtk.crm.agreement.AgreementModels.Confirmation;
import ru.rtk.crm.agreement.AgreementModels.ConfirmationQuery;
import ru.rtk.crm.agreement.AgreementModels.LinkedAttachment;
import ru.rtk.crm.agreement.AgreementModels.LinkedInteraction;
import ru.rtk.crm.agreement.AgreementModels.Responsible;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;

@Repository
public class AgreementRepository {
    public static final String ACTIVITY_START =
            "COALESCE(ac.actual_start, ac.planned_start, ac.actual_end, ac.planned_end, ag.concluded_on)";
    public static final String ACTIVITY_END =
            "COALESCE(ac.actual_end, ac.planned_end, ac.actual_start, ac.planned_start, ag.valid_until)";

    private static final String ATTACHMENT_COLUMNS = """
            a.id AS attachment_id, a.original_name, a.interaction_id, i.title AS interaction_title,
            st.name AS stage_name, a.status AS attachment_status, a.created_at AS attachment_created_at
            """;
    private static final String ATTACHMENT_FROM = """
            FROM attachments a
            JOIN interactions i ON i.id = a.interaction_id
            JOIN interaction_stages st ON st.id = a.stage_id
            """;

    private final JdbcClient jdbcClient;

    public AgreementRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ActivityKind> findKinds(boolean includeArchived) {
        return jdbcClient.sql("""
                SELECT id, name, sort_order, archived, version
                FROM agreement_activity_kinds
                %s
                ORDER BY sort_order, name, id
                """.formatted(includeArchived ? "" : "WHERE archived = FALSE"))
                .query(this::mapKind)
                .list();
    }

    public Optional<ActivityKind> findKind(UUID id) {
        return jdbcClient.sql("SELECT id, name, sort_order, archived, version FROM agreement_activity_kinds WHERE id = :id")
                .param("id", id)
                .query(this::mapKind)
                .optional();
    }

    public void insertKind(UUID id, String name, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO agreement_activity_kinds (id, name, sort_order, archived, version, created_at, updated_at)
                SELECT :id, :name, COALESCE(MAX(sort_order), 0) + 10, FALSE, 0, :now, :now
                FROM agreement_activity_kinds
                """)
                .param("id", id)
                .param("name", name)
                .param("now", now)
                .update();
    }

    public int updateKind(UUID id, int version, String name, boolean archived, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE agreement_activity_kinds
                SET name = :name, archived = :archived, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :version
                """)
                .param("id", id)
                .param("version", version)
                .param("name", name)
                .param("archived", archived)
                .param("now", now)
                .update();
    }

    public List<AgreementSummary> findSummaries(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT ag.id, ag.organization_id, ag.number, ag.concluded_on, ag.valid_until, ag.status, ag.version,
                       (SELECT COUNT(*) FROM agreement_activities ac WHERE ac.agreement_id = ag.id) AS activity_count,
                       (SELECT COUNT(*) FROM agreement_activity_attachments aa
                        JOIN agreement_activities ac ON ac.id = aa.activity_id
                        JOIN attachments ca ON ca.id = aa.attachment_id
                        WHERE ac.agreement_id = ag.id AND ca.deleted_at IS NULL) AS confirmation_count
                FROM agreements ag
                WHERE ag.organization_id = :organizationId
                ORDER BY ag.concluded_on DESC NULLS LAST, ag.number, ag.id
                """)
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> new AgreementSummary(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("number"),
                        resultSet.getObject("concluded_on", LocalDate.class),
                        resultSet.getObject("valid_until", LocalDate.class),
                        AgreementStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("activity_count"),
                        resultSet.getInt("confirmation_count"),
                        resultSet.getInt("version")
                ))
                .list();
    }

    public Optional<AgreementRow> findAgreementRow(UUID agreementId) {
        return jdbcClient.sql("SELECT id, organization_id, version FROM agreements WHERE id = :id")
                .param("id", agreementId)
                .query((resultSet, rowNumber) -> new AgreementRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getInt("version")
                ))
                .optional();
    }

    public Optional<Agreement> findAgreement(UUID agreementId) {
        return jdbcClient.sql("""
                SELECT ag.id, ag.organization_id, o.name AS organization_name, ag.number, ag.concluded_on, ag.valid_until,
                       ag.parties, ag.status, ag.version, ag.created_at, ag.updated_at, ag.file_attachment_id
                FROM agreements ag
                JOIN organizations o ON o.id = ag.organization_id
                WHERE ag.id = :id
                """)
                .param("id", agreementId)
                .query((resultSet, rowNumber) -> new AgreementHeader(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getString("number"),
                        resultSet.getObject("concluded_on", LocalDate.class),
                        resultSet.getObject("valid_until", LocalDate.class),
                        resultSet.getString("parties"),
                        AgreementStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("version"),
                        resultSet.getObject("created_at", OffsetDateTime.class),
                        resultSet.getObject("updated_at", OffsetDateTime.class),
                        resultSet.getObject("file_attachment_id", UUID.class)
                ))
                .optional()
                .map(header -> new Agreement(
                        header.id(),
                        header.organizationId(),
                        header.organizationName(),
                        header.number(),
                        header.concludedOn(),
                        header.validUntil(),
                        header.parties(),
                        header.status(),
                        header.fileAttachmentId() == null ? null : findAttachments(List.of(header.fileAttachmentId()))
                                .stream().findFirst().orElse(null),
                        header.version(),
                        header.createdAt(),
                        header.updatedAt(),
                        findActivities(header.id())
                ));
    }

    public void insertAgreement(UUID id, UUID organizationId, AgreementValues values, UUID createdBy, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO agreements (
                    id, organization_id, number, concluded_on, valid_until, parties, status, file_attachment_id,
                    version, created_by, created_at, updated_at
                ) VALUES (
                    :id, :organizationId, :number, :concludedOn, :validUntil, :parties, :status, :fileAttachmentId,
                    0, :createdBy, :now, :now
                )
                """)
                .param("id", id)
                .param("organizationId", organizationId)
                .params(values.parameters())
                .param("createdBy", createdBy)
                .param("now", now)
                .update();
    }

    public int updateAgreement(UUID id, int version, AgreementValues values, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE agreements
                SET number = :number, concluded_on = :concludedOn, valid_until = :validUntil, parties = :parties,
                    status = :status, file_attachment_id = :fileAttachmentId, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :version
                """)
                .param("id", id)
                .param("version", version)
                .params(values.parameters())
                .param("now", now)
                .update();
    }

    public Optional<ActivityRow> findActivityRow(UUID activityId) {
        return jdbcClient.sql("""
                SELECT ac.id, ac.agreement_id, ag.organization_id, ac.kind_id, ac.responsible_profile_id, ac.version
                FROM agreement_activities ac
                JOIN agreements ag ON ag.id = ac.agreement_id
                WHERE ac.id = :id
                """)
                .param("id", activityId)
                .query((resultSet, rowNumber) -> new ActivityRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("agreement_id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getObject("kind_id", UUID.class),
                        resultSet.getObject("responsible_profile_id", UUID.class),
                        resultSet.getInt("version")
                ))
                .optional();
    }

    public Optional<Activity> findActivity(UUID activityId) {
        return activities("ac.id = :id", Map.of("id", activityId)).stream().findFirst();
    }

    public List<Activity> findActivities(UUID agreementId) {
        return activities("ac.agreement_id = :agreementId", Map.of("agreementId", agreementId));
    }

    public void insertActivity(UUID id, UUID agreementId, ActivityValues values, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO agreement_activities (
                    id, agreement_id, kind_id, title, unit, planned_volume, actual_volume, planned_start, planned_end,
                    actual_start, actual_end, responsible_profile_id, status, version, created_at, updated_at
                ) VALUES (
                    :id, :agreementId, :kindId, :title, :unit, :plannedVolume, :actualVolume, :plannedStart, :plannedEnd,
                    :actualStart, :actualEnd, :responsibleProfileId, :status, 0, :now, :now
                )
                """)
                .param("id", id)
                .param("agreementId", agreementId)
                .params(values.parameters())
                .param("now", now)
                .update();
    }

    public int updateActivity(UUID id, int version, ActivityValues values, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE agreement_activities
                SET kind_id = :kindId, title = :title, unit = :unit, planned_volume = :plannedVolume,
                    actual_volume = :actualVolume, planned_start = :plannedStart, planned_end = :plannedEnd,
                    actual_start = :actualStart, actual_end = :actualEnd, responsible_profile_id = :responsibleProfileId,
                    status = :status, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :version
                """)
                .param("id", id)
                .param("version", version)
                .params(values.parameters())
                .param("now", now)
                .update();
    }

    public int deleteActivity(UUID id, int version) {
        return jdbcClient.sql("DELETE FROM agreement_activities WHERE id = :id AND version = :version")
                .param("id", id)
                .param("version", version)
                .update();
    }

    public void replaceActivityLinks(UUID activityId, List<UUID> interactionIds, List<UUID> attachmentIds) {
        jdbcClient.sql("DELETE FROM agreement_activity_interactions WHERE activity_id = :activityId")
                .param("activityId", activityId)
                .update();
        jdbcClient.sql("DELETE FROM agreement_activity_attachments WHERE activity_id = :activityId")
                .param("activityId", activityId)
                .update();
        for (UUID interactionId : interactionIds) {
            jdbcClient.sql("INSERT INTO agreement_activity_interactions (activity_id, interaction_id) VALUES (:activityId, :interactionId)")
                    .param("activityId", activityId)
                    .param("interactionId", interactionId)
                    .update();
        }
        for (UUID attachmentId : attachmentIds) {
            jdbcClient.sql("INSERT INTO agreement_activity_attachments (activity_id, attachment_id) VALUES (:activityId, :attachmentId)")
                    .param("activityId", activityId)
                    .param("attachmentId", attachmentId)
                    .update();
        }
    }

    public List<LinkedInteraction> findOrganizationInteractions(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT id, title FROM interactions
                WHERE organization_id = :organizationId
                ORDER BY created_at DESC, id
                """)
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> new LinkedInteraction(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("title")
                ))
                .list();
    }

    public List<LinkedAttachment> findOrganizationCleanAttachments(UUID organizationId) {
        return jdbcClient.sql("SELECT " + ATTACHMENT_COLUMNS + ATTACHMENT_FROM + """
                WHERE i.organization_id = :organizationId AND a.status = 'CLEAN' AND a.deleted_at IS NULL
                ORDER BY a.created_at DESC, a.id
                """)
                .param("organizationId", organizationId)
                .query(this::mapAttachment)
                .list();
    }

    public List<Responsible> findResponsibles(UUID teamId) {
        return jdbcClient.sql("""
                SELECT id, display_name FROM crm_user_profiles
                WHERE team_id = :teamId AND active = TRUE AND role IN ('USER', 'LEADER')
                ORDER BY display_name, id
                """)
                .param("teamId", teamId)
                .query((resultSet, rowNumber) -> new Responsible(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("display_name")
                ))
                .list();
    }

    public Set<UUID> findOrganizationInteractionIds(UUID organizationId, List<UUID> interactionIds) {
        if (interactionIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql("""
                SELECT id FROM interactions WHERE organization_id = :organizationId AND id IN (:ids)
                """)
                .param("organizationId", organizationId)
                .param("ids", interactionIds)
                .query(UUID.class)
                .list());
    }

    public Set<UUID> findOrganizationCleanAttachmentIds(UUID organizationId, List<UUID> attachmentIds) {
        if (attachmentIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql("""
                SELECT a.id FROM attachments a
                JOIN interactions i ON i.id = a.interaction_id
                WHERE i.organization_id = :organizationId AND a.status = 'CLEAN' AND a.deleted_at IS NULL AND a.id IN (:ids)
                """)
                .param("organizationId", organizationId)
                .param("ids", attachmentIds)
                .query(UUID.class)
                .list());
    }

    public List<ConfirmationRow> findConfirmations(VisibilityScope scope, ConfirmationQuery query, int limit) {
        List<String> conditions = new ArrayList<>(List.of(
                "ag.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")",
                "i.organization_id = ag.organization_id",
                "a.deleted_at IS NULL"
        ));
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        if (query.organizationId() != null) {
            conditions.add("ag.organization_id = :organizationId");
            parameters.put("organizationId", query.organizationId());
        }
        if (query.agreementId() != null) {
            conditions.add("ag.id = :agreementId");
            parameters.put("agreementId", query.agreementId());
        }
        if (query.kindId() != null) {
            conditions.add("ac.kind_id = :kindId");
            parameters.put("kindId", query.kindId());
        }
        periodCondition(conditions, parameters, query.from(), query.to());
        return jdbcClient.sql("""
                SELECT o.id AS organization_id, o.name AS organization_name, ag.id AS agreement_id, ag.number,
                       ac.id AS activity_id, ac.title AS activity_title, k.id AS kind_id, k.name AS kind_name,
                       %s AS activity_start, %s AS activity_end,
                       a.id AS attachment_id, a.original_name, a.size_bytes, a.status, a.created_at, a.storage_key,
                       i.title AS interaction_title
                FROM agreement_activity_attachments aa
                JOIN agreement_activities ac ON ac.id = aa.activity_id
                JOIN agreements ag ON ag.id = ac.agreement_id
                JOIN organizations o ON o.id = ag.organization_id
                JOIN agreement_activity_kinds k ON k.id = ac.kind_id
                JOIN attachments a ON a.id = aa.attachment_id
                JOIN interactions i ON i.id = a.interaction_id
                WHERE %s
                ORDER BY k.sort_order, k.name, o.name, ag.number, ac.title, ac.id, a.created_at, a.id
                LIMIT :limit
                """.formatted(ACTIVITY_START, ACTIVITY_END, String.join(" AND ", conditions)))
                .params(parameters)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new ConfirmationRow(
                        new Confirmation(
                                resultSet.getObject("organization_id", UUID.class),
                                resultSet.getString("organization_name"),
                                resultSet.getObject("agreement_id", UUID.class),
                                resultSet.getString("number"),
                                resultSet.getObject("activity_id", UUID.class),
                                resultSet.getString("activity_title"),
                                resultSet.getObject("kind_id", UUID.class),
                                resultSet.getString("kind_name"),
                                resultSet.getObject("activity_start", LocalDate.class),
                                resultSet.getObject("activity_end", LocalDate.class),
                                resultSet.getObject("attachment_id", UUID.class),
                                resultSet.getString("original_name"),
                                resultSet.getLong("size_bytes"),
                                resultSet.getString("status"),
                                resultSet.getObject("created_at", OffsetDateTime.class),
                                resultSet.getString("interaction_title")
                        ),
                        resultSet.getObject("storage_key", UUID.class)
                ))
                .list();
    }

    public static void periodCondition(List<String> conditions, Map<String, Object> parameters, LocalDate from, LocalDate to) {
        if (to != null) {
            conditions.add("(" + ACTIVITY_START + " IS NULL OR " + ACTIVITY_START + " <= :periodTo)");
            parameters.put("periodTo", to);
        }
        if (from != null) {
            conditions.add("(" + ACTIVITY_END + " IS NULL OR " + ACTIVITY_END + " >= :periodFrom)");
            parameters.put("periodFrom", from);
        }
    }

    private List<Activity> activities(String condition, Map<String, Object> parameters) {
        List<ActivityHeader> headers = jdbcClient.sql("""
                SELECT ac.id, ac.agreement_id, ac.kind_id, k.name AS kind_name, ac.title, ac.unit, ac.planned_volume,
                       ac.actual_volume, ac.planned_start, ac.planned_end, ac.actual_start, ac.actual_end,
                       ac.responsible_profile_id, rp.display_name AS responsible_name, ac.status, ac.version
                FROM agreement_activities ac
                JOIN agreement_activity_kinds k ON k.id = ac.kind_id
                LEFT JOIN crm_user_profiles rp ON rp.id = ac.responsible_profile_id
                WHERE %s
                ORDER BY k.sort_order, k.name, ac.planned_start NULLS LAST, ac.title, ac.id
                """.formatted(condition))
                .params(parameters)
                .query(this::mapActivityHeader)
                .list();
        if (headers.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = headers.stream().map(ActivityHeader::id).toList();
        Map<UUID, List<LinkedInteraction>> interactions = new HashMap<>();
        jdbcClient.sql("""
                SELECT ai.activity_id, i.id, i.title
                FROM agreement_activity_interactions ai
                JOIN interactions i ON i.id = ai.interaction_id
                WHERE ai.activity_id IN (:ids)
                ORDER BY i.title, i.id
                """)
                .param("ids", ids)
                .query((ResultSet resultSet) -> {
                    interactions.computeIfAbsent(resultSet.getObject("activity_id", UUID.class), key -> new ArrayList<>())
                            .add(new LinkedInteraction(resultSet.getObject("id", UUID.class), resultSet.getString("title")));
                });
        Map<UUID, List<LinkedAttachment>> attachments = new HashMap<>();
        jdbcClient.sql("SELECT aa.activity_id, " + ATTACHMENT_COLUMNS + ATTACHMENT_FROM + """
                JOIN agreement_activity_attachments aa ON aa.attachment_id = a.id
                WHERE aa.activity_id IN (:ids) AND a.deleted_at IS NULL
                ORDER BY a.created_at, a.id
                """)
                .param("ids", ids)
                .query((ResultSet resultSet) -> {
                    attachments.computeIfAbsent(resultSet.getObject("activity_id", UUID.class), key -> new ArrayList<>())
                            .add(mapAttachment(resultSet, 0));
                });
        return headers.stream()
                .map(header -> header.activity(
                        interactions.getOrDefault(header.id(), List.of()),
                        attachments.getOrDefault(header.id(), List.of())
                ))
                .toList();
    }

    private List<LinkedAttachment> findAttachments(List<UUID> attachmentIds) {
        return jdbcClient.sql("SELECT " + ATTACHMENT_COLUMNS + ATTACHMENT_FROM + " WHERE a.id IN (:ids)")
                .param("ids", attachmentIds)
                .query(this::mapAttachment)
                .list();
    }

    private ActivityKind mapKind(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ActivityKind(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getInt("sort_order"),
                resultSet.getBoolean("archived"),
                resultSet.getInt("version")
        );
    }

    private LinkedAttachment mapAttachment(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LinkedAttachment(
                resultSet.getObject("attachment_id", UUID.class),
                resultSet.getString("original_name"),
                resultSet.getObject("interaction_id", UUID.class),
                resultSet.getString("interaction_title"),
                resultSet.getString("stage_name"),
                resultSet.getString("attachment_status"),
                resultSet.getObject("attachment_created_at", OffsetDateTime.class)
        );
    }

    private ActivityHeader mapActivityHeader(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ActivityHeader(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("agreement_id", UUID.class),
                resultSet.getObject("kind_id", UUID.class),
                resultSet.getString("kind_name"),
                resultSet.getString("title"),
                resultSet.getString("unit"),
                resultSet.getObject("planned_volume", Integer.class),
                resultSet.getObject("actual_volume", Integer.class),
                resultSet.getObject("planned_start", LocalDate.class),
                resultSet.getObject("planned_end", LocalDate.class),
                resultSet.getObject("actual_start", LocalDate.class),
                resultSet.getObject("actual_end", LocalDate.class),
                resultSet.getObject("responsible_profile_id", UUID.class),
                resultSet.getString("responsible_name"),
                ActivityStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("version")
        );
    }

    public record AgreementRow(UUID id, UUID organizationId, int version) {
    }

    public record ActivityRow(UUID id, UUID agreementId, UUID organizationId, UUID kindId, UUID responsibleProfileId, int version) {
    }

    public record ConfirmationRow(Confirmation confirmation, UUID storageKey) {
    }

    public record AgreementValues(
            String number,
            LocalDate concludedOn,
            LocalDate validUntil,
            String parties,
            AgreementStatus status,
            UUID fileAttachmentId
    ) {
        Map<String, Object> parameters() {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("number", number);
            parameters.put("concludedOn", concludedOn);
            parameters.put("validUntil", validUntil);
            parameters.put("parties", parties);
            parameters.put("status", status.name());
            parameters.put("fileAttachmentId", fileAttachmentId);
            return parameters;
        }
    }

    public record ActivityValues(
            UUID kindId,
            String title,
            String unit,
            Integer plannedVolume,
            Integer actualVolume,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            LocalDate actualStart,
            LocalDate actualEnd,
            UUID responsibleProfileId,
            ActivityStatus status
    ) {
        Map<String, Object> parameters() {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("kindId", kindId);
            parameters.put("title", title);
            parameters.put("unit", unit);
            parameters.put("plannedVolume", plannedVolume);
            parameters.put("actualVolume", actualVolume);
            parameters.put("plannedStart", plannedStart);
            parameters.put("plannedEnd", plannedEnd);
            parameters.put("actualStart", actualStart);
            parameters.put("actualEnd", actualEnd);
            parameters.put("responsibleProfileId", responsibleProfileId);
            parameters.put("status", status.name());
            return parameters;
        }
    }

    private record AgreementHeader(
            UUID id,
            UUID organizationId,
            String organizationName,
            String number,
            LocalDate concludedOn,
            LocalDate validUntil,
            String parties,
            AgreementStatus status,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            UUID fileAttachmentId
    ) {
    }

    private record ActivityHeader(
            UUID id,
            UUID agreementId,
            UUID kindId,
            String kindName,
            String title,
            String unit,
            Integer plannedVolume,
            Integer actualVolume,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            LocalDate actualStart,
            LocalDate actualEnd,
            UUID responsibleProfileId,
            String responsibleName,
            ActivityStatus status,
            int version
    ) {
        Activity activity(List<LinkedInteraction> interactions, List<LinkedAttachment> attachments) {
            return new Activity(
                    id,
                    agreementId,
                    kindId,
                    kindName,
                    title,
                    unit,
                    plannedVolume,
                    actualVolume,
                    plannedStart,
                    plannedEnd,
                    actualStart,
                    actualEnd,
                    responsibleProfileId,
                    responsibleName,
                    status,
                    version,
                    List.copyOf(interactions),
                    List.copyOf(attachments)
            );
        }
    }
}
