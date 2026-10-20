package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.agreement.AgreementStages;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.InteractionDue;
import ru.rtk.crm.interaction.InteractionFlag;
import ru.rtk.crm.interaction.InteractionRepository;
import ru.rtk.crm.interaction.WorkStatusAt;
import ru.rtk.crm.work.WorkModels.ReminderAgreement;
import ru.rtk.crm.work.WorkModels.ReminderLicense;
import ru.rtk.crm.work.WorkModels.ReminderStep;
import ru.rtk.crm.work.WorkModels.ReminderQualification;
import ru.rtk.crm.work.WorkModels.ReminderTraining;
import ru.rtk.crm.work.WorkModels.ReminderUnassigned;

@Repository
public class WorkRepository {
    private static final String OPEN_WORK = """
            FROM interactions i
            JOIN organizations o ON o.id = i.organization_id
            WHERE i.organization_id IN (SELECT id FROM organizations WHERE %s)
              AND i.work_status = 'ACTIVE'
            """;
    // «Долго на этапе» считается в Java: порог у каждой работы свой (норматив этапа шаблона или общий), а вычитать
    // интервал из даты одинаково на H2 и PostgreSQL нельзя.
    private static final String WORK_FLAGS = """
            SELECT %s AS group_id,
            """ + "CASE WHEN " + InteractionDue.OVERDUE.condition() + " THEN 1 ELSE 0 END AS overdue,\n"
            + "CASE WHEN " + InteractionDue.NO_NEXT_STEP.condition() + " THEN 1 ELSE 0 END AS without_next_step,\n" + """
                   %s AS stage_entered_at,
                   CASE WHEN %s THEN 1 ELSE 0 END AS under_approval,
                   (SELECT ts.norm_days FROM interaction_stages cs JOIN workflow_template_stages ts ON ts.id = cs.template_stage_id
                    WHERE cs.id = i.current_stage_id) AS norm_days,
            """ + "CASE WHEN " + InteractionFlag.RISK_OR_PROBLEM.condition() + " THEN 1 ELSE 0 END AS at_risk\n";

    private static final String COUNTED_LEARNING = """
            FROM learning_snapshots snapshot
            JOIN source_records source_record ON source_record.id = snapshot.source_record_id
            JOIN source_mappings source_mapping ON source_mapping.source = source_record.source
                AND source_mapping.external_key = source_record.external_id
                AND source_mapping.kind = CASE WHEN snapshot.group_id IS NULL THEN 'COURSE' ELSE 'GROUP' END
                AND source_mapping.run_starts_on IS NOT NULL AND source_mapping.run_kind = 'STUDENTS'
            JOIN organizations ON organizations.id = snapshot.organization_id
            WHERE organizations.team_id = team.id AND """ + " " + OrganizationRepository.currentStatus("organizations") + " AND %s";

    private final JdbcClient jdbcClient;
    private final AgreementStages agreementStages;

    public WorkRepository(JdbcClient jdbcClient, AgreementStages agreementStages) {
        this.jdbcClient = jdbcClient;
        this.agreementStages = agreementStages;
    }

    public Map<UUID, WorkCounts> countWorkByManager(VisibilityScope scope, OffsetDateTime now, int stuckDays) {
        String manager = "CASE WHEN %s THEN NULL ELSE o.owner_manager_id END"
                .formatted(OrganizationRepository.requiresAssignment("o"));
        return countWork(manager, scope, now, stuckDays);
    }

    public Map<UUID, WorkCounts> countWorkByTeam(VisibilityScope scope, OffsetDateTime now, int stuckDays) {
        return countWork("o.team_id", scope, now, stuckDays);
    }

