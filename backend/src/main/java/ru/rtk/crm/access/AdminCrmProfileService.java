package ru.rtk.crm.access;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminCrmProfileRepository.ProfileState;
import ru.rtk.crm.catalog.OrganizationAssignmentEvent;
import ru.rtk.crm.catalog.OrganizationAssignmentReason;
import ru.rtk.crm.catalog.OrganizationAssignmentRepository;
import ru.rtk.crm.catalog.OrganizationAssignmentTarget;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class AdminCrmProfileService {
    private static final int DISPLAY_NAME_LIMIT = 200;

    private final AdminCrmProfileRepository adminCrmProfileRepository;
    private final AdminTeamRepository adminTeamRepository;
    private final OrganizationAssignmentRepository organizationAssignmentRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public AdminCrmProfileService(
            AdminCrmProfileRepository adminCrmProfileRepository,
            AdminTeamRepository adminTeamRepository,
            OrganizationAssignmentRepository organizationAssignmentRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.adminCrmProfileRepository = adminCrmProfileRepository;
        this.adminTeamRepository = adminTeamRepository;
        this.organizationAssignmentRepository = organizationAssignmentRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AdminCrmProfilePage list(CrmProfile actor, AdminCrmProfileQuery query) {
        AdminAuthorization.requireAdmin(actor);
        return adminCrmProfileRepository.findPage(query);
    }

    @Transactional(readOnly = true)
    public List<CrmProfileEvent> events(CrmProfile actor, UUID profileId) {
        AdminAuthorization.requireAdmin(actor);
        adminCrmProfileRepository.findById(requiredProfileId(profileId))
                .orElseThrow(AdminCrmProfileNotFoundException::new);
        return adminCrmProfileRepository.findEventsByProfileId(profileId);
    }

    @Transactional
    public AdminCrmProfile update(
            CrmProfile actor,
            UUID profileId,
            AdminCrmProfileUpdateRequest request,
            String idempotencyKey,
            String requestId
    ) {
        AdminAuthorization.requireAdmin(actor);
        requiredProfileId(profileId);
        int expectedVersion = requiredVersion(request.version());
        ProfileUpdateCommand command = new ProfileUpdateCommand(
                profileId,
                expectedVersion,
                optionalDisplayName(request.displayName()),
                request.role(),
                request.teamIdPresent(),
                request.teamId(),
                request.active(),
                request.enrolmentOperator()
        );
        if (!command.changesAnything()) {
            throw new InteractionValidationException(
                    "body", "Укажите хотя бы одно изменение: имя, роль, команду, активность или флаг «Оператор зачисления»"
            );
        }
        if (actor.id().equals(profileId) && command.changesAccess()) {
            throw new InteractionValidationException(
                    command.active() != null ? "active" : command.role() != null ? "role" : "teamId",
                    "Администратор не может менять себе роль, команду или активность"
            );
        }
        String normalizedKey = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                actor.id(),
                CommandOperation.UPDATE_CRM_PROFILE,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replay(actor.id(), normalizedKey, fingerprint);
        }

        List<UUID> activeAdministrators = command.changesAccess()
                ? adminCrmProfileRepository.lockActiveAdministrators()
                : List.of();
        AdminCrmProfile target = adminCrmProfileRepository.findByIdForUpdate(profileId)
                .orElseThrow(AdminCrmProfileNotFoundException::new);
        if (target.version() != expectedVersion) {
            throw InteractionConflictException.crmProfileVersion(target.version());
        }
        ProfileState previous = ProfileState.of(target);
        ProfileState next = command.applyTo(previous);
        if (Boolean.TRUE.equals(command.enrolmentOperator()) && !next.enrolmentOperator()) {
            throw new InteractionValidationException(
                    "enrolmentOperator", "Флаг «Оператор зачисления» доступен только КАМ и руководителю"
            );
        }
        if (next.equals(previous)) {
            throw new InteractionValidationException("body", "Профиль уже имеет указанные значения");
        }
        boolean partner = previous.role() == UserRole.PARTNER || next.role() == UserRole.PARTNER;
        if (partner && (previous.role() != next.role() || !Objects.equals(previous.teamId(), next.teamId())
                || next.active() && !previous.active())) {
            throw new InteractionValidationException(
                    command.role() != null ? "role" : command.teamIdPresent() ? "teamId" : "active",
                    "Доступ представителя вуза открывают в карточке вуза, во вкладке «Контакты»; здесь его можно только закрыть"
            );
        }
        if (next.teamId() != null && !Objects.equals(next.teamId(), previous.teamId())
                && adminTeamRepository.findByIdForUpdate(next.teamId()).filter(team -> !team.archived()).isEmpty()) {
            throw new InteractionValidationException("teamId", "Команда не найдена или в архиве");
        }
        if (next.active() && !partner && next.role() != UserRole.ADMIN && next.role() != UserRole.MANAGEMENT
                && next.teamId() == null) {
            throw new InteractionValidationException("teamId", "Менеджер и руководитель без команды не могут быть активны");
        }
        boolean removesAdministrator = previous.active() && previous.role() == UserRole.ADMIN
                && !(next.active() && next.role() == UserRole.ADMIN);
        if (removesAdministrator && activeAdministrators.stream().noneMatch(id -> !id.equals(profileId))) {
            throw InteractionConflictException.lastActiveAdministrator();
        }
        boolean accessChanged = previous.role() != next.role()
                || !Objects.equals(previous.teamId(), next.teamId())
                || previous.active() != next.active()
                || previous.enrolmentOperator() != next.enrolmentOperator();
        if (!adminCrmProfileRepository.update(profileId, expectedVersion, next, accessChanged, now)) {
            int currentVersion = adminCrmProfileRepository.findById(profileId)
                    .map(AdminCrmProfile::version)
                    .orElseThrow(AdminCrmProfileNotFoundException::new);
            throw InteractionConflictException.crmProfileVersion(currentVersion);
        }

        String actorDisplayName = adminCrmProfileRepository.findDisplayName(actor.id())
                .orElseThrow(() -> new IllegalStateException("Administrator profile is unavailable for audit"));
        boolean keepsOwnership = next.active() && (next.role() == UserRole.USER || next.role() == UserRole.LEADER)
                && Objects.equals(previous.teamId(), next.teamId());
        if (!keepsOwnership) {
            OrganizationAssignmentReason reason = !next.active()
                    ? OrganizationAssignmentReason.PROFILE_BLOCKED
                    : next.role() != previous.role()
                            ? OrganizationAssignmentReason.PROFILE_ROLE_CHANGED
                            : OrganizationAssignmentReason.PROFILE_TEAM_CHANGED;
            unassignOwnedOrganizations(target, actor, actorDisplayName, commandId, auditRequestId, reason, now);
        }
        AdminCrmProfile updated = adminCrmProfileRepository.findById(profileId)
                .orElseThrow(AdminCrmProfileNotFoundException::new);
        adminCrmProfileRepository.insertEvent(
                UUID.randomUUID(),
                profileId,
                commandId,
                actor.id(),
                actorDisplayName,
                previous,
                next,
                auditRequestId,
                updated.version(),
                now
        );
        return store(commandId, updated);
    }

    private void unassignOwnedOrganizations(
            AdminCrmProfile target,
            CrmProfile actor,
            String actorDisplayName,
            UUID rootCommandId,
            String requestId,
            OrganizationAssignmentReason reason,
            OffsetDateTime occurredAt
    ) {
        for (OrganizationAssignmentTarget organization : organizationAssignmentRepository.lockOwnedOrganizations(target.id())) {
            if (!organizationAssignmentRepository.clearOwnerAfterProfileDeactivation(
                    organization.organizationId(),
                    target.id(),
                    organization.version(),
                    occurredAt
            )) {
                throw new IllegalStateException("Organization owner changed while CRM profile access was being changed");
            }
            OrganizationAssignmentEvent event = organizationAssignmentRepository.insertEvent(
                    UUID.randomUUID(),
                    organization.organizationId(),
                    rootCommandId,
                    target.id(),
                    target.displayName(),
                    null,
                    null,
                    actor.id(),
                    actorDisplayName,
                    requestId,
                    reason,
                    null,
                    organization.version() + 1,
                    occurredAt
            );
            if (event.commandId() == null) {
                throw new IllegalStateException("Organization deassignment audit event is unavailable");
            }
        }
    }

    private UUID requiredProfileId(UUID profileId) {
        if (profileId == null) {
            throw new InteractionValidationException("id", "Укажите профиль");
        }
        return profileId;
    }

    private int requiredVersion(Integer value) {
        if (value == null || value < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите страницу");
        }
        return value;
    }

    private String optionalDisplayName(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > DISPLAY_NAME_LIMIT) {
            throw new InteractionValidationException("displayName", "Имя должно содержать от 1 до 200 символов");
        }
        return normalized;
    }

    private AdminCrmProfile replay(UUID actorProfileId, String idempotencyKey, String fingerprint) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, CommandOperation.UPDATE_CRM_PROFILE, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved CRM profile command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved CRM profile command has no result");
        }
        return read(command.resultJson());
    }

    private AdminCrmProfile store(UUID commandId, AdminCrmProfile result) {
        String resultJson = write(result);
        commandIdempotencyRepository.complete(commandId, resultJson);
        return read(resultJson);
    }

    private AdminCrmProfile read(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, AdminCrmProfile.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored CRM profile command result cannot be read", exception);
        }
    }

    private String write(AdminCrmProfile result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("CRM profile command result cannot be stored", exception);
        }
    }

    private record ProfileUpdateCommand(
            UUID profileId,
            int version,
            String displayName,
            UserRole role,
            boolean teamIdPresent,
            UUID teamId,
            Boolean active,
            Boolean enrolmentOperator
    ) {
        boolean changesAccess() {
            return role != null || teamIdPresent || active != null;
        }

        boolean changesAnything() {
            return displayName != null || changesAccess() || enrolmentOperator != null;
        }

        ProfileState applyTo(ProfileState current) {
            UserRole nextRole = role == null ? current.role() : role;
            boolean operatorAllowed = nextRole == UserRole.USER || nextRole == UserRole.LEADER;
            return new ProfileState(
                    displayName == null ? current.displayName() : displayName,
                    nextRole,
                    teamIdPresent ? teamId : current.teamId(),
                    active == null ? current.active() : active,
                    operatorAllowed && (enrolmentOperator == null ? current.enrolmentOperator() : enrolmentOperator)
            );
        }
    }
}
