package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class OrganizationCatalogService {
    private static final int SIMILAR_MIN_LENGTH = 4;
    private static final int DUPLICATES_LIMIT = 10;

    private final OrganizationRepository organizationRepository;
    private final AdminOrganizationRepository adminOrganizationRepository;
    private final OrganizationCatalogRepository organizationCatalogRepository;
    private final OrganizationAssignmentRepository organizationAssignmentRepository;
    private final CatalogChangeEventRepository catalogChangeEventRepository;
    private final IdempotentCommandRunner commandRunner;
    private final ApplicationEventPublisher eventPublisher;
    private final boolean managerCreationRequiresApproval;

    public OrganizationCatalogService(
            OrganizationRepository organizationRepository,
            AdminOrganizationRepository adminOrganizationRepository,
            OrganizationCatalogRepository organizationCatalogRepository,
            OrganizationAssignmentRepository organizationAssignmentRepository,
            CatalogChangeEventRepository catalogChangeEventRepository,
            IdempotentCommandRunner commandRunner,
            ApplicationEventPublisher eventPublisher,
            @Value("${app.organizations.manager-creation-requires-approval:true}") boolean managerCreationRequiresApproval
    ) {
        this.organizationRepository = organizationRepository;
        this.adminOrganizationRepository = adminOrganizationRepository;
        this.organizationCatalogRepository = organizationCatalogRepository;
        this.organizationAssignmentRepository = organizationAssignmentRepository;
        this.catalogChangeEventRepository = catalogChangeEventRepository;
        this.commandRunner = commandRunner;
        this.eventPublisher = eventPublisher;
        this.managerCreationRequiresApproval = managerCreationRequiresApproval;
    }

    @Transactional(readOnly = true)
    public List<OrganizationDuplicate> findDuplicates(CrmProfile actor, String name, UUID exceptOrganizationId) {
        String normalized = CatalogNames.normalized(name);
        if (normalized.length() < 2) {
            return List.of();
        }
        List<OrganizationDuplicate> duplicates = new ArrayList<>();
        for (OrganizationCatalogRepository.NameRow row : organizationCatalogRepository.findNames()) {
            if (row.id().equals(exceptOrganizationId)) {
                continue;
            }
            String other = CatalogNames.normalized(row.name());
            boolean exact = other.equals(normalized);
            boolean similar = !exact && Math.min(other.length(), normalized.length()) >= SIMILAR_MIN_LENGTH
                    && (other.contains(normalized) || normalized.contains(other));
            boolean visible = visibleTo(actor, row);
            if (exact || (similar && visible)) {
                duplicates.add(new OrganizationDuplicate(
                        visible ? row.id() : null, row.name(), row.type(), row.status(), row.teamName(), exact
                ));
            }
        }
        return duplicates.stream()
                .sorted(Comparator.comparing((OrganizationDuplicate duplicate) -> !duplicate.exact())
                        .thenComparing(OrganizationDuplicate::name))
                .limit(DUPLICATES_LIMIT)
                .toList();
    }

    @Transactional
    public OrganizationCommandResult create(
            CrmProfile actor,
            OrganizationDetailsRequest request,
            String idempotencyKey,
            String requestId
    ) {
        requireBusinessActor(actor);
        OrganizationDetails details = OrganizationDetails.from(request);
        boolean pending = actor.role() == UserRole.USER && managerCreationRequiresApproval;
        UUID ownerManagerId = actor.role() == UserRole.USER ? actor.id() : null;
        return createOrganization(actor, details, actor.teamId(), ownerManagerId, pending, idempotencyKey, requestId);
    }

    @Transactional
    public OrganizationCommandResult createAsAdmin(
            CrmProfile actor,
            OrganizationDetailsRequest request,
            String idempotencyKey,
            String requestId
    ) {
        AdminAuthorization.requireAdmin(actor);
        OrganizationDetails details = OrganizationDetails.from(request);
        if (request.teamId() == null) {
            throw new InteractionValidationException("teamId", "Выберите команду");
        }
        return createOrganization(actor, details, request.teamId(), null, false, idempotencyKey, requestId);
    }

    @Transactional
    public OrganizationCommandResult update(
            CrmProfile actor,
            UUID organizationId,
            OrganizationDetailsRequest request,
            String idempotencyKey,
            String requestId
    ) {
        requireBusinessActor(actor);
        return updateOrganization(actor, organizationId, request, idempotencyKey, requestId);
    }

    @Transactional
    public OrganizationCommandResult updateAsAdmin(
            CrmProfile actor,
            UUID organizationId,
            OrganizationDetailsRequest request,
            String idempotencyKey,
            String requestId
    ) {
        AdminAuthorization.requireAdmin(actor);
        return updateOrganization(actor, organizationId, request, idempotencyKey, requestId);
    }

    @Transactional
    public OrganizationCommandResult changeStatus(
            CrmProfile actor,
            UUID organizationId,
            OrganizationStatusRequest request,
            String idempotencyKey,
            String requestId
    ) {
        requireBusinessActor(actor);
        return changeOrganizationStatus(actor, organizationId, request, idempotencyKey, requestId);
    }

    @Transactional
    public OrganizationCommandResult changeStatusAsAdmin(
            CrmProfile actor,
            UUID organizationId,
            OrganizationStatusRequest request,
            String idempotencyKey,
            String requestId
    ) {
        AdminAuthorization.requireAdmin(actor);
        return changeOrganizationStatus(actor, organizationId, request, idempotencyKey, requestId);
    }

    private OrganizationCommandResult createOrganization(
            CrmProfile actor,
            OrganizationDetails details,
            UUID teamId,
            UUID ownerManagerId,
            boolean pending,
            String idempotencyKey,
            String requestId
    ) {
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        CreateCommand command = new CreateCommand(teamId, details);
        return commandRunner.run(actor.id(), CommandOperation.CREATE_ORGANIZATION, idempotencyKey, command,
                OrganizationCommandResult.class, commandId -> {
                    if (!adminOrganizationRepository.activeTeamExists(teamId)) {
                        throw new InteractionValidationException("teamId", "Команда не найдена или в архиве");
                    }
                    catalogChangeEventRepository.lockNames(CatalogEntityType.ORGANIZATION);
                    requireNoDuplicate(actor, details.name(), null);
                    UUID organizationId = UUID.randomUUID();
                    OffsetDateTime now = OffsetDateTime.now();
                    OrganizationStatus status = pending ? OrganizationStatus.PENDING : OrganizationStatus.ACTIVE;
                    try {
                        organizationCatalogRepository.insert(organizationId, details, teamId, ownerManagerId, status, now);
                    } catch (DuplicateKeyException exception) {
                        throw duplicateName(details.name());
                    }
                    if (ownerManagerId != null) {
                        organizationAssignmentRepository.incrementAccessRevisions(null, ownerManagerId);
                    }
                    AdminOrganization created = adminOrganizationRepository.findById(organizationId)
                            .orElseThrow(OrganizationNotFoundException::new);
                    List<String> changes = new ArrayList<>(details.describe());
                    changes.add("Команда: " + created.teamName());
                    catalogChangeEventRepository.insert(
                            CatalogEntityType.ORGANIZATION,
                            organizationId,
                            pending ? CatalogChangeAction.REQUEST : CatalogChangeAction.CREATE,
                            details.name(),
                            String.join("; ", changes),
                            actor.id(),
                            auditRequestId,
                            now
                    );
                    return new OrganizationCommandResult(organizationId);
                });
    }

    private OrganizationCommandResult updateOrganization(
            CrmProfile actor,
            UUID organizationId,
            OrganizationDetailsRequest request,
            String idempotencyKey,
            String requestId
    ) {
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        OrganizationDetails details = OrganizationDetails.from(request);
        int expectedVersion = requiredVersion(request.version());
        UpdateCommand command = new UpdateCommand(organizationId, expectedVersion, details);
        return commandRunner.run(actor.id(), CommandOperation.UPDATE_ORGANIZATION, idempotencyKey, command,
                OrganizationCommandResult.class, commandId -> {
                    AdminOrganization current = lockManageable(actor, organizationId);
                    if (actor.role() == UserRole.USER) {
                        throw new CatalogChangeAccessDeniedException();
                    }
                    requireVersion(current, expectedVersion);
                    OrganizationDetails previous = new OrganizationDetails(
                            current.name(), current.type(), current.city(), current.website(), current.inn()
                    );
                    List<String> changes = details.changesFrom(previous);
                    if (changes.isEmpty()) {
                        throw new InteractionValidationException("body", "Данные организации не изменились");
                    }
                    if (!CatalogNames.normalized(previous.name()).equals(CatalogNames.normalized(details.name()))) {
                        catalogChangeEventRepository.lockNames(CatalogEntityType.ORGANIZATION);
                        requireNoDuplicate(actor, details.name(), organizationId);
                    }
                    OffsetDateTime now = OffsetDateTime.now();
                    try {
                        if (!organizationCatalogRepository.updateDetails(organizationId, expectedVersion, details, now)) {
                            throw InteractionConflictException.organizationVersion(current.version());
                        }
                    } catch (DuplicateKeyException exception) {
                        throw duplicateName(details.name());
                    }
                    catalogChangeEventRepository.insert(
                            CatalogEntityType.ORGANIZATION, organizationId, CatalogChangeAction.UPDATE, details.name(),
                            String.join("; ", changes), actor.id(), auditRequestId, now
                    );
                    return new OrganizationCommandResult(organizationId);
                });
    }

    private OrganizationCommandResult changeOrganizationStatus(
            CrmProfile actor,
            UUID organizationId,
            OrganizationStatusRequest request,
            String idempotencyKey,
            String requestId
    ) {
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        if (request == null || request.action() == null) {
            throw new InteractionValidationException("action", "Выберите действие");
        }
        int expectedVersion = requiredVersion(request.version());
        String reason = CatalogNames.clean(request.reason());
        if (reason.length() > 500) {
            throw new InteractionValidationException("reason", "Причина длиннее 500 символов");
        }
        StatusCommand command = new StatusCommand(organizationId, request.action(), expectedVersion, reason);
        return commandRunner.run(actor.id(), CommandOperation.CHANGE_ORGANIZATION_STATUS, idempotencyKey, command,
                OrganizationCommandResult.class, commandId -> {
                    AdminOrganization current = lockManageable(actor, organizationId);
                    OrganizationStatus next = nextStatus(actor, current, request.action());
                    requireVersion(current, expectedVersion);
                    OffsetDateTime now = OffsetDateTime.now();
                    if (!organizationCatalogRepository.updateStatus(organizationId, expectedVersion, next, now)) {
                        throw InteractionConflictException.organizationVersion(current.version());
                    }
                    catalogChangeEventRepository.insert(
                            CatalogEntityType.ORGANIZATION, organizationId, changeAction(request.action()), current.name(),
                            reason.isEmpty() ? null : "Причина: " + reason, actor.id(), auditRequestId, now
                    );
                    if (next == OrganizationStatus.ARCHIVED) {
                        eventPublisher.publishEvent(new CatalogArchivedEvent(organizationId, null, actor.id(), auditRequestId));
                    }
                    return new OrganizationCommandResult(organizationId);
                });
    }

    private OrganizationStatus nextStatus(CrmProfile actor, AdminOrganization current, OrganizationStatusAction action) {
        boolean manager = actor.role() == UserRole.USER;
        return switch (action) {
            case APPROVE, REJECT -> {
                if (manager) {
                    throw new CatalogChangeAccessDeniedException();
                }
                if (current.status() != OrganizationStatus.PENDING) {
                    throw new InteractionValidationException("action", "Организация не ожидает подтверждения");
                }
                yield action == OrganizationStatusAction.APPROVE ? OrganizationStatus.ACTIVE : OrganizationStatus.ARCHIVED;
            }
            case ARCHIVE -> {
                if (current.status() == OrganizationStatus.ARCHIVED) {
                    throw new InteractionValidationException("action", "Организация уже в архиве");
                }
                yield OrganizationStatus.ARCHIVED;
            }
            case RESTORE -> {
                if (manager) {
                    throw new CatalogChangeAccessDeniedException();
                }
                if (current.status() != OrganizationStatus.ARCHIVED) {
                    throw new InteractionValidationException("action", "Организация не в архиве");
                }
                if (!adminOrganizationRepository.activeTeamExists(current.teamId())) {
                    throw new InteractionValidationException(
                            "action", "Команда организации в архиве: восстановите команду или перенесите организацию"
                    );
                }
                yield OrganizationStatus.ACTIVE;
            }
        };
    }

    private static CatalogChangeAction changeAction(OrganizationStatusAction action) {
        return switch (action) {
            case APPROVE -> CatalogChangeAction.APPROVE;
            case REJECT -> CatalogChangeAction.REJECT;
            case ARCHIVE -> CatalogChangeAction.ARCHIVE;
            case RESTORE -> CatalogChangeAction.RESTORE;
        };
    }

    private AdminOrganization lockManageable(CrmProfile actor, UUID organizationId) {
        if (organizationId == null) {
            throw new InteractionValidationException("id", "Укажите организацию");
        }
        if (actor.role() != UserRole.ADMIN && organizationRepository.findVisibleById(actor, organizationId).isEmpty()) {
            throw new OrganizationNotFoundException();
        }
        return adminOrganizationRepository.findByIdForUpdate(organizationId)
                .orElseThrow(OrganizationNotFoundException::new);
    }

    private void requireNoDuplicate(CrmProfile actor, String name, UUID exceptOrganizationId) {
        findDuplicates(actor, name, exceptOrganizationId).stream()
                .filter(OrganizationDuplicate::exact)
                .findFirst()
                .ifPresent(duplicate -> {
                    throw new InteractionValidationException("name", duplicate.id() == null
                            ? "Организация «" + duplicate.name() + "» уже есть в CRM в команде «" + duplicate.teamName()
                                    + "». Обратитесь к руководителю или администратору."
                            : "Организация «" + duplicate.name() + "» уже есть в CRM"
                                    + (duplicate.status() == OrganizationStatus.ARCHIVED ? " (в архиве)" : "") + ".");
                });
    }

    private static InteractionValidationException duplicateName(String name) {
        return new InteractionValidationException("name", "Организация «" + name + "» уже есть в CRM.");
    }

    private static boolean visibleTo(CrmProfile actor, OrganizationCatalogRepository.NameRow row) {
        return switch (actor.role()) {
            case ADMIN, MANAGEMENT -> true;
            case LEADER -> Objects.equals(actor.teamId(), row.teamId());
            case USER -> Objects.equals(actor.teamId(), row.teamId()) && actor.id().equals(row.ownerManagerId());
            case PARTNER -> false;
        };
    }

    private static void requireBusinessActor(CrmProfile actor) {
        if (actor.role() != UserRole.USER && actor.role() != UserRole.LEADER || actor.teamId() == null) {
            throw new CatalogChangeAccessDeniedException();
        }
    }

    private static void requireVersion(AdminOrganization current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw InteractionConflictException.organizationVersion(current.version());
        }
    }

    private static int requiredVersion(Integer version) {
        if (version == null || version < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите страницу");
        }
        return version;
    }

    private record CreateCommand(UUID teamId, OrganizationDetails details) {
    }

    private record UpdateCommand(UUID organizationId, int version, OrganizationDetails details) {
    }

    private record StatusCommand(UUID organizationId, OrganizationStatusAction action, int version, String reason) {
    }
}
