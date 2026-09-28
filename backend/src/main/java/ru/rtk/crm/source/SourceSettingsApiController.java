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
@RequestMapping("/api/admin")
public class SourceSettingsApiController {
    private final CurrentProfileService currentProfileService;
    private final SourceSettingsService sourceSettingsService;

    public SourceSettingsApiController(CurrentProfileService currentProfileService, SourceSettingsService sourceSettingsService) {
        this.currentProfileService = currentProfileService;
        this.sourceSettingsService = sourceSettingsService;
    }

    @GetMapping("/source-settings")
    public SourceSettingsView settings(@AuthenticationPrincipal OidcUser user) {
        return sourceSettingsService.view(currentProfileService.requireActiveProfile(user));
    }

    @PutMapping("/source-settings")
    public SourceSettingsView update(
            @AuthenticationPrincipal OidcUser user,
            @RequestBody(required = false) SourceSettingsRequest request,
            HttpServletRequest httpRequest
    ) {
        return sourceSettingsService.update(currentProfileService.requireActiveProfile(user), request, RequestId.from(httpRequest));
    }

    @DeleteMapping("/source-settings")
    public ResponseEntity<Void> reset(@AuthenticationPrincipal OidcUser user, HttpServletRequest httpRequest) {
        sourceSettingsService.reset(currentProfileService.requireActiveProfile(user), RequestId.from(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sources/{source}/check")
    public SourceConnectionCheck check(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String source,
            @RequestBody(required = false) SourceSettingsRequest request
    ) {
        return sourceSettingsService.check(
                currentProfileService.requireActiveProfile(user), SourcesApiController.parseSource(source), request);
    }
}
