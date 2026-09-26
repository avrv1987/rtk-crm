package ru.rtk.crm.audit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;
import ru.rtk.crm.report.ExcelReportWriter;

@Component
public class AuditExportWriter {
    private static final List<String> HEADERS = List.of(
            "Время (МСК)", "Автор", "Действие", "Объект", "Подробности", "Request ID"
    );
    private static final int[] WIDTHS = {18, 24, 34, 34, 60, 38};
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(AuditQuery.ZONE);
    private static final char CSV_SEPARATOR = ';';

    byte[] xlsx(List<AuditEntry> entries) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Журнал");
            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            CellStyle quoted = workbook.createCellStyle();
            quoted.setQuotePrefixed(true);
            Row headerRow = sheet.createRow(0);
            for (int column = 0; column < HEADERS.size(); column++) {
                headerRow.createCell(column).setCellValue(HEADERS.get(column));
                headerRow.getCell(column).setCellStyle(header);
                sheet.setColumnWidth(column, WIDTHS[column] * 256);
            }
            int rowIndex = 1;
            for (AuditEntry entry : entries) {
                Row row = sheet.createRow(rowIndex++);
                List<String> values = values(entry);
                for (int column = 0; column < values.size(); column++) {
                    String value = values.get(column);
                    row.createCell(column).setCellValue(value);
                    if (ExcelReportWriter.isFormulaLike(value)) {
                        row.getCell(column).setCellStyle(quoted);
                    }
                }
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, HEADERS.size() - 1));
            workbook.write(output);
            workbook.dispose();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Audit journal cannot be written as XLSX", exception);
        }
    }

    byte[] csv(List<AuditEntry> entries) {
        StringBuilder csv = new StringBuilder("﻿");
        line(csv, HEADERS);
        for (AuditEntry entry : entries) {
            line(csv, values(entry));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> values(AuditEntry entry) {
        return List.of(
                DATE_TIME.format(entry.occurredAt()),
                text(entry.actorDisplayName()),
                entry.actionLabel(),
                text(entry.objectName()),
                text(entry.details()),
                text(entry.requestId())
        );
    }

    private static void line(StringBuilder csv, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                csv.append(CSV_SEPARATOR);
            }
            String value = values.get(index);
            String safe = ExcelReportWriter.isFormulaLike(value) ? "'" + value : value;
            csv.append('"').append(safe.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
