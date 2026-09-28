package ru.rtk.crm.work;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.InteractionRepository;
import ru.rtk.crm.work.WorkModels.ReminderLicense;
import ru.rtk.crm.work.WorkModels.ReminderStep;
import ru.rtk.crm.work.WorkModels.ReminderTraining;

@Repository
public class WorkRepository {
    private static final String OPEN_WORK = """
            FROM interactions i
            JOIN organizations o ON o.id = i.organization_id
            WHERE i.organization_id IN (SELECT id FROM organizations WHERE %s)
              AND i.work_status = 'ACTIVE'
            """;
    private static final String WORK_FLAGS = """
            SELECT %s AS group_id,
                   CASE WHEN i.next_action_at < :now THEN 1 ELSE 0 END AS overdue,
                   CASE WHEN i.next_action IS NULL OR i.next_action_at IS NULL THEN 1 ELSE 0 END AS without_next_step,
                   CASE WHEN %s <= :stuckBefore THEN 1 ELSE 0 END AS stuck
            """;

    private static final String COUNTED_LEARNING = """
            FROM learning_snapshots snapshot
            JOIN source_records source_record ON source_record.id = snapshot.source_record_id
            JOIN source_mappings source_mapping ON source_mapping.source = source_record.source
                AND source_mapping.external_key = source_record.external_id
                AND source_mapping.kind = CASE WHEN snapshot.group_id IS NULL THEN 'COURSE' ELSE 'GROUP' END
                AND source_mapping.run_starts_on IS NOT NULL AND source_mapping.run_kind = 'STUDENTS'
            JOIN organizations ON organizations.id = snapshot.organization_id
            WHERE organizations.team_id = team.id AND %s""";

    private final JdbcClient jdbcClient;

    public WorkRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Map<UUID, WorkCounts> countWorkByManager(VisibilityScope scope, OffsetDateTime now, OffsetDateTime stuckBefore) {
        String manager = "CASE WHEN %s THEN NULL ELSE o.owner_manager_id END"
                .formatted(OrganizationRepository.requiresAssignment("o"));
        return countWork(manager, scope, now, stuckBefore);
    }

    public Map<UUID, WorkCounts> countWorkByTeam(VisibilityScope scope, OffsetDateTime now, OffsetDateTime stuckBefore) {
        return countWork("o.team_id", scope, now, stuckBefore);
    }

    private Map<UUID, WorkCounts> countWork(String group, VisibilityScope scope, OffsetDateTime now, OffsetDateTime stuckBefore) {
        Map<UUID, WorkCounts> counts = new HashMap<>();
        jdbcClient.sql("""
                SELECT work.group_id, COUNT(*) AS interactions, SUM(work.overdue) AS overdue,
                       SUM(work.without_next_step) AS without_next_step, SUM(work.stuck) AS stuck
                FROM (
                """ + WORK_FLAGS.formatted(group, InteractionRepository.STAGE_ENTERED_AT)
                        + OPEN_WORK.formatted(scope.condition()) + """
                ) work
                GROUP BY work.group_id
                """)
                .params(scope.parameters())
                .param("now", now)
                .param("stuckBefore", stuckBefore)
                .query((resultSet, rowNumber) -> counts.put(
                        resultSet.getObject("group_id", UUID.class),
                        new WorkCounts(
                                resultSet.getLong("interactions"),
                                resultSet.getLong("overdue"),
                                resultSet.getLong("without_next_step"),
                                resultSet.getLong("stuck")
                        )
                ))
                .list();
        return counts;
    }

    public Map<UUID, Long> countOrganizationsByManager(VisibilityScope scope) {
        Map<UUID, Long> counts = new HashMap<>();
        jdbcClient.sql("""
                SELECT owned.group_id, COUNT(*) AS organizations
                FROM (
                    SELECT CASE WHEN %s THEN NULL ELSE organizations.owner_manager_id END AS group_id
                    FROM organizations
                    WHERE %s AND %s
                ) owned
                GROUP BY owned.group_id
                """.formatted(
                        OrganizationRepository.requiresAssignment("organizations"),
                        scope.condition(),
                        OrganizationRepository.currentStatus("organizations")
                ))
                .params(scope.parameters())
                .query((resultSet, rowNumber) -> counts.put(
                        resultSet.getObject("group_id", UUID.class),
                        resultSet.getLong("organizations")
                ))
                .list();
        return counts;
    }

