package ru.rtk.crm.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.rtk.crm.access.ContactInteractionMutationAccessDeniedException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.work.LmsSignalModels.LmsSignal;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalDismissalRequest;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalType;
import ru.rtk.crm.work.LmsSignalRepository.SignalSource;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:lms_signals;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrganizationRepository.class, LmsSignalRepository.class, LmsSignalService.class, LmsSignalServiceTest.TestBeans.class})
class LmsSignalServiceTest {
    private static final LmsSignalProperties PROPERTIES = new LmsSignalProperties("Занятия", 14, 50, 7);
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
    private final LocalDate today = LocalDate.now(WorkProperties.ZONE);

    @Autowired
    private LmsSignalService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
                runStartsOn, runEndsOn, participants, completed, observedAt, observedAt
        );
    }

    private UUID insertWork(UUID organizationId, String title, int currentStageOrder) {
        UUID id = UUID.randomUUID();
        for (int order = 0; order <= 12; order++) {
            jdbcTemplate.update("INSERT INTO interaction_stages (id, interaction_id, stage_order, name) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), id, order, order == 10 ? "Занятия" : "Этап " + order);
        }
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
                    participants_count, completed_count, observed_at, changed_at)
                VALUES (?, ?, ?, 'Демо-курс', NULL, ?, ?, ?, ?)
                """, mappingId, organizationId, PROGRAM, participants, completed, observedAt, observedAt);
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
        List.of(
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (
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
                CREATE TABLE IF NOT EXISTS interaction_issues (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, kind VARCHAR(16) NOT NULL,
                    description VARCHAR(1000) NOT NULL, risk_level VARCHAR(16), responsible_profile_id UUID NOT NULL, due_on DATE,
                    status VARCHAR(16) DEFAULT 'OPEN' NOT NULL, resolution VARCHAR(1000), created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, resolved_by UUID, resolved_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interactions (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL,
                    current_stage_id UUID NOT NULL, program_id UUID, work_status VARCHAR(16) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_stages (
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
                    completed_count INTEGER, observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    changed_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS lms_signal_dismissals (
                    interaction_id UUID NOT NULL, mapping_id UUID NOT NULL, signal_type VARCHAR(32) NOT NULL,
                    data_version VARCHAR(80) NOT NULL, dismissed_by UUID NOT NULL,
                    dismissed_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (interaction_id, mapping_id, signal_type)
                )
                """
        ).forEach(jdbcTemplate::execute);
    }

    private static List<LmsSignalType> rules(SignalSource source, LocalDate today) {
        return LmsSignalService.types(source, today, PROPERTIES);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        LmsSignalProperties lmsSignalProperties() {
            return PROPERTIES;
        }
    }
}
