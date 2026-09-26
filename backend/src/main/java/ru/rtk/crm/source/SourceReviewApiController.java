package ru.rtk.crm.source;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
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
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/source-records")
public class SourceReviewApiController {
    private final CurrentProfileService currentProfileService;
    private final SourceReviewService sourceReviewService;

    public SourceReviewApiController(CurrentProfileService currentProfileService, SourceReviewService sourceReviewService) {
        this.currentProfileService = currentProfileService;
        this.sourceReviewService = sourceReviewService;
    }

    @GetMapping
    public List<PendingSourceRecordView> pending(@AuthenticationPrincipal OidcUser user) {
        return sourceReviewService.pending(currentProfileService.requireActiveProfile(user));
    }

    @PostMapping("/{id}/resolve")
    public SourceRecordApplyResult resolve(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestBody(required = false) SourceRecordResolveRequest request
    ) {
        return sourceReviewService.resolve(currentProfileService.requireActiveProfile(user), SourcesApiController.parseUuid(id),
                request);
    }

    @PostMapping("/{id}/organization")
    public ResponseEntity<SourceOrganizationCreated> createOrganization(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestBody(required = false) SourceOrganizationCreateRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sourceReviewService.createOrganization(
                currentProfileService.requireActiveProfile(user), SourcesApiController.parseUuid(id), request, RequestId.from(httpRequest)));
    }
}
