package ru.rtk.crm.access;

import java.util.UUID;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class CurrentProfileService {
    private static final int DISPLAY_NAME_LIMIT = 200;

    private final UserProfileRepository userProfileRepository;

    public CurrentProfileService(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    public CrmProfile requireActiveProfile(OidcUser user) {
        String issuer = user.getIdToken().getIssuer().toString();
        String subject = user.getSubject();
        return userProfileRepository.findActiveByIdentity(issuer, subject)
                .orElseThrow(() -> userProfileRepository.isPendingActivation(issuer, subject)
                        ? new CrmProfilePendingException()
                        : new CrmProfileNotFoundException());
    }

    public boolean registerPendingProfile(OidcUser user) {
        return userProfileRepository.insertPendingIfAbsent(
                UUID.randomUUID(),
                user.getIdToken().getIssuer().toString(),
                user.getSubject(),
                displayName(user)
        );
    }

    private static String displayName(OidcUser user) {
        String value = firstNonBlank(user.getFullName(), user.getPreferredUsername(), user.getSubject());
        return value.length() > DISPLAY_NAME_LIMIT ? value.substring(0, DISPLAY_NAME_LIMIT) : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        throw new IllegalStateException("OIDC identity has no subject");
    }
}
