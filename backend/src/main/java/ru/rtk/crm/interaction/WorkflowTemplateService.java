package ru.rtk.crm.interaction;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.attachment.DocumentKinds;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;

@Service
public class WorkflowTemplateService {
    private final WorkflowTemplateRepository workflowTemplateRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;
    private final AuditJournalRepository auditJournalRepository;
    private final DocumentKinds documentKinds;

    public WorkflowTemplateService(
            WorkflowTemplateRepository workflowTemplateRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper,
            AuditJournalRepository auditJournalRepository,
            DocumentKinds documentKinds
    ) {
        this.workflowTemplateRepository = workflowTemplateRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
        this.auditJournalRepository = auditJournalRepository;
        this.documentKinds = documentKinds;
    }

    @Transactional(readOnly = true)
    public WorkflowTemplatePage listAvailable(CrmProfile profile, WorkflowTemplateQuery query) {
        return profile.teamId() == null
                ? workflowTemplateRepository.findGlobalPage(query)
                : workflowTemplateRepository.findPageForTeam(profile.teamId(), query)
                        .withTeamDefault(workflowTemplateRepository.findTeamDefaultId(profile.teamId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public WorkflowTemplatePage listManaged(CrmProfile profile, WorkflowTemplateQuery query) {
        requireTemplateManager(profile);
        return profile.role() == UserRole.ADMIN
                ? workflowTemplateRepository.findGlobalPage(query)
                : workflowTemplateRepository.findTeamPageWithDefault(profile.teamId(), query)
                        .withTeamDefault(workflowTemplateRepository.findTeamDefaultId(profile.teamId()).orElse(null));
    }

    @Transactional
    public WorkflowTemplate makeDefault(CrmProfile profile, UUID templateId, Integer version, String idempotencyKey) {
        requireTemplateManager(profile);
        int expectedVersion = requiredVersion(version);
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        DefaultTemplateCommand command = new DefaultTemplateCommand(templateId, expectedVersion, profile.teamId());
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.SET_DEFAULT_WORKFLOW_TEMPLATE,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replay(profile.id(), CommandOperation.SET_DEFAULT_WORKFLOW_TEMPLATE, normalizedKey, fingerprint);
        }
        WorkflowTemplateRepository.WorkflowTemplateRow current = requireVisibleTemplate(profile, templateId, true);
        if (current.version() != expectedVersion) {
            throw InteractionConflictException.workflowTemplateVersion(current.version());
        }
        // Шаблон по умолчанию бывает общим и командным. Администратор меняет общий (ровно один на систему: старый
        // снимается заменой в одной транзакции). Руководитель выбирает для своей команды любой видимый шаблон; выбор
        // общего шаблона по умолчанию сбрасывает командный выбор (teamDefaultId = null), и команда работает по общему.
        if (profile.role() == UserRole.ADMIN) {
            if (current.teamId() != null) {
                throw new WorkflowTemplateAccessDeniedException();
            }
            if (current.defaultTemplate()) {
                throw new InteractionValidationException("id", "Этот шаблон уже используется по умолчанию");
            }
            WorkflowTemplate previous = workflowTemplateRepository.findDefaultForUpdate()
                    .orElseThrow(() -> InteractionConflictException.workflowTemplateVersion(current.version()));
            workflowTemplateRepository.replaceGlobalDefault(previous.id(), templateId, now);
        } else {
            if (current.teamId() == null && !current.defaultTemplate()) {
                throw new WorkflowTemplateAccessDeniedException();
            }
            UUID teamDefaultId = current.defaultTemplate() ? null : templateId;
            if (Objects.equals(workflowTemplateRepository.findTeamDefaultId(profile.teamId()).orElse(null), teamDefaultId)) {
                throw new InteractionValidationException("id", "Этот шаблон уже используется в команде по умолчанию");
            }
            workflowTemplateRepository.updateTeamDefault(profile.teamId(), teamDefaultId);
        }
        journal(profile, templateId, current.name(),
                profile.role() == UserRole.ADMIN ? "выбран общим шаблоном по умолчанию" : "выбран шаблоном команды по умолчанию");
        WorkflowTemplate result = workflowTemplateRepository.findById(templateId)
                .map(workflowTemplateRepository::toTemplate)
                .orElseThrow(WorkflowTemplateNotFoundException::new);
        return store(commandId, result);
    }

    @Transactional(readOnly = true)
    public WorkflowTemplate getManaged(CrmProfile profile, UUID templateId) {
        WorkflowTemplateRepository.WorkflowTemplateRow row = requireVisibleTemplate(profile, templateId, false);
        if (row.defaultTemplate()) {
            requireTemplateManager(profile);
        } else {
            requireTemplateManager(profile, row);
        }
        return workflowTemplateRepository.toTemplate(row);
    }

    @Transactional
    public WorkflowTemplate create(CrmProfile profile, WorkflowTemplateRequest request, String idempotencyKey) {
        UUID teamId = managedTeamId(profile);
        String name = requiredText(request == null ? null : request.name(), "name", 200);
        NormalizedWorkflow workflow = normalizeWorkflow(
                request == null ? null : request.stages(),
                request == null ? null : request.transitions(),
                Set.of()
        );
        String comment = optionalText(request.comment(), "comment", 1000);
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        CreateTemplateCommand command = new CreateTemplateCommand(
                teamId,
                name,
                request.stages(),
                request.transitions(),
                comment
        );
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.CREATE_WORKFLOW_TEMPLATE,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replay(profile.id(), CommandOperation.CREATE_WORKFLOW_TEMPLATE, normalizedKey, fingerprint);
        }

        UUID templateId = UUID.randomUUID();
        workflowTemplateRepository.insert(templateId, teamId, name, workflow.stages(), workflow.transitions(), now);
        workflowTemplateRepository.insertVersion(
                templateId,
                1,
                name,
                new WorkflowRoute(workflow.stages(), workflow.transitions()).write(objectMapper),
                comment,
                profile.id(),
                now
        );
        journal(profile, templateId, name, versionText("создан", 1, workflow.stages().size(), comment));
        WorkflowTemplate result = workflowTemplateRepository.findById(templateId)
                .map(workflowTemplateRepository::toTemplate)
                .orElseThrow(WorkflowTemplateNotFoundException::new);
        return store(commandId, result);
    }

    @Transactional
    public WorkflowTemplate update(
            CrmProfile profile,
            UUID templateId,
            WorkflowTemplateRequest request,
            String idempotencyKey
    ) {
        WorkflowTemplateRepository.WorkflowTemplateRow current = requireVisibleTemplate(profile, templateId, true);
        requireTemplateManager(profile, current);
        int expectedVersion = requiredVersion(request == null ? null : request.version());
        String name = request == null || request.name() == null
                ? current.name()
                : requiredText(request.name(), "name", 200);
        List<WorkflowTemplateStage> currentStages = workflowTemplateRepository.findStages(templateId);
        NormalizedWorkflow workflow = validateUpdateWorkflow(
                request,
                currentStages.stream().map(WorkflowTemplateStage::id).collect(Collectors.toSet())
        );
        String comment = optionalText(request.comment(), "comment", 1000);
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        UpdateTemplateCommand command = new UpdateTemplateCommand(
                templateId,
                expectedVersion,
                name,
                request.stages(),
                request.transitions(),
                comment
        );
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.UPDATE_WORKFLOW_TEMPLATE,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replay(profile.id(), CommandOperation.UPDATE_WORKFLOW_TEMPLATE, normalizedKey, fingerprint);
        }
        if (current.version() != expectedVersion) {
            throw InteractionConflictException.workflowTemplateVersion(current.version());
        }
        // Два разных счётчика: version шаблона — оптимистичная блокировка правки (409), а номер версии маршрута
        // (insertVersion) растёт при каждом сохранении и неизменяем: на него ссылаются идущие работы.
        if (!workflowTemplateRepository.update(templateId, expectedVersion, name, now)) {
            int version = workflowTemplateRepository.findById(templateId)
                    .map(WorkflowTemplateRepository.WorkflowTemplateRow::version)
                    .orElseThrow(WorkflowTemplateNotFoundException::new);
            throw InteractionConflictException.workflowTemplateVersion(version);
        }
        WorkflowRoute route;
        if (workflow != null) {
            workflowTemplateRepository.replaceWorkflow(templateId, workflow.stages(), workflow.transitions());
            route = new WorkflowRoute(workflow.stages(), workflow.transitions());
        } else {
            route = new WorkflowRoute(currentStages, workflowTemplateRepository.findTransitions(templateId));
        }
        // Версия маршрута пишется и при правке одного названия: так сохраняется полная история, а откат — это
        // обычный перевод работ на прежнюю версию.
        int versionNumber = workflowTemplateRepository.findLatestVersionNumber(templateId) + 1;
        workflowTemplateRepository.insertVersion(
                templateId,
                versionNumber,
                name,
                route.write(objectMapper),
                comment,
                profile.id(),
                now
        );
        journal(profile, templateId, name, versionText(
                current.name().equals(name) ? "изменён" : "изменён, прежнее название «" + current.name() + "»",
                versionNumber, route.stages().size(), comment
        ));
        WorkflowTemplate result = workflowTemplateRepository.findById(templateId)
                .map(workflowTemplateRepository::toTemplate)
                .orElseThrow(WorkflowTemplateNotFoundException::new);
        return store(commandId, result);
    }

