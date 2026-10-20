package ru.rtk.crm.module;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;

@RestController
public class ModuleStatusApiController {
    private final CurrentProfileService currentProfileService;
    private final ModuleStatusService service;

    public ModuleStatusApiController(CurrentProfileService currentProfileService, ModuleStatusService service) {
        this.currentProfileService = currentProfileService;
        this.service = service;
    }

    @GetMapping("/api/admin/modules")
    public List<ModuleStatus> list(@AuthenticationPrincipal OidcUser user) {
        return service.list(currentProfileService.requireActiveProfile(user));
    }
}
