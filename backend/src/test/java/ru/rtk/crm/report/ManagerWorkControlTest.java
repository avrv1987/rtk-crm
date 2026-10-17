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
        WorkControlService.class,
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

    @BeforeEach
    void setUp() {
        ReportTestData.createSchema(jdbcTemplate);
        ReportTestData.insertScenario(jdbcTemplate);
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
        assertThat(works(workControlService.control(LEADER_A_PROFILE, filters().withChange(ManagerWorkChange.COMPLETED), SEPTEMBER_FIRST, SEPTEMBER_LAST))).containsExactly(INTERACTION_TWO_PRODUCTS);
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
        assertThat(preview.columns().getFirst().title()).isEqualTo("Менеджер");
        assertThat(preview.items()).extracting(item -> item.get("MANAGER"), item -> item.get("ORGANIZATION"))
                .containsExactly(
                        tuple("Анна Кузнецова", "Университет «Альфа»"),
                        tuple("Анна Кузнецова", "Университет «Альфа»"),
                        tuple("Анна Кузнецова", "Итого по менеджеру Анна Кузнецова: вузов 1, работ 2"),
                        tuple("Требует назначения", "Институт без КАМ"),
                        tuple("Требует назначения", "Итого по менеджеру «Требует назначения»: вузов 1, работ 1")
                );
        assertThat(preview.total()).isEqualTo(5);
        var agreement = preview.items().stream().filter(item -> "Договор на курс Java".equals(item.get("INTERACTION"))).findFirst();
        assertThat(agreement).isEmpty();
        ReportRequest withTitle = request(SEPTEMBER_FIRST, SEPTEMBER_LAST, ReportColumn.MANAGER, ReportColumn.INTERACTION,
                ReportColumn.STAGE_AT_START, ReportColumn.STAGE_AT_END, ReportColumn.STAGE_POSITION, ReportColumn.TRANSITIONS,
                ReportColumn.PERIOD_COMMENTS, ReportColumn.DUE_STATE, ReportColumn.OVERDUE_DAYS);
        var rows = reportService.preview(LEADER_A_PROFILE, withTitle, 0, 50).items();
        var row = rows.stream().filter(item -> "Договор на курс Java".equals(item.get("INTERACTION"))).findFirst().orElseThrow();
        assertThat(row.get("STAGE_AT_START")).isEqualTo("Поиск контакта");
        assertThat(row.get("STAGE_AT_END")).isEqualTo("Встреча");
        assertThat(row.get("STAGE_POSITION")).isEqualTo("3 из 3");
        assertThat(row.get("TRANSITIONS")).isEqualTo(1L);
        assertThat(row.get("DUE_STATE")).isEqualTo("Просрочено");
        assertThat((String) row.get("PERIOD_COMMENTS")).contains("05.09.2026: Назначена встреча").contains("10.09.2026: ")
                .doesNotContain("Перед периодом").doesNotContain("После периода");
        assertThat((Long) row.get("OVERDUE_DAYS")).isPositive();
        var total = rows.stream().filter(item -> item.get("INTERACTION") == null && "Анна Кузнецова".equals(item.get("MANAGER")))
                .findFirst().orElseThrow();
        assertThat(total.get("TRANSITIONS")).isEqualTo(1L);
        assertThat((String) total.get("DUE_STATE")).isEqualTo("просрочено 1, на этой неделе 0, без шага или срока 1");
    }

    @Test
    void managerWorkReportRespectsScopeAndRejectsUnsupportedFilters() {
        assertThat(reportService.preview(MANAGER_A_PROFILE, request(null, null), 0, 50).total()).isEqualTo(3);
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
                        .isEqualTo("Менеджер");
                var cells = new java.util.ArrayList<String>();
                workbook.getSheetAt(0).forEach(row -> {
                    if (row.getCell(1) != null && row.getCell(1).toString().startsWith("Итого по менеджеру")) {
                        row.forEach(cell -> cells.add(cell.toString()));
                    }
                });
                assertThat(cells).isNotEmpty().doesNotContain("Не указано");
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
        assertThat(node.get("rows")).hasSize(5);
        assertThat(node.get("totals").get("organizations").asInt()).isEqualTo(2);
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
