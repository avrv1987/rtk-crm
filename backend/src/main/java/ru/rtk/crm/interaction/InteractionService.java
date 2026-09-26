package ru.rtk.crm.interaction;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.ContactInteractionMutationAccessDeniedException;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.attachment.AttachmentRepository;
import ru.rtk.crm.catalog.CatalogReference;
import ru.rtk.crm.catalog.CatalogRepository;
import ru.rtk.crm.catalog.Contact;
import ru.rtk.crm.catalog.ContactRepository;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.catalog.OrganizationStatus;

@Service
public class InteractionService {
    private static final DateTimeFormatter EVENT_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Moscow"));
    private static final DateTimeFormatter STEP_DEADLINE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final ZoneId COMPLETION_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter COMPLETION_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final OrganizationRepository organizationRepository;
    private final ContactRepository contactRepository;
    private final CatalogRepository catalogRepository;
    private final InteractionRepository interactionRepository;
    private final WorkflowTemplateRepository workflowTemplateRepository;
    private final AttachmentRepository attachmentRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public InteractionService(
            OrganizationRepository organizationRepository,
            ContactRepository contactRepository,
            CatalogRepository catalogRepository,
            InteractionRepository interactionRepository,
            WorkflowTemplateRepository workflowTemplateRepository,
            AttachmentRepository attachmentRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.organizationRepository = organizationRepository;
        this.contactRepository = contactRepository;
        this.catalogRepository = catalogRepository;
        this.interactionRepository = interactionRepository;
        this.workflowTemplateRepository = workflowTemplateRepository;
        this.attachmentRepository = attachmentRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public InteractionPage list(CrmProfile profile, InteractionFilter filter, InteractionQuery query) {
        if (filter.organizationId() != null) {
            requireVisibleOrganization(profile, filter.organizationId());
        }
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return new InteractionPage(List.of(), query.page(), query.size(), 0);
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<InteractionRepository.InteractionListRow> rows = interactionRepository.findVisible(scope.get(), filter, query, now);
        List<UUID> ids = rows.stream().map(row -> row.row().id()).toList();
        Map<UUID, List<UUID>> contactIds = interactionRepository.findContactIdsByInteractionIds(ids);
        Map<UUID, List<UUID>> productIds = interactionRepository.findProductIdsByInteractionIds(ids);
        List<InteractionSummary> items = rows.stream()
                .map(row -> toSummary(
                        row,
                        contactIds.getOrDefault(row.row().id(), List.of()),
                        productIds.getOrDefault(row.row().id(), List.of())
                ))
                .toList();
        return new InteractionPage(items, query.page(), query.size(), interactionRepository.countVisible(scope.get(), filter, now));
    }

    @Transactional(readOnly = true)
    public Interaction get(CrmProfile profile, UUID interactionId) {
        return toInteraction(requireVisibleInteraction(profile, interactionId).row());
    }

    @Transactional(readOnly = true)
    public List<InteractionEvent> events(CrmProfile profile, UUID interactionId) {
        requireVisibleInteraction(profile, interactionId);
        return interactionRepository.findEvents(interactionId);
    }

    @Transactional
    public Interaction create(CrmProfile profile, InteractionCreateRequest request, String idempotencyKey) {
        Organization organization = requireVisibleOrganization(profile, request.organizationId());
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        requireWorkableOrganization(organization.status() == OrganizationStatus.ARCHIVED);
        String title = requiredText(request.title(), "title", 200);
        String nextAction = optionalText(request.nextAction(), "nextAction", 500);
        List<UUID> contactIds = validatedContactIds(organization.id(), request.contactIds());
        requireActiveContacts(organization.id(), contactIds);
        UUID programId = validatedProgramId(request.programId());
        List<UUID> productIds = validatedProductIds(request.productIds());
        WorkflowTemplate template = requireAvailableTemplate(organization, request.templateId());
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        CreateInteractionCommand command = new CreateInteractionCommand(
                organization.id(),
                title,
                nextAction,
                request.nextActionAt(),
                contactIds,
                programId,
                productIds,
                request.lastContactAt(),
                request.templateId()
        );
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.CREATE_INTERACTION,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replayInteraction(profile.id(), CommandOperation.CREATE_INTERACTION, normalizedKey, fingerprint);
        }

        UUID interactionId = startInteraction(
                new InteractionStart(
                        organization.id(),
                        organization.ownerManagerId(),
                        title,
                        nextAction,
                        request.nextActionAt(),
                        programId,
                        request.lastContactAt(),
                        contactIds,
                        productIds
                ),
                template,
                profile.id(),
                commandId,
                now
        );
        Interaction created = toInteraction(interactionRepository.findById(interactionId)
                .orElseThrow(InteractionNotFoundException::new));
        return storeInteraction(commandId, created);
    }

    @Transactional
    public UUID createImportedInteraction(
            UUID organizationId,
            UUID ownerManagerId,
            String title,
            UUID actorProfileId,
            UUID commandId,
            OffsetDateTime now
    ) {
        requireWorkableOrganization(organizationRepository.isArchived(organizationId));
        WorkflowTemplate template = workflowTemplateRepository.findDefaultForOrganizationForUpdate(organizationId)
                .orElseThrow(() -> new InteractionValidationException(
                        "templateId", "Нет шаблона процесса по умолчанию; создайте его перед импортом"
                ));
        return startInteraction(
                new InteractionStart(organizationId, ownerManagerId, title, null, null, null, null, List.of(), List.of()),
                template,
                actorProfileId,
                commandId,
                now
        );
    }

    @Transactional
    public UUID createSourceInteraction(
            UUID organizationId,
            UUID ownerManagerId,
            String title,
            UUID programId,
            List<UUID> productIds,
            List<UUID> contactIds,
            UUID actorProfileId,
            UUID commandId,
            OffsetDateTime now
    ) {
        requireWorkableOrganization(organizationRepository.isArchived(organizationId));
        WorkflowTemplate template = workflowTemplateRepository.findDefaultForOrganizationForUpdate(organizationId)
                .orElseThrow(() -> new InteractionValidationException(
                        "templateId", "Нет шаблона процесса по умолчанию; создайте его перед применением записей источника"
                ));
        return startInteraction(
                new InteractionStart(organizationId, ownerManagerId, title, null, null, programId, null, contactIds, productIds),
                template,
                actorProfileId,
                commandId,
                now
        );
    }

    @Transactional
    public void appendSourceComment(
            UUID interactionId,
            String text,
            List<UUID> contactIds,
            UUID ownerManagerId,
            UUID actorProfileId,
            UUID commandId,
            OffsetDateTime now
    ) {
        InteractionRepository.InteractionRow row = interactionRepository.findByIdForUpdate(interactionId)
                .orElseThrow(InteractionNotFoundException::new);
        List<UUID> linkedContactIds = interactionRepository.findContactIds(interactionId);
        interactionRepository.insertContacts(
                interactionId,
                row.organizationId(),
                contactIds.stream().filter(contactId -> !linkedContactIds.contains(contactId)).toList()
        );
        if (!interactionRepository.touchVersion(interactionId, row.version(), now)) {
            throw versionConflict(interactionId, row.version());
        }
        interactionRepository.insertEvent(
                UUID.randomUUID(),
                interactionId,
                commandId,
                InteractionEventType.COMMENTED,
                currentStage(interactionRepository.findStages(interactionId), row),
                null,
                null,
                text,
                null,
                actorProfileId,
                ownerManagerId,
                row.version() + 1,
                now
        );
    }

