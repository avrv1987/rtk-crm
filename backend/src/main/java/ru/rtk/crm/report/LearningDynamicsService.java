package ru.rtk.crm.report;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.LearningDynamics.SeriesBy;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.report.LearningHistoryRepository.Observation;
import ru.rtk.crm.report.LearningHistoryRepository.Run;
import ru.rtk.crm.report.ReportRepository.CatalogTable;

@Service
public class LearningDynamicsService {
    static final int MAX_MONTHS = 60;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"));
    private static final List<String> COLUMNS = List.of("Месяц", "Вуз", "ИТ-программа", "Потоков с данными", "Потоков без данных",
            "Обучающиеся в потоках месяца (Moodle)", "Завершили (Moodle)", "Статус завершения известен",
            "Доля завершивших среди известных, %", "Основание доли");
    private static final int[] WIDTHS = {12, 26, 26, 10, 10, 12, 12, 12, 12, 34};

    private final LearningHistoryRepository historyRepository;
    private final OrganizationRepository organizationRepository;
    private final ReportRepository reportRepository;

    public LearningDynamicsService(
            LearningHistoryRepository historyRepository,
            OrganizationRepository organizationRepository,
            ReportRepository reportRepository
    ) {
        this.historyRepository = historyRepository;
        this.organizationRepository = organizationRepository;
        this.reportRepository = reportRepository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LearningDynamics dynamics(
            CrmProfile profile,
            LocalDate from,
            LocalDate to,
            SeriesBy seriesBy,
            List<UUID> organizationIds,
            List<UUID> programIds
    ) {
        LocalDate today = LocalDate.now(ReportRequest.ZONE);
        // «Этот месяц», «Этот год» и другие готовые периоды кончаются в будущем: строим по сегодняшний день, как
        // остальные отчёты (ReportRequest.runsAsOf), а не отказываем — наблюдений Moodle в будущем не бывает.
        LocalDate end = to == null || to.isAfter(today) ? today : to;
        LocalDate start = from == null ? YearMonth.from(end).minusMonths(11).atDay(1) : from;
        if (start.isAfter(end)) {
            throw new InteractionValidationException("to", "Дата окончания периода раньше даты начала");
        }
        if (YearMonth.from(start).plusMonths(MAX_MONTHS - 1).isBefore(YearMonth.from(end))) {
            throw new InteractionValidationException("from", "Динамика строится не больше чем за " + MAX_MONTHS + " месяцев");
        }
        SeriesBy by = seriesBy == null ? SeriesBy.ORGANIZATION : seriesBy;
        List<UUID> organizations = organizationIds == null ? List.of() : List.copyOf(organizationIds);
        List<UUID> programs = programIds == null ? List.of() : List.copyOf(programIds);
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        List<Observation> observations = scope
                .map(visible -> historyRepository.findObservations(visible, start, end, organizations, programs))
                .orElse(List.of());
        List<Run> runs = scope
                .map(visible -> historyRepository.findRuns(visible, start, end, organizations, programs))
                .orElse(List.of());

        List<LearningDynamics.Month> months = new ArrayList<>();
        for (YearMonth month = YearMonth.from(start); !month.isAfter(YearMonth.from(end)); month = month.plusMonths(1)) {
            LocalDate asOf = month.atEndOfMonth().isAfter(end) ? end : month.atEndOfMonth();
            months.add(new LearningDynamics.Month(MONTH_KEY.format(month), MONTH_LABEL.format(month), asOf));
        }
        int size = months.size();
        Counts[] totals = new Counts[size];
        Map<String, RowTotals> rows = new LinkedHashMap<>();
        Map<String, SeriesTotals> series = new LinkedHashMap<>();
        for (int index = 0; index < size; index++) {
            LearningDynamics.Month month = months.get(index);
            LocalDate firstDay = YearMonth.parse(month.key()).atDay(1);
            LocalDate monthStart = firstDay.isBefore(start) ? start : firstDay;
            Map<UUID, Observation> inForce = LearningHistoryRepository.inForce(observations, month.asOf());
            totals[index] = new Counts();
            // Поток, шедший в месяце, учитывается и без наблюдения: тогда он «без данных», а не ноль обучающихся.
            for (Run run : runs) {
                if (!run.runEndsOn().isAfter(monthStart) || run.runStartsOn().isAfter(month.asOf())) {
                    continue;
                }
                Observation observation = inForce.get(run.mappingId());
                rows.computeIfAbsent(month.key() + "|" + run.organizationId() + "|" + run.programId(),
                        key -> new RowTotals(month, run)).counts.add(observation);
                String seriesKey = (by == SeriesBy.PROGRAM ? run.programId() : run.organizationId()).toString();
                series.computeIfAbsent(seriesKey, key -> new SeriesTotals(key,
                        by == SeriesBy.PROGRAM ? run.programName() : run.organizationName(), size))
                        .add(index, observation, run.runStartsOn());
                totals[index].add(observation);
            }
        }
        // До первого потока в данных ноль читался бы как «никто не учился», а реестра плановых потоков нет:
        // такие месяцы — «нет данных».
        LocalDate firstRun = runs.stream().map(Run::runStartsOn).min(Comparator.naturalOrder()).orElse(null);
        for (int index = 0; index < size; index++) {
            totals[index].beforeData = firstRun == null || months.get(index).asOf().isBefore(firstRun);
        }
        series.values().forEach(item -> item.markBeforeData(months));
        OffsetDateTime generatedAt = OffsetDateTime.now();
        return new LearningDynamics(
                generatedAt,
                start,
                end,
                ReportRequest.ZONE.getId(),
                by,
                notes(profile, generatedAt, start, end, organizations, programs),
                months,
                series.values().stream()
                        .sorted(Comparator.comparingLong((SeriesTotals item) -> -item.counts[size - 1].observedParticipants())
                                .thenComparing(item -> item.label, String.CASE_INSENSITIVE_ORDER))
                        .map(SeriesTotals::view)
                        .toList(),
                Arrays.stream(totals).map(Counts::participants).toList(),
                Arrays.stream(totals).map(Counts::completed).toList(),
                Arrays.stream(totals).map(Counts::known).toList(),
                Arrays.stream(totals).map(counts -> counts.runsWithoutData).toList(),
                rows.values().stream()
                        .map(RowTotals::view)
                        .sorted(Comparator.comparing(LearningDynamics.Row::month)
                                .thenComparing(LearningDynamics.Row::organizationName, String.CASE_INSENSITIVE_ORDER)
                                .thenComparing(LearningDynamics.Row::programName, String.CASE_INSENSITIVE_ORDER))
                        .toList()
        );
    }

    public static String fileName(LearningDynamics dynamics, ReportFormat format) {
        return "Динамика_обучения_" + dynamics.from() + "_" + dynamics.to() + "." + format.extension();
    }

    public void write(LearningDynamics dynamics, ReportFormat format, OutputStream output) throws IOException {
        if (format == ReportFormat.PDF) {
            PdfLayout.write(LearningDynamics.TITLE, dynamics.generatedAt(), dynamics.notes(), output, layout -> {
                layout.paragraph("Строк в отчёте: " + dynamics.rows().size(), PdfLayout.NOTE_SIZE);
                layout.gap(PdfLayout.NOTE_SIZE);
                layout.table(COLUMNS, WIDTHS);
                for (LearningDynamics.Row row : dynamics.rows()) {
                    layout.row(List.of(row.monthLabel(), row.organizationName(), row.programName(), Long.toString(row.runs()),
                            Long.toString(row.runsWithoutData()), text(row.participants()), text(row.completed()), text(row.known()),
                            text(row.completionPercent()), row.completionBasis()));
                }
                if (dynamics.rows().isEmpty()) {
                    layout.gap(PdfLayout.NOTE_SIZE);
                    layout.paragraph("Нет наблюдений, удовлетворяющих фильтрам", PdfLayout.NOTE_SIZE);
                }
            });
            return;
        }
        if (format != ReportFormat.XLSX) {
            throw new InteractionValidationException("format", "Динамика обучения выгружается в XLSX или PDF");
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Динамика");
            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            header.setWrapText(true);
            CellStyle quoted = workbook.createCellStyle();
            quoted.setQuotePrefixed(true);
            sheet.createRow(0).createCell(0).setCellValue(LearningDynamics.TITLE);
            sheet.getRow(0).getCell(0).setCellStyle(header);
            for (int index = 0; index < dynamics.notes().size(); index++) {
                sheet.createRow(index + 1).createCell(0).setCellValue(dynamics.notes().get(index));
            }
            int headerRow = dynamics.notes().size() + 2;
            Row titles = sheet.createRow(headerRow);
            for (int column = 0; column < COLUMNS.size(); column++) {
                Cell cell = titles.createCell(column);
                cell.setCellValue(COLUMNS.get(column));
                cell.setCellStyle(header);
                sheet.setColumnWidth(column, WIDTHS[column] * 512);
            }
            int rowIndex = headerRow + 1;
            for (LearningDynamics.Row row : dynamics.rows()) {
                Row cells = sheet.createRow(rowIndex++);
                cells.createCell(0).setCellValue(row.monthLabel());
                text(cells.createCell(1), row.organizationName(), quoted);
                text(cells.createCell(2), row.programName(), quoted);
                cells.createCell(3).setCellValue(row.runs());
                cells.createCell(4).setCellValue(row.runsWithoutData());
                number(cells.createCell(5), row.participants());
                number(cells.createCell(6), row.completed());
                number(cells.createCell(7), row.known());
                number(cells.createCell(8), row.completionPercent());
                cells.createCell(9).setCellValue(row.completionBasis());
            }
            sheet.createRow(rowIndex + 1).createCell(0).setCellValue("Строк в отчёте: " + dynamics.rows().size());
            sheet.createFreezePane(0, headerRow + 1);
            sheet.setAutoFilter(new CellRangeAddress(headerRow, Math.max(headerRow, rowIndex - 1), 0, COLUMNS.size() - 1));
            workbook.write(output);
        }
    }

    private List<String> notes(
            CrmProfile profile,
            OffsetDateTime generatedAt,
            LocalDate start,
            LocalDate end,
            List<UUID> organizations,
            List<UUID> programs
    ) {
        List<String> filters = new ArrayList<>();
        if (!organizations.isEmpty()) {
            filters.add("вузы: " + String.join(", ", reportRepository.findOrganizationNames(profile, organizations).values()));
        }
        if (!programs.isEmpty()) {
            filters.add("программы: " + String.join(", ", reportRepository.findCatalogNames(CatalogTable.PROGRAMS, programs).values()));
        }
        return List.of(
                "Сформирован: " + ReportColumn.DATE_TIME.format(generatedAt.atZoneSameInstant(ReportRequest.ZONE))
                        + " (часовой пояс " + ReportRequest.ZONE.getId() + ")",
                "Период: " + DATE.format(start) + " – " + DATE.format(end) + "; в месяц входят потоки занятий студентов"
                        + " Moodle, шедшие в нём хотя бы один день, по наблюдению, действовавшему на конец последнего дня месяца"
                        + " (у последнего месяца — на конец дня окончания периода)",
                "Обучающиеся и завершившие — участия (не уникальные люди). Доля завершивших — среди участий с известным"
                        + " статусом; основание показывает, у скольких участий статус известен. Поток без наблюдения — «нет"
                        + " данных», а не ноль, он считается в «Потоках без данных»; месяцы раньше первого потока — тоже «нет данных»;"
                        + " обучение преподавателей не учитывается",
                "Фильтры: " + (filters.isEmpty() ? "не заданы" : String.join("; ", filters)) + "; архивные вузы не входят"
        );
    }

    private static void text(Cell cell, String value, CellStyle quoted) {
        cell.setCellValue(value);
        if (ExcelReportWriter.isFormulaLike(value)) {
            cell.setCellStyle(quoted);
        }
    }

    private static String text(Long value) {
        return value == null ? ReportColumn.NO_DATA : value.toString();
    }

    private static void number(Cell cell, Long value) {
        if (value == null) {
            cell.setCellValue(ReportColumn.NO_DATA);
        } else {
            cell.setCellValue(value);
        }
    }

    // «16 из 20 с известным статусом; данные по 20 из 100»: доля считается только среди участий с известным статусом,
    // а покрытие показывает, на какую часть всех участий она опирается.
    static String completionBasis(Long completed, Long known, Long participants) {
        if (participants == null) {
            return "нет данных: у потоков нет наблюдений";
        }
        if (known == null) {
            return "нет данных: завершение в Moodle не отслеживается";
        }
        return completed + " из " + known + " с известным статусом; данные по " + known + " из " + participants;
    }

    // Счётчики одной ячейки: C — завершили, N — не завершили, участия без статуса — остальное. Поток без наблюдения
    // увеличивает только runsWithoutData.
    static final class Counts {
        private long runs;
        private long runsWithoutData;
        private long participants;
        private long completed;
        private long notCompleted;
        private boolean tracked;
        private boolean beforeData;

        void add(Observation observation) {
            if (observation == null) {
                runsWithoutData++;
                return;
            }
            runs++;
            participants += observation.participants();
            if (observation.completed() != null) {
                tracked = true;
                completed += observation.completed();
                notCompleted += observation.notCompleted() == null ? 0 : observation.notCompleted();
            }
        }

        long observedParticipants() {
            return participants;
        }

        // Ноль — потоков в месяце не было; null — потоки были, но ни у одного нет наблюдения, или месяц раньше первого потока.
        Long participants() {
            return runs == 0 && (runsWithoutData > 0 || beforeData) ? null : participants;
        }

        Long completed() {
            return tracked ? completed : null;
        }

        Long known() {
            return tracked ? completed + notCompleted : null;
        }

        Long percent() {
            return ReportRow.percentOf(completed(), known());
        }

        String basis() {
            return completionBasis(completed(), known(), participants());
        }
    }

    private static final class RowTotals {
        private final LearningDynamics.Month month;
        private final Run run;
        private final Counts counts = new Counts();

        private RowTotals(LearningDynamics.Month month, Run run) {
            this.month = month;
            this.run = run;
        }

        private LearningDynamics.Row view() {
            return new LearningDynamics.Row(month.key(), month.label(), run.organizationId(), run.organizationName(),
                    run.programId(), run.programName(), counts.runs, counts.runsWithoutData, counts.participants(),
                    counts.completed(), counts.known(), counts.percent(), counts.basis());
        }
    }

    private static final class SeriesTotals {
        private final String key;
        private final String label;
        private final Counts[] counts;
        private LocalDate firstRun;

        private SeriesTotals(String key, String label, int size) {
            this.key = key;
            this.label = label;
            this.counts = new Counts[size];
            Arrays.setAll(counts, index -> new Counts());
        }

        private void add(int index, Observation observation, LocalDate runStartsOn) {
            counts[index].add(observation);
            firstRun = firstRun == null || runStartsOn.isBefore(firstRun) ? runStartsOn : firstRun;
        }

        private void markBeforeData(List<LearningDynamics.Month> months) {
            for (int index = 0; index < counts.length; index++) {
                counts[index].beforeData = months.get(index).asOf().isBefore(firstRun);
            }
        }

        private LearningDynamics.Series view() {
            return new LearningDynamics.Series(key, label, Arrays.stream(counts).map(Counts::participants).toList(),
                    Arrays.stream(counts).map(Counts::completed).toList());
        }
    }
}
