package ru.rtk.crm.interaction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;

@Repository
public class InteractionIssueRepository {
    public static final String MARK_COLUMNS = """
            (SELECT COUNT(*) FROM interaction_issues mark WHERE mark.interaction_id = i.id
               AND mark.status = 'OPEN' AND mark.kind = 'PROBLEM') AS problem_count,
            (SELECT COUNT(*) FROM interaction_issues mark WHERE mark.interaction_id = i.id
               AND mark.status = 'OPEN' AND mark.kind = 'RISK') AS risk_count,
            CASE WHEN EXISTS (SELECT 1 FROM interaction_issues mark WHERE mark.interaction_id = i.id
                                AND mark.status = 'OPEN' AND mark.risk_level = 'HIGH') THEN 'HIGH'
                 WHEN EXISTS (SELECT 1 FROM interaction_issues mark WHERE mark.interaction_id = i.id
                                AND mark.status = 'OPEN' AND mark.risk_level = 'MEDIUM') THEN 'MEDIUM' END AS risk_level,
            (SELECT STRING_AGG(mark.description, '; ' ORDER BY mark.created_at, mark.id)
             FROM interaction_issues mark WHERE mark.interaction_id = i.id
               AND mark.status = 'OPEN' AND mark.kind = 'PROBLEM') AS problems,
            (SELECT STRING_AGG(CASE mark.risk_level WHEN 'HIGH' THEN 'высокий' ELSE 'средний' END || ': ' || mark.description,
                               '; ' ORDER BY CASE mark.risk_level WHEN 'HIGH' THEN 0 ELSE 1 END, mark.created_at, mark.id)
             FROM interaction_issues mark WHERE mark.interaction_id = i.id
               AND mark.status = 'OPEN' AND mark.kind = 'RISK') AS risks""";

    private static final String SELECT = """
            SELECT ii.id, ii.interaction_id, ii.kind, ii.description, ii.risk_level, ii.responsible_profile_id,
                   ii.due_on, ii.status, ii.resolution, ii.created_by, ii.created_at, ii.resolved_at,
                   i.title AS interaction_title, i.organization_id, o.name AS organization_name,
                   owner_profile.display_name AS owner_manager_name, responsible.display_name AS responsible_name,
                   creator.display_name AS created_by_name, resolver.display_name AS resolved_by_name
            FROM interaction_issues ii
            JOIN interactions i ON i.id = ii.interaction_id
            JOIN organizations o ON o.id = i.organization_id
            LEFT JOIN crm_user_profiles owner_profile ON owner_profile.id = o.owner_manager_id
            JOIN crm_user_profiles responsible ON responsible.id = ii.responsible_profile_id
            JOIN crm_user_profiles creator ON creator.id = ii.created_by
            LEFT JOIN crm_user_profiles resolver ON resolver.id = ii.resolved_by
            """;

    private final JdbcClient jdbcClient;

    public InteractionIssueRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<InteractionIssue> findByInteraction(UUID interactionId) {
        return jdbcClient.sql(SELECT + """
                WHERE ii.interaction_id = :interactionId
                ORDER BY ii.status, ii.created_at, ii.id
                """)
                .param("interactionId", interactionId)
                .query(this::mapIssue)
                .list();
    }

    public Optional<InteractionIssue> findInInteraction(UUID interactionId, UUID issueId) {
        return jdbcClient.sql(SELECT + "WHERE ii.interaction_id = :interactionId AND ii.id = :issueId")
                .param("interactionId", interactionId)
                .param("issueId", issueId)
                .query(this::mapIssue)
                .optional();
    }

    public List<InteractionIssue> findVisible(VisibilityScope scope, InteractionIssueFilter filter, LocalDate today, int limit, int offset) {
        Selection selection = selection(scope, filter, today);
        return jdbcClient.sql(SELECT + "WHERE " + selection.where() + """

                ORDER BY CASE WHEN ii.due_on IS NULL THEN 1 ELSE 0 END, ii.due_on, ii.created_at, ii.id
                LIMIT :limit OFFSET :offset
                """)
                .params(selection.parameters())
                .param("limit", limit)
                .param("offset", offset)
                .query(this::mapIssue)
                .list();
    }

