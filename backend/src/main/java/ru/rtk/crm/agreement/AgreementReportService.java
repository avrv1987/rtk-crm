package ru.rtk.crm.agreement;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import ru.rtk.crm.agreement.ReportingModels.AgreementReport;
import ru.rtk.crm.agreement.ReportingModels.ExportColumn;
import ru.rtk.crm.agreement.ReportingModels.PeriodRange;
import ru.rtk.crm.agreement.ReportingModels.Quantity;
import ru.rtk.crm.agreement.ReportingModels.Rate;
import ru.rtk.crm.agreement.ReportingModels.ReferenceCategory;
import ru.rtk.crm.agreement.ReportingModels.ReferenceItem;
import ru.rtk.crm.agreement.ReportingModels.ReportGroup;
import ru.rtk.crm.agreement.ReportingModels.ReportTotals;
import ru.rtk.crm.agreement.ReportingModels.Settings;
import ru.rtk.crm.agreement.ReportingModels.ThresholdRow;
import ru.rtk.crm.agreement.ReportingModels.ThresholdSummary;
import ru.rtk.crm.agreement.ReportingRepository.ReportActivity;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.ExcelReportWriter;
import ru.rtk.crm.report.PdfLayout;
import ru.rtk.crm.report.ReportFormat;
import ru.rtk.crm.web.Plural;

// «Отчёт по соглашениям» за период: строки — мероприятия планов договоров, своды по вузам, видам, исполнителям и
// уровням, свод по исполнителям против порога. Набор и порядок колонок строк задаёт администратор (шаблон выгрузки),
// и экран, и файлы строятся из одних и тех же строк, поэтому значения совпадают.
@Service
public class AgreementReportService {
    public static final String TITLE = "Отчёт по соглашениям";
    static final String NOT_SET = "Не указан";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final List<String> GROUP_COLUMNS = List.of("Название", "Мероприятий", "Охват, чел.", "Количество (план / факт)",
            "Стоимость, ₽", "Оценка по плану, ₽", "Оценка факт, ₽");
    private static final List<String> THRESHOLD_COLUMNS = List.of("Исполнитель", "Оценка по плану, ₽", "Оценка факт, ₽", "База, ₽",
            "Требуется по порогу, ₽", "План достигает порога", "Факт достигает порога");

    public enum Field {
        ORGANIZATION("Вуз", false),
        AGREEMENT("Договор", false),
        KIND("Вид мероприятия", false),
        TITLE("Мероприятие", false),
        EDUCATION_LEVEL("Уровень образования", false),
        EXECUTOR("Исполнитель", false),
        STATUS("Статус", false),
        PERIOD("Сроки", false),
        UNIT("Единица", false),
        PLANNED_VOLUME("Количество по плану", true),
        ACTUAL_VOLUME("Количество факт", true),
        PARTICIPANTS("Охват, чел.", true),
        COST("Стоимость, ₽", true),
        RATE("Ставка, ₽", true),
        PLANNED_VALUATION("Оценка по плану, ₽", true),
        ACTUAL_VALUATION("Оценка факт, ₽", true),
        DOCUMENT("Подтверждающий документ", false),
        RESPONSIBLE("Ответственный", false);

        private final String title;
        private final boolean numeric;

        Field(String title, boolean numeric) {
            this.title = title;
            this.numeric = numeric;
        }

        public String title() {
            return title;
        }

        public boolean numeric() {
            return numeric;
        }

        public static Optional<Field> find(String code) {
            return Arrays.stream(values()).filter(field -> field.name().equals(code)).findFirst();
        }
    }

    private final OrganizationRepository organizationRepository;
    private final ReportingRepository repository;
    private final ReportingSettingsService settingsService;
    private final ObjectMapper objectMapper;

