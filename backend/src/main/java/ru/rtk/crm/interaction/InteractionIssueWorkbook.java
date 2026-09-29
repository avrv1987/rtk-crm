package ru.rtk.crm.interaction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
public class InteractionIssueWorkbook {
    private static final List<String> HEADERS = List.of(
            "Вид", "Уровень", "Описание", "Работа", "Вуз", "КАМ", "Ответственный", "Срок", "Возраст, дней",
            "Статус", "Отметил", "Отмечено (МСК)", "Решение"
    );
    private static final int[] WIDTHS = {12, 12, 50, 34, 34, 24, 24, 12, 14, 12, 24, 18, 40};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    byte[] xlsx(List<InteractionIssue> issues, LocalDate today) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Проблемы и риски");
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
            for (InteractionIssue issue : issues) {
                Row row = sheet.createRow(rowIndex++);
                List<String> values = values(issue, today);
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
            throw new UncheckedIOException("Issue registry cannot be written as XLSX", exception);
        }
    }

    private static List<String> values(InteractionIssue issue, LocalDate today) {
        LocalDate createdOn = issue.createdAt().atZoneSameInstant(InteractionIssueService.ZONE).toLocalDate();
        LocalDate endOn = issue.resolvedAt() == null
                ? today
                : issue.resolvedAt().atZoneSameInstant(InteractionIssueService.ZONE).toLocalDate();
        return List.of(
                issue.kind().label(),
                issue.riskLevel() == null ? "" : issue.riskLevel().label(),
                issue.description(),
                issue.interactionTitle(),
                issue.organizationName(),
                issue.ownerManagerName() == null ? "Требует назначения" : issue.ownerManagerName(),
                issue.responsibleName(),
                issue.dueOn() == null ? "" : DATE.format(issue.dueOn()),
                Long.toString(ChronoUnit.DAYS.between(createdOn, endOn)),
                issue.status() == InteractionIssueStatus.OPEN ? "Открыта" : "Решена",
                issue.createdByName(),
                DATE_TIME.format(issue.createdAt().atZoneSameInstant(InteractionIssueService.ZONE)),
                issue.resolution() == null ? "" : issue.resolution()
        );
    }
}
