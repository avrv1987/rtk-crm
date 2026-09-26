package ru.rtk.crm.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionValidationException;

@RestController
public class LearningSnapshotsApiController {
    private final CurrentProfileService currentProfileService;
    private final OrganizationRepository organizationRepository;
    private final SourceRepository repository;
    private final SourceSyncService sourceSyncService;

    public LearningSnapshotsApiController(
            CurrentProfileService currentProfileService,
            OrganizationRepository organizationRepository,
            SourceRepository repository,
            SourceSyncService sourceSyncService
    ) {
        this.currentProfileService = currentProfileService;
        this.organizationRepository = organizationRepository;
        this.repository = repository;
        this.sourceSyncService = sourceSyncService;
    }

    @GetMapping("/api/interactions/{id}/learning-snapshots")
    public List<LearningSnapshotView> snapshots(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return snapshots(currentProfileService.requireActiveProfile(user), parseUuid(id));
    }

    @PostMapping("/api/interactions/{id}/learning-snapshots/sync")
    public LearningSnapshotsRefresh refresh(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return refresh(currentProfileService.requireActiveProfile(user), parseUuid(id));
    }

    LearningSnapshotsRefresh refresh(CrmProfile profile, UUID interactionId) {
        VisibilityScope scope = organizationRepository.visibilityScope(profile).orElseThrow(InteractionNotFoundException::new);
        SyncRunView run = sourceSyncService.refreshLearning(profile, scope, interactionId);
        return new LearningSnapshotsRefresh(run, snapshots(profile, interactionId));
    }

    List<LearningSnapshotView> snapshots(CrmProfile profile, UUID interactionId) {
        VisibilityScope scope = organizationRepository.visibilityScope(profile).orElseThrow(InteractionNotFoundException::new);
        if (!repository.interactionVisible(interactionId, scope)) {
            throw new InteractionNotFoundException();
        }
        return repository.findInteractionSnapshots(interactionId, scope).stream()
                .map(snapshot -> new LearningSnapshotView(
                        snapshot.unit().courseId(),
                        snapshot.unit().courseName(),
                        snapshot.unit().groupId(),
                        snapshot.unit().groupName(),
                        snapshot.unit().participants(),
                        snapshot.unit().teachers(),
                        snapshot.unit().completed(),
                        snapshot.unit().notCompleted(),
                        snapshot.unit().unknown(),
                        snapshot.unit().groupsCount(),
                        snapshot.runStartsOn(),
                        snapshot.runEndsOn(),
                        snapshot.observedAt(),
                        snapshot.changedAt()
                ))
                .toList();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }

    record LearningSnapshotsRefresh(SyncRunView run, List<LearningSnapshotView> snapshots) {
    }

    record LearningSnapshotView(
            long courseId,
            String courseName,
            Long groupId,
            String groupName,
            int participants,
            int teachers,
            Integer completed,
            Integer notCompleted,
            int unknown,
            int groupsCount,
            LocalDate runStartsOn,
            LocalDate runEndsOn,
            OffsetDateTime observedAt,
            OffsetDateTime changedAt
    ) {
    }
}
