package ru.rtk.crm.partner;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.attachment.AttachmentKind;
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.interaction.InteractionWorkStatus;
import ru.rtk.crm.partner.PartnerModels.PartnerDocument;

@Repository
public class PartnerCabinetRepository {
    private static final String VISIBLE_DOCUMENT = """
            FROM attachments a
            JOIN interactions i ON i.id = a.interaction_id
            WHERE i.organization_id = :organizationId AND a.partner_visible = TRUE AND a.deleted_at IS NULL
            """;

    private final JdbcClient jdbcClient;

    public PartnerCabinetRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<UUID> findOrganizationId(UUID profileId) {
        return jdbcClient.sql("""
                SELECT p.partner_organization_id
                FROM crm_user_profiles p
                JOIN organizations o ON o.id = p.partner_organization_id
                JOIN contacts c ON c.id = p.partner_contact_id AND c.organization_id = o.id
                WHERE p.id = :profileId AND p.role = 'PARTNER' AND p.active = TRUE
                  AND o.status = 'ACTIVE' AND c.inactive = FALSE AND c.personal_data_status = 'ACTIVE'
                """)
                .param("profileId", profileId)
                .query(UUID.class)
                .optional();
    }

    public OrganizationRow findOrganization(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT o.id, o.name, o.type, owner.display_name AS manager_name
                FROM organizations o
                LEFT JOIN crm_user_profiles owner ON owner.id = o.owner_manager_id AND owner.active = TRUE
                WHERE o.id = :organizationId
                """)
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> new OrganizationRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        OrganizationType.valueOf(resultSet.getString("type")),
                        resultSet.getString("manager_name")
                ))
                .single();
    }

    public List<WorkRow> findWorks(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT i.id, i.title, program.name AS program_name, stage.name AS stage_name, i.work_status,
                       i.next_action, i.next_action_at, i.next_step_partner_visible
                FROM interactions i
                JOIN interaction_stages stage ON stage.id = i.current_stage_id AND stage.interaction_id = i.id
                LEFT JOIN programs program ON program.id = i.program_id
                WHERE i.organization_id = :organizationId
                ORDER BY i.created_at DESC, i.id
                """)
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> new WorkRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getString("program_name"),
                        resultSet.getString("stage_name"),
                        InteractionWorkStatus.valueOf(resultSet.getString("work_status")),
                        resultSet.getString("next_action"),
                        resultSet.getObject("next_action_at", OffsetDateTime.class),
                        resultSet.getBoolean("next_step_partner_visible")
                ))
                .list();
    }

    public List<ProductRow> findProducts(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT agreement.interaction_id, product.name
                FROM product_agreements agreement
                JOIN interactions i ON i.id = agreement.interaction_id
                JOIN products product ON product.id = agreement.product_id
                WHERE i.organization_id = :organizationId AND agreement.archived_at IS NULL
                ORDER BY product.name, agreement.id
                """)
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> new ProductRow(
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getString("name")
                ))
                .list();
    }

    public List<StageRow> findStagesBeforeCurrent(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT stage.interaction_id, stage.name, completion.completed_on,
                       (SELECT MIN(event.occurred_at)
                        FROM interaction_events event
                        WHERE event.interaction_id = stage.interaction_id AND event.from_stage_id = stage.id
                          AND event.type = 'TRANSITIONED') AS left_at
                FROM interaction_stages stage
                JOIN interactions i ON i.id = stage.interaction_id
                JOIN interaction_stages current_stage ON current_stage.id = i.current_stage_id AND current_stage.interaction_id = i.id
                LEFT JOIN interaction_stage_completions completion
                  ON completion.interaction_id = stage.interaction_id AND completion.stage_id = stage.id
                WHERE i.organization_id = :organizationId AND stage.stage_order < current_stage.stage_order
                ORDER BY stage.interaction_id, stage.stage_order
                """)
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> new StageRow(
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getObject("completed_on", LocalDate.class),
                        resultSet.getObject("left_at", OffsetDateTime.class)
                ))
                .list();
    }

    public List<PartnerDocument> findDocuments(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT a.id, a.interaction_id, i.title, a.original_name, a.kind, a.media_type, a.size_bytes, a.status, a.created_at
                """ + VISIBLE_DOCUMENT + """
                  AND a.status <> 'REJECTED'
                ORDER BY a.created_at DESC, a.id
                """)
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> new PartnerDocument(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getString("original_name"),
                        AttachmentKind.valueOf(resultSet.getString("kind")),
                        resultSet.getString("media_type"),
                        resultSet.getLong("size_bytes"),
                        "CLEAN".equals(resultSet.getString("status")),
                        resultSet.getObject("created_at", OffsetDateTime.class)
                ))
                .list();
    }

    public Optional<DownloadRow> findDownloadable(UUID organizationId, UUID attachmentId) {
        return jdbcClient.sql("""
                SELECT a.id, a.original_name, a.media_type, a.size_bytes, a.storage_key
                """ + VISIBLE_DOCUMENT + """
                  AND a.id = :attachmentId AND a.status = 'CLEAN'
                """)
                .param("organizationId", organizationId)
                .param("attachmentId", attachmentId)
                .query((resultSet, rowNumber) -> new DownloadRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("original_name"),
                        resultSet.getString("media_type"),
                        resultSet.getLong("size_bytes"),
                        resultSet.getObject("storage_key", UUID.class)
                ))
                .optional();
    }

    public List<AgreementRow> findAgreements(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT id, number, concluded_on, valid_until, status
                FROM agreements
                WHERE organization_id = :organizationId
                ORDER BY concluded_on DESC NULLS LAST, number, id
                """)
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> new AgreementRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("number"),
                        resultSet.getObject("concluded_on", LocalDate.class),
                        resultSet.getObject("valid_until", LocalDate.class),
                        AgreementStatus.valueOf(resultSet.getString("status"))
                ))
                .list();
    }

    public List<ActivityRow> findAgreementActivities(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT activity.agreement_id, activity.title
                FROM agreement_activities activity
                JOIN agreements agreement ON agreement.id = activity.agreement_id
                WHERE agreement.organization_id = :organizationId AND activity.status <> 'CANCELLED'
                ORDER BY activity.planned_start NULLS LAST, activity.title, activity.id
                """)
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> new ActivityRow(
                        resultSet.getObject("agreement_id", UUID.class),
                        resultSet.getString("title")
                ))
                .list();
    }

    public record OrganizationRow(UUID id, String name, OrganizationType type, String managerName) {
    }

    public record WorkRow(
            UUID id,
            String title,
            String programName,
            String stageName,
            InteractionWorkStatus status,
            String nextAction,
            OffsetDateTime nextActionAt,
            boolean nextStepPartnerVisible
    ) {
    }

    public record ProductRow(UUID interactionId, String name) {
    }

    public record StageRow(UUID interactionId, String name, LocalDate completedOn, OffsetDateTime leftAt) {
    }

    public record DownloadRow(UUID id, String originalName, String mediaType, long sizeBytes, UUID storageKey) {
    }

    public record AgreementRow(UUID id, String number, LocalDate concludedOn, LocalDate validUntil, AgreementStatus status) {
    }

    public record ActivityRow(UUID agreementId, String title) {
    }
}
