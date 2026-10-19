package ru.rtk.crm.access;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AccountSyncRepository.Account;
import ru.rtk.crm.access.AdminCrmProfileRepository.ProfileState;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.mail.MailInvitationService;
import ru.rtk.crm.web.InputRules;

@Service
public class EmployeeAccountService {
    private static final int DISPLAY_NAME_LIMIT = 200;
    private static final Pattern LOGIN = Pattern.compile("[a-z0-9][a-z0-9._-]{1,62}");
    private static final Logger LOGGER = LoggerFactory.getLogger(EmployeeAccountService.class);

    private final AdminCrmProfileRepository adminCrmProfileRepository;
    private final AdminTeamRepository adminTeamRepository;
    private final AccountSyncRepository accountSyncRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final KeycloakAccountClient keycloakAccountClient;
    private final AuditJournalRepository auditJournalRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<MailInvitationService> mailInvitations;

    public EmployeeAccountService(
            AdminCrmProfileRepository adminCrmProfileRepository,
            AdminTeamRepository adminTeamRepository,
            AccountSyncRepository accountSyncRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            KeycloakAccountClient keycloakAccountClient,
            AuditJournalRepository auditJournalRepository,
            ObjectMapper objectMapper,
            ApplicationEventPublisher events,
            ObjectProvider<MailInvitationService> mailInvitations
    ) {
        this.adminCrmProfileRepository = adminCrmProfileRepository;
        this.adminTeamRepository = adminTeamRepository;
        this.accountSyncRepository = accountSyncRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.keycloakAccountClient = keycloakAccountClient;
        this.auditJournalRepository = auditJournalRepository;
        this.objectMapper = objectMapper;
        this.events = events;
        this.mailInvitations = mailInvitations;
    }

    // При включённом модуле почты пароль на экран не выдаётся: сотрудник получает письмо со ссылкой и видит временный
    // пароль сам. Пустой результат — модуль выключен или почта неизвестна: пароль показывается администратору, как раньше.
    private Optional<String> invite(UUID actorId, UUID profileId, String login, String email, boolean newAccount, String requestId) {
        MailInvitationService invitations = mailInvitations.getIfAvailable();
        return invitations == null ? Optional.empty() : invitations.invite(actorId, profileId, login, email, newAccount, requestId);
    }

    @Transactional
    public AccountCredentials create(CrmProfile actor, NewEmployeeRequest request, String idempotencyKey, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        NewEmployee employee = NewEmployee.of(request);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        UUID commandId = UUID.randomUUID();
        Optional<AdminCrmProfile> replayed = reserve(commandId, actor, CommandOperation.CREATE_EMPLOYEE_ACCOUNT, idempotencyKey, employee);
        if (replayed.isPresent()) {
            return new AccountCredentials(replayed.get(), replayed.get().login(), null, null);
        }
        if (employee.teamId() != null
                && adminTeamRepository.findByIdForUpdate(employee.teamId()).filter(team -> !team.archived()).isEmpty()) {
            throw new InteractionValidationException("teamId", "Команда не найдена или в архиве");
        }
        String issuer = adminCrmProfileRepository.findIssuer(actor.id())
                .orElseThrow(() -> new IllegalStateException("Administrator profile is unavailable for account creation"));
        String password = TemporaryPassword.generate();
        String subject = keycloakAccountClient.createEmployeeUser(
                employee.login(), employee.email(), employee.firstName(), employee.lastName(), password
        );
        UUID profileId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        adminCrmProfileRepository.insertEmployee(
                profileId, issuer, subject, employee.displayName(), employee.login(), employee.role(), employee.teamId(), now
        );
        adminCrmProfileRepository.insertEvent(
                UUID.randomUUID(),
                profileId,
                commandId,
                actor.id(),
                actorDisplayName(actor),
                new ProfileState(employee.displayName(), employee.role(), employee.teamId(), false, false),
                new ProfileState(employee.displayName(), employee.role(), employee.teamId(), true, false),
                auditRequestId,
                0,
                now
        );
        Optional<String> invitationSentTo = invite(actor.id(), profileId, employee.login(), employee.email(), true, auditRequestId);
        auditJournalRepository.record(
                AuditAction.ACCOUNT_CREATED, actor.id(), "PROFILE", profileId, employee.displayName(),
                "сотрудник; логин " + employee.login() + (invitationSentTo.isPresent() ? "; приглашение отправлено на почту" : "; временный пароль выдан"),
                auditRequestId
        );
        String roleError = null;
        try {
            keycloakAccountClient.setCrmRole(subject, employee.role());
            adminCrmProfileRepository.markRoleSynced(profileId, now);
        } catch (AccountSyncException exception) {
            LOGGER.warn("Keycloak role is not assigned for new CRM profile {}: {}", profileId, exception.getMessage());
            auditJournalRepository.record(
                    AuditAction.ACCOUNT_SYNC_FAILED, actor.id(), "PROFILE", profileId, employee.displayName(),
                    "роль не назначена: " + exception.getMessage(), auditRequestId
            );
            roleError = exception.getMessage();
        }
        AdminCrmProfile created = complete(commandId, profileId);
        if (roleError != null) {
            created = created.withAccountSyncError(roleError);
        }
        if (employee.role() == UserRole.ADMIN && keycloakAccountClient.configured()) {
            try {
                keycloakAccountClient.setPrivileged(subject, true);
            } catch (AccountSyncException exception) {
                LOGGER.warn("Second factor is not required for new CRM profile {}: {}", profileId, exception.getMessage());
                auditJournalRepository.record(
                        AuditAction.ACCOUNT_SYNC_FAILED, actor.id(), "PROFILE", profileId, employee.displayName(),
                        "второй фактор не назначен: " + exception.getMessage(), auditRequestId
                );
                created = created.withAccountSyncError(exception.getMessage());
            }
        }
        return new AccountCredentials(created, employee.login(), invitationSentTo.isPresent() ? null : password, invitationSentTo.orElse(null));
    }

