package ru.rtk.crm.catalog;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/admin/organizations")
public class AdminOrganizationsApiController {
    private final CurrentProfileService currentProfileService;
    private final AdminOrganizationService adminOrganizationService;
    private final OrganizationCatalogService organizationCatalogService;
    private final AdminOrganizationRepository adminOrganizationRepository;

    public AdminOrganizationsApiController(
            CurrentProfileService currentProfileService,
            AdminOrganizationService adminOrganizationService,
            OrganizationCatalogService organizationCatalogService,
            AdminOrganizationRepository adminOrganizationRepository
    ) {
        this.currentProfileService = currentProfileService;
        this.adminOrganizationService = adminOrganizationService;
        this.organizationCatalogService = organizationCatalogService;
        this.adminOrganizationRepository = adminOrganizationRepository;
    }

    @GetMapping
    public AdminOrganizationPage list(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status
    ) {
        return adminOrganizationService.list(
                currentProfileService.requireActiveProfile(user),
                OrganizationQuery.from(page, size, "name,asc", q, false, status)
        );
    }

    @PostMapping
    public ResponseEntity<AdminOrganization> create(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OrganizationDetailsRequest request,
            HttpServletRequest httpRequest
    ) {
        OrganizationCommandResult result = organizationCatalogService.createAsAdmin(
                currentProfileService.requireActiveProfile(user), request, idempotencyKey, RequestId.from(httpRequest)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(current(result.id()));
    }

    @PatchMapping("/{id}")
    public AdminOrganization update(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OrganizationDetailsRequest request,
            HttpServletRequest httpRequest
    ) {
        OrganizationCommandResult result = organizationCatalogService.updateAsAdmin(
                currentProfileService.requireActiveProfile(user),
                parseOrganizationId(id),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        );
        return current(result.id());
    }

    @PostMapping("/{id}/status")
    public AdminOrganization changeStatus(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OrganizationStatusRequest request,
            HttpServletRequest httpRequest
    ) {
        OrganizationCommandResult result = organizationCatalogService.changeStatusAsAdmin(
                currentProfileService.requireActiveProfile(user),
                parseOrganizationId(id),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        );
        return current(result.id());
    }

    private AdminOrganization current(UUID organizationId) {
        return adminOrganizationRepository.findById(organizationId).orElseThrow(OrganizationNotFoundException::new);
    }

    @PatchMapping("/{id}/team")
    public AdminOrganization transferTeam(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AdminOrganizationTeamRequest request,
            HttpServletRequest httpRequest
    ) {
        return adminOrganizationService.transferTeam(
                currentProfileService.requireActiveProfile(user),
                parseOrganizationId(id),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        );
    }

    private UUID parseOrganizationId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidOrganizationQueryException("id", "Некорректный идентификатор вуза");
        }
    }
}
