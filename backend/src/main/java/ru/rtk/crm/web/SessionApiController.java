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
import ru.rtk.crm.catalog.OrganizationCatalogRepository;
import ru.rtk.crm.enrolment.EnrolmentAccess;
import ru.rtk.crm.mcp.McpProperties;
import ru.rtk.crm.module.ModuleProperties;
import ru.rtk.crm.settings.SystemSettings;

@RestController
public class SessionApiController {
    private final CurrentProfileService currentProfileService;
    private final UserProfileRepository userProfileRepository;
    private final EnrolmentAccess enrolmentAccess;
    private final ModuleProperties modules;
    private final McpProperties mcp;
    private final OrganizationCatalogRepository organizationCatalogRepository;
    private final SystemSettings systemSettings;

    public SessionApiController(
            CurrentProfileService currentProfileService,
            UserProfileRepository userProfileRepository,
            EnrolmentAccess enrolmentAccess,
            ModuleProperties modules,
            McpProperties mcp,
            OrganizationCatalogRepository organizationCatalogRepository,
            SystemSettings systemSettings
    ) {
        this.currentProfileService = currentProfileService;
        this.userProfileRepository = userProfileRepository;
        this.enrolmentAccess = enrolmentAccess;
        this.modules = modules;
        this.mcp = mcp;
        this.organizationCatalogRepository = organizationCatalogRepository;
        this.systemSettings = systemSettings;
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
        boolean operator = enrolmentAccess.isOperator(profile.id());
        // КАМ служебной команды «Открытый набор» с флагом — менеджер учебного процесса без своих вузов: разделы КАМ ему не нужны.
        boolean enrolmentOnly = operator && profile.role() == UserRole.USER && organizationCatalogRepository.serviceTeam(profile.teamId());
        return new MeResponse(profile.id(), profile.role(), profile.teamId(), teamName, profile.accessRevision(),
                operator, userProfileRepository.findDisplayName(profile.id()).orElse(null), enrolmentOnly);
    }

    @GetMapping("/api/config")
    public ConfigResponse config() {
        return new ConfigResponse(new ConfigResponse.Modules(modules.enrolmentEnabled(), modules.partnerEnabled(), modules.maxEnabled(),
                mcp.enabled(),
                modules.mailEnabled()), systemSettings.landing());
    }

    @GetMapping("/api/csrf")
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }
}
