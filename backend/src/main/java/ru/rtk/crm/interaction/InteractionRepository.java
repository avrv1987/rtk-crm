package ru.rtk.crm.interaction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.catalog.SearchPattern;

@Repository
public class InteractionRepository {
    private static final String EVENT_SELECT = """
            SELECT e.id, e.type, e.command_id, e.stage_id, e.stage_name_snapshot,
                   e.from_stage_id, e.from_stage_name_snapshot,
                   e.to_stage_id, e.to_stage_name_snapshot, e.comment,
                   e.plan_changed, e.next_action, e.next_action_at,
                   e.actor_profile_id, actor.display_name AS actor_display_name,
                   e.owner_manager_id_snapshot, e.version, e.occurred_at
            FROM interaction_events e
            JOIN crm_user_profiles actor ON actor.id = e.actor_profile_id
            """;

    private final JdbcClient jdbcClient;

    public InteractionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<InteractionRow> findById(UUID interactionId) {
        return findOne(interactionId, false);
    }

    public Optional<InteractionRow> findByIdForUpdate(UUID interactionId) {
        return findOne(interactionId, true);
    }

    public Optional<UUID> findOrganizationIdById(UUID interactionId) {
        return findOrganizationId(interactionId, false);
    }

    public Optional<UUID> findOrganizationIdByIdForUpdate(UUID interactionId) {
        return findOrganizationId(interactionId, true);
    }

