package ru.rtk.crm.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
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
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.module.ModuleProperties;
import ru.rtk.crm.report.ExcelReportWriter;
import ru.rtk.crm.report.JsonReportWriter;
import ru.rtk.crm.report.LearningDynamicsService;
import ru.rtk.crm.report.PdfReportWriter;
import ru.rtk.crm.report.ReportColumn;
import ru.rtk.crm.report.ReportDocument;
import ru.rtk.crm.report.ReportFormat;
import ru.rtk.crm.report.ReportKind;
import ru.rtk.crm.report.ReportPreview;
import ru.rtk.crm.report.ReportProperties;
import ru.rtk.crm.report.ReportRepository;
import ru.rtk.crm.report.ReportRequest;
import ru.rtk.crm.report.ReportScenario;
import ru.rtk.crm.report.ReportService;
import ru.rtk.crm.report.SigningPlanService;
import ru.rtk.crm.work.LearningTrendService;
import ru.rtk.crm.work.LmsLearningTestBeans;
import ru.rtk.crm.work.LmsSignalService;

// Сверка «экран = файл = MCP» для индекса востребованности (М17): одно и то же место, индекс, вклад компонентов и
// покрытие в предпросмотре, XLSX, XLS, PDF, JSON и инструменте program_demand.
@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:demand_parity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        ReportRepository.class,
        ReportService.class,
        LmsLearningTestBeans.class,
        ru.rtk.crm.agreement.AgreementRepository.class,
        ru.rtk.crm.agreement.AgreementApproval.class,
        ru.rtk.crm.agreement.AgreementStages.class,
        DemandIndexParityTest.PropertiesConfiguration.class
})
class DemandIndexParityTest {
    private static final List<ReportColumn> COMPARED = List.of(ReportColumn.PROGRAM, ReportColumn.DEMAND_RANK,
            ReportColumn.DEMAND_INDEX, ReportColumn.DEMAND_CONTRIBUTIONS, ReportColumn.DEMAND_COVERAGE, ReportColumn.UNIVERSITY_DEMAND);

    @Autowired
    private ReportService reportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AnalyticsTools tools;

    @BeforeEach
    void setUp() {
        ReportScenario.prepare(jdbcTemplate);
        ReportScenario.insertDemand(jdbcTemplate);
        observe("external_key = '2'", 4, 1, 5);
        observe("external_key = '5:51'", 1, 1, 1);
        observe("program_id IN (SELECT id FROM programs WHERE name = 'Анализ данных')", 0, 3, 1);
        tools = new AnalyticsTools(mock(LearningDynamicsService.class), mock(LmsSignalService.class), mock(LearningTrendService.class),
                reportService, mock(SigningPlanService.class), LmsLearningTestBeans.PROPERTIES,
                new McpProperties(true, "crm-mcp", List.of(), 60, 25, 10, 90, "crm-mcp.write", Duration.ofHours(24), 10, 50, Map.of()), new ru.rtk.crm.settings.TestSystemSettings());
    }

    @Test
    void screenFilesAndMcpToolShowTheSamePlacesIndexesAndContributions() throws IOException {
        ReportRequest request = new ReportRequest(ReportKind.DEMAND, null, null, null, null, null, ReportFormat.XLSX, null, null);
        ReportPreview screen = reportService.preview(ReportScenario.LEADER_A_PROFILE, request, 0, 50);
        List<List<String>> expected = screen.items().stream()
                .map(row -> COMPARED.stream().map(column -> text(row.get(column.name()))).toList())
                .toList();
        assertThat(expected).extracting(row -> row.get(1)).containsExactly("1", "2", "");
        assertThat(expected).extracting(row -> row.get(2)).containsExactly("100.0", "0.0", "");

        McpTools.Definition definition = tools.definitions().stream()
                .filter(item -> item.name().equals("program_demand")).findFirst().orElseThrow();
        assertThat(definition.roles()).containsExactlyInAnyOrder(UserRole.LEADER, UserRole.MANAGEMENT);
        Map<?, ?> data = (Map<?, ?>) definition.handler()
                .apply(new McpCaller(ReportScenario.LEADER_A_PROFILE, "test", Set.of(), "request"), new McpTools.Args(Map.of()))
                .data();
        List<?> mcpRows = ((McpTools.Page<?>) data.get("rows")).items();
        assertThat(mcpRows.stream().map(row -> COMPARED.stream().map(column -> text(((Map<?, ?>) row).get(column.name()))).toList())
                .toList()).isEqualTo(expected);

        ReportDocument document = reportService.document(ReportScenario.LEADER_A_PROFILE, request);
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        ByteArrayOutputStream json = new ByteArrayOutputStream();
        new JsonReportWriter(mapper).write(document, json);
        JsonNode rows = mapper.readTree(json.toByteArray()).path("rows");
        List<List<String>> fromJson = new ArrayList<>();
        rows.forEach(row -> fromJson.add(COMPARED.stream()
                .map(column -> row.path(column.name()).isNull() ? "" : row.path(column.name()).asText()).toList()));
        assertThat(fromJson).isEqualTo(expected);

        for (ReportFormat format : List.of(ReportFormat.XLSX, ReportFormat.XLS)) {
            ByteArrayOutputStream excel = new ByteArrayOutputStream();
            new ExcelReportWriter().write(document, format, excel);
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excel.toByteArray()))) {
                assertThat(excelRows(workbook.getSheetAt(0), document)).as(format.name()).isEqualTo(expected);
            }
        }

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        new PdfReportWriter().write(document, pdf);
        try (PDDocument loaded = Loader.loadPDF(pdf.toByteArray())) {
            String text = new PDFTextStripper().getText(loaded).replace("\n", " ").replace("\r", " ");
            assertThat(text).contains("Место").contains("100,0").contains("данных недостаточно");
        }
    }

    // Пустая ячейка файла — текст «данных недостаточно» или пусто; число — как на экране, без форматирования.
    private static List<List<String>> excelRows(Sheet sheet, ReportDocument document) {
        int header = document.notes().size() + 2;
        Row titles = sheet.getRow(header);
        List<Integer> indexes = COMPARED.stream().map(column -> {
            for (Cell cell : titles) {
                if (cell.getStringCellValue().equals(document.columnTitle(column))) {
                    return cell.getColumnIndex();
                }
            }
            throw new AssertionError("Нет колонки " + column);
        }).toList();
        List<List<String>> result = new ArrayList<>();
        for (int rowIndex = header + 1; rowIndex <= header + document.rows().size(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            List<String> values = new ArrayList<>();
            for (int position = 0; position < COMPARED.size(); position++) {
                Cell cell = row.getCell(indexes.get(position));
                ReportColumn column = COMPARED.get(position);
                if (cell.getCellType() == CellType.NUMERIC) {
                    double number = cell.getNumericCellValue();
                    values.add(column == ReportColumn.DEMAND_INDEX ? java.math.BigDecimal.valueOf(number).setScale(1).toPlainString()
                            : Long.toString((long) number));
                } else {
                    String text = new DataFormatter().formatCellValue(cell);
                    values.add(text.equals(column.emptyText()) ? "" : text);
                }
            }
            result.add(values);
        }
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private void observe(String mapping, int completed, int notCompleted, int active) {
        jdbcTemplate.update("UPDATE learning_observations SET completed_count = ?, not_completed_count = ?, active_7d_count = ?"
                + " WHERE mapping_id IN (SELECT id FROM source_mappings WHERE " + mapping + ")", completed, notCompleted, active);
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
