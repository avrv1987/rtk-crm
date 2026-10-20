package ru.rtk.crm.agreement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.AgreementModels.ActivityAttribute;
import ru.rtk.crm.agreement.AgreementModels.ActivityKind;
import ru.rtk.crm.agreement.ReportingModels.AttributeOption;
import ru.rtk.crm.agreement.ReportingModels.ColumnOption;
import ru.rtk.crm.agreement.ReportingModels.Deadline;
import ru.rtk.crm.agreement.ReportingModels.DeadlineInput;
import ru.rtk.crm.agreement.ReportingModels.DeadlineReminder;
import ru.rtk.crm.agreement.ReportingModels.DeadlinesRequest;
import ru.rtk.crm.agreement.ReportingModels.ExportColumn;
import ru.rtk.crm.agreement.ReportingModels.PeriodKind;
import ru.rtk.crm.agreement.ReportingModels.PeriodRange;
import ru.rtk.crm.agreement.ReportingModels.Rate;
import ru.rtk.crm.agreement.ReportingModels.RateRequest;
import ru.rtk.crm.agreement.ReportingModels.ReferenceCategory;
import ru.rtk.crm.agreement.ReportingModels.ReferenceCreateRequest;
import ru.rtk.crm.agreement.ReportingModels.ReferenceItem;
import ru.rtk.crm.agreement.ReportingModels.ReferenceUpdateRequest;
import ru.rtk.crm.agreement.ReportingModels.ReportingReminders;
import ru.rtk.crm.agreement.ReportingModels.Settings;
import ru.rtk.crm.agreement.ReportingModels.SettingsRequest;
import ru.rtk.crm.agreement.ReportingModels.SettingsView;
import ru.rtk.crm.agreement.ReportingModels.TemplateRequest;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionValidationException;

// Справочники и настройки отчётности по соглашениям ведёт только администратор; читают их все сотрудники, потому что
// по ним заполняется план мероприятий и строится отчёт. Каждое изменение пишется в журнал администратора.
@Service
public class ReportingSettingsService {
    static final String OBJECT_TYPE = "AGREEMENT_REPORTING";
    private static final String OBJECT_NAME = "Отчётность по соглашениям";
    private static final int REMINDER_LIMIT = 50;

    private final ReportingRepository repository;
    private final AgreementRepository agreementRepository;
    private final AgreementService agreementService;
    private final AuditJournalRepository auditJournalRepository;
    private final OrganizationRepository organizationRepository;
    private final ObjectMapper objectMapper;
    private final ActivityListSilence silence;

    public ReportingSettingsService(
            ReportingRepository repository,
            AgreementRepository agreementRepository,
            AgreementService agreementService,
            AuditJournalRepository auditJournalRepository,
            OrganizationRepository organizationRepository,
            ObjectMapper objectMapper,
            ActivityListSilence silence
    ) {
        this.repository = repository;
        this.agreementRepository = agreementRepository;
        this.agreementService = agreementService;
        this.auditJournalRepository = auditJournalRepository;
        this.organizationRepository = organizationRepository;
        this.objectMapper = objectMapper;
        this.silence = silence;
    }

    public static void requireStaff(CrmProfile profile) {
        if (profile.role() == UserRole.PARTNER) {
            throw AgreementException.forbidden();
        }
    }

    // ---------- справочники ----------

    @Transactional(readOnly = true)
    public List<ReferenceItem> references(CrmProfile profile, boolean includeArchived) {
        requireStaff(profile);
        return repository.findReferences(includeArchived);
    }

    @Transactional
    public ReferenceItem createReference(CrmProfile profile, ReferenceCreateRequest request, String idempotencyKey, String requestId) {
        AdminAuthorization.requireAdmin(profile);
        if (request.category() == null) {
            throw new InteractionValidationException("category", "Выберите справочник");
        }
        String name = requiredText(request.name(), "name");
        BigDecimal base = base(request.category(), request.baseAmount());
        return agreementService.idempotent(profile, CommandOperation.CREATE_AGREEMENT_REFERENCE, idempotencyKey,
                new CreateReferenceCommand(request.category(), name, base), ReferenceItem.class, () -> {
                    if (repository.referenceNameTaken(request.category(), name, null)) {
                        throw nameTaken();
                    }
                    UUID id = UUID.randomUUID();
                    repository.insertReference(id, request.category(), name, base, OffsetDateTime.now());
                    audit(profile, request.category().title() + ": добавлено «" + name + "»" + baseText(base), requestId);
                    return repository.findReference(id).orElseThrow(AgreementException::referenceNotFound);
                });
    }