    private UUID startInteraction(
            InteractionStart start,
            WorkflowTemplate template,
            UUID actorProfileId,
            UUID commandId,
            OffsetDateTime now
    ) {
        UUID interactionId = UUID.randomUUID();
        InteractionWorkflowSnapshot snapshot = snapshotWorkflow(template);
        List<InteractionStage> stages = snapshot.stages();
        InteractionStage initialStage = stages.getFirst();
        interactionRepository.insertInteraction(
                interactionId,
                start.organizationId(),
                start.title(),
                initialStage.id(),
                start.nextAction(),
                start.nextActionAt(),
                start.programId(),
                start.lastContactAt(),
                actorProfileId,
                now
        );
        interactionRepository.insertStages(interactionId, stages);
        interactionRepository.insertTransitions(interactionId, snapshot.transitions());
        interactionRepository.insertContacts(interactionId, start.organizationId(), start.contactIds());
        interactionRepository.insertProductAgreements(interactionId, start.productIds(), now);
        interactionRepository.insertEvent(
                UUID.randomUUID(),
                interactionId,
                commandId,
                InteractionEventType.CREATED,
                initialStage,
                null,
                initialStage,
                null,
                start.nextAction() == null && start.nextActionAt() == null
                        ? null
                        : new InteractionNextStep(start.nextAction(), start.nextActionAt()),
                actorProfileId,
                start.ownerManagerId(),
                0,
                now
        );
        return interactionId;
    }

    @Transactional
    public Interaction transition(
            CrmProfile profile,
            UUID interactionId,
            InteractionTransitionRequest request,
            String idempotencyKey
    ) {
        VisibleInteraction visible = requireVisibleInteractionForUpdate(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        int expectedVersion = requiredVersion(request.version());
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        String comment = optionalText(request.comment(), "comment", 4_000);
        List<UUID> attachmentIds = validatedAttachmentIds(request.attachmentIds());
        NextStepPatch nextStep = nextStepPatch(request.nextStep());
        TransitionInteractionCommand command = new TransitionInteractionCommand(
                interactionId,
                expectedVersion,
                request.toStageId(),
                comment,
                attachmentIds,
                nextStep
        );
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.TRANSITION_INTERACTION,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replayInteraction(profile.id(), CommandOperation.TRANSITION_INTERACTION, normalizedKey, fingerprint);
        }
        requireExpectedVersion(visible.row(), expectedVersion);
        List<InteractionStage> stages = interactionRepository.findStages(interactionId);
        InteractionStage fromStage = currentStage(stages, visible.row());
        InteractionStage toStage = targetStage(stages, request.toStageId(), "toStageId");
        InteractionStageTransition transition = validateTransition(
                interactionRepository.findTransitions(interactionId),
                fromStage,
                toStage
        );
        if (transition.commentRequired() && comment == null) {
            throw new InteractionValidationException("comment", "Заполните значение");
        }
        if (!interactionRepository.updateCurrentStage(interactionId, expectedVersion, toStage.id(), now)) {
            throw versionConflict(interactionId, visible.row().version());
        }
        InteractionNextStep changedNextStep = applyNextStep(visible.row(), nextStep);
        int resultVersion = expectedVersion + 1;
        UUID eventId = UUID.randomUUID();
        interactionRepository.insertEvent(
                eventId,
                interactionId,
                commandId,
                InteractionEventType.TRANSITIONED,
                toStage,
                fromStage,
                toStage,
                comment,
                changedNextStep,
                profile.id(),
                visible.organization().ownerManagerId(),
                resultVersion,
                now
        );
        attachmentRepository.bindCleanUnbound(interactionId, toStage.id(), eventId, attachmentIds, now);
        Interaction updated = toInteraction(interactionRepository.findById(interactionId)
                .orElseThrow(InteractionNotFoundException::new));
        return storeInteraction(commandId, updated);
    }

    @Transactional
    public InteractionCommentResult comment(
            CrmProfile profile,
            UUID interactionId,
            InteractionCommentRequest request,
            String idempotencyKey
    ) {
        VisibleInteraction visible = requireVisibleInteractionForUpdate(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        int expectedVersion = requiredVersion(request.version());
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        String text = requiredText(request.text(), "text", 4_000);
        List<UUID> attachmentIds = validatedAttachmentIds(request.attachmentIds());
        NextStepPatch nextStep = nextStepPatch(request.nextStep());
        CommentInteractionCommand command = new CommentInteractionCommand(
                interactionId,
                expectedVersion,
                request.stageId(),
                text,
                attachmentIds,
                nextStep
        );
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.COMMENT_INTERACTION,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replayComment(profile.id(), normalizedKey, fingerprint);
        }
        requireExpectedVersion(visible.row(), expectedVersion);
        InteractionStage selectedStage = targetStage(
                interactionRepository.findStages(interactionId),
                request.stageId(),
                "stageId"
        );
        if (!interactionRepository.touchVersion(interactionId, expectedVersion, now)) {
            throw versionConflict(interactionId, visible.row().version());
        }
        InteractionNextStep changedNextStep = applyNextStep(visible.row(), nextStep);
        int resultVersion = expectedVersion + 1;
        UUID eventId = UUID.randomUUID();
        InteractionEvent event = interactionRepository.insertEvent(
                eventId,
                interactionId,
                commandId,
                InteractionEventType.COMMENTED,
                selectedStage,
                null,
                null,
                text,
                changedNextStep,
                profile.id(),
                visible.organization().ownerManagerId(),
                resultVersion,
                now
        );
        attachmentRepository.bindCleanUnbound(interactionId, selectedStage.id(), eventId, attachmentIds, now);
        Interaction updated = toInteraction(interactionRepository.findById(interactionId)
                .orElseThrow(InteractionNotFoundException::new));
        return storeComment(commandId, new InteractionCommentResult(updated, event));
    }