    private Map<UUID, WorkCounts> countWork(String group, VisibilityScope scope, OffsetDateTime now, int stuckDays) {
        Map<UUID, WorkCounts> counts = new HashMap<>();
        jdbcClient.sql(WORK_FLAGS.formatted(group, InteractionRepository.STAGE_ENTERED_AT,
                        agreementStages.stageUnderApprovalCondition("i"))
                        + OPEN_WORK.formatted(scope.condition())
                        + " AND " + OrganizationRepository.currentStatus("o"))
                .params(scope.parameters())
                .params(agreementStages.currentStageParameters())
                .params(InteractionDue.parameters(now))
                .query(resultSet -> {
                    OffsetDateTime enteredAt = resultSet.getObject("stage_entered_at", OffsetDateTime.class);
                    Integer norm = resultSet.getObject("norm_days", Integer.class);
                    boolean stuck = resultSet.getInt("under_approval") == 0 && enteredAt != null
                            && !enteredAt.isAfter(now.minusDays(norm == null ? stuckDays : norm));
                    counts.merge(resultSet.getObject("group_id", UUID.class), new WorkCounts(1,
                            resultSet.getInt("overdue"), resultSet.getInt("without_next_step"), stuck ? 1 : 0,
                            resultSet.getInt("at_risk")), WorkCounts::plus);
                });
        return counts;
    }

