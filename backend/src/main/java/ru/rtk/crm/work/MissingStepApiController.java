package ru.rtk.crm.work;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.security.RequestId;

@RestController
public class MissingStepApiController {
    private final CurrentProfileService currentProfileService;
    private final MissingStepService service;

    public MissingStepApiController(CurrentProfileService currentProfileService, MissingStepService service) {
        this.currentProfileService = currentProfileService;
        this.service = service;
    }

    @PostMapping("/api/work/control/missing-steps")
    public MissingStepService.MissingStepResult assign(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) MissingStepService.MissingStepRequest body,
            HttpServletRequest request
    ) {
        return service.assign(currentProfileService.requireActiveProfile(user), body, idempotencyKey, RequestId.from(request));
    }
}
