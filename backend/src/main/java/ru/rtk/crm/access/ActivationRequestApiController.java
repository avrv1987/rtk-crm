package ru.rtk.crm.access;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.security.RequestId;

@RestController
public class ActivationRequestApiController {
    private final ActivationRequestService activationRequestService;

    public ActivationRequestApiController(ActivationRequestService activationRequestService) {
        this.activationRequestService = activationRequestService;
    }

    @PostMapping("/api/me/activation-request")
    public ActivationRequestService.ActivationRequest request(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        return activationRequestService.request(user, RequestId.from(request));
    }
}