    public List<PortfolioChange.State> findPortfolioStates(VisibilityScope scope, OffsetDateTime fromAt, OffsetDateTime toAt) {
        return jdbcClient.sql("SELECT t.id AS team_id, t.name AS team_name, " + WorkStatusAt.sql("fromAt") + " AS status_start, "
                        + WorkStatusAt.sql("toAt") + " AS status_end"
                        + " FROM interactions i JOIN organizations o ON o.id = i.organization_id JOIN teams t ON t.id = o.team_id"
                        + " WHERE i.organization_id IN (" + scope.currentOrganizations() + ")"
                        + " AND o.type <> 'OPEN_ENROLLMENT' AND i.created_at < :toAt")
                .params(scope.parameters())
                .param("fromAt", fromAt)
                .param("toAt", toAt)
                .query((resultSet, rowNumber) -> new PortfolioChange.State(
                        resultSet.getObject("team_id", UUID.class),
                        resultSet.getString("team_name"),
                        resultSet.getString("status_start"),
                        resultSet.getString("status_end")
                ))
                .list();
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

    public List<TeamOrganizations> countOrganizationsByTeam(VisibilityScope scope, LocalDate today) {
        return jdbcClient.sql("""
                SELECT team.id, team.name,
                       (SELECT COUNT(*) FROM organizations WHERE organizations.team_id = team.id AND %1$s AND %4$s
                            AND organizations.type <> 'OPEN_ENROLLMENT') AS organizations,
                       (SELECT COUNT(*) FROM organizations WHERE organizations.team_id = team.id AND %1$s AND %4$s AND %2$s)
                           AS unassigned_organizations,
                       (SELECT COUNT(DISTINCT snapshot.organization_id) %3$s AND snapshot.participants_count > 0)
                           AS organizations_with_learning,
                       (SELECT COALESCE(SUM(snapshot.participants_count), 0) %3$s) AS participants,
                       (SELECT COALESCE(SUM(CASE WHEN snapshot.completed_count IS NOT NULL
                                AND source_mapping.run_starts_on <= :today AND :today < source_mapping.run_ends_on
                                THEN snapshot.participants_count - snapshot.completed_count ELSE 0 END), 0) %3$s) AS learning_now,
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
                .param("today", today)
                .query((resultSet, rowNumber) -> new TeamOrganizations(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getLong("organizations"),
                        resultSet.getLong("unassigned_organizations"),
                        resultSet.getLong("organizations_with_learning"),
                        resultSet.getLong("participants"),
                        resultSet.getLong("learning_now"),
                        resultSet.getLong("teachers")
                ))
                .list();
    }

    // Итоги групп ленты «Моей работы» одним запросом и по тому же правилу срока, что отбор due списка: число в
    // группе равно «Найдено» в списке по ссылке «Показать все», сколько бы строк ни было.
    public WorkModels.ReminderFeed countFeed(VisibilityScope scope, OffsetDateTime now) {
        String active = "COUNT(CASE WHEN i.work_status = 'ACTIVE' AND %s THEN 1 END)";
        return jdbcClient.sql("SELECT "
                        + active.formatted(InteractionDue.OVERDUE.condition()) + " AS overdue, "
                        + active.formatted(InteractionDue.TODAY.condition()) + " AS today, "
                        + active.formatted(InteractionDue.THIS_WEEK.condition()) + " AS this_week, "
                        + active.formatted(InteractionDue.LATER.condition()) + " AS later, "
                        + active.formatted(InteractionDue.NO_NEXT_STEP.condition()) + " AS no_next_step, "
                        + """
                        COUNT(CASE WHEN i.work_status = 'PAUSED' AND i.next_action_at < :dueDayEnd THEN 1 END) AS resume_due,
                        COUNT(CASE WHEN i.work_status = 'PAUSED' AND i.next_action_at IS NULL THEN 1 END) AS resume_no_date
                        FROM interactions i
                        WHERE i.organization_id IN (SELECT id FROM organizations WHERE %s)
                        """.formatted(scope.condition()))
                .params(scope.parameters())
                .params(InteractionDue.parameters(now))
                .query((resultSet, rowNumber) -> new WorkModels.ReminderFeed(
                        resultSet.getLong("overdue"),
                        resultSet.getLong("today"),
                        resultSet.getLong("this_week"),
                        resultSet.getLong("later"),
                        resultSet.getLong("no_next_step"),
                        resultSet.getLong("resume_due"),
                        resultSet.getLong("resume_no_date")
                ))
                .single();
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

    // Последнее событие назначения вуза — снятие из-за блокировки КАМ, и с тех пор КАМ не назначен.
    public List<ReminderUnassigned> findUnassignedAfterBlock(VisibilityScope scope, OffsetDateTime since, int limit) {
        return jdbcClient.sql("""
                SELECT o.id, o.name, e.previous_owner_manager_display_name, e.occurred_at
                FROM organizations o
                JOIN organization_assignment_events e ON e.organization_id = o.id
                WHERE o.id IN (SELECT id FROM organizations WHERE %s)
                  AND o.owner_manager_id IS NULL
                  AND e.reason = 'PROFILE_BLOCKED' AND e.owner_manager_id IS NULL AND e.occurred_at >= :since
                  AND NOT EXISTS (
                      SELECT 1 FROM organization_assignment_events later
                      WHERE later.organization_id = o.id AND later.occurred_at > e.occurred_at
                  )
                ORDER BY e.occurred_at DESC, o.name, o.id
                LIMIT :limit
                """.formatted(scope.condition()))
                .params(scope.parameters())
                .param("since", since)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new ReminderUnassigned(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getString("previous_owner_manager_display_name"),
                        resultSet.getObject("occurred_at", OffsetDateTime.class)
                ))
                .list();
    }

    // Истёкшие «Действует» тоже попадают в список (нижней границы нет): они уходят из него, только когда КАМ продлит
    // срок или переведёт договор в «Завершён».
    public List<ReminderAgreement> findExpiringAgreements(VisibilityScope scope, LocalDate until, int limit) {
        return jdbcClient.sql("""
                SELECT ag.id, ag.organization_id, o.name AS organization_name, ag.number, ag.valid_until, ag.planned_kind,
                       ag.planned_on,
                       (SELECT owner_profile.display_name FROM crm_user_profiles owner_profile
                        WHERE owner_profile.id = o.owner_manager_id) AS owner_manager_name
                FROM agreements ag
                JOIN organizations o ON o.id = ag.organization_id
                WHERE ag.organization_id IN (SELECT id FROM organizations WHERE %s)
                  AND ag.status = 'ACTIVE' AND ag.valid_until IS NOT NULL AND ag.valid_until <= :until
                ORDER BY ag.valid_until, o.name, ag.number, ag.id
                LIMIT :limit
                """.formatted(scope.condition()))
                .params(scope.parameters())
                .param("until", until)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new ReminderAgreement(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getString("number"),
                        resultSet.getObject("valid_until", LocalDate.class),
                        resultSet.getString("planned_kind"),
                        resultSet.getObject("planned_on", LocalDate.class),
                        resultSet.getString("owner_manager_name")
                ))
                .list();
    }

    public List<ReminderTraining> findTrainingCycles(
            VisibilityScope scope,
            OffsetDateTime trainedBefore,
            int cycleYears,
            int limit
    ) {
        return jdbcClient.sql("""
                SELECT i.id, i.organization_id, i.title, o.name AS organization_name,
                       training.to_stage_name_snapshot AS stage_name, training.occurred_at AS trained_at
                FROM interaction_events training
                JOIN interactions i ON i.id = training.interaction_id
                JOIN organizations o ON o.id = i.organization_id
                WHERE i.organization_id IN (SELECT id FROM organizations WHERE %s)
                  AND EXISTS (SELECT 1 FROM interaction_stages ts WHERE ts.id = training.to_stage_id
                      AND ts.lms_role IN ('TEACHER_TRAINING', 'QUALIFICATION'))
                  AND training.occurred_at < :trainedBefore
                  AND NOT EXISTS (
                      SELECT 1
                      FROM interactions later_work
                      JOIN interaction_events later ON later.interaction_id = later_work.id
                      WHERE later_work.organization_id = i.organization_id
                        AND EXISTS (SELECT 1 FROM interaction_stages ls WHERE ls.id = later.to_stage_id
                            AND ls.lms_role IN ('TEACHER_TRAINING', 'QUALIFICATION'))
                        AND (later.occurred_at > training.occurred_at
                            OR later.occurred_at = training.occurred_at AND later.id > training.id)
                  )
                ORDER BY training.occurred_at, o.name, i.id
                LIMIT :limit
                """.formatted(scope.condition()))
                .params(scope.parameters())
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

    /**
     * Работы, где с последней записи «Обучение преподавателей» прошло не меньше порога, а этап повышения квалификации
     * ещё впереди и не отмечен. Это только напоминание: этап отмечает пользователь в карточке.
     */
    public List<ReminderQualification> findQualificationDue(
            VisibilityScope scope,
            LocalDate trainedBy,
            int afterMonths,
            int limit
    ) {
        if (afterMonths <= 0) {
            return List.of();
        }
        return jdbcClient.sql("""
                SELECT i.id, i.organization_id, i.title, o.name AS organization_name, q.name AS stage_name,
                       last_training.trained_on
                FROM interactions i
                JOIN organizations o ON o.id = i.organization_id
                JOIN interaction_stages current_stage ON current_stage.id = i.current_stage_id
                JOIN interaction_stages q ON q.interaction_id = i.id AND q.lms_role = 'QUALIFICATION'
                    AND NOT EXISTS (
                        SELECT 1 FROM interaction_stages earlier
                        WHERE earlier.interaction_id = i.id AND earlier.lms_role = 'QUALIFICATION' AND earlier.stage_order < q.stage_order
                    )
                JOIN (
                    SELECT interaction_id, MAX(trained_on) AS trained_on FROM teacher_trainings GROUP BY interaction_id
                ) last_training ON last_training.interaction_id = i.id
                WHERE i.organization_id IN (SELECT id FROM organizations WHERE %s)
                  AND i.work_status = 'ACTIVE'
                  AND last_training.trained_on <= :trainedBy
                  AND current_stage.stage_order < q.stage_order
                  AND NOT EXISTS (
                      SELECT 1 FROM interaction_stage_completions c WHERE c.interaction_id = i.id AND c.stage_id = q.id
                  )
                ORDER BY last_training.trained_on, o.name, i.id
                LIMIT :limit
                """.formatted(scope.condition()))
                .params(scope.parameters())
                .param("trainedBy", trainedBy)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> {
                    LocalDate trainedOn = resultSet.getObject("trained_on", LocalDate.class);
                    return new ReminderQualification(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getObject("organization_id", UUID.class),
                            resultSet.getString("title"),
                            resultSet.getString("organization_name"),
                            resultSet.getString("stage_name"),
                            trainedOn,
                            trainedOn.plusMonths(afterMonths)
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

    public record WorkCounts(long interactions, long overdue, long withoutNextStep, long stuck, long atRisk) {
        WorkCounts plus(WorkCounts other) {
            return new WorkCounts(interactions + other.interactions, overdue + other.overdue,
                    withoutNextStep + other.withoutNextStep, stuck + other.stuck, atRisk + other.atRisk);
        }

        static final WorkCounts EMPTY = new WorkCounts(0, 0, 0, 0, 0);
    }

    public record TeamOrganizations(
            UUID teamId,
            String teamName,
            long organizations,
            long unassignedOrganizations,
            long organizationsWithLearning,
            long participants,
            long learningNow,
            long teachers
    ) {
    }
}
