package ru.rtk.crm.source;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.source.CardSourcesService.InteractionSourceStatus;
import ru.rtk.crm.source.CardSourcesService.LearningSnapshotView;
import ru.rtk.crm.source.CardSourcesService.LearningSnapshotsRefresh;
import ru.rtk.crm.source.CardSourcesService.SourcesRefresh;

@RestController
public class LearningSnapshotsApiController {
    private final CurrentProfileService currentProfileService;
    private final CardSourcesService cardSourcesService;

    public LearningSnapshotsApiController(CurrentProfileService currentProfileService, CardSourcesService cardSourcesService) {
        this.currentProfileService = currentProfileService;
        this.cardSourcesService = cardSourcesService;
    }

    @GetMapping("/api/interactions/{id}/learning-snapshots")
    public List<LearningSnapshotView> snapshots(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return cardSourcesService.snapshots(currentProfileService.requireActiveProfile(user), parseUuid(id));
    }

    @PostMapping("/api/interactions/{id}/learning-snapshots/sync")
    public LearningSnapshotsRefresh refresh(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return cardSourcesService.refreshLearning(currentProfileService.requireActiveProfile(user), parseUuid(id));
    }

    @GetMapping("/api/interactions/{id}/source-status")
    public InteractionSourceStatus sourceStatus(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return cardSourcesService.status(currentProfileService.requireActiveProfile(user), parseUuid(id));
    }

    @PostMapping("/api/interactions/{id}/sources/refresh")
    public SourcesRefresh refreshSources(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return cardSourcesService.refresh(currentProfileService.requireActiveProfile(user), parseUuid(id));
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
