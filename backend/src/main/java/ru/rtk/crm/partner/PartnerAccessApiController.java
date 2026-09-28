package ru.rtk.crm.partner;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.partner.PartnerModels.PartnerAccess;
import ru.rtk.crm.partner.PartnerModels.PartnerAccessGranted;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/organizations/{id}")
public class PartnerAccessApiController {
    private final CurrentProfileService currentProfileService;
    private final PartnerAccessService partnerAccessService;

    public PartnerAccessApiController(CurrentProfileService currentProfileService, PartnerAccessService partnerAccessService) {
        this.currentProfileService = currentProfileService;
        this.partnerAccessService = partnerAccessService;
    }

    @GetMapping("/partner-access")
    public List<PartnerAccess> list(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return partnerAccessService.list(currentProfileService.requireActiveProfile(user), parseId(id, "id"));
    }

    @PostMapping("/contacts/{contactId}/partner-access")
    public ResponseEntity<PartnerAccessGranted> open(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @PathVariable String contactId,
            HttpServletRequest request
    ) {
        PartnerAccessGranted granted = partnerAccessService.open(
                currentProfileService.requireActiveProfile(user),
                parseId(id, "id"),
                parseId(contactId, "contactId"),
                RequestId.from(request)
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(granted);
    }

    @DeleteMapping("/contacts/{contactId}/partner-access")
    public PartnerAccess close(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @PathVariable String contactId,
            HttpServletRequest request
    ) {
        return partnerAccessService.close(
                currentProfileService.requireActiveProfile(user),
                parseId(id, "id"),
                parseId(contactId, "contactId"),
                RequestId.from(request)
        );
    }

    private UUID parseId(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException(field, "Некорректный идентификатор");
        }
    }
}