    public List<TeamOrganizations> countOrganizationsByTeam(VisibilityScope scope) {
        return jdbcClient.sql("""
                SELECT team.id, team.name,
                       (SELECT COUNT(*) FROM organizations WHERE organizations.team_id = team.id AND %1$s AND %4$s
                            AND organizations.type <> 'OPEN_ENROLLMENT') AS organizations,
                       (SELECT COUNT(*) FROM organizations WHERE organizations.team_id = team.id AND %1$s AND %4$s AND %2$s)
                           AS unassigned_organizations,
                       (SELECT COUNT(DISTINCT snapshot.organization_id) %3$s AND snapshot.participants_count > 0)
                           AS organizations_with_learning,
                       (SELECT COALESCE(SUM(snapshot.participants_count), 0) %3$s) AS participants,
                       (SELECT COALESCE(SUM(snapshot.teachers_count), 0) %3$s) AS teachers
                FROM teams team
                WHERE team.archived = FALSE
                   OR EXISTS (SELECT 1 FROM organizations WHERE organizations.team_id = team.id AND %1$s AND %4$s)
                ORDER BY team.name, team.id
                """.formatted(
                        scope.condition(),
                        OrganizationRepository.requiresAssignment("organizations"),
                        COUNTED_LEARNING.formatted(scope.condition()),
                        OrganizationRepository.currentStatus("organizations")
                ))
                .params(scope.parameters())
                .query((resultSet, rowNumber) -> new TeamOrganizations(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getLong("organizations"),
                        resultSet.getLong("unassigned_organizations"),
                        resultSet.getLong("organizations_with_learning"),
                        resultSet.getLong("participants"),
                        resultSet.getLong("teachers")
                ))
                .list();
    }

    public long countSteps(VisibilityScope scope, OffsetDateTime from, OffsetDateTime to) {
        StepRange range = stepRange(from, to);
        return jdbcClient.sql("SELECT COUNT(*) " + OPEN_WORK.formatted(scope.condition()) + range.condition())
                .params(scope.parameters())
                .params(range.parameters())
                .query(Long.class)
                .single();
    }

