package ru.rtk.crm.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;

@Repository
public class LearningHistoryRepository {
    private final JdbcClient jdbcClient;

    public LearningHistoryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Observation> findObservations(
            VisibilityScope scope,
            LocalDate from,
            LocalDate to,
            List<UUID> organizationIds,
            List<UUID> programIds
    ) {
        List<String> conditions = new ArrayList<>(List.of(
                "sm.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")",
                "sm.run_ends_on > :from",
                "sm.run_starts_on <= :to",
                "s.observed_from < :toAt"
        ));
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        parameters.put("from", from);
        parameters.put("to", to);
        parameters.put("toAt", endOfDay(to));
        if (!organizationIds.isEmpty()) {
            conditions.add("o.id IN (:organizationIds)");
            parameters.put("organizationIds", organizationIds);
        }
        if (!programIds.isEmpty()) {
            conditions.add("p.id IN (:programIds)");
            parameters.put("programIds", programIds);
        }
        return jdbcClient.sql("""
                SELECT s.mapping_id, sm.run_starts_on, sm.run_ends_on, o.id AS organization_id, o.name AS organization_name,
                       t.id AS team_id, t.name AS team_name, p.id AS program_id, p.name AS program_name, s.observed_from,
                       s.participants_count, s.completed_count
                FROM learning_observations s
                JOIN source_mappings sm ON sm.id = s.mapping_id AND sm.kind IN ('COURSE', 'GROUP')
                    AND sm.run_starts_on IS NOT NULL AND sm.run_kind = 'STUDENTS'
                JOIN organizations o ON o.id = sm.organization_id
                JOIN teams t ON t.id = o.team_id
                JOIN programs p ON p.id = sm.program_id
                WHERE %s
                ORDER BY s.mapping_id, s.observed_from
                """.formatted(String.join(" AND ", conditions)))
                .params(parameters)
                .query((resultSet, rowNumber) -> new Observation(
                        resultSet.getObject("mapping_id", UUID.class),
                        resultSet.getObject("run_starts_on", LocalDate.class),
                        resultSet.getObject("run_ends_on", LocalDate.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getObject("team_id", UUID.class),
                        resultSet.getString("team_name"),
                        resultSet.getObject("program_id", UUID.class),
                        resultSet.getString("program_name"),
                        resultSet.getObject("observed_from", OffsetDateTime.class),
                        resultSet.getInt("participants_count"),
                        resultSet.getObject("completed_count", Integer.class)
                ))
                .list();
    }

    public static Map<UUID, Observation> inForce(List<Observation> observations, LocalDate day) {
        OffsetDateTime until = endOfDay(day);
        Map<UUID, Observation> latest = new LinkedHashMap<>();
        for (Observation observation : observations) {
            if (observation.observedFrom().isBefore(until) && !observation.runStartsOn().isAfter(day)) {
                latest.put(observation.mappingId(), observation);
            }
        }
        return latest;
    }

    public static OffsetDateTime endOfDay(LocalDate day) {
        return day.plusDays(1).atStartOfDay(ReportRequest.ZONE).toOffsetDateTime();
    }

    public record Observation(
            UUID mappingId,
            LocalDate runStartsOn,
            LocalDate runEndsOn,
            UUID organizationId,
            String organizationName,
            UUID teamId,
            String teamName,
            UUID programId,
            String programName,
            OffsetDateTime observedFrom,
            int participants,
            Integer completed
    ) {
        public boolean activeOn(LocalDate day) {
            return !runStartsOn.isAfter(day) && runEndsOn.isAfter(day);
        }
    }
}
