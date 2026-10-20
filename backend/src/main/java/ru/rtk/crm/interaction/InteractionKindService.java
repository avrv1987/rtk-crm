package ru.rtk.crm.interaction;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.CatalogRepository;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKind;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindRequest;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindResolution;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindRule;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindRuleRequest;
import ru.rtk.crm.interaction.InteractionKindModels.KindSource;
import ru.rtk.crm.interaction.InteractionKindModels.ResolvedRoute;
import ru.rtk.crm.interaction.InteractionKindModels.TeamTemplateRequest;
import ru.rtk.crm.interaction.InteractionKindModels.TemplateSource;

@Service
public class InteractionKindService {
    private static final int MAX_SORT_ORDER = 10_000;

    private final InteractionKindRepository repository;
    private final WorkflowTemplateRepository workflowTemplateRepository;
    private final OrganizationRepository organizationRepository;
    private final CatalogRepository catalogRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final AuditJournalRepository auditJournalRepository;
    private final ObjectMapper objectMapper;

    public InteractionKindService(
            InteractionKindRepository repository,
            WorkflowTemplateRepository workflowTemplateRepository,
            OrganizationRepository organizationRepository,
            CatalogRepository catalogRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            AuditJournalRepository auditJournalRepository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.workflowTemplateRepository = workflowTemplateRepository;
        this.organizationRepository = organizationRepository;
        this.catalogRepository = catalogRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.auditJournalRepository = auditJournalRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<InteractionKind> available(CrmProfile profile) {
        return repository.findKinds(profile.teamId(), true);
    }

    // Для фильтров списков и отчётов: отключённый вид остаётся у карточек, поэтому по нему тоже нужно отбирать.
    @Transactional(readOnly = true)
    public List<InteractionKind> availableWithInactive(CrmProfile profile) {
        return repository.findKinds(profile.teamId(), false);
    }

    @Transactional(readOnly = true)
    public List<InteractionKind> managed(CrmProfile profile) {
        requireKindReader(profile);
        return repository.findKinds(profile.role() == UserRole.LEADER ? profile.teamId() : null, false);
    }

    @Transactional
    public InteractionKind create(CrmProfile profile, InteractionKindRequest request, String idempotencyKey) {
        AdminAuthorization.requireAdmin(profile);
        KindValues values = kindValues(request, null);
        requireDefaultActive(values);
        return idempotent(profile, CommandOperation.CREATE_INTERACTION_KIND, idempotencyKey, values, InteractionKind.class, () -> {
            UUID id = UUID.randomUUID();
            int sortOrder = values.sortOrder() == null ? repository.nextSortOrder() : values.sortOrder();
            repository.insertKind(id, values.name(), values.description(), values.active(), values.templateId(), sortOrder,
                    OffsetDateTime.now());
            if (Boolean.TRUE.equals(values.defaultKind())) {
                repository.makeDefault(id);
            }
            InteractionKind created = repository.findKind(id, null).orElseThrow(InteractionKindNotFoundException::new);
            journal(profile, AuditAction.INTERACTION_KIND_CHANGED, "INTERACTION_KIND", id, created.name(), "добавлен; " + describe(created));
            return created;
        });
    }

    @Transactional
    public InteractionKind update(CrmProfile profile, UUID kindId, InteractionKindRequest request, String idempotencyKey) {
        AdminAuthorization.requireAdmin(profile);
        InteractionKind current = repository.findKindForUpdate(kindId).orElseThrow(InteractionKindNotFoundException::new);
        int version = requiredVersion(request == null ? null : request.version());
        KindValues values = kindValues(request, kindId);
        // Хотя бы один вид остаётся действующим: без него новой карточке, импорту и источникам нечего назначить.
        if (current.active() && !values.active() && repository.countActiveExcept(kindId) == 0) {
            throw new InteractionValidationException("active", "Нужен хотя бы один действующий вид взаимодействия");
        }
        // Вид по умолчанию задаётся явно (Н-16): раньше им молча становился первый по порядку, и новый вид с порядком 0
        // менял вид всех новых карточек. Снять отметку можно, только назначив вид по умолчанию другому виду.
        if (current.defaultKind() && (!values.active() || Boolean.FALSE.equals(values.defaultKind()))) {
            throw new InteractionValidationException(values.active() ? "defaultKind" : "active",
                    "Это вид по умолчанию для новых карточек: сначала назначьте вид по умолчанию другому виду");
        }
        requireDefaultActive(values);
        return idempotent(profile, CommandOperation.UPDATE_INTERACTION_KIND, idempotencyKey,
                new UpdateKindCommand(kindId, version, values), InteractionKind.class, () -> {
                    int sortOrder = values.sortOrder() == null ? current.sortOrder() : values.sortOrder();
                    if (!repository.updateKind(kindId, version, values.name(), values.description(), values.active(),
                            values.templateId(), sortOrder, OffsetDateTime.now())) {
                        throw InteractionConflictException.version(current.version());
                    }
                    if (Boolean.TRUE.equals(values.defaultKind()) && !current.defaultKind()) {
                        repository.makeDefault(kindId);
                    }
                    InteractionKind updated = repository.findKind(kindId, null).orElseThrow(InteractionKindNotFoundException::new);
                    journal(profile, AuditAction.INTERACTION_KIND_CHANGED, "INTERACTION_KIND", kindId, updated.name(),
                            "изменён; было: " + describe(current) + "; стало: " + describe(updated));
                    return updated;
                });
    }

    // Командный шаблон вида — та же модель, что командный шаблон по умолчанию: руководитель выбирает шаблон своей
    // команды или общий, сброс (templateId = null) возвращает команду к шаблону вида.
    @Transactional
    public InteractionKind setTeamTemplate(CrmProfile profile, UUID kindId, TeamTemplateRequest request, String idempotencyKey) {
        if (profile.role() != UserRole.LEADER || profile.teamId() == null) {
            throw new WorkflowTemplateAccessDeniedException();
        }
        InteractionKind current = repository.findKind(kindId, profile.teamId()).orElseThrow(InteractionKindNotFoundException::new);
        UUID templateId = request == null ? null : request.templateId();
        String templateName = null;
        if (templateId != null) {
            WorkflowTemplateRepository.WorkflowTemplateRow template = workflowTemplateRepository.findById(templateId)
                    .filter(row -> row.teamId() == null || row.teamId().equals(profile.teamId()))
                    .orElseThrow(() -> new InteractionValidationException("templateId", "Шаблон процесса недоступен"));
            templateName = template.name();
        }
        String chosenName = templateName;
        return idempotent(profile, CommandOperation.SET_INTERACTION_KIND_TEAM_TEMPLATE, idempotencyKey,
                new TeamTemplateCommand(kindId, profile.teamId(), templateId), InteractionKind.class, () -> {
                    repository.replaceTeamTemplate(kindId, profile.teamId(), templateId, OffsetDateTime.now());
                    journal(profile, AuditAction.INTERACTION_KIND_CHANGED, "INTERACTION_KIND", kindId, current.name(),
                            chosenName == null
                                    ? "команда вернулась к шаблону вида"
                                    : "шаблон команды: «" + chosenName + "»");
                    return repository.findKind(kindId, profile.teamId()).orElseThrow(InteractionKindNotFoundException::new);
                });
    }

    @Transactional(readOnly = true)
    public List<InteractionKindRule> rules(CrmProfile profile) {
        AdminAuthorization.requireAdmin(profile);
        return repository.findRules();
    }

    @Transactional
    public InteractionKindRule createRule(CrmProfile profile, InteractionKindRuleRequest request, String idempotencyKey) {
        AdminAuthorization.requireAdmin(profile);
        RuleValues values = ruleValues(request);
        requireNoTwin(values, null);
        return idempotent(profile, CommandOperation.CREATE_INTERACTION_KIND_RULE, idempotencyKey, values, InteractionKindRule.class, () -> {
            UUID id = UUID.randomUUID();
            repository.insertRule(id, values.kindId(), values.organizationType(), values.programId(), values.productId(),
                    values.priority(), values.active(), OffsetDateTime.now());
            InteractionKindRule created = repository.findRule(id).orElseThrow(InteractionKindNotFoundException::new);
            journal(profile, AuditAction.INTERACTION_KIND_RULE_CHANGED, "INTERACTION_KIND_RULE", id, created.kindName(),
                    "добавлено: " + describe(created));
            return created;
        });
    }

    @Transactional
    public InteractionKindRule updateRule(CrmProfile profile, UUID ruleId, InteractionKindRuleRequest request, String idempotencyKey) {
        AdminAuthorization.requireAdmin(profile);
        InteractionKindRule current = repository.findRule(ruleId).orElseThrow(InteractionKindNotFoundException::new);
        int version = requiredVersion(request == null ? null : request.version());
        RuleValues values = ruleValues(request);
        requireNoTwin(values, ruleId);
        return idempotent(profile, CommandOperation.UPDATE_INTERACTION_KIND_RULE, idempotencyKey,
                new UpdateRuleCommand(ruleId, version, values), InteractionKindRule.class, () -> {
                    if (!repository.updateRule(ruleId, version, values.kindId(), values.organizationType(), values.programId(),
                            values.productId(), values.priority(), values.active(), OffsetDateTime.now())) {
                        throw InteractionConflictException.version(current.version());
                    }
                    InteractionKindRule updated = repository.findRule(ruleId).orElseThrow(InteractionKindNotFoundException::new);
                    journal(profile, AuditAction.INTERACTION_KIND_RULE_CHANGED, "INTERACTION_KIND_RULE", ruleId, updated.kindName(),
                            "изменено; было: " + describe(current) + "; стало: " + describe(updated));
                    return updated;
                });
    }

    @Transactional
    public void deleteRule(CrmProfile profile, UUID ruleId, Integer version, String idempotencyKey) {
        AdminAuthorization.requireAdmin(profile);
        int expectedVersion = requiredVersion(version);
        Optional<InteractionKindRule> current = repository.findRule(ruleId);
        idempotent(profile, CommandOperation.DELETE_INTERACTION_KIND_RULE, idempotencyKey,
                new DeleteRuleCommand(ruleId, expectedVersion), Boolean.class, () -> {
                    InteractionKindRule rule = current.orElseThrow(InteractionKindNotFoundException::new);
                    if (!repository.deleteRule(ruleId, expectedVersion)) {
                        throw InteractionConflictException.version(rule.version());
                    }
                    journal(profile, AuditAction.INTERACTION_KIND_RULE_CHANGED, "INTERACTION_KIND_RULE", ruleId, rule.kindName(),
                            "удалено: " + describe(rule));
                    return Boolean.TRUE;
                });
    }

    // Подсказка формы создания: тот же расчёт, что выполнит сервер при создании, без записи.
    @Transactional(readOnly = true)
    public InteractionKindResolution resolve(CrmProfile profile, UUID organizationId, UUID kindId, UUID programId,
                                             List<UUID> productIds) {
        if (organizationId == null) {
            throw new InteractionValidationException("organizationId", "Укажите вуз");
        }
        organizationRepository.findVisibleById(profile, organizationId).orElseThrow(OrganizationNotFoundException::new);
        List<UUID> products = productIds == null ? List.of() : productIds.stream().filter(Objects::nonNull).distinct().toList();
        KindChoice kind = chooseKind(organizationId, kindId, programId, products);
        TemplateChoice template = chooseTemplate(kind.kindId(), organizationId, false);
        return new InteractionKindResolution(
                kind.kindId(),
                repository.findRef(kind.kindId()).map(InteractionKindModels.InteractionKindRef::name).orElse(null),
                kind.source(),
                template.template().id(),
                template.template().name(),
                template.source()
        );
    }

    /**
     * Вид и маршрут новой карточки. Вид: выбранный → первое подходящее правило → первый действующий по порядку.
     * Шаблон: выбранный вручную → командный для вида → шаблон вида → шаблон по умолчанию (командный, затем общий).
     * Шаблон блокируется до снимка этапов, чтобы маршрут не сменился посреди создания.
     */
    ResolvedRoute route(UUID organizationId, UUID kindId, UUID programId, List<UUID> productIds, UUID manualTemplateId) {
        KindChoice kind = chooseKind(organizationId, kindId, programId, productIds == null ? List.of() : productIds);
        if (manualTemplateId != null) {
            return new ResolvedRoute(kind.kindId(), null);
        }
        return new ResolvedRoute(kind.kindId(), chooseTemplate(kind.kindId(), organizationId, true).template());
    }

    /** Вид, который правила дают записи источника (сайт, Moodle): по нему ищется карточка для записи. */
    @Transactional(readOnly = true)
    public UUID sourceKind(UUID organizationId, UUID programId, List<UUID> productIds) {
        return chooseKind(organizationId, null, programId, productIds == null ? List.of() : productIds).kindId();
    }

    String kindName(UUID kindId) {
        return repository.findRef(kindId).map(InteractionKindModels.InteractionKindRef::name).orElse("");
    }

    @Transactional(readOnly = true)
    public UUID activeOrNull(UUID kindId) {

        return kindId != null && repository.activeKindExists(kindId) ? kindId : null;
    }

    private KindChoice chooseKind(UUID organizationId, UUID kindId, UUID programId, List<UUID> productIds) {
        if (kindId != null) {
            if (!repository.activeKindExists(kindId)) {
                throw new InteractionValidationException("kindId", "Вид взаимодействия недоступен; выберите действующий вид");
            }
            return new KindChoice(kindId, KindSource.SELECTED);
        }
        Optional<UUID> byRule = repository.findKindByRule(organizationId, programId, productIds);
        if (byRule.isPresent()) {
            return new KindChoice(byRule.get(), KindSource.RULE);
        }
        return new KindChoice(repository.findFirstActiveKind()
                .orElseThrow(() -> new InteractionValidationException("kindId", "Нет действующего вида взаимодействия")),
                KindSource.FIRST_ACTIVE);
    }

    private TemplateChoice chooseTemplate(UUID kindId, UUID organizationId, boolean lock) {
        Optional<UUID> teamTemplate = repository.findTeamTemplateId(kindId, organizationId);
        if (teamTemplate.isPresent()) {
            Optional<WorkflowTemplate> template = template(teamTemplate.get(), lock);
            if (template.isPresent()) {
                return new TemplateChoice(template.get(), TemplateSource.TEAM_KIND);
            }
        }
        Optional<UUID> kindTemplate = repository.findKindTemplateId(kindId);
        if (kindTemplate.isPresent()) {
            Optional<WorkflowTemplate> template = template(kindTemplate.get(), lock);
            if (template.isPresent()) {
                return new TemplateChoice(template.get(), TemplateSource.KIND);
            }
        }
        // Чтение без блокировки нужно подсказке формы: в транзакции только для чтения PostgreSQL не разрешает FOR UPDATE.
        return new TemplateChoice((lock
                ? workflowTemplateRepository.findDefaultForOrganizationForUpdate(organizationId)
                : workflowTemplateRepository.findDefaultForOrganization(organizationId, false))
                .orElseThrow(() -> new InteractionValidationException(
                        "templateId", "Нет шаблона процесса по умолчанию; создайте его перед созданием взаимодействия"
                )), TemplateSource.DEFAULT);
    }

    private Optional<WorkflowTemplate> template(UUID templateId, boolean lock) {
        return (lock ? workflowTemplateRepository.findByIdForUpdate(templateId) : workflowTemplateRepository.findById(templateId))
                .map(workflowTemplateRepository::toTemplate);
    }

    private void requireKindReader(CrmProfile profile) {
        if (profile.role() == UserRole.ADMIN || profile.role() == UserRole.LEADER && profile.teamId() != null) {
            return;
        }
        throw new WorkflowTemplateAccessDeniedException();
    }

    private KindValues kindValues(InteractionKindRequest request, UUID kindId) {
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        String name = requiredText(request.name(), "name", 200);
        if (repository.nameTaken(name, kindId)) {
            throw new InteractionValidationException("name", "Вид с таким названием уже есть");
        }
        String description = optionalText(request.description(), "description", 1000);
        // Шаблон вида — только общий: вид общий для всех команд, а командный маршрут задаёт руководитель отдельно.
        if (request.templateId() != null && workflowTemplateRepository.findById(request.templateId())
                .filter(template -> template.teamId() == null).isEmpty()) {
            throw new InteractionValidationException("templateId", "Для вида можно выбрать только общий шаблон процесса");
        }
        Integer sortOrder = request.sortOrder();
        if (sortOrder != null && (sortOrder < 0 || sortOrder > MAX_SORT_ORDER)) {
            throw new InteractionValidationException("sortOrder", "Порядок — целое число от 0 до " + MAX_SORT_ORDER);
        }
        return new KindValues(name, description, request.active() == null || request.active(), request.templateId(), sortOrder,
                request.defaultKind());
    }

    private static void requireDefaultActive(KindValues values) {
        if (Boolean.TRUE.equals(values.defaultKind()) && !values.active()) {
            throw new InteractionValidationException("defaultKind", "Вид по умолчанию должен действовать");
        }
    }

    private RuleValues ruleValues(InteractionKindRuleRequest request) {
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        if (request.kindId() == null || repository.findRef(request.kindId()).isEmpty()) {
            throw new InteractionValidationException("kindId", "Выберите вид взаимодействия");
        }
        if (request.organizationType() == null && request.programId() == null && request.productId() == null) {
            throw new InteractionValidationException("organizationType", "Укажите хотя бы одно условие: тип организации, программу или продукт");
        }
        // Правило на программу из архива никогда не сработает: новые карточки архивную программу не получают (Н-18).
        if (request.programId() != null && catalogRepository.findActiveProgramById(request.programId()).isEmpty()) {
            throw new InteractionValidationException("programId", "Выберите действующую программу справочника");
        }
        if (request.productId() != null && catalogRepository.findActiveProductIds(List.of(request.productId())).isEmpty()) {
            throw new InteractionValidationException("productId", "Выберите действующий продукт справочника");
        }
        int priority = request.priority() == null ? 100 : request.priority();
        if (priority < 1 || priority > 1000) {
            throw new InteractionValidationException("priority", "Приоритет — целое число от 1 до 1000");
        }
        return new RuleValues(request.kindId(), request.organizationType(), request.programId(), request.productId(), priority,
                request.active() == null || request.active());
    }

    // Два правила с теми же условиями и тем же приоритетом: одно из них никогда не сработает (Н-15). Частичные
    // пересечения не запрещаются — форма показывает, какое правило выиграет. Отключённое правило и правило
    // отключённого вида не действуют, поэтому двойником не считаются (аудит видов, Н-2).
    private void requireNoTwin(RuleValues values, UUID exceptId) {
        if (!values.active()) {
            return;
        }
        repository.findRules().stream()
                .filter(rule -> !rule.id().equals(exceptId))
                .filter(rule -> rule.active() && rule.kindActive())
                .filter(rule -> rule.priority() == values.priority()
                        && Objects.equals(rule.organizationType(), values.organizationType())
                        && Objects.equals(rule.programId(), values.programId())
                        && Objects.equals(rule.productId(), values.productId()))
                .findFirst()
                .ifPresent(twin -> {
                    throw new InteractionValidationException("priority", twin.kindId().equals(values.kindId())
                            ? "Такое правило уже есть"
                            : "Правило с теми же условиями и приоритетом " + twin.priority() + " уже ведёт к виду «" + twin.kindName()
                                    + "»: одно из них никогда не сработает; измените приоритет или условия");
                });
    }

    private static String describe(InteractionKind kind) {
        return "«" + kind.name() + "», " + (kind.active() ? "действует" : "не действует")
                + ", шаблон: " + (kind.templateName() == null ? "по умолчанию" : "«" + kind.templateName() + "»")
                + ", порядок " + kind.sortOrder() + (kind.defaultKind() ? ", вид по умолчанию" : "");
    }

    private static String describe(InteractionKindRule rule) {
        return "вид «" + rule.kindName() + "»"
                + (rule.organizationType() == null ? "" : ", тип " + rule.organizationType().name())
                + (rule.programName() == null ? "" : ", программа «" + rule.programName() + "»")
                + (rule.productName() == null ? "" : ", продукт «" + rule.productName() + "»")
                + ", приоритет " + rule.priority() + (rule.active() ? "" : ", отключено");
    }

    private void journal(CrmProfile profile, AuditAction action, String objectType, UUID objectId, String name, String details) {
        auditJournalRepository.record(action, profile.id(), objectType, objectId, name, details, null);
    }

    private <T> T idempotent(CrmProfile profile, CommandOperation operation, String idempotencyKey, Object command,
                             Class<T> resultType, Supplier<T> action) {
        String key = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        if (!commandIdempotencyRepository.reserve(commandId, profile.id(), operation, key, fingerprint, OffsetDateTime.now())) {
            CommandIdempotencyRepository.CommandRecord stored = commandIdempotencyRepository.find(profile.id(), operation, key)
                    .orElseThrow(() -> new IllegalStateException("Reserved interaction kind command is unavailable"));
            if (!fingerprint.equals(stored.requestFingerprint())) {
                throw InteractionConflictException.idempotency();
            }
            if (stored.resultJson() == null) {
                throw new IllegalStateException("Reserved interaction kind command has no result");
            }
            return read(stored.resultJson(), resultType);
        }
        T result = action.get();
        commandIdempotencyRepository.complete(commandId, write(result));
        return result;
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored interaction kind command result cannot be read", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Interaction kind command result cannot be stored", exception);
        }
    }

    private static int requiredVersion(Integer value) {
        if (value == null || value < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите страницу");
        }
        return value;
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException(field, "Заполните значение");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new InteractionValidationException(field, "Слишком длинное значение");
        }
        return normalized;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new InteractionValidationException(field, "Слишком длинное значение");
        }
        return normalized;
    }

    private record KindChoice(UUID kindId, KindSource source) {
    }

    private record TemplateChoice(WorkflowTemplate template, TemplateSource source) {
    }

    private record KindValues(String name, String description, boolean active, UUID templateId, Integer sortOrder,
                              Boolean defaultKind) {
    }

    private record RuleValues(UUID kindId, ru.rtk.crm.catalog.OrganizationType organizationType, UUID programId, UUID productId,
                              int priority, boolean active) {
    }

    private record UpdateKindCommand(UUID kindId, int version, KindValues values) {
    }

    private record TeamTemplateCommand(UUID kindId, UUID teamId, UUID templateId) {
    }

    private record UpdateRuleCommand(UUID ruleId, int version, RuleValues values) {
    }

    private record DeleteRuleCommand(UUID ruleId, int version) {
    }
}
