package ru.rtk.crm.access;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/admin/crm-profiles")
public class AdminCrmProfilesApiController {
    private final CurrentProfileService currentProfileService;
    private final AdminCrmProfileService adminCrmProfileService;

    public AdminCrmProfilesApiController(
            CurrentProfileService currentProfileService,
            AdminCrmProfileService adminCrmProfileService
    ) {
        this.currentProfileService = currentProfileService;
        this.adminCrmProfileService = adminCrmProfileService;
    }

    @GetMapping
    public AdminCrmProfilePage list(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "displayName,asc") String sort,
            @RequestParam(defaultValue = "false") boolean pending
    ) {
        return adminCrmProfileService.list(
                currentProfileService.requireActiveProfile(user),
                AdminCrmProfileQuery.from(page, size, sort, pending)
        );
    }

    @GetMapping("/{id}/events")
    public List<CrmProfileEvent> events(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return adminCrmProfileService.events(currentProfileService.requireActiveProfile(user), parseProfileId(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<AdminCrmProfile> update(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AdminCrmProfileUpdateRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(adminCrmProfileService.update(
                currentProfileService.requireActiveProfile(user),
                parseProfileId(id),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        ));
    }

    private UUID parseProfileId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор профиля");
        }
    }
}
