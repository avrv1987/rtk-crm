package ru.rtk.crm.catalogimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import ru.rtk.crm.interaction.InteractionValidationException;

class CatalogImportWorkbookReaderTest {
    private final CatalogImportWorkbookReader reader = new CatalogImportWorkbookReader();

    @Test
    void readsRealXlsAndXlsxWorkbooks() throws IOException {
        assertWorkbook(new HSSFWorkbook(), "catalog.xls");
        assertWorkbook(new XSSFWorkbook(), "catalog.xlsx");
    }

    @Test
    void usesCachedFormulaValuesAndReportsFormulasWithoutThem() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Catalog");
        header(sheet, 0, "externalKey", "year");
        Row evaluated = sheet.createRow(1);
        evaluated.createCell(0).setCellValue("program:math");
        evaluated.createCell(1).setCellFormula("2000+27");
        XSSFFormulaEvaluator.evaluateAllFormulaCells((XSSFWorkbook) workbook);
        Row unevaluated = sheet.createRow(2);
        unevaluated.createCell(0).setCellValue("program:physics");
        unevaluated.createCell(1).setCellFormula("2000+28");

        CatalogImportWorkbookSheet parsed = reader.read(file(workbook, "catalog.xlsx"), "Catalog");

        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).values()).containsEntry("year", "2027");
        assertThat(parsed.rows().get(0).fieldErrors()).isEmpty();
        assertThat(parsed.rows().get(1).fieldErrors().get("year")).startsWith("Формула без сохранённого значения");
    }

    @Test
    void skipsEmptySheetsAndFindsHeadersBelowTitleRowsIgnoringFormattedTail() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        workbook.createSheet("Пустой");
        Sheet sheet = workbook.createSheet("Каталог");
        CellStyle filled = workbook.createCellStyle();
        filled.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
        filled.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        sheet.createRow(0).createCell(3).setCellStyle(filled);
        Row header = header(sheet, 2, "Название ВУЗа", "", "ПО");
        for (int index = 3; index < 60; index++) {
            header.createCell(index).setCellStyle(filled);
        }
        Row data = sheet.createRow(3);
        data.createCell(0).setCellValue("Университет");
        data.createCell(1).setCellValue("не используется");
        data.createCell(2).setCellValue("Платформа");
        Cell date = data.createCell(5);
        date.setCellValue(LocalDate.of(2027, 6, 30));
        CellStyle dateStyle = workbook.createCellStyle();
        dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd.mm.yyyy"));
        date.setCellStyle(dateStyle);
        for (int index = 4; index < 7_000; index++) {
            sheet.createRow(index).createCell(0).setCellStyle(filled);
        }
        MockMultipartFile file = file(workbook, "catalog.xlsx");

        CatalogImportInspectResponse inspected = reader.inspect(file);
        CatalogImportWorkbookSheet parsed = reader.read(file, "Каталог");

        assertThat(inspected.sheets()).singleElement().satisfies(value -> {
            assertThat(value.name()).isEqualTo("Каталог");
            assertThat(value.headers()).containsExactly("Название ВУЗа", "ПО");
        });
        assertThat(parsed.rows()).singleElement().satisfies(value -> {
            assertThat(value.rowNumber()).isEqualTo(4);
            assertThat(value.values()).containsOnlyKeys("Название ВУЗа", "ПО").containsEntry("ПО", "Платформа");
        });
    }

    @Test
    void readsDateCellsAsIsoDates() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Catalog");
        header(sheet, 0, "signed");
        Cell date = sheet.createRow(1).createCell(0);
        date.setCellValue(LocalDate.of(2027, 6, 30));
        CellStyle dateStyle = workbook.createCellStyle();
        dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd.mm.yyyy"));
        date.setCellStyle(dateStyle);

        CatalogImportWorkbookSheet parsed = reader.read(file(workbook, "catalog.xlsx"), "Catalog");

        assertThat(parsed.rows()).singleElement()
                .satisfies(value -> assertThat(value.values()).containsEntry("signed", "2027-06-30"));
    }

    @Test
    void limitsNonEmptyDataRowsAndNamesTheSheet() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Большой");
        header(sheet, 0, "name");
        for (int index = 1; index <= CatalogImportWorkbookReader.MAX_ROWS + 1; index++) {
            sheet.createRow(index).createCell(0).setCellValue("row " + index);
        }

        assertThatThrownBy(() -> reader.read(file(workbook, "catalog.xlsx"), "Большой"))
                .isInstanceOf(InteractionValidationException.class)
                .hasMessage("Лист «Большой»: больше 5000 строк с данными; разделите файл");
    }

    @Test
    void rejectsMoreThanFiftyColumnsWithSheetLocation() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Catalog");
        Row header = sheet.createRow(0);
        for (int index = 0; index < 51; index++) {
            header.createCell(index).setCellValue("column" + index);
        }

        assertThatThrownBy(() -> reader.inspect(file(workbook, "catalog.xlsx")))
                .isInstanceOf(InteractionValidationException.class)
                .hasMessage("Лист «Catalog», строка 1: больше 50 столбцов");
    }

    @Test
    void inspectOffersOnlySheetsWithUsableHeaders() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        header(workbook.createSheet("Справочник"), 0, "Код", "Код");
        header(workbook.createSheet("Каталог"), 0, "Название ВУЗа", "ПО");

        CatalogImportInspectResponse inspected = reader.inspect(file(workbook, "catalog.xlsx"));

        assertThat(inspected.sheets()).singleElement().satisfies(value -> assertThat(value.name()).isEqualTo("Каталог"));
    }

    private void assertWorkbook(Workbook workbook, String fileName) throws IOException {
        Sheet sheet = workbook.createSheet("Catalog");
        header(sheet, 0, "externalKey", "name");
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("direction:it");
        row.createCell(1).setCellValue("ИТ");

        MockMultipartFile file = file(workbook, fileName);
        CatalogImportInspectResponse inspected = reader.inspect(file);
        CatalogImportWorkbookSheet parsed = reader.read(file, "Catalog");

        assertThat(inspected.sheets()).singleElement().satisfies(value ->
                assertThat(value.headers()).containsExactly("externalKey", "name")
        );
        assertThat(parsed.rows()).singleElement().satisfies(value ->
                assertThat(value.values()).containsEntry("externalKey", "direction:it").containsEntry("name", "ИТ")
        );
    }

    private Row header(Sheet sheet, int rowIndex, String... values) {
        Row header = sheet.createRow(rowIndex);
        for (int index = 0; index < values.length; index++) {
            if (!values[index].isEmpty()) {
                header.createCell(index).setCellValue(values[index]);
            }
        }
        return header;
    }

    private MockMultipartFile file(Workbook workbook, String name) throws IOException {
        try (workbook; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            workbook.write(bytes);
            return new MockMultipartFile("file", name, "application/octet-stream", bytes.toByteArray());
        }
    }
}
