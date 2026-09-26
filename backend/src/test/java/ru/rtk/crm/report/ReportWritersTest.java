package ru.rtk.crm.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.util.CodePageUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ReportWritersTest {
    private static final List<ReportColumn> COLUMNS = List.of(
            ReportColumn.EVENT_AT,
            ReportColumn.ORGANIZATION,
            ReportColumn.PROGRAM,
            ReportColumn.COMMENT
    );
    private static final OffsetDateTime EVENT_AT = OffsetDateTime.parse("2026-09-10T09:30:00Z");

    private final ExcelReportWriter excelWriter = new ExcelReportWriter();
    private final PdfReportWriter pdfWriter = new PdfReportWriter();
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void xlsxAndXlsContainOnlySelectedColumnsAndKeepFormulaTextInert() throws IOException {
        ReportDocument document = document(List.of(row("Университет «Альфа»", ReportTestData.FORMULA_COMMENT)));

        for (ReportFormat format : List.of(ReportFormat.XLSX, ReportFormat.XLS)) {
            byte[] bytes = excel(document, format);
            assertThat(FileMagic.valueOf(bytes)).isEqualTo(format == ReportFormat.XLS ? FileMagic.OLE2 : FileMagic.OOXML);
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                assertThat(workbook).isInstanceOf(format == ReportFormat.XLS ? HSSFWorkbook.class : XSSFWorkbook.class);
                Sheet sheet = workbook.getSheetAt(0);
                int headerIndex = document.notes().size() + 2;
                Row header = sheet.getRow(headerIndex);
                assertThat(header.getLastCellNum()).isEqualTo((short) COLUMNS.size());
                assertThat(texts(header)).containsExactly("Дата события", "Вуз", "ИТ-программа", "Комментарий");

                Row data = sheet.getRow(headerIndex + 1);
                assertThat(data.getCell(0).getLocalDateTimeCellValue())
                        .isEqualTo(EVENT_AT.atZoneSameInstant(ReportRequest.ZONE).toLocalDateTime());
                assertThat(data.getCell(1).getStringCellValue()).isEqualTo("Университет «Альфа»");
                assertThat(data.getCell(2).getStringCellValue()).isEqualTo("Не указано");
                Cell comment = data.getCell(3);
                assertThat(comment.getCellType()).isEqualTo(CellType.STRING);
                assertThat(comment.getStringCellValue()).isEqualTo(ReportTestData.FORMULA_COMMENT);
                assertThat(comment.getCellStyle().getQuotePrefixed()).isTrue();
                assertThat(data.getCell(1).getCellStyle().getQuotePrefixed()).isFalse();
                assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("События взаимодействий за период");
            }
        }
    }

    @Test
    void xlsCarriesDocumentSummaryWithReportTitle() throws IOException {
        ReportDocument document = document(List.of(row("Вуз", "Текст")));

        try (HSSFWorkbook workbook = new HSSFWorkbook(new ByteArrayInputStream(excel(document, ReportFormat.XLS)))) {
            assertThat(workbook.getSummaryInformation()).isNotNull();
            assertThat(workbook.getSummaryInformation().getTitle()).isEqualTo(document.title());
            assertThat(workbook.getDocumentSummaryInformation()).isNotNull();
            assertThat(workbook.getSummaryInformation().getFirstSection().getCodepage()).isEqualTo(CodePageUtil.CP_UTF8);
            assertThat(workbook.getDocumentSummaryInformation().getFirstSection().getCodepage()).isEqualTo(CodePageUtil.CP_UTF8);
        }
    }

    @Test
    void xlsRefusesRowsBeyondSheetLimitInsteadOfTruncating() {
        ReportDocument document = document(Collections.nCopies(65_536, row("Вуз", "Текст")));

        assertThatThrownBy(() -> excel(document, ReportFormat.XLS))
                .isInstanceOfSatisfying(ReportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("REPORT_XLS_ROW_LIMIT"));
    }

    @Test
    void pdfContainsCyrillicTextAndRepeatsHeaderOnEveryPage() throws IOException {
        List<ReportRow> rows = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            rows.add(row("Университет «Альфа» № " + index, "Комментарий к встрече " + index));
        }
        rows.add(row("Институт с длинным комментарием", "Ж".repeat(6_000) + " 😀 конец"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        pdfWriter.write(document(rows), output);

        try (PDDocument pdf = Loader.loadPDF(output.toByteArray())) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(2);
            assertThat(pdf.getPage(0).getMediaBox().getWidth()).isGreaterThan(pdf.getPage(0).getMediaBox().getHeight());
            PDFTextStripper stripper = new PDFTextStripper();
            String all = stripper.getText(pdf);
            assertThat(all).contains("События взаимодействий за период", "Университет «Альфа» № 0",
                    "Университет «Альфа» № 119", "Не указано", "Фильтры: не заданы", "конец");
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(pdf);
                assertThat(text).contains("Комментарий", "ИТ-программа", "Стр. " + page);
                assertThat(text).contains("Период: не ограничен");
            }
        }
    }

    @Test
    void jsonCarriesSchemaFiltersColumnsRowsAndTotals() throws IOException {
        ReportDocument document = document(List.of(row("Университет «Альфа»", ReportTestData.FORMULA_COMMENT)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new JsonReportWriter(objectMapper).write(document, output);

        JsonNode json = objectMapper.readTree(output.toByteArray());
        assertThat(json.path("schemaVersion").asInt()).isEqualTo(JsonReportWriter.SCHEMA_VERSION);
        assertThat(json.path("kind").asText()).isEqualTo("EVENTS");
        assertThat(json.path("timezone").asText()).isEqualTo("Europe/Moscow");
        assertThat(json.path("filters").path("organizationIds").isArray()).isTrue();
        assertThat(json.path("columns")).extracting(node -> node.path("id").asText())
                .containsExactly("EVENT_AT", "ORGANIZATION", "PROGRAM", "COMMENT");
        JsonNode row = json.path("rows").get(0);
        assertThat(row.path("ORGANIZATION").asText()).isEqualTo("Университет «Альфа»");
        assertThat(row.path("PROGRAM").isNull()).isTrue();
        assertThat(row.path("COMMENT").asText()).isEqualTo(ReportTestData.FORMULA_COMMENT);
        assertThat(OffsetDateTime.parse(row.path("EVENT_AT").asText()).toInstant()).isEqualTo(EVENT_AT.toInstant());
        assertThat(json.path("totals").path("rows").asInt()).isEqualTo(1);
        assertThat(json.path("quality").path("withoutProgram").asInt()).isEqualTo(1);
    }

    @Test
    void demandFileWritesApplicationsAsNumbersAndMissingMoodleValuesAsNoData() throws IOException {
        ReportRequest request = new ReportRequest(ReportKind.DEMAND, null, null, null, null, null, ReportFormat.XLSX, null, null)
                .normalized();
        ReportRow row = new ReportRow(null, null, null, null, UUID.randomUUID(), "Программирование", UUID.randomUUID(),
                "Java-разработчик", List.of(), null, null, null, null, null, null, null, null, null, null, null, null,
                5L, null, null);
        ReportDocument document = new ReportDocument(request, OffsetDateTime.parse("2026-09-24T12:00:00+03:00"),
                List.of("Сформирован: 24.09.2026 12:00"), List.of(row));

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excel(document, ReportFormat.XLSX)))) {
            Sheet sheet = workbook.getSheetAt(0);
            int headerIndex = document.notes().size() + 2;
            assertThat(texts(sheet.getRow(headerIndex))).containsExactly(
                    "ИТ-направление", "ИТ-программа", "Заявки (сайт)", "Обучающиеся (Moodle)", "Параллельные потоки (Moodle)");
            Row data = sheet.getRow(headerIndex + 1);
            assertThat(data.getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(data.getCell(2).getNumericCellValue()).isEqualTo(5.0);
            assertThat(data.getCell(3).getStringCellValue()).isEqualTo("нет данных");
            assertThat(data.getCell(4).getStringCellValue()).isEqualTo("нет данных");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new JsonReportWriter(objectMapper).write(document, output);
        JsonNode json = objectMapper.readTree(output.toByteArray());
        assertThat(json.path("rows").get(0).path("APPLICATIONS").asLong()).isEqualTo(5);
        assertThat(json.path("rows").get(0).path("PARTICIPANTS").isNull()).isTrue();
        assertThat(json.path("totals").path("applications").asLong()).isEqualTo(5);
        assertThat(json.path("quality").path("noData")).extracting(JsonNode::asText)
                .containsExactly("PARTICIPANTS", "PARALLEL_RUNS");
    }

    private byte[] excel(ReportDocument document, ReportFormat format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        excelWriter.write(document, format, output);
        return output.toByteArray();
    }

    private static List<String> texts(Row row) {
        List<String> values = new ArrayList<>();
        row.forEach(cell -> values.add(cell.getStringCellValue()));
        return values;
    }

    private static ReportDocument document(List<ReportRow> rows) {
        ReportRequest request = new ReportRequest(ReportKind.EVENTS, null, null, null, null, COLUMNS, ReportFormat.XLSX, null, null)
                .normalized();
        return new ReportDocument(
                request,
                OffsetDateTime.parse("2026-09-24T12:00:00+03:00"),
                List.of("Сформирован: 24.09.2026 12:00", "Период: не ограничен", "Фильтры: не заданы"),
                rows
        );
    }

    private static ReportRow row(String organization, String comment) {
        return new ReportRow(
                UUID.randomUUID(),
                "Взаимодействие",
                UUID.randomUUID(),
                organization,
                null,
                null,
                null,
                null,
                List.of(),
                "Встреча",
                null,
                null,
                EVENT_AT,
                null,
                null,
                null,
                EVENT_AT,
                "Комментарий",
                null,
                comment,
                "Анна Кузнецова",
                null,
                null,
                null
        );
    }
}
