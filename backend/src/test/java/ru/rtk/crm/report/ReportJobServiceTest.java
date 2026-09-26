package ru.rtk.crm.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static ru.rtk.crm.report.ReportTestData.LEADER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.MANAGER_B_PROFILE;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:report-jobs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        ReportRepository.class,
        ReportService.class,
        ReportJobRepository.class,
        ReportJobService.class,
        ReportStorage.class,
        ExcelReportWriter.class,
        PdfReportWriter.class,
        JsonReportWriter.class,
        StatisticsChartWriter.class,
        ReportJobServiceTest.JobConfiguration.class
})
class ReportJobServiceTest {
    @Autowired
    private ReportJobService reportJobService;

    @Autowired
    private ReportJobRepository reportJobRepository;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ReportStorage reportStorage;

    @Autowired
    private ExcelReportWriter excelWriter;

    @Autowired
    private PdfReportWriter pdfWriter;

    @Autowired
    private JsonReportWriter jsonWriter;

    @Autowired
    private StatisticsChartWriter chartWriter;

    @Autowired
    private ReportProperties reportProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        ReportTestData.createSchema(jdbcTemplate);
        ReportTestData.insertScenario(jdbcTemplate);
    }

    @Test
    void ownerDownloadsBuiltFileAndOtherManagerCannotSeeTheJob() throws IOException {
        UUID jobId = reportJobService.submit(MANAGER_A_PROFILE, request(ReportFormat.XLSX), "key-1").jobId();

        ReportJob job = reportJobService.get(MANAGER_A_PROFILE, jobId);
        assertThat(job.status()).isEqualTo(ReportJobStatus.SUCCEEDED);
        assertThat(job.resultReady()).isTrue();
        assertThat(job.rowCount()).isEqualTo(2);
        assertThat(job.fileName()).isEqualTo("Отчёт_портфель_2026-08-01_2026-09-30.xlsx");
        assertThat(job.chartType()).isNull();

        ReportJobService.ReportResult result = reportJobService.result(MANAGER_A_PROFILE, jobId);
        assertThat(result.format()).isEqualTo(ReportFormat.XLSX);
        try (InputStream input = Files.newInputStream(result.file());
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo(ReportKind.PORTFOLIO.title());
        }
        assertThat(reportJobService.recent(MANAGER_A_PROFILE)).extracting(ReportJob::id).containsExactly(jobId);

        assertThatThrownBy(() -> reportJobService.get(MANAGER_B_PROFILE, jobId))
                .isInstanceOfSatisfying(ReportException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> reportJobService.result(MANAGER_B_PROFILE, jobId))
                .isInstanceOfSatisfying(ReportException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(reportJobService.recent(MANAGER_B_PROFILE)).isEmpty();
    }

    @Test
    void chartPngAndPdfAreBuiltFromTheSameStatisticsAsTheApi() throws IOException {
        StatisticsResult statistics = reportService.statistics(
                LEADER_A_PROFILE,
                chart(StatisticsGroupBy.PRODUCT, null).statisticsRequest()
        );
        assertThat(statistics.items()).extracting(StatisticsResult.Item::label, StatisticsResult.Item::count)
                .containsExactly(tuple("Продукт Икс", 2L), tuple("Продукт Игрек", 1L));
        assertThat(statistics.unknownCount()).isEqualTo(1);

        UUID pngJob = reportJobService.submit(
                LEADER_A_PROFILE, chart(StatisticsGroupBy.PRODUCT, ReportFormat.PNG), "chart-1").jobId();
        ReportJob png = reportJobService.get(LEADER_A_PROFILE, pngJob);
        assertThat(png.status()).isEqualTo(ReportJobStatus.SUCCEEDED);
        assertThat(png.groupBy()).isEqualTo(StatisticsGroupBy.PRODUCT);
        assertThat(png.chartType()).isEqualTo(ChartType.BAR);
        assertThat(png.seriesBy()).isNull();
        assertThat(png.rowCount()).isEqualTo(3);
        assertThat(png.fileName()).isEqualTo("Диаграмма_портфель_по_ИТ-продуктам_2026-08-01_2026-09-30.png");
        ReportJobService.ReportResult pngResult = reportJobService.result(LEADER_A_PROFILE, pngJob);
        assertThat(pngResult.format().mediaType()).isEqualTo("image/png");
        BufferedImage image = ImageIO.read(pngResult.file().toFile());
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isPositive();
        assertThat(image.getHeight()).isPositive();

        UUID pdfJob = reportJobService.submit(
                LEADER_A_PROFILE, chart(StatisticsGroupBy.PRODUCT, ReportFormat.PDF), "chart-2").jobId();
        assertThat(reportJobService.get(LEADER_A_PROFILE, pdfJob).fileName())
                .isEqualTo("Диаграмма_портфель_по_ИТ-продуктам_2026-08-01_2026-09-30.pdf");
        String text = pdfText(LEADER_A_PROFILE, pdfJob);
        List<String> lines = text.lines().map(String::strip).toList();
        assertThat(text).contains(
                "Портфель взаимодействий: текущее состояние — по ИТ-продуктам",
                "Показатель: число взаимодействий в группе; всего в выборке: 3",
                "«Не указано» (штриховка) — строки без значения группировки: 1",
                "Таблица основания диаграммы",
                "ИТ-продукт",
                "Число взаимодействий"
        );
        for (StatisticsResult.Item item : statistics.items()) {
            assertThat(lines).contains(item.label() + " " + item.count());
        }
        assertThat(lines).contains(
                "Не указано " + statistics.unknownCount(),
                "Всего строк в выборке " + statistics.total()
        );

        assertThatThrownBy(() -> reportJobService.result(MANAGER_B_PROFILE, pdfJob))
                .isInstanceOfSatisfying(ReportException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void monthlyLineChartWithSeriesIsRenderedAsPngAndPdfWithBasisTable() throws IOException {
        UUID pdfJob = reportJobService.submit(LEADER_A_PROFILE, line(ReportFormat.PDF, StatisticsGroupBy.PROGRAM), "line-1").jobId();

        ReportJob pdf = reportJobService.get(LEADER_A_PROFILE, pdfJob);
        assertThat(pdf.status()).isEqualTo(ReportJobStatus.SUCCEEDED);
        assertThat(pdf.fileName()).isEqualTo("График_события_по_месяцам_линии_по_ИТ-программам_2026-08-01_2026-10-31.pdf");
        assertThat(pdf.chartType()).isEqualTo(ChartType.LINE);
        assertThat(pdf.seriesBy()).isEqualTo(StatisticsGroupBy.PROGRAM);
        List<String> lines = pdfText(LEADER_A_PROFILE, pdfJob).lines().map(String::strip).toList();
        assertThat(lines).contains("Таблица основания графика", "август 2026 2 0 2", "сентябрь 2026 3 1 4", "октябрь 2026 1 0 1",
                "Итого за период 6 1 7", "Всего строк в выборке 7");
        assertThat(String.join(" ", lines)).contains("Java-разработчик", "Не указано", "Всего за месяц", "пунктирная");

        UUID pngJob = reportJobService.submit(LEADER_A_PROFILE, line(ReportFormat.PNG, null), "line-2").jobId();
        assertThat(reportJobService.get(LEADER_A_PROFILE, pngJob).fileName()).startsWith("График_события_по_месяцам_2026");
        BufferedImage image = ImageIO.read(reportJobService.result(LEADER_A_PROFILE, pngJob).file().toFile());
        assertThat(image.getWidth()).isEqualTo(1200);

        ReportRequest seriesOnBars = new ReportRequest(ReportKind.EVENTS, null, null, null, null, null, ReportFormat.PNG,
                StatisticsGroupBy.MONTH, null, null, ChartType.BAR, StatisticsGroupBy.PROGRAM);
        assertThatThrownBy(() -> reportJobService.submit(LEADER_A_PROFILE, seriesOnBars, "line-3"))
                .isInstanceOf(InteractionValidationException.class);
    }

    @Test
    void durationAndSnapshotFilesKeepNumbersAndDateInName() throws IOException {
        ReportRequest duration = new ReportRequest(ReportKind.DURATION, LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-20"), null, null, null, ReportFormat.XLSX, null, null, null, null, null);
        UUID durationJob = reportJobService.submit(LEADER_A_PROFILE, duration, "duration-1").jobId();

        assertThat(reportJobService.get(LEADER_A_PROFILE, durationJob).rowCount()).isEqualTo(8);
        try (InputStream input = Files.newInputStream(reportJobService.result(LEADER_A_PROFILE, durationJob).file());
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            int header = sheet.getLastRowNum() - 10;
            assertThat(sheet.getRow(header).getCell(4).getStringCellValue()).isEqualTo("Средняя длительность, дн.");
            assertThat(sheet.getRow(header + 1).getCell(4).getNumericCellValue()).isEqualTo(26.1);
            assertThat(sheet.getRow(header + 3).getCell(4).getStringCellValue()).isEqualTo("нет данных");
        }

        ReportRequest snapshot = new ReportRequest(ReportKind.SNAPSHOT, null, null, null, null, null, ReportFormat.JSON, null,
                null, LocalDate.parse("2026-09-10"), null, null);
        UUID snapshotJob = reportJobService.submit(LEADER_A_PROFILE, snapshot, "snapshot-1").jobId();
        assertThat(reportJobService.get(LEADER_A_PROFILE, snapshotJob).fileName()).isEqualTo("Отчёт_состояние_на_2026-09-10.json");
    }

    @Test
    void chartShowsOnlyOrganizationsVisibleToTheOwner() throws IOException {
        UUID leaderJob = reportJobService.submit(
                LEADER_A_PROFILE, chart(StatisticsGroupBy.ORGANIZATION, ReportFormat.PDF), "chart-3").jobId();
        String leaderText = pdfText(LEADER_A_PROFILE, leaderJob);
        assertThat(leaderText.lines().map(String::strip).toList())
                .contains("Университет «Альфа» 2", "Институт без КАМ 1", "Всего строк в выборке 3");
        assertThat(leaderText).doesNotContain("Университет «Бета»", "штриховка");

        UUID managerJob = reportJobService.submit(
                MANAGER_B_PROFILE, chart(StatisticsGroupBy.ORGANIZATION, ReportFormat.PDF), "chart-4").jobId();
        String managerText = pdfText(MANAGER_B_PROFILE, managerJob);
        assertThat(managerText.lines().map(String::strip).toList()).contains("Университет «Бета» 1");
        assertThat(managerText).doesNotContain("Университет «Альфа»", "Институт без КАМ");
    }

    @Test
    void pngIsOnlyForChartsAndChartsOnlyForPngOrPdf() {
        assertThatThrownBy(() -> reportJobService.submit(MANAGER_A_PROFILE, request(ReportFormat.PNG), "chart-5"))
                .isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> reportJobService.submit(
                MANAGER_A_PROFILE, chart(StatisticsGroupBy.STAGE, ReportFormat.XLSX), "chart-6"))
                .isInstanceOf(InteractionValidationException.class);
        ReportRequest activityByMonth = new ReportRequest(ReportKind.PORTFOLIO, LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-30"), PeriodBasis.ACTIVITY, null, null, ReportFormat.PNG, StatisticsGroupBy.MONTH, null);
        assertThatThrownBy(() -> reportJobService.submit(MANAGER_A_PROFILE, activityByMonth, "chart-7"))
                .isInstanceOf(InteractionValidationException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM report_jobs", Integer.class)).isZero();
    }

    @Test
    void changedAccessRevisionRevokesFinishedResult() {
        UUID jobId = reportJobService.submit(MANAGER_A_PROFILE, request(ReportFormat.JSON), "key-2").jobId();
        jdbcTemplate.update("UPDATE crm_user_profiles SET access_revision = 1 WHERE id = ?", MANAGER_A);
        CrmProfile afterReassignment = new CrmProfile(MANAGER_A, UserRole.USER, ReportTestData.TEAM_A, 1);

        assertThatThrownBy(() -> reportJobService.result(afterReassignment, jobId))
                .isInstanceOfSatisfying(ReportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("REPORT_ACCESS_CHANGED"));
        assertThatThrownBy(() -> reportJobService.get(afterReassignment, jobId))
                .isInstanceOfSatisfying(ReportException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.GONE));
        assertThat(reportJobService.recent(afterReassignment)).isEmpty();
    }

    @Test
    void accessChangedBeforeBuildFailsTheJobWithoutAFile() {
        ReportRequest request = request(ReportFormat.PDF).normalized();
        UUID jobId = UUID.randomUUID();
        reportJobRepository.insert(jobId, MANAGER_A_PROFILE, "key-3", "0".repeat(64), write(request), request,
                OffsetDateTime.now());
        jdbcTemplate.update("UPDATE crm_user_profiles SET access_revision = 1 WHERE id = ?", MANAGER_A);

        reportJobService.run(jobId);

        ReportJobRepository.StoredJob job = reportJobRepository.findById(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(ReportJobStatus.FAILED);
        assertThat(job.errorCode()).isEqualTo("REPORT_ACCESS_CHANGED");
        assertThat(job.resultStorageKey()).isNull();
    }

    @Test
    void repeatedKeyReturnsSameJobAndDifferentPayloadConflicts() {
        UUID first = reportJobService.submit(MANAGER_A_PROFILE, request(ReportFormat.XLS), "key-4").jobId();

        assertThat(reportJobService.submit(MANAGER_A_PROFILE, request(ReportFormat.XLS), "key-4").jobId()).isEqualTo(first);
        assertThatThrownBy(() -> reportJobService.submit(MANAGER_A_PROFILE, request(ReportFormat.PDF), "key-4"))
                .isInstanceOf(InteractionConflictException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM report_jobs", Integer.class)).isEqualTo(1);
    }

    @Test
    void saturatedPoolRejectsWithoutLeavingPendingJob() {
        ReportJobService saturated = new ReportJobService(
                reportService,
                reportJobRepository,
                new ReportJobExecutor(reportProperties) {
                    @Override
                    public void execute(Runnable task) {
                        throw new TaskRejectedException("Report slots are busy");
                    }
                },
                reportStorage,
                excelWriter,
                pdfWriter,
                jsonWriter,
                chartWriter,
                reportProperties,
                objectMapper
        );

        assertThatThrownBy(() -> saturated.submit(MANAGER_A_PROFILE, request(ReportFormat.XLSX), "key-5"))
                .isInstanceOfSatisfying(ReportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("REPORT_CAPACITY_EXCEEDED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM report_jobs", Integer.class)).isZero();
    }

    @Test
    void unfinishedJobsFromPreviousProcessFailAfterRestart() {
        ReportRequest request = request(ReportFormat.XLSX).normalized();
        UUID pending = UUID.randomUUID();
        UUID running = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        OffsetDateTime beforeStart = OffsetDateTime.now().minusHours(1);
        reportJobRepository.insert(pending, MANAGER_A_PROFILE, "old-1", "0".repeat(64), write(request), request, beforeStart);
        reportJobRepository.insert(running, MANAGER_A_PROFILE, "old-2", "0".repeat(64), write(request), request, beforeStart);
        reportJobRepository.claim(running, beforeStart);
        reportJobRepository.insert(current, MANAGER_A_PROFILE, "new-1", "0".repeat(64), write(request), request,
                OffsetDateTime.now().plusSeconds(1));

        reportJobService.failInterruptedJobs();

        assertThat(List.of(pending, running)).allSatisfy(id -> {
            ReportJobRepository.StoredJob job = reportJobRepository.findById(id).orElseThrow();
            assertThat(job.status()).isEqualTo(ReportJobStatus.FAILED);
            assertThat(job.errorCode()).isEqualTo("REPORT_INTERRUPTED");
        });
        assertThat(reportJobRepository.findById(current).orElseThrow().status()).isEqualTo(ReportJobStatus.PENDING);
    }

    private ReportRequest request(ReportFormat format) {
        return new ReportRequest(
                ReportKind.PORTFOLIO,
                LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-09-30"),
                PeriodBasis.CREATED,
                null,
                null,
                format,
                null,
                null
        );
    }

    private ReportRequest chart(StatisticsGroupBy groupBy, ReportFormat format) {
        return new ReportRequest(
                ReportKind.PORTFOLIO,
                LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-09-30"),
                PeriodBasis.CREATED,
                null,
                null,
                format,
                groupBy,
                null
        );
    }

    private ReportRequest line(ReportFormat format, StatisticsGroupBy seriesBy) {
        return new ReportRequest(ReportKind.EVENTS, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-10-31"), null, null,
                null, format, StatisticsGroupBy.MONTH, null, null, ChartType.LINE, seriesBy);
    }

    private String pdfText(CrmProfile profile, UUID jobId) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(reportJobService.result(profile, jobId).file().toFile())) {
            return new PDFTextStripper().getText(pdf);
        }
    }

    private String write(ReportRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration
    static class JobConfiguration {
        @Bean
        ReportProperties reportProperties() throws IOException {
            Path root = Files.createTempDirectory("report-jobs-test");
            return new ReportProperties(root, 10, 5, 1_000, 1_000);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder()
                    .findAndAddModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();
        }

        @Bean
        ReportJobExecutor reportJobExecutor(ReportProperties properties) {
            return new ReportJobExecutor(properties) {
                @Override
                public void execute(Runnable task) {
                    task.run();
                }
            };
        }
    }
}
