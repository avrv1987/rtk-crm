package ru.rtk.crm.catalogimport;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;

@RestController
@RequestMapping("/api/jobs")
public class CatalogImportJobsApiController {
    private final CurrentProfileService currentProfileService;
    private final CatalogImportService catalogImportService;

    public CatalogImportJobsApiController(
            CurrentProfileService currentProfileService,
            CatalogImportService catalogImportService
    ) {
        this.currentProfileService = currentProfileService;
        this.catalogImportService = catalogImportService;
    }

    @GetMapping("/{id}")
    public CatalogImportJobView get(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return catalogImportService.getJob(currentProfileService.requireActiveProfile(user), parseUuid(id));
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
