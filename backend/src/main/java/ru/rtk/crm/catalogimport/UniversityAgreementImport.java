package ru.rtk.crm.catalogimport;

import static ru.rtk.crm.catalogimport.CatalogImportIndex.normalized;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import ru.rtk.crm.agreement.AgreementDateRules;
import ru.rtk.crm.agreement.AgreementModels.ActivityKind;
import ru.rtk.crm.agreement.AgreementModels.ActivityStatus;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementRepository;
import ru.rtk.crm.agreement.AgreementRepository.SubjectLink;
import ru.rtk.crm.interaction.InteractionValidationException;

// Профиль «Договоры с вузами»: реестр договоров школы и вуза с предметом и планом мероприятий. Строка — договор и,
// если заполнено, одно мероприятие его плана; строки одного договора повторяют его данные. Существующий договор импорт
// не меняет (статус меняется только переходами с историей): совпавшая строка — «Без изменений», расхождение — конфликт.
@Component
class UniversityAgreementImport {
    static final Set<String> REQUIRED_FIELDS = Set.of("organizationName", "agreementNumber");
    static final Set<String> MAPPING_FIELDS = Set.of(
            "organizationName", "organizationExternalKey", "agreementNumber", "agreementStatus", "concludedOn", "validUntil",
            "sentOn", "parties", "programNames", "productNames", "directionNames", "activityKind", "activityTitle",
            "activityUnit", "activityPlannedVolume", "activityPlannedStart", "activityPlannedEnd", "activityStatus"
    );
    private static final List<String> ACTIVITY_FIELDS = List.of(
            "activityKind", "activityUnit", "activityPlannedVolume", "activityPlannedStart", "activityPlannedEnd", "activityStatus"
    );

    private final CatalogImportRepository catalogRepository;
    private final MigrationImportRepository repository;
    private final AgreementRepository agreementRepository;

    UniversityAgreementImport(
            CatalogImportRepository catalogRepository,
            MigrationImportRepository repository,
            AgreementRepository agreementRepository
    ) {
        this.catalogRepository = catalogRepository;
        this.repository = repository;
        this.agreementRepository = agreementRepository;
    }

    List<CatalogImportStoredRow> plan(CatalogImportWorkbookSheet sheet, CatalogImportMapping mapping, String fileName) {
        Map<String, MigrationImportRepository.UniversityAgreementRef> agreements = repository.findUniversityAgreements().stream()
                .collect(Collectors.toMap(agreement -> agreement.organizationId() + "|" + normalized(agreement.number()),
                        agreement -> agreement, (first, second) -> first));
        Set<String> activities = new HashSet<>();
        repository.findActivities().forEach(activity -> activities.add(activity.agreementId() + "|" + normalized(activity.title())));
        Catalogs catalogs = new Catalogs(
                new CatalogImportIndex<>(catalogRepository.findOrganizations(), organization -> normalized(organization.name())),
                agreements,
                activities,
                byName(repository.findNamed("programs")),
                byName(repository.findNamed("products")),
                byName(repository.findNamed("directions")),
                agreementRepository.findKinds(true).stream()
                        .collect(Collectors.toMap(kind -> normalized(kind.name()), kind -> kind, (first, second) -> first))
        );
        String source = "Импорт договоров" + (fileName == null || fileName.isBlank() ? "" : ", файл «" + fileName.strip() + "»");
        List<PlannedRow> rows = sheet.rows().stream().map(row -> new PlannedRow(row, sheet.name(), mapping)).toList();
        rows.forEach(row -> planRow(row, catalogs, source));
        PlannedRow.resolveConflicts(rows);
        return rows.stream().map(PlannedRow::stored).toList();
    }

