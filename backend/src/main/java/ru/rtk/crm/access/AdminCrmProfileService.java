package ru.rtk.crm.access;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
    private static final String KEYCLOAK_ACTOR = "Keycloak";
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminCrmProfileService.class);

    private final AdminCrmProfileRepository adminCrmProfileRepository;
    private final AdminTeamRepository adminTeamRepository;
    private final OrganizationAssignmentRepository organizationAssignmentRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final AccountSyncRepository accountSyncRepository;
    private final KeycloakAccountClient keycloakAccountClient;
    private final AccountSyncService accountSyncService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public AdminCrmProfileService(
            AdminCrmProfileRepository adminCrmProfileRepository,
            AdminTeamRepository adminTeamRepository,
            OrganizationAssignmentRepository organizationAssignmentRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            AccountSyncRepository accountSyncRepository,
            KeycloakAccountClient keycloakAccountClient,
            AccountSyncService accountSyncService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.adminCrmProfileRepository = adminCrmProfileRepository;
        this.adminTeamRepository = adminTeamRepository;
        this.organizationAssignmentRepository = organizationAssignmentRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.accountSyncRepository = accountSyncRepository;
        this.keycloakAccountClient = keycloakAccountClient;
        this.accountSyncService = accountSyncService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public AdminCrmProfilePage list(CrmProfile actor, AdminCrmProfileQuery query) {
        AdminAuthorization.requireAdmin(actor);
        return adminCrmProfileRepository.findPage(query);
    }

    @Transactional(readOnly = true)
    public List<AdminCrmProfile> exportRows(CrmProfile actor, AdminCrmProfileQuery query, int limit) {
        AdminAuthorization.requireAdmin(actor);
        return adminCrmProfileRepository.findRows(query, limit);
    }

    @Transactional(readOnly = true)
    public List<CrmProfileEvent> events(CrmProfile actor, UUID profileId) {
        AdminAuthorization.requireAdmin(actor);
        adminCrmProfileRepository.findById(requiredProfileId(profileId))
                .orElseThrow(AdminCrmProfileNotFoundException::new);
        return adminCrmProfileRepository.findEventsByProfileId(profileId);
    }

    // Порядок команды, когда меняется роль или требование второго фактора:
    // 1) короткая транзакция с блокировками проверяет команду и ставит признак «требует сверки»;
    // 2) вне транзакции изменение записывается в Keycloak;
    // 3) вторая транзакция снова проверяет команду, сохраняет профиль и снимает признак.
    // Пока признак стоит, вход сотрудника не переносит роль из Keycloak в CRM. Если шаг 2 или 3 не удался,
    // Keycloak возвращается к состоянию, сохранённому в CRM; если и это невозможно, признак остаётся, и администратор
    // видит у профиля «требует сверки». Так запросы к Keycloak не держат блокировки строк администраторов.
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
        // Администратор не меняет доступ себе: иначе одним действием можно потерять права и остаться без
        // администратора; смену делает другой администратор.
        if (actor.id().equals(profileId) && command.changesAccess()) {
            throw new InteractionValidationException(
                    command.active() != null ? "active" : command.role() != null ? "role" : "teamId",
                    "Администратор не может менять себе роль, команду или активность"
            );
        }
        String normalizedKey = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        Optional<AdminCrmProfile> replayed = findReplay(actor.id(), normalizedKey, fingerprint);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        // Первая транзакция короткая: она только проверяет команду и ставит признак «требует сверки». Keycloak
        // вызывается вне транзакции (см. порядок команды выше), чтобы сетевой запрос не держал блокировки строк.
        KeycloakChange change = transactionTemplate.execute(status -> prepareKeycloakChange(command));
        if (change == null) {
            return Objects.requireNonNull(transactionTemplate.execute(
                    status -> apply(actor, command, normalizedKey, fingerprint, auditRequestId, false)
            ));
        }
        try {
            if (change.role()) {
                keycloakAccountClient.setCrmRole(change.subject(), change.next().role());
            }
            if (change.privileged() && keycloakAccountClient.configured()) {
                keycloakAccountClient.setPrivileged(change.subject(), change.next().privileged());
            }
            return Objects.requireNonNull(transactionTemplate.execute(
                    status -> apply(actor, command, normalizedKey, fingerprint, auditRequestId, true)
            ));
        } catch (RuntimeException failure) {
            // Любой сбой на шаге 2 или 3 возвращает Keycloak к состоянию из CRM; исходная ошибка при этом не теряется.
            restoreKeycloak(actor.id(), profileId, change, auditRequestId);
            throw failure;
        }
    }

    private KeycloakChange prepareKeycloakChange(ProfileUpdateCommand command) {
        Validated validated = validate(command);
        ProfileState previous = ProfileState.of(validated.target());
        boolean roleChanged = previous.role() != validated.next().role();
        boolean privilegedChanged = previous.privileged() != validated.next().privileged();
        if (!roleChanged && !privilegedChanged) {
            return null;
        }
        // Если профиль уже требовал сверки, Keycloak выравнивается по новому состоянию целиком, иначе снятие признака
        // в шаге 3 скрыло бы прежнее расхождение.
        boolean unreconciled = validated.target().roleReconcileRequired();
        adminCrmProfileRepository.setRoleReconcileRequired(command.profileId(), true);
        return new KeycloakChange(
                subject(command.profileId()), roleChanged || unreconciled, privilegedChanged || unreconciled, validated.next()
        );
    }

    private void restoreKeycloak(UUID actorProfileId, UUID profileId, KeycloakChange change, String requestId) {
        if (!keycloakAccountClient.configured()) {
            adminCrmProfileRepository.setRoleReconcileRequired(profileId, false);
            return;
        }
        try {
            accountSyncService.reconcileRole(profileId, change.role(), change.privileged());
        } catch (RuntimeException failure) {
            accountSyncService.recordReconcileFailure(actorProfileId, profileId, failure, requestId);
        }
    }

    private AdminCrmProfile apply(
            CrmProfile actor,
            ProfileUpdateCommand command,
            String idempotencyKey,
            String fingerprint,
            String auditRequestId,
            boolean keycloakChanged
    ) {
        UUID profileId = command.profileId();
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                actor.id(),
                CommandOperation.UPDATE_CRM_PROFILE,
                idempotencyKey,
                fingerprint,
                now
        )) {
            return replay(actor.id(), idempotencyKey, fingerprint);
        }
        Validated validated = validate(command);
        AdminCrmProfile target = validated.target();
        ProfileState previous = ProfileState.of(target);
        ProfileState next = validated.next();
        boolean roleChanged = previous.role() != next.role();
        // Проверка при повторном чтении под блокировкой: если между шагами роль изменилась, а Keycloak не менялся,
        // команда устарела, и Keycloak с CRM разойдутся; такой запрос отклоняется как конфликт версии.
        if (!keycloakChanged && (roleChanged || previous.privileged() != next.privileged())) {
            throw InteractionConflictException.crmProfileVersion(target.version());
        }
        boolean accessChanged = roleChanged
                || !Objects.equals(previous.teamId(), next.teamId())
                || previous.active() != next.active()
                || previous.enrolmentOperator() != next.enrolmentOperator();
        if (!adminCrmProfileRepository.update(profileId, command.version(), next, accessChanged, now)) {
            int currentVersion = adminCrmProfileRepository.findById(profileId)
                    .map(AdminCrmProfile::version)
                    .orElseThrow(AdminCrmProfileNotFoundException::new);
            throw InteractionConflictException.crmProfileVersion(currentVersion);
        }
        if (roleChanged) {
            adminCrmProfileRepository.markRoleSynced(profileId, now);
        }
        if (keycloakChanged) {
            adminCrmProfileRepository.setRoleReconcileRequired(profileId, false);
        }

        String actorDisplayName = adminCrmProfileRepository.findDisplayName(actor.id())
                .orElseThrow(() -> new IllegalStateException("Administrator profile is unavailable for audit"));
        // Закреплённые за сотрудником вузы снимаются, если он перестал быть действующим КАМ или руководителем
        // своей команды: вуз не должен оставаться за тем, кто не может с ним работать. Причина сохраняется в истории.
        boolean keepsOwnership = next.active() && (next.role() == UserRole.USER || next.role() == UserRole.LEADER)
                && Objects.equals(previous.teamId(), next.teamId());
        if (!keepsOwnership) {
            OrganizationAssignmentReason reason = !next.active()
                    ? OrganizationAssignmentReason.PROFILE_BLOCKED
                    : next.role() != previous.role()
                            ? OrganizationAssignmentReason.PROFILE_ROLE_CHANGED
                            : OrganizationAssignmentReason.PROFILE_TEAM_CHANGED;
            unassignOwnedOrganizations(target, actor.id(), actorDisplayName, commandId, auditRequestId, reason, now);
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

    private Validated validate(ProfileUpdateCommand command) {
        UUID profileId = command.profileId();
        // Блокировки берутся в одном порядке: сначала все действующие администраторы (по id), затем целевой профиль.
        // Единый порядок не даёт двум администраторам, одновременно лишающим друг друга прав, взаимно заблокироваться;
        // а под блокировкой надёжно проверяется правило «последний администратор».
        List<UUID> activeAdministrators = command.changesAccess()
                ? adminCrmProfileRepository.lockActiveAdministrators()
                : List.of();
        AdminCrmProfile target = adminCrmProfileRepository.findByIdForUpdate(profileId)
                .orElseThrow(AdminCrmProfileNotFoundException::new);
        if (target.version() != command.version()) {
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
        // Нельзя оставить систему без действующего администратора: ни блокировкой, ни сменой роли последнего.
        if (removesAdministrator && activeAdministrators.stream().noneMatch(id -> !id.equals(profileId))) {
            throw InteractionConflictException.lastActiveAdministrator();
        }
        return new Validated(target, next);
    }

    // Вызывается при входе: роль в Keycloak главнее роли в CRM. Если профиль ждёт сверки (CRM записывает роль в
    // Keycloak прямо сейчас), вход роль не переносит, чтобы не затереть только что сохранённую в CRM.
    @Transactional
    public void applyKeycloakRole(String issuer, String subject, Set<UserRole> keycloakRoles, String requestId) {
        UUID profileId = adminCrmProfileRepository.findIdByIdentity(issuer, subject).orElse(null);
        if (profileId == null) {
            return;
        }
        AdminCrmProfile target = adminCrmProfileRepository.findByIdForUpdate(profileId)
                .orElseThrow(AdminCrmProfileNotFoundException::new);
        if (target.roleReconcileRequired()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (target.role() == UserRole.PARTNER) {
            if (keycloakRoles.contains(UserRole.PARTNER)) {
                adminCrmProfileRepository.markRoleSynced(profileId, now);
            }
            return;
        }
        // Если в токене несколько ролей сотрудника, действует старшая (ADMIN, затем MANAGEMENT, LEADER, USER).
        // Токен вообще без роли сотрудника профиль не меняет: доступ при этом закрывает requireSessionProfile.
        UserRole role = UserRole.employeeRole(keycloakRoles).orElse(null);
        if (role == null) {
            return;
        }
        if (role == target.role()) {
            adminCrmProfileRepository.markRoleSynced(profileId, now);
            return;
        }
        ProfileState previous = ProfileState.of(target);
        boolean teamRole = role == UserRole.USER || role == UserRole.LEADER;
        // Роль из Keycloak не даёт команду: КАМ и руководитель без команды деактивируются и возвращаются в очередь
        // активации, флаг «Оператор зачисления» сохраняется только у ролей, которым он положен.
        ProfileState next = new ProfileState(
                previous.displayName(),
                role,
                previous.teamId(),
                previous.active() && (!teamRole || previous.teamId() != null),
                teamRole && previous.enrolmentOperator()
        );
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        UUID commandId = UUID.randomUUID();
        // Запись в таблице команд нужна как цель внешнего ключа command_id у событий истории профиля и назначений
        // вузов, как и при действии администратора; ключ уникален, поэтому смысла «повтора» здесь нет.
        commandIdempotencyRepository.reserve(
                commandId,
                profileId,
                CommandOperation.APPLY_KEYCLOAK_ROLE,
                "keycloak-role-" + commandId,
                CommandFingerprint.of(objectMapper, next),
                now
        );
        if (!adminCrmProfileRepository.applyKeycloakRole(profileId, target.version(), next, now)) {
            throw new IllegalStateException("CRM profile changed while the Keycloak role was being applied");
        }
        if (!(next.active() && teamRole)) {
            unassignOwnedOrganizations(
                    target, profileId, KEYCLOAK_ACTOR, commandId, auditRequestId, OrganizationAssignmentReason.PROFILE_ROLE_CHANGED, now
            );
        }
        if (previous.privileged() != next.privileged() && keycloakAccountClient.configured()) {
            try {
                keycloakAccountClient.setPrivileged(subject, next.privileged());
            } catch (AccountSyncException exception) {
                LOGGER.warn("Second factor requirement of CRM profile {} is not updated: {}", profileId, exception.getMessage());
            }
        }
        AdminCrmProfile updated = adminCrmProfileRepository.findById(profileId)
                .orElseThrow(AdminCrmProfileNotFoundException::new);
        adminCrmProfileRepository.insertEvent(
                UUID.randomUUID(), profileId, commandId, profileId, KEYCLOAK_ACTOR, previous, next, auditRequestId, updated.version(), now
        );
        store(commandId, updated);
    }

    private String subject(UUID profileId) {
        return accountSyncRepository.findAccount(profileId).orElseThrow(AdminCrmProfileNotFoundException::new).subject();
    }

    private void unassignOwnedOrganizations(
            AdminCrmProfile target,
            UUID actorProfileId,
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
                    actorProfileId,
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

    private Optional<AdminCrmProfile> findReplay(UUID actorProfileId, String idempotencyKey, String fingerprint) {
        return commandIdempotencyRepository.find(actorProfileId, CommandOperation.UPDATE_CRM_PROFILE, idempotencyKey)
                .map(command -> replay(actorProfileId, idempotencyKey, fingerprint));
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

    private record KeycloakChange(String subject, boolean role, boolean privileged, ProfileState next) {
    }

    private record Validated(AdminCrmProfile target, ProfileState next) {
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
