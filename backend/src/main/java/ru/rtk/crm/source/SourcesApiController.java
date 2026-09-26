package ru.rtk.crm.source;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;

@RestController
@RequestMapping("/api/admin")
public class SourcesApiController {
    private final CurrentProfileService currentProfileService;
    private final SourceSyncService sourceSyncService;

    public SourcesApiController(CurrentProfileService currentProfileService, SourceSyncService sourceSyncService) {
        this.currentProfileService = currentProfileService;
        this.sourceSyncService = sourceSyncService;
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

    private static SourceCode parseSource(String value) {
        try {
            return SourceCode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("source", "Источник должен быть WEBSITE или MOODLE");
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