    @Transactional
    public Interaction stageEdits(
            CrmProfile profile,
            UUID interactionId,
            InteractionStageEditRequest request,
            String idempotencyKey
    ) {
        VisibleInteraction visible = requireVisibleInteractionForUpdate(profile, interactionId);
        requireStageEditor(profile);
        int expectedVersion = requiredVersion(request == null ? null : request.version());
        List<InteractionStageEditOperation> operations = requiredStageEditOperations(request == null ? null : request.operations());
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        StageEditInteractionCommand command = new StageEditInteractionCommand(interactionId, expectedVersion, operations);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.EDIT_INTERACTION_STAGES,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replayInteraction(profile.id(), CommandOperation.EDIT_INTERACTION_STAGES, normalizedKey, fingerprint);
        }
        requireExpectedVersion(visible.row(), expectedVersion);
        UUID currentStageId = visible.row().currentStageId();
        List<InteractionStage> previousStages = interactionRepository.findStages(interactionId);
        List<InteractionStage> stages = new ArrayList<>(previousStages);
        Set<UUID> protectedStageIds = interactionRepository.findProtectedStageIds(interactionId);
        List<InteractionStageTransition> previousTransitions = interactionRepository.findTransitions(interactionId);
        List<InteractionStageTransition> transitions = previousTransitions;
        List<String> changes = new ArrayList<>();
        for (InteractionStageEditOperation operation : operations) {
            StageEdit edit = applyStageEdit(stages, currentStageId, protectedStageIds, operation);
            normalizeStageOrders(stages);
            transitions = WorkflowGraph.rebuild(stages, transitions, edit.removedStageId(), edit.insertedStageId());
            changes.add(edit.description());
        }
        if (!stageIds(previousStages).equals(stageIds(stages)) || !transitions.equals(previousTransitions)) {
            WorkflowGraph.validate(stages, transitions, "operations");
        }
        if (!interactionRepository.touchVersion(interactionId, expectedVersion, now)) {
            throw versionConflict(interactionId, visible.row().version());
        }
        interactionRepository.replaceWorkflow(interactionId, previousStages, stages, transitions);
        interactionRepository.insertEvent(
                UUID.randomUUID(),
                interactionId,
                commandId,
                InteractionEventType.STAGES_EDITED,
                currentStage(stages, currentStageId),
                null,
                null,
                String.join("; ", changes),
                null,
                profile.id(),
                visible.organization().ownerManagerId(),
                expectedVersion + 1,
                now
        );
        Interaction updated = toInteraction(interactionRepository.findById(interactionId)
                .orElseThrow(InteractionNotFoundException::new));
        return storeInteraction(commandId, updated);
    }

    @Transactional
    public Interaction completeStage(
            CrmProfile profile,
            UUID interactionId,
            InteractionStageCompletionRequest request,
            String idempotencyKey
    ) {
        VisibleInteraction visible = requireVisibleInteractionForUpdate(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        int expectedVersion = requiredVersion(request.version());
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        LocalDate completedOn = requiredCompletionDate(request.completedOn());
        String comment = optionalText(request.comment(), "comment", 4_000);
        List<UUID> attachmentIds = validatedAttachmentIds(request.attachmentIds());
        CompleteStageCommand command = new CompleteStageCommand(
                interactionId,
                expectedVersion,
                request.stageId(),
                completedOn,
                comment,
                attachmentIds
        );
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.COMPLETE_INTERACTION_STAGE,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replayInteraction(profile.id(), CommandOperation.COMPLETE_INTERACTION_STAGE, normalizedKey, fingerprint);
        }
        requireExpectedVersion(visible.row(), expectedVersion);
        InteractionStage stage = targetStage(interactionRepository.findStages(interactionId), request.stageId(), "stageId");
        if (stage.id().equals(visible.row().currentStageId())) {
            throw new InteractionValidationException(
                    "stageId",
                    "Текущий этап завершается переходом; отметить выполненным можно другой этап"
            );
        }
        if (!interactionRepository.touchVersion(interactionId, expectedVersion, now)) {
            throw versionConflict(interactionId, visible.row().version());
        }
        String text = "Дата выполнения: " + COMPLETION_DATE_FORMAT.format(completedOn)
                + (comment == null ? "" : ". " + comment);
        InteractionEvent event = interactionRepository.insertEvent(
                UUID.randomUUID(),
                interactionId,
                commandId,
                InteractionEventType.STAGE_COMPLETED,
                stage,
                null,
                null,
                text,
                null,
                profile.id(),
                visible.organization().ownerManagerId(),
                expectedVersion + 1,
                now
        );
        interactionRepository.saveStageCompletion(interactionId, stage.id(), completedOn, comment, event.id());
        attachmentRepository.bindCleanUnbound(interactionId, stage.id(), event.id(), attachmentIds, now);
        Interaction updated = toInteraction(interactionRepository.findById(interactionId)
                .orElseThrow(InteractionNotFoundException::new));
        return storeInteraction(commandId, updated);
    }

    @Transactional
    public Interaction clearStageCompletion(
            CrmProfile profile,
            UUID interactionId,
            UUID stageId,
            Integer version,
            String idempotencyKey
    ) {
        VisibleInteraction visible = requireVisibleInteractionForUpdate(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        int expectedVersion = requiredVersion(version);
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        ClearStageCompletionCommand command = new ClearStageCompletionCommand(interactionId, expectedVersion, stageId);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.CLEAR_INTERACTION_STAGE_COMPLETION,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replayInteraction(profile.id(), CommandOperation.CLEAR_INTERACTION_STAGE_COMPLETION, normalizedKey, fingerprint);
        }
        requireExpectedVersion(visible.row(), expectedVersion);
        InteractionStage stage = targetStage(interactionRepository.findStages(interactionId), stageId, "stageId");
        LocalDate completedOn = interactionRepository.findStageCompletionDate(interactionId, stage.id())
                .orElseThrow(() -> new InteractionValidationException("stageId", "Этап не отмечен выполненным"));
        if (!interactionRepository.touchVersion(interactionId, expectedVersion, now)) {
            throw versionConflict(interactionId, visible.row().version());
        }
        interactionRepository.deleteStageCompletion(interactionId, stage.id());
        interactionRepository.insertEvent(
                UUID.randomUUID(),
                interactionId,
                commandId,
                InteractionEventType.STAGE_COMPLETION_CLEARED,
                stage,
                null,
                null,
                "Снята отметка о выполнении " + COMPLETION_DATE_FORMAT.format(completedOn),
                null,
                profile.id(),
                visible.organization().ownerManagerId(),
                expectedVersion + 1,
                now
        );
        Interaction updated = toInteraction(interactionRepository.findById(interactionId)
                .orElseThrow(InteractionNotFoundException::new));
        return storeInteraction(commandId, updated);
    }

    private LocalDate requiredCompletionDate(LocalDate value) {
        if (value == null) {
            throw new InteractionValidationException("completedOn", "Укажите дату выполнения");
        }
        if (value.isAfter(LocalDate.now(COMPLETION_ZONE))) {
            throw new InteractionValidationException("completedOn", "Дата выполнения не может быть позже сегодняшней");
        }
        return value;
    }

    @Transactional
    public Interaction updatePlan(
            CrmProfile profile,
            UUID interactionId,
            InteractionPlanRequest request,
            String idempotencyKey
    ) {
        VisibleInteraction visible = requireVisibleInteractionForUpdate(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        int expectedVersion = requiredVersion(request.version());
        NextStepPatch nextStep = nextStepPatch(request.nextAction(), request.nextActionAt());
        boolean programSet = request.programId() != null;
        UUID requestedProgramId = programSet ? request.programId().orElse(null) : null;
        List<UUID> requestedProductIds = request.productIds() == null
                ? null
                : requestedProductIds(request.productIds().orElse(List.of()));
        String requestedTitle = request.title() == null ? null : requiredText(request.title().orElse(null), "title", 200);
        boolean lastContactAtSet = request.lastContactAt() != null;
        OffsetDateTime requestedLastContactAt = lastContactAtSet ? request.lastContactAt().orElse(null) : null;
        List<UUID> requestedContactIds = request.contactIds() == null
                ? null
                : validatedContactIds(visible.row().organizationId(), request.contactIds().orElse(List.of()));
        if (!nextStep.nextActionSet() && !nextStep.nextActionAtSet() && !programSet && requestedProductIds == null
                && requestedTitle == null && !lastContactAtSet && requestedContactIds == null) {
            throw new InteractionValidationException("body", "Измените хотя бы одно поле плана");
        }
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        UpdatePlanCommand command = new UpdatePlanCommand(
                interactionId,
                expectedVersion,
                nextStep,
                programSet,
                requestedProgramId,
                requestedProductIds,
                requestedTitle,
                lastContactAtSet,
                requestedLastContactAt,
                requestedContactIds
        );
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.UPDATE_INTERACTION_PLAN,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replayInteraction(profile.id(), CommandOperation.UPDATE_INTERACTION_PLAN, normalizedKey, fingerprint);
        }
        InteractionRepository.InteractionRow row = visible.row();
        requireExpectedVersion(row, expectedVersion);
        UUID programId = programSet ? requestedProgramId : row.programId();
        boolean programChanged = !Objects.equals(programId, row.programId());
        if (programChanged) {
            validatedProgramId(programId);
        }
        List<UUID> currentProductIds = interactionRepository.findProductIds(interactionId);
        List<UUID> addedProductIds = requestedProductIds == null
                ? List.of()
                : requestedProductIds.stream().filter(id -> !currentProductIds.contains(id)).toList();
        List<UUID> removedProductIds = requestedProductIds == null
                ? List.of()
                : currentProductIds.stream().filter(id -> !requestedProductIds.contains(id)).toList();
        if (catalogRepository.findActiveProductIds(addedProductIds).size() != addedProductIds.size()) {
            throw new InteractionValidationException("productIds", "Добавлять можно только действующие продукты справочника");
        }
        requireRemovableProducts(interactionId, removedProductIds);
        InteractionNextStep resolvedNextStep = resolveNextStep(row, nextStep);
        boolean nextStepChanged = !sameNextStep(row, resolvedNextStep);
        boolean titleChanged = requestedTitle != null && !requestedTitle.equals(row.title());
        boolean lastContactChanged = lastContactAtSet && !sameInstant(row.lastContactAt(), requestedLastContactAt);
        List<UUID> currentContactIds = interactionRepository.findContactIds(interactionId);
        List<UUID> addedContactIds = requestedContactIds == null
                ? List.of()
                : requestedContactIds.stream().filter(id -> !currentContactIds.contains(id)).toList();
        List<UUID> removedContactIds = requestedContactIds == null
                ? List.of()
                : currentContactIds.stream().filter(id -> !requestedContactIds.contains(id)).toList();
        requireActiveContacts(row.organizationId(), addedContactIds);
        boolean detailsChanged = titleChanged || lastContactChanged || !addedContactIds.isEmpty() || !removedContactIds.isEmpty();
        if (!nextStepChanged && !programChanged && addedProductIds.isEmpty() && removedProductIds.isEmpty() && !detailsChanged) {
            return storeInteraction(commandId, toInteraction(row));
        }
        if (!interactionRepository.touchVersion(interactionId, expectedVersion, now)) {
            throw versionConflict(interactionId, row.version());
        }
        List<String> detailChanges = new ArrayList<>();
        if (titleChanged || lastContactChanged) {
            interactionRepository.updateDetails(
                    interactionId,
                    titleChanged ? requestedTitle : row.title(),
                    lastContactChanged ? requestedLastContactAt : row.lastContactAt()
            );
        }
        if (titleChanged) {
            detailChanges.add("Название: «" + row.title() + "» → «" + requestedTitle + "»");
        }
        if (lastContactChanged) {
            detailChanges.add("Дата последнего контакта: " + eventDateTime(row.lastContactAt())
                    + " → " + eventDateTime(requestedLastContactAt));
        }
        interactionRepository.deleteContacts(interactionId, removedContactIds);
        interactionRepository.insertContacts(interactionId, row.organizationId(), addedContactIds);
        List<ProductAgreement> previousAgreements = interactionRepository.findProductAgreements(interactionId);
        interactionRepository.updatePlan(
                interactionId,
                resolvedNextStep.nextAction(),
                resolvedNextStep.nextActionAt(),
                programId
        );
        interactionRepository.deleteEmptyProductAgreements(interactionId, removedProductIds);
        requireRemovableProducts(interactionId, removedProductIds);
        interactionRepository.insertProductAgreements(interactionId, addedProductIds, now);
        List<String> changes = new ArrayList<>(detailChanges);
        if (programChanged) {
            changes.add(programId == null
                    ? "Программа снята"
                    : "Программа: «" + catalogRepository.findProgramById(programId)
                            .orElseThrow(() -> new IllegalStateException("Interaction program is unavailable"))
                            .name() + "»");
        }
        if (!addedProductIds.isEmpty()) {
            changes.add("Добавлены продукты: " + productNames(
                    interactionRepository.findProductAgreements(interactionId),
                    addedProductIds
            ));
        }
        if (!removedProductIds.isEmpty()) {
            changes.add("Удалены продукты: " + productNames(previousAgreements, removedProductIds));
        }
        UUID eventId = UUID.randomUUID();
        interactionRepository.insertEvent(
                eventId,
                interactionId,
                commandId,
                detailsChanged ? InteractionEventType.DETAILS_UPDATED : InteractionEventType.PLAN_UPDATED,
                currentStage(interactionRepository.findStages(interactionId), row),
                null,
                null,
                changes.isEmpty() ? null : String.join("; ", changes),
                nextStepChanged ? resolvedNextStep : null,
                profile.id(),
                visible.organization().ownerManagerId(),
                expectedVersion + 1,
                now
        );
        interactionRepository.insertEventContacts(eventId, addedContactIds, "ADDED");
        interactionRepository.insertEventContacts(eventId, removedContactIds, "REMOVED");
        Interaction updated = toInteraction(interactionRepository.findById(interactionId)
                .orElseThrow(InteractionNotFoundException::new));
        return storeInteraction(commandId, updated);
    }

    @Transactional
    public Interaction completeStep(
            CrmProfile profile,
            UUID interactionId,
            InteractionStepCompletionRequest request,
            String idempotencyKey
    ) {
        VisibleInteraction visible = requireVisibleInteractionForUpdate(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        int expectedVersion = requiredVersion(request.version());
        String result = optionalText(request.result(), "result", 4_000);
        InteractionNextStep nextStep = request.nextStep() == null
                ? new InteractionNextStep(null, null)
                : new InteractionNextStep(
                        optionalText(request.nextStep().nextAction(), "nextStep.nextAction", 500),
                        request.nextStep().nextActionAt()
                );
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        CompleteStepCommand command = new CompleteStepCommand(interactionId, expectedVersion, result, nextStep);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.COMPLETE_INTERACTION_STEP,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replayInteraction(profile.id(), CommandOperation.COMPLETE_INTERACTION_STEP, normalizedKey, fingerprint);
        }
        InteractionRepository.InteractionRow row = visible.row();
        requireExpectedVersion(row, expectedVersion);
        if (row.nextAction() == null && row.nextActionAt() == null) {
            throw new InteractionValidationException("nextStep", "Следующий шаг не задан: отмечать выполненным нечего");
        }
        if (!interactionRepository.touchVersion(interactionId, expectedVersion, now)) {
            throw versionConflict(interactionId, row.version());
        }
        interactionRepository.updatePlan(interactionId, nextStep.nextAction(), nextStep.nextActionAt(), row.programId());
        interactionRepository.insertEvent(
                UUID.randomUUID(),
                interactionId,
                commandId,
                InteractionEventType.PLAN_UPDATED,
                currentStage(interactionRepository.findStages(interactionId), row),
                null,
                null,
                completedStepText(row, result),
                nextStep,
                profile.id(),
                visible.organization().ownerManagerId(),
                expectedVersion + 1,
                now
        );
        Interaction updated = toInteraction(interactionRepository.findById(interactionId)
                .orElseThrow(InteractionNotFoundException::new));
        return storeInteraction(commandId, updated);
    }

    private String completedStepText(InteractionRepository.InteractionRow row, String result) {
        StringBuilder text = new StringBuilder("Шаг выполнен");
        if (row.nextAction() != null) {
            text.append(": «").append(row.nextAction()).append("»");
        }
        if (row.nextActionAt() != null) {
            text.append(row.nextAction() == null ? ", срок " : " (срок ")
                    .append(STEP_DEADLINE_FORMAT.format(row.nextActionAt().atZoneSameInstant(InteractionDue.ZONE)))
                    .append(row.nextAction() == null ? "" : ")");
        }
        if (result != null) {
            text.append(". Результат: ").append(result);
        }
        return text.toString();
    }

    @Transactional
    public Interaction changeStatus(
            CrmProfile profile,
            UUID interactionId,
            InteractionStatusRequest request,
            String idempotencyKey
    ) {
        VisibleInteraction visible = requireVisibleInteractionForUpdate(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        int expectedVersion = requiredVersion(request.version());
        if (request.status() == null) {
            throw new InteractionValidationException("status", "Выберите статус работы");
        }
        String reason = optionalText(request.reason(), "reason", 1_000);
        if (reason == null && request.status() != InteractionWorkStatus.ACTIVE) {
            throw new InteractionValidationException(
                    "reason",
                    request.status() == InteractionWorkStatus.COMPLETED ? "Укажите итог работы" : "Укажите причину приостановки"
            );
        }
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        ChangeStatusCommand command = new ChangeStatusCommand(interactionId, expectedVersion, request.status(), reason);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.CHANGE_INTERACTION_STATUS,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replayInteraction(profile.id(), CommandOperation.CHANGE_INTERACTION_STATUS, normalizedKey, fingerprint);
        }
        InteractionRepository.InteractionRow row = visible.row();
        requireExpectedVersion(row, expectedVersion);
        InteractionMarks current = row.marks();
        if (current.status() == request.status()) {
            throw new InteractionValidationException("status", "Работа уже в этом статусе");
        }
        InteractionMarks next = new InteractionMarks(
                request.status(),
                reason,
                current.waitingOn(),
                current.waitingNote(),
                current.problem(),
                current.riskLevel(),
                current.riskReason()
        );
        return saveMarks(profile, visible, commandId, expectedVersion, next, InteractionEventType.STATUS_CHANGED,
                statusDescription(request.status(), reason), now);
    }

    @Transactional
    public Interaction updateFlags(
            CrmProfile profile,
            UUID interactionId,
            InteractionFlagsRequest request,
            String idempotencyKey
    ) {
        VisibleInteraction visible = requireVisibleInteractionForUpdate(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        int expectedVersion = requiredVersion(request.version());
        String waitingNote = optionalText(request.waitingNote(), "waitingNote", 500);
        if (request.waitingOn() == null && waitingNote != null) {
            throw new InteractionValidationException("waitingOn", "Выберите, чьего ответа ждём");
        }
        String problem = optionalText(request.problem(), "problem", 1_000);
        String riskReason = optionalText(request.riskReason(), "riskReason", 1_000);
        if (request.riskLevel() != null && riskReason == null) {
            throw new InteractionValidationException("riskReason", "Укажите причину риска");
        }
        if (request.riskLevel() == null && riskReason != null) {
            throw new InteractionValidationException("riskLevel", "Выберите уровень риска");
        }
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        UpdateFlagsCommand command = new UpdateFlagsCommand(
                interactionId,
                expectedVersion,
                request.waitingOn(),
                waitingNote,
                problem,
                request.riskLevel(),
                riskReason
        );
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.UPDATE_INTERACTION_FLAGS,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replayInteraction(profile.id(), CommandOperation.UPDATE_INTERACTION_FLAGS, normalizedKey, fingerprint);
        }
        InteractionRepository.InteractionRow row = visible.row();
        requireExpectedVersion(row, expectedVersion);
        InteractionMarks current = row.marks();
        InteractionMarks next = new InteractionMarks(
                current.status(),
                current.statusReason(),
                request.waitingOn(),
                waitingNote,
                problem,
                request.riskLevel(),
                riskReason
        );
        List<String> changes = flagChanges(current, next);
        if (changes.isEmpty()) {
            return storeInteraction(commandId, toInteraction(row));
        }
        return saveMarks(profile, visible, commandId, expectedVersion, next, InteractionEventType.DETAILS_UPDATED,
                String.join("; ", changes), now);
    }

    private Interaction saveMarks(
            CrmProfile profile,
            VisibleInteraction visible,
            UUID commandId,
            int expectedVersion,
            InteractionMarks marks,
            InteractionEventType type,
            String description,
            OffsetDateTime now
    ) {
        InteractionRepository.InteractionRow row = visible.row();
        if (!interactionRepository.updateMarks(row.id(), expectedVersion, marks, now)) {
            throw versionConflict(row.id(), row.version());
        }
        interactionRepository.insertEvent(
                UUID.randomUUID(),
                row.id(),
                commandId,
                type,
                currentStage(interactionRepository.findStages(row.id()), row),
                null,
                null,
                description,
                null,
                profile.id(),
                visible.organization().ownerManagerId(),
                expectedVersion + 1,
                now
        );
        Interaction updated = toInteraction(interactionRepository.findById(row.id())
                .orElseThrow(InteractionNotFoundException::new));
        return storeInteraction(commandId, updated);
    }

    private String statusDescription(InteractionWorkStatus status, String reason) {
        return switch (status) {
            case ACTIVE -> reason == null ? "Работа возобновлена" : "Работа возобновлена: " + reason;
            case PAUSED -> "Работа приостановлена. Причина: " + reason;
            case COMPLETED -> "Работа завершена. Итог: " + reason;
        };
    }

    private List<String> flagChanges(InteractionMarks current, InteractionMarks next) {
        List<String> changes = new ArrayList<>();
        if (current.waitingOn() != next.waitingOn() || !Objects.equals(current.waitingNote(), next.waitingNote())) {
            changes.add(next.waitingOn() == null
                    ? "Ожидание снято"
                    : next.waitingOn().label() + (next.waitingNote() == null ? "" : ": «" + next.waitingNote() + "»"));
        }
        if (!Objects.equals(current.problem(), next.problem())) {
            changes.add(next.problem() == null ? "Проблема снята" : "Есть проблема: «" + next.problem() + "»");
        }
        if (current.riskLevel() != next.riskLevel() || !Objects.equals(current.riskReason(), next.riskReason())) {
            changes.add(next.riskLevel() == null
                    ? "Риск снят"
                    : "Риск " + next.riskLevel().label() + ": «" + next.riskReason() + "»");
        }
        return changes;
    }

    private void requireActiveContacts(UUID organizationId, List<UUID> contactIds) {
        if (contactRepository.findByIds(organizationId, contactIds).stream().anyMatch(Contact::inactive)) {
            throw new InteractionValidationException("contactIds", "Контакт отмечен как неактуальный; выберите действующий контакт");
        }
    }

    private boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
        return Objects.equals(left == null ? null : left.toInstant(), right == null ? null : right.toInstant());
    }

    private String eventDateTime(OffsetDateTime value) {
        return value == null ? "не указана" : EVENT_DATE_TIME.format(value);
    }

    private void requireRemovableProducts(UUID interactionId, List<UUID> productIds) {
        if (interactionRepository.countFilledProductAgreements(interactionId, productIds) > 0) {
            throw new InteractionValidationException("productIds", "Нельзя снять продукт, по которому заполнены данные договора");
        }
    }

    private NextStepPatch nextStepPatch(Optional<String> nextAction, Optional<OffsetDateTime> nextActionAt) {
        return new NextStepPatch(
                nextAction != null,
                nextAction == null ? null : optionalText(nextAction.orElse(null), "nextAction", 500),
                nextActionAt != null,
                nextActionAt == null ? null : nextActionAt.orElse(null)
        );
    }

    private NextStepPatch nextStepPatch(InteractionNextStep nextStep) {
        return nextStep == null
                ? new NextStepPatch(false, null, false, null)
                : new NextStepPatch(
                        true,
                        optionalText(nextStep.nextAction(), "nextStep.nextAction", 500),
                        true,
                        nextStep.nextActionAt()
                );
    }

    private InteractionNextStep resolveNextStep(InteractionRepository.InteractionRow row, NextStepPatch patch) {
        return new InteractionNextStep(
                patch.nextActionSet() ? patch.nextAction() : row.nextAction(),
                patch.nextActionAtSet() ? patch.nextActionAt() : row.nextActionAt()
        );
    }

    private boolean sameNextStep(InteractionRepository.InteractionRow row, InteractionNextStep nextStep) {
        return Objects.equals(row.nextAction(), nextStep.nextAction())
                && Objects.equals(
                        row.nextActionAt() == null ? null : row.nextActionAt().toInstant(),
                        nextStep.nextActionAt() == null ? null : nextStep.nextActionAt().toInstant()
                );
    }

    private InteractionNextStep applyNextStep(InteractionRepository.InteractionRow row, NextStepPatch patch) {
        InteractionNextStep nextStep = resolveNextStep(row, patch);
        if (sameNextStep(row, nextStep)) {
            return null;
        }
        interactionRepository.updatePlan(row.id(), nextStep.nextAction(), nextStep.nextActionAt(), row.programId());
        return nextStep;
    }

    private String productNames(List<ProductAgreement> agreements, List<UUID> productIds) {
        return agreements.stream()
                .filter(agreement -> productIds.contains(agreement.productId()))
                .map(agreement -> "«" + agreement.productName() + "»")
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private List<UUID> stageIds(List<InteractionStage> stages) {
        return stages.stream().map(InteractionStage::id).toList();
    }

    private void requireStageEditor(CrmProfile profile) {
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
    }

    private List<InteractionStageEditOperation> requiredStageEditOperations(
            List<InteractionStageEditOperation> value
    ) {
        if (value == null || value.isEmpty() || value.size() > 100) {
            throw new InteractionValidationException("operations", "Укажите от 1 до 100 операций");
        }
        if (value.stream().anyMatch(operation -> operation == null || operation.type() == null)) {
            throw new InteractionValidationException("operations", "Укажите тип каждой операции");
        }
        return List.copyOf(value);
    }

    private StageEdit applyStageEdit(
            List<InteractionStage> stages,
            UUID currentStageId,
            Set<UUID> protectedStageIds,
            InteractionStageEditOperation operation
    ) {
        return switch (operation.type()) {
            case RENAME -> renameStage(stages, operation);
            case ADD_AFTER -> addStageAfter(stages, currentStageId, protectedStageIds, operation);
            case MOVE_AFTER -> moveStageAfter(stages, currentStageId, protectedStageIds, operation);
            case DELETE -> deleteStage(stages, currentStageId, protectedStageIds, operation);
        };
    }

    private StageEdit renameStage(List<InteractionStage> stages, InteractionStageEditOperation operation) {
        int index = requiredStageIndex(stages, operation.id(), "id");
        InteractionStage stage = stages.get(index);
        String name = requiredText(operation.name(), "name", 200);
        stages.set(index, new InteractionStage(stage.id(), name, stage.order(), stage.optional()));
        return new StageEdit("Этап «" + stage.name() + "» переименован в «" + name + "»", null, null);
    }

    private StageEdit addStageAfter(
            List<InteractionStage> stages,
            UUID currentStageId,
            Set<UUID> protectedStageIds,
            InteractionStageEditOperation operation
    ) {
        int afterIndex = requiredStageIndex(stages, operation.afterId(), "afterId");
        InteractionStage after = stages.get(afterIndex);
        requireFutureAnchor(stages, currentStageId, protectedStageIds, after);
        if (operation.optional() == null) {
            throw new InteractionValidationException("optional", "Заполните значение");
        }
        String name = requiredText(operation.name(), "name", 200);
        InteractionStage added = new InteractionStage(UUID.randomUUID(), name, after.order() + 1, operation.optional());
        stages.add(afterIndex + 1, added);
        return new StageEdit(
                "Добавлен " + (operation.optional() ? "необязательный" : "обязательный")
                        + " этап «" + name + "» после «" + after.name() + "»",
                null,
                added.id()
        );
    }

    private StageEdit moveStageAfter(
            List<InteractionStage> stages,
            UUID currentStageId,
            Set<UUID> protectedStageIds,
            InteractionStageEditOperation operation
    ) {
        int stageIndex = requiredStageIndex(stages, operation.id(), "id");
        int afterIndex = requiredStageIndex(stages, operation.afterId(), "afterId");
        if (stageIndex == afterIndex) {
            throw new InteractionValidationException("afterId", "Этап нельзя поставить после самого себя");
        }
        InteractionStage stage = stages.get(stageIndex);
        InteractionStage after = stages.get(afterIndex);
        requireFutureStage(stages, currentStageId, protectedStageIds, stage);
        requireFutureAnchor(stages, currentStageId, protectedStageIds, after);
        stages.remove(stageIndex);
        int targetIndex = requiredStageIndex(stages, after.id(), "afterId");
        stages.add(targetIndex + 1, stage);
        return new StageEdit("Этап «" + stage.name() + "» перемещён после «" + after.name() + "»", stage.id(), stage.id());
    }

    private StageEdit deleteStage(
            List<InteractionStage> stages,
            UUID currentStageId,
            Set<UUID> protectedStageIds,
            InteractionStageEditOperation operation
    ) {
        int index = requiredStageIndex(stages, operation.id(), "id");
        InteractionStage stage = stages.get(index);
        if (stage.id().equals(currentStageId) || protectedStageIds.contains(stage.id())) {
            throw new InteractionValidationException("id", "Нельзя удалить текущий этап или этап с событиями");
        }
        stages.remove(index);
        return new StageEdit("Удалён этап «" + stage.name() + "»", stage.id(), null);
    }

    private void requireFutureStage(
            List<InteractionStage> stages,
            UUID currentStageId,
            Set<UUID> protectedStageIds,
            InteractionStage stage
    ) {
        InteractionStage current = currentStage(stages, currentStageId);
        if (stage.order() <= current.order() || protectedStageIds.contains(stage.id())) {
            throw new InteractionValidationException("id", "Перемещать можно только будущие этапы без событий");
        }
    }

    private void requireFutureAnchor(
            List<InteractionStage> stages,
            UUID currentStageId,
            Set<UUID> protectedStageIds,
            InteractionStage stage
    ) {
        InteractionStage current = currentStage(stages, currentStageId);
        if (stage.order() < current.order()
                || (!stage.id().equals(currentStageId) && protectedStageIds.contains(stage.id()))) {
            throw new InteractionValidationException("afterId", "Выберите текущий этап или будущий этап без событий");
        }
    }

    private int requiredStageIndex(List<InteractionStage> stages, UUID stageId, String field) {
        if (stageId == null) {
            throw new InteractionValidationException(field, "Выберите этап");
        }
        for (int index = 0; index < stages.size(); index++) {
            if (stages.get(index).id().equals(stageId)) {
                return index;
            }
        }
        throw new InteractionValidationException(field, "Этап не относится к этому взаимодействию");
    }

    private void normalizeStageOrders(List<InteractionStage> stages) {
        for (int index = 0; index < stages.size(); index++) {
            InteractionStage stage = stages.get(index);
            stages.set(index, new InteractionStage(stage.id(), stage.name(), index, stage.optional()));
        }
    }

    private Organization requireVisibleOrganization(CrmProfile profile, UUID organizationId) {
        if (organizationId == null) {
            throw new InteractionValidationException("organizationId", "Укажите вуз");
        }
        return organizationRepository.findVisibleById(profile, organizationId)
                .orElseThrow(OrganizationNotFoundException::new);
    }

    private static void requireWorkableOrganization(boolean archived) {
        if (archived) {
            throw new InteractionValidationException(
                    "organizationId", "Организация в архиве; восстановите её, чтобы начать новую работу"
            );
        }
    }

    private VisibleInteraction requireVisibleInteraction(CrmProfile profile, UUID interactionId) {
        InteractionRepository.InteractionRow row = interactionRepository.findById(interactionId)
                .orElseThrow(InteractionNotFoundException::new);
        Organization organization = organizationRepository.findVisibleById(profile, row.organizationId())
                .orElseThrow(InteractionNotFoundException::new);
        return new VisibleInteraction(row, organization);
    }

    private VisibleInteraction requireVisibleInteractionForUpdate(CrmProfile profile, UUID interactionId) {
        InteractionRepository.InteractionRow row = interactionRepository.findByIdForUpdate(interactionId)
                .orElseThrow(InteractionNotFoundException::new);
        Organization organization = organizationRepository.findVisibleById(profile, row.organizationId())
                .orElseThrow(InteractionNotFoundException::new);
        return new VisibleInteraction(row, organization);
    }

    private List<UUID> validatedContactIds(UUID organizationId, List<UUID> requestedContactIds) {
        List<UUID> contactIds = requestedContactIds == null ? List.of() : new ArrayList<>(requestedContactIds);
        if (contactIds.size() > 100) {
            throw new InteractionValidationException("contactIds", "Можно выбрать не более 100 контактов");
        }
        if (contactIds.stream().anyMatch(id -> id == null)) {
            throw new InteractionValidationException("contactIds", "Некорректный идентификатор контакта");
        }
        LinkedHashSet<UUID> uniqueContactIds = new LinkedHashSet<>(contactIds);
        if (uniqueContactIds.size() != contactIds.size()) {
            throw new InteractionValidationException("contactIds", "Контакты не должны повторяться");
        }
        if (contactRepository.findIdsByOrganizationId(organizationId, contactIds).size() != contactIds.size()) {
            throw new InteractionValidationException("contactIds", "Все контакты должны относиться к вузу взаимодействия");
        }
        return List.copyOf(uniqueContactIds);
    }

    private UUID validatedProgramId(UUID programId) {
        if (programId == null) {
            return null;
        }
        if (catalogRepository.findActiveProgramById(programId).isEmpty()) {
            throw new InteractionValidationException("programId", "Выберите действующую программу из справочника");
        }
        return programId;
    }

    private List<UUID> validatedProductIds(List<UUID> requestedProductIds) {
        List<UUID> productIds = requestedProductIds(requestedProductIds);
        if (catalogRepository.findActiveProductIds(productIds).size() != productIds.size()) {
            throw new InteractionValidationException("productIds", "Выберите действующие продукты из справочника");
        }
        return productIds;
    }

    private List<UUID> requestedProductIds(List<UUID> requestedProductIds) {
        List<UUID> productIds = requestedProductIds == null ? List.of() : new ArrayList<>(requestedProductIds);
        if (productIds.size() > 100) {
            throw new InteractionValidationException("productIds", "Можно выбрать не более 100 продуктов");
        }
        if (productIds.stream().anyMatch(id -> id == null)) {
            throw new InteractionValidationException("productIds", "Некорректный идентификатор продукта");
        }
        LinkedHashSet<UUID> uniqueProductIds = new LinkedHashSet<>(productIds);
        if (uniqueProductIds.size() != productIds.size()) {
            throw new InteractionValidationException("productIds", "Продукты не должны повторяться");
        }
        return productIds.stream().sorted().toList();
    }

    private List<UUID> validatedAttachmentIds(List<UUID> requestedAttachmentIds) {
        List<UUID> attachmentIds = requestedAttachmentIds == null ? List.of() : new ArrayList<>(requestedAttachmentIds);
        if (attachmentIds.size() > 100) {
            throw new InteractionValidationException("attachmentIds", "Можно выбрать не более 100 файлов");
        }
        if (attachmentIds.stream().anyMatch(id -> id == null)) {
            throw new InteractionValidationException("attachmentIds", "Некорректный идентификатор файла");
        }
        LinkedHashSet<UUID> uniqueAttachmentIds = new LinkedHashSet<>(attachmentIds);
        if (uniqueAttachmentIds.size() != attachmentIds.size()) {
            throw new InteractionValidationException("attachmentIds", "Файлы не должны повторяться");
        }
        return List.copyOf(uniqueAttachmentIds);
    }

    private WorkflowTemplate requireAvailableTemplate(Organization organization, UUID templateId) {
        WorkflowTemplate template = (templateId == null
                ? workflowTemplateRepository.findDefaultForOrganizationForUpdate(organization.id())
                : workflowTemplateRepository.findByIdForUpdate(templateId).map(workflowTemplateRepository::toTemplate))
                .orElseThrow(() -> new InteractionValidationException("templateId", "Шаблон процесса недоступен"));
        if (template.teamId() != null && !template.teamId().equals(organization.teamId())) {
            throw new InteractionValidationException("templateId", "Шаблон процесса недоступен");
        }
        return template;
    }

    private InteractionWorkflowSnapshot snapshotWorkflow(WorkflowTemplate template) {
        Map<UUID, UUID> stageIds = new HashMap<>();
        List<InteractionStage> stages = template.stages().stream().map(stage -> {
            UUID interactionStageId = UUID.randomUUID();
            stageIds.put(stage.id(), interactionStageId);
            return new InteractionStage(interactionStageId, stage.name(), stage.order(), stage.optional());
        }).toList();
        return new InteractionWorkflowSnapshot(
                stages,
                WorkflowTemplateService.asInteractionTransitions(template.transitions(), stageIds)
        );
    }

    private InteractionSummary toSummary(
            InteractionRepository.InteractionListRow listRow,
            List<UUID> contactIds,
            List<UUID> productIds
    ) {
        InteractionRepository.InteractionRow row = listRow.row();
        return new InteractionSummary(
                row.id(),
                row.organizationId(),
                row.title(),
                row.currentStageId(),
                row.currentStageName(),
                row.nextAction(),
                row.nextActionAt(),
                contactIds,
                row.programId(),
                productIds,
                row.lastContactAt(),
                row.version(),
                row.createdBy(),
                row.createdAt(),
                row.updatedAt(),
                listRow.organizationName(),
                listRow.programName(),
                listRow.ownerManagerName(),
                row.marks(),
                listRow.lastEventType(),
                listRow.lastEventAt(),
                listRow.stageEnteredAt(),
                listRow.deputyManagerName(),
                listRow.deputyEndsOn()
        );
    }

    private Interaction toInteraction(InteractionRepository.InteractionRow row) {
        List<InteractionStage> stages = interactionRepository.findStages(row.id());
        List<InteractionStageTransition> transitions = interactionRepository.findTransitions(row.id());
        InteractionStage currentStage = currentStage(stages, row);
        CatalogReference program = row.programId() == null
                ? null
                : catalogRepository.findProgramById(row.programId())
                        .orElseThrow(() -> new IllegalStateException("Interaction program is unavailable"));
        return new Interaction(
                row.id(),
                row.organizationId(),
                row.title(),
                currentStage.id(),
                currentStage.name(),
                stages,
                transitions,
                allowedTransitions(stages, transitions, currentStage),
                row.nextAction(),
                row.nextActionAt(),
                interactionRepository.findContactIds(row.id()),
                row.programId(),
                program,
                interactionRepository.findProductIds(row.id()),
                interactionRepository.findProductAgreements(row.id()),
                attachmentRepository.findByInteractionId(row.id()),
                interactionRepository.findStageCompletions(row.id()),
                row.lastContactAt(),
                row.version(),
                row.createdBy(),
                row.createdAt(),
                row.updatedAt(),
                row.marks()
        );
    }

    private List<InteractionTransitionOption> allowedTransitions(
            List<InteractionStage> stages,
            List<InteractionStageTransition> transitions,
            InteractionStage currentStage
    ) {
        Map<UUID, InteractionStage> stagesById = new HashMap<>();
        stages.forEach(stage -> stagesById.put(stage.id(), stage));
        return transitions.stream()
                .filter(transition -> transition.fromStageId().equals(currentStage.id()))
                .map(transition -> {
                    InteractionStage target = stagesById.get(transition.toStageId());
                    if (target == null) {
                        throw new IllegalStateException("Interaction transition target is unavailable");
                    }
                    return new InteractionTransitionOption(target.id(), target.name(), transition.commentRequired());
                })
                .sorted(Comparator.comparing(option -> stagesById.get(option.stageId()).order()))
                .toList();
    }

    private InteractionStage currentStage(List<InteractionStage> stages, InteractionRepository.InteractionRow row) {
        return currentStage(stages, row.currentStageId());
    }

    private InteractionStage currentStage(List<InteractionStage> stages, UUID currentStageId) {
        return stages.stream()
                .filter(stage -> stage.id().equals(currentStageId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Interaction current stage is unavailable"));
    }

    private InteractionStage targetStage(List<InteractionStage> stages, UUID stageId, String field) {
        if (stageId == null) {
            throw new InteractionValidationException(field, "Выберите новый этап");
        }
        return stages.stream()
                .filter(stage -> stage.id().equals(stageId))
                .findFirst()
                .orElseThrow(() -> new InteractionValidationException(
                        field,
                        "Этап не относится к этому взаимодействию"
                ));
    }

    private InteractionStageTransition validateTransition(
            List<InteractionStageTransition> transitions,
            InteractionStage fromStage,
            InteractionStage toStage
    ) {
        return transitions.stream()
                .filter(transition -> transition.fromStageId().equals(fromStage.id())
                        && transition.toStageId().equals(toStage.id()))
                .findFirst()
                .orElseThrow(() -> new InteractionValidationException(
                        "toStageId",
                        "Переход на этот этап из текущего не разрешён"
                ));
    }

    private void requireExpectedVersion(InteractionRepository.InteractionRow row, int expectedVersion) {
        if (row.version() != expectedVersion) {
            throw InteractionConflictException.version(row.version());
        }
    }

    private InteractionConflictException versionConflict(UUID interactionId, int fallbackVersion) {
        int currentVersion = interactionRepository.findById(interactionId)
                .map(InteractionRepository.InteractionRow::version)
                .orElse(fallbackVersion);
        return InteractionConflictException.version(currentVersion);
    }

    private Interaction replayInteraction(
            UUID actorProfileId,
            CommandOperation operation,
            String idempotencyKey,
            String fingerprint
    ) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, operation, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved interaction command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved interaction command has no result");
        }
        return readInteraction(command.resultJson());
    }

    private InteractionCommentResult replayComment(UUID actorProfileId, String idempotencyKey, String fingerprint) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, CommandOperation.COMMENT_INTERACTION, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved interaction command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved interaction command has no result");
        }
        try {
            return objectMapper.readValue(command.resultJson(), InteractionCommentResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored interaction comment result cannot be read", exception);
        }
    }

    private Interaction storeInteraction(UUID commandId, Interaction interaction) {
        String resultJson = write(interaction);
        commandIdempotencyRepository.complete(commandId, resultJson);
        return readInteraction(resultJson);
    }

    private InteractionCommentResult storeComment(UUID commandId, InteractionCommentResult result) {
        String resultJson = write(result);
        commandIdempotencyRepository.complete(commandId, resultJson);
        try {
            return objectMapper.readValue(resultJson, InteractionCommentResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Interaction comment result cannot be read", exception);
        }
    }

    private Interaction readInteraction(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, Interaction.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored interaction command result cannot be read", exception);
        }
    }

    private String write(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Interaction command result cannot be stored", exception);
        }
    }

    private int requiredVersion(Integer version) {
        if (version == null || version < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите страницу");
        }
        return version;
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
        String normalized = optionalText(value, field, maxLength);
        if (normalized == null) {
            throw new InteractionValidationException(field, "Заполните значение");
        }
        return normalized;
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

    private record StageEdit(String description, UUID removedStageId, UUID insertedStageId) {
    }

    private record VisibleInteraction(InteractionRepository.InteractionRow row, Organization organization) {
    }

    private record InteractionStart(
            UUID organizationId,
            UUID ownerManagerId,
            String title,
            String nextAction,
            OffsetDateTime nextActionAt,
            UUID programId,
            OffsetDateTime lastContactAt,
            List<UUID> contactIds,
            List<UUID> productIds
    ) {
    }

    private record CreateInteractionCommand(
            UUID organizationId,
            String title,
            String nextAction,
            OffsetDateTime nextActionAt,
            List<UUID> contactIds,
            UUID programId,
            List<UUID> productIds,
            OffsetDateTime lastContactAt,
            UUID templateId
    ) {
    }

    private record TransitionInteractionCommand(
            UUID interactionId,
            int version,
            UUID toStageId,
            String comment,
            List<UUID> attachmentIds,
            NextStepPatch nextStep
    ) {
    }

    private record CommentInteractionCommand(
            UUID interactionId,
            int version,
            UUID stageId,
            String text,
            List<UUID> attachmentIds,
            NextStepPatch nextStep
    ) {
    }

    private record CompleteStageCommand(
            UUID interactionId,
            int version,
            UUID stageId,
            LocalDate completedOn,
            String comment,
            List<UUID> attachmentIds
    ) {
    }

    private record ClearStageCompletionCommand(UUID interactionId, int version, UUID stageId) {
    }

    private record StageEditInteractionCommand(
            UUID interactionId,
            int version,
            List<InteractionStageEditOperation> operations
    ) {
    }

    private record InteractionWorkflowSnapshot(
            List<InteractionStage> stages,
            List<InteractionStageTransition> transitions
    ) {
    }

    private record NextStepPatch(
            boolean nextActionSet,
            String nextAction,
            boolean nextActionAtSet,
            OffsetDateTime nextActionAt
    ) {
    }

    private record CompleteStepCommand(
            UUID interactionId,
            int version,
            String result,
            InteractionNextStep nextStep
    ) {
    }

    private record UpdatePlanCommand(
            UUID interactionId,
            int version,
            NextStepPatch nextStep,
            boolean programSet,
            UUID programId,
            List<UUID> productIds,
            String title,
            boolean lastContactAtSet,
            OffsetDateTime lastContactAt,
            List<UUID> contactIds
    ) {
    }

    private record ChangeStatusCommand(UUID interactionId, int version, InteractionWorkStatus status, String reason) {
    }

    private record UpdateFlagsCommand(
            UUID interactionId,
            int version,
            InteractionWaiting waitingOn,
            String waitingNote,
            String problem,
            InteractionRiskLevel riskLevel,
            String riskReason
    ) {
    }
}
