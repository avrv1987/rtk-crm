package ru.rtk.crm.report;

import ru.rtk.crm.module.ModuleProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static ru.rtk.crm.report.ReportTestData.ADMIN_PROFILE;
import static ru.rtk.crm.report.ReportTestData.INTERACTION_FOREIGN;
import static ru.rtk.crm.report.ReportTestData.INTERACTION_TWO_PRODUCTS;
import static ru.rtk.crm.report.ReportTestData.INTERACTION_UNASSIGNED;
import static ru.rtk.crm.report.ReportTestData.INTERACTION_WITHOUT_LINKS;
import static ru.rtk.crm.report.ReportTestData.LEADER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A2;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.ORGANIZATION_B;
import static ru.rtk.crm.report.ReportTestData.PRODUCT_Y;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationAssignmentRepository;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.interaction.InteractionIssueKind;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.work.WorkAccessDeniedException;
import ru.rtk.crm.work.WorkControlModels.Control;
import ru.rtk.crm.work.WorkControlModels.ManagerBlock;
import ru.rtk.crm.work.WorkControlModels.TeamBlock;
import ru.rtk.crm.work.WorkControlModels.WorkItem;
import ru.rtk.crm.work.WorkControlService;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:manager_work;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        OrganizationAssignmentRepository.class,
        ReportRepository.class,
        ReportService.class,
        ru.rtk.crm.work.LmsLearningTestBeans.class,
        WorkControlService.class,
        ru.rtk.crm.work.WorkRepository.class,
        ru.rtk.crm.agreement.AgreementRepository.class,
        ru.rtk.crm.agreement.AgreementApproval.class,
        ru.rtk.crm.agreement.AgreementStages.class,
        ManagerWorkControlTest.PropertiesConfiguration.class
})
class ManagerWorkControlTest {
    private static final LocalDate SEPTEMBER_FIRST = LocalDate.parse("2026-09-01");
    private static final LocalDate SEPTEMBER_LAST = LocalDate.parse("2026-09-30");

    @Autowired
    private WorkControlService workControlService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ru.rtk.crm.work.WorkRepository workRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @BeforeEach
    void setUp() {
        ReportTestData.createSchema(jdbcTemplate);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agreements (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, number VARCHAR(100) NOT NULL, status VARCHAR(16) NOT NULL,
                    status_changed_at TIMESTAMP WITH TIME ZONE NOT NULL, valid_until DATE, sent_on DATE
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agreement_status_events (
                    id UUID PRIMARY KEY, agreement_id UUID NOT NULL, from_status VARCHAR(16), to_status VARCHAR(16) NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agreement_interactions (
                    agreement_id UUID NOT NULL, interaction_id UUID NOT NULL, PRIMARY KEY (agreement_id, interaction_id)
                )
                """);
        jdbcTemplate.update("DELETE FROM agreement_interactions");
        jdbcTemplate.update("DELETE FROM agreement_status_events");
        jdbcTemplate.update("DELETE FROM agreements");
        ReportTestData.insertScenario(jdbcTemplate);
    }

    @Test
    void workShowsTheStatusOfItsLatestLinkedContract() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO agreements (id, organization_id, number, status, status_changed_at, valid_until) VALUES
                    (?, ?, 'Д-1', 'ACTIVE', TIMESTAMP WITH TIME ZONE '2026-09-01 10:00:00+03:00', NULL),
                    (?, ?, 'Д-2', 'REVIEW', TIMESTAMP WITH TIME ZONE '2026-09-10 10:00:00+03:00', DATE '2027-09-10')
                """, older, UUID.randomUUID(), newer, UUID.randomUUID());
        jdbcTemplate.update("INSERT INTO agreement_interactions (agreement_id, interaction_id) VALUES (?, ?), (?, ?)",
                older, INTERACTION_TWO_PRODUCTS, newer, INTERACTION_TWO_PRODUCTS);

        List<WorkItem> works = workControlService.control(LEADER_A_PROFILE, filters(), null, null).teams().getFirst().managers().stream()
                .flatMap(manager -> manager.works().stream())
                .toList();

        assertThat(works).filteredOn(work -> work.interactionId().equals(INTERACTION_TWO_PRODUCTS)).singleElement()
                .satisfies(work -> {
                    assertThat(work.agreementStatus()).isEqualTo(ru.rtk.crm.agreement.AgreementModels.AgreementStatus.REVIEW);
                    assertThat(work.agreementNumber()).isEqualTo("Д-2");
                    assertThat(work.agreementValidUntil()).isEqualTo(java.time.LocalDate.parse("2027-09-10"));
                });
        assertThat(works).filteredOn(work -> work.interactionId().equals(INTERACTION_WITHOUT_LINKS)).singleElement()
                .satisfies(work -> assertThat(work.agreementStatus()).isNull());
    }

    @Test
    void contractStageUnderApprovalIsNotStuckAndShowsTheApprovalColorOnScreenAndInExport() {
        UUID review = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO agreements (id, organization_id, number, status, status_changed_at, sent_on)
                VALUES (?, ?, 'Д-9', 'REVIEW', CURRENT_TIMESTAMP, ?)
                """, review, ReportTestData.ORGANIZATION_A_UNASSIGNED, ReportTestData.TODAY.minusDays(400));
        jdbcTemplate.update("INSERT INTO agreement_interactions (agreement_id, interaction_id) VALUES (?, ?)", review, INTERACTION_UNASSIGNED);
        jdbcTemplate.update("""
                INSERT INTO agreement_status_events (id, agreement_id, from_status, to_status, occurred_at)
                VALUES (?, ?, 'DRAFT', 'REVIEW', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), review);
        assertThat(titles(tile(ManagerWorkTile.STUCK, 1))).hasSize(3);

        jdbcTemplate.update("UPDATE interaction_stages SET name = 'Обмен документами' WHERE id = (SELECT current_stage_id FROM interactions WHERE id = ?)",
                INTERACTION_UNASSIGNED);

        WorkItem item = workControlService.control(LEADER_A_PROFILE, filters(), null, null).teams().getFirst().managers().stream()
                .flatMap(manager -> manager.works().stream())
                .filter(work -> work.interactionId().equals(INTERACTION_UNASSIGNED))
                .findFirst().orElseThrow();
        assertThat(item.stageUnderApproval()).isTrue();
        assertThat(item.agreementApproval().round()).isEqualTo(1);
        assertThat(item.agreementApproval().color()).isEqualTo(ru.rtk.crm.agreement.AgreementModels.ApprovalColor.RED);
        assertThat(titles(tile(ManagerWorkTile.STUCK, 1))).hasSize(2).doesNotContain("Запрос на обучение");
        assertThat(reportService.preview(LEADER_A_PROFILE, request(null, null, ReportColumn.INTERACTION, ReportColumn.AGREEMENT_STATUS),
                0, 50).items())
                .filteredOn(row -> "Запрос на обучение".equals(row.get("INTERACTION"))).singleElement()
                .satisfies(row -> assertThat(row.get("AGREEMENT_STATUS")).isEqualTo("На согласовании · согласование: красный"));
    }

    @Test
    void leaderSeesOwnTeamByManagerWithRoutePositionDueStateAndIdleManager() {
        Control control = workControlService.control(LEADER_A_PROFILE, filters(), null, null);

        assertThat(control.byTeams()).isFalse();
        TeamBlock team = control.teams().getFirst();
        assertThat(control.teams()).hasSize(1);
        assertThat(team.teamName()).isEqualTo("Команда А");
        assertThat(team.managers()).extracting(ManagerBlock::managerName, block -> block.works().size())
                .containsExactly(
                        tuple("Анна Кузнецова", 2),
                        tuple("Борис Смирнов", 0),
                        tuple(null, 1)
                );
        List<WorkItem> annaWorks = team.managers().getFirst().works();
        assertThat(annaWorks).extracting(WorkItem::interactionId)
                .containsExactlyInAnyOrder(INTERACTION_TWO_PRODUCTS, INTERACTION_WITHOUT_LINKS);
        WorkItem agreement = annaWorks.stream().filter(work -> work.interactionId().equals(INTERACTION_TWO_PRODUCTS)).findFirst().orElseThrow();
        assertThat(agreement.stage()).isEqualTo("Встреча");
        assertThat(agreement.stagePosition()).isEqualTo(3);
        assertThat(agreement.stageTotal()).isEqualTo(3);
        assertThat(agreement.due()).isEqualTo(ManagerWorkDue.OVERDUE);
        assertThat(agreement.products()).containsExactly("Продукт Игрек", "Продукт Икс");
        assertThat(agreement.program()).isEqualTo("Java-разработчик");
        WorkItem first = annaWorks.stream().filter(work -> work.interactionId().equals(INTERACTION_WITHOUT_LINKS)).findFirst().orElseThrow();
        assertThat(first.due()).isEqualTo(ManagerWorkDue.NO_NEXT_STEP);
        assertThat(first.stagePosition()).isEqualTo(1);
        assertThat(control.total().works()).isEqualTo(3);
        assertThat(control.total().organizations()).isEqualTo(2);
        assertThat(control.total().overdue()).isEqualTo(1);
        assertThat(control.total().noNextStep()).isEqualTo(2);
    }

