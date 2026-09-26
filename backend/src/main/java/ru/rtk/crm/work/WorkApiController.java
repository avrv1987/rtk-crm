package ru.rtk.crm.work;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;

@RestController
public class WorkApiController {
    private final CurrentProfileService currentProfileService;
    private final WorkService workService;

    public WorkApiController(CurrentProfileService currentProfileService, WorkService workService) {
        this.currentProfileService = currentProfileService;
        this.workService = workService;
    }

    @GetMapping("/api/work/team-indicators")
    public WorkModels.TeamIndicators teamIndicators(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) String stuckDays
    ) {
        return workService.teamIndicators(currentProfileService.requireActiveProfile(user), days(stuckDays));
    }

    @GetMapping("/api/work/teams-summary")
    public WorkModels.TeamsSummary teamsSummary(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) String stuckDays
    ) {
        return workService.teamsSummary(currentProfileService.requireActiveProfile(user), days(stuckDays));
    }

    @GetMapping("/api/reminders")
    public WorkModels.ReminderDigest reminders(@AuthenticationPrincipal OidcUser user) {
        return workService.reminders(currentProfileService.requireActiveProfile(user));
    }

    @PutMapping("/api/reminders/settings")
    public WorkModels.ReminderSettings saveReminderSettings(
            @AuthenticationPrincipal OidcUser user,
            @RequestBody(required = false) WorkModels.ReminderSettings request
    ) {
        return workService.saveReminderSettings(currentProfileService.requireActiveProfile(user), request);
    }

    private Integer days(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException exception) {
            throw new InteractionValidationException("stuckDays", "Укажите целое число дней");
        }
    }
}