    public long countVisible(VisibilityScope scope, InteractionIssueFilter filter, LocalDate today) {
        Selection selection = selection(scope, filter, today);
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM interaction_issues ii
                JOIN interactions i ON i.id = ii.interaction_id
                WHERE %s
                """.formatted(selection.where()))
                .params(selection.parameters())
                .query(Long.class)
                .single();
    }

    public List<InteractionIssueList.ResponsibleOption> findResponsibleOptions(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT profile.id, profile.display_name
                FROM crm_user_profiles profile
                JOIN organizations o ON o.team_id = profile.team_id
                WHERE o.id = :organizationId AND profile.active AND profile.role IN ('USER', 'LEADER')
                ORDER BY profile.display_name, profile.id
                """)
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> new InteractionIssueList.ResponsibleOption(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("display_name")
                ))
                .list();
    }

    public void insert(
            UUID id,
            UUID interactionId,
            InteractionIssueKind kind,
            String description,
            InteractionRiskLevel riskLevel,
            UUID responsibleId,
            LocalDate dueOn,
            UUID createdBy,
            OffsetDateTime createdAt
    ) {
        jdbcClient.sql("""
                INSERT INTO interaction_issues (
                    id, interaction_id, kind, description, risk_level, responsible_profile_id, due_on, status, created_by, created_at
                ) VALUES (
                    :id, :interactionId, :kind, :description, :riskLevel, :responsibleId, :dueOn, 'OPEN', :createdBy, :createdAt
                )
                """)
                .param("id", id)
                .param("interactionId", interactionId)
                .param("kind", kind.name())
                .param("description", description)
                .param("riskLevel", riskLevel == null ? null : riskLevel.name())
                .param("responsibleId", responsibleId)
                .param("dueOn", dueOn)
                .param("createdBy", createdBy)
                .param("createdAt", createdAt)
                .update();
    }

    public void update(UUID id, String description, InteractionRiskLevel riskLevel, UUID responsibleId, LocalDate dueOn) {
        jdbcClient.sql("""
                UPDATE interaction_issues
                SET description = :description, risk_level = :riskLevel, responsible_profile_id = :responsibleId, due_on = :dueOn
                WHERE id = :id
                """)
                .param("id", id)
                .param("description", description)
                .param("riskLevel", riskLevel == null ? null : riskLevel.name())
                .param("responsibleId", responsibleId)
                .param("dueOn", dueOn)
                .update();
    }

    public void resolve(UUID id, String resolution, UUID resolvedBy, OffsetDateTime resolvedAt) {
        jdbcClient.sql("""
                UPDATE interaction_issues
                SET status = 'RESOLVED', resolution = :resolution, resolved_by = :resolvedBy, resolved_at = :resolvedAt
                WHERE id = :id
                """)
                .param("id", id)
                .param("resolution", resolution)
                .param("resolvedBy", resolvedBy)
                .param("resolvedAt", resolvedAt)
                .update();
    }

    private Selection selection(VisibilityScope scope, InteractionIssueFilter filter, LocalDate today) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        conditions.add("i.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")");
        if (filter.status() != null) {
            conditions.add("ii.status = :status");
            parameters.put("status", filter.status().name());
        }
        if (filter.kind() != null) {
            conditions.add("ii.kind = :kind");
            parameters.put("kind", filter.kind().name());
        }
        if (filter.riskLevel() != null) {
            conditions.add("ii.risk_level = :riskLevel");
            parameters.put("riskLevel", filter.riskLevel().name());
        }
        if (filter.responsibleId() != null) {
            conditions.add("ii.responsible_profile_id = :responsibleId");
            parameters.put("responsibleId", filter.responsibleId());
        }
        if (filter.organizationId() != null) {
            conditions.add("i.organization_id = :organizationId");
            parameters.put("organizationId", filter.organizationId());
        }
        if (filter.overdue()) {
            conditions.add("ii.status = 'OPEN' AND ii.due_on < :today");
            parameters.put("today", today);
        }
        return new Selection(String.join(" AND ", conditions), parameters);
    }

    private InteractionIssue mapIssue(ResultSet resultSet, int rowNumber) throws SQLException {
        String riskLevel = resultSet.getString("risk_level");
        return new InteractionIssue(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("interaction_id", UUID.class),
                resultSet.getString("interaction_title"),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getString("organization_name"),
                resultSet.getString("owner_manager_name"),
                InteractionIssueKind.valueOf(resultSet.getString("kind")),
                resultSet.getString("description"),
                riskLevel == null ? null : InteractionRiskLevel.valueOf(riskLevel),
                resultSet.getObject("responsible_profile_id", UUID.class),
                resultSet.getString("responsible_name"),
                resultSet.getObject("due_on", LocalDate.class),
                InteractionIssueStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("resolution"),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getString("created_by_name"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getString("resolved_by_name"),
                resultSet.getObject("resolved_at", OffsetDateTime.class)
        );
    }

    public static InteractionMarks mapMarks(ResultSet resultSet) throws SQLException {
        String waitingOn = resultSet.getString("waiting_on");
        String riskLevel = resultSet.getString("risk_level");
        return new InteractionMarks(
                InteractionWorkStatus.valueOf(resultSet.getString("work_status")),
                resultSet.getString("work_status_reason"),
                waitingOn == null ? null : InteractionWaiting.valueOf(waitingOn),
                resultSet.getString("waiting_note"),
                resultSet.getInt("problem_count"),
                resultSet.getInt("risk_count"),
                riskLevel == null ? null : InteractionRiskLevel.valueOf(riskLevel),
                resultSet.getString("problems"),
                resultSet.getString("risks")
        );
    }

    private record Selection(String where, Map<String, Object> parameters) {
    }
}