    private void planRow(PlannedRow row, Catalogs catalogs, String source) {
        Optional<ImportOrganization> organization = ImportCells.organization(row, catalogs.organizations());
        String number = ImportCells.required(row, "agreementNumber", 100);
        String statusText = ImportCells.optional(row, "agreementStatus", 60);
        LocalDate concludedOn = ImportCells.date(row, "concludedOn");
        LocalDate validUntil = ImportCells.date(row, "validUntil");
        LocalDate sentOn = ImportCells.date(row, "sentOn");
        String parties = ImportCells.optional(row, "parties", 2000);
        AgreementStatus status = AgreementStatus.DRAFT;
        if (!statusText.isEmpty()) {
            status = agreementStatus(statusText);
            if (status == null) {
                row.error("agreementStatus", "Статус: проект, на согласовании, на корректировке, на подписании, действует, "
                        + "завершён или расторгнут");
                status = AgreementStatus.DRAFT;
            }
        }
        if (status.signed() && concludedOn == null && !row.errors.containsKey("concludedOn")) {
            row.error("concludedOn", "Укажите дату подписания");
        }
        if (!row.errors.containsKey("concludedOn") && !row.errors.containsKey("validUntil") && !row.errors.containsKey("sentOn")) {
            try {
                AgreementDateRules.check(status, concludedOn, validUntil, sentOn, LocalDate.now(ImportCells.ZONE));
            } catch (InteractionValidationException exception) {
                row.error(exception.field(), exception.getMessage());
            }
        }
        List<UUID> programIds = subject(row, "programNames", catalogs.programs(), "Программа");
        List<UUID> productIds = subject(row, "productNames", catalogs.products(), "Продукт");
        List<UUID> directionIds = subject(row, "directionNames", catalogs.directions(), "Направление");

        String activityTitle = ImportCells.optional(row, "activityTitle", 300);
        ActivityKind kind = null;
        ActivityStatus activityStatus = ActivityStatus.PLANNED;
        Integer plannedVolume = null;
        LocalDate plannedStart = ImportCells.date(row, "activityPlannedStart");
        LocalDate plannedEnd = ImportCells.date(row, "activityPlannedEnd");
        String unit = ImportCells.optional(row, "activityUnit", 50);
        if (activityTitle.isEmpty()) {
            if (ACTIVITY_FIELDS.stream().anyMatch(field -> !CatalogImportIndex.clean(row.values.get(field)).isEmpty())) {
                row.error("activityTitle", "Укажите название мероприятия");
            }
        } else {
            String kindName = ImportCells.required(row, "activityKind", 200);
            if (!kindName.isEmpty()) {
                kind = catalogs.kinds().get(normalized(kindName));
                if (kind == null) {
                    row.error("activityKind", "Вид мероприятия «" + kindName + "» не найден в справочнике");
                } else if (kind.archived()) {
                    row.error("activityKind", "Вид мероприятия «" + kind.name() + "» в архиве; выберите действующий");
                }
            }
            String statusValue = ImportCells.optional(row, "activityStatus", 60);
            if (!statusValue.isEmpty()) {
                activityStatus = activityStatus(statusValue);
                if (activityStatus == null) {
                    row.error("activityStatus", "Статус мероприятия: запланировано, выполняется, выполнено или отменено");
                }
            }
            String volume = ImportCells.optional(row, "activityPlannedVolume", 12);
            if (!volume.isEmpty()) {
                try {
                    plannedVolume = Integer.valueOf(volume.endsWith(".0") ? volume.substring(0, volume.length() - 2) : volume);
                    if (plannedVolume < 0) {
                        row.error("activityPlannedVolume", "Объём не может быть отрицательным");
                    }
                } catch (NumberFormatException exception) {
                    row.error("activityPlannedVolume", "Укажите целое число");
                }
            }
            if (plannedStart != null && plannedEnd != null && plannedStart.isAfter(plannedEnd)) {
                row.error("activityPlannedEnd", "Плановое окончание раньше планового начала");
            }
        }
        if (organization.isEmpty() || row.hasErrors()) {
            return;
        }
        UUID organizationId = organization.get().id();
        String identity = organizationId + "|" + normalized(number);
        row.values.put("organizationId", organizationId.toString());
        row.values.put("agreementNumber", number);
        row.values.put("agreementStatusCode", status.name());
        row.values.put("concludedOn", text(concludedOn));
        row.values.put("validUntil", text(validUntil));
        row.values.put("sentOn", text(sentOn));
        row.values.put("programIds", join(programIds));
        row.values.put("productIds", join(productIds));
        row.values.put("directionIds", join(directionIds));
        row.values.put("importSource", source + ", строка " + row.rowNumber);

        MigrationImportRepository.UniversityAgreementRef existing = catalogs.agreements().get(identity);
        if (existing == null) {
            row.compare(false, "agreementNumber", "", number);
            row.compare(false, "agreementStatus", "", status.title());
            row.compare(false, "concludedOn", "", text(concludedOn));
            row.compare(false, "validUntil", "", text(validUntil));
        } else {
            row.values.put("agreementNumber", existing.number());
            row.compare(true, "agreementNumber", existing.number(), existing.number());
            List<String> differences = new ArrayList<>();
            if (row.mapped("agreementStatus") && !statusText.isEmpty() && !existing.status().equals(status.name())) {
                differences.add("статус «" + AgreementStatus.valueOf(existing.status()).title() + "» → «" + status.title() + "»");
            }
            difference(differences, row, "concludedOn", "дата подписания", existing.concludedOn(), concludedOn);
            difference(differences, row, "validUntil", "действует до", existing.validUntil(), validUntil);
            difference(differences, row, "sentOn", "дата отправки", existing.sentOn(), sentOn);
            if (row.mapped("parties") && !normalized(existing.parties()).equals(normalized(parties))) {
                differences.add("стороны");
            }
            if (!differences.isEmpty()) {
                row.conflict("agreementNumber", "Договор № " + existing.number() + " уже есть в CRM с другими данными ("
                        + String.join(", ", differences) + "); импорт не меняет существующие договоры, правка — в карточке договора");
            }
        }
        row.claim("universityAgreement", identity, "agreementNumber",
                String.join("|", status.name(), text(concludedOn), text(validUntil), text(sentOn), normalized(parties),
                        join(programIds), join(productIds), join(directionIds)),
                "другие данные договора");

        if (!activityTitle.isEmpty()) {
            row.values.put("activityKindId", kind.id().toString());
            row.values.put("activityStatusCode", activityStatus.name());
            row.values.put("activityPlannedVolume", plannedVolume == null ? "" : plannedVolume.toString());
            row.values.put("activityPlannedStart", text(plannedStart));
            row.values.put("activityPlannedEnd", text(plannedEnd));
            boolean activityExists = existing != null && catalogs.activities().contains(existing.id() + "|" + normalized(activityTitle));
            row.compare(activityExists, "activityTitle", activityExists ? activityTitle : "", activityTitle);
            row.claim("agreementActivity", identity + "|" + normalized(activityTitle), "activityTitle",
                    String.join("|", kind.id().toString(), activityStatus.name(), String.valueOf(plannedVolume), text(plannedStart),
                            text(plannedEnd), normalized(unit)),
                    "другие данные мероприятия");
        }
    }

