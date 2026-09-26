package ru.rtk.crm.catalog;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;

@RestController
@RequestMapping("/api/organizations/{id}/contacts")
public class OrganizationContactsApiController {
    private final CurrentProfileService currentProfileService;
    private final ContactService contactService;

    public OrganizationContactsApiController(CurrentProfileService currentProfileService, ContactService contactService) {
        this.currentProfileService = currentProfileService;
        this.contactService = contactService;
    }

    @GetMapping
    public List<Contact> list(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return contactService.list(currentProfileService.requireActiveProfile(user), parseOrganizationId(id));
    }

    @PostMapping
    public ResponseEntity<Contact> create(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContactCreateRequest request
    ) {
        Contact created = contactService.create(
                currentProfileService.requireActiveProfile(user),
                parseOrganizationId(id),
                request,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{contactId}")
    public Contact update(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @PathVariable String contactId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContactUpdateRequest request
    ) {
        return contactService.update(
                currentProfileService.requireActiveProfile(user),
                parseOrganizationId(id),
                parseContactId(contactId),
                request,
                idempotencyKey
        );
    }

    @GetMapping("/{contactId}/events")
    public List<ContactEvent> events(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @PathVariable String contactId
    ) {
        return contactService.events(
                currentProfileService.requireActiveProfile(user),
                parseOrganizationId(id),
                parseContactId(contactId)
        );
    }

    private UUID parseOrganizationId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор вуза");
        }
    }

    private UUID parseContactId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("contactId", "Некорректный идентификатор контакта");
        }
    }
}