    @Transactional
    public ReferenceItem updateReference(CrmProfile profile, UUID id, ReferenceUpdateRequest request, String idempotencyKey,
                                         String requestId) {
        AdminAuthorization.requireAdmin(profile);
        ReferenceItem current = repository.findReference(id).orElseThrow(AgreementException::referenceNotFound);
        int version = requiredVersion(request.version());
        String name = requiredText(request.name(), "name");
        boolean archived = Boolean.TRUE.equals(request.archived());
        BigDecimal base = base(current.category(), request.baseAmount());
        return agreementService.idempotent(profile, CommandOperation.UPDATE_AGREEMENT_REFERENCE, idempotencyKey,
                new UpdateReferenceCommand(id, version, name, archived, base), ReferenceItem.class, () -> {
                    if (repository.referenceNameTaken(current.category(), name, id)) {
                        throw nameTaken();
                    }
                    if (repository.updateReference(id, version, name, archived, base, OffsetDateTime.now()) != 1) {
                        throw AgreementException.referenceVersion(repository.findReference(id).map(ReferenceItem::version)
                                .orElse(current.version()));
                    }
                    List<String> changes = new ArrayList<>();
                    if (!name.equals(current.name())) {
                        changes.add("название «" + current.name() + "» → «" + name + "»");
                    }
                    if (archived != current.archived()) {
                        changes.add(archived ? "в архив" : "из архива");
                    }
                    if (!sameAmount(base, current.baseAmount())) {
                        changes.add("база: " + amountText(current.baseAmount()) + " → " + amountText(base));
                    }
                    audit(profile, current.category().title() + " «" + name + "»: "
                            + (changes.isEmpty() ? "без изменений" : String.join("; ", changes)), requestId);
                    return repository.findReference(id).orElseThrow(AgreementException::referenceNotFound);
                });
    }

    // ---------- ставки ----------

    @Transactional(readOnly = true)
    public List<Rate> rates(CrmProfile profile) {
        requireStaff(profile);
        return repository.findRates();
    }

    @Transactional
    public Rate createRate(CrmProfile profile, RateRequest request, String idempotencyKey, String requestId) {
        AdminAuthorization.requireAdmin(profile);
        RateValues values = rateValues(request, null);
        return agreementService.idempotent(profile, CommandOperation.CREATE_AGREEMENT_RATE, idempotencyKey, values, Rate.class, () -> {
            if (repository.rateExists(values.kindId(), values.unitId(), values.levelId(), values.effectiveFrom(), null)) {
                throw rateTaken();
            }
            UUID id = UUID.randomUUID();
            repository.insertRate(id, values.kindId(), values.unitId(), values.levelId(), values.effectiveFrom(), values.rate(),
                    OffsetDateTime.now());
            Rate created = repository.findRate(id).orElseThrow(AgreementException::rateNotFound);
            audit(profile, "ставка добавлена: " + rateText(created), requestId);
            return created;
        });
    }

    @Transactional
    public Rate updateRate(CrmProfile profile, UUID id, RateRequest request, String idempotencyKey, String requestId) {
        AdminAuthorization.requireAdmin(profile);
        Rate current = repository.findRate(id).orElseThrow(AgreementException::rateNotFound);
        int version = requiredVersion(request.version());
        RateValues values = rateValues(request, current);
        return agreementService.idempotent(profile, CommandOperation.UPDATE_AGREEMENT_RATE, idempotencyKey,
                new UpdateRateCommand(id, version, values), Rate.class, () -> {
                    if (repository.rateExists(values.kindId(), values.unitId(), values.levelId(), values.effectiveFrom(), id)) {
                        throw rateTaken();
                    }
                    if (repository.updateRate(id, version, values.kindId(), values.unitId(), values.levelId(), values.effectiveFrom(),
                            values.rate(), OffsetDateTime.now()) != 1) {
                        throw AgreementException.rateVersion(repository.findRate(id).map(Rate::version).orElse(current.version()));
                    }
                    Rate updated = repository.findRate(id).orElseThrow(AgreementException::rateNotFound);
                    audit(profile, "ставка изменена: " + rateText(current) + " → " + rateText(updated), requestId);
                    return updated;
                });
    }