    void apply(CatalogImportPlan plan, UUID actorProfileId, OffsetDateTime now) {
        Map<String, String> values = plan.values();
        UUID organizationId = UUID.fromString(values.get("organizationId"));
        String number = values.get("agreementNumber");
        UUID agreementId = repository.findUniversityAgreement(organizationId, number)
                .map(MigrationImportRepository.UniversityAgreementRef::id)
                .orElse(null);
        if (agreementId == null) {
            agreementId = UUID.randomUUID();
            AgreementStatus status = AgreementStatus.valueOf(values.get("agreementStatusCode"));
            agreementRepository.insertAgreement(agreementId, organizationId, new AgreementRepository.AgreementValues(
                    number, date(values.get("concludedOn")), date(values.get("validUntil")), date(values.get("sentOn")),
                    nullable(values.get("parties")), null, null, null, null
            ), status, actorProfileId, now);
            // Первая строка истории статусов — статус из реестра с пометкой источника: история не выдаёт его за переход в CRM.
            agreementRepository.insertStatusEvent(agreementId, null, status, values.get("importSource"), actorProfileId, null, now);
            agreementRepository.replaceLinks(agreementId, SubjectLink.PROGRAMS, ids(values.get("programIds")));
            agreementRepository.replaceLinks(agreementId, SubjectLink.PRODUCTS, ids(values.get("productIds")));
            agreementRepository.replaceLinks(agreementId, SubjectLink.DIRECTIONS, ids(values.get("directionIds")));
        }
        String title = values.getOrDefault("activityTitle", "");
        if (!title.isEmpty() && !repository.hasActivity(agreementId, title)) {
            String volume = values.getOrDefault("activityPlannedVolume", "");
            agreementRepository.insertActivity(UUID.randomUUID(), agreementId, new AgreementRepository.ActivityValues(
                    UUID.fromString(values.get("activityKindId")), title, nullable(values.get("activityUnit")),
                    volume.isEmpty() ? null : Integer.valueOf(volume), null,
                    date(values.get("activityPlannedStart")), date(values.get("activityPlannedEnd")), null, null, null,
                    ActivityStatus.valueOf(values.get("activityStatusCode"))
            ), now);
        }
    }