    public AgreementReportService(OrganizationRepository organizationRepository, ReportingRepository repository,
                                  ReportingSettingsService settingsService, ObjectMapper objectMapper) {
        this.organizationRepository = organizationRepository;
        this.repository = repository;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Transactional(readOnly = true)
    public AgreementReport report(CrmProfile profile, LocalDate requestedFrom, LocalDate requestedTo, UUID organizationId) {
        AgreementService.requireRegistryReader(profile);
        Settings settings = settingsService.settings();
        PeriodRange current = settingsService.currentPeriod(settings, LocalDate.now(AgreementService.ZONE));
        LocalDate from = requestedFrom == null ? current.from() : requestedFrom;
        LocalDate to = requestedTo == null ? current.to() : requestedTo;
        if (from.isAfter(to)) {
            throw new InteractionValidationException("to", "Дата окончания периода раньше даты начала");
        }
        if (organizationId != null) {
            organizationRepository.findVisibleById(profile, organizationId)
                    .orElseThrow(ru.rtk.crm.catalog.OrganizationNotFoundException::new);
        }
        VisibilityScope scope = organizationRepository.visibilityScope(profile).orElse(null);
        List<ReportActivity> activities = scope == null ? List.of() : repository.findReportActivities(scope, from, to, organizationId);
        Map<UUID, List<String>> documents = repository.findCleanAttachmentNames(activities.stream().map(ReportActivity::id).toList());
        List<ReferenceItem> executors = repository.findReferences(true).stream()
                .filter(item -> item.category() == ReferenceCategory.EXECUTOR)
                .toList();
        return build(OffsetDateTime.now(), from, to, settings, settingsService.template().columns(), activities, documents,
                repository.findRates(), executors);
    }

    static AgreementReport build(
            OffsetDateTime generatedAt,
            LocalDate from,
            LocalDate to,
            Settings settings,
            List<ExportColumn> columns,
            List<ReportActivity> activities,
            Map<UUID, List<String>> documents,
            List<Rate> rates,
            List<ReferenceItem> executors
    ) {
        List<Line> lines = activities.stream().map(activity -> line(activity, documents, rates)).toList();
        List<Field> fields = columns.stream().map(column -> Field.find(column.code()).orElseThrow()).toList();
        List<List<Object>> rows = lines.stream().map(line -> fields.stream().map(field -> value(field, line)).toList()).toList();
        int withoutRate = (int) lines.stream().filter(line -> line.rate() == null).count();
        ReportTotals totals = new ReportTotals(
                (int) activities.stream().map(ReportActivity::organizationId).distinct().count(),
                lines.size(),
                lines.stream().mapToLong(line -> number(line.activity().participants())).sum(),
                sum(lines, line -> line.activity().cost()),
                sum(lines, Line::plannedValuation),
                sum(lines, Line::actualValuation),
                withoutRate
        );
        List<String> notes = new ArrayList<>(List.of(
                "Сформирован: " + DATE_TIME.format(generatedAt.atZoneSameInstant(AgreementService.ZONE)) + " (МСК)",
                "Период: " + DATE.format(from) + " – " + DATE.format(to) + "; вузы вашей области доступа",
                "Мероприятия планов договоров, сроки которых пересекаются с периодом; отменённые и отклонённые не входят",
                "Оценка — количество × ставка из справочника ставок (вид мероприятия, единица, уровень образования), действующая на дату"
                        + " проведения: окончание мероприятия, иначе начало"
        ));
        if (withoutRate > 0) {
            notes.add("Без ставки: " + Plural.withCount(withoutRate, "мероприятие", "мероприятия", "мероприятий") + " — нет подходящей ставки, единицы из справочника или даты");
        }
        if (settings.thresholdEnabled() && settings.thresholdPercent() != null) {
            notes.add("Свод против порога: " + plain(settings.thresholdPercent()) + " % от базы"
                    + (settings.thresholdBaseLabel() == null ? "" : " «" + settings.thresholdBaseLabel() + "»"));
        }
        return new AgreementReport(
                generatedAt,
                from,
                to,
                List.copyOf(notes),
                List.copyOf(columns),
                rows,
                totals,
                groups(lines, line -> line.activity().organizationId(), line -> line.activity().organizationName(), false),
                groups(lines, line -> line.activity().kindId(), line -> line.activity().kindName(), false),
                groups(lines, line -> line.activity().executorId(), line -> line.activity().executorName(), true),
                groups(lines, line -> line.activity().educationLevelId(), line -> line.activity().educationLevelName(), true),
                threshold(settings, lines, executors)
        );
    }

    // Ставка подбирается по виду, единице и дате проведения: сначала с тем же уровнем образования, иначе ставка без
    // уровня; из подходящих — с самой поздней датой начала действия, не позже даты проведения.
    static BigDecimal rateFor(List<Rate> rates, UUID kindId, UUID unitId, UUID levelId, LocalDate date) {
        if (unitId == null || date == null) {
            return null;
        }
        Comparator<Rate> latest = Comparator.comparing(Rate::effectiveFrom);
        Optional<Rate> exact = levelId == null ? Optional.empty() : rates.stream()
                .filter(rate -> matches(rate, kindId, unitId, date) && levelId.equals(rate.educationLevelId()))
                .max(latest);
        return exact.or(() -> rates.stream()
                        .filter(rate -> matches(rate, kindId, unitId, date) && rate.educationLevelId() == null)
                        .max(latest))
                .map(Rate::rate)
                .orElse(null);
    }

    private static boolean matches(Rate rate, UUID kindId, UUID unitId, LocalDate date) {
        return rate.kindId().equals(kindId) && rate.unitId().equals(unitId) && !rate.effectiveFrom().isAfter(date);
    }

    public static String fileName(AgreementReport report, ReportFormat format) {
        return "Отчёт_по_соглашениям_" + report.from() + "_" + report.to() + "." + format.extension();
    }

    public void write(AgreementReport report, ReportFormat format, OutputStream output) throws IOException {
        switch (format) {
            case JSON -> objectMapper.writerWithDefaultPrettyPrinter().writeValue(output, report);
            case PDF -> writePdf(report, output);
            case XLSX -> writeXlsx(report, output);
            default -> throw new InteractionValidationException("format", "Отчёт по соглашениям выгружается в XLSX, PDF или JSON");
        }
    }

    // ---------- расчёт ----------

    private record Line(ReportActivity activity, BigDecimal rate, BigDecimal plannedValuation, BigDecimal actualValuation,
                        String document) {
    }

    private static Line line(ReportActivity activity, Map<UUID, List<String>> documents, List<Rate> rates) {
        BigDecimal rate = rateFor(rates, activity.kindId(), activity.unitId(), activity.educationLevelId(), activity.valuationDate());
        List<String> parts = new ArrayList<>();
        if (activity.documentNote() != null) {
            parts.add(activity.documentNote());
        }
        parts.addAll(documents.getOrDefault(activity.id(), List.of()));
        return new Line(activity, rate, multiply(rate, activity.plannedVolume()), multiply(rate, activity.actualVolume()),
                parts.isEmpty() ? null : String.join("; ", parts));
    }

    private static BigDecimal multiply(BigDecimal rate, Integer volume) {
        return rate == null || volume == null ? null : rate.multiply(BigDecimal.valueOf(volume)).setScale(2, RoundingMode.HALF_UP);
    }

    static Object value(Field field, Line line) {
        ReportActivity activity = line.activity();
        return switch (field) {
            case ORGANIZATION -> activity.organizationName();
            case AGREEMENT -> "№ " + activity.agreementNumber()
                    + (activity.agreementConcludedOn() == null ? "" : " от " + DATE.format(activity.agreementConcludedOn()));
            case KIND -> activity.kindName();
            case TITLE -> activity.title();
            case EDUCATION_LEVEL -> activity.educationLevelName();
            case EXECUTOR -> activity.executorName();
            case STATUS -> activity.status().title();
            case PERIOD -> period(activity.start(), activity.end());
            case UNIT -> activity.unit();
            case PLANNED_VOLUME -> activity.plannedVolume();
            case ACTUAL_VOLUME -> activity.actualVolume();
            case PARTICIPANTS -> activity.participants();
            case COST -> activity.cost();
            case RATE -> line.rate();
            case PLANNED_VALUATION -> line.plannedValuation();
            case ACTUAL_VALUATION -> line.actualValuation();
            case DOCUMENT -> line.document();
            case RESPONSIBLE -> activity.responsibleName();
        };
    }

    private static List<ReportGroup> groups(List<Line> lines, Function<Line, UUID> key, Function<Line, String> name,
                                            boolean notSetLast) {
        Map<UUID, List<Line>> grouped = new LinkedHashMap<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        UUID none = new UUID(0, 0);
        for (Line line : lines) {
            UUID id = key.apply(line) == null ? none : key.apply(line);
            grouped.computeIfAbsent(id, ignored -> new ArrayList<>()).add(line);
            names.putIfAbsent(id, name.apply(line) == null ? NOT_SET : name.apply(line));
        }
        List<ReportGroup> groups = new ArrayList<>();
        grouped.forEach((id, members) -> {
            Map<String, long[]> quantities = new LinkedHashMap<>();
            for (Line member : members) {
                long[] value = quantities.computeIfAbsent(member.activity().unit() == null ? "без единицы" : member.activity().unit(),
                        ignored -> new long[2]);
                value[0] += number(member.activity().plannedVolume());
                value[1] += number(member.activity().actualVolume());
            }
            groups.add(new ReportGroup(
                    id.equals(none) ? null : id,
                    names.get(id),
                    members.size(),
                    members.stream().mapToLong(member -> number(member.activity().participants())).sum(),
                    quantities.entrySet().stream().map(entry -> new Quantity(entry.getKey(), entry.getValue()[0], entry.getValue()[1])).toList(),
                    sum(members, member -> member.activity().cost()),
                    sum(members, Line::plannedValuation),
                    sum(members, Line::actualValuation)
            ));
        });
        if (notSetLast) {
            groups.sort(Comparator.comparing((ReportGroup group) -> group.id() == null).thenComparing(ReportGroup::name));
        }
        return List.copyOf(groups);
    }

    // Порог и база — настройки администратора; по умолчанию свод выключен. База исполнителя — в справочнике
    // «Исполнители», общая база — в настройках (если её нет, итог считается от суммы баз исполнителей).
    private static ThresholdSummary threshold(Settings settings, List<Line> lines, List<ReferenceItem> executors) {
        if (!settings.thresholdEnabled() || settings.thresholdPercent() == null) {
            return new ThresholdSummary(false, settings.thresholdPercent(), settings.thresholdBaseLabel(), List.of(), null);
        }
        BigDecimal percent = settings.thresholdPercent();
        Map<UUID, ReferenceItem> byId = new LinkedHashMap<>();
        executors.forEach(item -> byId.put(item.id(), item));
        List<UUID> order = new ArrayList<>();
        executors.stream().filter(item -> !item.archived() && item.baseAmount() != null).forEach(item -> order.add(item.id()));
        lines.stream().map(line -> line.activity().executorId()).filter(Objects::nonNull).distinct()
                .filter(id -> !order.contains(id)).forEach(order::add);
        List<ThresholdRow> rows = new ArrayList<>();
        for (UUID id : order) {
            List<Line> members = lines.stream().filter(line -> id.equals(line.activity().executorId())).toList();
            ReferenceItem item = byId.get(id);
            String name = item != null ? item.name() : members.get(0).activity().executorName();
            rows.add(thresholdRow(id, name, members, item == null ? null : item.baseAmount(), percent));
        }
        rows.sort(Comparator.comparing(ThresholdRow::name));
        List<Line> unassigned = lines.stream().filter(line -> line.activity().executorId() == null).toList();
        if (!unassigned.isEmpty()) {
            rows.add(thresholdRow(null, NOT_SET, unassigned, null, percent));
        }
        BigDecimal executorBases = executors.stream().map(ReferenceItem::baseAmount).filter(Objects::nonNull)
                .reduce(BigDecimal::add).orElse(null);
        BigDecimal totalBase = settings.thresholdBase() != null ? settings.thresholdBase() : executorBases;
        return new ThresholdSummary(true, percent, settings.thresholdBaseLabel(), List.copyOf(rows),
                thresholdRow(null, "Итого", lines, totalBase, percent));
    }

    private static ThresholdRow thresholdRow(UUID id, String name, List<Line> lines, BigDecimal base, BigDecimal percent) {
        BigDecimal planned = sum(lines, Line::plannedValuation);
        BigDecimal actual = sum(lines, Line::actualValuation);
        BigDecimal required = base == null ? null : base.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        return new ThresholdRow(id, name, planned, actual, base, required,
                required == null ? null : planned.compareTo(required) >= 0,
                required == null ? null : actual.compareTo(required) >= 0);
    }

    private static BigDecimal sum(List<Line> lines, Function<Line, BigDecimal> value) {
        return lines.stream().map(value).filter(Objects::nonNull).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private static long number(Integer value) {
        return value == null ? 0 : value;
    }

    // ---------- выгрузка ----------

    private void writeXlsx(AgreementReport report, OutputStream output) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = styles(workbook);
            Sheet sheet = workbook.createSheet("Мероприятия");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue(TITLE);
            title.getCell(0).setCellStyle(styles.header());
            for (int index = 0; index < report.notes().size(); index++) {
                sheet.createRow(index + 1).createCell(0).setCellValue(report.notes().get(index));
            }
            int header = report.notes().size() + 2;
            List<Field> fields = report.columns().stream().map(column -> Field.find(column.code()).orElseThrow()).toList();
            table(sheet, styles, header, report.columns().stream().map(ExportColumn::title).toList(), report.rows(),
                    fields.stream().map(Field::numeric).toList());
            groupSheet(workbook, styles, "По вузам", report.byOrganization());
            groupSheet(workbook, styles, "По видам", report.byKind());
            groupSheet(workbook, styles, "По исполнителям", report.byExecutor());
            groupSheet(workbook, styles, "По уровням", report.byLevel());
            if (report.threshold().enabled()) {
                Sheet threshold = workbook.createSheet("Свод против порога");
                table(threshold, styles, 0, THRESHOLD_COLUMNS, thresholdRows(report.threshold()),
                        List.of(false, true, true, true, true, false, false));
            }
            workbook.write(output);
        }
    }

