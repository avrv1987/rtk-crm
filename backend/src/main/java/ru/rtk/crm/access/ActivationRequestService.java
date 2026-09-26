package ru.rtk.crm.access;

import java.time.OffsetDateTime;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.UserProfileRepository.PendingProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class ActivationRequestService {
    private final UserProfileRepository userProfileRepository;
    private final AuditJournalRepository auditJournalRepository;

    public ActivationRequestService(UserProfileRepository userProfileRepository, AuditJournalRepository auditJournalRepository) {
        this.userProfileRepository = userProfileRepository;
        this.auditJournalRepository = auditJournalRepository;
    }

    @Transactional
    public ActivationRequest request(OidcUser user, String requestId) {
        String issuer = user.getIdToken().getIssuer().toString();
        PendingProfile profile = pending(issuer, user.getSubject());
        if (profile.activationRequestedAt() == null
                && userProfileRepository.markActivationRequested(profile.id(), OffsetDateTime.now())) {
            auditJournalRepository.record(
                    AuditAction.ACTIVATION_REQUESTED, profile.id(), "PROFILE", profile.id(), null, null, requestId
            );
        }
        return new ActivationRequest(pending(issuer, user.getSubject()).activationRequestedAt());
    }

    private PendingProfile pending(String issuer, String subject) {
        return userProfileRepository.findPending(issuer, subject)
                .orElseThrow(() -> new InteractionValidationException("profile", "Профиль CRM не ожидает активации"));
    }

    public record ActivationRequest(OffsetDateTime requestedAt) {
    }
}
