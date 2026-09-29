package ru.rtk.crm.report;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

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
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.PlanKind;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.SigningPlan.Source;
import ru.rtk.crm.report.SigningPlan.State;
import ru.rtk.crm.report.SigningPlan.Totals;
import ru.rtk.crm.report.SigningPlanRepository.Candidate;

@Service
public class SigningPlanService {
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2100;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String NO_MANAGER = "Без ответственного КАМ";
    private static final List<String> COLUMNS = List.of("Вуз", "Команда", "КАМ", "Соглашение", "Вид", "Плановая дата", "Основание даты",
            "Статус");
    private static final int[] WIDTHS = {30, 16, 20, 14, 12, 12, 22, 12};
    private static final List<String> TOTAL_COLUMNS = List.of("Название", "Команда", "Подписаний", "Продлений", "Выполнено",
            "Просрочено", "Впереди");
    private static final int[] TOTAL_WIDTHS = {30, 16, 10, 10, 10, 10, 10};

    private final SigningPlanRepository repository;
    private final OrganizationRepository organizationRepository;

    public SigningPlanService(SigningPlanRepository repository, OrganizationRepository organizationRepository) {
        this.repository = repository;
        this.organizationRepository = organizationRepository;
    }

    @Transactional(readOnly = true)
    public SigningPlan plan(CrmProfile profile, Integer year, Integer quarter) {
        LocalDate today = LocalDate.now(ReportRequest.ZONE);
        if (quarter != null && (quarter < 1 || quarter > 4)) {
            throw new InteractionValidationException("quarter", "Квартал — число от 1 до 4");
        }
        if (year != null && (year < MIN_YEAR || year > MAX_YEAR)) {
            throw new InteractionValidationException("year", "Год — от " + MIN_YEAR + " до " + MAX_YEAR);
        }
        Integer selectedQuarter = year == null && quarter == null ? Integer.valueOf((today.getMonthValue() - 1) / 3 + 1) : quarter;
        int selectedYear = year == null ? today.getYear() : year;
        LocalDate from = selectedQuarter == null ? LocalDate.of(selectedYear, 1, 1)
                : LocalDate.of(selectedYear, (selectedQuarter - 1) * 3 + 1, 1);
        LocalDate to = selectedQuarter == null ? LocalDate.of(selectedYear, 12, 31) : from.plusMonths(3).minusDays(1);
        List<SigningPlan.Row> rows = organizationRepository.visibilityScope(profile)
                .map(scope -> repository.findCandidates(scope, from, to).stream().map(candidate -> row(candidate, today)).toList())
                .orElse(List.of());
        OffsetDateTime generatedAt = OffsetDateTime.now();
        return new SigningPlan(
                generatedAt,
                ReportRequest.ZONE.getId(),
                selectedYear,
                selectedQuarter,
                from,
                to,
                notes(generatedAt, from, to),
                rows,
                totals(rows, row -> row.managerId() == null ? "none" : row.managerId().toString(),
                        row -> row.managerId() == null ? NO_MANAGER : row.managerName(), true),
                totals(rows, row -> row.teamId().toString(), SigningPlan.Row::teamName, false)
        );
    }

    public static String fileName(SigningPlan plan, ReportFormat format) {
        return "План_подписаний_" + plan.from() + "_" + plan.to() + "." + format.extension();
    }

