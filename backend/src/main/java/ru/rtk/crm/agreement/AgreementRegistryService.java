package ru.rtk.crm.agreement;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.agreement.AgreementModels.PeriodReport;
import ru.rtk.crm.agreement.AgreementModels.PeriodTotals;
import ru.rtk.crm.agreement.AgreementModels.PeriodTransition;
import ru.rtk.crm.agreement.AgreementModels.RegistryQuery;
import ru.rtk.crm.agreement.AgreementModels.RegistryRow;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.ExcelReportWriter;
import ru.rtk.crm.report.PdfLayout;
import ru.rtk.crm.report.ReportFormat;

@Service
public class AgreementRegistryService {
    public static final String REGISTRY_TITLE = "Реестр договоров с вузами";
    public static final String PERIOD_TITLE = "Договоры с вузами за период";
    public static final int DEFAULT_REVIEW_DAYS = 30;

    private static final int MAX_REVIEW_DAYS = 3650;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final List<String> REGISTRY_COLUMNS = List.of("Номер", "Вуз", "Статус", "Дней в статусе", "Отправлен на согласование",
            "Подписан", "Действует до", "Предмет", "Менеджер");
    private static final int[] REGISTRY_WIDTHS = {12, 28, 16, 10, 14, 12, 12, 34, 20};
    private static final List<String> TRANSITION_COLUMNS = List.of("Дата и время (МСК)", "Вуз", "Договор", "Из статуса", "В статус",
            "Кто", "Комментарий");
    private static final int[] TRANSITION_WIDTHS = {14, 26, 12, 16, 16, 18, 34};

    private final OrganizationRepository organizationRepository;
    private final AgreementRepository repository;

    public AgreementRegistryService(OrganizationRepository organizationRepository, AgreementRepository repository) {
        this.organizationRepository = organizationRepository;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<RegistryRow> registry(CrmProfile profile, RegistryQuery query) {
        AgreementService.requireRegistryReader(profile);
        Integer longer = reviewDays(query.reviewLongerThanDays(), null);
        OffsetDateTime now = OffsetDateTime.now();
        LocalDate today = LocalDate.now(AgreementService.ZONE);
        return organizationRepository.visibilityScope(profile)
                .map(scope -> repository.findRegistry(scope, query, longer == null ? null : now.minusDays(longer), today))
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public PeriodReport period(CrmProfile profile, LocalDate requestedFrom, LocalDate requestedTo, Integer requestedDays) {
        AgreementService.requireRegistryReader(profile);
        LocalDate today = LocalDate.now(AgreementService.ZONE);
        LocalDate from = requestedFrom == null ? today.withDayOfYear(1) : requestedFrom;
        LocalDate to = requestedTo == null ? today : requestedTo;
        if (from.isAfter(to)) {
            throw new InteractionValidationException("to", "Дата окончания периода раньше даты начала");
        }
        int days = reviewDays(requestedDays, DEFAULT_REVIEW_DAYS);
        OffsetDateTime generatedAt = OffsetDateTime.now();
        OffsetDateTime start = from.atStartOfDay(AgreementService.ZONE).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(AgreementService.ZONE).toOffsetDateTime();
        VisibilityScope scope = organizationRepository.visibilityScope(profile).orElse(null);
        List<PeriodTransition> transitions = scope == null ? List.of() : repository.findTransitionsBetween(scope, start, end);
        List<RegistryRow> signed = scope == null ? List.of() : repository.findSignedBetween(scope, from, to, today);
        List<RegistryRow> terminated = scope == null ? List.of() : repository.findTerminatedBetween(scope, start, end, today);
        List<RegistryRow> longReview = scope == null ? List.of() : repository.findRegistry(
                scope, new RegistryQuery(null, null, null, null, days), generatedAt.minusDays(days), today, true);
        List<Long> approval = signed.stream()
                .filter(row -> row.sentOn() != null && row.concludedOn() != null && !row.sentOn().isAfter(row.concludedOn()))
                .map(row -> row.concludedOn().toEpochDay() - row.sentOn().toEpochDay())
                .toList();
        Double average = approval.isEmpty() ? null
                : Math.round(approval.stream().mapToLong(Long::longValue).average().orElse(0) * 10) / 10.0;
        PeriodTotals totals = new PeriodTotals(transitions.size(), signed.size(), terminated.size(), average, approval.size(),
                longReview.size());
        return new PeriodReport(generatedAt, from, to, days, periodNotes(generatedAt, from, to, days), totals, transitions, signed,
                terminated, longReview);
    }

    public static String registryFileName(LocalDate today) {
        return "Реестр_договоров_" + today + ".xlsx";
    }

    public static String periodFileName(PeriodReport report, ReportFormat format) {
        return "Договоры_" + report.from() + "_" + report.to() + "." + format.extension();
    }

    public void writeRegistry(List<RegistryRow> rows, OffsetDateTime generatedAt, OutputStream output) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = styles(workbook);
            Sheet sheet = workbook.createSheet("Реестр");
            int header = titleBlock(sheet, styles, REGISTRY_TITLE, List.of("Сформирован: " + dateTime(generatedAt) + " (МСК)",
                    "Вузы вашей области доступа; строк: " + rows.size()));
            table(sheet, styles, header, REGISTRY_COLUMNS, REGISTRY_WIDTHS, rows.stream().map(AgreementRegistryService::registryValues).toList());
            workbook.write(output);
        }
    }

