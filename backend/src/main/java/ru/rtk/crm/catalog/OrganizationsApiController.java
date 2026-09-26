package ru.rtk.crm.catalog;

import java.util.List;
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
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/organizations")
public class OrganizationsApiController {
    private final CurrentProfileService currentProfileService;
    private final OrganizationRepository organizationRepository;
    private final OrganizationCatalogService organizationCatalogService;

    public OrganizationsApiController(
            CurrentProfileService currentProfileService,
            OrganizationRepository organizationRepository,
            OrganizationCatalogService organizationCatalogService
    ) {
        this.currentProfileService = currentProfileService;
        this.organizationRepository = organizationRepository;
        this.organizationCatalogService = organizationCatalogService;
    }

    @GetMapping
    public OrganizationPage list(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "updatedAt,desc") String sort,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean requiresAssignment,
            @RequestParam(required = false) String status
    ) {
        return organizationRepository.findVisible(
                currentProfileService.requireActiveProfile(user),
                OrganizationQuery.from(page, size, sort, q, requiresAssignment, status)
        );
    }

    @GetMapping("/duplicates")
    public List<OrganizationDuplicate> duplicates(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String exceptId
    ) {
        if (name != null && name.length() > 300) {
            throw new InvalidOrganizationQueryException("name", "Название длиннее 300 символов");
        }
        return organizationCatalogService.findDuplicates(
                currentProfileService.requireActiveProfile(user),
                name,
                exceptId == null || exceptId.isBlank() ? null : parseId(exceptId)
        );
    }

    @PostMapping
    public ResponseEntity<Organization> create(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OrganizationDetailsRequest request,
            HttpServletRequest httpRequest
    ) {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        OrganizationCommandResult result = organizationCatalogService.create(
                profile, request, idempotencyKey, RequestId.from(httpRequest)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(visible(profile, result.id()));
    }

    @GetMapping("/{id}")
    public Organization get(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return visible(currentProfileService.requireActiveProfile(user), parseId(id));
    }

    @PatchMapping("/{id}")
    public Organization update(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OrganizationDetailsRequest request,
            HttpServletRequest httpRequest
    ) {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        OrganizationCommandResult result = organizationCatalogService.update(
                profile, parseId(id), request, idempotencyKey, RequestId.from(httpRequest)
        );
        return visible(profile, result.id());
    }

    @PostMapping("/{id}/status")
    public Organization changeStatus(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OrganizationStatusRequest request,
            HttpServletRequest httpRequest
    ) {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        OrganizationCommandResult result = organizationCatalogService.changeStatus(
                profile, parseId(id), request, idempotencyKey, RequestId.from(httpRequest)
        );
        return visible(profile, result.id());
    }

    private Organization visible(CrmProfile profile, UUID organizationId) {
        return organizationRepository.findVisibleById(profile, organizationId)
                .orElseThrow(OrganizationNotFoundException::new);
    }

    private UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidOrganizationQueryException("id", "Некорректный идентификатор вуза");
        }
    }
}
