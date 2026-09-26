package ru.rtk.crm.catalog;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;

@RestController
@RequestMapping("/api")
public class CatalogApiController {
    private final CurrentProfileService currentProfileService;
    private final CatalogLookupService catalogLookupService;

    public CatalogApiController(CurrentProfileService currentProfileService, CatalogLookupService catalogLookupService) {
        this.currentProfileService = currentProfileService;
        this.catalogLookupService = catalogLookupService;
    }

    @GetMapping("/programs")
    public CatalogPage listPrograms(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String state
    ) {
        return catalogLookupService.listPrograms(
                currentProfileService.requireActiveProfile(user),
                CatalogQuery.from(page, size),
                CatalogEntryState.parse(state)
        );
    }

    @GetMapping("/products")
    public CatalogPage listProducts(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String state
    ) {
        return catalogLookupService.listProducts(
                currentProfileService.requireActiveProfile(user),
                CatalogQuery.from(page, size),
                CatalogEntryState.parse(state)
        );
    }

    @GetMapping("/directions")
    public CatalogPage listDirections(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String state
    ) {
        return catalogLookupService.listDirections(
                currentProfileService.requireActiveProfile(user),
                CatalogQuery.from(page, size),
                CatalogEntryState.parse(state)
        );
    }
}
