package ru.rtk.crm.report;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/admin/demand-index/settings")
public class DemandIndexSettingsApiController {
    private final CurrentProfileService currentProfileService;
    private final DemandIndexSettingsService service;

    public DemandIndexSettingsApiController(CurrentProfileService currentProfileService, DemandIndexSettingsService service) {
        this.currentProfileService = currentProfileService;
        this.service = service;
    }

    @GetMapping
    public DemandIndexSettingsView get(@AuthenticationPrincipal OidcUser user) {
        return service.view(currentProfileService.requireActiveProfile(user));
    }

    @PutMapping
    public DemandIndexSettingsView update(
            @AuthenticationPrincipal OidcUser user,
            @RequestBody(required = false) DemandIndexSettingsRequest body,
            HttpServletRequest request
    ) {
        return service.update(currentProfileService.requireActiveProfile(user), body, RequestId.from(request));
    }

    @DeleteMapping
    public ResponseEntity<Void> reset(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        service.reset(currentProfileService.requireActiveProfile(user), RequestId.from(request));
        return ResponseEntity.noContent().build();
    }
}
