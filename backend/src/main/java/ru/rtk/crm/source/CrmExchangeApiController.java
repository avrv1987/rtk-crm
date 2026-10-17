package ru.rtk.crm.source;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/admin/crm-exchange")
public class CrmExchangeApiController {
    private final CurrentProfileService currentProfileService;
    private final CrmExchangeService crmExchangeService;

    public CrmExchangeApiController(CurrentProfileService currentProfileService, CrmExchangeService crmExchangeService) {
        this.currentProfileService = currentProfileService;
        this.crmExchangeService = crmExchangeService;
    }

    @GetMapping("/events")
    public CrmEventList events(@AuthenticationPrincipal OidcUser user, @RequestParam(required = false) String status) {
        return crmExchangeService.events(currentProfileService.requireActiveProfile(user), parseStatus(status));
    }

    @PostMapping("/events/retry")
    public CrmEventsRequeued retry(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        return crmExchangeService.requeue(currentProfileService.requireActiveProfile(user), RequestId.from(request));
    }

    @PostMapping("/preview")
    public CrmPreview preview(@AuthenticationPrincipal OidcUser user) {
        return crmExchangeService.preview(currentProfileService.requireActiveProfile(user));
    }

    private static CrmEventStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CrmEventStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("status", "Состояние события должно быть QUEUED, SENT или ERROR");
        }
    }
}
