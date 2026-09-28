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
import ru.rtk.crm.report.LearningHistoryRepository.Observation;
import ru.rtk.crm.report.ReportRepository.CatalogTable;

@Service
public class LearningDynamicsService {
    static final int MAX_MONTHS = 60;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"));
    private static final List<String> COLUMNS = List.of("Месяц", "Вуз", "ИТ-программа", "Потоков с данными", "Обучающиеся (Moodle)",
            "Завершили (Moodle)");
    private static final int[] WIDTHS = {12, 30, 30, 10, 12, 12};

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
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? YearMonth.from(end).minusMonths(11).atDay(1) : from;
        if (end.isAfter(today)) {
            throw new InteractionValidationException("to", "Конец периода не может быть позже сегодняшней даты");
        }
        if (start.isAfter(end)) {
            throw new InteractionValidationException("to", "Дата окончания периода раньше даты начала");
        }
        if (YearMonth.from(start).plusMonths(MAX_MONTHS - 1).isBefore(YearMonth.from(end))) {
            throw new InteractionValidationException("from", "Динамика строится не больше чем за " + MAX_MONTHS + " месяцев");
        }
        SeriesBy by = seriesBy == null ? SeriesBy.ORGANIZATION : seriesBy;
        List<UUID> organizations = organizationIds == null ? List.of() : List.copyOf(organizationIds);
        List<UUID> programs = programIds == null ? List.of() : List.copyOf(programIds);
        List<Observation> observations = organizationRepository.visibilityScope(profile)
                .map(scope -> historyRepository.findObservations(scope, start, end, organizations, programs))
                .orElse(List.of());

        List<LearningDynamics.Month> months = new ArrayList<>();
        for (YearMonth month = YearMonth.from(start); !month.isAfter(YearMonth.from(end)); month = month.plusMonths(1)) {
            LocalDate asOf = month.atEndOfMonth().isAfter(end) ? end : month.atEndOfMonth();
            months.add(new LearningDynamics.Month(MONTH_KEY.format(month), MONTH_LABEL.format(month), asOf));
        }
        int size = months.size();
        long[] totalParticipants = new long[size];
        Long[] totalCompleted = new Long[size];
        Map<String, RowTotals> rows = new LinkedHashMap<>();
        Map<String, SeriesTotals> series = new LinkedHashMap<>();
        for (int index = 0; index < size; index++) {
            LearningDynamics.Month month = months.get(index);
            LocalDate firstDay = YearMonth.parse(month.key()).atDay(1);
            LocalDate monthStart = firstDay.isBefore(start) ? start : firstDay;
            for (Observation observation : LearningHistoryRepository.inForce(observations, month.asOf()).values()) {
                if (!observation.runEndsOn().isAfter(monthStart)) {
                    continue;
                }
                rows.computeIfAbsent(month.key() + "|" + observation.organizationId() + "|" + observation.programId(),
                        key -> new RowTotals(month, observation)).add(observation);
                String seriesKey = (by == SeriesBy.PROGRAM ? observation.programId() : observation.organizationId()).toString();
                series.computeIfAbsent(seriesKey, key -> new SeriesTotals(key,
                        by == SeriesBy.PROGRAM ? observation.programName() : observation.organizationName(), size))
                        .add(index, observation);
                totalParticipants[index] += observation.participants();
                totalCompleted[index] = plus(totalCompleted[index], observation.completed());
            }
        }
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
                        .sorted(Comparator.comparingLong((SeriesTotals totals) -> -totals.participants[size - 1])
                                .thenComparing(totals -> totals.label, String.CASE_INSENSITIVE_ORDER))
                        .map(SeriesTotals::view)
                        .toList(),
                Arrays.stream(totalParticipants).boxed().toList(),
                Arrays.asList(totalCompleted),
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
                            Long.toString(row.participants()), row.completed() == null ? ReportColumn.NO_DATA : row.completed().toString()));
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
                cells.createCell(4).setCellValue(row.participants());
                if (row.completed() == null) {
                    cells.createCell(5).setCellValue(ReportColumn.NO_DATA);
                } else {
                    cells.createCell(5).setCellValue(row.completed());
                }
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
                        + " Moodle, которые шли в нём хотя бы один день; число потока — наблюдение из истории, действовавшее"
                        + " на конец последнего дня месяца (у последнего месяца — на конец дня окончания периода)",
                "Обучающиеся и завершившие — участия, а не уникальные люди; завершившие — только потоки, где Moodle"
                        + " отслеживает завершение, иначе «нет данных»; поток без наблюдения к этой дате не учитывается;"
                        + " потоки обучения преподавателей не учитываются",
                "Фильтры: " + (filters.isEmpty() ? "не заданы" : String.join("; ", filters))
        );
    }

    private static void text(Cell cell, String value, CellStyle quoted) {
        cell.setCellValue(value);
        if (ExcelReportWriter.isFormulaLike(value)) {
            cell.setCellStyle(quoted);
        }
    }

    private static Long plus(Long total, Integer value) {
        if (value == null) {
            return total;
        }
        return (total == null ? 0L : total) + value;
    }

    private static final class RowTotals {
        private final LearningDynamics.Month month;
        private final Observation first;
        private long runs;
        private long participants;
        private Long completed;

        private RowTotals(LearningDynamics.Month month, Observation first) {
            this.month = month;
            this.first = first;
        }

        private void add(Observation observation) {
            runs++;
            participants += observation.participants();
            completed = plus(completed, observation.completed());
        }

        private LearningDynamics.Row view() {
            return new LearningDynamics.Row(month.key(), month.label(), first.organizationId(), first.organizationName(),
                    first.programId(), first.programName(), runs, participants, completed);
        }
    }

    private static final class SeriesTotals {
        private final String key;
        private final String label;
        private final long[] participants;
        private final Long[] completed;

        private SeriesTotals(String key, String label, int size) {
            this.key = key;
            this.label = label;
            this.participants = new long[size];
            this.completed = new Long[size];
        }

        private void add(int index, Observation observation) {
            participants[index] += observation.participants();
            completed[index] = plus(completed[index], observation.completed());
        }

        private LearningDynamics.Series view() {
            return new LearningDynamics.Series(key, label, Arrays.stream(participants).boxed().toList(), Arrays.asList(completed));
        }
    }
}
