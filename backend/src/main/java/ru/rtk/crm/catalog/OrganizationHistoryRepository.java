package ru.rtk.crm.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.interaction.InteractionRepository;

@Repository
public class OrganizationHistoryRepository {
    private static final String WORK_SELECT = """
            SELECT e.id AS id, e.type AS kind, e.occurred_at AS occurred_at, a.display_name AS actor_name,
                   i.id AS interaction_id, i.title AS interaction_title,
                   e.from_stage_name_snapshot AS from_stage_name, e.stage_name_snapshot AS stage_name,
                   %s AS comment,
                   CAST(NULL AS UUID) AS contact_id, CAST(NULL AS VARCHAR(200)) AS contact_name,
                   CAST(NULL AS VARCHAR(10000)) AS changes,
                   CAST(NULL AS UUID) AS previous_owner_id, CAST(NULL AS VARCHAR(200)) AS previous_owner_name,
                   CAST(NULL AS UUID) AS owner_id, CAST(NULL AS VARCHAR(200)) AS owner_name
            """.formatted(InteractionRepository.EVENT_COMMENT);

    private static final String WORK_FROM = """
            FROM interaction_events e
            JOIN interactions i ON i.id = e.interaction_id
            JOIN crm_user_profiles a ON a.id = e.actor_profile_id
            WHERE i.organization_id = :organizationId
            """;

    private static final String ASSIGNMENT_SELECT = """
            SELECT ae.id AS id, 'ASSIGNMENT' AS kind, ae.occurred_at AS occurred_at, ae.actor_display_name AS actor_name,
                   CAST(NULL AS UUID) AS interaction_id, CAST(NULL AS VARCHAR(200)) AS interaction_title,
                   CAST(NULL AS VARCHAR(200)) AS from_stage_name, CAST(NULL AS VARCHAR(200)) AS stage_name,
                   ae.handover_note AS comment,
                   CAST(NULL AS UUID) AS contact_id, CAST(NULL AS VARCHAR(200)) AS contact_name,
                   CAST(NULL AS VARCHAR(10000)) AS changes,
                   ae.previous_owner_manager_id AS previous_owner_id,
                   ae.previous_owner_manager_display_name AS previous_owner_name,
                   ae.owner_manager_id AS owner_id, ae.new_owner_manager_display_name AS owner_name
            """;

    private static final String ASSIGNMENT_FROM = """
            FROM organization_assignment_events ae
            WHERE ae.organization_id = :organizationId
            """;

    private static final String CONTACT_SELECT = """
            SELECT ce.id AS id, 'CONTACT' AS kind, ce.occurred_at AS occurred_at, a.display_name AS actor_name,
                   CAST(NULL AS UUID) AS interaction_id, CAST(NULL AS VARCHAR(200)) AS interaction_title,
                   CAST(NULL AS VARCHAR(200)) AS from_stage_name, CAST(NULL AS VARCHAR(200)) AS stage_name,
                   CAST(NULL AS VARCHAR(2000)) AS comment,
                   c.id AS contact_id, c.name AS contact_name, ce.changes AS changes,
                   CAST(NULL AS UUID) AS previous_owner_id, CAST(NULL AS VARCHAR(200)) AS previous_owner_name,
                   CAST(NULL AS UUID) AS owner_id, CAST(NULL AS VARCHAR(200)) AS owner_name
            """;

    private static final String CONTACT_FROM = """
            FROM contact_events ce
            JOIN contacts c ON c.id = ce.contact_id
            JOIN crm_user_profiles a ON a.id = ce.actor_profile_id
            WHERE c.organization_id = :organizationId
            """;

    private final JdbcClient jdbcClient;

