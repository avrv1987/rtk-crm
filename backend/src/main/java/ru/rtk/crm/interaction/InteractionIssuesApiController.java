package ru.rtk.crm.interaction;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.report.ReportException;
import ru.rtk.crm.report.ReportFormat;
import ru.rtk.crm.report.ReportProperties;
import ru.rtk.crm.security.RequestId;

@RestController
public class InteractionIssuesApiController {
    private final CurrentProfileService currentProfileService;
    private final InteractionIssueService issueService;
    private final InteractionIssueWorkbook issueWorkbook;
    private final AuditJournalRepository auditJournalRepository;
    private final ReportProperties reportProperties;

    public InteractionIssuesApiController(
            CurrentProfileService currentProfileService,
            InteractionIssueService issueService,
            InteractionIssueWorkbook issueWorkbook,
            AuditJournalRepository auditJournalRepository,
            ReportProperties reportProperties
    ) {
        this.currentProfileService = currentProfileService;
        this.issueService = issueService;
        this.issueWorkbook = issueWorkbook;
        this.auditJournalRepository = auditJournalRepository;
        this.reportProperties = reportProperties;
    }

    @GetMapping("/api/interactions/{id}/issues")
    public InteractionIssueList list(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return issueService.list(currentProfileService.requireActiveProfile(user), uuid(id, "id"));
    }

    @PostMapping("/api/interactions/{id}/issues")
    public Interaction create(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody InteractionIssueRequest request
    ) {
        return issueService.create(currentProfileService.requireActiveProfile(user), uuid(id, "id"), request, idempotencyKey);
    }

    @PatchMapping("/api/interactions/{id}/issues/{issueId}")
    public Interaction update(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @PathVariable String issueId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody InteractionIssueRequest request
    ) {
        return issueService.update(
                currentProfileService.requireActiveProfile(user), uuid(id, "id"), uuid(issueId, "issueId"), request, idempotencyKey
        );
    }

    @PostMapping("/api/interactions/{id}/issues/{issueId}/resolution")
    public Interaction resolve(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @PathVariable String issueId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody InteractionIssueResolution request
    ) {
        return issueService.resolve(
                currentProfileService.requireActiveProfile(user), uuid(id, "id"), uuid(issueId, "issueId"), request, idempotencyKey
        );
    }

    @GetMapping("/api/issues")
    public InteractionIssuePage registry(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String responsible,
            @RequestParam(required = false) String organizationId,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return issueService.registry(
                currentProfileService.requireActiveProfile(user),
                InteractionIssueFilter.from(kind, riskLevel, responsible, organizationId, overdue, status),
                page,
                size
        );
    }

    @GetMapping("/api/issues/file")
    public ResponseEntity<byte[]> file(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String responsible,
            @RequestParam(required = false) String organizationId,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(required = false) String status,
            HttpServletRequest request
    ) {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        int maxRows = reportProperties.maxRows();
        List<InteractionIssue> rows = issueService.registryRows(
                profile,
                InteractionIssueFilter.from(kind, riskLevel, responsible, organizationId, overdue, status),
                maxRows + 1
        );
        if (rows.size() > maxRows) {
            throw new ReportException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "REPORT_ROW_LIMIT",
                    "В выгрузку попадает больше " + maxRows + " строк; сузьте отбор"
            );
        }
        LocalDate today = LocalDate.now(InteractionIssueService.ZONE);
        String fileName = "Проблемы и риски " + today + ".xlsx";
        byte[] content = issueWorkbook.xlsx(rows, today);
        auditJournalRepository.recordReportFileDownload(profile.id(), fileName, "формат XLSX, строк: " + rows.size(), RequestId.from(request));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ReportFormat.XLSX.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
    }

    private static UUID uuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException(field, "Укажите идентификатор");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException(field, "Некорректный идентификатор");
        }
    }
}
