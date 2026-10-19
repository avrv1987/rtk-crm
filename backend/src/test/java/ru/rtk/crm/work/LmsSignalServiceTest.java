package ru.rtk.crm.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.rtk.crm.access.ContactInteractionMutationAccessDeniedException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.Interaction;
import ru.rtk.crm.interaction.InteractionIssueKind;
import ru.rtk.crm.interaction.InteractionIssueRequest;
import ru.rtk.crm.interaction.InteractionIssueService;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.work.LmsSignalModels.LmsSignal;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalDismissalRequest;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalRiskRequest;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalType;
import ru.rtk.crm.work.LmsSignalModels.WorkLearning;
import ru.rtk.crm.work.LmsSignalRepository.Activity;
import ru.rtk.crm.work.LmsSignalRepository.SignalSource;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:lms_signals;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrganizationRepository.class, LmsSignalRepository.class, LmsSignalService.class, LmsWorkLearningService.class,
        LmsSignalServiceTest.TestBeans.class})
class LmsSignalServiceTest {
    private static final LmsSignalProperties PROPERTIES = LmsLearningTestBeans.PROPERTIES;
    private static final UUID TEAM_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID LEADER_A = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID KAM_A = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID KAM_B = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private static final UUID MANAGEMENT = UUID.fromString("00000000-0000-0000-0000-000000000015");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000016");
    private static final UUID UNIVERSITY_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID UNIVERSITY_B = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID PROGRAM = UUID.fromString("00000000-0000-0000-0000-000000000201");

