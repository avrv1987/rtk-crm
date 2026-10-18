package ru.rtk.crm.work;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;

@RestController
public class WorkNewsApiController {
    private final CurrentProfileService currentProfileService;
    private final WorkNewsService workNewsService;

    public WorkNewsApiController(CurrentProfileService currentProfileService, WorkNewsService workNewsService) {
        this.currentProfileService = currentProfileService;
        this.workNewsService = workNewsService;
    }

    @GetMapping("/api/work/news")
    public WorkNewsService.News news(@AuthenticationPrincipal OidcUser user, @RequestParam(required = false) String days) {
        return workNewsService.news(currentProfileService.requireActiveProfile(user), days(days));
    }

    @PutMapping("/api/work/news/{eventId}/viewed")
    public ResponseEntity<Void> markViewed(@AuthenticationPrincipal OidcUser user, @PathVariable String eventId) {
        UUID id;
        try {
            id = UUID.fromString(eventId);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("eventId", "Некорректный идентификатор события");
        }
        workNewsService.markViewed(currentProfileService.requireActiveProfile(user), id);
        return ResponseEntity.noContent().build();
    }

    private static Integer days(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException exception) {
            throw new InteractionValidationException("days", "Укажите целое число дней");
        }
    }
}