    private void writePdf(AgreementReport report, OutputStream output) throws IOException {
        PdfLayout.write(TITLE, report.generatedAt(), report.notes(), output, layout -> {
            int[] widths = report.columns().stream().mapToInt(column -> Field.find(column.code()).orElseThrow().numeric() ? 8 : 14).toArray();
            pdfSection(layout, "Мероприятия", report.columns().stream().map(ExportColumn::title).toList(), widths, report.rows());
            int[] groupWidths = {24, 8, 8, 24, 10, 10, 10};
            pdfSection(layout, "По вузам", GROUP_COLUMNS, groupWidths, groupRows(report.byOrganization()));
            pdfSection(layout, "По видам", GROUP_COLUMNS, groupWidths, groupRows(report.byKind()));
            pdfSection(layout, "По исполнителям", GROUP_COLUMNS, groupWidths, groupRows(report.byExecutor()));
            pdfSection(layout, "По уровням", GROUP_COLUMNS, groupWidths, groupRows(report.byLevel()));
            if (report.threshold().enabled()) {
                pdfSection(layout, "Свод против порога", THRESHOLD_COLUMNS, new int[] {24, 10, 10, 10, 10, 9, 9},
                        thresholdRows(report.threshold()));
            }
        });
    }

    private static void pdfSection(PdfLayout layout, String title, List<String> columns, int[] widths, List<List<Object>> rows)
            throws IOException {
        layout.gap(PdfLayout.NOTE_SIZE);
        layout.paragraph(title + " (" + rows.size() + ")", PdfLayout.NOTE_SIZE);
        if (rows.isEmpty()) {
            layout.paragraph("Нет строк", PdfLayout.NOTE_SIZE);
            return;
        }
        layout.table(columns, widths);
        for (List<Object> row : rows) {
            layout.row(row.stream().map(AgreementReportService::text).toList());
        }
    }