    private static List<UUID> subject(PlannedRow row, String field, Map<String, List<UUID>> catalog, String label) {
        String value = ImportCells.optional(row, field, 2000);
        Set<UUID> ids = new LinkedHashSet<>();
        for (String name : value.split("[;\\n]")) {
            String clean = CatalogImportIndex.clean(name);
            if (clean.isEmpty()) {
                continue;
            }
            List<UUID> found = catalog.getOrDefault(normalized(clean), List.of());
            if (found.isEmpty()) {
                row.error(field, label + " «" + clean + "» не найден в справочнике");
            } else if (found.size() > 1) {
                row.error(field, label + " «" + clean + "»: в справочнике несколько записей с таким названием; выберите в карточке договора");
            } else {
                ids.add(found.getFirst());
            }
        }
        return List.copyOf(ids);
    }

    private static void difference(List<String> differences, PlannedRow row, String field, String label, LocalDate current, LocalDate next) {
        if (row.mapped(field) && next != null && !next.equals(current)) {
            differences.add(label + " " + text(current) + " → " + text(next));
        }
    }

    private static AgreementStatus agreementStatus(String value) {
        String key = normalized(value);
        for (AgreementStatus status : AgreementStatus.values()) {
            if (key.equals(normalized(status.title())) || key.equals(status.name().toLowerCase(java.util.Locale.ROOT))) {
                return status;
            }
        }
        return switch (key) {
            case "черновик" -> AgreementStatus.DRAFT;
            case "активный", "действующий", "подписан" -> AgreementStatus.ACTIVE;
            case "завершен", "закрыт", "истек" -> AgreementStatus.COMPLETED;
            default -> null;
        };
    }

    private static ActivityStatus activityStatus(String value) {
        String key = normalized(value);
        for (ActivityStatus status : ActivityStatus.values()) {
            // «Выполнено» — прежняя подпись DONE, теперь «Проведено»: файлы реестров с ней по-прежнему принимаются.
            if (key.equals(normalized(status.title())) || key.equals(status.name().toLowerCase(java.util.Locale.ROOT))
                    || status == ActivityStatus.DONE && key.equals(normalized("Выполнено"))) {
                return status;
            }
        }
        return null;
    }

    private static Map<String, List<UUID>> byName(List<MigrationImportRepository.NamedRef> items) {
        Map<String, List<UUID>> names = new HashMap<>();
        items.forEach(item -> names.computeIfAbsent(normalized(item.name()), ignored -> new ArrayList<>()).add(item.id()));
        return names;
    }

    private static String text(LocalDate value) {
        return value == null ? "" : value.toString();
    }

    private static LocalDate date(String value) {
        return value == null || value.isEmpty() ? null : LocalDate.parse(value);
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String join(List<UUID> ids) {
        return ids.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
    }

    private static List<UUID> ids(String value) {
        return value == null || value.isEmpty() ? List.of() : java.util.Arrays.stream(value.split(",")).map(UUID::fromString).toList();
    }

    private record Catalogs(
            CatalogImportIndex<ImportOrganization> organizations,
            Map<String, MigrationImportRepository.UniversityAgreementRef> agreements,
            Set<String> activities,
            Map<String, List<UUID>> programs,
            Map<String, List<UUID>> products,
            Map<String, List<UUID>> directions,
            Map<String, ActivityKind> kinds
    ) {
    }
}