    private final CrmProfile leaderA = new CrmProfile(LEADER_A, UserRole.LEADER, TEAM_A, 0);
    private final CrmProfile kamA = new CrmProfile(KAM_A, UserRole.USER, TEAM_A, 0);
    private final CrmProfile kamB = new CrmProfile(KAM_B, UserRole.USER, TEAM_B, 0);
    private final CrmProfile management = new CrmProfile(MANAGEMENT, UserRole.MANAGEMENT, null, 0);
    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);
    private final CrmProfile partner = new CrmProfile(UUID.randomUUID(), UserRole.PARTNER, null, 0);
    private final LocalDate today = LocalDate.now(WorkProperties.ZONE);

    @Autowired
    private LmsSignalService service;

    @Autowired
    private LmsWorkLearningService workLearningService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private InteractionIssueService issueService;

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of(
                "lms_signal_dismissals", "learning_snapshots", "source_mappings", "interaction_cycles",
                "interaction_stage_transitions", "interactions", "interaction_stages", "organization_deputies",
                "organizations", "crm_user_profiles"
        )) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        insertProfile(LEADER_A, "Руководитель А", "LEADER", TEAM_A);
        insertProfile(KAM_A, "КАМ А", "USER", TEAM_A);
        insertProfile(KAM_B, "КАМ Б", "USER", TEAM_B);
        insertProfile(MANAGEMENT, "Руководство", "MANAGEMENT", null);
        insertProfile(ADMIN, "Администратор", "ADMIN", null);
        jdbcTemplate.update("""
                INSERT INTO organizations (id, name, type, team_id, owner_manager_id, status)
                VALUES (?, 'Университет А', 'UNIVERSITY', ?, ?, 'ACTIVE'), (?, 'Университет Б', 'UNIVERSITY', ?, ?, 'ACTIVE')
                """, UNIVERSITY_A, TEAM_A, KAM_A, UNIVERSITY_B, TEAM_B, KAM_B);
    }

    @Test
    void studentsAppearedFollowsRunDatesAndStageOrder() {
        LocalDate start = today.minusDays(10);
        LocalDate end = today.plusDays(10);

        assertThat(rules(source(2, 10, start, end, today, 5, null), today))
                .containsExactly(LmsSignalType.STUDENTS_APPEARED);
        assertThat(rules(source(2, 10, start, end, start, 5, null), today))
                .as("observation on the first day of the run")
                .containsExactly(LmsSignalType.STUDENTS_APPEARED);
        assertThat(rules(source(2, 10, start, end, start.minusDays(1), 5, null), today))
                .as("observation before the run starts")
                .isEmpty();
        assertThat(rules(source(10, 10, start, end, today, 5, null), today))
                .as("already on the classes stage")
                .isEmpty();
        assertThat(rules(source(11, 10, start, end, today, 5, null), today))
                .as("already after the classes stage")
                .isEmpty();
        assertThat(rules(source(2, null, start, end, today, 5, null), today))
                .as("route without the classes stage")
                .isEmpty();
        assertThat(rules(source(2, 10, start, today.plusDays(1), today, 5, null), today))
                .as("last day of the run")
                .containsExactly(LmsSignalType.STUDENTS_APPEARED);
        assertThat(rules(source(2, 10, start, today, today.minusDays(1), 5, null), today))
                .as("run closed today: [start, end)")
                .isEmpty();
    }

    @Test
    void noStudentsNeedsMoreThanThresholdDaysInsideTheRun() {
        LocalDate start = today.minusDays(30);
        LocalDate end = today.plusDays(30);

        assertThat(rules(source(2, 10, start, end, start.plusDays(14), 0, null), today))
                .as("exactly 14 days is not more than 14")
                .isEmpty();
        assertThat(rules(source(2, 10, start, end, start.plusDays(15), 0, null), today))
                .containsExactly(LmsSignalType.NO_STUDENTS);
        assertThat(rules(source(2, 10, start, end, start.plusDays(15), 1, null), today))
                .containsExactly(LmsSignalType.STUDENTS_APPEARED);
        assertThat(rules(source(2, 10, start, today, today.minusDays(1), 0, null), today))
                .as("the run is over")
                .isEmpty();
        assertThat(rules(source(2, 10, today.plusDays(3), today.plusDays(60), today, 0, null), today))
                .as("observation before a future run")
                .isEmpty();
    }

    @Test
    void lowCompletionNeedsTrackingAndRunClosedOrEndingSoon() {
        LocalDate start = today.minusDays(60);

        assertThat(rules(source(12, 10, start, today.plusDays(7), today, 10, 4), today))
                .as("seven days before the end is inside the window")
                .containsExactly(LmsSignalType.LOW_COMPLETION);
        assertThat(rules(source(12, 10, start, today.plusDays(8), today, 10, 4), today))
                .as("eight days before the end is outside the window")
                .isEmpty();
        assertThat(rules(source(12, 10, start, today.minusDays(20), today.minusDays(21), 10, 4), today))
                .as("closed run keeps its last observation")
                .containsExactly(LmsSignalType.LOW_COMPLETION);
        assertThat(rules(source(12, 10, start, today.plusDays(3), today, 10, null), today))
                .as("completion tracking is off: no data is not a signal")
                .isEmpty();
        assertThat(rules(source(12, 10, start, today.plusDays(3), today, 10, 5), today))
                .as("exactly the threshold is not below it")
                .isEmpty();
        assertThat(rules(source(12, 10, start, today.plusDays(3), today, 0, 0), today))
                .as("no learners: no completion share, only the empty run signal")
                .containsExactly(LmsSignalType.NO_STUDENTS);
    }

    @Test
    void classesStageIsFoundByLmsRoleEvenWhenRenamed() {
        UUID work = insertWork(UNIVERSITY_A, "Переименованный маршрут", 3);
        insertRun(UNIVERSITY_A, today.minusDays(5), today.plusDays(30), 6, 3, today);
        // Этап занятий переименован, роль осталась: сигнал и переход к занятиям работают по роли.
        jdbcTemplate.update("UPDATE interaction_stages SET name = 'Лекции и практика' WHERE id = ?", stageId(work, 10));
        assertThat(service.list(kamA).items()).singleElement().satisfies(signal -> {
            assertThat(signal.type()).isEqualTo(LmsSignalType.STUDENTS_APPEARED);
            assertThat(signal.message()).contains("«Лекции и практика»");
        });
        // Этап с названием «Занятия», но без роли с LMS не связан: сравнивать текущий этап не с чем, сигнала нет.
        jdbcTemplate.update("UPDATE interaction_stages SET name = 'Занятия', lms_role = NULL WHERE id = ?", stageId(work, 10));
        assertThat(service.list(kamA).items()).isEmpty();
    }

    @Test
    void scopeFollowsRolesAndManagementOnlyReads() {
        UUID workA = insertWork(UNIVERSITY_A, "Работа А", 3);
        UUID workB = insertWork(UNIVERSITY_B, "Работа Б", 3);
        insertRun(UNIVERSITY_A, today.minusDays(5), today.plusDays(30), 6, 3, today);
        insertRun(UNIVERSITY_B, today.minusDays(5), today.plusDays(30), 4, null, today);

        assertThat(service.list(kamA).items()).extracting(LmsSignal::interactionId).containsExactly(workA);
        assertThat(service.list(kamB).items()).extracting(LmsSignal::interactionId).containsExactly(workB);
        assertThat(service.list(leaderA).items()).extracting(LmsSignal::ownerManagerName).containsExactly("КАМ А");
        assertThat(service.list(management).items()).extracting(LmsSignal::interactionId).containsExactlyInAnyOrder(workA, workB);
        assertThatThrownBy(() -> service.list(admin)).isInstanceOf(WorkAccessDeniedException.class);
        assertThatThrownBy(() -> service.forInteraction(kamA, workB)).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> service.forInteraction(admin, workA)).isInstanceOf(InteractionNotFoundException.class);
        assertThat(service.forInteraction(management, workA).items()).hasSize(1);

        LmsSignalDismissalRequest request = new LmsSignalDismissalRequest(LmsSignalType.STUDENTS_APPEARED, mappingOf(UNIVERSITY_A));
        assertThatThrownBy(() -> service.dismiss(management, workA, request))
                .isInstanceOf(ContactInteractionMutationAccessDeniedException.class);
        assertThatThrownBy(() -> service.dismiss(kamB, workA, request)).isInstanceOf(InteractionNotFoundException.class);
        assertThat(service.dismiss(leaderA, workA, request).items()).singleElement().extracting(LmsSignal::dismissed).isEqualTo(true);
    }

    @Test
    void transitionIsOfferedOnlyWhenTheRouteAllowsIt() {
        UUID work = insertWork(UNIVERSITY_A, "Работа А", 9);
        insertRun(UNIVERSITY_A, today.minusDays(5), today.plusDays(30), 6, 3, today);

        LmsSignal withoutRoute = service.forInteraction(kamA, work).items().getFirst();
        assertThat(withoutRoute.action()).isNull();
        assertThat(withoutRoute.actionHint()).contains("Прямого перехода из этапа «Этап 9» к этапу «Занятия»");

        jdbcTemplate.update("""
                INSERT INTO interaction_stage_transitions (interaction_id, from_stage_id, to_stage_id, comment_required)
                VALUES (?, ?, ?, FALSE)
                """, work, stageId(work, 9), stageId(work, 10));
        LmsSignal withRoute = service.forInteraction(kamA, work).items().getFirst();
        assertThat(withRoute.actionHint()).isNull();
        assertThat(withRoute.action().stageId()).isEqualTo(stageId(work, 10));
        assertThat(withRoute.action().stageName()).isEqualTo("Занятия");
        assertThat(withRoute.message()).contains("обучающихся: 6", "на этапе «Этап 9»");
    }

    @Test
    void dismissedSignalStaysHiddenUntilLmsDataChanges() {
        UUID work = insertWork(UNIVERSITY_A, "Работа А", 3);
        UUID mapping = insertRun(UNIVERSITY_A, today.minusDays(5), today.plusDays(30), 6, 3, today);
        LmsSignalDismissalRequest request = new LmsSignalDismissalRequest(LmsSignalType.STUDENTS_APPEARED, mapping);

        assertThat(service.dismiss(kamA, work, request).items()).singleElement().extracting(LmsSignal::dismissed).isEqualTo(true);
        assertThat(service.dismiss(kamA, work, request).items()).singleElement().extracting(LmsSignal::dismissed).isEqualTo(true);
        assertThat(service.list(kamA).items()).isEmpty();
        assertThat(service.list(leaderA).items()).isEmpty();

        jdbcTemplate.update("UPDATE learning_snapshots SET observed_at = ? WHERE mapping_id = ?", OffsetDateTime.now(), mapping);
        assertThat(service.list(kamA).items()).as("a new observation without changes keeps the signal hidden").isEmpty();

        jdbcTemplate.update("UPDATE learning_snapshots SET participants_count = 7, changed_at = ? WHERE mapping_id = ?",
                OffsetDateTime.now().plusSeconds(1), mapping);
        assertThat(service.list(kamA).items()).singleElement().extracting(LmsSignal::participants).isEqualTo(7);

        assertThatThrownBy(() -> service.dismiss(kamA, work, new LmsSignalDismissalRequest(LmsSignalType.NO_STUDENTS, mapping)))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.getMessage()).isNotBlank());
        assertThatThrownBy(() -> service.dismiss(kamA, work, new LmsSignalDismissalRequest(null, mapping)))
                .isInstanceOf(InteractionValidationException.class);
    }

    @Test
    void completedWorkAndTeacherRunsGiveNoSignals() {
        UUID work = insertWork(UNIVERSITY_A, "Работа А", 3);
        UUID mapping = insertRun(UNIVERSITY_A, today.minusDays(5), today.plusDays(30), 6, 3, today);
        jdbcTemplate.update("UPDATE source_mappings SET run_kind = 'TEACHERS' WHERE id = ?", mapping);
        assertThat(service.list(kamA).items()).isEmpty();

        jdbcTemplate.update("UPDATE source_mappings SET run_kind = 'STUDENTS' WHERE id = ?", mapping);
        jdbcTemplate.update("UPDATE interactions SET work_status = 'COMPLETED' WHERE id = ?", work);
        assertThat(service.list(kamA).items()).isEmpty();
    }

    @Test
    void activitySignalsFollowThresholdsGracePeriodAndOneCausePerSignal() {
        LocalDate start = today.minusDays(20);
        LocalDate end = today.plusDays(30);

        assertThat(rules(activitySource(start, end, 6, 1, new Activity(2, 4, 1, 2, 1, 1)), today))
                .as("2 of 6 active is below 40 %, 2 of 6 stopped is above 25 %")
                .containsExactly(LmsSignalType.LOW_ACTIVITY, LmsSignalType.DROPOUT);
        assertThat(rules(activitySource(start, end, 6, 1, new Activity(3, 5, 1, 1, 1, 1)), today))
                .as("3 of 6 active and 1 of 6 stopped are inside the thresholds")
                .isEmpty();
        assertThat(rules(activitySource(start, end, 6, 1, new Activity(0, 3, 4, 0, 0, 1)), today))
                .as("not started explains the zero activity")
                .containsExactly(LmsSignalType.NOT_STARTED);
        assertThat(rules(activitySource(start, end, 3, 1, new Activity(0, 2, 1, 0, 0, 0)), today))
                .as("nobody entered for 7 days, teachers silent for 14 days")
                .containsExactly(LmsSignalType.NO_ACTIVITY, LmsSignalType.TEACHER_INACTIVE);
        assertThat(rules(activitySource(start, end, 3, 0, new Activity(0, 2, 1, 0, 0, 0)), today))
                .as("no teacher with a course role: teacher silence is not a signal")
                .containsExactly(LmsSignalType.NO_ACTIVITY);
        assertThat(rules(activitySource(today.minusDays(6), end, 6, 1, new Activity(0, 0, 6, 0, 0, 0)), today))
                .as("first week of the run is a grace period")
                .isEmpty();
        assertThat(rules(activitySource(start, end, 6, 1, null), today))
                .as("Moodle did not return the last access: no data is not a signal")
                .isEmpty();
        assertThat(rules(activitySource(start.minusDays(40), today.minusDays(1), 6, 1, new Activity(0, 0, 6, 0, 0, 0),
                today.minusDays(2)), today))
                .as("closed run")
                .isEmpty();
    }

    @Test
    void activitySignalsAreNotGivenOnStaleObservationButNumericSignalsStay() {
        LocalDate start = today.minusDays(30);
        LocalDate end = today.plusDays(30);
        Activity stalled = new Activity(0, 0, 6, 0, 0, 0);
        OffsetDateTime noon = today.atTime(12, 0).atZone(WorkProperties.ZONE).toOffsetDateTime();

        assertThat(LmsSignalService.types(activitySource(start, end, 6, 1, stalled, today.minusDays(1)), noon, PROPERTIES))
                .as("observation 24 hours old is within 26 hours")
                .contains(LmsSignalType.NOT_STARTED, LmsSignalType.TEACHER_INACTIVE);
        assertThat(LmsSignalService.types(activitySource(start, end, 6, 1, stalled, today.minusDays(2)), noon, PROPERTIES))
                .as("observation two days old: no activity signals")
                .isEmpty();
        assertThat(LmsSignalService.types(activitySource(start, end, 6, 1, stalled, today.minusDays(1)), noon,
                new LmsSignalProperties(14, 50, 7, 7, 40, 50, 25, 14, Duration.ofHours(3), null)))
                .as("threshold is configurable")
                .isEmpty();
        assertThat(LmsSignalService.types(source(2, 10, start, end, today.minusDays(2), 5, null), noon, PROPERTIES))
                .as("numeric signal keeps working on the observation date")
                .containsExactly(LmsSignalType.STUDENTS_APPEARED);
    }

    @Test
    void dailyActivityDoesNotBringBackHiddenSignalsAndActivitySignalsHideForTheWeek() {
        UUID work = insertWork(UNIVERSITY_A, "Работа А", 3);
        UUID mapping = insertRun(UNIVERSITY_A, today.minusDays(20), today.plusDays(30), 6, 3, today);
        setActivity(mapping, 1, new Activity(1, 6, 0, 5, 1, 1));
        assertThat(service.list(kamA).items()).extracting(LmsSignal::type)
                .containsExactly(LmsSignalType.STUDENTS_APPEARED, LmsSignalType.LOW_ACTIVITY, LmsSignalType.DROPOUT);

        service.dismiss(kamA, work, new LmsSignalDismissalRequest(LmsSignalType.STUDENTS_APPEARED, mapping));
        service.dismiss(kamA, work, new LmsSignalDismissalRequest(LmsSignalType.LOW_ACTIVITY, mapping));
        jdbcTemplate.update("UPDATE learning_snapshots SET observed_at = ? WHERE mapping_id = ?", OffsetDateTime.now(), mapping);
        setActivity(mapping, 1, new Activity(2, 6, 0, 4, 1, 1));

        assertThat(service.list(kamA).items())
                .as("activity changed on the next run, counts and changed_at did not")
                .extracting(LmsSignal::type).containsExactly(LmsSignalType.DROPOUT);

        jdbcTemplate.update("UPDATE lms_signal_dismissals SET data_version = 'week:2000-01-03/0' WHERE signal_type = 'LOW_ACTIVITY'");
        assertThat(service.list(kamA).items()).as("an activity signal hidden last week is back")
                .extracting(LmsSignal::type).containsExactly(LmsSignalType.LOW_ACTIVITY, LmsSignalType.DROPOUT);
    }

    @Test
    void recordAsRiskCreatesARegistryEntryOnceAndHidesTheSignal() {
        UUID work = insertWork(UNIVERSITY_A, "Работа А", 10);
        UUID mapping = insertRun(UNIVERSITY_A, today.minusDays(20), today.plusDays(30), 6, 3, today);
        setActivity(mapping, 1, new Activity(4, 6, 0, 0, 0, 0));
        Interaction updated = mock(Interaction.class);
        when(issueService.create(eq(kamA), eq(work), any(), eq("risk-1"))).thenReturn(updated);
        LmsSignalRiskRequest request = new LmsSignalRiskRequest(5, LmsSignalType.TEACHER_INACTIVE, mapping);

        assertThat(service.recordRisk(kamA, work, request, "risk-1")).isSameAs(updated);

        ArgumentCaptor<InteractionIssueRequest> issue = ArgumentCaptor.forClass(InteractionIssueRequest.class);
        verify(issueService).create(eq(kamA), eq(work), issue.capture(), eq("risk-1"));
        assertThat(issue.getValue().kind()).isEqualTo(InteractionIssueKind.RISK);
        assertThat(issue.getValue().version()).isEqualTo(5);
        assertThat(issue.getValue().description()).startsWith("Сигнал LMS «Преподаватель не заходит в курс»: Поток «Демо-курс»");
        assertThat(service.forInteraction(kamA, work).items()).singleElement()
                .satisfies(signal -> {
                    assertThat(signal.dismissed()).isTrue();
                    assertThat(signal.recordedAsRisk()).isTrue();
                });
        clearInvocations(issueService);
        assertThatThrownBy(() -> service.recordRisk(kamA, work, request, "risk-2"))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.getMessage()).contains("уже записан"));
        assertThatThrownBy(() -> service.recordRisk(management, work, request, "risk-3"))
                .isInstanceOf(ContactInteractionMutationAccessDeniedException.class);
        assertThatThrownBy(() -> service.recordRisk(kamB, work, request, "risk-4")).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> service.recordRisk(admin, work, request, "risk-5")).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> service.recordRisk(partner, work, request, "risk-6")).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> service.recordRisk(kamA, work,
                new LmsSignalRiskRequest(6, LmsSignalType.NO_STUDENTS, mapping), "risk-7"))
                .isInstanceOf(InteractionValidationException.class);
        verify(issueService, never()).create(any(), any(), any(), any());
    }

    @Test
    void staleObservationHidesActivityInSignalsAndInControl() {
        UUID work = insertWork(UNIVERSITY_A, "На занятиях", 10);
        UUID run = insertRun(UNIVERSITY_A, today.minusDays(20), today.plusDays(30), 3, null, today.minusDays(3));
        setActivity(run, 1, new Activity(0, 2, 1, 0, 0, 0));

        assertThat(service.list(kamA).items()).as("course not read for three days").isEmpty();
        WorkLearning stale = workLearningService.byWork(kamA, List.of(work)).get(work);
        assertThat(stale.active7d()).isNull();
        assertThat(stale.signals()).isZero();
        assertThat(stale.mismatch()).isNull();

        jdbcTemplate.update("UPDATE learning_snapshots SET observed_at = ? WHERE mapping_id = ?", OffsetDateTime.now(), run);

        assertThat(service.list(kamA).items()).extracting(LmsSignal::type)
                .containsExactly(LmsSignalType.NO_ACTIVITY, LmsSignalType.TEACHER_INACTIVE);
        assertThat(workLearningService.byWork(kamA, List.of(work)).get(work).mismatch()).contains("никто не заходил");
    }

    // Сигналы по числам по старым данным остаются, но несут признак «данные устарели» — и в списке, и в тексте риска.
    @Test
    void numericSignalOnStaleObservationIsMarkedStale() {
        UUID work = insertWork(UNIVERSITY_A, "Раньше занятий", 3);
        UUID run = insertRun(UNIVERSITY_A, today.minusDays(20), today.plusDays(30), 6, null, today.minusDays(3));

        assertThat(service.list(kamA).items()).singleElement().satisfies(signal -> {
            assertThat(signal.type()).isEqualTo(LmsSignalType.STUDENTS_APPEARED);
            assertThat(signal.stale()).isTrue();
        });
        when(issueService.create(eq(kamA), eq(work), any(), eq("stale-risk"))).thenReturn(mock(Interaction.class));
        service.recordRisk(kamA, work, new LmsSignalRiskRequest(1, LmsSignalType.STUDENTS_APPEARED, run), "stale-risk");
        ArgumentCaptor<InteractionIssueRequest> issue = ArgumentCaptor.forClass(InteractionIssueRequest.class);
        verify(issueService).create(eq(kamA), eq(work), issue.capture(), eq("stale-risk"));
        assertThat(issue.getValue().description()).startsWith("Сигнал LMS «В LMS появились обучающиеся» (данные устарели): ");

        jdbcTemplate.update("DELETE FROM lms_signal_dismissals");
        jdbcTemplate.update("UPDATE learning_snapshots SET observed_at = ? WHERE mapping_id = ?", OffsetDateTime.now(), run);
        assertThat(service.forInteraction(kamA, work).items()).singleElement()
                .satisfies(signal -> assertThat(signal.stale()).isFalse());
    }

    @Test
    void workLearningSummarisesRunsSignalsAndStageMismatchInsideTheScope() {
        UUID early = insertWork(UNIVERSITY_A, "Раньше занятий", 3);
        UUID classes = insertWork(UNIVERSITY_B, "На занятиях", 10);
        UUID earlyRun = insertRun(UNIVERSITY_A, today.minusDays(20), today.plusDays(30), 6, 3, today);
        setActivity(earlyRun, 1, new Activity(4, 6, 0, 0, 1, 1));
        UUID classesRun = insertRun(UNIVERSITY_B, today.minusDays(20), today.plusDays(30), 3, null, today);
        setActivity(classesRun, 1, new Activity(0, 2, 1, 0, 0, 0));

        WorkLearning earlyLearning = workLearningService.byWork(management, List.of(early, classes)).get(early);
        assertThat(earlyLearning.runs()).isEqualTo(1);
        assertThat(earlyLearning.participants()).isEqualTo(6);
        assertThat(earlyLearning.active7d()).isEqualTo(4);
        assertThat(earlyLearning.signalTitles()).containsExactly("В LMS появились обучающиеся");
        assertThat(earlyLearning.mismatch()).isEqualTo("Этап «Этап 3» раньше этапа «Занятия», а в LMS идёт обучение: обучающихся 6");
        assertThat(earlyLearning.summary()).isEqualTo(
                "потоков идёт 1, обучающихся 6, заходили за 7 дней 4; сигналы: В LMS появились обучающиеся");

        WorkLearning classesLearning = workLearningService.byWork(management, List.of(classes)).get(classes);
        assertThat(classesLearning.signalTitles()).containsExactly("Поток идёт, активности нет", "Преподаватель не заходит в курс");
        assertThat(classesLearning.mismatch()).isEqualTo("Этап «Занятия», а за 7 дней в курс LMS никто не заходил");

        service.dismiss(kamA, early, new LmsSignalDismissalRequest(LmsSignalType.STUDENTS_APPEARED, earlyRun));
        WorkLearning dismissed = workLearningService.byWork(leaderA, List.of(early, classes)).get(early);
        assertThat(dismissed.signals()).as("hidden signals are not counted").isZero();
        assertThat(dismissed.mismatch()).as("hiding a signal does not settle the stage check").isNotNull();

        assertThat(workLearningService.byWork(leaderA, List.of(early, classes))).containsOnlyKeys(early);
        assertThat(workLearningService.byWork(kamB, List.of(early, classes))).containsOnlyKeys(classes);
        assertThat(workLearningService.byWork(admin, List.of(early, classes))).isEmpty();
        assertThat(workLearningService.byWork(partner, List.of(early, classes))).isEmpty();
    }

    // Низкая доля завершивших — только при достаточном покрытии статусов (по умолчанию 70 %) и среди известных; при малом
    // покрытии — отдельный сигнал «данных недостаточно, нужна проверка». Пример C=16, N=4, U=80.
    @Test
    void lowCompletionNeedsEnoughKnownStatusesOtherwiseItAsksForACheck() {
        LocalDate start = today.minusDays(60);
        LocalDate end = today.minusDays(1);

        assertThat(rules(coverageSource(start, end, 100, 16, 4), today))
                .as("16 of 20 known, data for 20 of 100: not enough to judge")
                .containsExactly(LmsSignalType.COMPLETION_UNVERIFIED);
        assertThat(rules(coverageSource(start, end, 100, 30, 40), today))
                .as("exactly 70 % known is enough; 30 of 70 is below 50 %")
                .containsExactly(LmsSignalType.LOW_COMPLETION);
        assertThat(rules(coverageSource(start, end, 100, 40, 35), today))
                .as("40 of 75 known is above the threshold even though it is 40 of all 100")
                .isEmpty();
        assertThat(rules(coverageSource(start, end, 100, 30, 39), today))
                .as("69 % known is not enough")
                .containsExactly(LmsSignalType.COMPLETION_UNVERIFIED);
        assertThat(LmsSignalService.title(LmsSignalType.COMPLETION_UNVERIFIED))
                .isEqualTo("Данных о завершении недостаточно, нужна проверка");
        assertThat(LmsSignalType.COMPLETION_UNVERIFIED.activity()).isFalse();
        LmsSignalProperties strict = new LmsSignalProperties(14, 50, 7, 7, 40, 50, 25, 14, null, 10);
        assertThat(LmsSignalService.types(coverageSource(start, end, 100, 4, 16),
                today.atTime(12, 0).atZone(WorkProperties.ZONE).toOffsetDateTime(), strict))
                .as("the coverage threshold is configurable: 20 known of 100 is enough at 10 %")
                .containsExactly(LmsSignalType.LOW_COMPLETION);
    }

    private static SignalSource coverageSource(LocalDate runStartsOn, LocalDate runEndsOn, int participants, int completed,
                                               int notCompleted) {
        OffsetDateTime observedAt = runEndsOn.minusDays(1).atTime(12, 0).atZone(WorkProperties.ZONE).toOffsetDateTime();
        return new SignalSource(
                UUID.randomUUID(), "Работа", UUID.randomUUID(), "Вуз", null, null, "Занятия", 10,
                UUID.randomUUID(), "Занятия", 10, false, false, UUID.randomUUID(), 0, "Курс", null,
                runStartsOn, runEndsOn, participants, completed, notCompleted, observedAt, observedAt, 0, null
        );
    }

    private SignalSource activitySource(LocalDate runStartsOn, LocalDate runEndsOn, int participants, int teachers,
                                        Activity activity) {
        return activitySource(runStartsOn, runEndsOn, participants, teachers, activity, today);
    }

    private static SignalSource activitySource(LocalDate runStartsOn, LocalDate runEndsOn, int participants, int teachers,
                                               Activity activity, LocalDate observedOn) {
        OffsetDateTime observedAt = observedOn.atTime(12, 0).atZone(WorkProperties.ZONE).toOffsetDateTime();
        return new SignalSource(
                UUID.randomUUID(), "Работа", UUID.randomUUID(), "Вуз", null, null, "Занятия", 10,
                UUID.randomUUID(), "Занятия", 10, false, false, UUID.randomUUID(), 0, "Курс", null,
                runStartsOn, runEndsOn, participants, null, null, observedAt, observedAt, teachers, activity
        );
    }

    private void setActivity(UUID mappingId, int teachers, Activity activity) {
        jdbcTemplate.update("""
                UPDATE learning_snapshots SET teachers_count = ?, active_7d_count = ?, active_30d_count = ?,
                    never_accessed_count = ?, inactive_count = ?, teachers_active_7d_count = ?, teachers_active_14d_count = ?
                WHERE mapping_id = ?
                """, teachers, activity.active7d(), activity.active30d(), activity.neverAccessed(), activity.inactive(),
                activity.teachersActive7d(), activity.teachersActive14d(), mappingId);
    }

    private static SignalSource source(
            int currentStageOrder,
            Integer classesStageOrder,
            LocalDate runStartsOn,
            LocalDate runEndsOn,
            LocalDate observedOn,
            int participants,
            Integer completed
    ) {
        OffsetDateTime observedAt = observedOn.atTime(12, 0).atZone(WorkProperties.ZONE).toOffsetDateTime();
        return new SignalSource(
                UUID.randomUUID(), "Работа", UUID.randomUUID(), "Вуз", null, null, "Этап", currentStageOrder,
                UUID.randomUUID(), "Занятия", classesStageOrder, false, false, UUID.randomUUID(), 0, "Курс", null,
                runStartsOn, runEndsOn, participants, completed, completed == null ? null : participants - completed, observedAt, observedAt, 0, null
        );
    }

    private UUID insertWork(UUID organizationId, String title, int currentStageOrder) {
        UUID id = UUID.randomUUID();
        for (int order = 0; order <= 12; order++) {
            jdbcTemplate.update("INSERT INTO interaction_stages (id, interaction_id, stage_order, name) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), id, order, order == 10 ? "Занятия" : "Этап " + order);
        }
        ru.rtk.crm.interaction.StagePropertiesTestSchema.markLegacyStages(jdbcTemplate);
        jdbcTemplate.update("""
                INSERT INTO interactions (id, organization_id, title, current_stage_id, program_id, work_status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """, id, organizationId, title, stageId(id, currentStageOrder), PROGRAM);
        return id;
    }

    private UUID insertRun(UUID organizationId, LocalDate startsOn, LocalDate endsOn, int participants, Integer completed,
                           LocalDate observedOn) {
        UUID mappingId = UUID.randomUUID();
        OffsetDateTime observedAt = observedOn.atTime(9, 0).atZone(WorkProperties.ZONE).toOffsetDateTime();
        jdbcTemplate.update("INSERT INTO source_mappings (id, version, run_starts_on, run_ends_on, run_kind) VALUES (?, 0, ?, ?, 'STUDENTS')",
                mappingId, startsOn, endsOn);
        jdbcTemplate.update("""
                INSERT INTO learning_snapshots (mapping_id, organization_id, program_id, course_name, group_name,
                    participants_count, completed_count, not_completed_count, observed_at, changed_at)
                VALUES (?, ?, ?, 'Демо-курс', NULL, ?, ?, ?, ?, ?)
                """, mappingId, organizationId, PROGRAM, participants, completed, completed == null ? null : participants - completed,
                observedAt, observedAt);
        return mappingId;
    }

    private UUID mappingOf(UUID organizationId) {
        return jdbcTemplate.queryForObject("SELECT mapping_id FROM learning_snapshots WHERE organization_id = ?", UUID.class,
                organizationId);
    }

    private UUID stageId(UUID interactionId, int order) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM interaction_stages WHERE interaction_id = ? AND stage_order = ?", UUID.class, interactionId, order
        );
    }

    private void insertProfile(UUID id, String displayName, String role, UUID teamId) {
        jdbcTemplate.update("INSERT INTO crm_user_profiles (id, display_name, role, team_id, active) VALUES (?, ?, ?, ?, TRUE)",
                id, displayName, role, teamId);
    }

    private void createSchema() {
        ru.rtk.crm.interaction.StagePropertiesTestSchema.create(jdbcTemplate);
        List.of(
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (role_synced_at TIMESTAMP WITH TIME ZONE, last_login_at TIMESTAMP WITH TIME ZONE, role_reconcile_required BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY, display_name VARCHAR(200) NOT NULL, role VARCHAR(16) NOT NULL, team_id UUID,
                    active BOOLEAN NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY, name VARCHAR(300) NOT NULL, type VARCHAR(16) NOT NULL, team_id UUID NOT NULL,
                    owner_manager_id UUID, status VARCHAR(16) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organization_deputies (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, deputy_profile_id UUID NOT NULL,
                    starts_at TIMESTAMP WITH TIME ZONE NOT NULL, ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    ended_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_issues (last_comment VARCHAR(1000), last_comment_by UUID, last_comment_at TIMESTAMP WITH TIME ZONE,
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, kind VARCHAR(16) NOT NULL,
                    description VARCHAR(1000) NOT NULL, risk_level VARCHAR(16), responsible_profile_id UUID NOT NULL, due_on DATE,
                    status VARCHAR(16) DEFAULT 'OPEN' NOT NULL, resolution VARCHAR(1000), created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, resolved_by UUID, resolved_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interactions (interaction_kind_id UUID, workflow_template_id UUID, workflow_version INTEGER, 
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL,
                    current_stage_id UUID NOT NULL, program_id UUID, work_status VARCHAR(16) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_stages (requires_agreement BOOLEAN DEFAULT FALSE NOT NULL, lms_role VARCHAR(32), template_stage_id UUID, 
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, stage_order INTEGER NOT NULL, name VARCHAR(200) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_stage_transitions (
                    interaction_id UUID NOT NULL, from_stage_id UUID NOT NULL, to_stage_id UUID NOT NULL,
                    comment_required BOOLEAN NOT NULL, PRIMARY KEY (interaction_id, from_stage_id, to_stage_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_cycles (
                    interaction_id UUID PRIMARY KEY, previous_interaction_id UUID NOT NULL, starts_on DATE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS source_mappings (
                    id UUID PRIMARY KEY, version INTEGER NOT NULL, run_starts_on DATE, run_ends_on DATE,
                    run_kind VARCHAR(16) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS learning_snapshots (
                    mapping_id UUID PRIMARY KEY, organization_id UUID NOT NULL, program_id UUID NOT NULL,
                    course_name VARCHAR(1333) NOT NULL, group_name VARCHAR(300), participants_count INTEGER NOT NULL,
                    completed_count INTEGER, not_completed_count INTEGER, observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    teachers_count INTEGER DEFAULT 0 NOT NULL, active_7d_count INTEGER, active_30d_count INTEGER, never_accessed_count INTEGER,
                    inactive_count INTEGER, teachers_active_7d_count INTEGER, teachers_active_14d_count INTEGER
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS lms_signal_dismissals (
                    interaction_id UUID NOT NULL, mapping_id UUID NOT NULL, signal_type VARCHAR(32) NOT NULL,
                    data_version VARCHAR(80) NOT NULL, dismissed_by UUID NOT NULL,
                    dismissed_at TIMESTAMP WITH TIME ZONE NOT NULL, recorded_as_risk BOOLEAN DEFAULT FALSE NOT NULL,
                    PRIMARY KEY (interaction_id, mapping_id, signal_type)
                )
                """
        ).forEach(jdbcTemplate::execute);
    }

    private static List<LmsSignalType> rules(SignalSource source, LocalDate today) {
        return LmsSignalService.types(source, today.atTime(12, 0).atZone(WorkProperties.ZONE).toOffsetDateTime(), PROPERTIES);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        LmsSignalProperties lmsSignalProperties() {
            return PROPERTIES;
        }
    }
}
