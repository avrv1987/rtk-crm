package ru.rtk.crm.source;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/admin/labor-market")
public class LaborMarketApiController {
    private final CurrentProfileService currentProfileService;
    private final LaborMarketSettingsService settingsService;
    private final LaborMarketService service;

    public LaborMarketApiController(
            CurrentProfileService currentProfileService,
            LaborMarketSettingsService settingsService,
            LaborMarketService service
    ) {
        this.currentProfileService = currentProfileService;
        this.settingsService = settingsService;
        this.service = service;
    }

    @GetMapping("/settings")
    public LaborMarketSettingsView settings(@AuthenticationPrincipal OidcUser user) {
        return settingsService.view(currentProfileService.requireActiveProfile(user));
    }

    @PutMapping("/settings")
    public LaborMarketSettingsView updateSettings(
            @AuthenticationPrincipal OidcUser user,
            @RequestBody(required = false) LaborMarketSettingsRequest request,
            HttpServletRequest httpRequest
    ) {
        return settingsService.update(currentProfileService.requireActiveProfile(user), request, RequestId.from(httpRequest));
    }

    @DeleteMapping("/settings")
    public ResponseEntity<Void> resetSettings(@AuthenticationPrincipal OidcUser user, HttpServletRequest httpRequest) {
        settingsService.reset(currentProfileService.requireActiveProfile(user), RequestId.from(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/check")
    public SourceConnectionCheck check(
            @AuthenticationPrincipal OidcUser user,
            @RequestBody(required = false) LaborMarketSettingsRequest request
    ) {
        return settingsService.check(currentProfileService.requireActiveProfile(user), request);
    }

    @GetMapping("/mappings")
    public LaborMarketMappings mappings(@AuthenticationPrincipal OidcUser user) {
        return service.mappings(currentProfileService.requireActiveProfile(user));
    }

    @PutMapping("/mappings/{directionId}")
    public LaborMarketDirection updateMapping(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String directionId,
            @RequestBody(required = false) LaborMarketMappingRequest request,
            HttpServletRequest httpRequest
    ) {
        return service.update(currentProfileService.requireActiveProfile(user), SourcesApiController.parseUuid(directionId), request,
                RequestId.from(httpRequest));
    }
}