    @Transactional
    public void deleteRate(CrmProfile profile, UUID id, Integer version, String idempotencyKey, String requestId) {
        AdminAuthorization.requireAdmin(profile);
        int expected = requiredVersion(version);
        agreementService.idempotent(profile, CommandOperation.DELETE_AGREEMENT_RATE, idempotencyKey, new DeleteRateCommand(id, expected),
                DeletedRate.class, () -> {
                    Rate current = repository.findRate(id).orElseThrow(AgreementException::rateNotFound);
                    if (repository.deleteRate(id, expected) != 1) {
                        throw AgreementException.rateVersion(current.version());
                    }
                    audit(profile, "ставка удалена: " + rateText(current), requestId);
                    return new DeletedRate(id);
                });
    }

    // ---------- настройки, сроки, шаблон ----------

    @Transactional(readOnly = true)
    public SettingsView view(CrmProfile profile) {
        requireStaff(profile);
        Settings settings = repository.findSettings();
        Template template = template();
        ReportingRepository.Saved saved = repository.findSaved().orElse(null);
        return new SettingsView(
                settings,
                template.columns(),
                template.isDefault(),
                Arrays.stream(AgreementReportService.Field.values()).map(field -> new ColumnOption(field.name(), field.title())).toList(),
                repository.findDeadlines(),
                Arrays.stream(ActivityAttribute.values()).map(attribute -> new AttributeOption(attribute, attribute.title())).toList(),
                currentPeriod(settings, LocalDate.now(AgreementService.ZONE)),
                saved == null ? null : saved.updatedAt(),
                saved == null ? null : saved.updatedByName()
        );
    }

    @Transactional
    public SettingsView saveSettings(CrmProfile profile, SettingsRequest request, String requestId) {
        AdminAuthorization.requireAdmin(profile);
        if (request == null) {
            throw new InteractionValidationException("body", "Передайте настройки");
        }
        boolean thresholdEnabled = Boolean.TRUE.equals(request.thresholdEnabled());
        BigDecimal percent = request.thresholdPercent();
        if (percent != null && (percent.signum() <= 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0
                || percent.stripTrailingZeros().scale() > 3)) {
            throw new InteractionValidationException("thresholdPercent", "Порог — от 0,001 до 100 %");
        }
        if (thresholdEnabled && percent == null) {
            throw new InteractionValidationException("thresholdPercent", "Укажите порог, чтобы включить свод против порога");
        }
        Settings next = new Settings(
                required(request.periodKind(), "periodKind"),
                rangeRequired(request.academicYearStartMonth(), 1, 12, "academicYearStartMonth"),
                range(request.silenceDays(), 1, 365, "silenceDays"),
                range(request.repeatSilenceDays(), 1, 365, "repeatSilenceDays"),
                rangeRequired(request.reminderDays(), 1, 365, "reminderDays"),
                thresholdEnabled,
                percent,
                AgreementService.money(request.thresholdBase(), "thresholdBase"),
                AgreementService.nullableText(request.thresholdBaseLabel())
        );
        Settings before = repository.findSettings();
        repository.saveSettings(next, profile.id(), OffsetDateTime.now());
        audit(profile, settingsChanges(before, next), requestId);
        return view(profile);
    }

    @Transactional
    public SettingsView saveTemplate(CrmProfile profile, TemplateRequest request, String requestId) {
        AdminAuthorization.requireAdmin(profile);
        if (request == null || request.columns() == null || request.columns().isEmpty()) {
            throw new InteractionValidationException("columns", "Выберите хотя бы одну колонку");
        }
        Set<String> seen = new HashSet<>();
        List<ExportColumn> columns = new ArrayList<>();
        for (ExportColumn column : request.columns()) {
            if (column == null) {
                throw new InteractionValidationException("columns", "Список не должен содержать пустых значений");
            }
            AgreementReportService.Field field = AgreementReportService.Field.find(column.code())
                    .orElseThrow(() -> new InteractionValidationException("columns", "Неизвестная колонка: " + column.code()));
            if (!seen.add(field.name())) {
                throw new InteractionValidationException("columns", "Колонка «" + field.title() + "» выбрана дважды");
            }
            String title = AgreementService.nullableText(column.title());
            columns.add(new ExportColumn(field.name(), title == null ? field.title() : title));
        }
        repository.saveColumnsJson(write(columns), profile.id(), OffsetDateTime.now());
        audit(profile, "шаблон выгрузки: " + String.join(", ", columns.stream().map(ExportColumn::title).toList()), requestId);
        return view(profile);
    }