    public OrganizationHistoryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<StoredHistoryItem> find(UUID organizationId, OrganizationHistoryQuery query) {
        Map<String, Object> parameters = parameters(organizationId, query);
        List<String> selects = new ArrayList<>();
        for (Branch branch : branches(query)) {
            selects.add(branch.select() + branch.from());
        }
        if (selects.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("SELECT * FROM (" + String.join(" UNION ALL ", selects)
                        + ") h ORDER BY h.occurred_at DESC, h.id DESC LIMIT :limit OFFSET :offset")
                .params(parameters)
                .param("limit", query.size())
                .param("offset", query.offset())
                .query(this::map)
                .list();
    }

    public long count(UUID organizationId, OrganizationHistoryQuery query) {
        Map<String, Object> parameters = parameters(organizationId, query);
        long total = 0;
        for (Branch branch : branches(query)) {
            total += jdbcClient.sql("SELECT COUNT(*) " + branch.from())
                    .params(parameters)
                    .query(Long.class)
                    .single();
        }
        return total;
    }

    private Map<String, Object> parameters(UUID organizationId, OrganizationHistoryQuery query) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("organizationId", organizationId);
        List<String> workKinds = workKinds(query);
        if (!workKinds.isEmpty()) {
            parameters.put("workKinds", workKinds);
        }
        if (query.fromAt() != null) {
            parameters.put("fromAt", query.fromAt());
        }
        if (query.toAt() != null) {
            parameters.put("toAt", query.toAt());
        }
        return parameters;
    }

    private static List<String> workKinds(OrganizationHistoryQuery query) {
        return query.kinds().stream()
                .filter(OrganizationHistoryKind.WORK_KINDS::contains)
                .map(Enum::name)
                .toList();
    }

    private List<Branch> branches(OrganizationHistoryQuery query) {
        List<Branch> branches = new ArrayList<>();
        if (query.includes(OrganizationHistoryKind.WORK_KINDS)) {
            String kinds = workKinds(query).isEmpty() ? "" : " AND e.type IN (:workKinds)";
            branches.add(new Branch(WORK_SELECT, WORK_FROM + kinds + period("e.occurred_at", query)));
        }
        if (query.includes(Set.of(OrganizationHistoryKind.ASSIGNMENT))) {
            branches.add(new Branch(ASSIGNMENT_SELECT, ASSIGNMENT_FROM + period("ae.occurred_at", query)));
        }
        if (query.includes(Set.of(OrganizationHistoryKind.CONTACT))) {
            branches.add(new Branch(CONTACT_SELECT, CONTACT_FROM + period("ce.occurred_at", query)));
        }
        return branches;
    }

    private static String period(String column, OrganizationHistoryQuery query) {
        return (query.fromAt() == null ? "" : " AND " + column + " >= :fromAt")
                + (query.toAt() == null ? "" : " AND " + column + " < :toAt");
    }

    private StoredHistoryItem map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredHistoryItem(
                resultSet.getObject("id", UUID.class),
                OrganizationHistoryKind.valueOf(resultSet.getString("kind")),
                resultSet.getObject("occurred_at", OffsetDateTime.class),
                resultSet.getString("actor_name"),
                resultSet.getObject("interaction_id", UUID.class),
                resultSet.getString("interaction_title"),
                resultSet.getString("from_stage_name"),
                resultSet.getString("stage_name"),
                resultSet.getString("comment"),
                resultSet.getObject("contact_id", UUID.class),
                resultSet.getString("contact_name"),
                resultSet.getString("changes"),
                resultSet.getObject("previous_owner_id", UUID.class),
                resultSet.getString("previous_owner_name"),
                resultSet.getObject("owner_id", UUID.class),
                resultSet.getString("owner_name")
        );
    }

    private record Branch(String select, String from) {
    }

    public record StoredHistoryItem(
            UUID id,
            OrganizationHistoryKind kind,
            OffsetDateTime occurredAt,
            String actorName,
            UUID interactionId,
            String interactionTitle,
            String fromStageName,
            String stageName,
            String comment,
            UUID contactId,
            String contactName,
            String changes,
            UUID previousOwnerId,
            String previousOwnerName,
            UUID ownerId,
            String ownerName
    ) {
    }
}
