package ru.rtk.crm.access;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import ru.rtk.crm.access.EmployeeAccountService.LoginLockoutStatus;
import ru.rtk.crm.access.EmployeeAccountService.NewEmployeeRequest;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.module.ModuleProperties;
import ru.rtk.crm.report.ReportException;
import ru.rtk.crm.report.ReportProperties;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/admin/crm-profiles")
public class AdminCrmProfilesApiController {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CurrentProfileService currentProfileService;
    private final AdminCrmProfileService adminCrmProfileService;
    private final AccountSyncService accountSyncService;
    private final EmployeeAccountService employeeAccountService;
    private final CrmProfileWorkbook crmProfileWorkbook;
    private final AuditJournalRepository auditJournalRepository;
    private final ReportProperties reportProperties;

    private final ModuleProperties modules;

    public AdminCrmProfilesApiController(
            CurrentProfileService currentProfileService,
            AdminCrmProfileService adminCrmProfileService,
            AccountSyncService accountSyncService,
            EmployeeAccountService employeeAccountService,
            CrmProfileWorkbook crmProfileWorkbook,
            AuditJournalRepository auditJournalRepository,
            ReportProperties reportProperties,
            ModuleProperties modules
    ) {
        this.currentProfileService = currentProfileService;
        this.adminCrmProfileService = adminCrmProfileService;
        this.accountSyncService = accountSyncService;
        this.employeeAccountService = employeeAccountService;
        this.crmProfileWorkbook = crmProfileWorkbook;
        this.auditJournalRepository = auditJournalRepository;
        this.reportProperties = reportProperties;
        this.modules = modules;
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

    @GetMapping("/{id}/organizations")
    public AdminCrmProfileService.OwnedOrganizations organizations(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return adminCrmProfileService.ownedOrganizations(currentProfileService.requireActiveProfile(user), parseProfileId(id));
    }

    @GetMapping("/{id}/login-lockout")
    public LoginLockoutStatus loginLockout(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return employeeAccountService.loginLockout(currentProfileService.requireActiveProfile(user), parseProfileId(id));
    }

    @PostMapping("/{id}/login-lockout-release")
    public LoginLockoutStatus releaseLoginLockout(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            HttpServletRequest httpRequest
    ) {
        return employeeAccountService.releaseLoginLockout(
                currentProfileService.requireActiveProfile(user), parseProfileId(id), RequestId.from(httpRequest)
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
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) AdminCrmProfileQuery.Status status
    ) {
        return adminCrmProfileService.list(
                currentProfileService.requireActiveProfile(user),
                AdminCrmProfileQuery.from(page, size, sort, pending ? AdminCrmProfileQuery.Status.PENDING : status, q, role, teamId)
        );
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) AdminCrmProfileQuery.Status status,
            HttpServletRequest request
    ) {
        CrmProfile actor = currentProfileService.requireActiveProfile(user);
        int maxRows = reportProperties.maxRows();
        List<AdminCrmProfile> profiles = adminCrmProfileService.exportRows(
                actor, AdminCrmProfileQuery.from(0, 1, "displayName,asc", status, q, role, teamId), maxRows + 1
        );
        if (profiles.size() > maxRows) {
            throw new ReportException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "REPORT_ROW_LIMIT", "В выгрузку попадает больше " + maxRows + " профилей; сузьте фильтры"
            );
        }
        byte[] content = crmProfileWorkbook.xlsx(profiles);
        auditJournalRepository.record(
                AuditAction.PROFILES_EXPORTED, actor.id(), "PROFILE", null, null, "строк: " + profiles.size(), RequestId.from(request)
        );
        return ResponseEntity.ok()
                .contentType(XLSX)
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("Профили CRM " + LocalDate.now(ZoneId.of("Europe/Moscow")) + ".xlsx", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
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
        if (Boolean.TRUE.equals(request.enrolmentOperator()) && !modules.enrolmentEnabled()) {
            throw new InteractionValidationException("enrolmentOperator", "Модуль «Слушатели» выключен");
        }
        String requestId = RequestId.from(httpRequest);
        AdminCrmProfile updated = adminCrmProfileService.update(actor, parseProfileId(id), request, idempotencyKey, requestId);
        return ResponseEntity.ok(accountSyncService.syncAfterChange(actor, updated.id(), requestId));
    }

    @PostMapping("/keycloak-roles")
    public AccountSyncService.RoleMigration migrateRoles(@AuthenticationPrincipal OidcUser user, HttpServletRequest httpRequest) {
        return accountSyncService.migrateRoles(currentProfileService.requireActiveProfile(user), RequestId.from(httpRequest));
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
