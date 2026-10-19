package ru.rtk.crm.work;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;

@RestController
public class OrganizationPathApiController {
    private final CurrentProfileService currentProfileService;
    private final OrganizationPathService organizationPathService;

    public OrganizationPathApiController(CurrentProfileService currentProfileService, OrganizationPathService organizationPathService) {
        this.currentProfileService = currentProfileService;
        this.organizationPathService = organizationPathService;
    }

    @GetMapping("/api/organizations/{id}/path")
    public OrganizationPathService.Path path(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        UUID organizationId;
        try {
            organizationId = UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор вуза");
        }
        return organizationPathService.path(currentProfileService.requireActiveProfile(user), organizationId);
    }
}