    private static void groupSheet(XSSFWorkbook workbook, Styles styles, String name, List<ReportGroup> groups) {
        table(workbook.createSheet(name), styles, 0, GROUP_COLUMNS, groupRows(groups), List.of(false, true, true, false, true, true, true));
    }

    static List<List<Object>> groupRows(List<ReportGroup> groups) {
        return groups.stream().map(group -> List.<Object>of(
                group.name(),
                group.activities(),
                group.participants(),
                quantities(group.quantities()),
                group.cost(),
                group.plannedValuation(),
                group.actualValuation()
        )).toList();
    }

    static String quantities(List<Quantity> quantities) {
        return String.join("; ", quantities.stream().map(quantity -> quantity.unit() + ": " + quantity.planned() + " / " + quantity.actual())
                .toList());
    }

    static List<List<Object>> thresholdRows(ThresholdSummary threshold) {
        List<List<Object>> rows = new ArrayList<>();
        List<ThresholdRow> all = new ArrayList<>(threshold.rows());
        if (threshold.total() != null) {
            all.add(threshold.total());
        }
        for (ThresholdRow row : all) {
            List<Object> values = new ArrayList<>();
            values.add(row.name());
            values.add(row.plannedValuation());
            values.add(row.actualValuation());
            values.add(row.base());
            values.add(row.required());
            values.add(yesNo(row.plannedMet()));
            values.add(yesNo(row.actualMet()));
            rows.add(values);
        }
        return rows;
    }

