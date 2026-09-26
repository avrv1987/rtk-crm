package ru.rtk.crm.work;

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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;

@RestController
@RequestMapping("/api/organizations/{id}/deputies")
public class OrganizationDeputiesApiController {
    private final CurrentProfileService currentProfileService;
    private final OrganizationDeputyService organizationDeputyService;

    public OrganizationDeputiesApiController(
            CurrentProfileService currentProfileService,
            OrganizationDeputyService organizationDeputyService
    ) {
        this.currentProfileService = currentProfileService;
        this.organizationDeputyService = organizationDeputyService;
    }

    @GetMapping
    public List<OrganizationDeputy> list(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return organizationDeputyService.list(currentProfileService.requireActiveProfile(user), parseUuid(id, "id"));
    }

    @PostMapping
    public ResponseEntity<OrganizationDeputy> assign(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) OrganizationDeputyRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(organizationDeputyService.assign(
                currentProfileService.requireActiveProfile(user),
                parseUuid(id, "id"),
                request,
                idempotencyKey
        ));
    }

    @PostMapping("/{deputyId}/end")
    public OrganizationDeputy end(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @PathVariable String deputyId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return organizationDeputyService.end(
                currentProfileService.requireActiveProfile(user),
                parseUuid(id, "id"),
                parseUuid(deputyId, "deputyId"),
                idempotencyKey
        );
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException(field, "Некорректный идентификатор");
        }
    }
}
