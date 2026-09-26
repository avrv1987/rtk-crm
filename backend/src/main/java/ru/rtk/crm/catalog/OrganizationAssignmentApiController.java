package ru.rtk.crm.catalog;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/organizations/{id}")
public class OrganizationAssignmentApiController {
    private final CurrentProfileService currentProfileService;
    private final OrganizationAssignmentService organizationAssignmentService;

    public OrganizationAssignmentApiController(
            CurrentProfileService currentProfileService,
            OrganizationAssignmentService organizationAssignmentService
    ) {
        this.currentProfileService = currentProfileService;
        this.organizationAssignmentService = organizationAssignmentService;
    }

    @GetMapping("/assignment-options")
    public List<OrganizationAssignmentCandidate> options(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id
    ) {
        return organizationAssignmentService.options(
                currentProfileService.requireActiveProfile(user),
                parseOrganizationId(id)
        );
    }

    @GetMapping("/assignment-events")
    public List<OrganizationAssignmentEvent> events(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id
    ) {
        return organizationAssignmentService.events(
                currentProfileService.requireActiveProfile(user),
                parseOrganizationId(id)
        );
    }

    @PostMapping("/assignment")
    public ResponseEntity<OrganizationAssignmentResult> assign(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrganizationAssignmentRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(organizationAssignmentService.assign(
                currentProfileService.requireActiveProfile(user),
                parseOrganizationId(id),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        ));
    }

    private UUID parseOrganizationId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор вуза");
        }
    }
}
