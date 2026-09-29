package ru.rtk.crm.report;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.PlanKind;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;

@Repository
public class SigningPlanRepository {
    private final JdbcClient jdbcClient;

    public SigningPlanRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Candidate> findCandidates(VisibilityScope scope, LocalDate from, LocalDate to) {
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        parameters.put("from", from);
        parameters.put("to", to);
        return jdbcClient.sql("""
                SELECT ag.id, ag.number, ag.status, ag.concluded_on, ag.valid_until, ag.planned_kind, ag.planned_on,
                       ag.planned_base_until, o.id AS organization_id, o.name AS organization_name,
                       t.id AS team_id, t.name AS team_name, p.id AS manager_id, p.display_name AS manager_name
                FROM agreements ag
                JOIN organizations o ON o.id = ag.organization_id
                JOIN teams t ON t.id = o.team_id
                LEFT JOIN crm_user_profiles p ON p.id = o.owner_manager_id
                WHERE ag.organization_id IN (SELECT id FROM organizations WHERE %s)
                  AND o.status IN ('ACTIVE', 'PENDING')
                  AND ag.status <> 'TERMINATED'
                  AND ((ag.planned_on BETWEEN :from AND :to)
                       OR (ag.planned_on IS NULL AND ag.status = 'ACTIVE' AND ag.valid_until BETWEEN :from AND :to))
                ORDER BY COALESCE(ag.planned_on, ag.valid_until), o.name, ag.number, ag.id
                """.formatted(scope.condition()))
                .params(parameters)
                .query((resultSet, rowNumber) -> new Candidate(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("number"),
                        AgreementStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("valid_until", LocalDate.class),
                        resultSet.getString("planned_kind") == null ? null : PlanKind.valueOf(resultSet.getString("planned_kind")),
                        resultSet.getObject("planned_on", LocalDate.class),
                        resultSet.getObject("planned_base_until", LocalDate.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getObject("team_id", UUID.class),
                        resultSet.getString("team_name"),
                        resultSet.getObject("manager_id", UUID.class),
                        resultSet.getString("manager_name")
                ))
                .list();
    }

    public record Candidate(
            UUID agreementId,
            String number,
            AgreementStatus status,
            LocalDate validUntil,
            PlanKind plannedKind,
            LocalDate plannedOn,
            LocalDate plannedBaseUntil,
            UUID organizationId,
            String organizationName,
            UUID teamId,
            String teamName,
            UUID managerId,
            String managerName
    ) {
    }
}
