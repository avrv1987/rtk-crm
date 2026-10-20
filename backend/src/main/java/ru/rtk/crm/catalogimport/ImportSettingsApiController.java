package ru.rtk.crm.catalogimport;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/admin/import-settings")
public class ImportSettingsApiController {
    private final CurrentProfileService currentProfileService;
    private final ImportSettingsService service;

    public ImportSettingsApiController(CurrentProfileService currentProfileService, ImportSettingsService service) {
        this.currentProfileService = currentProfileService;
        this.service = service;
    }

    @GetMapping
    public ImportSettingsService.ImportSettingsView get(@AuthenticationPrincipal OidcUser user) {
        return service.view(currentProfileService.requireActiveProfile(user));
    }

    @PutMapping
    public ImportSettingsService.ImportSettingsView update(
            @AuthenticationPrincipal OidcUser user,
            @RequestBody(required = false) ImportSettingsService.ImportSettingsView body,
            HttpServletRequest request
    ) {
        return service.update(currentProfileService.requireActiveProfile(user), body, RequestId.from(request));
    }
}
