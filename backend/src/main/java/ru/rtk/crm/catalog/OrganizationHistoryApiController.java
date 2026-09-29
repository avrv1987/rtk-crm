package ru.rtk.crm.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;

@RestController
@RequestMapping("/api/organizations/{id}/history")
public class OrganizationHistoryApiController {
    private final CurrentProfileService currentProfileService;
    private final OrganizationHistoryService organizationHistoryService;

    public OrganizationHistoryApiController(
            CurrentProfileService currentProfileService,
            OrganizationHistoryService organizationHistoryService
    ) {
        this.currentProfileService = currentProfileService;
        this.organizationHistoryService = organizationHistoryService;
    }

    @GetMapping
    public OrganizationHistoryPage history(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestParam(required = false) List<String> kinds,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return organizationHistoryService.history(
                currentProfileService.requireActiveProfile(user),
                parseOrganizationId(id),
                OrganizationHistoryQuery.from(kinds, from, to, page, size)
        );
    }

    private UUID parseOrganizationId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор вуза");
        }
    }
}