    @Transactional
    public SettingsView resetTemplate(CrmProfile profile, String requestId) {
        AdminAuthorization.requireAdmin(profile);
        repository.saveColumnsJson(null, profile.id(), OffsetDateTime.now());
        audit(profile, "шаблон выгрузки: восстановлены колонки по умолчанию", requestId);
        return view(profile);
    }

    @Transactional
    public SettingsView saveDeadlines(CrmProfile profile, DeadlinesRequest request, String requestId) {
        AdminAuthorization.requireAdmin(profile);
        if (request == null || request.deadlines() == null) {
            throw new InteractionValidationException("deadlines", "Передайте список сроков");
        }
        LocalDate today = LocalDate.now(AgreementService.ZONE);
        List<DeadlineInput> deadlines = new ArrayList<>();
        for (DeadlineInput input : request.deadlines()) {
            if (input == null) {
                throw new InteractionValidationException("deadlines", "Список не должен содержать пустых значений");
            }
            if (input.dueOn() == null) {
                throw new InteractionValidationException("deadlines", "Укажите дату срока");
            }
            ru.rtk.crm.web.InputRules.requireDateWithin(input.dueOn(), "deadlines", today, 30, 30);
            deadlines.add(new DeadlineInput(requiredText(input.name(), "deadlines"), input.dueOn(),
                    AgreementService.nullableText(input.deliverable()), Boolean.TRUE.equals(input.annual())));
        }
        repository.replaceDeadlines(deadlines);
        audit(profile, "сроки отчётного цикла: " + (deadlines.isEmpty() ? "список очищен"
                : String.join("; ", deadlines.stream().map(deadline -> deadline.name() + " — " + deadline.dueOn()
                + (Boolean.TRUE.equals(deadline.annual()) ? " ежегодно" : "")).toList())), requestId);
        return view(profile);
    }

    // ---------- период и напоминания ----------