    public void writePeriod(PeriodReport report, ReportFormat format, OutputStream output) throws IOException {
        if (format == ReportFormat.PDF) {
            PdfLayout.write(PERIOD_TITLE, report.generatedAt(), report.notes(), output, layout -> {
                layout.paragraph(totalsLine(report.totals()), PdfLayout.NOTE_SIZE);
                pdfSection(layout, "Переходы статусов", TRANSITION_COLUMNS, TRANSITION_WIDTHS,
                        report.transitions().stream().map(AgreementRegistryService::transitionValues).toList());
                pdfSection(layout, "Подписанные", REGISTRY_COLUMNS, REGISTRY_WIDTHS,
                        report.signed().stream().map(AgreementRegistryService::registryValues).toList());
                pdfSection(layout, "Расторгнутые", REGISTRY_COLUMNS, REGISTRY_WIDTHS,
                        report.terminated().stream().map(AgreementRegistryService::registryValues).toList());
                pdfSection(layout, "На согласовании дольше " + report.longerThanDays() + " дн.", REGISTRY_COLUMNS, REGISTRY_WIDTHS,
                        report.longReview().stream().map(AgreementRegistryService::registryValues).toList());
            });
            return;
        }
        if (format != ReportFormat.XLSX) {
            throw new InteractionValidationException("format", "Отчёт по договорам выгружается в XLSX или PDF");
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = styles(workbook);
            Sheet summary = workbook.createSheet("Итоги");
            int position = titleBlock(summary, styles, PERIOD_TITLE, report.notes());
            List<List<String>> totals = List.of(
                    List.of("Переходов статусов", Integer.toString(report.totals().transitions())),
                    List.of("Подписано", Integer.toString(report.totals().signed())),
                    List.of("Расторгнуто", Integer.toString(report.totals().terminated())),
                    List.of("Среднее время согласования, дней", average(report.totals())),
                    List.of("На согласовании дольше " + report.longerThanDays() + " дн.", Integer.toString(report.totals().longReview()))
            );
            table(summary, styles, position, List.of("Показатель", "Значение"), new int[] {36, 14}, totals);
            sheet(workbook, styles, "Переходы", TRANSITION_COLUMNS, TRANSITION_WIDTHS,
                    report.transitions().stream().map(AgreementRegistryService::transitionValues).toList());
            sheet(workbook, styles, "Подписанные", REGISTRY_COLUMNS, REGISTRY_WIDTHS,
                    report.signed().stream().map(AgreementRegistryService::registryValues).toList());
            sheet(workbook, styles, "Расторгнутые", REGISTRY_COLUMNS, REGISTRY_WIDTHS,
                    report.terminated().stream().map(AgreementRegistryService::registryValues).toList());
            sheet(workbook, styles, "Долго на согласовании", REGISTRY_COLUMNS, REGISTRY_WIDTHS,
                    report.longReview().stream().map(AgreementRegistryService::registryValues).toList());
            workbook.write(output);
        }
    }

    private static Integer reviewDays(Integer requested, Integer fallback) {
        if (requested == null) {
            return fallback;
        }
        if (requested < 1 || requested > MAX_REVIEW_DAYS) {
            throw new InteractionValidationException("reviewLongerThanDays", "Число дней — от 1 до " + MAX_REVIEW_DAYS);
        }
        return requested;
    }