    public void write(SigningPlan plan, ReportFormat format, OutputStream output) throws IOException {
        if (format == ReportFormat.PDF) {
            PdfLayout.write(SigningPlan.TITLE, plan.generatedAt(), plan.notes(), output, layout -> {
                layout.paragraph("Строк в отчёте: " + plan.rows().size(), PdfLayout.NOTE_SIZE);
                layout.gap(PdfLayout.NOTE_SIZE);
                layout.table(COLUMNS, WIDTHS);
                for (SigningPlan.Row row : plan.rows()) {
                    layout.row(rowValues(row));
                }
                if (plan.rows().isEmpty()) {
                    layout.gap(PdfLayout.NOTE_SIZE);
                    layout.paragraph("В выбранном периоде нет плановых подписаний и продлений", PdfLayout.NOTE_SIZE);
                }
                writePdfTotals(layout, "Итоги по КАМ", plan.byManager());
                writePdfTotals(layout, "Итоги по командам", plan.byTeam());
            });
            return;
        }
        if (format != ReportFormat.XLSX) {
            throw new InteractionValidationException("format", "План подписаний выгружается в XLSX или PDF");
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            header.setWrapText(true);
            CellStyle quoted = workbook.createCellStyle();
            quoted.setQuotePrefixed(true);
            Sheet sheet = workbook.createSheet("План");
            int headerRow = titleBlock(sheet, header, plan.notes());
            headerCells(sheet.createRow(headerRow), sheet, header, COLUMNS, WIDTHS);
            int rowIndex = headerRow + 1;
            for (SigningPlan.Row row : plan.rows()) {
                Row cells = sheet.createRow(rowIndex++);
                List<String> values = rowValues(row);
                for (int column = 0; column < values.size(); column++) {
                    text(cells.createCell(column), values.get(column), quoted);
                }
            }
            sheet.createRow(rowIndex + 1).createCell(0).setCellValue("Строк в отчёте: " + plan.rows().size());
            sheet.createFreezePane(0, headerRow + 1);
            sheet.setAutoFilter(new CellRangeAddress(headerRow, Math.max(headerRow, rowIndex - 1), 0, COLUMNS.size() - 1));
            Sheet totals = workbook.createSheet("Итоги");
            int position = totalsBlock(totals, header, quoted, 0, "Итоги по КАМ", plan.byManager());
            totalsBlock(totals, header, quoted, position + 1, "Итоги по командам", plan.byTeam());
            workbook.write(output);
        }
    }

    private static SigningPlan.Row row(Candidate candidate, LocalDate today) {
        boolean explicit = candidate.plannedOn() != null;
        PlanKind kind = explicit ? candidate.plannedKind() : PlanKind.RENEWAL;
        LocalDate plannedOn = explicit ? candidate.plannedOn() : candidate.validUntil();
        boolean done = explicit && (kind == PlanKind.SIGNING
                ? candidate.status() != AgreementStatus.DRAFT
                : candidate.validUntil() != null
                        && (candidate.plannedBaseUntil() == null || candidate.validUntil().isAfter(candidate.plannedBaseUntil())));
        State state = done ? State.DONE : plannedOn.isBefore(today) ? State.OVERDUE : State.UPCOMING;
        return new SigningPlan.Row(candidate.agreementId(), candidate.organizationId(), candidate.organizationName(),
                candidate.teamId(), candidate.teamName(), candidate.managerId(), candidate.managerName(), candidate.number(),
                candidate.status(), kind, plannedOn, explicit ? Source.PLAN : Source.EXPIRY, candidate.validUntil(), state);
    }