    @Transactional
    public AccountCredentials resetPassword(CrmProfile actor, UUID profileId, String idempotencyKey, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        requireOtherProfile(actor, profileId);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        UUID commandId = UUID.randomUUID();
        Optional<AdminCrmProfile> replayed = reserve(
                commandId, actor, CommandOperation.RESET_ACCOUNT_PASSWORD, idempotencyKey, new ProfileCommand(profileId, null)
        );
        if (replayed.isPresent()) {
            return new AccountCredentials(replayed.get(), replayed.get().login(), null, null);
        }
        AdminCrmProfile target = lockTarget(profileId);
        requireNotAdministrator(target);
        String password = TemporaryPassword.generate();
        keycloakAccountClient.resetTemporaryPassword(subject(profileId), password);
        Optional<String> invitationSentTo = invite(actor.id(), profileId, target.login(), null, false, auditRequestId);
        auditJournalRepository.record(
                AuditAction.ACCOUNT_PASSWORD_RESET, actor.id(), "PROFILE", profileId, target.displayName(),
                invitationSentTo.isPresent() ? "ссылка на новый пароль отправлена на почту" : "временный пароль выдан", auditRequestId
        );
        return new AccountCredentials(complete(commandId, profileId), target.login(), invitationSentTo.isPresent() ? null : password,
                invitationSentTo.orElse(null));
    }

    @Transactional
    public AdminCrmProfile endSessions(CrmProfile actor, UUID profileId, String idempotencyKey, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        requireOtherProfile(actor, profileId);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        UUID commandId = UUID.randomUUID();
        Optional<AdminCrmProfile> replayed = reserve(
                commandId, actor, CommandOperation.END_ACCOUNT_SESSIONS, idempotencyKey, new ProfileCommand(profileId, null)
        );
        if (replayed.isPresent()) {
            return replayed.get();
        }
        AdminCrmProfile target = lockTarget(profileId);
        keycloakAccountClient.logout(subject(profileId));
        int crmSessions = target.login() == null ? 0 : adminCrmProfileRepository.deleteSessions(target.login());
        // «Завершить сеансы» не меняет access_revision, поэтому сеансы подключаемых каналов (мини-приложение MAX)
        // закрываются по событию в этой же транзакции.
        events.publishEvent(new ProfileSessionsEnded(profileId));
        auditJournalRepository.record(
                AuditAction.ACCOUNT_SESSIONS_ENDED, actor.id(), "PROFILE", profileId, target.displayName(),
                "сеансы Keycloak завершены; сеансов CRM завершено: " + crmSessions, auditRequestId
        );
        return complete(commandId, profileId);
    }

    @Transactional
    public AdminCrmProfile resetSecondFactor(CrmProfile actor, UUID profileId, String idempotencyKey, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        requireOtherProfile(actor, profileId);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        UUID commandId = UUID.randomUUID();
        Optional<AdminCrmProfile> replayed = reserve(
                commandId, actor, CommandOperation.RESET_ACCOUNT_SECOND_FACTOR, idempotencyKey, new ProfileCommand(profileId, null)
        );
        if (replayed.isPresent()) {
            return replayed.get();
        }
        AdminCrmProfile target = lockTarget(profileId);
        requireNotAdministrator(target);
        String subject = subject(profileId);
        int removed = keycloakAccountClient.removeSecondFactor(subject);
        boolean required = ProfileState.of(target).privileged();
        if (required) {
            keycloakAccountClient.setPrivileged(subject, true);
        }
        auditJournalRepository.record(
                AuditAction.ACCOUNT_SECOND_FACTOR_RESET, actor.id(), "PROFILE", profileId, target.displayName(),
                "отвязано приложений: " + removed + (required ? "; при входе потребуется подключить заново" : ""), auditRequestId
        );
        return complete(commandId, profileId);
    }