    @Transactional
    public void delete(CrmProfile profile, UUID templateId, Integer version, String idempotencyKey) {
        WorkflowTemplateRepository.WorkflowTemplateRow current = requireVisibleTemplate(profile, templateId, true);
        requireTemplateManager(profile, current);
        // Шаблон по умолчанию не удаляется: новые работы всегда должны иметь шаблон для создания.
        if (current.defaultTemplate()) {
            throw new InteractionValidationException("id", "Шаблон по умолчанию удалить нельзя");
        }
        int expectedVersion = requiredVersion(version);
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        DeleteTemplateCommand command = new DeleteTemplateCommand(templateId, expectedVersion);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.DELETE_WORKFLOW_TEMPLATE,
                normalizedKey,
                fingerprint,
                now
        )) {
            replayDelete(profile.id(), normalizedKey, fingerprint);
            return;
        }
        if (current.version() != expectedVersion) {
            throw InteractionConflictException.workflowTemplateVersion(current.version());
        }
        // Удаление стирает историю версий (каскадом), и идущие карточки больше нельзя обновить или вернуть к версии;
        // привязки видов взаимодействия база сняла бы молча (SET NULL / CASCADE). Поэтому такой шаблон не удаляется.
        long open = workflowTemplateRepository.countOpenInteractions(templateId);
        if (open > 0) {
            throw new InteractionValidationException("id", "По шаблону идут незавершённые взаимодействия: " + open
                    + ". Удаление стёрло бы историю версий, и их нельзя было бы обновить; сначала переведите их на другой маршрут"
                    + " или завершите");
        }
        List<String> kinds = workflowTemplateRepository.findKindNamesUsing(templateId);
        if (!kinds.isEmpty()) {
            throw new InteractionValidationException("id", "Шаблон выбран маршрутом видов взаимодействия: "
                    + kinds.stream().map(kind -> "«" + kind + "»").collect(Collectors.joining(", "))
                    + ". Сначала выберите для них другой шаблон");
        }
        if (!workflowTemplateRepository.delete(templateId, expectedVersion)) {
            int currentVersion = workflowTemplateRepository.findById(templateId)
                    .map(WorkflowTemplateRepository.WorkflowTemplateRow::version)
                    .orElseThrow(WorkflowTemplateNotFoundException::new);
            throw InteractionConflictException.workflowTemplateVersion(currentVersion);
        }
        // Версии шаблона удаляются вместе с ним (ON DELETE CASCADE), поэтому след удаления остаётся только в журнале.
        journal(profile, templateId, current.name(), "удалён");
        commandIdempotencyRepository.complete(commandId, "{}");
    }

    private void journal(CrmProfile profile, UUID templateId, String name, String details) {
        auditJournalRepository.record(
                AuditAction.WORKFLOW_TEMPLATE_CHANGED, profile.id(), "WORKFLOW_TEMPLATE", templateId, name, details, null
        );
    }

    private static String versionText(String change, int version, int stages, String comment) {
        return change + "; версия маршрута " + version + ", этапов: " + stages
                + (comment == null ? "" : "; комментарий: " + comment);
    }

    private WorkflowTemplateRepository.WorkflowTemplateRow requireVisibleTemplate(
            CrmProfile profile,
            UUID templateId,
            boolean forUpdate
    ) {
        if (templateId == null) {
            throw new InteractionValidationException("id", "Укажите шаблон процесса");
        }
        WorkflowTemplateRepository.WorkflowTemplateRow row = (forUpdate
                ? workflowTemplateRepository.findByIdForUpdate(templateId)
                : workflowTemplateRepository.findById(templateId))
                .orElseThrow(WorkflowTemplateNotFoundException::new);
        if (row.teamId() != null && !row.teamId().equals(profile.teamId())) {
            throw new WorkflowTemplateNotFoundException();
        }
        return row;
    }

    private void requireTemplateManager(CrmProfile profile) {
        if (profile.role() == UserRole.ADMIN || (profile.role() == UserRole.LEADER && profile.teamId() != null)) {
            return;
        }
        throw new WorkflowTemplateAccessDeniedException();
    }

    private UUID managedTeamId(CrmProfile profile) {
        requireTemplateManager(profile);
        return profile.role() == UserRole.LEADER ? profile.teamId() : null;
    }

    private void requireTemplateManager(CrmProfile profile, WorkflowTemplateRepository.WorkflowTemplateRow template) {
        if (profile.role() == UserRole.LEADER && profile.teamId() != null && profile.teamId().equals(template.teamId())) {
            return;
        }
        if (profile.role() == UserRole.ADMIN && template.teamId() == null) {
            return;
        }
        throw new WorkflowTemplateAccessDeniedException();
    }

    private NormalizedWorkflow validateUpdateWorkflow(WorkflowTemplateRequest request, Set<UUID> reusableStageIds) {
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        if (request.stages() == null) {
            if (request.transitions() != null) {
                throw new InteractionValidationException("transitions", "Переходы можно менять только вместе с этапами");
            }
            if (request.name() == null) {
                throw new InteractionValidationException("body", "Измените хотя бы одно поле шаблона");
            }
            return null;
        }
        return normalizeWorkflow(request.stages(), request.transitions(), reusableStageIds);
    }

    private NormalizedWorkflow normalizeWorkflow(
            List<WorkflowStageInput> requestedStages,
            List<WorkflowTransitionInput> requestedTransitions,
            Set<UUID> reusableStageIds
    ) {
        if (requestedStages == null || requestedStages.isEmpty() || requestedStages.size() > 100) {
            throw new InteractionValidationException("stages", "Укажите от 1 до 100 этапов");
        }
        Map<Integer, WorkflowStageInput> stagesByOrder = new HashMap<>();
        for (WorkflowStageInput stage : requestedStages) {
            if (stage == null || stage.order() == null || stage.order() < 0) {
                throw new InteractionValidationException("stages", "Порядковый номер этапа не может быть отрицательным");
            }
            if (stagesByOrder.put(stage.order(), stage) != null) {
                throw new InteractionValidationException("stages", "Порядковые номера этапов не должны повторяться");
            }
        }
        List<WorkflowTemplateStage> stages = new ArrayList<>();
        Set<UUID> keptStageIds = new HashSet<>();
        for (int order = 0; order < requestedStages.size(); order++) {
            WorkflowStageInput stage = stagesByOrder.get(order);
            if (stage == null) {
                throw new InteractionValidationException("stages", "Порядковые номера этапов должны идти подряд с нуля");
            }
            if (stage.optional() == null) {
                throw new InteractionValidationException("stages", "Укажите для каждого этапа, обязателен ли он");
            }
            // Идентификатор этапа сохраняется только для этапов этого же шаблона: по нему идущие работы сопоставляют
            // этапы при переводе на версию. Чужой или неизвестный идентификатор заменяется новым, подставить этап
            // другого шаблона нельзя.
            boolean kept = stage.id() != null && reusableStageIds.contains(stage.id());
            if (kept && !keptStageIds.add(stage.id())) {
                throw new InteractionValidationException("stages", "Этапы не должны повторяться");
            }
            stages.add(new WorkflowTemplateStage(
                    kept ? stage.id() : UUID.randomUUID(),
                    stageName(stage.name(), order + 1),
                    order,
                    stage.optional(),
                    Boolean.TRUE.equals(stage.requiresAgreement()),
                    stage.lmsRole(),
                    documentKinds.normalizeRequired(stage.documentKinds(), "stages")
            ));
        }
        if (requestedTransitions == null) {
            throw new InteractionValidationException("transitions", "Не передан список переходов");
        }
        if (requestedTransitions.size() > 1000) {
            throw new InteractionValidationException("transitions", "Можно задать не более 1000 переходов");
        }
        // Отчёты и «Контроль» отбирают и группируют этапы по названию: одноимённые этапы шаблона там неразличимы.
        Set<String> names = new HashSet<>();
        for (WorkflowTemplateStage stage : stages) {
            if (!names.add(stage.name().strip().toLowerCase(java.util.Locale.ROOT))) {
                throw new InteractionValidationException("stages", "Названия этапов не должны повторяться: «" + stage.name()
                        + "» уже есть в шаблоне");
            }
        }
        Map<Integer, WorkflowTemplateStage> stageByOrder = new HashMap<>();
        stages.forEach(stage -> stageByOrder.put(stage.order(), stage));
        Set<StageOrders> edgeOrders = new HashSet<>();
        List<WorkflowTemplateTransition> transitions = new ArrayList<>();
        for (WorkflowTransitionInput transition : requestedTransitions) {
            if (transition == null || transition.fromOrder() == null || transition.toOrder() == null
                    || transition.commentRequired() == null) {
                throw new InteractionValidationException("transitions", "Укажите начало и конец каждого перехода");
            }
            WorkflowTemplateStage from = stageByOrder.get(transition.fromOrder());
            WorkflowTemplateStage to = stageByOrder.get(transition.toOrder());
            if (from == null || to == null || from.id().equals(to.id())) {
                throw new InteractionValidationException("transitions", "Переход должен соединять два разных этапа процесса");
            }
            if (!edgeOrders.add(new StageOrders(transition.fromOrder(), transition.toOrder()))) {
                throw new InteractionValidationException("transitions", "Переходы не должны повторяться");
            }
            transitions.add(new WorkflowTemplateTransition(from.id(), to.id(), transition.commentRequired()));
        }
        // Структура маршрута проверяется тем же правилом, что и правка этапов в карточке: у шаблона и у работы
        // не может быть расхождения в понятии «допустимый маршрут».
        WorkflowGraph.validate(
                stages.stream().map(stage -> new InteractionStage(
                        stage.id(), stage.name(), stage.order(), stage.optional()
                )).toList(),
                transitions.stream().map(transition -> new InteractionStageTransition(
                        transition.fromStageId(), transition.toStageId(), transition.commentRequired()
                )).toList(),
                "transitions"
        );
        return new NormalizedWorkflow(List.copyOf(stages), List.copyOf(transitions));
    }

    static List<InteractionStageTransition> asInteractionTransitions(
            List<WorkflowTemplateTransition> transitions,
            Map<UUID, UUID> stageIds
    ) {
        return transitions.stream()
                .map(transition -> new InteractionStageTransition(
                        stageIds.get(transition.fromStageId()),
                        stageIds.get(transition.toStageId()),
                        transition.commentRequired()
                ))
                .toList();
    }

    private WorkflowTemplate replay(
            UUID actorProfileId,
            CommandOperation operation,
            String idempotencyKey,
            String fingerprint
    ) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, operation, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved workflow template command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved workflow template command has no result");
        }
        return read(command.resultJson());
    }

    private void replayDelete(UUID actorProfileId, String idempotencyKey, String fingerprint) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, CommandOperation.DELETE_WORKFLOW_TEMPLATE, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved workflow template command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved workflow template command has no result");
        }
    }

    private WorkflowTemplate store(UUID commandId, WorkflowTemplate result) {
        String resultJson = write(result);
        commandIdempotencyRepository.complete(commandId, resultJson);
        return read(resultJson);
    }

    private WorkflowTemplate read(String value) {
        try {
            return objectMapper.readValue(value, WorkflowTemplate.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored workflow template command result cannot be read", exception);
        }
    }

    private String write(WorkflowTemplate value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Workflow template command result cannot be stored", exception);
        }
    }

    private int requiredVersion(Integer value) {
        if (value == null || value < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите страницу");
        }
        return value;
    }

    private String requiredIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException("Idempotency-Key", "Не передан ключ повтора запроса Idempotency-Key");
        }
        if (value.length() > 255) {
            throw new InteractionValidationException("Idempotency-Key", "Ключ повтора запроса Idempotency-Key длиннее 255 символов");
        }
        return value;
    }

    private String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new InteractionValidationException(field, "Слишком длинное значение");
        }
        return normalized;
    }

    // Номер этапа в тексте: файл и внешний клиент (MCP) не видят формы, где этап подсвечен.
    private static String stageName(String value, int order) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException("stages", "Этап " + order + ": заполните название");
        }
        String normalized = value.trim();
        if (normalized.length() > 200) {
            throw new InteractionValidationException("stages", "Этап " + order + ": название длиннее 200 символов");
        }
        return normalized;
    }

    private String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException(field, "Заполните значение");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new InteractionValidationException(field, "Слишком длинное значение");
        }
        return normalized;
    }

    private record NormalizedWorkflow(
            List<WorkflowTemplateStage> stages,
            List<WorkflowTemplateTransition> transitions
    ) {
    }

    private record StageOrders(int fromOrder, int toOrder) {
    }

    private record CreateTemplateCommand(
            UUID teamId,
            String name,
            List<WorkflowStageInput> stages,
            List<WorkflowTransitionInput> transitions,
            String comment
    ) {
    }

    private record UpdateTemplateCommand(
            UUID templateId,
            int version,
            String name,
            List<WorkflowStageInput> stages,
            List<WorkflowTransitionInput> transitions,
            String comment
    ) {
    }

    private record DeleteTemplateCommand(UUID templateId, int version) {
    }

    private record DefaultTemplateCommand(UUID templateId, int version, UUID teamId) {
    }
}
