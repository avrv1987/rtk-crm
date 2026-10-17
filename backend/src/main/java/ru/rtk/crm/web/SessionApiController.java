package ru.rtk.crm.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.access.PartnerModuleDisabledException;
import ru.rtk.crm.access.UserProfileRepository;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.enrolment.EnrolmentAccess;
import ru.rtk.crm.module.ModuleProperties;

@RestController
public class SessionApiController {
    private final CurrentProfileService currentProfileService;
    private final UserProfileRepository userProfileRepository;
    private final EnrolmentAccess enrolmentAccess;
    private final ModuleProperties modules;

    public SessionApiController(
            CurrentProfileService currentProfileService,
            UserProfileRepository userProfileRepository,
            EnrolmentAccess enrolmentAccess,
            ModuleProperties modules
    ) {
        this.currentProfileService = currentProfileService;
        this.userProfileRepository = userProfileRepository;
        this.enrolmentAccess = enrolmentAccess;
        this.modules = modules;
    }

    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal OidcUser user) {
        var profile = currentProfileService.requireSessionProfile(user);
        if (profile.role() == UserRole.PARTNER && !modules.partnerEnabled()) {
            throw new PartnerModuleDisabledException();
        }
        String teamName = profile.teamId() == null
                ? null
                : userProfileRepository.findTeamName(profile.teamId()).orElse(null);
        return new MeResponse(profile.id(), profile.role(), profile.teamId(), teamName, profile.accessRevision(),
                enrolmentAccess.isOperator(profile.id()), userProfileRepository.findDisplayName(profile.id()).orElse(null));
    }

    @GetMapping("/api/config")
    public ConfigResponse config() {
        return new ConfigResponse(new ConfigResponse.Modules(modules.enrolmentEnabled(), modules.partnerEnabled()));
    }

    @GetMapping("/api/csrf")
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }
}
