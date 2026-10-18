package ru.rtk.crm.partner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.attachment.AttachmentStatus;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestStatus;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerResponseDecision;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerResponseKind;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerReviewItem;

@Repository
public class PartnerRequestRepository {
    private static final String REQUEST_SELECT = """
            SELECT r.id, r.interaction_id, r.organization_id, r.contact_id, c.name AS contact_name, i.title AS work_title,
                   r.action, r.expected_result, r.due_on, r.status, r.return_reason, r.clarification, r.cancel_reason,
                   author.display_name AS created_by_name, r.created_at, r.updated_at, r.version
            FROM partner_requests r
            JOIN contacts c ON c.id = r.contact_id
            JOIN interactions i ON i.id = r.interaction_id
            LEFT JOIN crm_user_profiles author ON author.id = r.created_by
            """;

    private final JdbcClient jdbcClient;

    public PartnerRequestRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<RequestRow> findByInteraction(UUID interactionId) {
        return jdbcClient.sql(REQUEST_SELECT + " WHERE r.interaction_id = :interactionId ORDER BY r.created_at DESC, r.id")
                .param("interactionId", interactionId)
                .query(this::mapRequest)
                .list();
    }

    // Координатор видит только запросы, адресованные его контакту в его организации: чужие запросы того же вуза
    // и других вузов в выборку не попадают.
    public List<RequestRow> findForContact(UUID organizationId, UUID contactId) {
        return jdbcClient.sql(REQUEST_SELECT + """
                 WHERE r.organization_id = :organizationId AND r.contact_id = :contactId
                ORDER BY r.created_at DESC, r.id
                """)
                .param("organizationId", organizationId)
                .param("contactId", contactId)
                .query(this::mapRequest)
                .list();
    }

    public Optional<RequestRow> find(UUID requestId) {
        return jdbcClient.sql(REQUEST_SELECT + " WHERE r.id = :id")
                .param("id", requestId)
                .query(this::mapRequest)
                .optional();
    }

    public Optional<UUID> lock(UUID requestId) {
        return jdbcClient.sql("SELECT id FROM partner_requests WHERE id = :id FOR UPDATE")
                .param("id", requestId)
                .query(UUID.class)
                .optional();
    }

