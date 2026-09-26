package ru.rtk.crm.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.access.UserProfileRepository;
import ru.rtk.crm.enrolment.EnrolmentAccess;

@RestController
public class SessionApiController {
    private final CurrentProfileService currentProfileService;
    private final UserProfileRepository userProfileRepository;
    private final EnrolmentAccess enrolmentAccess;

    public SessionApiController(
            CurrentProfileService currentProfileService,
            UserProfileRepository userProfileRepository,
            EnrolmentAccess enrolmentAccess
    ) {
        this.currentProfileService = currentProfileService;
        this.userProfileRepository = userProfileRepository;
        this.enrolmentAccess = enrolmentAccess;
    }

    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal OidcUser user) {
        var profile = currentProfileService.requireActiveProfile(user);
        String teamName = profile.teamId() == null
                ? null
                : userProfileRepository.findTeamName(profile.teamId()).orElse(null);
        return new MeResponse(profile.id(), profile.role(), profile.teamId(), teamName, profile.accessRevision(),
                enrolmentAccess.isOperator(profile.id()));
    }

    @GetMapping("/api/csrf")
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }
}
