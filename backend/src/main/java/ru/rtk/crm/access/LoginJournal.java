package ru.rtk.crm.access;

import java.net.URL;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import ru.rtk.crm.audit.AuditAction;
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

    private final CurrentProfileService currentProfileService;
    private final AdminCrmProfileRepository adminCrmProfileRepository;
    private final AuditJournalRepository auditJournalRepository;

    public LoginJournal(
            CurrentProfileService currentProfileService,
            AdminCrmProfileRepository adminCrmProfileRepository,
            AuditJournalRepository auditJournalRepository
    ) {
        this.currentProfileService = currentProfileService;
        this.adminCrmProfileRepository = adminCrmProfileRepository;
        this.auditJournalRepository = auditJournalRepository;
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
