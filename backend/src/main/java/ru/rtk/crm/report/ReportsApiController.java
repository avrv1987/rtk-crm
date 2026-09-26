package ru.rtk.crm.report;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.CatalogReference;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api")
public class ReportsApiController {
    private final CurrentProfileService currentProfileService;
    private final ReportService reportService;
    private final ReportJobService reportJobService;
    private final AuditJournalRepository auditJournalRepository;
    private final SavedReportService savedReportService;

    public ReportsApiController(
            CurrentProfileService currentProfileService,
            ReportService reportService,
            ReportJobService reportJobService,
            AuditJournalRepository auditJournalRepository,
            SavedReportService savedReportService
    ) {
        this.currentProfileService = currentProfileService;
        this.reportService = reportService;
        this.reportJobService = reportJobService;
        this.auditJournalRepository = auditJournalRepository;
        this.savedReportService = savedReportService;
    }

    @PostMapping("/reports")
    public ResponseEntity<ReportJobCreated> create(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ReportRequest request
    ) {
        ReportJobCreated created = reportJobService.submit(
                currentProfileService.requireActiveProfile(user),
                request,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(created);
    }

    @PostMapping("/reports/preview")
    public ReportPreview preview(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestBody ReportRequest request
    ) {
        return reportService.preview(currentProfileService.requireActiveProfile(user), request, page, size);
    }

    @PostMapping("/statistics")
    public StatisticsResult statistics(@AuthenticationPrincipal OidcUser user, @RequestBody StatisticsRequest request) {
        return reportService.statistics(currentProfileService.requireActiveProfile(user), request);
    }

    @GetMapping("/report-jobs")
    public List<ReportJob> recentJobs(@AuthenticationPrincipal OidcUser user) {
        return reportJobService.recent(currentProfileService.requireActiveProfile(user));
    }

    @GetMapping("/report-jobs/{id}")
    public ReportJob job(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return reportJobService.get(currentProfileService.requireActiveProfile(user), parseUuid(id));
    }

    @GetMapping("/report-jobs/{id}/result")
    public ResponseEntity<Resource> result(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            HttpServletRequest request
    ) {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        UUID jobId = parseUuid(id);
        ReportJobService.ReportResult result = reportJobService.result(profile, jobId);
        auditJournalRepository.recordReportDownload(profile.id(), jobId, RequestId.from(request));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.format().mediaType()))
                .contentLength(result.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(result.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(result.file()));
    }

    @GetMapping("/report-filters/managers")
    public List<ReportManagerOption> managers(@AuthenticationPrincipal OidcUser user) {
        return reportService.managers(currentProfileService.requireActiveProfile(user));
    }

    @GetMapping("/report-filters/vendors")
    public List<CatalogReference> vendors(@AuthenticationPrincipal OidcUser user) {
        currentProfileService.requireActiveProfile(user);
        return reportService.vendors();
    }

    @GetMapping("/report-filters/stages")
    public List<String> stages(@AuthenticationPrincipal OidcUser user) {
        return reportService.stages(currentProfileService.requireActiveProfile(user));
    }

    @GetMapping("/saved-reports")
    public List<SavedReport> savedReports(@AuthenticationPrincipal OidcUser user) {
        return savedReportService.list(currentProfileService.requireActiveProfile(user));
    }

    @PostMapping("/saved-reports")
    public ResponseEntity<SavedReport> createSavedReport(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody SavedReportRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(savedReportService.create(
                currentProfileService.requireActiveProfile(user), request, idempotencyKey
        ));
    }

    @PatchMapping("/saved-reports/{id}")
    public SavedReport updateSavedReport(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody SavedReportRequest request
    ) {
        return savedReportService.update(currentProfileService.requireActiveProfile(user), parseUuid(id), request, idempotencyKey);
    }

    @DeleteMapping("/saved-reports/{id}")
    public ResponseEntity<Void> deleteSavedReport(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestParam(required = false) Integer version,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        savedReportService.delete(currentProfileService.requireActiveProfile(user), parseUuid(id), version, idempotencyKey);
        return ResponseEntity.noContent().build();
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
