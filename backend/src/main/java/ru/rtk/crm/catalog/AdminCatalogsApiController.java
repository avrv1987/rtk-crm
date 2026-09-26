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
@RequestMapping("/api/admin")
public class AdminCatalogsApiController {
    private final CurrentProfileService currentProfileService;
    private final AdminCatalogService adminCatalogService;
    private final VendorContactService vendorContactService;

    public AdminCatalogsApiController(
            CurrentProfileService currentProfileService,
            AdminCatalogService adminCatalogService,
            VendorContactService vendorContactService
    ) {
        this.currentProfileService = currentProfileService;
        this.adminCatalogService = adminCatalogService;
        this.vendorContactService = vendorContactService;
    }

    @GetMapping("/catalogs/{kind}")
    public AdminCatalogPage list(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String state
    ) {
        return adminCatalogService.list(
                currentProfileService.requireActiveProfile(user), CatalogKind.parse(kind), q, state, page, size
        );
    }

    @PostMapping("/catalogs/{kind}")
    public ResponseEntity<AdminCatalogEntry> create(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String kind,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CatalogEntryRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminCatalogService.create(
                currentProfileService.requireActiveProfile(user),
                CatalogKind.parse(kind),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        ));
    }

    @PatchMapping("/catalogs/{kind}/{id}")
    public AdminCatalogEntry update(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String kind,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CatalogEntryRequest request,
            HttpServletRequest httpRequest
    ) {
        return adminCatalogService.update(
                currentProfileService.requireActiveProfile(user),
                CatalogKind.parse(kind),
                parseId(id),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        );
    }

    @GetMapping("/catalogs/vendors/{vendorId}/contacts")
    public VendorContactList vendorContacts(@AuthenticationPrincipal OidcUser user, @PathVariable String vendorId) {
        return vendorContactService.list(currentProfileService.requireActiveProfile(user), parseId(vendorId));
    }

    @PostMapping("/catalogs/vendors/{vendorId}/contacts")
    public ResponseEntity<VendorContact> createVendorContact(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String vendorId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody VendorContactRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(vendorContactService.create(
                currentProfileService.requireActiveProfile(user),
                parseId(vendorId),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        ));
    }

    @PatchMapping("/catalogs/vendors/{vendorId}/contacts/{id}")
    public VendorContact updateVendorContact(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String vendorId,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody VendorContactRequest request,
            HttpServletRequest httpRequest
    ) {
        return vendorContactService.update(
                currentProfileService.requireActiveProfile(user),
                parseId(vendorId),
                parseId(id),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        );
    }

    @GetMapping("/catalog-events")
    public CatalogChangeEventPage events(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String entityType
    ) {
        return adminCatalogService.events(currentProfileService.requireActiveProfile(user), entityType, page, size);
    }

    private UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidOrganizationQueryException("id", "Некорректный идентификатор записи");
        }
    }
}