    private static List<String> periodNotes(OffsetDateTime generatedAt, LocalDate from, LocalDate to, int days) {
        return List.of(
                "Сформирован: " + dateTime(generatedAt) + " (МСК)",
                "Период: " + DATE.format(from) + " – " + DATE.format(to) + "; вузы вашей области доступа",
                "Подписанные — договоры с датой подписания в периоде. Среднее время согласования — от даты отправки на согласование"
                        + " до даты подписания по этим договорам",
                "Долго на согласовании — сейчас в статусе «На согласовании» дольше " + days + " дн."
        );
    }

    private static String totalsLine(PeriodTotals totals) {
        return "Переходов: " + totals.transitions() + "; подписано: " + totals.signed() + "; расторгнуто: " + totals.terminated()
                + "; среднее время согласования, дней: " + average(totals) + "; долго на согласовании: " + totals.longReview();
    }

    private static String average(PeriodTotals totals) {
        return totals.averageApprovalDays() == null ? "нет данных"
                : String.format(Locale.ROOT, "%.1f", totals.averageApprovalDays()).replace('.', ',');
    }

    private static List<String> registryValues(RegistryRow row) {
        return List.of(row.number(), row.organizationName(), row.status().title(), Long.toString(row.daysInStatus()), date(row.sentOn()),
                date(row.concludedOn()), date(row.validUntil()), row.subject() == null ? "" : row.subject(),
                row.managerName() == null ? "Не назначен" : row.managerName());
    }

    private static List<String> transitionValues(PeriodTransition row) {
        return List.of(dateTime(row.occurredAt()), row.organizationName(), row.number(),
                row.fromStatus() == null ? "" : row.fromStatus().title(), row.toStatus().title(),
                row.actorName() == null ? "" : row.actorName(), row.comment() == null ? "" : row.comment());
    }

    private static void pdfSection(PdfLayout layout, String title, List<String> columns, int[] widths, List<List<String>> rows)
            throws IOException {
        layout.gap(PdfLayout.NOTE_SIZE);
        layout.paragraph(title + " (" + rows.size() + ")", PdfLayout.NOTE_SIZE);
        if (rows.isEmpty()) {
            layout.paragraph("Нет строк", PdfLayout.NOTE_SIZE);
            return;
        }
        layout.table(columns, widths);
        for (List<String> row : rows) {
            layout.row(row);
        }
    }

    private static void sheet(XSSFWorkbook workbook, Styles styles, String name, List<String> columns, int[] widths,
                              List<List<String>> rows) {
        Sheet sheet = workbook.createSheet(name);
        table(sheet, styles, 0, columns, widths, rows);
    }

    private static int titleBlock(Sheet sheet, Styles styles, String title, List<String> notes) {
        Cell titleCell = sheet.createRow(0).createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(styles.header());
        for (int index = 0; index < notes.size(); index++) {
            sheet.createRow(index + 1).createCell(0).setCellValue(notes.get(index));
        }
        return notes.size() + 2;
    }

    private static void table(Sheet sheet, Styles styles, int headerRow, List<String> columns, int[] widths, List<List<String>> rows) {
        Row header = sheet.createRow(headerRow);
        for (int column = 0; column < columns.size(); column++) {
            Cell cell = header.createCell(column);
            cell.setCellValue(columns.get(column));
            cell.setCellStyle(styles.header());
            sheet.setColumnWidth(column, widths[column] * 512);
        }
        int index = headerRow + 1;
        for (List<String> values : rows) {
            Row cells = sheet.createRow(index++);
            for (int column = 0; column < values.size(); column++) {
                Cell cell = cells.createCell(column);
                cell.setCellValue(values.get(column));
                if (ExcelReportWriter.isFormulaLike(values.get(column))) {
                    cell.setCellStyle(styles.quoted());
                }
            }
        }
        sheet.createFreezePane(0, headerRow + 1);
        sheet.setAutoFilter(new CellRangeAddress(headerRow, Math.max(headerRow, index - 1), 0, columns.size() - 1));
    }

    private static Styles styles(XSSFWorkbook workbook) {
        Font bold = workbook.createFont();
        bold.setBold(true);
        CellStyle header = workbook.createCellStyle();
        header.setFont(bold);
        header.setWrapText(true);
        CellStyle quoted = workbook.createCellStyle();
        quoted.setQuotePrefixed(true);
        return new Styles(header, quoted);
    }

    private static String date(LocalDate value) {
        return value == null ? "" : DATE.format(value);
    }

    private static String dateTime(OffsetDateTime value) {
        return DATE_TIME.format(value.atZoneSameInstant(AgreementService.ZONE));
    }

    private record Styles(CellStyle header, CellStyle quoted) {
    }
}