    @Transactional
    public AdminCrmProfile changeEmail(
            CrmProfile actor,
            UUID profileId,
            AccountEmailRequest request,
            String idempotencyKey,
            String requestId
    ) {
        AdminAuthorization.requireAdmin(actor);
        requiredProfileId(profileId);
        String email = requiredEmail(request == null ? null : request.email());
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        UUID commandId = UUID.randomUUID();
        Optional<AdminCrmProfile> replayed = reserve(
                commandId, actor, CommandOperation.CHANGE_ACCOUNT_EMAIL, idempotencyKey, new ProfileCommand(profileId, email)
        );
        if (replayed.isPresent()) {
            return replayed.get();
        }
        AdminCrmProfile target = lockTarget(profileId);
        if (target.role() == UserRole.PARTNER) {
            throw new InteractionValidationException(
                    "email", "Почта представителя вуза — это почта контакта в карточке вуза; здесь она не меняется"
            );
        }
        keycloakAccountClient.changeEmail(subject(profileId), email);
        auditJournalRepository.record(
                AuditAction.ACCOUNT_EMAIL_CHANGED, actor.id(), "PROFILE", profileId, target.displayName(), null, auditRequestId
        );
        return complete(commandId, profileId);
    }

    @Transactional(readOnly = true)
    public LoginLockoutStatus loginLockout(CrmProfile actor, UUID profileId) {
        AdminAuthorization.requireAdmin(actor);
        String subject = subject(requiredProfileId(profileId));
        if (!keycloakAccountClient.configured()) {
            return LoginLockoutStatus.NOT_CONFIGURED;
        }
        return LoginLockoutStatus.of(keycloakAccountClient.loginLockout(subject));
    }

    // Снятие блокировки обнуляет счётчик неудачных входов в Keycloak; пароль и сеансы не меняются. Повтор безвреден,
    // поэтому ключ идемпотентности не нужен.
    @Transactional
    public LoginLockoutStatus releaseLoginLockout(CrmProfile actor, UUID profileId, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        AdminCrmProfile target = adminCrmProfileRepository.findById(requiredProfileId(profileId))
                .orElseThrow(AdminCrmProfileNotFoundException::new);
        String subject = subject(profileId);
        KeycloakAccountClient.LoginLockout before = keycloakAccountClient.loginLockout(subject);
        keycloakAccountClient.releaseLoginLockout(subject);
        auditJournalRepository.record(
                AuditAction.ACCOUNT_LOCKOUT_RELEASED, actor.id(), "PROFILE", profileId, target.displayName(),
                "неудачных попыток подряд: " + before.failures() + (before.locked() ? "; вход был временно заблокирован" : ""),
                auditRequestId
        );
        return LoginLockoutStatus.of(keycloakAccountClient.loginLockout(subject));
    }