    private static List<Totals> totals(
            List<SigningPlan.Row> rows,
            Function<SigningPlan.Row, String> key,
            Function<SigningPlan.Row, String> name,
            boolean withTeam
    ) {
        Map<String, int[]> counters = new LinkedHashMap<>();
        Map<String, SigningPlan.Row> first = new LinkedHashMap<>();
        for (SigningPlan.Row row : rows) {
            String groupKey = key.apply(row);
            first.putIfAbsent(groupKey, row);
            int[] counter = counters.computeIfAbsent(groupKey, ignored -> new int[5]);
            counter[row.kind() == PlanKind.SIGNING ? 0 : 1]++;
            counter[2 + row.state().ordinal()]++;
        }
        List<Totals> result = new ArrayList<>();
        counters.forEach((groupKey, counter) -> {
            SigningPlan.Row row = first.get(groupKey);
            UUID id = withTeam ? row.managerId() : row.teamId();
            result.add(new Totals(id, name.apply(row), withTeam ? row.teamName() : null, counter[0], counter[1], counter[2],
                    counter[3], counter[4]));
        });
        result.sort(Comparator.comparing(Totals::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static List<String> rowValues(SigningPlan.Row row) {
        return List.of(row.organizationName(), row.teamName(), row.managerName() == null ? NO_MANAGER : row.managerName(),
                row.agreementNumber(), row.kind().title(), DATE.format(row.plannedOn()),
                row.source() == Source.PLAN ? "задана в плане" : "срок действия соглашения", row.state().title());
    }

    private static List<String> totalValues(Totals totals) {
        return List.of(totals.name(), totals.teamName() == null ? "" : totals.teamName(), Integer.toString(totals.signing()),
                Integer.toString(totals.renewal()), Integer.toString(totals.done()), Integer.toString(totals.overdue()),
                Integer.toString(totals.upcoming()));
    }

    private static void writePdfTotals(PdfLayout layout, String title, List<Totals> totals) throws IOException {
        layout.gap(PdfLayout.NOTE_SIZE);
        layout.paragraph(title, PdfLayout.NOTE_SIZE);
        layout.table(TOTAL_COLUMNS, TOTAL_WIDTHS);
        for (Totals item : totals) {
            layout.row(totalValues(item));
        }
    }

    private static int titleBlock(Sheet sheet, CellStyle header, List<String> notes) {
        sheet.createRow(0).createCell(0).setCellValue(SigningPlan.TITLE);
        sheet.getRow(0).getCell(0).setCellStyle(header);
        for (int index = 0; index < notes.size(); index++) {
            sheet.createRow(index + 1).createCell(0).setCellValue(notes.get(index));
        }
        return notes.size() + 2;
    }

    private static void headerCells(Row titles, Sheet sheet, CellStyle header, List<String> columns, int[] widths) {
        for (int column = 0; column < columns.size(); column++) {
            Cell cell = titles.createCell(column);
            cell.setCellValue(columns.get(column));
            cell.setCellStyle(header);
            sheet.setColumnWidth(column, widths[column] * 512);
        }
    }

    private static int totalsBlock(Sheet sheet, CellStyle header, CellStyle quoted, int start, String title, List<Totals> totals) {
        sheet.createRow(start).createCell(0).setCellValue(title);
        sheet.getRow(start).getCell(0).setCellStyle(header);
        headerCells(sheet.createRow(start + 1), sheet, header, TOTAL_COLUMNS, TOTAL_WIDTHS);
        int rowIndex = start + 2;
        for (Totals item : totals) {
            Row cells = sheet.createRow(rowIndex++);
            text(cells.createCell(0), item.name(), quoted);
            text(cells.createCell(1), item.teamName() == null ? "" : item.teamName(), quoted);
            cells.createCell(2).setCellValue(item.signing());
            cells.createCell(3).setCellValue(item.renewal());
            cells.createCell(4).setCellValue(item.done());
            cells.createCell(5).setCellValue(item.overdue());
            cells.createCell(6).setCellValue(item.upcoming());
        }
        return rowIndex;
    }

    private static List<String> notes(OffsetDateTime generatedAt, LocalDate from, LocalDate to) {
        return List.of(
                "Сформирован: " + ReportColumn.DATE_TIME.format(generatedAt.atZoneSameInstant(ReportRequest.ZONE))
                        + " (часовой пояс " + ReportRequest.ZONE.getId() + ")",
                "Период: " + DATE.format(from) + " – " + DATE.format(to) + "; вузы вашей области доступа, расторгнутые соглашения"
                        + " и архивные вузы не учитываются",
                "Подписание — соглашение в статусе «Проект» с плановой датой подписания; «выполнено», когда статус сменился."
                        + " Продление — плановая дата продления действующего соглашения; «выполнено», когда срок действия"
                        + " сдвинут позже прежнего. Действующее соглашение без плановой даты попадает в отчёт продлением"
                        + " на дату окончания срока действия",
                "Просрочено — плановая дата раньше сегодняшней (МСК), а подписание или продление не отмечено"
        );
    }

    private static void text(Cell cell, String value, CellStyle quoted) {
        cell.setCellValue(value);
        if (ExcelReportWriter.isFormulaLike(value)) {
            cell.setCellStyle(quoted);
        }
    }
}
