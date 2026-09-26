package ru.rtk.crm.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationAssignmentAccessDeniedException;
import ru.rtk.crm.catalog.OrganizationAssignmentRepository;
import ru.rtk.crm.catalog.OrganizationAssignmentRequest;
import ru.rtk.crm.catalog.OrganizationAssignmentService;
import ru.rtk.crm.catalog.OrganizationBulkAssignmentRequest;
import ru.rtk.crm.catalog.OrganizationBulkAssignmentResult;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:work_control;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        OrganizationAssignmentRepository.class,
        OrganizationAssignmentService.class,
        OrganizationDeputyRepository.class,
        OrganizationDeputyService.class,
        CommandIdempotencyRepository.class,
        WorkRepository.class,
        WorkService.class,
        WorkControlTest.TestBeans.class
})
class WorkControlTest {
    private static final UUID TEAM_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID LEADER_A = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID KAM_A = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID KAM_C = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID KAM_B = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private static final UUID LEADER_B = UUID.fromString("00000000-0000-0000-0000-000000000014");
    private static final UUID MANAGEMENT = UUID.fromString("00000000-0000-0000-0000-000000000015");
    private static final UUID UNIVERSITY_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID UNIVERSITY_C = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID UNIVERSITY_B = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID UNIVERSITY_D = UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final String REQUEST_ID = "00000000-0000-0000-0000-000000000999";

    private final CrmProfile leaderA = new CrmProfile(LEADER_A, UserRole.LEADER, TEAM_A, 0);
    private final CrmProfile leaderB = new CrmProfile(LEADER_B, UserRole.LEADER, TEAM_B, 0);
    private final CrmProfile kamA = new CrmProfile(KAM_A, UserRole.USER, TEAM_A, 0);
    private final CrmProfile kamC = new CrmProfile(KAM_C, UserRole.USER, TEAM_A, 0);
    private final CrmProfile kamB = new CrmProfile(KAM_B, UserRole.USER, TEAM_B, 0);
    private final CrmProfile management = new CrmProfile(MANAGEMENT, UserRole.MANAGEMENT, null, 0);

    @Autowired
    private OrganizationDeputyService organizationDeputyService;

