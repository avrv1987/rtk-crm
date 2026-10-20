package ru.rtk.crm.catalogimport;

import static ru.rtk.crm.catalogimport.CatalogImportIndex.normalized;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionContactChannel;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionValidationException;

// Профиль «История взаимодействий»: события прошлых периодов с их датами. Событие помечается как импортированное
// (источник и автор из файла), записывает его администратор импорта — сотрудником, сделавшим действие, оно не выдаётся
// (ТЗ, импортированный комментарий). Повтор файла событий не дублирует: у каждого события ключ строки.
@Component
class InteractionHistoryImport {
    static final Set<String> REQUIRED_FIELDS = Set.of("organizationName", "eventDate", "eventType");
    static final Set<String> MAPPING_FIELDS = Set.of(
            "organizationName", "organizationExternalKey", "interactionTitle", "interactionExternalKey", "interactionId",
            "eventDate", "eventType", "stageName", "eventText", "eventAuthor", "contactChannel", "stepDueDate", "rowKey"
    );
    static final String IMPORT_AUTHOR = "импорт";
    private static final String NEW_TITLE = "История взаимодействия (импорт)";
    private static final int TEXT_LIMIT = 4_000;
    private static final int KEY_LIMIT = 200;
    private static final int YEARS_BACK = 30;
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Map<String, EventKind> KINDS = kinds();
    private static final Map<String, InteractionContactChannel> CHANNELS = channels();

    private final CatalogImportRepository catalogRepository;
    private final MigrationImportRepository repository;
    private final InteractionService interactionService;
    private final ImportSettingsService settingsService;
    private final ObjectMapper objectMapper;

