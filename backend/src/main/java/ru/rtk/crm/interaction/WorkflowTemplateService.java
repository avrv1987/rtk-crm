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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;

@Service
public class WorkflowTemplateService {
    private final WorkflowTemplateRepository workflowTemplateRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public WorkflowTemplateService(
            WorkflowTemplateRepository workflowTemplateRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.workflowTemplateRepository = workflowTemplateRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
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
                request == null ? null : request.transitions()
        );
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        CreateTemplateCommand command = new CreateTemplateCommand(
                teamId,
                name,
                request.stages(),
                request.transitions()
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
        NormalizedWorkflow workflow = validateUpdateWorkflow(request);
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        UpdateTemplateCommand command = new UpdateTemplateCommand(
                templateId,
                expectedVersion,
                name,
                request.stages(),
                request.transitions()
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
        if (!workflowTemplateRepository.update(templateId, expectedVersion, name, now)) {
            int version = workflowTemplateRepository.findById(templateId)
                    .map(WorkflowTemplateRepository.WorkflowTemplateRow::version)
                    .orElseThrow(WorkflowTemplateNotFoundException::new);
            throw InteractionConflictException.workflowTemplateVersion(version);
        }
        if (workflow != null) {
            workflowTemplateRepository.replaceWorkflow(templateId, workflow.stages(), workflow.transitions());
        }
        WorkflowTemplate result = workflowTemplateRepository.findById(templateId)
                .map(workflowTemplateRepository::toTemplate)
                .orElseThrow(WorkflowTemplateNotFoundException::new);
        return store(commandId, result);
    }

    @Transactional
    public void delete(CrmProfile profile, UUID templateId, Integer version, String idempotencyKey) {
        WorkflowTemplateRepository.WorkflowTemplateRow current = requireVisibleTemplate(profile, templateId, true);
        requireTemplateManager(profile, current);
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
        if (!workflowTemplateRepository.delete(templateId, expectedVersion)) {
            int currentVersion = workflowTemplateRepository.findById(templateId)
                    .map(WorkflowTemplateRepository.WorkflowTemplateRow::version)
                    .orElseThrow(WorkflowTemplateNotFoundException::new);
            throw InteractionConflictException.workflowTemplateVersion(currentVersion);
        }
        commandIdempotencyRepository.complete(commandId, "{}");
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

    private NormalizedWorkflow validateUpdateWorkflow(WorkflowTemplateRequest request) {
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
        return normalizeWorkflow(request.stages(), request.transitions());
    }

    private NormalizedWorkflow normalizeWorkflow(
            List<WorkflowStageInput> requestedStages,
            List<WorkflowTransitionInput> requestedTransitions
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
        for (int order = 0; order < requestedStages.size(); order++) {
            WorkflowStageInput stage = stagesByOrder.get(order);
            if (stage == null) {
                throw new InteractionValidationException("stages", "Порядковые номера этапов должны идти подряд с нуля");
            }
            if (stage.optional() == null) {
                throw new InteractionValidationException("stages", "Укажите для каждого этапа, обязателен ли он");
            }
            stages.add(new WorkflowTemplateStage(
                    UUID.randomUUID(),
                    requiredText(stage.name(), "stages", 200),
                    order,
                    stage.optional()
            ));
        }
        if (requestedTransitions == null || requestedTransitions.size() > 1000) {
            throw new InteractionValidationException("transitions", "Можно задать не более 1000 переходов");
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
            List<WorkflowTransitionInput> transitions
    ) {
    }

    private record UpdateTemplateCommand(
            UUID templateId,
            int version,
            String name,
            List<WorkflowStageInput> stages,
            List<WorkflowTransitionInput> transitions
    ) {
    }

    private record DeleteTemplateCommand(UUID templateId, int version) {
    }

    private record DefaultTemplateCommand(UUID templateId, int version, UUID teamId) {
    }
}
