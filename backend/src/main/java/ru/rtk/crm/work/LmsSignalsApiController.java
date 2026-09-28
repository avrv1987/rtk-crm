package ru.rtk.crm.work;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalDismissalRequest;
import ru.rtk.crm.work.LmsSignalModels.LmsSignals;

@RestController
public class LmsSignalsApiController {
    private final CurrentProfileService currentProfileService;
    private final LmsSignalService lmsSignalService;

    public LmsSignalsApiController(CurrentProfileService currentProfileService, LmsSignalService lmsSignalService) {
        this.currentProfileService = currentProfileService;
        this.lmsSignalService = lmsSignalService;
    }

    @GetMapping("/api/lms-signals")
    public LmsSignals list(@AuthenticationPrincipal OidcUser user) {
        return lmsSignalService.list(currentProfileService.requireActiveProfile(user));
    }

    @GetMapping("/api/interactions/{id}/lms-signals")
    public LmsSignals forInteraction(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return lmsSignalService.forInteraction(currentProfileService.requireActiveProfile(user), parseUuid(id));
    }

    @PostMapping("/api/interactions/{id}/lms-signals/dismissals")
    public LmsSignals dismiss(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestBody(required = false) LmsSignalDismissalRequest request
    ) {
        return lmsSignalService.dismiss(currentProfileService.requireActiveProfile(user), parseUuid(id), request);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
