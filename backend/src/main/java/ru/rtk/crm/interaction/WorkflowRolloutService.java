package ru.rtk.crm.interaction;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;

@Service
public class WorkflowRolloutService {
    private static final int MAX_WORKS = 1000;
    private static final int MAX_STAGE_MAP = 200;

    private final WorkflowTemplateRepository workflowTemplateRepository;
    private final WorkflowRolloutRepository workflowRolloutRepository;
    private final InteractionRepository interactionRepository;
    private final OrganizationRepository organizationRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public WorkflowRolloutService(
            WorkflowTemplateRepository workflowTemplateRepository,
            WorkflowRolloutRepository workflowRolloutRepository,
            InteractionRepository interactionRepository,
            OrganizationRepository organizationRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.workflowTemplateRepository = workflowTemplateRepository;
        this.workflowRolloutRepository = workflowRolloutRepository;
        this.interactionRepository = interactionRepository;
        this.organizationRepository = organizationRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<WorkflowTemplateVersion> versions(CrmProfile profile, UUID templateId) {
        requireVisibleTemplate(profile, templateId);
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        Map<Integer, Long> works = scope
                .map(value -> workflowRolloutRepository.countActiveWorksByVersion(templateId, value))
                .orElse(Map.of());
        return workflowTemplateRepository.findVersions(templateId).stream()
                .map(version -> {
                    WorkflowRoute route = WorkflowRoute.read(objectMapper, version.content());
                    return new WorkflowTemplateVersion(
                            version.number(),
                            version.name(),
                            version.comment(),
                            version.authorName(),
                            version.createdAt(),
                            route.stages(),
                            route.transitions(),
                            scope.isPresent() ? works.getOrDefault(version.number(), 0L) : null
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowRolloutPreview preview(
            CrmProfile profile,
            UUID templateId,
            int targetVersion,
            WorkflowRolloutRequest request
    ) {
        VisibilityScope scope = requireRolloutScope(profile);
        requireVisibleTemplate(profile, templateId);
        WorkflowRoute target = requireVersion(templateId, targetVersion);
        Map<UUID, UUID> overrides = overrides(request == null ? null : request.stageMap(), target);
        Map<Integer, WorkflowRoute> sources = new TreeMap<>();
        Map<Integer, Map<UUID, Integer>> worksOnStage = new HashMap<>();
        List<WorkflowRolloutPreview.Work> works = new ArrayList<>();
        for (WorkflowRolloutRepository.Candidate candidate
                : workflowRolloutRepository.findCandidates(templateId, targetVersion, scope)) {
            WorkflowRoute source = sources.computeIfAbsent(
                    candidate.workflowVersion(),
                    version -> requireVersion(templateId, version)
            );
            List<WorkflowRollout.RouteStage> stages = workflowRolloutRepository.findRouteStages(candidate.id());
            WorkflowRollout.Plan plan = WorkflowRollout.plan(
                    stages,
                    interactionRepository.findTransitions(candidate.id()),
                    candidate.currentStageId(),
                    source,
                    target,
                    overrides
            );
            stages.stream()
                    .filter(stage -> stage.id().equals(candidate.currentStageId()) && stage.templateStageId() != null)
                    .findFirst()
                    .ifPresent(stage -> worksOnStage
                            .computeIfAbsent(candidate.workflowVersion(), version -> new HashMap<>())
                            .merge(stage.templateStageId(), 1, Integer::sum));
            works.add(new WorkflowRolloutPreview.Work(
                    candidate.id(),
                    candidate.title(),
                    candidate.organizationName(),
                    candidate.version(),
                    candidate.workflowVersion(),
                    candidate.currentStageName(),
                    plan.currentStageName(),
                    plan.currentStageRemoved(),
                    plan.edited(),
                    String.join("; ", plan.changes())
            ));
        }
        List<WorkflowRolloutPreview.Source> sourceItems = new ArrayList<>();
        sources.forEach((version, route) -> {
            Map<UUID, Integer> counts = worksOnStage.getOrDefault(version, Map.of());
            List<WorkflowRolloutPreview.StageMatch> stages = WorkflowRollout.map(
                    WorkflowRollout.asRoute(route),
                    target,
                    overrides
            ).stream().map(mapped -> new WorkflowRolloutPreview.StageMatch(
                    mapped.stage().id(),
                    mapped.stage().name(),
                    mapped.target().id(),
                    mapped.target().name(),
                    mapped.match(),
                    counts.getOrDefault(mapped.stage().id(), 0)
            )).toList();
            sourceItems.add(new WorkflowRolloutPreview.Source(
                    version,
                    (int) works.stream().filter(work -> work.sourceVersion() == version).count(),
                    stages
            ));
        });
        return new WorkflowRolloutPreview(
                templateId,
                targetVersion,
                target.stages().stream().sorted(Comparator.comparingInt(WorkflowTemplateStage::order)).toList(),
                sourceItems,
                works
        );
    }

    @Transactional
    public WorkflowRolloutResult apply(
            CrmProfile profile,
            UUID templateId,
            int targetVersion,
            WorkflowRolloutRequest request,
            String idempotencyKey
    ) {
        requireRolloutScope(profile);
        WorkflowTemplateRepository.WorkflowTemplateRow template = requireVisibleTemplate(profile, templateId);
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        List<WorkflowRolloutWorkInput> works = requiredWorks(request.works());
        boolean replaceLocalChanges = Boolean.TRUE.equals(request.replaceLocalChanges());
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        RolloutCommand command = new RolloutCommand(
                templateId,
                targetVersion,
                request.stageMap() == null ? List.of() : request.stageMap(),
                works,
                replaceLocalChanges
        );
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.ROLL_OUT_WORKFLOW_VERSION,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replay(profile.id(), normalizedKey, fingerprint);
        }
        WorkflowRoute target = requireVersion(templateId, targetVersion);
        Map<UUID, UUID> overrides = overrides(request.stageMap(), target);
        Map<Integer, WorkflowRoute> sources = new HashMap<>();
        for (WorkflowRolloutWorkInput work : works) {
            InteractionRepository.InteractionRow row = interactionRepository.findByIdForUpdate(work.id())
                    .orElseThrow(InteractionNotFoundException::new);
            Organization organization = organizationRepository.findVisibleById(profile, row.organizationId())
                    .orElseThrow(InteractionNotFoundException::new);
            WorkflowRolloutRepository.RouteState state = workflowRolloutRepository.findState(work.id())
                    .orElseThrow(InteractionNotFoundException::new);
            if (row.version() != work.version()) {
                throw InteractionConflictException.version(row.version());
            }
            if (!templateId.equals(state.templateId()) || state.workflowVersion() == null
                    || state.workStatus() == InteractionWorkStatus.COMPLETED
                    || state.workflowVersion() == targetVersion) {
                throw new InteractionValidationException(
                        "works",
                        "Работа «" + row.title() + "» не идёт по другой версии этого шаблона или уже завершена"
                );
            }
            int sourceVersion = state.workflowVersion();
            WorkflowRoute source = sources.computeIfAbsent(sourceVersion, version -> requireVersion(templateId, version));
            List<WorkflowRollout.RouteStage> previous = workflowRolloutRepository.findRouteStages(work.id());
            WorkflowRollout.Plan plan = WorkflowRollout.plan(
                    previous,
                    interactionRepository.findTransitions(work.id()),
                    row.currentStageId(),
                    source,
                    target,
                    overrides
            );
            if (plan.edited() && !replaceLocalChanges) {
                throw new InteractionValidationException(
                        "replaceLocalChanges",
                        "У работы «" + row.title() + "» этапы изменены в карточке; подтвердите замену этих изменений"
                );
            }
            if (!workflowRolloutRepository.moveToVersion(work.id(), work.version(), plan.currentStageId(), targetVersion, now)) {
                throw InteractionConflictException.version(row.version());
            }
            plan.merged().forEach((from, to) -> workflowRolloutRepository.repointStage(work.id(), from, to));
            List<InteractionStage> stages = plan.stages().stream().map(WorkflowRollout.RouteStage::asStage).toList();
            interactionRepository.replaceWorkflow(
                    work.id(),
                    previous.stream().map(WorkflowRollout.RouteStage::asStage).toList(),
                    stages,
                    plan.transitions()
            );
            interactionRepository.updateRoute(
                    work.id(),
                    templateId,
                    targetVersion,
                    plan.stages().stream().collect(Collectors.toMap(
                            WorkflowRollout.RouteStage::id,
                            WorkflowRollout.RouteStage::templateStageId
                    ))
            );
            InteractionStage current = stages.stream()
                    .filter(stage -> stage.id().equals(plan.currentStageId()))
                    .findFirst()
                    .orElseThrow();
            String summary = (targetVersion > sourceVersion ? "Маршрут обновлён до версии " : "Маршрут возвращён к версии ")
                    + targetVersion + " шаблона «" + template.name() + "» (была версия " + sourceVersion + ")";
            interactionRepository.insertEvent(
                    UUID.randomUUID(),
                    work.id(),
                    commandId,
                    InteractionEventType.STAGES_EDITED,
                    current,
                    null,
                    null,
                    plan.changes().isEmpty() ? summary : summary + ": " + String.join("; ", plan.changes()),
                    null,
                    profile.id(),
                    organization.ownerManagerId(),
                    work.version() + 1,
                    now
            );
        }
        return store(commandId, new WorkflowRolloutResult(targetVersion, works.size()));
    }

    private VisibilityScope requireRolloutScope(CrmProfile profile) {
        if (profile.role() != UserRole.LEADER || profile.teamId() == null) {
            throw new WorkflowTemplateAccessDeniedException();
        }
        return organizationRepository.visibilityScope(profile).orElseThrow(WorkflowTemplateAccessDeniedException::new);
    }

    private WorkflowTemplateRepository.WorkflowTemplateRow requireVisibleTemplate(CrmProfile profile, UUID templateId) {
        if (profile.role() == UserRole.PARTNER) {
            throw new WorkflowTemplateAccessDeniedException();
        }
        WorkflowTemplateRepository.WorkflowTemplateRow row = workflowTemplateRepository.findById(templateId)
                .orElseThrow(WorkflowTemplateNotFoundException::new);
        if (row.teamId() != null && !row.teamId().equals(profile.teamId())) {
            throw new WorkflowTemplateNotFoundException();
        }
        return row;
    }

    private WorkflowRoute requireVersion(UUID templateId, int number) {
        return workflowTemplateRepository.findVersion(templateId, number)
                .map(version -> WorkflowRoute.read(objectMapper, version.content()))
                .orElseThrow(WorkflowTemplateNotFoundException::new);
    }

    private Map<UUID, UUID> overrides(List<WorkflowStageMapInput> stageMap, WorkflowRoute target) {
        if (stageMap == null) {
            return Map.of();
        }
        if (stageMap.size() > MAX_STAGE_MAP) {
            throw new InteractionValidationException("stageMap", "Можно сопоставить не более 200 этапов");
        }
        Set<UUID> targetIds = target.stages().stream().map(WorkflowTemplateStage::id).collect(Collectors.toSet());
        Map<UUID, UUID> overrides = new LinkedHashMap<>();
        for (WorkflowStageMapInput item : stageMap) {
            if (item == null || item.fromStageId() == null || item.toStageId() == null) {
                throw new InteractionValidationException("stageMap", "Укажите этап старой и этап новой версии");
            }
            if (!targetIds.contains(item.toStageId())) {
                throw new InteractionValidationException("stageMap", "Выберите этап из новой версии шаблона");
            }
            if (overrides.put(item.fromStageId(), item.toStageId()) != null) {
                throw new InteractionValidationException("stageMap", "Каждый этап сопоставляется один раз");
            }
        }
        return overrides;
    }

    private List<WorkflowRolloutWorkInput> requiredWorks(List<WorkflowRolloutWorkInput> works) {
        if (works == null || works.isEmpty() || works.size() > MAX_WORKS) {
            throw new InteractionValidationException("works", "Выберите от 1 до 1000 работ");
        }
        Set<UUID> ids = new HashSet<>();
        for (WorkflowRolloutWorkInput work : works) {
            if (work == null || work.id() == null || work.version() == null || work.version() < 0) {
                throw new InteractionValidationException("works", "Укажите работу и её версию");
            }
            if (!ids.add(work.id())) {
                throw new InteractionValidationException("works", "Работы не должны повторяться");
            }
        }
        return works.stream().sorted(Comparator.comparing(WorkflowRolloutWorkInput::id)).toList();
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

    private WorkflowRolloutResult replay(UUID actorProfileId, String idempotencyKey, String fingerprint) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, CommandOperation.ROLL_OUT_WORKFLOW_VERSION, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved workflow rollout command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved workflow rollout command has no result");
        }
        try {
            return objectMapper.readValue(command.resultJson(), WorkflowRolloutResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored workflow rollout result cannot be read", exception);
        }
    }

    private WorkflowRolloutResult store(UUID commandId, WorkflowRolloutResult result) {
        try {
            commandIdempotencyRepository.complete(commandId, objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Workflow rollout result cannot be stored", exception);
        }
        return result;
    }

    private record RolloutCommand(
            UUID templateId,
            int targetVersion,
            List<WorkflowStageMapInput> stageMap,
            List<WorkflowRolloutWorkInput> works,
            boolean replaceLocalChanges
    ) {
    }
}
