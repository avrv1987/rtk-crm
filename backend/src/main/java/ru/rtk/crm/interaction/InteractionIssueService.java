package ru.rtk.crm.interaction;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;

@Service
public class InteractionIssueService {
    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int TEXT_LIMIT = 1_000;

    private final OrganizationRepository organizationRepository;
    private final InteractionRepository interactionRepository;
    private final InteractionIssueRepository issueRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final InteractionService interactionService;
    private final ObjectMapper objectMapper;

    public InteractionIssueService(
            OrganizationRepository organizationRepository,
            InteractionRepository interactionRepository,
            InteractionIssueRepository issueRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            InteractionService interactionService,
            ObjectMapper objectMapper
    ) {
        this.organizationRepository = organizationRepository;
        this.interactionRepository = interactionRepository;
        this.issueRepository = issueRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.interactionService = interactionService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public InteractionIssueList list(CrmProfile profile, UUID interactionId) {
        Target target = visible(profile, interactionId, false);
        return new InteractionIssueList(
                issueRepository.findByInteraction(interactionId),
                issueRepository.findResponsibleOptions(target.organization().id())
        );
    }

    @Transactional(readOnly = true)
    public InteractionIssuePage registry(CrmProfile profile, InteractionIssueFilter filter, int page, int size) {
        if (page < 0) {
            throw new InteractionValidationException("page", "Номер страницы не может быть отрицательным");
        }
        if (size < 1 || size > 100) {
            throw new InteractionValidationException("size", "Размер страницы должен быть от 1 до 100");
        }
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return new InteractionIssuePage(List.of(), page, size, 0);
        }
        if (page > 100_000) {
            throw new InteractionValidationException("page", "Слишком большой номер страницы");
        }
        LocalDate today = LocalDate.now(ZONE);
        return new InteractionIssuePage(
                issueRepository.findVisible(scope.get(), filter, today, size, page * size),
                page,
                size,
                issueRepository.countVisible(scope.get(), filter, today)
        );
    }

    @Transactional(readOnly = true)
    public List<InteractionIssue> registryRows(CrmProfile profile, InteractionIssueFilter filter, int limit) {
        return organizationRepository.visibilityScope(profile)
                .map(scope -> issueRepository.findVisible(scope, filter, LocalDate.now(ZONE), limit, 0))
                .orElse(List.of());
    }