    public List<MaterialRow> findMaterials(Collection<UUID> requestIds) {
        if (requestIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                SELECT m.request_id, a.id, a.original_name, a.revision, a.status, a.partner_visible, a.deleted_at,
                       (SELECT receipt.confirmed_at
                        FROM partner_material_receipts receipt
                        JOIN crm_user_profiles partner ON partner.id = receipt.profile_id
                        WHERE receipt.attachment_id = a.id AND partner.partner_contact_id = r.contact_id) AS received_at
                FROM partner_request_materials m
                JOIN partner_requests r ON r.id = m.request_id
                JOIN attachments a ON a.id = m.attachment_id
                WHERE m.request_id IN (:ids)
                ORDER BY a.original_name, a.revision, a.id
                """)
                .param("ids", requestIds)
                .query((resultSet, rowNumber) -> new MaterialRow(
                        resultSet.getObject("request_id", UUID.class),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("original_name"),
                        resultSet.getInt("revision"),
                        AttachmentStatus.valueOf(resultSet.getString("status")),
                        resultSet.getBoolean("partner_visible") && resultSet.getObject("deleted_at") == null,
                        resultSet.getObject("received_at", OffsetDateTime.class)
                ))
                .list();
    }

    public List<ResponseRow> findResponses(Collection<UUID> requestIds) {
        if (requestIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                SELECT response.id, response.request_id, response.receipt_number, response.kind, response.message,
                       response.attachment_id, a.original_name, a.status AS file_status, response.submitted_at,
                       author.display_name AS submitted_by_name, response.decision, response.decision_reason, response.decided_at
                FROM partner_request_responses response
                LEFT JOIN attachments a ON a.id = response.attachment_id
                LEFT JOIN crm_user_profiles author ON author.id = response.submitted_by
                WHERE response.request_id IN (:ids)
                ORDER BY response.submitted_at DESC, response.id
                """)
                .param("ids", requestIds)
                .query((resultSet, rowNumber) -> new ResponseRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("request_id", UUID.class),
                        resultSet.getString("receipt_number"),
                        PartnerResponseKind.valueOf(resultSet.getString("kind")),
                        resultSet.getString("message"),
                        resultSet.getObject("attachment_id", UUID.class),
                        resultSet.getString("original_name"),
                        resultSet.getString("file_status") == null ? null : AttachmentStatus.valueOf(resultSet.getString("file_status")),
                        resultSet.getObject("submitted_at", OffsetDateTime.class),
                        resultSet.getString("submitted_by_name"),
                        resultSet.getString("decision") == null ? null : PartnerResponseDecision.valueOf(resultSet.getString("decision")),
                        resultSet.getString("decision_reason"),
                        resultSet.getObject("decided_at", OffsetDateTime.class)
                ))
                .list();
    }

    public void insert(RequestRow request, UUID createdBy) {
        jdbcClient.sql("""
                INSERT INTO partner_requests (
                    id, interaction_id, organization_id, contact_id, action, expected_result, due_on, status, version,
                    created_by, created_at, updated_at
                ) VALUES (
                    :id, :interactionId, :organizationId, :contactId, :action, :expectedResult, :dueOn, 'AWAITING_RESPONSE', 0,
                    :createdBy, :now, :now
                )
                """)
                .param("id", request.id())
                .param("interactionId", request.interactionId())
                .param("organizationId", request.organizationId())
                .param("contactId", request.contactId())
                .param("action", request.action())
                .param("expectedResult", request.expectedResult())
                .param("dueOn", request.dueOn())
                .param("createdBy", createdBy)
                .param("now", request.createdAt())
                .update();
    }

    public void insertMaterial(UUID requestId, UUID attachmentId) {
        jdbcClient.sql("INSERT INTO partner_request_materials (request_id, attachment_id) VALUES (:requestId, :attachmentId)")
                .param("requestId", requestId)
                .param("attachmentId", attachmentId)
                .update();
    }

    // Переход статуса проверяет версию: две параллельные команды по одному запросу не перезаписывают друг друга.
    public boolean changeStatus(
            UUID requestId,
            int expectedVersion,
            PartnerRequestStatus status,
            Map<String, String> texts,
            OffsetDateTime now
    ) {
        return jdbcClient.sql("""
                UPDATE partner_requests
                SET status = :status,
                    return_reason = CASE WHEN :status = 'RETURNED' THEN :returnReason ELSE return_reason END,
                    clarification = COALESCE(:clarification, clarification),
                    cancel_reason = COALESCE(:cancelReason, cancel_reason),
                    version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """)
                .param("id", requestId)
                .param("expectedVersion", expectedVersion)
                .param("status", status.name())
                .param("returnReason", texts.get("returnReason"))
                .param("clarification", texts.get("clarification"))
                .param("cancelReason", texts.get("cancelReason"))
                .param("now", now)
                .update() == 1;
    }

    public void insertResponse(
            UUID id,
            UUID requestId,
            String receiptNumber,
            PartnerResponseKind kind,
            String message,
            UUID attachmentId,
            UUID submittedBy,
            OffsetDateTime submittedAt
    ) {
        jdbcClient.sql("""
                INSERT INTO partner_request_responses (
                    id, request_id, receipt_number, kind, message, attachment_id, submitted_by, submitted_at
                ) VALUES (:id, :requestId, :receiptNumber, :kind, :message, :attachmentId, :submittedBy, :submittedAt)
                """)
                .param("id", id)
                .param("requestId", requestId)
                .param("receiptNumber", receiptNumber)
                .param("kind", kind.name())
                .param("message", message)
                .param("attachmentId", attachmentId)
                .param("submittedBy", submittedBy)
                .param("submittedAt", submittedAt)
                .update();
    }

    public void decide(UUID responseId, PartnerResponseDecision decision, String reason, UUID decidedBy, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE partner_request_responses
                SET decision = :decision, decision_reason = :reason, decided_by = :decidedBy, decided_at = :now
                WHERE id = :id AND decision IS NULL
                """)
                .param("id", responseId)
                .param("decision", decision.name())
                .param("reason", reason)
                .param("decidedBy", decidedBy)
                .param("now", now)
                .update();
    }

    public List<PartnerReviewItem> findAwaitingReview(String scopeCondition, Map<String, Object> scopeParameters, int limit) {
        return jdbcClient.sql("""
                SELECT r.id, r.interaction_id, r.organization_id, o.name AS organization_name, i.title, r.action, r.status,
                       r.updated_at
                FROM partner_requests r
                JOIN interactions i ON i.id = r.interaction_id
                JOIN organizations o ON o.id = r.organization_id
                WHERE r.status IN ('RESPONDED', 'CLARIFICATION_NEEDED')
                  AND r.organization_id IN (SELECT id FROM organizations WHERE %s)
                ORDER BY r.updated_at, r.id
                LIMIT :limit
                """.formatted(scopeCondition))
                .params(scopeParameters)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new PartnerReviewItem(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getString("title"),
                        resultSet.getString("action"),
                        PartnerRequestStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("updated_at", OffsetDateTime.class)
                ))
                .list();
    }

    public Optional<UUID> findCurrentStage(UUID interactionId) {
        return jdbcClient.sql("SELECT current_stage_id FROM interactions WHERE id = :id")
                .param("id", interactionId)
                .query(UUID.class)
                .optional();
    }

    public List<AttachmentRow> findAttachments(UUID interactionId, Collection<UUID> attachmentIds) {
        if (attachmentIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                SELECT id, status, partner_visible, deleted_at
                FROM attachments
                WHERE interaction_id = :interactionId AND id IN (:ids)
                """)
                .param("interactionId", interactionId)
                .param("ids", attachmentIds)
                .query((resultSet, rowNumber) -> new AttachmentRow(
                        resultSet.getObject("id", UUID.class),
                        AttachmentStatus.valueOf(resultSet.getString("status")),
                        resultSet.getBoolean("partner_visible"),
                        resultSet.getObject("deleted_at") != null
                ))
                .list();
    }

    public boolean hasActivePartnerAccess(UUID organizationId, UUID contactId) {
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM crm_user_profiles p
                JOIN contacts c ON c.id = p.partner_contact_id
                WHERE p.role = 'PARTNER' AND p.active = TRUE AND p.partner_organization_id = :organizationId
                  AND p.partner_contact_id = :contactId AND c.inactive = FALSE AND c.personal_data_status = 'ACTIVE'
                """)
                .param("organizationId", organizationId)
                .param("contactId", contactId)
                .query(Long.class)
                .single() > 0;
    }

    // Адресаты писем о запросе: КАМ вуза и действующая учётка координатора-контакта.
    public Optional<MailTarget> findMailTarget(UUID organizationId, UUID contactId) {
        return jdbcClient.sql("""
                SELECT o.name AS organization_name, o.owner_manager_id, p.id AS coordinator_profile_id
                FROM organizations o
                LEFT JOIN crm_user_profiles p ON p.partner_organization_id = o.id AND p.partner_contact_id = :contactId
                  AND p.role = 'PARTNER' AND p.active = TRUE
                WHERE o.id = :organizationId
                """)
                .param("organizationId", organizationId)
                .param("contactId", contactId)
                .query(MailTarget.class)
                .optional();
    }

    public Optional<OffsetDateTime> findReceipt(UUID attachmentId, UUID profileId) {
        return jdbcClient.sql("""
                SELECT confirmed_at FROM partner_material_receipts WHERE attachment_id = :attachmentId AND profile_id = :profileId
                """)
                .param("attachmentId", attachmentId)
                .param("profileId", profileId)
                .query(OffsetDateTime.class)
                .optional();
    }

    public Map<UUID, OffsetDateTime> findReceipts(UUID profileId) {
        return jdbcClient.sql("SELECT attachment_id, confirmed_at FROM partner_material_receipts WHERE profile_id = :profileId")
                .param("profileId", profileId)
                .query((resultSet, rowNumber) -> Map.entry(
                        resultSet.getObject("attachment_id", UUID.class),
                        resultSet.getObject("confirmed_at", OffsetDateTime.class)
                ))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public void insertReceipt(UUID attachmentId, UUID profileId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO partner_material_receipts (attachment_id, profile_id, confirmed_at)
                SELECT :attachmentId, :profileId, :now
                WHERE NOT EXISTS (
                    SELECT 1 FROM partner_material_receipts WHERE attachment_id = :attachmentId AND profile_id = :profileId
                )
                """)
                .param("attachmentId", attachmentId)
                .param("profileId", profileId)
                .param("now", now)
                .update();
    }

    private RequestRow mapRequest(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RequestRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("interaction_id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("contact_id", UUID.class),
                resultSet.getString("contact_name"),
                resultSet.getString("work_title"),
                resultSet.getString("action"),
                resultSet.getString("expected_result"),
                resultSet.getObject("due_on", LocalDate.class),
                PartnerRequestStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("return_reason"),
                resultSet.getString("clarification"),
                resultSet.getString("cancel_reason"),
                resultSet.getString("created_by_name"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class),
                resultSet.getInt("version")
        );
    }

    public record RequestRow(
            UUID id,
            UUID interactionId,
            UUID organizationId,
            UUID contactId,
            String contactName,
            String workTitle,
            String action,
            String expectedResult,
            LocalDate dueOn,
            PartnerRequestStatus status,
            String returnReason,
            String clarification,
            String cancelReason,
            String createdByName,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    public record MaterialRow(
            UUID requestId,
            UUID id,
            String name,
            int revision,
            AttachmentStatus status,
            boolean published,
            OffsetDateTime receivedAt
    ) {
    }

    public record ResponseRow(
            UUID id,
            UUID requestId,
            String receiptNumber,
            PartnerResponseKind kind,
            String message,
            UUID attachmentId,
            String fileName,
            AttachmentStatus fileStatus,
            OffsetDateTime submittedAt,
            String submittedByName,
            PartnerResponseDecision decision,
            String decisionReason,
            OffsetDateTime decidedAt
    ) {
    }

    public record AttachmentRow(UUID id, AttachmentStatus status, boolean partnerVisible, boolean deleted) {
    }

    public record MailTarget(String organizationName, UUID ownerManagerId, UUID coordinatorProfileId) {
    }
}
