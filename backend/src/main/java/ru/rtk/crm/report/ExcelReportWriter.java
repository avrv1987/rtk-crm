package ru.rtk.crm.report;

import java.io.IOException;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.CodePageUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class ExcelReportWriter {
    private static final int STREAMING_WINDOW = 200;
    private static final int MAX_CELL_LENGTH = SpreadsheetVersion.EXCEL97.getMaxTextLength();

    public void write(ReportDocument document, ReportFormat format, OutputStream output) throws IOException {
        int headerRow = document.notes().size() + 2;
        int dataRowLimit = SpreadsheetVersion.EXCEL97.getMaxRows() - headerRow - 3;
        if (format == ReportFormat.XLS && document.rows().size() > dataRowLimit) {
            throw ReportException.xlsRowLimit(dataRowLimit);
        }
        Workbook workbook = format == ReportFormat.XLS ? legacyWorkbook(document) : new SXSSFWorkbook(STREAMING_WINDOW);
        try {
            fill(workbook, document, headerRow);
            workbook.write(output);
        } finally {
            if (workbook instanceof SXSSFWorkbook streaming) {
                streaming.dispose();
            }
            workbook.close();
        }
    }

    private static HSSFWorkbook legacyWorkbook(ReportDocument document) {
        HSSFWorkbook workbook = new HSSFWorkbook();
        workbook.createInformationProperties();
        workbook.getSummaryInformation().getFirstSection().setCodepage(CodePageUtil.CP_UTF8);
        workbook.getDocumentSummaryInformation().getFirstSection().setCodepage(CodePageUtil.CP_UTF8);
        workbook.getSummaryInformation().setTitle(document.title());
        return workbook;
    }

    private void fill(Workbook workbook, ReportDocument document, int headerRow) {
        Styles styles = new Styles(workbook);
        Sheet sheet = workbook.createSheet("Отчёт");
        List<ReportColumn> columns = document.columns();
        int lastColumn = Math.max(columns.size() - 1, 0);

        text(sheet.createRow(0).createCell(0), document.title(), styles.title);
        for (int index = 0; index < document.notes().size(); index++) {
            text(sheet.createRow(index + 1).createCell(0), document.notes().get(index), styles.note);
        }

        Row header = sheet.createRow(headerRow);
        for (int column = 0; column < columns.size(); column++) {
            ReportColumn reportColumn = columns.get(column);
            text(header.createCell(column), document.columnTitle(reportColumn), styles.header);
            sheet.setColumnWidth(column, Math.min(reportColumn.width(), 80) * 256);
        }

        int rowIndex = headerRow + 1;
        for (ReportRow reportRow : document.rows()) {
            Row row = sheet.createRow(rowIndex++);
            for (int column = 0; column < columns.size(); column++) {
                value(row.createCell(column), columns.get(column), reportRow, styles);
            }
        }

        Row total = sheet.createRow(rowIndex + 1);
        text(total.createCell(0), "Строк в отчёте: " + document.rows().size(), styles.note);

        sheet.createFreezePane(0, headerRow + 1);
        sheet.setAutoFilter(new CellRangeAddress(headerRow, Math.max(headerRow, rowIndex - 1), 0, lastColumn));
    }

    private void value(Cell cell, ReportColumn column, ReportRow row, Styles styles) {
        Object value = column.value(row);
        if (value instanceof OffsetDateTime dateTime) {
            cell.setCellValue(dateTime.atZoneSameInstant(ReportRequest.ZONE).toLocalDateTime());
            cell.setCellStyle(styles.dateTime);
            return;
        }
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            cell.setCellStyle(styles.body);
            return;
        }
        String text = value == null ? column.emptyText() : value.toString();
        text(cell, text, isFormulaLike(text) ? styles.quoted : styles.body);
    }

    private void text(Cell cell, String value, CellStyle style) {
        cell.setCellValue(value.length() > MAX_CELL_LENGTH ? value.substring(0, MAX_CELL_LENGTH - 1) + "…" : value);
        cell.setCellStyle(style);
    }

    public static boolean isFormulaLike(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }

    private static final class Styles {
        private final CellStyle title;
        private final CellStyle note;
        private final CellStyle header;
        private final CellStyle body;
        private final CellStyle quoted;
        private final CellStyle dateTime;

        private Styles(Workbook workbook) {
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 13);
            title = workbook.createCellStyle();
            title.setFont(titleFont);

            note = workbook.createCellStyle();

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            header = bordered(workbook);
            header.setFont(headerFont);
            header.setWrapText(true);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            body = bordered(workbook);
            body.setWrapText(true);

            quoted = bordered(workbook);
            quoted.setWrapText(true);
            quoted.setQuotePrefixed(true);

            dateTime = bordered(workbook);
            dateTime.setDataFormat(workbook.createDataFormat().getFormat("dd.mm.yyyy hh:mm"));
        }

        private static CellStyle bordered(Workbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setVerticalAlignment(VerticalAlignment.TOP);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            return style;
        }
    }
}