    InteractionHistoryImport(
            CatalogImportRepository catalogRepository,
            MigrationImportRepository repository,
            InteractionService interactionService,
            ImportSettingsService settingsService,
            ObjectMapper objectMapper
    ) {
        this.catalogRepository = catalogRepository;
        this.repository = repository;
        this.interactionService = interactionService;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    List<CatalogImportStoredRow> plan(CatalogImportWorkbookSheet sheet, CatalogImportMapping mapping, String fileName) {
        List<MigrationImportRepository.InteractionRef> interactions = repository.findInteractions();
        Catalogs catalogs = new Catalogs(
                new CatalogImportIndex<>(catalogRepository.findOrganizations(), organization -> normalized(organization.name())),
                interactions.stream().collect(Collectors.groupingBy(MigrationImportRepository.InteractionRef::organizationId)),
                interactions.stream().filter(interaction -> interaction.externalKey() != null)
                        .collect(Collectors.toMap(MigrationImportRepository.InteractionRef::externalKey, value -> value)),
                interactions.stream().collect(Collectors.toMap(MigrationImportRepository.InteractionRef::id, value -> value)),
                repository.findStages().stream().collect(Collectors.groupingBy(
                        MigrationImportRepository.StageRef::interactionId,
                        Collectors.toMap(stage -> normalized(stage.name()), MigrationImportRepository.StageRef::name, (first, second) -> first)
                )),
                repository.findTemplateStageNames().stream()
                        .collect(Collectors.toMap(CatalogImportIndex::normalized, name -> name, (first, second) -> first)),
                repository.findProfileNamesByLogin()
        );
        String source = "Импорт истории" + (fileName == null || fileName.isBlank() ? "" : ", файл «" + fileName.strip() + "»");
        Map<String, Integer> keys = new HashMap<>();
        Map<String, Integer> newInteractions = new HashMap<>();
        List<PlannedRow> rows = sheet.rows().stream().map(row -> new PlannedRow(row, sheet.name(), mapping)).toList();
        for (PlannedRow row : rows) {
            planRow(row, catalogs, source, keys, newInteractions);
        }
        return rows.stream().map(PlannedRow::stored).toList();
    }

    private void planRow(PlannedRow row, Catalogs catalogs, String source, Map<String, Integer> keys, Map<String, Integer> newInteractions) {
        Optional<ImportOrganization> organization = ImportCells.organization(row, catalogs.organizations());
        OffsetDateTime occurredAt = ImportCells.dateTime(row, "eventDate");
        String typeText = ImportCells.required(row, "eventType", 60);
        String stageName = ImportCells.optional(row, "stageName", 200);
        String text = ImportCells.text(row, "eventText", TEXT_LIMIT);
        String authorLogin = ImportCells.optional(row, "eventAuthor", 200);
        String channelText = ImportCells.optional(row, "contactChannel", 60);
        java.time.LocalDate stepDue = ImportCells.date(row, "stepDueDate");
        String rowKey = ImportCells.optional(row, "rowKey", KEY_LIMIT);
        String title = ImportCells.optional(row, "interactionTitle", 200);
        String interactionKey = ImportCells.optional(row, "interactionExternalKey", KEY_LIMIT);
        UUID explicitId = row.target.interactionId() != null ? row.target.interactionId() : ImportCells.uuid(row, "interactionId");

        if (occurredAt != null) {
            OffsetDateTime now = OffsetDateTime.now(ImportCells.ZONE);
            if (occurredAt.isAfter(now)) {
                row.error("eventDate", "Дата события не может быть позже сегодняшней");
            } else if (occurredAt.isBefore(now.minusYears(YEARS_BACK))) {
                row.error("eventDate", "Дата события раньше чем " + YEARS_BACK + " лет назад");
            }
        }
        EventKind kind = typeText.isEmpty() ? null : KINDS.get(normalized(typeText));
        if (!typeText.isEmpty() && kind == null) {
            row.error("eventType", "Тип события: комментарий, переход этапа, контакт (звонок, встреча, письмо, мессенджер, "
                    + "мероприятие) или шаг");
        }
        InteractionContactChannel channel = null;
        if (kind != null && kind.type() == EventType.CONTACT) {
            channel = kind.channel();
            if (!channelText.isEmpty()) {
                channel = CHANNELS.get(normalized(channelText));
                if (channel == null) {
                    row.error("contactChannel", "Канал: встреча, звонок, письмо, мессенджер или мероприятие");
                }
            } else if (channel == null) {
                row.error("contactChannel", "Для контакта укажите канал: встреча, звонок, письмо, мессенджер или мероприятие");
            }
        }
        if (kind != null && (kind.type() == EventType.COMMENT || kind.type() == EventType.STEP) && text.isEmpty()) {
            row.error("eventText", kind.type() == EventType.STEP ? "Укажите текст шага" : "Укажите текст комментария");
        }
        if (kind != null && kind.type() == EventType.TRANSITION && stageName.isEmpty()) {
            row.error("stageName", "Для перехода укажите этап, на который перешли");
        }
        String author = IMPORT_AUTHOR;
        if (!authorLogin.isEmpty() && !normalized(authorLogin).equals(IMPORT_AUTHOR)) {
            author = catalogs.authors().get(normalized(authorLogin));
            if (author == null) {
                row.error("eventAuthor", "Сотрудник с логином «" + authorLogin + "» не найден; оставьте ячейку пустой или «импорт»");
                author = IMPORT_AUTHOR;
            }
        }

        if (organization.isEmpty()) {
            return;
        }
        UUID organizationId = organization.get().id();
        row.values.put("organizationId", organizationId.toString());
        row.values.put("importSource", source + ", строка " + row.rowNumber);
        row.values.put("importAuthor", author);

        // Ключ карточки в ключе события устойчив между загрузками: UUID, ключ, название или «единственная карточка вуза».
        String interactionToken;
        MigrationImportRepository.InteractionRef interaction = null;
        List<MigrationImportRepository.InteractionRef> own = catalogs.byOrganization().getOrDefault(organizationId, List.of());
        if (explicitId != null) {
            interactionToken = "id:" + explicitId;
            interaction = catalogs.byId().get(explicitId);
            if (interaction == null) {
                row.error("interactionId", "Взаимодействие с указанным UUID не найдено");
            } else if (!interaction.organizationId().equals(organizationId)) {
                row.conflict("interactionId", "Взаимодействие относится к другому вузу");
            }
        } else if (!interactionKey.isEmpty()) {
            interactionToken = "key:" + interactionKey;
            interaction = catalogs.byKey().get(interactionKey);
            if (interaction != null && !interaction.organizationId().equals(organizationId)) {
                row.conflict("interactionExternalKey", "Взаимодействие с этим ключом относится к другому вузу");
            }
        } else if (!title.isEmpty()) {
            interactionToken = "title:" + normalized(title);
            List<MigrationImportRepository.InteractionRef> named = own.stream()
                    .filter(candidate -> normalized(candidate.title()).equals(normalized(title)))
                    .toList();
            if (named.size() > 1) {
                row.conflict("interactionTitle", "У вуза несколько взаимодействий с таким названием; укажите ключ или UUID");
            } else if (named.size() == 1) {
                interaction = named.getFirst();
            }
        } else {
            interactionToken = "only";
            if (own.size() > 1) {
                row.conflict("interactionTitle", "У вуза несколько взаимодействий; укажите название, ключ или UUID взаимодействия");
            } else if (own.size() == 1) {
                interaction = own.getFirst();
            }
        }
        if (row.hasErrors()) {
            return;
        }

        String storedStage = "";
        if (interaction != null) {
            row.values.put("interactionId", interaction.id().toString());
            row.values.put("interactionTitle", interaction.title());
            row.compare(true, "interaction", interaction.title(), interaction.title());
            if (!stageName.isEmpty()) {
                storedStage = catalogs.stages().getOrDefault(interaction.id(), Map.of()).get(normalized(stageName));
                if (storedStage == null) {
                    row.error("stageName", "У взаимодействия «" + interaction.title() + "» нет этапа «" + stageName + "»");
                    return;
                }
            }
        } else {
            String newTitle = !title.isEmpty() ? title : !interactionKey.isEmpty() ? interactionKey : NEW_TITLE;
            String planKey = organizationId + "|" + interactionToken;
            row.values.put("interactionId", "");
            row.values.put("interactionTitle", newTitle);
            row.values.put("interactionExternalKey", interactionKey);
            row.values.put("interactionPlanKey", planKey);
            Integer plannedBy = newInteractions.putIfAbsent(planKey, row.rowNumber);
            row.compare(false, "interaction", "", plannedBy == null
                    ? "Новое взаимодействие «" + newTitle + "» по базовому процессу"
                    : "Взаимодействие, создаваемое строкой " + plannedBy);
            if (!stageName.isEmpty()) {
                storedStage = catalogs.templateStages().get(normalized(stageName));
                if (storedStage == null) {
                    row.error("stageName", "Этапа «" + stageName + "» нет в шаблонах маршрута");
                    return;
                }
            }
        }

        String key = rowKey.isEmpty()
                ? "history:" + CommandFingerprint.of(objectMapper, List.of(
                        organizationId.toString(), interactionToken, occurredAt.toInstant().toString(), kind.type().name(),
                        normalized(storedStage), text, author, channel == null ? "" : channel.name()))
                : "history-key:" + rowKey;
        if (key.length() > KEY_LIMIT) {
            key = "history-key:" + CommandFingerprint.of(objectMapper, rowKey);
        }
        Integer firstRow = keys.putIfAbsent(key, row.rowNumber);
        if (firstRow != null) {
            row.conflict(rowKey.isEmpty() ? "eventText" : "rowKey", "Повторяет событие строки " + firstRow);
            return;
        }
        row.values.put("historyKey", key);
        row.values.put("eventKind", kind.type().name());
        row.values.put("occurredAt", occurredAt.toString());
        row.values.put("stageName", storedStage);
        row.values.put("contactChannel", channel == null ? "" : channel.name());
        row.values.put("stepDueDate", stepDue == null ? "" : stepDue.toString());
        String shown = SHOWN.format(occurredAt.atZoneSameInstant(ImportCells.ZONE)) + " · " + kind.label(channel)
                + (storedStage.isEmpty() ? "" : " · этап «" + storedStage + "»")
                + (text.isEmpty() ? "" : " · " + abbreviate(text)) + " · автор: " + author;
        row.compare(catalogRepository.hasImportEvent(key), "historyEvent", shown, shown);
    }

    void apply(CatalogImportPlan plan, UUID actorProfileId, UUID commandId, OffsetDateTime now, Map<String, UUID> createdInteractions) {
        Map<String, String> values = plan.values();
        UUID organizationId = UUID.fromString(values.get("organizationId"));
        String interactionValue = values.getOrDefault("interactionId", "");
        UUID interactionId;
        if (!interactionValue.isEmpty()) {
            interactionId = UUID.fromString(interactionValue);
        } else {
            String planKey = values.get("interactionPlanKey");
            interactionId = createdInteractions.get(planKey);
            if (interactionId == null) {
                UUID ownerManagerId = catalogRepository.findOrganizationById(organizationId)
                        .map(ImportOrganization::ownerManagerId)
                        .orElseThrow(() -> InteractionConflictException.catalogImportVersion(0));
                interactionId = interactionService.createImportedInteraction(organizationId, ownerManagerId, values.get("interactionTitle"),
                        null, null, settingsService.defaultStep(now), actorProfileId, commandId, now);
                String externalKey = values.getOrDefault("interactionExternalKey", "");
                if (!externalKey.isEmpty()) {
                    repository.setInteractionExternalKey(interactionId, externalKey);
                }
                createdInteractions.put(planKey, interactionId);
            }
        }
        String key = values.get("historyKey");
        if (catalogRepository.hasImportEvent(key)) {
            return;
        }
        ImportInteraction interaction = catalogRepository.findInteractionByIdForUpdate(interactionId)
                .filter(found -> found.organizationId().equals(organizationId))
                .orElseThrow(() -> InteractionConflictException.catalogImportVersion(0));
        UUID stageId = interaction.currentStageId();
        String stageName = interaction.currentStageName();
        String requestedStage = values.getOrDefault("stageName", "");
        if (!requestedStage.isEmpty()) {
            MigrationImportRepository.StageRef stage = repository.findStages(interactionId).stream()
                    .filter(candidate -> normalized(candidate.name()).equals(normalized(requestedStage)))
                    .findFirst()
                    .orElseThrow(() -> new InteractionValidationException("stageName",
                            "У взаимодействия «" + interaction.title() + "» нет этапа «" + requestedStage + "»; исправьте файл"));
            stageId = stage.id();
            stageName = stage.name();
        }
        if (!catalogRepository.advanceInteractionVersion(interaction.id(), interaction.version(), now)) {
            throw InteractionConflictException.catalogImportVersion(interaction.version() + 1);
        }
        EventType type = EventType.valueOf(values.get("eventKind"));
        OffsetDateTime occurredAt = OffsetDateTime.parse(values.get("occurredAt"));
        String text = values.getOrDefault("eventText", "");
        String channel = values.getOrDefault("contactChannel", "");
        String due = values.getOrDefault("stepDueDate", "");
        repository.insertImportedEvent(new MigrationImportRepository.ImportedEvent(
                key,
                interaction.id(),
                commandId,
                switch (type) {
                    case COMMENT, CONTACT -> "COMMENTED";
                    case TRANSITION -> "TRANSITIONED";
                    case STEP -> "PLAN_UPDATED";
                },
                stageId,
                stageName,
                type == EventType.TRANSITION,
                type == EventType.STEP || text.isEmpty() ? null : text,
                type == EventType.STEP ? text : null,
                type == EventType.STEP && !due.isEmpty() ? java.time.LocalDate.parse(due).atStartOfDay(ImportCells.ZONE).toOffsetDateTime() : null,
                type == EventType.CONTACT ? channel : null,
                actorProfileId,
                interaction.ownerManagerId(),
                interaction.version() + 1,
                occurredAt,
                values.get("importSource"),
                values.get("importAuthor")
        ));
        if (type == EventType.CONTACT) {
            repository.advanceLastContact(interaction.id(), occurredAt);
        }
    }

    // Этап карточки ставится по самому позднему переходу из файла, только если после него в карточке не было других
    // переходов: ручная работа в CRM важнее старой истории.
    void applyStages(UUID commandId, OffsetDateTime now) {
        for (MigrationImportRepository.LastTransition transition : repository.findLatestImportedTransitions(commandId)) {
            if (transition.toStageId().equals(transition.currentStageId())) {
                continue;
            }
            if (!repository.moveToStage(transition.interactionId(), transition.toStageId(), transition.version(), now)) {
                throw InteractionConflictException.catalogImportVersion(transition.version() + 1);
            }
        }
    }

    private static String abbreviate(String text) {
        String line = text.replaceAll("\\s+", " ");
        return line.length() > 80 ? line.substring(0, 80) + "…" : line;
    }

    private static Map<String, EventKind> kinds() {
        Map<String, EventKind> kinds = new HashMap<>();
        for (String word : List.of("комментарий", "коммент", "примечание", "заметка", "comment", "note")) {
            kinds.put(word, new EventKind(EventType.COMMENT, null));
        }
        for (String word : List.of("переход", "переход этапа", "смена этапа", "этап", "transition", "stage")) {
            kinds.put(word, new EventKind(EventType.TRANSITION, null));
        }
        for (String word : List.of("контакт", "был контакт", "contact")) {
            kinds.put(word, new EventKind(EventType.CONTACT, null));
        }
        for (String word : List.of("шаг", "следующий шаг", "задача", "step", "task")) {
            kinds.put(word, new EventKind(EventType.STEP, null));
        }
        channels().forEach((word, channel) -> kinds.put(word, new EventKind(EventType.CONTACT, channel)));
        return Map.copyOf(kinds);
    }

    private static Map<String, InteractionContactChannel> channels() {
        Map<String, InteractionContactChannel> channels = new HashMap<>();
        for (InteractionContactChannel channel : InteractionContactChannel.values()) {
            channels.put(channel.label(), channel);
            channels.put(channel.name().toLowerCase(java.util.Locale.ROOT), channel);
        }
        channels.put("call", InteractionContactChannel.CALL);
        channels.put("встреча", InteractionContactChannel.MEETING);
        channels.put("email", InteractionContactChannel.EMAIL);
        channels.put("e-mail", InteractionContactChannel.EMAIL);
        channels.put("почта", InteractionContactChannel.EMAIL);
        channels.put("чат", InteractionContactChannel.MESSENGER);
        return Map.copyOf(channels);
    }

    enum EventType {
        COMMENT("Комментарий"),
        TRANSITION("Переход этапа"),
        CONTACT("Контакт"),
        STEP("Шаг");

        private final String label;

        EventType(String label) {
            this.label = label;
        }
    }

    private record EventKind(EventType type, InteractionContactChannel channel) {
        String label(InteractionContactChannel actual) {
            return type == EventType.CONTACT && actual != null ? "Контакт: " + actual.label() : type.label;
        }
    }

    private record Catalogs(
            CatalogImportIndex<ImportOrganization> organizations,
            Map<UUID, List<MigrationImportRepository.InteractionRef>> byOrganization,
            Map<String, MigrationImportRepository.InteractionRef> byKey,
            Map<UUID, MigrationImportRepository.InteractionRef> byId,
            Map<UUID, Map<String, String>> stages,
            Map<String, String> templateStages,
            Map<String, String> authors
    ) {
    }
}
