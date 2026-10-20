package ru.rtk.crm.settings;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/admin/settings")
public class SystemSettingsApiController {
    private final CurrentProfileService currentProfileService;
    private final SystemSettingsService service;

    public SystemSettingsApiController(CurrentProfileService currentProfileService, SystemSettingsService service) {
        this.currentProfileService = currentProfileService;
        this.service = service;
    }

    @GetMapping
    public List<SystemSettingView> list(@AuthenticationPrincipal OidcUser user) {
        return service.list(currentProfileService.requireActiveProfile(user));
    }

    @PutMapping("/{key}")
    public List<SystemSettingView> update(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String key,
            @RequestBody(required = false) SystemSettingRequest body,
            HttpServletRequest request
    ) {
        return service.update(currentProfileService.requireActiveProfile(user), key, body, RequestId.from(request));
    }

    @DeleteMapping("/{key}")
    public List<SystemSettingView> reset(@AuthenticationPrincipal OidcUser user, @PathVariable String key, HttpServletRequest request) {
        return service.reset(currentProfileService.requireActiveProfile(user), key, RequestId.from(request));
    }
}
