package ru.rtk.crm.catalog;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;

@RestController
@RequestMapping("/api/organizations")
public class OrganizationsApiController {
    private final CurrentProfileService currentProfileService;
    private final OrganizationRepository organizationRepository;

    public OrganizationsApiController(
            CurrentProfileService currentProfileService,
            OrganizationRepository organizationRepository
    ) {
        this.currentProfileService = currentProfileService;
        this.organizationRepository = organizationRepository;
    }

    @GetMapping
    public OrganizationPage list(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "updatedAt,desc") String sort,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean requiresAssignment
    ) {
        return organizationRepository.findVisible(
                currentProfileService.requireActiveProfile(user),
                OrganizationQuery.from(page, size, sort, q, requiresAssignment)
        );
    }

    @GetMapping("/{id}")
    public Organization get(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return organizationRepository.findVisibleById(currentProfileService.requireActiveProfile(user), parseId(id))
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