    private static String yesNo(Boolean value) {
        return value == null ? "нет базы" : value ? "да" : "нет";
    }

    private static void table(Sheet sheet, Styles styles, int headerRow, List<String> columns, List<List<Object>> rows,
                              List<Boolean> numeric) {
        Row header = sheet.createRow(headerRow);
        for (int column = 0; column < columns.size(); column++) {
            Cell cell = header.createCell(column);
            cell.setCellValue(columns.get(column));
            cell.setCellStyle(styles.header());
            sheet.setColumnWidth(column, (numeric.get(column) ? 12 : 26) * 256);
        }
        int index = headerRow + 1;
        for (List<Object> values : rows) {
            Row cells = sheet.createRow(index++);
            for (int column = 0; column < values.size(); column++) {
                Object value = values.get(column);
                Cell cell = cells.createCell(column);
                if (value instanceof BigDecimal decimal) {
                    cell.setCellValue(decimal.doubleValue());
                    cell.setCellStyle(styles.money());
                } else if (value instanceof Number number) {
                    cell.setCellValue(number.doubleValue());
                } else if (value != null) {
                    String text = value.toString();
                    cell.setCellValue(text);
                    if (ExcelReportWriter.isFormulaLike(text)) {
                        cell.setCellStyle(styles.quoted());
                    }
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
        CellStyle money = workbook.createCellStyle();
        money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
        return new Styles(header, quoted, money);
    }

    private record Styles(CellStyle header, CellStyle quoted, CellStyle money) {
    }

    // Текст значения так же, как на экране: суммы с двумя знаками и пробелом между разрядами, пусто вместо null.
    static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
            symbols.setGroupingSeparator(' ');
            symbols.setDecimalSeparator(',');
            return new DecimalFormat("#,##0.00", symbols).format(decimal);
        }
        return value.toString();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String period(LocalDate start, LocalDate end) {
        if (start == null && end == null) {
            return "";
        }
        if (start != null && start.equals(end)) {
            return DATE.format(start);
        }
        return (start == null ? "…" : DATE.format(start)) + " – " + (end == null ? "…" : DATE.format(end));
    }
}