    @Transactional
    public Interaction create(CrmProfile profile, UUID interactionId, InteractionIssueRequest request, String idempotencyKey) {
        Target target = visible(profile, interactionId, true);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        int version = interactionService.requiredVersion(request.version());
        if (request.kind() == null) {
            throw new InteractionValidationException("kind", "Выберите: проблема или риск");
        }
        String description = requiredText(request.description(), "description", "Опишите проблему или риск");
        InteractionRiskLevel riskLevel = riskLevel(request.kind(), request.riskLevel());
        String key = interactionService.requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(objectMapper, new CreateIssueCommand(
                interactionId, version, request.kind(), description, riskLevel, request.responsibleId(), request.dueOn()
        ));
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(commandId, profile.id(), CommandOperation.CREATE_INTERACTION_ISSUE, key, fingerprint, now)) {
            return interactionService.replayInteraction(profile.id(), CommandOperation.CREATE_INTERACTION_ISSUE, key, fingerprint);
        }
        requireVersion(target, version);
        InteractionIssueList.ResponsibleOption responsible = responsible(profile, target, request.responsibleId(), null);
        issueRepository.insert(UUID.randomUUID(), interactionId, request.kind(), description, riskLevel,
                responsible.id(), request.dueOn(), profile.id(), now);
        String text = (request.kind() == InteractionIssueKind.PROBLEM ? "Добавлена проблема" : "Добавлен риск (" + riskLevel.label() + ")")
                + ": «" + description + "». Ответственный: " + responsible.displayName()
                + (request.dueOn() == null ? "" : ", срок " + DATE.format(request.dueOn()));
        return record(profile, target, commandId, version, text, now);
    }

    @Transactional
    public Interaction update(
            CrmProfile profile,
            UUID interactionId,
            UUID issueId,
            InteractionIssueRequest request,
            String idempotencyKey
    ) {
        Target target = visible(profile, interactionId, true);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        int version = interactionService.requiredVersion(request.version());
        String description = requiredText(request.description(), "description", "Опишите проблему или риск");
        String key = interactionService.requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(objectMapper, new UpdateIssueCommand(
                interactionId, issueId, version, request.kind(), description, request.riskLevel(), request.responsibleId(), request.dueOn()
        ));
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(commandId, profile.id(), CommandOperation.UPDATE_INTERACTION_ISSUE, key, fingerprint, now)) {
            return interactionService.replayInteraction(profile.id(), CommandOperation.UPDATE_INTERACTION_ISSUE, key, fingerprint);
        }
        requireVersion(target, version);
        InteractionIssue issue = openIssue(interactionId, issueId);
        if (request.kind() != null && request.kind() != issue.kind()) {
            throw new InteractionValidationException("kind", "Вид записи изменить нельзя");
        }
        InteractionRiskLevel riskLevel = riskLevel(issue.kind(), request.riskLevel());
        InteractionIssueList.ResponsibleOption responsible = responsible(profile, target, request.responsibleId(), issue);
        List<String> changes = new ArrayList<>();
        if (!issue.description().equals(description)) {
            changes.add("описание: «" + issue.description() + "» → «" + description + "»");
        }
        if (issue.riskLevel() != riskLevel) {
            changes.add("уровень: " + issue.riskLevel().label() + " → " + riskLevel.label());
        }
        if (!issue.responsibleId().equals(responsible.id())) {
            changes.add("ответственный: " + issue.responsibleName() + " → " + responsible.displayName());
        }
        if (!Objects.equals(issue.dueOn(), request.dueOn())) {
            changes.add("срок: " + dueText(issue.dueOn()) + " → " + dueText(request.dueOn()));
        }
        if (changes.isEmpty()) {
            return interactionService.storeInteraction(commandId, interactionService.get(profile, interactionId));
        }
        issueRepository.update(issueId, description, riskLevel, responsible.id(), request.dueOn());
        String text = (issue.kind() == InteractionIssueKind.PROBLEM ? "Изменена проблема" : "Изменён риск")
                + " «" + issue.description() + "»: " + String.join("; ", changes);
        return record(profile, target, commandId, version, text, now);
    }

    @Transactional
    public Interaction resolve(
            CrmProfile profile,
            UUID interactionId,
            UUID issueId,
            InteractionIssueResolution request,
            String idempotencyKey
    ) {
        Target target = visible(profile, interactionId, true);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        int version = interactionService.requiredVersion(request.version());
        String resolution = requiredText(request.resolution(), "resolution", "Напишите, как решили");
        String key = interactionService.requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(objectMapper, new ResolveIssueCommand(interactionId, issueId, version, resolution));
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(commandId, profile.id(), CommandOperation.RESOLVE_INTERACTION_ISSUE, key, fingerprint, now)) {
            return interactionService.replayInteraction(profile.id(), CommandOperation.RESOLVE_INTERACTION_ISSUE, key, fingerprint);
        }
        requireVersion(target, version);
        InteractionIssue issue = openIssue(interactionId, issueId);
        issueRepository.resolve(issueId, resolution, profile.id(), now);
        String text = (issue.kind() == InteractionIssueKind.PROBLEM ? "Решена проблема" : "Решён риск")
                + " «" + issue.description() + "». Решение: " + resolution;
        return record(profile, target, commandId, version, text, now);
    }

    private Target visible(CrmProfile profile, UUID interactionId, boolean forUpdate) {
        InteractionRepository.InteractionRow row = (forUpdate
                ? interactionRepository.findByIdForUpdate(interactionId)
                : interactionRepository.findById(interactionId))
                .orElseThrow(InteractionNotFoundException::new);
        Organization organization = organizationRepository.findVisibleById(profile, row.organizationId())
                .orElseThrow(InteractionNotFoundException::new);
        return new Target(row, organization);
    }

    private void requireVersion(Target target, int version) {
        if (target.row().version() != version) {
            throw InteractionConflictException.version(target.row().version());
        }
    }

    private InteractionIssue openIssue(UUID interactionId, UUID issueId) {
        InteractionIssue issue = issueRepository.findInInteraction(interactionId, issueId)
                .orElseThrow(InteractionIssueNotFoundException::new);
        if (issue.status() == InteractionIssueStatus.RESOLVED) {
            throw new InteractionValidationException("status", "Запись уже решена");
        }
        return issue;
    }

    private InteractionIssueList.ResponsibleOption responsible(
            CrmProfile profile,
            Target target,
            UUID requestedId,
            InteractionIssue current
    ) {
        if (current != null && (requestedId == null || requestedId.equals(current.responsibleId()))) {
            return new InteractionIssueList.ResponsibleOption(current.responsibleId(), current.responsibleName());
        }
        List<InteractionIssueList.ResponsibleOption> options = issueRepository.findResponsibleOptions(target.organization().id());
        if (requestedId != null) {
            return option(options, requestedId)
                    .orElseThrow(() -> new InteractionValidationException(
                            "responsibleId", "Ответственным можно выбрать только сотрудника команды вуза"
                    ));
        }
        UUID ownerId = target.organization().ownerManagerId();
        return (ownerId == null ? Optional.<InteractionIssueList.ResponsibleOption>empty() : option(options, ownerId))
                .or(() -> option(options, profile.id()))
                .orElseThrow(() -> new InteractionValidationException("responsibleId", "Выберите ответственного"));
    }

    private static Optional<InteractionIssueList.ResponsibleOption> option(
            List<InteractionIssueList.ResponsibleOption> options,
            UUID id
    ) {
        return options.stream().filter(option -> option.id().equals(id)).findFirst();
    }

    private Interaction record(CrmProfile profile, Target target, UUID commandId, int version, String text, OffsetDateTime now) {
        UUID interactionId = target.row().id();
        if (!interactionRepository.touchVersion(interactionId, version, now)) {
            throw InteractionConflictException.version(target.row().version());
        }
        InteractionStage currentStage = interactionRepository.findStages(interactionId).stream()
                .filter(stage -> stage.id().equals(target.row().currentStageId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Current interaction stage is unavailable"));
        interactionRepository.insertEvent(
                UUID.randomUUID(),
                interactionId,
                commandId,
                InteractionEventType.DETAILS_UPDATED,
                currentStage,
                null,
                null,
                text,
                null,
                profile.id(),
                target.organization().ownerManagerId(),
                version + 1,
                now
        );
        return interactionService.storeInteraction(commandId, interactionService.get(profile, interactionId));
    }

    private static InteractionRiskLevel riskLevel(InteractionIssueKind kind, InteractionRiskLevel requested) {
        if (kind == InteractionIssueKind.PROBLEM) {
            return null;
        }
        if (requested == null) {
            throw new InteractionValidationException("riskLevel", "Выберите уровень риска");
        }
        return requested;
    }

    private static String requiredText(String value, String field, String emptyMessage) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException(field, emptyMessage);
        }
        String normalized = value.trim();
        if (normalized.length() > TEXT_LIMIT) {
            throw new InteractionValidationException(field, "Слишком длинное значение");
        }
        return normalized;
    }

    private static String dueText(LocalDate value) {
        return value == null ? "не указан" : DATE.format(value);
    }

    private record Target(InteractionRepository.InteractionRow row, Organization organization) {
    }

    private record CreateIssueCommand(
            UUID interactionId,
            int version,
            InteractionIssueKind kind,
            String description,
            InteractionRiskLevel riskLevel,
            UUID responsibleId,
            LocalDate dueOn
    ) {
    }

    private record UpdateIssueCommand(
            UUID interactionId,
            UUID issueId,
            int version,
            InteractionIssueKind kind,
            String description,
            InteractionRiskLevel riskLevel,
            UUID responsibleId,
            LocalDate dueOn
    ) {
    }

    private record ResolveIssueCommand(UUID interactionId, UUID issueId, int version, String resolution) {
    }
}
