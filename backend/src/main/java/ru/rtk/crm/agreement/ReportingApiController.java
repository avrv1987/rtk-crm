package ru.rtk.crm.agreement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.agreement.ReportingModels.ActivityList;
import ru.rtk.crm.agreement.ReportingModels.ActivityListCreateRequest;
import ru.rtk.crm.agreement.ReportingModels.ActivityListTransitionRequest;
import ru.rtk.crm.agreement.ReportingModels.ActivityListUpdateRequest;
import ru.rtk.crm.agreement.ReportingModels.ActivityListsView;
import ru.rtk.crm.agreement.ReportingModels.AgreementReport;
import ru.rtk.crm.agreement.ReportingModels.DeadlinesRequest;
import ru.rtk.crm.agreement.ReportingModels.PartnerDecisionRequest;
import ru.rtk.crm.agreement.ReportingModels.Rate;
import ru.rtk.crm.agreement.ReportingModels.RateRequest;
import ru.rtk.crm.agreement.ReportingModels.ReferenceCreateRequest;
import ru.rtk.crm.agreement.ReportingModels.ReferenceItem;
import ru.rtk.crm.agreement.ReportingModels.ReferenceUpdateRequest;
import ru.rtk.crm.agreement.ReportingModels.ReportingReminders;
import ru.rtk.crm.agreement.ReportingModels.SettingsRequest;
import ru.rtk.crm.agreement.ReportingModels.SettingsView;
import ru.rtk.crm.agreement.ReportingModels.TemplateRequest;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.ReportFormat;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api")
public class ReportingApiController {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CurrentProfileService currentProfileService;
    private final ReportingSettingsService settingsService;
    private final ActivityListService listService;
    private final AgreementReportService reportService;
    private final AuditJournalRepository auditJournalRepository;

    public ReportingApiController(
            CurrentProfileService currentProfileService,
            ReportingSettingsService settingsService,
            ActivityListService listService,
            AgreementReportService reportService,
            AuditJournalRepository auditJournalRepository
    ) {
        this.currentProfileService = currentProfileService;
        this.settingsService = settingsService;
        this.listService = listService;
        this.reportService = reportService;
        this.auditJournalRepository = auditJournalRepository;
    }

    // ---------- настройки и справочники ----------

    @GetMapping("/agreement-reporting/settings")
    public SettingsView settings(@AuthenticationPrincipal OidcUser user) {
        return settingsService.view(currentProfileService.requireActiveProfile(user));
    }

    @PutMapping("/admin/agreement-reporting/settings")
    public SettingsView saveSettings(@AuthenticationPrincipal OidcUser user, @Valid @RequestBody(required = false) SettingsRequest body,
                                     HttpServletRequest request) {
        return settingsService.saveSettings(currentProfileService.requireActiveProfile(user), body, RequestId.from(request));
    }

    @PutMapping("/admin/agreement-reporting/template")
    public SettingsView saveTemplate(@AuthenticationPrincipal OidcUser user, @Valid @RequestBody(required = false) TemplateRequest body,
                                     HttpServletRequest request) {
        return settingsService.saveTemplate(currentProfileService.requireActiveProfile(user), body, RequestId.from(request));
    }

    @DeleteMapping("/admin/agreement-reporting/template")
    public SettingsView resetTemplate(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        return settingsService.resetTemplate(currentProfileService.requireActiveProfile(user), RequestId.from(request));
    }

    @PutMapping("/admin/agreement-reporting/deadlines")
    public SettingsView saveDeadlines(@AuthenticationPrincipal OidcUser user, @Valid @RequestBody(required = false) DeadlinesRequest body,
                                      HttpServletRequest request) {
        return settingsService.saveDeadlines(currentProfileService.requireActiveProfile(user), body, RequestId.from(request));
    }

    @GetMapping("/agreement-reporting/references")
    public List<ReferenceItem> references(@AuthenticationPrincipal OidcUser user,
                                          @RequestParam(defaultValue = "false") boolean includeArchived) {
        return settingsService.references(currentProfileService.requireActiveProfile(user), includeArchived);
    }

    @PostMapping("/admin/agreement-reporting/references")
    public ResponseEntity<ReferenceItem> createReference(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ReferenceCreateRequest body,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(settingsService.createReference(
                currentProfileService.requireActiveProfile(user), body, idempotencyKey, RequestId.from(request)));
    }

