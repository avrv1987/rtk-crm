package ru.rtk.crm.access;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Service
public class EmployeeAccountService {
    private static final int DISPLAY_NAME_LIMIT = 200;
    private static final Pattern LOGIN = Pattern.compile("[a-z0-9][a-z0-9._-]{1,62}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final int EMAIL_LIMIT = 254;

    private final AdminCrmProfileRepository adminCrmProfileRepository;
    private final AdminTeamRepository adminTeamRepository;
    private final AccountSyncRepository accountSyncRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final KeycloakAccountClient keycloakAccountClient;
    private final AuditJournalRepository auditJournalRepository;
    private final ObjectMapper objectMapper;

    public EmployeeAccountService(
            AdminCrmProfileRepository adminCrmProfileRepository,
            AdminTeamRepository adminTeamRepository,
            AccountSyncRepository accountSyncRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            KeycloakAccountClient keycloakAccountClient,
            AuditJournalRepository auditJournalRepository,
            ObjectMapper objectMapper
    ) {
        this.adminCrmProfileRepository = adminCrmProfileRepository;
        this.adminTeamRepository = adminTeamRepository;
        this.accountSyncRepository = accountSyncRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.keycloakAccountClient = keycloakAccountClient;
        this.auditJournalRepository = auditJournalRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AccountCredentials create(CrmProfile actor, NewEmployeeRequest request, String idempotencyKey, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        NewEmployee employee = NewEmployee.of(request);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        UUID commandId = UUID.randomUUID();
        Optional<AdminCrmProfile> replayed = reserve(commandId, actor, CommandOperation.CREATE_EMPLOYEE_ACCOUNT, idempotencyKey, employee);
        if (replayed.isPresent()) {
            return new AccountCredentials(replayed.get(), replayed.get().login(), null);
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
        auditJournalRepository.record(
                AuditAction.ACCOUNT_CREATED, actor.id(), "PROFILE", profileId, employee.displayName(),
                "сотрудник; логин " + employee.login() + "; временный пароль выдан", auditRequestId
        );
        AdminCrmProfile created = complete(commandId, profileId);
        return new AccountCredentials(created, employee.login(), password);
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
            return new AccountCredentials(replayed.get(), replayed.get().login(), null);
        }
        AdminCrmProfile target = lockTarget(profileId);
        String password = TemporaryPassword.generate();
        keycloakAccountClient.resetTemporaryPassword(subject(profileId), password);
        auditJournalRepository.record(
                AuditAction.ACCOUNT_PASSWORD_RESET, actor.id(), "PROFILE", profileId, target.displayName(),
                "временный пароль выдан", auditRequestId
        );
        return new AccountCredentials(complete(commandId, profileId), target.login(), password);
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
        auditJournalRepository.record(
                AuditAction.ACCOUNT_SESSIONS_ENDED, actor.id(), "PROFILE", profileId, target.displayName(),
                "сеансы Keycloak завершены; сеансов CRM завершено: " + crmSessions, auditRequestId
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

    private static UUID requiredProfileId(UUID profileId) {
        if (profileId == null) {
            throw new InteractionValidationException("id", "Укажите профиль");
        }
        return profileId;
    }

    private static String requiredEmail(String value) {
        String email = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (email.length() > EMAIL_LIMIT || !EMAIL.matcher(email).matches()) {
            throw new InteractionValidationException("email", "Укажите почту в виде name@example.ru");
        }
        return email;
    }

    public record NewEmployeeRequest(String displayName, String login, String email, UserRole role, UUID teamId) {
    }

    public record AccountEmailRequest(String email) {
    }

    public record AccountCredentials(AdminCrmProfile profile, String login, String temporaryPassword) {
    }

    private record ProfileCommand(UUID profileId, String email) {
    }

    private record NewEmployee(String displayName, String login, String email, UserRole role, UUID teamId) {
        static NewEmployee of(NewEmployeeRequest request) {
            if (request == null) {
                throw new InteractionValidationException("body", "Заполните данные сотрудника");
            }
            String displayName = request.displayName() == null ? "" : request.displayName().strip();
            if (displayName.isEmpty() || displayName.length() > DISPLAY_NAME_LIMIT) {
                throw new InteractionValidationException("displayName", "ФИО должно содержать от 1 до 200 символов");
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