    @Autowired
    private OrganizationAssignmentService organizationAssignmentService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkService workService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of(
                "reminder_settings", "learning_snapshots", "source_mappings", "source_records", "product_agreements", "products", "interaction_events",
                "interactions", "organization_deputies", "organization_assignment_events", "command_idempotency_records",
                "organizations", "crm_user_profiles", "teams"
        )) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, ?), (?, ?)", TEAM_A, "Команда А", TEAM_B, "Команда Б");
        insertProfile(LEADER_A, "Руководитель А", "LEADER", TEAM_A, true);
        insertProfile(KAM_A, "КАМ А", "USER", TEAM_A, true);
        insertProfile(KAM_C, "КАМ В", "USER", TEAM_A, true);
        insertProfile(KAM_B, "КАМ Б", "USER", TEAM_B, true);
        insertProfile(LEADER_B, "Руководитель Б", "LEADER", TEAM_B, true);
        insertProfile(MANAGEMENT, "Руководство школы", "MANAGEMENT", null, true);
        insertOrganization(UNIVERSITY_A, "Университет А", TEAM_A, KAM_A);
        insertOrganization(UNIVERSITY_C, "Университет C", TEAM_A, null);
        insertOrganization(UNIVERSITY_B, "Университет Б", TEAM_B, KAM_B);
    }

    @Test
    void deputyGetsTemporaryAccessWhileOwnerKeepsItAndLosesItWhenPeriodEnds() {
        LocalDate today = LocalDate.now(WorkProperties.ZONE);
        OrganizationDeputyRequest request = new OrganizationDeputyRequest(KAM_C, today, today.plusDays(1));

        OrganizationDeputy deputy = organizationDeputyService.assign(leaderA, UNIVERSITY_A, request, "deputy-assign");
        OrganizationDeputy replayed = organizationDeputyService.assign(leaderA, UNIVERSITY_A, request, "deputy-assign");

        assertThat(replayed).isEqualTo(deputy);
        assertThat(deputy.status()).isEqualTo(OrganizationDeputy.Status.ACTIVE);
        assertThat(deputy.deputyDisplayName()).isEqualTo("КАМ В");
        assertThat(deputy.actorDisplayName()).isEqualTo("Руководитель А");
        assertThat(organizationRepository.findVisibleById(kamC, UNIVERSITY_A)).hasValueSatisfying(organization -> {
            assertThat(organization.ownerManagerId()).isEqualTo(KAM_A);
            assertThat(organization.deputyManagerName()).isEqualTo("КАМ В");
            assertThat(organization.deputyEndsOn()).isEqualTo(today.plusDays(1));
        });
        assertThat(organizationRepository.findVisibleById(kamA, UNIVERSITY_A)).isPresent();
        assertThat(accessRevision(KAM_C)).isEqualTo(1);
        assertThat(organizationDeputyService.list(leaderA, UNIVERSITY_A)).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(deputy.id());
            assertThat(row.status()).isEqualTo(OrganizationDeputy.Status.ACTIVE);
        });

        assertThatThrownBy(() -> organizationDeputyService.assign(
                leaderA,
                UNIVERSITY_A,
                new OrganizationDeputyRequest(KAM_C, today, today.plusDays(3)),
                "deputy-assign"
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
            assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        });
        assertThatThrownBy(() -> organizationDeputyService.assign(
                leaderA,
                UNIVERSITY_A,
                new OrganizationDeputyRequest(KAM_C, today, today.plusDays(3)),
                "deputy-second"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("deputyProfileId");
        });

        OrganizationDeputy ended = organizationDeputyService.end(leaderA, UNIVERSITY_A, deputy.id(), "deputy-end");

        assertThat(ended.status()).isEqualTo(OrganizationDeputy.Status.ENDED);
        assertThat(ended.endedByDisplayName()).isEqualTo("Руководитель А");
        assertThat(organizationRepository.findVisibleById(kamC, UNIVERSITY_A)).isEmpty();
        assertThat(organizationRepository.findVisibleById(kamA, UNIVERSITY_A)).isPresent();
        assertThat(accessRevision(KAM_C)).isEqualTo(2);
        assertThat(organizationDeputyService.end(leaderA, UNIVERSITY_A, deputy.id(), "deputy-end")).isEqualTo(ended);
        assertThatThrownBy(() -> organizationDeputyService.end(leaderA, UNIVERSITY_A, deputy.id(), "deputy-end-again"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
                    assertThat(exception.field()).isEqualTo("deputyId");
                });
        assertThatThrownBy(() -> organizationDeputyService.end(leaderA, UNIVERSITY_A, UUID.randomUUID(), "deputy-end-missing"))
                .isInstanceOf(OrganizationDeputyNotFoundException.class);
    }

    @Test
    void expiredDeputyPeriodIsEndedAutomaticallyAndScheduledPeriodGivesNoAccessYet() {
        LocalDate today = LocalDate.now(WorkProperties.ZONE);
        OrganizationDeputy deputy = organizationDeputyService.assign(
                leaderA,
                UNIVERSITY_A,
                new OrganizationDeputyRequest(KAM_C, today, today),
                "deputy-today"
        );
        jdbcTemplate.update(
                "UPDATE organization_deputies SET ends_at = ? WHERE id = ?",
                OffsetDateTime.now().minusMinutes(1),
                deputy.id()
        );

        assertThat(organizationRepository.findVisibleById(kamC, UNIVERSITY_A)).isEmpty();
        organizationDeputyService.endExpiredPeriods();

        assertThat(organizationDeputyService.list(leaderA, UNIVERSITY_A)).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(OrganizationDeputy.Status.ENDED);
            assertThat(row.endedAt()).isNotNull();
            assertThat(row.endedByProfileId()).isNull();
        });
        assertThat(accessRevision(KAM_C)).isEqualTo(2);

        OrganizationDeputy scheduled = organizationDeputyService.assign(
                leaderA,
                UNIVERSITY_A,
                new OrganizationDeputyRequest(KAM_C, today.plusDays(2), today.plusDays(5)),
                "deputy-later"
        );
        assertThat(scheduled.status()).isEqualTo(OrganizationDeputy.Status.SCHEDULED);
        assertThat(organizationRepository.findVisibleById(kamC, UNIVERSITY_A)).isEmpty();
    }

    @Test
    void deputyRulesAndScopeAreCheckedOnServer() {
        LocalDate today = LocalDate.now(WorkProperties.ZONE);

        assertThatThrownBy(() -> organizationDeputyService.assign(
                leaderB, UNIVERSITY_A, new OrganizationDeputyRequest(KAM_C, today, today), "deputy-foreign"
        )).isInstanceOf(OrganizationNotFoundException.class);
        assertThatThrownBy(() -> organizationDeputyService.assign(
                kamA, UNIVERSITY_A, new OrganizationDeputyRequest(KAM_C, today, today), "deputy-kam"
        )).isInstanceOf(OrganizationAssignmentAccessDeniedException.class);
        assertThatThrownBy(() -> organizationDeputyService.assign(
                management, UNIVERSITY_A, new OrganizationDeputyRequest(KAM_C, today, today), "deputy-management"
        )).isInstanceOf(OrganizationAssignmentAccessDeniedException.class);
        assertRejected(new OrganizationDeputyRequest(KAM_B, today, today), UNIVERSITY_A, "deputyProfileId");
        assertRejected(new OrganizationDeputyRequest(KAM_A, today, today), UNIVERSITY_A, "deputyProfileId");
        assertRejected(new OrganizationDeputyRequest(KAM_C, today, today.minusDays(1)), UNIVERSITY_A, "endsOn");
        assertRejected(new OrganizationDeputyRequest(KAM_C, today.minusDays(3), today.minusDays(1)), UNIVERSITY_A, "endsOn");
        assertRejected(new OrganizationDeputyRequest(KAM_C, today, today.plusDays(400)), UNIVERSITY_A, "endsOn");
        assertRejected(new OrganizationDeputyRequest(KAM_C, today, today), UNIVERSITY_C, "deputyProfileId");
        assertThat(organizationDeputyService.list(leaderA, UNIVERSITY_A)).isEmpty();
    }

    @Test
    void leaderTransfersSeveralOrganizationsToDifferentManagersInOneConfirmedCommand() {
        jdbcTemplate.update("UPDATE organizations SET owner_manager_id = ? WHERE id = ?", KAM_B, UNIVERSITY_C);
        OrganizationBulkAssignmentRequest request = new OrganizationBulkAssignmentRequest(List.of(
                new OrganizationBulkAssignmentRequest.Item(UNIVERSITY_A, 0, KAM_C),
                new OrganizationBulkAssignmentRequest.Item(UNIVERSITY_C, 0, KAM_A)
        ));

        OrganizationBulkAssignmentResult result = organizationAssignmentService.bulkAssign(leaderA, request, "bulk", REQUEST_ID);
        OrganizationBulkAssignmentResult replayed = organizationAssignmentService.bulkAssign(leaderA, request, "bulk", REQUEST_ID);

        assertThat(replayed).isEqualTo(result);
        assertThat(result.assigned()).hasSize(2);
        assertThat(result.unchangedOrganizationIds()).isEmpty();
        assertThat(ownerOf(UNIVERSITY_A)).isEqualTo(KAM_C);
        assertThat(ownerOf(UNIVERSITY_C)).isEqualTo(KAM_A);
        assertThat(result.assigned()).allSatisfy(assignment -> {
            assertThat(assignment.event().actorDisplayName()).isEqualTo("Руководитель А");
            assertThat(assignment.organization().version()).isEqualTo(1);
        });
        assertThat(result.assigned()).filteredOn(assignment -> assignment.organization().id().equals(UNIVERSITY_C))
                .singleElement()
                .satisfies(assignment -> assertThat(assignment.event().previousOwnerManagerDisplayName()).isEqualTo("КАМ Б"));
        assertThat(organizationRepository.findVisibleById(kamC, UNIVERSITY_A)).isPresent();
        assertThat(organizationRepository.findVisibleById(kamA, UNIVERSITY_A)).isEmpty();

        OrganizationBulkAssignmentResult unchanged = organizationAssignmentService.bulkAssign(
                leaderA,
                new OrganizationBulkAssignmentRequest(List.of(new OrganizationBulkAssignmentRequest.Item(UNIVERSITY_A, 1, KAM_C))),
                "bulk-unchanged",
                REQUEST_ID
        );
        assertThat(unchanged.assigned()).isEmpty();
        assertThat(unchanged.unchangedOrganizationIds()).containsExactly(UNIVERSITY_A);

        assertThatThrownBy(() -> organizationAssignmentService.bulkAssign(
                leaderA,
                new OrganizationBulkAssignmentRequest(List.of(new OrganizationBulkAssignmentRequest.Item(UNIVERSITY_C, 1, KAM_C))),
                "bulk",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
            assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        });
        assertThatThrownBy(() -> organizationAssignmentService.bulkAssign(
                leaderA,
                new OrganizationBulkAssignmentRequest(List.of(new OrganizationBulkAssignmentRequest.Item(UNIVERSITY_C, 0, KAM_C))),
                "bulk-stale",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
            assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
        });
        assertThatThrownBy(() -> organizationAssignmentService.bulkAssign(
                leaderA,
                new OrganizationBulkAssignmentRequest(List.of(
                        new OrganizationBulkAssignmentRequest.Item(UNIVERSITY_C, 1, KAM_C),
                        new OrganizationBulkAssignmentRequest.Item(UNIVERSITY_B, 0, KAM_C)
                )),
                "bulk-foreign",
                REQUEST_ID
        )).isInstanceOf(OrganizationNotFoundException.class);
        assertThat(ownerOf(UNIVERSITY_C)).isEqualTo(KAM_A);
        assertThatThrownBy(() -> organizationAssignmentService.bulkAssign(
                leaderA,
                new OrganizationBulkAssignmentRequest(List.of(new OrganizationBulkAssignmentRequest.Item(UNIVERSITY_C, 1, KAM_B))),
                "bulk-other-team-manager",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("ownerManagerId");
        });
        assertThatThrownBy(() -> organizationAssignmentService.bulkAssign(kamA, request, "bulk-kam", REQUEST_ID))
                .isInstanceOf(OrganizationAssignmentAccessDeniedException.class);
        assertThatThrownBy(() -> organizationAssignmentService.bulkAssign(management, request, "bulk-management", REQUEST_ID))
                .isInstanceOf(OrganizationAssignmentAccessDeniedException.class);
        assertThatThrownBy(() -> organizationAssignmentService.bulkAssign(
                leaderA,
                new OrganizationBulkAssignmentRequest(List.of(
                        new OrganizationBulkAssignmentRequest.Item(UNIVERSITY_A, 1, KAM_A),
                        new OrganizationBulkAssignmentRequest.Item(UNIVERSITY_A, 1, KAM_A)
                )),
                "bulk-duplicate",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("items");
        });
    }

    @Test
    void leaderIndicatorsCountEachManagerAndUnassignedWorkLikeTheWorkListFilters() {
        OffsetDateTime now = OffsetDateTime.now();
        insertInteraction(UNIVERSITY_A, "Просрочено у КАМ А", "Позвонить", now.minusDays(1), now.minusDays(40), "ACTIVE");
        insertInteraction(UNIVERSITY_A, "Без шага у КАМ А", null, null, now.minusDays(2), "ACTIVE");
        insertInteraction(UNIVERSITY_A, "Завершено у КАМ А", "Позвонить", now.minusDays(1), now.minusDays(40), "COMPLETED");
        insertInteraction(UNIVERSITY_A, "Приостановлено у КАМ А", "Позвонить", now.minusDays(1), now.minusDays(40), "PAUSED");
        insertInteraction(UNIVERSITY_C, "Просрочено без КАМ", null, now.minusHours(1), now.minusDays(1), "ACTIVE");
        insertInteraction(UNIVERSITY_B, "Чужая команда", "Позвонить", now.minusDays(1), now.minusDays(40), "ACTIVE");

        WorkModels.TeamIndicators indicators = workService.teamIndicators(leaderA, null);

        assertThat(indicators.stuckDays()).isEqualTo(30);
        assertThat(indicators.unassignedOrganizations()).isEqualTo(1);
        assertThat(indicators.managers()).containsExactly(
                new WorkModels.ManagerIndicators(KAM_A, "КАМ А", 1, 2, 1, 1, 1),
                new WorkModels.ManagerIndicators(KAM_C, "КАМ В", 0, 0, 0, 0, 0),
                new WorkModels.ManagerIndicators(null, null, 1, 1, 1, 1, 0)
        );
        assertThat(workService.teamIndicators(leaderA, 1).managers())
                .filteredOn(row -> KAM_A.equals(row.managerId()))
                .singleElement()
                .satisfies(row -> assertThat(row.stuck()).isEqualTo(2));
        assertThatThrownBy(() -> workService.teamIndicators(kamA, null)).isInstanceOf(WorkAccessDeniedException.class);
        assertThatThrownBy(() -> workService.teamIndicators(management, null)).isInstanceOf(WorkAccessDeniedException.class);
        assertThatThrownBy(() -> workService.teamIndicators(leaderA, 0))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
                    assertThat(exception.field()).isEqualTo("stuckDays");
                });
    }

    @Test
    void managementSeesReadOnlySummaryOfAllTeams() {
        OffsetDateTime now = OffsetDateTime.now();
        insertInteraction(UNIVERSITY_A, "Работа А", "Позвонить", now.minusDays(1), now.minusDays(40), "ACTIVE");
        insertInteraction(UNIVERSITY_B, "Работа Б", null, null, now.minusDays(1), "ACTIVE");
        insertLearning(UNIVERSITY_A, null, 6, 2, LocalDate.of(2026, 9, 1));
        insertLearning(UNIVERSITY_B, 7L, 4, 1, LocalDate.of(2026, 9, 1));
        insertLearning(UNIVERSITY_A, null, 100, 50, null);
        insertLearning(UNIVERSITY_C, 8L, 30, 3, null);
        insertLearning(UNIVERSITY_B, 9L, 4, 0, LocalDate.of(2026, 9, 1), "TEACHERS");
        UUID openTeam = UUID.randomUUID();
        UUID archivedTeam = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO teams (id, name, archived) VALUES (?, 'Открытый набор', FALSE), (?, 'Архивная команда', TRUE)",
                openTeam, archivedTeam);
        jdbcTemplate.update("""
                INSERT INTO organizations (id, name, type, team_id, owner_manager_id, version, updated_at)
                VALUES (?, 'Открытый набор (физлица)', 'OPEN_ENROLLMENT', ?, NULL, 0, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), openTeam);

        WorkModels.TeamsSummary summary = workService.teamsSummary(management, null);

        assertThat(summary.teams()).containsExactly(
                new WorkModels.TeamSummary(TEAM_A, "Команда А", 2, 1, 1, 1, 0, 1, 1, 6, 2),
                new WorkModels.TeamSummary(TEAM_B, "Команда Б", 1, 0, 1, 0, 1, 0, 1, 4, 1),
                new WorkModels.TeamSummary(openTeam, "Открытый набор", 0, 0, 0, 0, 0, 0, 0, 0, 0)
        );
        assertThat(summary.total()).isEqualTo(new WorkModels.TeamSummary(null, null, 3, 1, 2, 1, 1, 1, 2, 10, 3));
        assertThat(organizationRepository.findVisibleById(management, UNIVERSITY_B)).isPresent();
        assertThatThrownBy(() -> workService.teamsSummary(leaderA, null)).isInstanceOf(WorkAccessDeniedException.class);
        assertThatThrownBy(() -> workService.reminders(management)).isInstanceOf(WorkAccessDeniedException.class);
    }

    @Test
    void remindersListOwnOverdueAndUpcomingStepsLicensesAndTrainingCyclesAndKeepTheSetting() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID overdue = insertInteraction(UNIVERSITY_A, "Вчерашний шаг", "Отправить договор", now.minusDays(1), now, "ACTIVE");
        UUID upcoming = insertInteraction(UNIVERSITY_A, "Завтрашний шаг", "Встреча в вузе", now.plusDays(1), now, "ACTIVE");
        insertInteraction(UNIVERSITY_A, "Далёкий шаг", "Позже", now.plusDays(30), now, "ACTIVE");
        insertInteraction(UNIVERSITY_A, "Завершённый шаг", "Отправить", now.minusDays(1), now, "COMPLETED");
        insertInteraction(UNIVERSITY_B, "Чужой шаг", "Позвонить", now.minusDays(1), now, "ACTIVE");
        insertAgreement(upcoming, insertProduct("Защищённая связь"), now.getYear());
        insertAgreement(overdue, insertProduct("Аврора"), now.getYear() + 5);
        insertTraining(overdue, now.minusYears(3).plusDays(10));

        WorkModels.ReminderDigest digest = workService.reminders(kamA);

        assertThat(digest.enabled()).isTrue();
        assertThat(digest.overdueTotal()).isEqualTo(1);
        assertThat(digest.upcomingTotal()).isEqualTo(1);
        assertThat(digest.overdue()).extracting(WorkModels.ReminderStep::interactionId).containsExactly(overdue);
        assertThat(digest.upcoming()).extracting(WorkModels.ReminderStep::interactionId).containsExactly(upcoming);
        assertThat(digest.expiringLicenses()).singleElement().satisfies(license -> {
            assertThat(license.interactionId()).isEqualTo(upcoming);
            assertThat(license.productName()).isEqualTo("Защищённая связь");
            assertThat(license.contractNumber()).isEqualTo("Д-" + license.licenseExpiryYear() + "/017");
        });
        assertThat(digest.trainingCycles()).singleElement().satisfies(training -> {
            assertThat(training.interactionId()).isEqualTo(overdue);
            assertThat(training.stageName()).isEqualTo("Повышение квалификации");
            assertThat(training.nextCycleOn()).isEqualTo(training.trainedAt().atZoneSameInstant(WorkProperties.ZONE).toLocalDate().plusYears(3));
        });
        assertThat(workService.reminders(leaderA).overdueTotal()).isEqualTo(1);
        assertThat(workService.reminders(kamB).overdue()).extracting(WorkModels.ReminderStep::title).containsExactly("Чужой шаг");

        workService.saveReminderSettings(kamA, new WorkModels.ReminderSettings(false));
        assertThat(workService.reminders(kamA).enabled()).isFalse();
        workService.saveReminderSettings(kamA, new WorkModels.ReminderSettings(true));
        assertThat(workService.reminders(kamA).enabled()).isTrue();
        assertThat(workService.reminders(kamC).enabled()).isTrue();
        assertThatThrownBy(() -> workService.saveReminderSettings(kamA, new WorkModels.ReminderSettings(null)))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
                    assertThat(exception.field()).isEqualTo("enabled");
                });
        assertThatThrownBy(() -> workService.saveReminderSettings(management, new WorkModels.ReminderSettings(false)))
                .isInstanceOf(WorkAccessDeniedException.class);
    }

    @Test
    void remindersFollowTheUniversityLicenceAndTrainingAfterTheWorkIsCompleted() {
        OffsetDateTime now = OffsetDateTime.now();
        int year = now.atZoneSameInstant(WorkProperties.ZONE).getYear();
        insertOrganization(UNIVERSITY_D, "Университет Д", TEAM_A, KAM_A);
        UUID signed = insertInteraction(UNIVERSITY_A, "Лицензия подписана", null, null, now.minusYears(3), "COMPLETED");
        UUID renewal = insertInteraction(UNIVERSITY_A, "Продление лицензии", null, null, now, "COMPLETED");
        UUID expiring = insertProduct("Защищённая связь");
        UUID renewed = insertProduct("Аврора");
        insertAgreement(signed, expiring, year);
        insertAgreement(signed, renewed, year - 1);
        insertAgreement(renewal, renewed, year + 3);
        insertTraining(signed, now.minusYears(3).plusDays(10));
        UUID firstCycle = insertInteraction(UNIVERSITY_D, "Первый цикл", null, null, now.minusYears(4), "COMPLETED");
        UUID secondCycle = insertInteraction(UNIVERSITY_D, "Второй цикл", null, null, now.minusYears(1), "COMPLETED");
        insertTraining(firstCycle, now.minusYears(4));
        insertTraining(secondCycle, now.minusYears(1));

        WorkModels.ReminderDigest digest = workService.reminders(kamA);

        assertThat(digest.overdueTotal()).isZero();
        assertThat(digest.expiringLicenses()).singleElement().satisfies(license -> {
            assertThat(license.interactionId()).isEqualTo(signed);
            assertThat(license.productName()).isEqualTo("Защищённая связь");
            assertThat(license.licenseExpiryYear()).isEqualTo(year);
        });
        assertThat(digest.trainingCycles()).singleElement().satisfies(training -> {
            assertThat(training.interactionId()).isEqualTo(signed);
            assertThat(training.organizationId()).isEqualTo(UNIVERSITY_A);
        });
        assertThat(workService.reminders(kamB).expiringLicenses()).isEmpty();
        assertThat(workService.reminders(kamB).trainingCycles()).isEmpty();
    }

    @Test
    void changingTheOwnerEndsTheDeputyPeriodSoNobodyStandsInForAnotherManager() {
        LocalDate today = LocalDate.now(WorkProperties.ZONE);
        OrganizationDeputy active = organizationDeputyService.assign(
                leaderA, UNIVERSITY_A, new OrganizationDeputyRequest(KAM_C, today, today.plusDays(5)), "deputy-before-transfer"
        );

        organizationAssignmentService.assign(
                leaderA, UNIVERSITY_A, new OrganizationAssignmentRequest(0, KAM_C), "transfer-to-deputy", REQUEST_ID
        );

        assertThat(organizationDeputyService.list(leaderA, UNIVERSITY_A)).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(active.id());
            assertThat(row.status()).isEqualTo(OrganizationDeputy.Status.ENDED);
            assertThat(row.endedByProfileId()).isEqualTo(LEADER_A);
            assertThat(row.endedByDisplayName()).isEqualTo("Руководитель А");
        });
        assertThat(organizationRepository.findVisibleById(kamC, UNIVERSITY_A)).hasValueSatisfying(organization -> {
            assertThat(organization.ownerManagerId()).isEqualTo(KAM_C);
            assertThat(organization.deputyManagerName()).isNull();
        });
        assertThat(accessRevision(KAM_C)).isEqualTo(3);

        OrganizationDeputy scheduled = organizationDeputyService.assign(
                leaderA, UNIVERSITY_A, new OrganizationDeputyRequest(KAM_A, today.plusDays(2), today.plusDays(5)), "deputy-later"
        );
        organizationAssignmentService.bulkAssign(
                leaderA,
                new OrganizationBulkAssignmentRequest(List.of(new OrganizationBulkAssignmentRequest.Item(UNIVERSITY_A, 1, KAM_A))),
                "bulk-back",
                REQUEST_ID
        );

        assertThat(organizationDeputyService.list(leaderA, UNIVERSITY_A))
                .filteredOn(row -> row.id().equals(scheduled.id()))
                .singleElement()
                .satisfies(row -> assertThat(row.status()).isEqualTo(OrganizationDeputy.Status.ENDED));
        assertThat(ownerOf(UNIVERSITY_A)).isEqualTo(KAM_A);
    }

    private void assertRejected(OrganizationDeputyRequest request, UUID organizationId, String field) {
        assertThatThrownBy(() -> organizationDeputyService.assign(leaderA, organizationId, request, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
                    assertThat(exception.field()).isEqualTo(field);
                });
    }

    private UUID insertInteraction(
            UUID organizationId,
            String title,
            String nextAction,
            OffsetDateTime nextActionAt,
            OffsetDateTime stageEnteredAt,
            String status
    ) {
        UUID id = UUID.randomUUID();
        UUID stageId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO interactions (id, organization_id, title, current_stage_id, next_action, next_action_at, created_at, work_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, organizationId, title, stageId, nextAction, nextActionAt, stageEnteredAt, status);
        jdbcTemplate.update("""
                INSERT INTO interaction_events (id, interaction_id, type, to_stage_id, to_stage_name_snapshot, version, occurred_at)
                VALUES (?, ?, 'CREATED', ?, 'Поиск контакта', 0, ?)
                """, UUID.randomUUID(), id, stageId, stageEnteredAt);
        return id;
    }

    private UUID insertProduct(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO products (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private void insertAgreement(UUID interactionId, UUID productId, int licenseExpiryYear) {
        jdbcTemplate.update(
                "INSERT INTO product_agreements (id, interaction_id, product_id, contract_number, license_expiry_year) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), interactionId, productId, "Д-" + licenseExpiryYear + "/017", licenseExpiryYear
        );
    }

    private void insertTraining(UUID interactionId, OffsetDateTime occurredAt) {
        jdbcTemplate.update("""
                INSERT INTO interaction_events (id, interaction_id, type, to_stage_id, to_stage_name_snapshot, version, occurred_at)
                VALUES (?, ?, 'TRANSITIONED', ?, 'Повышение квалификации', 1, ?)
                """, UUID.randomUUID(), interactionId, UUID.randomUUID(), occurredAt);
    }

    private void insertLearning(UUID organizationId, Long groupId, int participants, int teachers, LocalDate runStartsOn) {
        insertLearning(organizationId, groupId, participants, teachers, runStartsOn, "STUDENTS");
    }

    private void insertLearning(
            UUID organizationId,
            Long groupId,
            int participants,
            int teachers,
            LocalDate runStartsOn,
            String runKind
    ) {
        UUID recordId = UUID.randomUUID();
        String externalId = "course:" + recordId;
        jdbcTemplate.update("INSERT INTO source_records (id, source, external_id) VALUES (?, 'MOODLE', ?)", recordId, externalId);
        jdbcTemplate.update(
                "INSERT INTO source_mappings (id, source, kind, external_key, run_starts_on, run_kind) VALUES (?, 'MOODLE', ?, ?, ?, ?)",
                UUID.randomUUID(), groupId == null ? "COURSE" : "GROUP", externalId, runStartsOn, runKind
        );
        jdbcTemplate.update("""
                INSERT INTO learning_snapshots (source_record_id, organization_id, group_id, participants_count, teachers_count)
                VALUES (?, ?, ?, ?, ?)
                """, recordId, organizationId, groupId, participants, teachers);
    }

    private void insertProfile(UUID id, String displayName, String role, UUID teamId, boolean active) {
        jdbcTemplate.update(
                "INSERT INTO crm_user_profiles (id, display_name, role, team_id, active) VALUES (?, ?, ?, ?, ?)",
                id, displayName, role, teamId, active
        );
    }

    private void insertOrganization(UUID id, String name, UUID teamId, UUID ownerManagerId) {
        jdbcTemplate.update("""
                INSERT INTO organizations (id, name, type, team_id, owner_manager_id, version, updated_at)
                VALUES (?, ?, 'UNIVERSITY', ?, ?, 0, CURRENT_TIMESTAMP)
                """, id, name, teamId, ownerManagerId);
    }

    private UUID ownerOf(UUID organizationId) {
        return jdbcTemplate.queryForObject("SELECT owner_manager_id FROM organizations WHERE id = ?", UUID.class, organizationId);
    }

    private int accessRevision(UUID profileId) {
        return jdbcTemplate.queryForObject("SELECT access_revision FROM crm_user_profiles WHERE id = ?", Integer.class, profileId);
    }

    private void createSchema() {
        List.of(
                "CREATE TABLE IF NOT EXISTS teams (id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL, archived BOOLEAN DEFAULT FALSE NOT NULL)",
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY, display_name VARCHAR(200) NOT NULL, role VARCHAR(16) NOT NULL, team_id UUID,
                    active BOOLEAN NOT NULL, access_revision INTEGER NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY, name VARCHAR(300) NOT NULL, type VARCHAR(16) NOT NULL, team_id UUID NOT NULL,
                    owner_manager_id UUID, version INTEGER NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, city VARCHAR(200), website VARCHAR(300), inn VARCHAR(12)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS contacts (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, name VARCHAR(200) NOT NULL,
                    confirmed_at TIMESTAMP WITH TIME ZONE, confirmed_by UUID
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organization_deputies (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, deputy_profile_id UUID NOT NULL,
                    deputy_display_name VARCHAR(200) NOT NULL, starts_on DATE NOT NULL, ends_on DATE NOT NULL,
                    starts_at TIMESTAMP WITH TIME ZONE NOT NULL, ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    command_id UUID NOT NULL, actor_profile_id UUID NOT NULL, actor_display_name VARCHAR(200) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, ended_at TIMESTAMP WITH TIME ZONE,
                    ended_by_profile_id UUID, ended_by_display_name VARCHAR(200)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS command_idempotency_records (
                    id UUID PRIMARY KEY, actor_profile_id UUID NOT NULL, operation VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL, request_fingerprint CHAR(64) NOT NULL,
                    result_json VARCHAR(20000), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (actor_profile_id, operation, idempotency_key)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organization_assignment_events (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, command_id UUID NOT NULL,
                    previous_owner_manager_id UUID, previous_owner_manager_display_name VARCHAR(200),
                    owner_manager_id UUID, new_owner_manager_display_name VARCHAR(200), actor_profile_id UUID NOT NULL,
                    actor_display_name VARCHAR(200) NOT NULL, request_id VARCHAR(64) NOT NULL, version INTEGER NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL, reason VARCHAR(32), handover_note VARCHAR(2000),
                    UNIQUE (command_id, organization_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interactions (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL,
                    current_stage_id UUID NOT NULL, next_action VARCHAR(500), next_action_at TIMESTAMP WITH TIME ZONE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, work_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_events (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, type VARCHAR(32) NOT NULL, to_stage_id UUID,
                    to_stage_name_snapshot VARCHAR(200), version INTEGER NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                "CREATE TABLE IF NOT EXISTS vendors (id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL)",
                "CREATE TABLE IF NOT EXISTS products (id UUID PRIMARY KEY, vendor_contact_id UUID, vendor_id UUID, name VARCHAR(200) NOT NULL)",
                """
                CREATE TABLE IF NOT EXISTS vendor_contacts (
                    id UUID PRIMARY KEY, vendor_id UUID NOT NULL, name VARCHAR(200) NOT NULL, phone VARCHAR(16),
                    email VARCHAR(320), prefers_email BOOLEAN DEFAULT FALSE NOT NULL,
                    prefers_telegram BOOLEAN DEFAULT FALSE NOT NULL, archived BOOLEAN DEFAULT FALSE NOT NULL,
                    personal_data_status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, external_key VARCHAR(200) UNIQUE,
                    version INTEGER DEFAULT 0 NOT NULL, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS product_agreements (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, product_id UUID NOT NULL, license_expiry_year INTEGER,
                    contract_number VARCHAR(200)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS learning_snapshots (
                    source_record_id UUID PRIMARY KEY, organization_id UUID NOT NULL, group_id BIGINT,
                    participants_count INTEGER NOT NULL, teachers_count INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS source_records (stream_no INTEGER, payload_hash CHAR(64), 
                    id UUID PRIMARY KEY, source VARCHAR(16) NOT NULL, external_id VARCHAR(200) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS source_mappings (
                    id UUID PRIMARY KEY, source VARCHAR(16) NOT NULL, kind VARCHAR(16) NOT NULL,
                    external_key VARCHAR(310) NOT NULL, run_starts_on DATE, run_kind VARCHAR(16) DEFAULT 'STUDENTS' NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS reminder_settings (
                    profile_id UUID PRIMARY KEY, enabled BOOLEAN NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """
        ).forEach(jdbcTemplate::execute);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @ConditionalOnMissingBean(ObjectMapper.class)
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        WorkProperties workProperties() {
            return new WorkProperties(30, 7, 1, List.of("Обучение преподавателей", "Повышение квалификации"), 3, 90, 50);
        }
    }
}