    @PatchMapping("/admin/agreement-reporting/references/{id}")
    public ReferenceItem updateReference(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ReferenceUpdateRequest body,
            HttpServletRequest request
    ) {
        return settingsService.updateReference(currentProfileService.requireActiveProfile(user), uuid(id), body, idempotencyKey,
                RequestId.from(request));
    }

    @GetMapping("/agreement-reporting/rates")
    public List<Rate> rates(@AuthenticationPrincipal OidcUser user) {
        return settingsService.rates(currentProfileService.requireActiveProfile(user));
    }

    @PostMapping("/admin/agreement-reporting/rates")
    public ResponseEntity<Rate> createRate(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody RateRequest body,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(settingsService.createRate(
                currentProfileService.requireActiveProfile(user), body, idempotencyKey, RequestId.from(request)));
    }

    @PatchMapping("/admin/agreement-reporting/rates/{id}")
    public Rate updateRate(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody RateRequest body,
            HttpServletRequest request
    ) {
        return settingsService.updateRate(currentProfileService.requireActiveProfile(user), uuid(id), body, idempotencyKey,
                RequestId.from(request));
    }

    @DeleteMapping("/admin/agreement-reporting/rates/{id}")
    public ResponseEntity<Void> deleteRate(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestParam(required = false) Integer version,
            HttpServletRequest request
    ) {
        settingsService.deleteRate(currentProfileService.requireActiveProfile(user), uuid(id), version, idempotencyKey,
                RequestId.from(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/agreement-reporting/reminders")
    public ReportingReminders reminders(@AuthenticationPrincipal OidcUser user) {
        return settingsService.reminders(currentProfileService.requireActiveProfile(user));
    }

    // ---------- перечни ----------

    @GetMapping("/organizations/{id}/activity-lists")
    public ActivityListsView lists(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return listService.view(currentProfileService.requireActiveProfile(user), uuid(id));
    }

    @PostMapping("/organizations/{id}/activity-lists")
    public ResponseEntity<ActivityList> createList(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ActivityListCreateRequest body,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(listService.create(currentProfileService.requireActiveProfile(user),
                uuid(id), body, idempotencyKey, RequestId.from(request)));
    }

    @GetMapping("/activity-lists/{id}")
    public ActivityList list(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return listService.get(currentProfileService.requireActiveProfile(user), uuid(id));
    }

    @PatchMapping("/activity-lists/{id}")
    public ActivityList updateList(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ActivityListUpdateRequest body
    ) {
        return listService.update(currentProfileService.requireActiveProfile(user), uuid(id), body, idempotencyKey);
    }

    @PostMapping("/activity-lists/{id}/transitions")
    public ActivityList transitionList(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ActivityListTransitionRequest body,
            HttpServletRequest request
    ) {
        return listService.transition(currentProfileService.requireActiveProfile(user), uuid(id), body, idempotencyKey,
                RequestId.from(request));
    }

    @GetMapping("/partner/activity-lists")
    public ResponseEntity<List<ActivityList>> partnerLists(@AuthenticationPrincipal OidcUser user) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(listService.partnerLists(currentProfileService.requirePartnerProfile(user)));
    }

    @PostMapping("/partner/activity-lists/{id}/decision")
    public ActivityList partnerDecision(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody PartnerDecisionRequest body,
            HttpServletRequest request
    ) {
        return listService.partnerDecide(currentProfileService.requirePartnerProfile(user), uuid(id), body, idempotencyKey,
                RequestId.from(request));
    }

    // ---------- отчёт ----------

    @GetMapping("/reports/agreement-reporting")
    public AgreementReport report(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID organizationId
    ) {
        return reportService.report(currentProfileService.requireActiveProfile(user), from, to, organizationId);
    }

    @GetMapping("/reports/agreement-reporting/file")
    public ResponseEntity<byte[]> reportFile(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam ReportFormat format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID organizationId,
            HttpServletRequest request
    ) throws IOException {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        if (format != ReportFormat.XLSX && format != ReportFormat.PDF && format != ReportFormat.JSON) {
            throw new InteractionValidationException("format", "Отчёт по соглашениям выгружается в XLSX, PDF или JSON");
        }
        AgreementReport report = reportService.report(profile, from, to, organizationId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        reportService.write(report, format, output);
        String fileName = AgreementReportService.fileName(report, format);
        auditJournalRepository.recordReportFileDownload(profile.id(), fileName,
                "формат " + format + ", строк: " + report.rows().size(), RequestId.from(request));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(format.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(output.toByteArray());
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
