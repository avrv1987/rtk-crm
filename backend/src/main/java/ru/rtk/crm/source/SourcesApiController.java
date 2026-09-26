package ru.rtk.crm.source;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;

@RestController
@RequestMapping("/api/admin")
public class SourcesApiController {
    private final CurrentProfileService currentProfileService;
    private final SourceSyncService sourceSyncService;
    private final SourceMappingService sourceMappingService;
    private final SourceReviewService sourceReviewService;

    public SourcesApiController(
            CurrentProfileService currentProfileService,
            SourceSyncService sourceSyncService,
            SourceMappingService sourceMappingService,
            SourceReviewService sourceReviewService
    ) {
        this.currentProfileService = currentProfileService;
        this.sourceSyncService = sourceSyncService;
        this.sourceMappingService = sourceMappingService;
        this.sourceReviewService = sourceReviewService;
    }

    @GetMapping("/sources")
    public List<SourceView> sources(@AuthenticationPrincipal OidcUser user) {
        return sourceSyncService.sources(currentProfileService.requireActiveProfile(user));
    }

    @PostMapping("/sources/{source}/sync")
    public ResponseEntity<SyncRunCreated> sync(@AuthenticationPrincipal OidcUser user, @PathVariable String source) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(sourceSyncService.start(currentProfileService.requireActiveProfile(user), parseSource(source)));
    }

    @GetMapping("/sources/{source}/runs")
    public List<SyncRunView> runs(@AuthenticationPrincipal OidcUser user, @PathVariable String source) {
        return sourceSyncService.runs(currentProfileService.requireActiveProfile(user), parseSource(source));
    }

    @GetMapping("/source-records")
    public List<SourceRecordView> problemRecords(@AuthenticationPrincipal OidcUser user) {
        return sourceSyncService.problemRecords(currentProfileService.requireActiveProfile(user));
    }

    @GetMapping("/source-mapping-options")
    public SourceMappingOptions mappingOptions(@AuthenticationPrincipal OidcUser user) {
        return sourceSyncService.mappingOptions(currentProfileService.requireActiveProfile(user));
    }

    @PostMapping("/source-records/{id}/apply")
    public SourceRecordApplyResult apply(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestBody(required = false) SourceRecordApplyRequest request
    ) {
        return sourceSyncService.apply(currentProfileService.requireActiveProfile(user), parseUuid(id), request);
    }

    @PostMapping("/source-records/{id}/organization")
    public ResponseEntity<SourceOrganizationCreated> createOrganization(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestBody(required = false) SourceOrganizationCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sourceReviewService.createOrganization(
                currentProfileService.requireActiveProfile(user), parseUuid(id), request));
    }

    @GetMapping("/source-mappings")
    public List<SourceMappingView> mappings(@AuthenticationPrincipal OidcUser user) {
        return sourceMappingService.mappings(currentProfileService.requireActiveProfile(user));
    }

    @PutMapping("/source-mappings/{id}")
    public SourceMappingView updateMapping(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestBody(required = false) SourceMappingRequest request
    ) {
        return sourceMappingService.update(currentProfileService.requireActiveProfile(user), parseUuid(id), request);
    }

    @DeleteMapping("/source-mappings/{id}")
    public ResponseEntity<Void> removeMapping(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestParam(required = false) Integer version
    ) {
        sourceMappingService.remove(currentProfileService.requireActiveProfile(user), parseUuid(id), version);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/source-mappings/{id}/runs")
    public ResponseEntity<SourceMappingView> addRun(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestBody(required = false) SourceMappingRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sourceMappingService.addRun(currentProfileService.requireActiveProfile(user), parseUuid(id), request));
    }

    @DeleteMapping("/source-mappings/{id}/snapshot")
    public ResponseEntity<Void> deleteSnapshot(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        sourceMappingService.deleteSnapshot(currentProfileService.requireActiveProfile(user), parseUuid(id));
        return ResponseEntity.noContent().build();
    }

    private static SourceCode parseSource(String value) {
        try {
            return SourceCode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("source", "Источник должен быть WEBSITE или MOODLE");
        }
    }

    static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