    public PeriodRange period(Settings settings, int year) {
        if (settings.periodKind() == PeriodKind.CALENDAR) {
            return new PeriodRange(year, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), year + " год");
        }
        LocalDate from = LocalDate.of(year, settings.academicYearStartMonth(), 1);
        LocalDate to = from.plusYears(1).minusDays(1);
        String label = to.getYear() == year ? "учебный год " + year : year + "/" + to.getYear() + " учебный год";
        return new PeriodRange(year, from, to, label);
    }

    public PeriodRange currentPeriod(Settings settings, LocalDate today) {
        int year = settings.periodKind() == PeriodKind.ACADEMIC && today.getMonthValue() < settings.academicYearStartMonth()
                ? today.getYear() - 1 : today.getYear();
        return period(settings, year);
    }

    // Напоминания КАМ и руководителю: сроки отчётного цикла в ближайшие N дней (N — настройка) и перечни их вузов,
    // которые ждут ответа вуза или возвращены на доработку.
    @Transactional(readOnly = true)
    public ReportingReminders reminders(CrmProfile profile) {
        requireStaff(profile);
        Settings settings = repository.findSettings();
        LocalDate today = LocalDate.now(AgreementService.ZONE);
        silence.settle(today);
        List<DeadlineReminder> deadlines = upcomingDeadlines(repository.findDeadlines(), today, settings.reminderDays());
        return new ReportingReminders(
                settings.reminderDays(),
                deadlines,
                organizationRepository.visibilityScope(profile)
                        .map(scope -> repository.findListReminders(scope, REMINDER_LIMIT))
                        .orElse(List.of())
        );
    }

    static List<DeadlineReminder> upcomingDeadlines(List<Deadline> deadlines, LocalDate today, int days) {
        List<DeadlineReminder> result = new ArrayList<>();
        for (Deadline deadline : deadlines) {
            LocalDate next = nextOccurrence(deadline, today);
            long left = ChronoUnit.DAYS.between(today, next);
            if (left >= 0 && left <= days) {
                result.add(new DeadlineReminder(deadline.id(), deadline.name(), next, deadline.deliverable(), left));
            }
        }
        result.sort((first, second) -> first.dueOn().compareTo(second.dueOn()));
        return result;
    }

    // Ежегодный срок повторяется в тот же день и месяц: ближайший не раньше сегодняшнего; 29 февраля в невисокосный
    // год переносится на 28-е (так делает MonthDay.atYear).
    static LocalDate nextOccurrence(Deadline deadline, LocalDate today) {
        if (!deadline.annual()) {
            return deadline.dueOn();
        }
        MonthDay day = MonthDay.from(deadline.dueOn());
        LocalDate candidate = day.atYear(today.getYear());
        return candidate.isBefore(today) ? day.atYear(today.getYear() + 1) : candidate;
    }

    Template template() {
        String json = repository.findColumnsJson().orElse(null);
        if (json != null) {
            try {
                List<ExportColumn> stored = objectMapper.readValue(json, new TypeReference<List<ExportColumn>>() {
                });
                List<ExportColumn> known = stored.stream()
                        .filter(column -> AgreementReportService.Field.find(column.code()).isPresent())
                        .toList();
                if (!known.isEmpty()) {
                    return new Template(known, false);
                }
            } catch (JsonProcessingException exception) {
                // Повреждённый шаблон не должен ломать отчёт: действуют колонки по умолчанию.
            }
        }
        return new Template(Arrays.stream(AgreementReportService.Field.values())
                .map(field -> new ExportColumn(field.name(), field.title())).toList(), true);
    }

    Settings settings() {
        return repository.findSettings();
    }

    record Template(List<ExportColumn> columns, boolean isDefault) {
    }

    private RateValues rateValues(RateRequest request, Rate current) {
        if (request.kindId() == null) {
            throw new InteractionValidationException("kindId", "Выберите вид мероприятия");
        }
        ActivityKind kind = agreementRepository.findKind(request.kindId())
                .orElseThrow(() -> new InteractionValidationException("kindId", "Вид мероприятия не найден"));
        if (request.unitId() == null) {
            throw new InteractionValidationException("unitId", "Выберите единицу");
        }
        requireReference(request.unitId(), ReferenceCategory.UNIT, "unitId", current == null ? null : current.unitId());
        if (request.educationLevelId() != null) {
            requireReference(request.educationLevelId(), ReferenceCategory.EDUCATION_LEVEL, "educationLevelId",
                    current == null ? null : current.educationLevelId());
        }
        if (request.effectiveFrom() == null) {
            throw new InteractionValidationException("effectiveFrom", "Укажите дату начала действия ставки");
        }
        ru.rtk.crm.web.InputRules.requireDateWithin(request.effectiveFrom(), "effectiveFrom", LocalDate.now(AgreementService.ZONE), 30, 30);
        if (request.rate() == null) {
            throw new InteractionValidationException("rate", "Укажите ставку");
        }
        return new RateValues(kind.id(), request.unitId(), request.educationLevelId(), request.effectiveFrom(),
                AgreementService.money(request.rate(), "rate"));
    }

    private void requireReference(UUID id, ReferenceCategory category, String field, UUID currentId) {
        ReferenceItem item = repository.findReference(id)
                .filter(candidate -> candidate.category() == category)
                .orElseThrow(() -> new InteractionValidationException(field, "Значение не найдено в справочнике «" + category.title() + "»"));
        if (item.archived() && !id.equals(currentId)) {
            throw new InteractionValidationException(field, "Значение справочника в архиве; выберите действующее");
        }
    }

    private static BigDecimal base(ReferenceCategory category, BigDecimal value) {
        if (value != null && category != ReferenceCategory.EXECUTOR) {
            throw new InteractionValidationException("baseAmount", "База для порога задаётся только у исполнителя");
        }
        return AgreementService.money(value, "baseAmount");
    }

    private void audit(CrmProfile profile, String details, String requestId) {
        auditJournalRepository.record(AuditAction.AGREEMENT_REPORTING_CHANGED, profile.id(), OBJECT_TYPE, null, OBJECT_NAME,
                details.length() > 1990 ? details.substring(0, 1989) + "…" : details, requestId);
    }

    private static String settingsChanges(Settings before, Settings after) {
        List<String> changes = new ArrayList<>();
        change(changes, "период", periodText(before), periodText(after));
        change(changes, "срок ответа вуза", daysText(before.silenceDays()), daysText(after.silenceDays()));
        change(changes, "срок повторного ответа", daysText(before.repeatSilenceDays()), daysText(after.repeatSilenceDays()));
        change(changes, "напоминать за", before.reminderDays() + " дн.", after.reminderDays() + " дн.");
        change(changes, "свод против порога", before.thresholdEnabled() ? "включён" : "выключен",
                after.thresholdEnabled() ? "включён" : "выключен");
        change(changes, "порог", amountText(before.thresholdPercent()) + " %", amountText(after.thresholdPercent()) + " %");
        change(changes, "база", amountText(before.thresholdBase()), amountText(after.thresholdBase()));
        change(changes, "название базы", String.valueOf(before.thresholdBaseLabel()), String.valueOf(after.thresholdBaseLabel()));
        return changes.isEmpty() ? "настройки сохранены без изменений" : String.join("; ", changes);
    }

    private static void change(List<String> changes, String label, String before, String after) {
        if (!Objects.equals(before, after)) {
            changes.add(label + ": " + before + " → " + after);
        }
    }

    private static String periodText(Settings settings) {
        return settings.periodKind() == PeriodKind.CALENDAR ? "календарный год"
                : "учебный год с " + settings.academicYearStartMonth() + "-го месяца";
    }

    private static String daysText(Integer days) {
        return days == null ? "не задан" : days + " дн.";
    }

    private static String amountText(BigDecimal value) {
        return value == null ? "не задана" : value.stripTrailingZeros().toPlainString();
    }

    private static String baseText(BigDecimal base) {
        return base == null ? "" : ", база " + amountText(base);
    }

    private static boolean sameAmount(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private static String rateText(Rate rate) {
        return rate.kindName() + " / " + rate.unitName() + (rate.educationLevelName() == null ? "" : " / " + rate.educationLevelName())
                + " с " + rate.effectiveFrom() + ": " + amountText(rate.rate());
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Report template cannot be stored", exception);
        }
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new InteractionValidationException(field, "Укажите значение");
        }
        return value;
    }

    private static int rangeRequired(Integer value, int min, int max, String field) {
        return range(required(value, field), min, max, field);
    }

    private static Integer range(Integer value, int min, int max, String field) {
        if (value != null && (value < min || value > max)) {
            throw new InteractionValidationException(field, "Значение — от " + min + " до " + max);
        }
        return value;
    }

    private static int requiredVersion(Integer version) {
        if (version == null) {
            throw new InteractionValidationException("version", "Укажите версию, которую вы изменяете");
        }
        return version;
    }

    private static String requiredText(String value, String field) {
        String normalized = AgreementService.nullableText(value);
        if (normalized == null) {
            throw new InteractionValidationException(field, "Заполните поле");
        }
        if (normalized.length() > 200) {
            throw new InteractionValidationException(field, "Не длиннее 200 символов");
        }
        return normalized;
    }

    private static InteractionValidationException nameTaken() {
        return new InteractionValidationException("name", "Такое значение в справочнике уже есть");
    }

    private static InteractionValidationException rateTaken() {
        return new InteractionValidationException("effectiveFrom", "Ставка для этого сочетания с этой даты уже есть");
    }

    private record CreateReferenceCommand(ReferenceCategory category, String name, BigDecimal baseAmount) {
    }

    private record UpdateReferenceCommand(UUID id, int version, String name, boolean archived, BigDecimal baseAmount) {
    }

    private record RateValues(UUID kindId, UUID unitId, UUID levelId, LocalDate effectiveFrom, BigDecimal rate) {
    }

    private record UpdateRateCommand(UUID id, int version, RateValues values) {
    }

    private record DeleteRateCommand(UUID id, int version) {
    }

    record DeletedRate(UUID id) {
    }
}