    public List<ReminderStep> findSteps(VisibilityScope scope, OffsetDateTime from, OffsetDateTime to, int limit) {
        StepRange range = stepRange(from, to);
        return jdbcClient.sql("""
                SELECT i.id, i.organization_id, i.title, o.name AS organization_name, i.next_action, i.next_action_at,
                       (SELECT owner_profile.display_name FROM crm_user_profiles owner_profile
                        WHERE owner_profile.id = o.owner_manager_id) AS owner_manager_name
                """ + OPEN_WORK.formatted(scope.condition()) + range.condition() + """
                ORDER BY i.next_action_at, o.name, i.id
                LIMIT :limit
                """)
                .params(scope.parameters())
                .params(range.parameters())
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new ReminderStep(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getString("organization_name"),
                        resultSet.getString("next_action"),
                        resultSet.getObject("next_action_at", OffsetDateTime.class),
                        resultSet.getString("owner_manager_name")
                ))
                .list();
    }

    public List<ReminderLicense> findExpiringLicenses(VisibilityScope scope, int expiresBy, int limit) {
        return jdbcClient.sql("""
                SELECT i.id, i.organization_id, i.title, o.name AS organization_name,
                       product.name AS product_name, vendor.name AS vendor_name, agreement.contract_number,
                       agreement.license_expiry_year
                FROM product_agreements agreement
                JOIN products product ON product.id = agreement.product_id
                LEFT JOIN vendors vendor ON vendor.id = product.vendor_id
                JOIN interactions i ON i.id = agreement.interaction_id
                JOIN organizations o ON o.id = i.organization_id
                WHERE i.organization_id IN (SELECT id FROM organizations WHERE %s)
                  AND agreement.license_expiry_year <= :expiresBy
                  AND NOT EXISTS (
                      SELECT 1
                      FROM product_agreements renewal
                      JOIN interactions renewal_work ON renewal_work.id = renewal.interaction_id
                      WHERE renewal.product_id = agreement.product_id
                        AND renewal_work.organization_id = i.organization_id
                        AND (renewal.license_expiry_year > agreement.license_expiry_year
                            OR renewal.license_expiry_year = agreement.license_expiry_year AND renewal.id > agreement.id)
                  )
                ORDER BY agreement.license_expiry_year, o.name, product.name, agreement.id
                LIMIT :limit
                """.formatted(scope.condition()))
                .params(scope.parameters())
                .param("expiresBy", expiresBy)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new ReminderLicense(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getString("organization_name"),
                        resultSet.getString("product_name"),
                        resultSet.getString("vendor_name"),
                        resultSet.getString("contract_number"),
                        resultSet.getInt("license_expiry_year")
                ))
                .list();
    }

    public List<ReminderTraining> findTrainingCycles(
            VisibilityScope scope,
            List<String> stageNames,
            OffsetDateTime trainedBefore,
            int cycleYears,
            int limit
    ) {
        if (stageNames.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                SELECT i.id, i.organization_id, i.title, o.name AS organization_name,
                       training.to_stage_name_snapshot AS stage_name, training.occurred_at AS trained_at
                FROM interaction_events training
                JOIN interactions i ON i.id = training.interaction_id
                JOIN organizations o ON o.id = i.organization_id
                WHERE i.organization_id IN (SELECT id FROM organizations WHERE %s)
                  AND training.to_stage_name_snapshot IN (:stageNames)
                  AND training.occurred_at < :trainedBefore
                  AND NOT EXISTS (
                      SELECT 1
                      FROM interactions later_work
                      JOIN interaction_events later ON later.interaction_id = later_work.id
                      WHERE later_work.organization_id = i.organization_id
                        AND later.to_stage_name_snapshot IN (:stageNames)
                        AND (later.occurred_at > training.occurred_at
                            OR later.occurred_at = training.occurred_at AND later.id > training.id)
                  )
                ORDER BY training.occurred_at, o.name, i.id
                LIMIT :limit
                """.formatted(scope.condition()))
                .params(scope.parameters())
                .param("stageNames", stageNames)
                .param("trainedBefore", trainedBefore)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> {
                    OffsetDateTime trainedAt = resultSet.getObject("trained_at", OffsetDateTime.class);
                    return new ReminderTraining(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getObject("organization_id", UUID.class),
                            resultSet.getString("title"),
                            resultSet.getString("organization_name"),
                            resultSet.getString("stage_name"),
                            trainedAt,
                            trainedAt.atZoneSameInstant(WorkProperties.ZONE).toLocalDate().plusYears(cycleYears)
                    );
                })
                .list();
    }

    public Optional<Boolean> findRemindersEnabled(UUID profileId) {
        return jdbcClient.sql("SELECT enabled FROM reminder_settings WHERE profile_id = :profileId")
                .param("profileId", profileId)
                .query(Boolean.class)
                .optional();
    }

    public void saveRemindersEnabled(UUID profileId, boolean enabled, OffsetDateTime now) {
        int inserted = jdbcClient.sql("""
                INSERT INTO reminder_settings (profile_id, enabled, updated_at)
                VALUES (:profileId, :enabled, :updatedAt)
                ON CONFLICT DO NOTHING
                """)
                .param("profileId", profileId)
                .param("enabled", enabled)
                .param("updatedAt", now)
                .update();
        if (inserted == 0) {
            jdbcClient.sql("""
                    UPDATE reminder_settings SET enabled = :enabled, updated_at = :updatedAt
                    WHERE profile_id = :profileId
                    """)
                    .param("profileId", profileId)
                    .param("enabled", enabled)
                    .param("updatedAt", now)
                    .update();
        }
    }

    private StepRange stepRange(OffsetDateTime from, OffsetDateTime to) {
        Map<String, Object> parameters = new HashMap<>();
        StringBuilder condition = new StringBuilder(" AND i.next_action_at IS NOT NULL");
        if (from != null) {
            condition.append(" AND i.next_action_at >= :stepsFrom");
            parameters.put("stepsFrom", from);
        }
        condition.append(" AND i.next_action_at < :stepsTo ");
        parameters.put("stepsTo", to);
        return new StepRange(condition.toString(), parameters);
    }

    private record StepRange(String condition, Map<String, Object> parameters) {
    }

    public record WorkCounts(long interactions, long overdue, long withoutNextStep, long stuck) {
        static final WorkCounts EMPTY = new WorkCounts(0, 0, 0, 0);
    }

    public record TeamOrganizations(
            UUID teamId,
            String teamName,
            long organizations,
            long unassignedOrganizations,
            long organizationsWithLearning,
            long participants,
            long teachers
    ) {
    }
}
