package ru.rtk.crm.access;

import java.net.URL;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import ru.rtk.crm.access.KeycloakAccountClient.LoginError;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditEntry;
import ru.rtk.crm.audit.AuditJournalRepository;

@Service
public class LoginJournal {
    private static final Map<UserRole, String> ROLE_LABELS = Map.of(
            UserRole.USER, "КАМ",
            UserRole.LEADER, "руководитель",
            UserRole.ADMIN, "администратор",
            UserRole.MANAGEMENT, "руководство",
            UserRole.PARTNER, "представитель вуза"
    );

    private static final Map<String, String> LOGIN_ERRORS = Map.of(
            "invalid_user_credentials", "неверный пароль",
            "user_temporarily_disabled", "вход временно заблокирован после неудачных попыток",
            "user_disabled", "учётная запись отключена",
            "invalid_authenticator_code", "неверный код второго фактора",
            "invalid_totp", "неверный код второго фактора"
    );
    private static final String KEYCLOAK_EVENT = "keycloak:";
    private static final int EVENTS_PAGE = 100;
    private static final int EVENTS_PAGES = 10;
    private static final Logger LOGGER = LoggerFactory.getLogger(LoginJournal.class);

    private final CurrentProfileService currentProfileService;
    private final AdminCrmProfileRepository adminCrmProfileRepository;
    private final AuditJournalRepository auditJournalRepository;
    private final KeycloakAccountClient keycloakAccountClient;

    public LoginJournal(
            CurrentProfileService currentProfileService,
            AdminCrmProfileRepository adminCrmProfileRepository,
            AuditJournalRepository auditJournalRepository,
            KeycloakAccountClient keycloakAccountClient
    ) {
        this.currentProfileService = currentProfileService;
        this.adminCrmProfileRepository = adminCrmProfileRepository;
        this.auditJournalRepository = auditJournalRepository;
        this.keycloakAccountClient = keycloakAccountClient;
    }

    public void loggedIn(OidcUser user, String address, String requestId) {
        UUID profileId = profileId(user);
        try {
            CrmProfile profile = currentProfileService.requireSessionProfile(user);
            adminCrmProfileRepository.recordLogin(profile.id(), OffsetDateTime.now());
            record(AuditAction.LOGIN_SUCCEEDED, profile.id(), user, "роль: " + ROLE_LABELS.get(profile.role()), address, requestId);
        } catch (CrmProfilePendingException exception) {
            record(AuditAction.LOGIN_DENIED, profileId, user, "профиль ожидает активации администратором", address, requestId);
        } catch (CrmProfileNotFoundException exception) {
            String reason = profileId == null
                    ? "нет профиля CRM"
                    : adminCrmProfileRepository.findById(profileId).filter(profile -> !profile.active()).isPresent()
                            ? "профиль CRM заблокирован"
                            : "в Keycloak нет роли CRM";
            record(AuditAction.LOGIN_DENIED, profileId, user, reason, address, requestId);
        }
    }

    public void loggedOut(OidcUser user, String address, String requestId) {
        UUID profileId = profileId(user);
        if (profileId != null) {
            record(AuditAction.LOGOUT, profileId, user, null, address, requestId);
        }
    }

    // Неудачные входы видит только Keycloak: CRM переносит его события LOGIN_ERROR в «Входы и выходы». Чтение идёт
    // со дня последнего перенесённого события, повтор отсекается по идентификатору события Keycloak.
    // ponytail: за один проход читается не больше EVENTS_PAGES страниц новых событий; при штурме в тысячи попыток
    // между проходами самые старые из них в журнал не попадут, но счётчик и блокировка в карточке сотрудника верны.
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${app.account-sync.login-errors-delay:PT5M}")
    public void importLoginErrors() {
        if (!keycloakAccountClient.configured()) {
            return;
        }
        LocalDate from = auditJournalRepository.findLatest(AuditAction.LOGIN_FAILED)
                .map(AuditEntry::occurredAt)
                .orElseGet(() -> OffsetDateTime.now().minusDays(1))
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDate();
        try {
            for (int page = 0; page < EVENTS_PAGES; page++) {
                List<LoginError> errors = keycloakAccountClient.loginErrors(from, page * EVENTS_PAGE, EVENTS_PAGE);
                recordLoginErrors(errors);
                if (errors.size() < EVENTS_PAGE) {
                    return;
                }
            }
        } catch (AccountSyncException exception) {
            LOGGER.warn("Keycloak login errors are not imported: {}", exception.getMessage());
        }
    }

    // Попытки входа под несуществующим логином не переносятся: в поле логина нередко вводят пароль или чужие данные.
    public int recordLoginErrors(List<LoginError> errors) {
        int recorded = 0;
        for (LoginError error : errors) {
            String requestId = KEYCLOAK_EVENT + error.id();
            UUID profileId = adminCrmProfileRepository.findIdBySubject(error.userId()).orElse(null);
            if (profileId == null || auditJournalRepository.recorded(AuditAction.LOGIN_FAILED, requestId)) {
                continue;
            }
            String reason = error.error() == null ? "причина не указана" : LOGIN_ERRORS.getOrDefault(error.error(), error.error());
            auditJournalRepository.record(
                    AuditAction.LOGIN_FAILED, profileId, "PROFILE", profileId, error.username(),
                    error.address() == null ? reason : reason + "; адрес: " + error.address(), requestId,
                    error.occurredAt().atOffset(ZoneOffset.UTC)
            );
            recorded++;
        }
        return recorded;
    }

    private UUID profileId(OidcUser user) {
        URL issuer = user.getIdToken().getIssuer();
        return issuer == null ? null : adminCrmProfileRepository.findIdByIdentity(issuer.toString(), user.getSubject()).orElse(null);
    }

    private void record(AuditAction action, UUID profileId, OidcUser user, String details, String address, String requestId) {
        String withAddress = address == null ? details : details == null ? "адрес: " + address : details + "; адрес: " + address;
        auditJournalRepository.record(
                action, profileId, "PROFILE", profileId, user.getPreferredUsername(), withAddress, requestId
        );
    }
}
