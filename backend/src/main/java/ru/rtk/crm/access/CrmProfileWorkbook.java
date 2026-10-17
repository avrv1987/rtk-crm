package ru.rtk.crm.access;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;
import ru.rtk.crm.report.ExcelReportWriter;

@Component
public class CrmProfileWorkbook {
    private static final List<String> HEADERS = List.of(
            "Сотрудник", "Логин", "Роль", "Роль в Keycloak", "Команда или вуз", "Состояние", "Оператор зачисления",
            "Синхронизация с Keycloak", "Последний вход (МСК)"
    );
    private static final int[] WIDTHS = {34, 20, 20, 20, 30, 20, 20, 30, 20};
    private static final Map<UserRole, String> ROLES = Map.of(
            UserRole.USER, "КАМ",
            UserRole.LEADER, "Руководитель",
            UserRole.ADMIN, "Администратор",
            UserRole.MANAGEMENT, "Руководство",
            UserRole.PARTNER, "Представитель вуза"
    );
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Moscow"));

    byte[] xlsx(List<AdminCrmProfile> profiles) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Профили CRM");
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
            for (AdminCrmProfile profile : profiles) {
                Row row = sheet.createRow(rowIndex++);
                List<String> values = values(profile);
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
            throw new UncheckedIOException("CRM profiles cannot be written as XLSX", exception);
        }
    }

    private static List<String> values(AdminCrmProfile profile) {
        return List.of(
                profile.displayName(),
                text(profile.login()),
                ROLES.get(profile.role()),
                profile.roleSyncedAt() != null ? "подтверждена" : profile.pendingActivation() ? "" : "не перенесена",
                profile.role() == UserRole.PARTNER ? text(profile.partnerOrganizationName()) : text(profile.teamName()),
                profile.active() ? "Активен" : profile.pendingActivation() ? "Ожидает активации" : "Заблокирован",
                profile.enrolmentOperator() ? "да" : "",
                profile.roleReconcileRequired() ? "роль требует сверки"
                        : profile.accountSyncRequired() ? "требует синхронизации" : "",
                dateTime(profile.lastLoginAt())
        );
    }

    private static String dateTime(OffsetDateTime value) {
        return value == null ? "" : DATE_TIME.format(value);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
