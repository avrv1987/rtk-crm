package ru.rtk.crm.access;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.EmployeeAccountService.AccountCredentials;
import ru.rtk.crm.access.EmployeeAccountService.AccountEmailRequest;
import ru.rtk.crm.access.EmployeeAccountService.NewEmployeeRequest;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/admin/crm-profiles")
public class AdminCrmProfilesApiController {
    private final CurrentProfileService currentProfileService;
    private final AdminCrmProfileService adminCrmProfileService;
    private final AccountSyncService accountSyncService;
    private final EmployeeAccountService employeeAccountService;

    public AdminCrmProfilesApiController(
            CurrentProfileService currentProfileService,
            AdminCrmProfileService adminCrmProfileService,
            AccountSyncService accountSyncService,
            EmployeeAccountService employeeAccountService
    ) {
        this.currentProfileService = currentProfileService;
        this.adminCrmProfileService = adminCrmProfileService;
        this.accountSyncService = accountSyncService;
        this.employeeAccountService = employeeAccountService;
    }

    @PostMapping
    public ResponseEntity<AccountCredentials> create(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody NewEmployeeRequest request,
            HttpServletRequest httpRequest
    ) {
        AccountCredentials created = employeeAccountService.create(
                currentProfileService.requireActiveProfile(user), request, idempotencyKey, RequestId.from(httpRequest)
        );
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store").body(created);
    }

    @PostMapping("/{id}/account-password-reset")
    public ResponseEntity<AccountCredentials> resetPassword(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest
    ) {
        AccountCredentials credentials = employeeAccountService.resetPassword(
                currentProfileService.requireActiveProfile(user), parseProfileId(id), idempotencyKey, RequestId.from(httpRequest)
        );
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(credentials);
    }

    @PostMapping("/{id}/account-logout")
    public AdminCrmProfile endSessions(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest
    ) {
        return employeeAccountService.endSessions(
                currentProfileService.requireActiveProfile(user), parseProfileId(id), idempotencyKey, RequestId.from(httpRequest)
        );
    }

    @PostMapping("/{id}/account-second-factor-reset")
    public AdminCrmProfile resetSecondFactor(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest
    ) {
        return employeeAccountService.resetSecondFactor(
                currentProfileService.requireActiveProfile(user), parseProfileId(id), idempotencyKey, RequestId.from(httpRequest)
        );
    }

    @PutMapping("/{id}/account-email")
    public AdminCrmProfile changeEmail(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AccountEmailRequest request,
            HttpServletRequest httpRequest
    ) {
        return employeeAccountService.changeEmail(
                currentProfileService.requireActiveProfile(user), parseProfileId(id), request, idempotencyKey,
                RequestId.from(httpRequest)
        );
    }

    @GetMapping
    public AdminCrmProfilePage list(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "displayName,asc") String sort,
            @RequestParam(defaultValue = "false") boolean pending,
            @RequestParam(required = false) String q
    ) {
        return adminCrmProfileService.list(
                currentProfileService.requireActiveProfile(user),
                AdminCrmProfileQuery.from(page, size, sort, pending, q)
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
        CrmProfile actor = currentProfileService.requireActiveProfile(user);
        String requestId = RequestId.from(httpRequest);
        AdminCrmProfile updated = adminCrmProfileService.update(actor, parseProfileId(id), request, idempotencyKey, requestId);
        return ResponseEntity.ok(accountSyncService.syncAfterChange(actor, updated.id(), requestId));
    }

    @PostMapping("/{id}/account-sync")
    public AdminCrmProfile syncAccount(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            HttpServletRequest httpRequest
    ) {
        return accountSyncService.sync(
                currentProfileService.requireActiveProfile(user),
                parseProfileId(id),
                RequestId.from(httpRequest)
        );
    }

    private UUID parseProfileId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор профиля");
        }
    }
}