    private Optional<UUID> findOrganizationId(UUID interactionId, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        return jdbcClient.sql(("""
                SELECT organization_id
                FROM interactions
                WHERE id = :interactionId
                """ + lock))
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> resultSet.getObject("organization_id", UUID.class))
                .optional();
    }

    public List<InteractionListRow> findVisible(
            VisibilityScope scope,
            InteractionFilter filter,
            InteractionQuery query,
            OffsetDateTime now
    ) {
        ListSelection selection = listSelection(scope, filter, now);
        return jdbcClient.sql("""
                SELECT i.id, i.organization_id, i.title, i.current_stage_id, current_stage.name AS current_stage_name,
                       i.next_action, i.next_action_at, i.program_id, i.last_contact_at,
                       i.version, i.created_by, i.created_at, i.updated_at,
                       o.name AS organization_name, program.name AS program_name,
                       owner_profile.display_name AS owner_manager_name
                FROM interactions i
                JOIN interaction_stages current_stage
                  ON current_stage.id = i.current_stage_id AND current_stage.interaction_id = i.id
                JOIN organizations o ON o.id = i.organization_id
                LEFT JOIN programs program ON program.id = i.program_id
                LEFT JOIN crm_user_profiles owner_profile ON owner_profile.id = o.owner_manager_id
                WHERE %s
                ORDER BY %s
                LIMIT :size OFFSET :offset
                """.formatted(selection.where(), query.sort().orderBy()))
                .params(selection.parameters())
                .param("size", query.size())
                .param("offset", query.offset())
                .query((resultSet, rowNumber) -> new InteractionListRow(
                        mapInteractionRow(resultSet, rowNumber),
                        resultSet.getString("organization_name"),
                        resultSet.getString("program_name"),
                        resultSet.getString("owner_manager_name")
                ))
                .list();
    }

    public long countVisible(VisibilityScope scope, InteractionFilter filter, OffsetDateTime now) {
        ListSelection selection = listSelection(scope, filter, now);
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM interactions i
                JOIN interaction_stages current_stage
                  ON current_stage.id = i.current_stage_id AND current_stage.interaction_id = i.id
                JOIN organizations o ON o.id = i.organization_id
                WHERE %s
                """.formatted(selection.where()))
                .params(selection.parameters())
                .query(Long.class)
                .single();
    }

    private ListSelection listSelection(VisibilityScope scope, InteractionFilter filter, OffsetDateTime now) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        conditions.add("i.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")");
        if (filter.organizationId() != null) {
            conditions.add("i.organization_id = :organizationId");
            parameters.put("organizationId", filter.organizationId());
        }
        if (filter.search() != null) {
            conditions.add("(LOWER(i.title) LIKE :search %1$s OR LOWER(o.name) LIKE :search %1$s)"
                    .formatted(SearchPattern.LIKE_ESCAPE));
            parameters.put("search", SearchPattern.contains(filter.search()));
        }
        if (filter.stage() != null) {
            conditions.add("current_stage.name = :stage");
            parameters.put("stage", filter.stage());
        }
        if (filter.due() != null) {
            switch (filter.due()) {
                case OVERDUE -> {
                    conditions.add("i.next_action IS NOT NULL AND i.next_action_at < :now");
                    parameters.put("now", now);
                }
                case THIS_WEEK -> {
                    OffsetDateTime weekStart = InteractionDue.weekStart(now);
                    conditions.add("i.next_action IS NOT NULL AND i.next_action_at >= :weekStart AND i.next_action_at < :weekEnd");
                    parameters.put("weekStart", weekStart);
                    parameters.put("weekEnd", weekStart.plusWeeks(1));
                }
                case NO_NEXT_STEP -> conditions.add("(i.next_action IS NULL OR i.next_action_at IS NULL)");
            }
        }
        return new ListSelection(String.join(" AND ", conditions), parameters);
    }

    public void insertInteraction(
            UUID interactionId,
            UUID organizationId,
            String title,
            UUID currentStageId,
            String nextAction,
            OffsetDateTime nextActionAt,
            UUID programId,
            OffsetDateTime lastContactAt,
            UUID createdBy,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO interactions (
                    id, organization_id, title, current_stage_id, next_action, next_action_at, program_id, last_contact_at,
                    version, created_by, created_at, updated_at
                ) VALUES (
                    :id, :organizationId, :title, :currentStageId, :nextAction, :nextActionAt, :programId, :lastContactAt,
                    0, :createdBy, :createdAt, :updatedAt
                )
                """)
                .param("id", interactionId)
                .param("organizationId", organizationId)
                .param("title", title)
                .param("currentStageId", currentStageId)
                .param("nextAction", nextAction)
                .param("nextActionAt", nextActionAt)
                .param("programId", programId)
                .param("lastContactAt", lastContactAt)
                .param("createdBy", createdBy)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }

    public void insertStages(UUID interactionId, List<InteractionStage> stages) {
        for (InteractionStage stage : stages) {
            jdbcClient.sql("""
                    INSERT INTO interaction_stages (id, interaction_id, stage_order, name, optional)
                    VALUES (:id, :interactionId, :stageOrder, :name, :optional)
                    """)
                    .param("id", stage.id())
                    .param("interactionId", interactionId)
                    .param("stageOrder", stage.order())
                    .param("name", stage.name())
                    .param("optional", stage.optional())
                    .update();
        }
    }

    public void insertContacts(UUID interactionId, UUID organizationId, List<UUID> contactIds) {
        for (UUID contactId : contactIds) {
            jdbcClient.sql("""
                    INSERT INTO interaction_contacts (interaction_id, organization_id, contact_id)
                    VALUES (:interactionId, :organizationId, :contactId)
                    """)
                    .param("interactionId", interactionId)
                    .param("organizationId", organizationId)
                    .param("contactId", contactId)
                    .update();
        }
    }

    public void insertProductAgreements(UUID interactionId, List<UUID> productIds, OffsetDateTime now) {
        for (UUID productId : productIds) {
            jdbcClient.sql("""
                    INSERT INTO product_agreements (
                        id, interaction_id, product_id, created_at, updated_at
                    ) VALUES (
                        :id, :interactionId, :productId, :createdAt, :updatedAt
                    )
                    """)
                    .param("id", UUID.randomUUID())
                    .param("interactionId", interactionId)
                    .param("productId", productId)
                    .param("createdAt", now)
                    .param("updatedAt", now)
                    .update();
        }
    }

    public List<InteractionStage> findStages(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT id, name, stage_order, optional
                FROM interaction_stages
                WHERE interaction_id = :interactionId
                ORDER BY stage_order ASC
                """)
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> new InteractionStage(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getInt("stage_order"),
                        resultSet.getBoolean("optional")
                ))
                .list();
    }

    public List<InteractionStageTransition> findTransitions(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT from_stage_id, to_stage_id, comment_required
                FROM interaction_stage_transitions
                WHERE interaction_id = :interactionId
                ORDER BY from_stage_id, to_stage_id
                """)
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> new InteractionStageTransition(
                        resultSet.getObject("from_stage_id", UUID.class),
                        resultSet.getObject("to_stage_id", UUID.class),
                        resultSet.getBoolean("comment_required")
                ))
                .list();
    }

    public void insertTransitions(UUID interactionId, List<InteractionStageTransition> transitions) {
        for (InteractionStageTransition transition : transitions) {
            jdbcClient.sql("""
                    INSERT INTO interaction_stage_transitions (
                        interaction_id, from_stage_id, to_stage_id, comment_required
                    ) VALUES (
                        :interactionId, :fromStageId, :toStageId, :commentRequired
                    )
                    """)
                    .param("interactionId", interactionId)
                    .param("fromStageId", transition.fromStageId())
                    .param("toStageId", transition.toStageId())
                    .param("commentRequired", transition.commentRequired())
                    .update();
        }
    }

    public Set<UUID> findProtectedStageIds(UUID interactionId) {
        return new HashSet<>(jdbcClient.sql("""
                SELECT stage_id
                FROM interaction_events
                WHERE interaction_id = :interactionId
                UNION
                SELECT from_stage_id
                FROM interaction_events
                WHERE interaction_id = :interactionId AND from_stage_id IS NOT NULL
                UNION
                SELECT to_stage_id
                FROM interaction_events
                WHERE interaction_id = :interactionId AND to_stage_id IS NOT NULL
                UNION
                SELECT stage_id
                FROM attachments
                WHERE interaction_id = :interactionId
                """)
                .param("interactionId", interactionId)
                .query(UUID.class)
                .list());
    }

    public void replaceWorkflow(
            UUID interactionId,
            List<InteractionStage> previousStages,
            List<InteractionStage> stages,
            List<InteractionStageTransition> transitions
    ) {
        jdbcClient.sql("DELETE FROM interaction_stage_transitions WHERE interaction_id = :interactionId")
                .param("interactionId", interactionId)
                .update();

        Set<UUID> updatedIds = stages.stream().map(InteractionStage::id).collect(java.util.stream.Collectors.toSet());
        for (InteractionStage previousStage : previousStages) {
            if (!updatedIds.contains(previousStage.id())) {
                jdbcClient.sql("DELETE FROM interaction_stages WHERE id = :stageId AND interaction_id = :interactionId")
                        .param("stageId", previousStage.id())
                        .param("interactionId", interactionId)
                        .update();
            }
        }

        jdbcClient.sql("""
                UPDATE interaction_stages
                SET stage_order = stage_order + 10000
                WHERE interaction_id = :interactionId
                """)
                .param("interactionId", interactionId)
                .update();

        Set<UUID> previousIds = previousStages.stream().map(InteractionStage::id)
                .collect(java.util.stream.Collectors.toSet());
        for (InteractionStage stage : stages) {
            if (!previousIds.contains(stage.id())) {
                jdbcClient.sql("""
                        INSERT INTO interaction_stages (id, interaction_id, stage_order, name, optional)
                        VALUES (:id, :interactionId, :stageOrder, :name, :optional)
                        """)
                        .param("id", stage.id())
                        .param("interactionId", interactionId)
                        .param("stageOrder", 20000 + stage.order())
                        .param("name", stage.name())
                        .param("optional", stage.optional())
                        .update();
            }
        }
        for (InteractionStage stage : stages) {
            jdbcClient.sql("""
                    UPDATE interaction_stages
                    SET stage_order = :stageOrder, name = :name, optional = :optional
                    WHERE id = :stageId AND interaction_id = :interactionId
                    """)
                    .param("stageId", stage.id())
                    .param("interactionId", interactionId)
                    .param("stageOrder", stage.order())
                    .param("name", stage.name())
                    .param("optional", stage.optional())
                    .update();
        }
        insertTransitions(interactionId, transitions);
    }

    public Map<UUID, List<UUID>> findContactIdsByInteractionIds(List<UUID> interactionIds) {
        return findIdsByInteractionIds("""
                SELECT interaction_id, contact_id AS linked_id
                FROM interaction_contacts
                WHERE interaction_id IN (:interactionIds)
                ORDER BY interaction_id, contact_id
                """, interactionIds);
    }

    public Map<UUID, List<UUID>> findProductIdsByInteractionIds(List<UUID> interactionIds) {
        return findIdsByInteractionIds("""
                SELECT DISTINCT interaction_id, product_id AS linked_id
                FROM product_agreements
                WHERE interaction_id IN (:interactionIds)
                ORDER BY interaction_id, product_id
                """, interactionIds);
    }

    private Map<UUID, List<UUID>> findIdsByInteractionIds(String sql, List<UUID> interactionIds) {
        Map<UUID, List<UUID>> result = new HashMap<>();
        if (interactionIds.isEmpty()) {
            return result;
        }
        jdbcClient.sql(sql)
                .param("interactionIds", interactionIds)
                .query((resultSet, rowNumber) -> result
                        .computeIfAbsent(resultSet.getObject("interaction_id", UUID.class), ignored -> new ArrayList<>())
                        .add(resultSet.getObject("linked_id", UUID.class)))
                .list();
        return result;
    }

    public List<UUID> findContactIds(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT contact_id
                FROM interaction_contacts
                WHERE interaction_id = :interactionId
                ORDER BY contact_id ASC
                """)
                .param("interactionId", interactionId)
                .query(UUID.class)
                .list();
    }

    public List<UUID> findProductIds(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT DISTINCT product_id
                FROM product_agreements
                WHERE interaction_id = :interactionId
                ORDER BY product_id ASC
                """)
                .param("interactionId", interactionId)
                .query(UUID.class)
                .list();
    }

    public List<ProductAgreement> findProductAgreements(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT agreement.id, agreement.product_id, product.name AS product_name, product.archived AS product_archived,
                       agreement.contract_number, agreement.license_signed, agreement.license_expiry_year, agreement.transfer_status
                FROM product_agreements agreement
                JOIN products product ON product.id = agreement.product_id
                WHERE agreement.interaction_id = :interactionId
                ORDER BY product.name ASC, agreement.id ASC
                """)
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> new ProductAgreement(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("product_id", UUID.class),
                        resultSet.getString("product_name"),
                        resultSet.getBoolean("product_archived"),
                        resultSet.getString("contract_number"),
                        resultSet.getObject("license_signed", Boolean.class),
                        resultSet.getObject("license_expiry_year", Integer.class),
                        resultSet.getString("transfer_status")
                ))
                .list();
    }

    public long countFilledProductAgreements(UUID interactionId, List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return 0;
        }
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM product_agreements
                WHERE interaction_id = :interactionId
                  AND product_id IN (:productIds)
                  AND (contract_number IS NOT NULL OR license_signed IS NOT NULL
                       OR license_expiry_year IS NOT NULL OR transfer_status IS NOT NULL)
                """)
                .param("interactionId", interactionId)
                .param("productIds", productIds)
                .query(Long.class)
                .single();
    }

    public void deleteEmptyProductAgreements(UUID interactionId, List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return;
        }
        jdbcClient.sql("""
                DELETE FROM product_agreements
                WHERE interaction_id = :interactionId
                  AND product_id IN (:productIds)
                  AND contract_number IS NULL AND license_signed IS NULL
                  AND license_expiry_year IS NULL AND transfer_status IS NULL
                """)
                .param("interactionId", interactionId)
                .param("productIds", productIds)
                .update();
    }

    public void updatePlan(UUID interactionId, String nextAction, OffsetDateTime nextActionAt, UUID programId) {
        jdbcClient.sql("""
                UPDATE interactions
                SET next_action = :nextAction, next_action_at = :nextActionAt, program_id = :programId
                WHERE id = :interactionId
                """)
                .param("interactionId", interactionId)
                .param("nextAction", nextAction)
                .param("nextActionAt", nextActionAt)
                .param("programId", programId)
                .update();
    }

    public boolean updateCurrentStage(
            UUID interactionId,
            int expectedVersion,
            UUID currentStageId,
            OffsetDateTime updatedAt
    ) {
        return jdbcClient.sql("""
                UPDATE interactions
                SET current_stage_id = :currentStageId, version = version + 1, updated_at = :updatedAt
                WHERE id = :interactionId AND version = :expectedVersion
                """)
                .param("interactionId", interactionId)
                .param("expectedVersion", expectedVersion)
                .param("currentStageId", currentStageId)
                .param("updatedAt", updatedAt)
                .update() == 1;
    }

    public boolean touchVersion(UUID interactionId, int expectedVersion, OffsetDateTime updatedAt) {
        return jdbcClient.sql("""
                UPDATE interactions
                SET version = version + 1, updated_at = :updatedAt
                WHERE id = :interactionId AND version = :expectedVersion
                """)
                .param("interactionId", interactionId)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", updatedAt)
                .update() == 1;
    }

    public InteractionEvent insertEvent(
            UUID eventId,
            UUID interactionId,
            UUID commandId,
            InteractionEventType type,
            InteractionStage stage,
            InteractionStage fromStage,
            InteractionStage toStage,
            String comment,
            InteractionNextStep nextStep,
            UUID actorProfileId,
            UUID ownerManagerIdSnapshot,
            int version,
            OffsetDateTime occurredAt
    ) {
        jdbcClient.sql("""
                INSERT INTO interaction_events (
                    id, interaction_id, command_id, type,
                    stage_id, stage_name_snapshot,
                    from_stage_id, from_stage_name_snapshot,
                    to_stage_id, to_stage_name_snapshot,
                    comment, plan_changed, next_action, next_action_at,
                    actor_profile_id, owner_manager_id_snapshot, version, occurred_at
                ) VALUES (
                    :id, :interactionId, :commandId, :type,
                    :stageId, :stageNameSnapshot,
                    :fromStageId, :fromStageNameSnapshot,
                    :toStageId, :toStageNameSnapshot,
                    :comment, :planChanged, :nextAction, :nextActionAt,
                    :actorProfileId, :ownerManagerIdSnapshot, :version, :occurredAt
                )
                """)
                .param("id", eventId)
                .param("interactionId", interactionId)
                .param("commandId", commandId)
                .param("type", type.name())
                .param("stageId", stage.id())
                .param("stageNameSnapshot", stage.name())
                .param("fromStageId", fromStage == null ? null : fromStage.id())
                .param("fromStageNameSnapshot", fromStage == null ? null : fromStage.name())
                .param("toStageId", toStage == null ? null : toStage.id())
                .param("toStageNameSnapshot", toStage == null ? null : toStage.name())
                .param("comment", comment)
                .param("planChanged", nextStep != null)
                .param("nextAction", nextStep == null ? null : nextStep.nextAction())
                .param("nextActionAt", nextStep == null ? null : nextStep.nextActionAt())
                .param("actorProfileId", actorProfileId)
                .param("ownerManagerIdSnapshot", ownerManagerIdSnapshot)
                .param("version", version)
                .param("occurredAt", occurredAt)
                .update();
        return jdbcClient.sql(EVENT_SELECT + "WHERE e.id = :eventId")
                .param("eventId", eventId)
                .query(this::mapEvent)
                .single();
    }

    public List<InteractionEvent> findEvents(UUID interactionId) {
        return jdbcClient.sql(EVENT_SELECT + """
                WHERE e.interaction_id = :interactionId
                ORDER BY e.version ASC, e.occurred_at ASC, e.id ASC
                """)
                .param("interactionId", interactionId)
                .query(this::mapEvent)
                .list();
    }

    private Optional<InteractionRow> findOne(UUID interactionId, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        return jdbcClient.sql(("""
                SELECT i.id, i.organization_id, i.title, i.current_stage_id, current_stage.name AS current_stage_name,
                       i.next_action, i.next_action_at, i.program_id, i.last_contact_at,
                       i.version, i.created_by, i.created_at, i.updated_at
                FROM interactions i
                JOIN interaction_stages current_stage
                  ON current_stage.id = i.current_stage_id AND current_stage.interaction_id = i.id
                WHERE i.id = :interactionId
                """ + lock))
                .param("interactionId", interactionId)
                .query(this::mapInteractionRow)
                .optional();
    }

    private InteractionRow mapInteractionRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new InteractionRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getString("title"),
                resultSet.getObject("current_stage_id", UUID.class),
                resultSet.getString("current_stage_name"),
                resultSet.getString("next_action"),
                resultSet.getObject("next_action_at", OffsetDateTime.class),
                resultSet.getObject("program_id", UUID.class),
                resultSet.getObject("last_contact_at", OffsetDateTime.class),
                resultSet.getInt("version"),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private InteractionEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new InteractionEvent(
                resultSet.getObject("id", UUID.class),
                InteractionEventType.valueOf(resultSet.getString("type")),
                resultSet.getObject("command_id", UUID.class),
                resultSet.getObject("stage_id", UUID.class),
                resultSet.getString("stage_name_snapshot"),
                resultSet.getObject("from_stage_id", UUID.class),
                resultSet.getString("from_stage_name_snapshot"),
                resultSet.getObject("to_stage_id", UUID.class),
                resultSet.getString("to_stage_name_snapshot"),
                resultSet.getString("comment"),
                resultSet.getBoolean("plan_changed")
                        ? new InteractionNextStep(
                                resultSet.getString("next_action"),
                                resultSet.getObject("next_action_at", OffsetDateTime.class)
                        )
                        : null,
                resultSet.getObject("actor_profile_id", UUID.class),
                resultSet.getString("actor_display_name"),
                resultSet.getObject("owner_manager_id_snapshot", UUID.class),
                resultSet.getInt("version"),
                resultSet.getObject("occurred_at", OffsetDateTime.class)
        );
    }

    private record ListSelection(String where, Map<String, Object> parameters) {
    }

    record InteractionListRow(InteractionRow row, String organizationName, String programName, String ownerManagerName) {
    }

    record InteractionRow(
            UUID id,
            UUID organizationId,
            String title,
            UUID currentStageId,
            String currentStageName,
            String nextAction,
            OffsetDateTime nextActionAt,
            UUID programId,
            OffsetDateTime lastContactAt,
            int version,
            UUID createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }
}