    private Optional<AdminCrmProfile> reserve(
            UUID commandId,
            CrmProfile actor,
            CommandOperation operation,
            String idempotencyKey,
            Object command
    ) {
        String key = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        if (commandIdempotencyRepository.reserve(commandId, actor.id(), operation, key, fingerprint, OffsetDateTime.now())) {
            return Optional.empty();
        }
        CommandIdempotencyRepository.CommandRecord record = commandIdempotencyRepository.find(actor.id(), operation, key)
                .orElseThrow(() -> new IllegalStateException("Reserved account command is unavailable"));
        if (!fingerprint.equals(record.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (record.resultJson() == null) {
            throw new IllegalStateException("Reserved account command has no result");
        }
        try {
            return Optional.of(objectMapper.readValue(record.resultJson(), AdminCrmProfile.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored account command result cannot be read", exception);
        }
    }

    private AdminCrmProfile complete(UUID commandId, UUID profileId) {
        AdminCrmProfile profile = adminCrmProfileRepository.findById(profileId).orElseThrow(AdminCrmProfileNotFoundException::new);
        try {
            commandIdempotencyRepository.complete(commandId, objectMapper.writeValueAsString(profile));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Account command result cannot be stored", exception);
        }
        return profile;
    }

    private AdminCrmProfile lockTarget(UUID profileId) {
        return adminCrmProfileRepository.findByIdForUpdate(profileId).orElseThrow(AdminCrmProfileNotFoundException::new);
    }

    private String subject(UUID profileId) {
        return accountSyncRepository.findAccount(profileId).map(Account::subject).orElseThrow(AdminCrmProfileNotFoundException::new);
    }

    private String actorDisplayName(CrmProfile actor) {
        return adminCrmProfileRepository.findDisplayName(actor.id())
                .orElseThrow(() -> new IllegalStateException("Administrator profile is unavailable for audit"));
    }

    private static void requireOtherProfile(CrmProfile actor, UUID profileId) {
        if (actor.id().equals(requiredProfileId(profileId))) {
            throw new InteractionValidationException(
                    "id", "Для своей учётной записи это действие недоступно: пароль меняют и выходят из сеансов в самом Keycloak"
            );
        }
    }

    // Сброс пароля и второго фактора другого администратора дал бы войти от его имени, и журнал перестал бы
    // различать администраторов. Администратор меняет их сам в личном кабинете Keycloak, при утере — через консоль.
    private static void requireNotAdministrator(AdminCrmProfile target) {
        if (target.role() == UserRole.ADMIN) {
            throw new InteractionValidationException(
                    "id",
                    "Пароль и второй фактор администратора из CRM не сбрасываются: администратор меняет их сам в личном кабинете"
                            + " Keycloak, а если доступ утерян — администратор Keycloak в консоли Keycloak"
            );
        }
    }

    private static UUID requiredProfileId(UUID profileId) {
        if (profileId == null) {
            throw new InteractionValidationException("id", "Укажите профиль");
        }
        return profileId;
    }

    private static String requiredEmail(String value) {
        String email = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!InputRules.isLoginEmail(email)) {
            throw new InteractionValidationException("email", InputRules.LOGIN_EMAIL_RULE);
        }
        return email;
    }

    public record NewEmployeeRequest(String displayName, String login, String email, UserRole role, UUID teamId) {
    }

    public record AccountEmailRequest(String email) {
    }

    // invitationSentTo — маска адреса, куда ушла ссылка на пароль; тогда temporaryPassword пустой.
    public record AccountCredentials(AdminCrmProfile profile, String login, String temporaryPassword, String invitationSentTo) {
    }

    public record LoginLockoutStatus(boolean available, int failures, boolean locked, OffsetDateTime lastFailureAt) {
        static final LoginLockoutStatus NOT_CONFIGURED = new LoginLockoutStatus(false, 0, false, null);

        static LoginLockoutStatus of(KeycloakAccountClient.LoginLockout lockout) {
            return new LoginLockoutStatus(
                    true, lockout.failures(), lockout.locked(),
                    lockout.lastFailureAt() == null ? null : lockout.lastFailureAt().atOffset(ZoneOffset.UTC)
            );
        }
    }

    private record ProfileCommand(UUID profileId, String email) {
    }

    private record NewEmployee(String displayName, String login, String email, UserRole role, UUID teamId) {
        static NewEmployee of(NewEmployeeRequest request) {
            if (request == null) {
                throw new InteractionValidationException("body", "Заполните данные сотрудника");
            }
            String displayName = request.displayName() == null ? "" : request.displayName().strip();
            // ФИО и почту проверяем до Keycloak: он отклоняет угловые скобки и кириллицу в почте ответом 400, и раньше
            // ошибка пользователя выглядела как сбой сервера (503).
            if (!InputRules.isPersonName(displayName, DISPLAY_NAME_LIMIT)) {
                throw new InteractionValidationException("displayName", "ФИО: от 1 до 200 символов — " + InputRules.NAME_RULE);
            }
            String login = request.login() == null ? "" : request.login().strip().toLowerCase(Locale.ROOT);
            if (!LOGIN.matcher(login).matches()) {
                throw new InteractionValidationException(
                        "login", "Логин: от 2 до 63 символов — латинские буквы, цифры, точка, дефис или подчёркивание, начиная с буквы или цифры"
                );
            }
            String email = requiredEmail(request.email());
            if (request.role() == null) {
                throw new InteractionValidationException("role", "Выберите роль");
            }
            if (request.role() == UserRole.PARTNER) {
                throw new InteractionValidationException(
                        "role", "Доступ представителя вуза открывают в карточке вуза, во вкладке «Контакты»"
                );
            }
            if ((request.role() == UserRole.USER || request.role() == UserRole.LEADER) && request.teamId() == null) {
                throw new InteractionValidationException("teamId", "КАМ и руководителю нужна команда");
            }
            return new NewEmployee(displayName, login, email, request.role(), request.teamId());
        }

        String lastName() {
            int space = displayName.indexOf(' ');
            return space < 0 ? displayName : displayName.substring(0, space);
        }

        String firstName() {
            int space = displayName.indexOf(' ');
            return space < 0 ? displayName : displayName.substring(space + 1).strip();
        }
    }
}