    @Test
    void openIssuesMarkTheWork() {
        jdbcTemplate.update("""
                INSERT INTO interaction_issues (id, interaction_id, kind, description, risk_level, responsible_profile_id, status,
                    created_by, created_at)
                VALUES (?, ?, ?, 'Вуз не отвечает', NULL, ?, 'OPEN', ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), INTERACTION_WITHOUT_LINKS, InteractionIssueKind.PROBLEM.name(), MANAGER_A, MANAGER_A);

        Control control = workControlService.control(LEADER_A_PROFILE, filters(), null, null);

        assertThat(control.total().withIssues()).isEqualTo(1);
        assertThat(control.teams().getFirst().managers().getFirst().works())
                .filteredOn(work -> work.problems() > 0)
                .singleElement()
                .satisfies(work -> {
                    assertThat(work.interactionId()).isEqualTo(INTERACTION_WITHOUT_LINKS);
                    assertThat(work.issues()).isEqualTo("Вуз не отвечает");
                });
    }

    @Test
    void missingDocumentsOfTheCurrentStageAreASignalInControl() {
        UUID stage = jdbcTemplate.queryForObject("SELECT current_stage_id FROM interactions WHERE id = ?", UUID.class, INTERACTION_WITHOUT_LINKS);
        jdbcTemplate.update("INSERT INTO interaction_stage_documents (interaction_id, stage_id, kind_code) VALUES (?, ?, 'CONTRACT'), (?, ?, 'ACT')",
                INTERACTION_WITHOUT_LINKS, stage, INTERACTION_WITHOUT_LINKS, stage);
        jdbcTemplate.update("INSERT INTO attachments (id, interaction_id, stage_id, kind) VALUES (?, ?, ?, 'CONTRACT')",
                UUID.randomUUID(), INTERACTION_WITHOUT_LINKS, stage);
        java.util.function.Supplier<List<WorkItem>> flagged = () -> workControlService.control(LEADER_A_PROFILE, filters(), null, null)
                .teams().getFirst().managers().stream().flatMap(block -> block.works().stream()).filter(WorkItem::missingDocuments).toList();

        assertThat(flagged.get()).extracting(WorkItem::interactionId).containsExactly(INTERACTION_WITHOUT_LINKS);
        UUID act = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO attachments (id, interaction_id, stage_id, kind, status) VALUES (?, ?, ?, 'ACT', 'REJECTED')",
                act, INTERACTION_WITHOUT_LINKS, stage);
        assertThat(flagged.get()).as("отклонённый антивирусом файл не приложен").hasSize(1);
        jdbcTemplate.update("UPDATE attachments SET status = 'QUARANTINE' WHERE id = ?", act);
        assertThat(flagged.get()).as("всё приложено — сигнала нет").isEmpty();
    }

    @Test
    void managementSeesAllTeamsReadOnlyAndOthersAreDenied() {
        CrmProfile management = new CrmProfile(UUID.randomUUID(), UserRole.MANAGEMENT, null, 0);

        Control control = workControlService.control(management, filters(), null, null);

        assertThat(control.byTeams()).isTrue();
        assertThat(control.teams()).extracting(TeamBlock::teamName).containsExactly("Команда А", "Команда Б");
        assertThat(control.total().works()).isEqualTo(4);
        assertThat(control.teams().get(1).managers()).singleElement()
                .satisfies(block -> assertThat(block.works()).extracting(WorkItem::interactionId).containsExactly(INTERACTION_FOREIGN));
        for (CrmProfile denied : List.of(
                MANAGER_A_PROFILE,
                ADMIN_PROFILE,
                new CrmProfile(UUID.randomUUID(), UserRole.PARTNER, null, 0),
                new CrmProfile(UUID.randomUUID(), UserRole.LEADER, null, 0)
        )) {
            assertThatThrownBy(() -> workControlService.control(denied, filters(), null, null))
                    .isInstanceOf(WorkAccessDeniedException.class);
        }
    }

    @Test
    void leaderDoesNotSeeForeignTeamEvenWhenFilteredByIt() {
        Control control = workControlService.control(
                LEADER_A_PROFILE, new ReportFilters(List.of(ORGANIZATION_B), List.of(), false, List.of(), false, List.of(), false,
                        List.of(), false, List.of(), null), null, null);

        assertThat(control.total().works()).isZero();
    }

    @Test
    void filtersNarrowByProductManagerAndStage() {
        assertThat(works(workControlService.control(LEADER_A_PROFILE, filters(List.of(PRODUCT_Y), List.of(), List.of(), false),
                null, null))).containsExactly(INTERACTION_TWO_PRODUCTS);
        assertThat(works(workControlService.control(LEADER_A_PROFILE, filters(List.of(), List.of(MANAGER_A2), List.of(), false),
                null, null))).isEmpty();
        assertThat(works(workControlService.control(LEADER_A_PROFILE, filters(List.of(), List.of(), List.of(), true),
                null, null))).containsExactly(INTERACTION_UNASSIGNED);
        assertThat(works(workControlService.control(LEADER_A_PROFILE, filters(List.of(), List.of(), List.of("Встреча"), false),
                null, null))).containsExactly(INTERACTION_TWO_PRODUCTS);
    }

    @Test
    void periodShowsStageAtStartTransitionsAndChangeFilters() {
        Control control = workControlService.control(LEADER_A_PROFILE, filters(), SEPTEMBER_FIRST, SEPTEMBER_LAST);

        WorkItem agreement = control.teams().getFirst().managers().stream()
                .flatMap(block -> block.works().stream())
                .filter(work -> work.interactionId().equals(INTERACTION_TWO_PRODUCTS))
                .findFirst().orElseThrow();
        assertThat(agreement.stageAtStart()).isEqualTo("Поиск контакта");
        assertThat(agreement.stage()).isEqualTo("Встреча");
        assertThat(agreement.transitions()).isEqualTo(1);
        assertThat(agreement.created()).isFalse();
        assertThat(control.total().moved()).isEqualTo(1);
        assertThat(control.total().created()).isEqualTo(2);

        assertThat(works(workControlService.control(LEADER_A_PROFILE, filters().withChange(ManagerWorkChange.MOVED), SEPTEMBER_FIRST, SEPTEMBER_LAST))).containsExactly(INTERACTION_TWO_PRODUCTS);
        assertThat(works(workControlService.control(LEADER_A_PROFILE, filters().withChange(ManagerWorkChange.NEW), SEPTEMBER_FIRST, SEPTEMBER_LAST))).containsExactlyInAnyOrder(INTERACTION_WITHOUT_LINKS, INTERACTION_UNASSIGNED);
        // П07: вход в последний этап маршрута при статусе ACTIVE — не завершение.
        assertThat(works(workControlService.control(LEADER_A_PROFILE, filters().withChange(ManagerWorkChange.COMPLETED), SEPTEMBER_FIRST, SEPTEMBER_LAST))).isEmpty();
        assertThat(control.total().completed()).isZero();
    }

    // П01, П02: вуз передан другому КАМ после конца месяца. В каждом режиме экран, предпросмотр и файл дают одни и те же
    // взаимодействия, менеджеров и итоги — без отбора и с отбором каждого КАМ.
    @Test
    void eachModeUsesOneOwnerPolicyOnScreenPreviewAndFileForEveryManagerFilter() {
        ReportTestData.assignment(jdbcTemplate, ReportTestData.ORGANIZATION_A, MANAGER_A, "Анна Кузнецова", MANAGER_A2,
                "Борис Смирнов", ReportTestData.at("2026-10-05T10:00:00+03:00"));
        List<ReportFilters> managerFilters = List.of(filters(), filters(List.of(), List.of(MANAGER_A), List.of(), false),
                filters(List.of(), List.of(MANAGER_A2), List.of(), false), filters(List.of(), List.of(), List.of(), true));
        for (ManagerAt mode : ManagerAt.values()) {
            for (ReportFilters managerFilter : managerFilters) {
                ReportFilters selected = managerFilter.withManagerAt(mode);
                Control control = workControlService.control(LEADER_A_PROFILE, selected, SEPTEMBER_FIRST, SEPTEMBER_LAST);
                ReportRequest request = new ReportRequest(ReportKind.MANAGER_WORK, SEPTEMBER_FIRST, SEPTEMBER_LAST, null, selected,
                        List.of(ReportColumn.MANAGER, ReportColumn.INTERACTION), ReportFormat.XLSX, null, null);
                ReportPreview preview = reportService.preview(LEADER_A_PROFILE, request, 0, 50);
                ReportDocument document = reportService.document(LEADER_A_PROFILE, request);
                String label = mode + " " + selected.managerIds() + " " + selected.includeNoManager();

                assertThat(document.rows()).extracting(ReportRow::interactionId).as(label)
                        .containsExactlyInAnyOrderElementsOf(works(control));
                assertThat(preview.total()).as(label).isEqualTo(control.total().works()).isEqualTo(document.rows().size());
                assertThat(preview.items()).extracting(item -> item.get("INTERACTION") + "|" + item.get("MANAGER")).as(label)
                        .containsExactlyInAnyOrderElementsOf(document.rows().stream()
                                .map(row -> row.interactionTitle() + "|" + row.managerName()).toList())
                        .containsExactlyInAnyOrderElementsOf(control.teams().stream().flatMap(team -> team.managers().stream())
                                .flatMap(block -> block.works().stream().map(work -> work.title() + "|"
                                        + (block.managerName() == null ? "Требует назначения" : block.managerName())))
                                .toList());
                if (control.total().works() > 0) {
                    assertThat(preview.groupTotals().getLast().works()).as(label).isEqualTo(control.total().works());
                    assertThat(document.groupTotals().getLast().organizations()).as(label).isEqualTo(control.total().organizations());
                }
            }
        }
        ReportFilters annaInSeptember = filters(List.of(), List.of(MANAGER_A), List.of(), false);
        assertThat(works(workControlService.control(LEADER_A_PROFILE, annaInSeptember.withManagerAt(ManagerAt.PERIOD_END),
                SEPTEMBER_FIRST, SEPTEMBER_LAST))).containsExactlyInAnyOrder(INTERACTION_TWO_PRODUCTS, INTERACTION_WITHOUT_LINKS);
        assertThat(works(workControlService.control(LEADER_A_PROFILE, annaInSeptember.withManagerAt(ManagerAt.CURRENT),
                SEPTEMBER_FIRST, SEPTEMBER_LAST))).isEmpty();
        assertThat(works(workControlService.control(LEADER_A_PROFILE, annaInSeptember, SEPTEMBER_FIRST, SEPTEMBER_LAST)))
                .as("без режима «Контроль» — «Сейчас»").isEmpty();
        assertThat(works(workControlService.control(LEADER_A_PROFILE, annaInSeptember.withManagerAt(ManagerAt.PERIOD_END), null, null)))
                .as("без периода режим «Сейчас»").isEmpty();
    }

    // Исторический КАМ не расширяет доступ: вуз, переданный после периода в другую команду, руководитель прежней
    // команды не видит и в режиме «За период».
    @Test
    void periodModeKeepsTheCurrentScope() {
        ReportTestData.assignment(jdbcTemplate, ReportTestData.ORGANIZATION_A, MANAGER_A, "Анна Кузнецова", ReportTestData.MANAGER_B,
                "Вера Орлова", ReportTestData.at("2026-10-05T10:00:00+03:00"));
        jdbcTemplate.update("UPDATE organizations SET team_id = ? WHERE id = ?", ReportTestData.TEAM_B, ReportTestData.ORGANIZATION_A);
        ReportFilters periodEnd = filters().withManagerAt(ManagerAt.PERIOD_END);

        assertThat(works(workControlService.control(LEADER_A_PROFILE, periodEnd, SEPTEMBER_FIRST, SEPTEMBER_LAST)))
                .containsExactly(INTERACTION_UNASSIGNED);
        assertThat(reportService.preview(LEADER_A_PROFILE, new ReportRequest(ReportKind.MANAGER_WORK, SEPTEMBER_FIRST, SEPTEMBER_LAST,
                null, periodEnd, List.of(ReportColumn.INTERACTION), ReportFormat.XLSX, null, null), 0, 50).items())
                .extracting(item -> item.get("INTERACTION")).containsExactly("Запрос на обучение");
    }

    // П03: выгрузка плитки «Без работы» — те же вузы, что на экране, с КАМ, командой и последним действием сотрудника.
    @Test
    void idleExportIsTheSameOrganizationListWithLastStaffAction() {
        UUID gamma = UUID.randomUUID();
        UUID delta = UUID.randomUUID();
        ReportTestData.organization(jdbcTemplate, gamma, "Университет «Гамма»", ReportTestData.TEAM_A, MANAGER_A2);
        ReportTestData.organization(jdbcTemplate, delta, "Университет «Дельта»", ReportTestData.TEAM_A, MANAGER_A);
        ReportTestData.organization(jdbcTemplate, UUID.randomUUID(), "Колледж без КАМ", ReportTestData.TEAM_A, null);
        UUID closed = UUID.randomUUID();
        ReportTestData.interaction(jdbcTemplate, closed, delta, "Закрытое", null, 0, ReportTestData.at("2026-08-02T10:00:00+03:00"));
        jdbcTemplate.update("UPDATE interactions SET work_status = 'COMPLETED' WHERE id = ?", closed);
        ReportTestData.event(jdbcTemplate, closed, "COMMENTED", "Поиск контакта", null, "Итоговый звонок", MANAGER_A,
                ReportTestData.at("2026-09-20T15:00:00+03:00"));
        UUID command = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO command_idempotency_records (id, actor_profile_id, operation, idempotency_key, request_fingerprint)
                VALUES (?, ?, 'APPLY_SOURCE_RECORD', 'source-idle', ?)
                """, command, LEADER_A_PROFILE.id(), "0".repeat(64));
        ReportTestData.event(jdbcTemplate, closed, "COMMENTED", "Поиск контакта", null, "Данные LMS", MANAGER_A,
                ReportTestData.at("2026-10-01T15:00:00+03:00"));
        jdbcTemplate.update("UPDATE interaction_events SET command_id = ? WHERE comment = 'Данные LMS'", command);

        Control control = workControlService.control(LEADER_A_PROFILE, filters(), null, null);
        ReportRequest idle = new ReportRequest(ReportKind.MANAGER_WORK, null, null, null, tileFilters(ManagerWorkTile.IDLE, null),
                List.of(ReportColumn.MANAGER, ReportColumn.INTERACTION, ReportColumn.NEXT_ACTION), ReportFormat.XLSX, null, null);
        ReportPreview preview = reportService.preview(LEADER_A_PROFILE, idle, 0, 50);
        ReportDocument document = reportService.document(LEADER_A_PROFILE, idle);

        assertThat(preview.columns()).extracting(column -> column.id().name())
                .containsExactly("MANAGER", "TEAM", "ORGANIZATION", "LAST_STAFF_ACTION");
        assertThat(document.rows()).extracting(ReportRow::organizationId)
                .containsExactlyInAnyOrderElementsOf(control.idleOrganizations().stream().map(ManagerWorkOrganization::id).toList())
                .containsExactlyInAnyOrder(gamma, delta);
        assertThat(document.rows()).allSatisfy(row -> assertThat(row.interactionId()).isNull());
        assertThat(preview.items()).extracting(item -> item.get("ORGANIZATION"), item -> item.get("MANAGER"), item -> item.get("TEAM"))
                .containsExactly(tuple("Университет «Дельта»", "Анна Кузнецова", "Команда А"),
                        tuple("Университет «Гамма»", "Борис Смирнов", "Команда А"));
        assertThat(document.rows()).extracting(ReportColumn.LAST_STAFF_ACTION::text)
                .containsExactly("20.09.2026 15:00", "не зафиксировано");
        assertThat(control.idleOrganizations()).filteredOn(organization -> organization.id().equals(delta)).singleElement()
                .satisfies(organization -> assertThat(organization.lastActionAt())
                        .isEqualTo(ReportTestData.at("2026-09-20T15:00:00+03:00")));
        assertThat(preview.groupTotals().getLast().organizations()).isEqualTo(control.total().idle());
        assertThat(preview.groupTotals().getLast().works()).isZero();
        assertThatThrownBy(() -> reportService.preview(LEADER_A_PROFILE, new ReportRequest(ReportKind.MANAGER_WORK, SEPTEMBER_FIRST,
                SEPTEMBER_LAST, null, tileFilters(ManagerWorkTile.IDLE, null), null, ReportFormat.XLSX, null, null), 0, 50))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("filters.tile"));
    }

    // П06 и повторное открытие: завершено в периоде — только если последняя смена статуса в периоде дала COMPLETED.
    @Test
    void completedInPeriodFollowsTheStatusAfterTheEvent() {
        statusEvent(INTERACTION_WITHOUT_LINKS, "PAUSED", "2026-09-10T10:00:00+03:00");
        statusEvent(INTERACTION_WITHOUT_LINKS, "COMPLETED", "2026-10-03T10:00:00+03:00");
        statusEvent(INTERACTION_UNASSIGNED, "COMPLETED", "2026-09-20T10:00:00+03:00");
        statusEvent(INTERACTION_UNASSIGNED, "ACTIVE", "2026-09-25T10:00:00+03:00");
        jdbcTemplate.update("UPDATE interactions SET work_status = 'COMPLETED' WHERE id = ?", INTERACTION_WITHOUT_LINKS);
        LocalDate octoberFirst = LocalDate.parse("2026-10-01");
        LocalDate octoberLast = LocalDate.parse("2026-10-31");
        ReportFilters completed = filters().withChange(ManagerWorkChange.COMPLETED);

        for (ManagerAt mode : ManagerAt.values()) {
            ReportFilters byMode = completed.withManagerAt(mode);
            assertThat(works(workControlService.control(LEADER_A_PROFILE, byMode, SEPTEMBER_FIRST, SEPTEMBER_LAST))).as(mode.name())
                    .isEmpty();
            assertThat(works(workControlService.control(LEADER_A_PROFILE, byMode, octoberFirst, octoberLast))).as(mode.name())
                    .containsExactly(INTERACTION_WITHOUT_LINKS);
            // Приостановленная в сентябре и завершённая в октябре остаётся в сентябре строкой, но не «завершённой».
            Control september = workControlService.control(LEADER_A_PROFILE, filters().withManagerAt(mode), SEPTEMBER_FIRST,
                    SEPTEMBER_LAST);
            assertThat(works(september)).as(mode.name()).contains(INTERACTION_WITHOUT_LINKS, INTERACTION_UNASSIGNED);
            assertThat(september.total().completed()).as(mode.name()).isZero();
        }
        assertThat(reportService.preview(LEADER_A_PROFILE, new ReportRequest(ReportKind.MANAGER_WORK, octoberFirst, octoberLast, null,
                completed, List.of(ReportColumn.INTERACTION), ReportFormat.XLSX, null, null), 0, 50).items())
                .extracting(item -> item.get("INTERACTION")).containsExactly("Первое обращение");
    }

    // Н05 (C01, C02, C05): закончившийся период «За период» собирается по статусу на его конец, а не по сегодняшнему.
    // Работа без событий в сентябре, завершённая в октябре, остаётся в сентябре у КАМ, который вёл вуз тогда;
    // приостановленная до сентября не входит. Экран, предпросмотр, XLSX и JSON дают одни строки, а «стало» состава
    // активных (М15) — то же число, что строки «Контроля».
    @Test
    void pastPeriodCompositionFollowsStatusHistoryNotTodaysStatus() throws IOException {
        UUID completedLater = UUID.randomUUID();
        ReportTestData.interaction(jdbcTemplate, completedLater, ReportTestData.ORGANIZATION_A, "Без событий в сентябре", null, 0,
                ReportTestData.at("2026-08-05T10:00:00+03:00"));
        ReportTestData.event(jdbcTemplate, completedLater, "CREATED", "Поиск контакта", null, null, MANAGER_A,
                ReportTestData.at("2026-08-05T10:00:00+03:00"));
        statusEvent(completedLater, "COMPLETED", "2026-10-10T10:00:00+03:00");
        UUID pausedBefore = UUID.randomUUID();
        ReportTestData.interaction(jdbcTemplate, pausedBefore, ReportTestData.ORGANIZATION_A, "Приостановлено до сентября", null, 0,
                ReportTestData.at("2026-08-06T10:00:00+03:00"));
        ReportTestData.event(jdbcTemplate, pausedBefore, "CREATED", "Поиск контакта", null, null, MANAGER_A,
                ReportTestData.at("2026-08-06T10:00:00+03:00"));
        statusEvent(pausedBefore, "PAUSED", "2026-08-20T10:00:00+03:00");
        statusEvent(pausedBefore, "COMPLETED", "2026-10-12T10:00:00+03:00");
        jdbcTemplate.update("UPDATE interactions SET work_status = 'COMPLETED' WHERE id IN (?, ?)", completedLater, pausedBefore);
        ReportTestData.assignment(jdbcTemplate, ReportTestData.ORGANIZATION_A, MANAGER_A, "Анна Кузнецова", MANAGER_A2,
                "Борис Смирнов", ReportTestData.at("2026-10-05T10:00:00+03:00"));
        ReportFilters periodEnd = filters().withManagerAt(ManagerAt.PERIOD_END);

        Control control = workControlService.control(LEADER_A_PROFILE, periodEnd, SEPTEMBER_FIRST, SEPTEMBER_LAST);

        assertThat(works(control)).containsExactlyInAnyOrder(INTERACTION_TWO_PRODUCTS, INTERACTION_WITHOUT_LINKS,
                INTERACTION_UNASSIGNED, completedLater);
        assertThat(control.teams().getFirst().managers()).filteredOn(block -> block.works().stream()
                        .anyMatch(work -> work.interactionId().equals(completedLater)))
                .singleElement().extracting(ManagerBlock::managerName).isEqualTo("Анна Кузнецова");
        assertThat(control.total().completed()).as("завершение в октябре сентябрю не приписывается").isZero();

        ReportRequest request = new ReportRequest(ReportKind.MANAGER_WORK, SEPTEMBER_FIRST, SEPTEMBER_LAST, null, periodEnd,
                List.of(ReportColumn.MANAGER, ReportColumn.INTERACTION), ReportFormat.XLSX, null, null);
        ReportPreview preview = reportService.preview(LEADER_A_PROFILE, request, 0, 50);
        ReportDocument document = reportService.document(LEADER_A_PROFILE, request);
        assertThat(document.rows()).extracting(ReportRow::interactionId).containsExactlyInAnyOrderElementsOf(works(control));
        assertThat(preview.total()).isEqualTo(control.total().works()).isEqualTo(4);
        assertThat(preview.items()).extracting(item -> item.get("INTERACTION") + "|" + item.get("MANAGER"))
                .contains("Без событий в сентябре|Анна Кузнецова")
                .containsExactlyInAnyOrderElementsOf(document.rows().stream()
                        .map(row -> row.interactionTitle() + "|" + row.managerName()).toList());
        assertThat(preview.notes()).anyMatch(note -> note.contains("активные на его конец по истории смены статуса"));

        ByteArrayOutputStream xlsx = new ByteArrayOutputStream();
        new ExcelReportWriter().write(document, ReportFormat.XLSX, xlsx);
        List<String> cells = new java.util.ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(xlsx.toByteArray()))) {
            workbook.getSheetAt(0).forEach(row -> row.forEach(cell -> cells.add(cell.toString())));
        }
        assertThat(cells).contains("Без событий в сентябре").doesNotContain("Приостановлено до сентября");
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        ByteArrayOutputStream json = new ByteArrayOutputStream();
        new JsonReportWriter(mapper).write(document, json);
        assertThat(mapper.readTree(json.toByteArray()).get("rows")).hasSize(4);

        List<ru.rtk.crm.work.PortfolioChange.State> states = workRepository.findPortfolioStates(
                organizationRepository.visibilityScope(LEADER_A_PROFILE).orElseThrow(),
                SEPTEMBER_FIRST.atStartOfDay(ReportRequest.ZONE).toOffsetDateTime(),
                SEPTEMBER_LAST.plusDays(1).atStartOfDay(ReportRequest.ZONE).toOffsetDateTime());
        assertThat(states.stream().filter(state -> "ACTIVE".equals(state.statusAtEnd())).count())
                .as("«стало» М15 = строки «Контроля»").isEqualTo(control.total().works());
        assertThat(states.stream().filter(state -> "ACTIVE".equals(state.statusAtStart())).count()).isEqualTo(2);

        // «Сейчас» по-прежнему отбирает по сегодняшнему статусу: работа без событий в периоде, уже завершённая, не входит.
        assertThat(works(workControlService.control(LEADER_A_PROFILE, filters().withManagerAt(ManagerAt.CURRENT), SEPTEMBER_FIRST,
                SEPTEMBER_LAST))).doesNotContain(completedLater, pausedBefore);
    }

    // П08: завершённое взаимодействие без следующего шага — не дефект продолжения; приостановленное — по дате возврата.
    @Test
    void dueCountsOnlyActiveWorkAndPausedByReturnDate() {
        statusEvent(INTERACTION_WITHOUT_LINKS, "COMPLETED", "2026-09-20T10:00:00+03:00");
        jdbcTemplate.update("UPDATE interactions SET work_status = 'COMPLETED' WHERE id = ?", INTERACTION_WITHOUT_LINKS);
        statusEvent(INTERACTION_UNASSIGNED, "PAUSED", "2026-09-21T10:00:00+03:00");
        jdbcTemplate.update("UPDATE interactions SET work_status = 'PAUSED', next_action = 'Вернуться к работе после приостановки',"
                + " next_action_at = ? WHERE id = ?", java.time.OffsetDateTime.now().minusDays(1), INTERACTION_UNASSIGNED);

        Control control = workControlService.control(LEADER_A_PROFILE, filters(), SEPTEMBER_FIRST, SEPTEMBER_LAST);

        assertThat(control.teams().getFirst().managers().stream().flatMap(block -> block.works().stream()))
                .extracting(WorkItem::interactionId, WorkItem::due)
                .containsExactlyInAnyOrder(
                        tuple(INTERACTION_TWO_PRODUCTS, ManagerWorkDue.OVERDUE),
                        tuple(INTERACTION_WITHOUT_LINKS, ManagerWorkDue.IN_ORDER),
                        tuple(INTERACTION_UNASSIGNED, ManagerWorkDue.OVERDUE));
        assertThat(control.total().noNextStep()).isZero();
        assertThat(control.total().overdue()).isEqualTo(2);

        jdbcTemplate.update("UPDATE interactions SET next_action_at = ? WHERE id = ?",
                java.time.OffsetDateTime.now().plusDays(60), INTERACTION_UNASSIGNED);
        jdbcTemplate.update("UPDATE interactions SET next_action_at = ? WHERE id = ?",
                java.time.OffsetDateTime.now().minusDays(3), INTERACTION_WITHOUT_LINKS);
        Control later = workControlService.control(LEADER_A_PROFILE, filters(), SEPTEMBER_FIRST, SEPTEMBER_LAST);
        assertThat(later.total().overdue()).isEqualTo(1);
        assertThat(later.total().noNextStep()).isZero();
        assertThat(reportService.preview(LEADER_A_PROFILE, new ReportRequest(ReportKind.MANAGER_WORK, SEPTEMBER_FIRST, SEPTEMBER_LAST,
                null, tileFilters(ManagerWorkTile.NO_NEXT_STEP, null), List.of(ReportColumn.INTERACTION), ReportFormat.XLSX, null, null),
                0, 50).total()).isZero();
    }

    // П09: возврат из последнего этапа — это переходы, а не завершение.
    @Test
    void returnFromTheLastStageIsTransitionsNotCompletion() {
        ReportTestData.event(jdbcTemplate, INTERACTION_TWO_PRODUCTS, "TRANSITIONED", "Уточнение актуальности", "Встреча",
                "Вернули на уточнение", MANAGER_A, ReportTestData.at("2026-09-20T12:00:00+03:00"));
        jdbcTemplate.update("UPDATE interactions SET current_stage_id = (SELECT id FROM interaction_stages WHERE interaction_id = ?"
                + " AND name = 'Уточнение актуальности') WHERE id = ?", INTERACTION_TWO_PRODUCTS, INTERACTION_TWO_PRODUCTS);

        Control control = workControlService.control(LEADER_A_PROFILE, filters(), SEPTEMBER_FIRST, SEPTEMBER_LAST);

        WorkItem item = control.teams().getFirst().managers().stream().flatMap(block -> block.works().stream())
                .filter(work -> work.interactionId().equals(INTERACTION_TWO_PRODUCTS)).findFirst().orElseThrow();
        assertThat(item.transitions()).isEqualTo(2);
        assertThat(item.completed()).isFalse();
        assertThat(control.total().completed()).isZero();
        assertThat(works(workControlService.control(LEADER_A_PROFILE, filters().withChange(ManagerWorkChange.MOVED), SEPTEMBER_FIRST,
                SEPTEMBER_LAST))).containsExactly(INTERACTION_TWO_PRODUCTS);
    }

    private void statusEvent(UUID interactionId, String status, String occurredAt) {
        String stage = jdbcTemplate.queryForObject("SELECT s.name FROM interactions i JOIN interaction_stages s ON s.id = i.current_stage_id"
                + " WHERE i.id = ?", String.class, interactionId);
        ReportTestData.event(jdbcTemplate, interactionId, "STATUS_CHANGED", stage, null, "Смена статуса: " + status, MANAGER_A,
                ReportTestData.at(occurredAt));
        jdbcTemplate.update("UPDATE interaction_events SET work_status_after = ? WHERE interaction_id = ? AND occurred_at = ?",
                status, interactionId, ReportTestData.at(occurredAt));
    }

    @Test
    void invalidPeriodIsRejected() {
        assertThatThrownBy(() -> workControlService.control(LEADER_A_PROFILE, filters(), SEPTEMBER_LAST, SEPTEMBER_FIRST))
                .isInstanceOf(InteractionValidationException.class);
    }

    @Test
    void archivedOrganizationsAndTheirWorkAreLeftOutOfMatrixAndReport() {
        jdbcTemplate.update("UPDATE organizations SET status = 'ARCHIVED' WHERE id = ?", ReportTestData.ORGANIZATION_A_UNASSIGNED);

        Control control = workControlService.control(LEADER_A_PROFILE, filters(), null, null);

        assertThat(works(control)).doesNotContain(INTERACTION_UNASSIGNED);
        assertThat(control.teams().getFirst().managers()).extracting(ManagerBlock::managerName)
                .containsExactly("Анна Кузнецова", "Борис Смирнов");
        assertThat(control.total().works()).isEqualTo(2);
        assertThat(reportService.preview(LEADER_A_PROFILE, request(null, null), 0, 50).items())
                .noneMatch(item -> "Институт без КАМ".equals(item.get("ORGANIZATION")));
    }

    @Test
    void managerWorkReportGroupsByManagerWithTotalsAndPeriodColumns() {
        ReportRequest request = request(SEPTEMBER_FIRST, SEPTEMBER_LAST);

        ReportPreview preview = reportService.preview(LEADER_A_PROFILE, request, 0, 50);

        assertThat(preview.columns()).extracting(column -> column.id().name()).contains(
                "MANAGER", "ORGANIZATION", "STAGE_AT_START", "STAGE_AT_END", "TRANSITIONS", "PERIOD_COMMENTS", "OVERDUE_DAYS");
        assertThat(preview.columns().getFirst().title()).isEqualTo("Менеджер на конец периода");
        assertThat(preview.items()).extracting(item -> item.get("MANAGER"), item -> item.get("ORGANIZATION"))
                .containsExactly(
                        tuple("Анна Кузнецова", "Университет «Альфа»"),
                        tuple("Анна Кузнецова", "Университет «Альфа»"),
                        tuple("Требует назначения", "Институт без КАМ")
                );
        assertThat(preview.total()).isEqualTo(3);
        assertThat(preview.groupTotals())
                .extracting(ManagerWorkTotal::team, ManagerWorkTotal::manager, ManagerWorkTotal::organizations, ManagerWorkTotal::works)
                .containsExactly(
                        tuple("Команда А", "Анна Кузнецова", 1L, 2L),
                        tuple("Команда А", "Требует назначения", 1L, 1L),
                        tuple("Команда А", "Итого по команде", 2L, 3L),
                        tuple("Все команды", "Всего", 2L, 3L)
                );
        // Н5 (2.5.3): «Взаимодействие» — в колонках по умолчанию, рядом с вузом.
        assertThat(ReportKind.MANAGER_WORK.defaultColumns()).startsWith(ReportColumn.MANAGER, ReportColumn.ORGANIZATION,
                ReportColumn.INTERACTION);
        assertThat(preview.items()).anyMatch(item -> "Договор на курс Java".equals(item.get("INTERACTION")));
        ReportRequest withTitle = request(SEPTEMBER_FIRST, SEPTEMBER_LAST, ReportColumn.MANAGER, ReportColumn.INTERACTION,
                ReportColumn.STAGE_AT_START, ReportColumn.STAGE_AT_END, ReportColumn.STAGE_POSITION, ReportColumn.TRANSITIONS,
                ReportColumn.PERIOD_COMMENTS, ReportColumn.DUE_STATE, ReportColumn.OVERDUE_DAYS);
        ReportPreview withTitlePreview = reportService.preview(LEADER_A_PROFILE, withTitle, 0, 50);
        var rows = withTitlePreview.items();
        var row = rows.stream().filter(item -> "Договор на курс Java".equals(item.get("INTERACTION"))).findFirst().orElseThrow();
        assertThat(row.get("STAGE_AT_START")).isEqualTo("Поиск контакта");
        assertThat(row.get("STAGE_AT_END")).isEqualTo("Встреча");
        assertThat(row.get("STAGE_POSITION")).isEqualTo("3 из 3");
        assertThat(row.get("TRANSITIONS")).isEqualTo(1L);
        assertThat(row.get("DUE_STATE")).isEqualTo("Просрочено");
        assertThat((String) row.get("PERIOD_COMMENTS")).contains("05.09.2026: Назначена встреча").contains("10.09.2026: ")
                .doesNotContain("Перед периодом").doesNotContain("После периода");
        assertThat((Long) row.get("OVERDUE_DAYS")).isPositive();
        assertThat(rows).noneMatch(item -> item.get("INTERACTION") == null);
        var total = withTitlePreview.groupTotals().stream().filter(item -> "Анна Кузнецова".equals(item.manager()))
                .findFirst().orElseThrow();
        assertThat(total.transitions()).isEqualTo(1L);
        assertThat(List.of(total.overdue(), total.thisWeek(), total.noNextStep())).containsExactly(1L, 0L, 1L);
    }

    @Test
    void controlAndReportShowLmsLearningAndActiveSignalsOfTheWork() {
        ReportTestData.learning(jdbcTemplate, 2, null, ReportTestData.ORGANIZATION_A, ReportTestData.PROGRAM, 6,
                ReportTestData.TODAY.minusDays(30), ReportTestData.RUN_ENDS, "STUDENTS");
        jdbcTemplate.update("""
                UPDATE learning_snapshots SET active_7d_count = 1, active_30d_count = 6, never_accessed_count = 0,
                    inactive_count = 0, teachers_active_7d_count = 1, teachers_active_14d_count = 1
                WHERE course_id = 2
                """);

        WorkItem work = workControlService.control(LEADER_A_PROFILE, filters(), null, null).teams().getFirst().managers().stream()
                .flatMap(manager -> manager.works().stream())
                .filter(item -> item.interactionId().equals(INTERACTION_TWO_PRODUCTS))
                .findFirst().orElseThrow();

        assertThat(work.learning().runs()).isEqualTo(1);
        assertThat(work.learning().participants()).isEqualTo(6);
        assertThat(work.learning().active7d()).isEqualTo(1);
        assertThat(work.learning().signalTitles()).containsExactly("Мало заходят в курс");
        assertThat(work.learning().mismatch()).as("the route of the work has no classes stage").isNull();

        var rows = reportService.preview(LEADER_A_PROFILE, request(null, null), 0, 50).items();
        assertThat(rows).filteredOn(item -> "Договор на курс Java".equals(item.get("INTERACTION")) || item.get("LMS_LEARNING") != null)
                .extracting(item -> item.get("LMS_LEARNING"))
                .containsExactly("потоков идёт 1, обучающихся 6, заходили за 7 дней 1; сигналы: Мало заходят в курс");
        assertThat(reportService.preview(ADMIN_PROFILE, request(null, null), 0, 50).items()).isEmpty();
    }

    @Test
    void managerWorkReportRespectsScopeAndRejectsUnsupportedFilters() {
        assertThat(reportService.preview(MANAGER_A_PROFILE, request(null, null), 0, 50).total()).isEqualTo(2);
        assertThat(reportService.preview(ADMIN_PROFILE, request(null, null), 0, 50).total()).isZero();
        assertThatThrownBy(() -> reportService.preview(LEADER_A_PROFILE, new ReportRequest(ReportKind.MANAGER_WORK, null, null, null,
                new ReportFilters(List.of(), List.of(), false, List.of(), false, List.of(), false, List.of(), false, List.of(), null,
                        List.of(ru.rtk.crm.interaction.InteractionWorkStatus.PAUSED), List.of(), ReportAgreementFilters.none(),
                        List.of(), null), null, ReportFormat.XLSX, null, null), 0, 50))
                .isInstanceOf(InteractionValidationException.class);
    }

    @Test
    void managerWorkReportIsWrittenInEveryFormat() throws IOException {
        ReportDocument document = reportService.document(LEADER_A_PROFILE, request(SEPTEMBER_FIRST, SEPTEMBER_LAST));
        ExcelReportWriter excel = new ExcelReportWriter();
        for (ReportFormat format : List.of(ReportFormat.XLSX, ReportFormat.XLS)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            excel.write(document, format, output);
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
                assertThat(workbook.getSheetAt(0).getRow(document.notes().size() + 2).getCell(0).getStringCellValue())
                        .isEqualTo("Менеджер на конец периода");
                var dataTexts = new java.util.ArrayList<String>();
                workbook.getSheetAt(0).forEach(row -> row.forEach(cell -> dataTexts.add(cell.toString())));
                assertThat(dataTexts).noneMatch(text -> text.startsWith("Итого по менеджеру"));
                assertThat(workbook.getSheetAt(0).getRow(workbook.getSheetAt(0).getLastRowNum()).getCell(0).getStringCellValue())
                        .isEqualTo("Строк в отчёте: 3");
                Sheet totals = workbook.getSheet(ExcelReportWriter.TOTALS_SHEET);
                assertThat(totals).isNotNull();
                assertThat(totals.getRow(2).getCell(2).getStringCellValue()).isEqualTo("Вузов");
                org.apache.poi.ss.usermodel.Row team = null;
                for (org.apache.poi.ss.usermodel.Row row : totals) {
                    if (row.getCell(1) != null && "Итого по команде".equals(row.getCell(1).toString())) {
                        team = row;
                    }
                }
                assertThat(team).isNotNull();
                assertThat(team.getCell(0).getStringCellValue()).isEqualTo("Команда А");
                assertThat(team.getCell(2).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
                assertThat(team.getCell(2).getNumericCellValue()).isEqualTo(2.0);
                assertThat(team.getCell(3).getNumericCellValue()).isEqualTo(3.0);
                assertThat(team.getCell(4).getNumericCellValue()).isEqualTo(1.0);
            }
        }
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        new PdfReportWriter().write(document, pdf);
        assertThat(pdf.size()).isPositive();
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        ByteArrayOutputStream json = new ByteArrayOutputStream();
        new JsonReportWriter(mapper).write(document, json);
        JsonNode node = mapper.readTree(json.toByteArray());
        assertThat(node.get("kind").asText()).isEqualTo("MANAGER_WORK");
        assertThat(node.get("rows")).hasSize(3);
        assertThat(node.get("totals").get("rows").asInt()).isEqualTo(3);
        assertThat(node.get("totals").get("organizations").asInt()).isEqualTo(2);
        assertThat(node.get("totals").get("managers")).hasSize(4);
        assertThat(node.get("totals").get("managers").get(2).get("manager").asText()).isEqualTo("Итого по команде");
        assertThat(node.get("totals").get("managers").get(2).get("works").asInt()).isEqualTo(3);
    }

    @Test
    void managementTotalsKeepUnassignedOrganizationsOfEachTeamApartWithTeamAndGrandTotals() {
        UUID college = UUID.randomUUID();
        ReportTestData.organization(jdbcTemplate, college, "Колледж без КАМ", ReportTestData.TEAM_B, null);
        ReportTestData.interaction(jdbcTemplate, UUID.randomUUID(), college, "Запрос колледжа", null, 0,
                ReportTestData.at("2026-09-20T10:00:00+03:00"));
        CrmProfile management = new CrmProfile(UUID.randomUUID(), UserRole.MANAGEMENT, null, 0);

        ReportPreview preview = reportService.preview(management, request(null, null), 0, 50);

        assertThat(preview.groupTotals())
                .extracting(ManagerWorkTotal::team, ManagerWorkTotal::manager, ManagerWorkTotal::organizations, ManagerWorkTotal::works)
                .containsExactly(
                        tuple("Команда А", "Анна Кузнецова", 1L, 2L),
                        tuple("Команда А", "Требует назначения", 1L, 1L),
                        tuple("Команда А", "Итого по команде", 2L, 3L),
                        tuple("Команда Б", "Вера Орлова", 1L, 1L),
                        tuple("Команда Б", "Требует назначения", 1L, 1L),
                        tuple("Команда Б", "Итого по команде", 2L, 2L),
                        tuple("Все команды", "Всего", 4L, 5L)
                );
    }

    @Test
    void exportFollowsTheChosenTileAndShowsTheContractColumns() {
        UUID active = UUID.randomUUID();
        UUID review = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO agreements (id, organization_id, number, status, status_changed_at) VALUES
                    (?, ?, 'Д-7', 'ACTIVE', TIMESTAMP WITH TIME ZONE '2026-09-01 10:00:00+03:00'),
                    (?, ?, 'Д-8', 'REVIEW', CURRENT_TIMESTAMP)
                """, active, ReportTestData.ORGANIZATION_A, review, ReportTestData.ORGANIZATION_A_UNASSIGNED);
        jdbcTemplate.update("INSERT INTO agreement_interactions (agreement_id, interaction_id) VALUES (?, ?), (?, ?)",
                active, INTERACTION_TWO_PRODUCTS, review, INTERACTION_UNASSIGNED);

        assertThat(titles(tile(ManagerWorkTile.OVERDUE, null))).containsExactly("Договор на курс Java");
        assertThat(titles(tile(ManagerWorkTile.NO_NEXT_STEP, null))).containsExactlyInAnyOrder("Первое обращение", "Запрос на обучение");
        assertThat(titles(tile(ManagerWorkTile.AGREEMENT, null))).containsExactly("Запрос на обучение");
        assertThat(titles(tile(ManagerWorkTile.UNASSIGNED, null))).containsExactly("Запрос на обучение");
        assertThat(titles(tile(ManagerWorkTile.STUCK, 3650))).isEmpty();
        assertThat(titles(tile(ManagerWorkTile.STUCK, 1))).hasSize(3);
        ReportPreview overdue = reportService.preview(LEADER_A_PROFILE, tile(ManagerWorkTile.OVERDUE, null), 0, 50);
        assertThat(overdue.groupTotals()).extracting(ManagerWorkTotal::manager, ManagerWorkTotal::works)
                .containsExactly(tuple("Анна Кузнецова", 1L), tuple("Итого по команде", 1L), tuple("Всего", 1L));
        assertThat(overdue.notes()).anyMatch(note -> note.equals("Отбор плиткой «Контроля»: просрочено"));

        ReportRequest contracts = request(null, null, ReportColumn.INTERACTION, ReportColumn.AGREEMENT_STATUS, ReportColumn.AGREEMENT);
        assertThat(reportService.preview(LEADER_A_PROFILE, contracts, 0, 50).items())
                .extracting(item -> item.get("INTERACTION"), item -> item.get("AGREEMENT_STATUS"), item -> item.get("AGREEMENT"))
                .containsExactlyInAnyOrder(
                        tuple("Договор на курс Java", "Действует", "№ Д-7"),
                        tuple("Первое обращение", "Нет", null),
                        tuple("Запрос на обучение", "На согласовании", "№ Д-8")
                );
        assertThatThrownBy(() -> reportService.preview(LEADER_A_PROFILE, new ReportRequest(ReportKind.PORTFOLIO, null, null, null,
                tileFilters(ManagerWorkTile.OVERDUE, null), null, ReportFormat.XLSX, null, null), 0, 50))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("filters.tile"));
    }

    @Test
    void organizationsWithoutActiveWorkFormTheIdleGroupAndCountLikeTheUniversityList() {
        UUID gamma = UUID.randomUUID();
        UUID delta = UUID.randomUUID();
        ReportTestData.organization(jdbcTemplate, gamma, "Университет «Гамма»", ReportTestData.TEAM_A, MANAGER_A2);
        ReportTestData.organization(jdbcTemplate, delta, "Университет «Дельта»", ReportTestData.TEAM_A, MANAGER_A);
        ReportTestData.organization(jdbcTemplate, UUID.randomUUID(), "Колледж без работы", ReportTestData.TEAM_A, null);
        UUID paused = UUID.randomUUID();
        ReportTestData.interaction(jdbcTemplate, paused, delta, "Приостановленное", null, 0, ReportTestData.at("2026-09-02T10:00:00+03:00"));
        jdbcTemplate.update("UPDATE interactions SET work_status = 'PAUSED' WHERE id = ?", paused);

        Control control = workControlService.control(LEADER_A_PROFILE, filters(), null, null);

        Long listed = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM organizations WHERE team_id = ? AND status IN ('ACTIVE', 'PENDING')
                """, Long.class, ReportTestData.TEAM_A);
        assertThat(control.total().organizations()).isEqualTo(listed).isEqualTo(5);
        assertThat(control.total().idle()).isEqualTo(2);
        assertThat(control.total().works()).isEqualTo(3);
        assertThat(control.idleOrganizations()).extracting(ManagerWorkOrganization::name, ManagerWorkOrganization::managerName)
                .containsExactly(tuple("Университет «Гамма»", "Борис Смирнов"), tuple("Университет «Дельта»", "Анна Кузнецова"));
        assertThat(control.teams().getFirst().totals().organizations()).isEqualTo(5);
        assertThat(control.teams().getFirst().managers())
                .extracting(ManagerBlock::managerName, block -> block.totals().organizations(), block -> block.totals().idle())
                .containsExactly(tuple("Анна Кузнецова", 2L, 1L), tuple("Борис Смирнов", 1L, 1L), tuple(null, 2L, 0L));

        Control boris = workControlService.control(LEADER_A_PROFILE, filters(List.of(), List.of(MANAGER_A2), List.of(), false), null, null);
        assertThat(boris.idleOrganizations()).extracting(ManagerWorkOrganization::id).containsExactly(gamma);
        assertThat(boris.total().organizations()).isEqualTo(1);

        Control byProduct = workControlService.control(LEADER_A_PROFILE, filters(List.of(PRODUCT_Y), List.of(), List.of(), false), null, null);
        assertThat(byProduct.idleOrganizations()).isNull();
        assertThat(byProduct.total().organizations()).isEqualTo(1);
        assertThat(workControlService.control(LEADER_A_PROFILE, filters(), SEPTEMBER_FIRST, SEPTEMBER_LAST).idleOrganizations()).isNull();

        Control management = workControlService.control(new CrmProfile(UUID.randomUUID(), UserRole.MANAGEMENT, null, 0), filters(), null, null);
        assertThat(management.total().organizations()).isEqualTo(6);
        assertThat(management.idleOrganizations()).hasSize(2);
    }

    // Отбор по типу организации в «Контроле» — тот же, что в отчётах: строки, «Без работы» и её выгрузка сужаются по типу.
    @Test
    void organizationTypeNarrowsRowsIdleOrganizationsAndTheIdleExport() {
        UUID college = UUID.randomUUID();
        ReportTestData.organization(jdbcTemplate, UUID.randomUUID(), "Университет «Гамма»", ReportTestData.TEAM_A, MANAGER_A2);
        ReportTestData.organization(jdbcTemplate, college, "Колледж «Бета»", ReportTestData.TEAM_A, MANAGER_A);
        jdbcTemplate.update("UPDATE organizations SET type = 'COLLEGE' WHERE id = ?", college);
        ReportFilters colleges = new ReportFilters(List.of(), List.of(), false, List.of(), false, List.of(), false, List.of(), false,
                List.of(), OrganizationType.COLLEGE);

        Control all = workControlService.control(LEADER_A_PROFILE, filters(), null, null);
        Control control = workControlService.control(LEADER_A_PROFILE, colleges, null, null);

        assertThat(all.idleOrganizations()).hasSize(2);
        assertThat(control.idleOrganizations()).extracting(ManagerWorkOrganization::id).containsExactly(college);
        assertThat(control.total().works()).isZero();
        assertThat(control.total().organizations()).isEqualTo(1);
        ReportRequest idle = new ReportRequest(ReportKind.MANAGER_WORK, null, null, null,
                new ReportFilters(List.of(), List.of(), false, List.of(), false, List.of(), false, List.of(), false, List.of(),
                        OrganizationType.COLLEGE, List.of(), List.of(), ReportAgreementFilters.none(), List.of(), null, null,
                        ManagerWorkTile.IDLE), null, ReportFormat.XLSX, null, null);
        assertThat(reportService.document(LEADER_A_PROFILE, idle).rows()).extracting(ReportRow::organizationId)
                .containsExactly(college);
    }

    @Test
    void exportTotalsCountOrganizationsWithoutWorkLikeTheControlScreen() {
        ReportTestData.organization(jdbcTemplate, UUID.randomUUID(), "Университет «Гамма»", ReportTestData.TEAM_A, MANAGER_A2);
        ReportTestData.organization(jdbcTemplate, UUID.randomUUID(), "Университет «Дельта»", ReportTestData.TEAM_A, MANAGER_A);
        ReportTestData.organization(jdbcTemplate, UUID.randomUUID(), "Колледж без работы", ReportTestData.TEAM_A, null);
        CrmProfile management = new CrmProfile(UUID.randomUUID(), UserRole.MANAGEMENT, null, 0);

        Control control = workControlService.control(management, filters(), null, null);
        ReportPreview preview = reportService.preview(management, request(null, null), 0, 50);

        assertThat(preview.groupTotals())
                .extracting(ManagerWorkTotal::team, ManagerWorkTotal::manager, ManagerWorkTotal::organizations, ManagerWorkTotal::works)
                .containsExactly(
                        tuple("Команда А", "Анна Кузнецова", 2L, 2L),
                        tuple("Команда А", "Борис Смирнов", 1L, 0L),
                        tuple("Команда А", "Требует назначения", 2L, 1L),
                        tuple("Команда А", "Итого по команде", 5L, 3L),
                        tuple("Команда Б", "Вера Орлова", 1L, 1L),
                        tuple("Команда Б", "Итого по команде", 1L, 1L),
                        tuple("Все команды", "Всего", 6L, 4L)
                );
        assertThat(preview.groupTotals().stream().filter(total -> "Итого по команде".equals(total.manager()))
                .map(ManagerWorkTotal::organizations))
                .containsExactlyElementsOf(control.teams().stream().map(team -> team.totals().organizations()).toList());
        assertThat(preview.groupTotals().getLast().organizations()).isEqualTo(control.total().organizations());
        ManagerBlock anna = control.teams().getFirst().managers().stream()
                .filter(block -> "Анна Кузнецова".equals(block.managerName())).findFirst().orElseThrow();
        assertThat(preview.groupTotals().getFirst().organizations()).isEqualTo(anna.totals().organizations());
        // Вуз без КАМ и без работы входит в блок «не назначен» на экране так же, как в строку «Требует назначения».
        ManagerBlock unassigned = control.teams().getFirst().managers().stream()
                .filter(block -> block.managerId() == null).findFirst().orElseThrow();
        assertThat(unassigned.totals().organizations()).isEqualTo(2);

        assertThat(reportService.preview(LEADER_A_PROFILE, tile(ManagerWorkTile.NO_NEXT_STEP, null), 0, 50).groupTotals().getLast()
                .organizations()).isEqualTo(2);
        assertThat(reportService.preview(LEADER_A_PROFILE, request(SEPTEMBER_FIRST, SEPTEMBER_LAST), 0, 50).groupTotals().getLast()
                .organizations()).isEqualTo(2);
    }

    @Test
    void agreementTileCountsContractsOnApprovalEvenWhenNotLinkedToWork() {
        UUID review = UUID.randomUUID();
        UUID correction = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO agreements (id, organization_id, number, status, status_changed_at, sent_on) VALUES
                    (?, ?, 'Д-11', 'REVIEW', TIMESTAMP WITH TIME ZONE '2026-09-01 10:00:00+03:00', DATE '2026-07-20'),
                    (?, ?, 'Д-12', 'CORRECTION', TIMESTAMP WITH TIME ZONE '2026-09-02 10:00:00+03:00', NULL),
                    (?, ?, 'Д-13', 'ACTIVE', TIMESTAMP WITH TIME ZONE '2026-09-03 10:00:00+03:00', NULL),
                    (?, ?, 'Д-14', 'REVIEW', TIMESTAMP WITH TIME ZONE '2026-09-04 10:00:00+03:00', NULL)
                """, review, ReportTestData.ORGANIZATION_A, correction, ReportTestData.ORGANIZATION_A_UNASSIGNED,
                UUID.randomUUID(), ReportTestData.ORGANIZATION_A, UUID.randomUUID(), ORGANIZATION_B);

        Control control = workControlService.control(LEADER_A_PROFILE, filters(), null, null);
        assertThat(control.agreements()).as("ни один договор не привязан к взаимодействию, чужая команда не видна")
                .extracting(row -> row.number(), row -> row.teamName())
                .containsExactlyInAnyOrder(tuple("Д-11", "Команда А"), tuple("Д-12", "Команда А"));
        assertThat(control.agreements()).filteredOn(row -> row.number().equals("Д-11")).singleElement()
                .satisfies(row -> assertThat(row.approval().sideDays()).as("круг у вуза — от даты отправки").isPositive());
        assertThat(workControlService.control(new CrmProfile(UUID.randomUUID(), UserRole.MANAGEMENT, null, 0), filters(), null, null)
                .agreements()).hasSize(3);

        // С отбором остаются договоры вузов из строк: продукт Игрек есть только у взаимодействия Университета «Альфа».
        assertThat(workControlService.control(LEADER_A_PROFILE, filters(List.of(PRODUCT_Y), List.of(), List.of(), false), null, null)
                .agreements()).extracting(row -> row.number()).containsExactly("Д-11");

        // Выгрузка с плиткой — взаимодействия этих вузов, как строки на экране.
        assertThat(titles(tile(ManagerWorkTile.AGREEMENT, null)))
                .containsExactlyInAnyOrder("Договор на курс Java", "Первое обращение", "Запрос на обучение");
    }

    @Test
    void reportTotalsByProductAndByUniversityCountDistinctWorks() {
        ReportPreview preview = reportService.preview(LEADER_A_PROFILE, request(null, null), 0, 50);

        assertThat(preview.productTotals())
                .extracting(ManagerWorkGroupTotal::name, ManagerWorkGroupTotal::organizations, ManagerWorkGroupTotal::works)
                .containsExactly(
                        tuple("Продукт Игрек", 1L, 1L),
                        tuple("Продукт Икс", 2L, 2L),
                        tuple("Продукт не указан", 1L, 1L),
                        tuple("Всего (уникальные взаимодействия)", 2L, 3L)
                );
        assertThat(preview.productTotals().subList(0, 3).stream().mapToLong(ManagerWorkGroupTotal::works).sum())
                .as("взаимодействие с двумя продуктами входит в обе строки").isEqualTo(4);
        assertThat(preview.organizationTotals())
                .extracting(ManagerWorkGroupTotal::name, ManagerWorkGroupTotal::works)
                .containsExactly(
                        tuple("Институт без КАМ", 1L),
                        tuple("Университет «Альфа»", 2L),
                        tuple("Всего (уникальные взаимодействия)", 3L)
                );
        assertThat(preview.notes()).contains(ReportService.GROUP_TOTALS_NOTE);
    }

    @Test
    void groupTotalsGoToTheirOwnSheetsInTheFile() throws IOException {
        ReportDocument document = reportService.document(LEADER_A_PROFILE, request(null, null));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new ExcelReportWriter().write(document, ReportFormat.XLSX, output);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
            Sheet products = workbook.getSheet(ExcelReportWriter.PRODUCT_TOTALS_SHEET);
            assertThat(products.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Продукт");
            assertThat(products.getRow(6).getCell(0).getStringCellValue()).isEqualTo("Всего (уникальные взаимодействия)");
            assertThat(products.getRow(6).getCell(2).getNumericCellValue()).isEqualTo(3);
            Sheet universities = workbook.getSheet(ExcelReportWriter.ORGANIZATION_TOTALS_SHEET);
            assertThat(universities.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Вуз");
            assertThat(universities.getRow(4).getCell(0).getStringCellValue()).isEqualTo("Университет «Альфа»");
        }
    }

    private ReportRequest tile(ManagerWorkTile tile, Integer days) {
        return new ReportRequest(ReportKind.MANAGER_WORK, null, null, null, tileFilters(tile, days),
                List.of(ReportColumn.MANAGER, ReportColumn.INTERACTION), ReportFormat.XLSX, null, null);
    }

    private static ReportFilters tileFilters(ManagerWorkTile tile, Integer days) {
        return new ReportFilters(List.of(), List.of(), false, List.of(), false, List.of(), false, List.of(), false, List.of(), null,
                List.of(), List.of(), ReportAgreementFilters.none(), List.of(), days, null, tile);
    }

    private List<Object> titles(ReportRequest request) {
        return reportService.preview(LEADER_A_PROFILE, request, 0, 50).items().stream().map(item -> item.get("INTERACTION")).toList();
    }

    private static List<UUID> works(Control control) {
        return control.teams().stream()
                .flatMap(team -> team.managers().stream())
                .flatMap(manager -> manager.works().stream())
                .map(WorkItem::interactionId)
                .toList();
    }

    private static ReportFilters filters() {
        return filters(List.of(), List.of(), List.of(), false);
    }

    private static ReportFilters filters(List<UUID> products, List<UUID> managers, List<String> stages, boolean noManager) {
        return new ReportFilters(List.of(), List.of(), false, List.of(), false, products, false, managers, noManager, stages, null);
    }

    private static ReportRequest request(LocalDate from, LocalDate to, ReportColumn... columns) {
        return new ReportRequest(ReportKind.MANAGER_WORK, from, to, null, ReportFilters.none(),
                columns.length == 0 ? null : List.of(columns), ReportFormat.XLSX, null, null);
    }

    @TestConfiguration
    static class PropertiesConfiguration {
        @Bean
        ModuleProperties moduleProperties() {
            return new ModuleProperties(new ModuleProperties.Toggle(true), null);
        }

        @Bean
        ReportProperties reportProperties() {
            return new ReportProperties(Path.of("target", "test-reports"), 10, 5, 1_000, 1_000);
        }
    }
}
