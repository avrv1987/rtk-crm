package ru.rtk.crm.agreement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.agreement.AgreementModels.Activity;
import ru.rtk.crm.agreement.AgreementModels.ActivityKind;
import ru.rtk.crm.agreement.AgreementModels.ActivityKindCreateRequest;
import ru.rtk.crm.agreement.AgreementModels.ActivityKindUpdateRequest;
import ru.rtk.crm.agreement.AgreementModels.ActivityRequest;
import ru.rtk.crm.agreement.AgreementModels.Agreement;
import ru.rtk.crm.agreement.AgreementModels.AgreementFile;
import ru.rtk.crm.agreement.AgreementModels.AgreementRequest;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.AgreementSummary;
import ru.rtk.crm.agreement.AgreementModels.Confirmation;
import ru.rtk.crm.agreement.AgreementModels.ConfirmationQuery;
import ru.rtk.crm.agreement.AgreementModels.LinkOptions;
import ru.rtk.crm.agreement.AgreementModels.PeriodReport;
import ru.rtk.crm.agreement.AgreementModels.RegistryQuery;
import ru.rtk.crm.agreement.AgreementModels.RegistryRow;
import ru.rtk.crm.agreement.AgreementModels.Supplement;
import ru.rtk.crm.agreement.AgreementModels.SupplementRequest;
import ru.rtk.crm.agreement.AgreementModels.TransitionRequest;
import ru.rtk.crm.agreement.AgreementModels.WorkAgreements;
import ru.rtk.crm.agreement.AgreementRepository.ConfirmationRow;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.ReportFormat;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api")
public class AgreementsApiController {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CurrentProfileService currentProfileService;
    private final AgreementService agreementService;
    private final AgreementConfirmationArchive archive;
    private final AgreementFileService agreementFileService;
    private final AgreementRegistryService registryService;
    private final AuditJournalRepository auditJournalRepository;

    public AgreementsApiController(
            CurrentProfileService currentProfileService,
            AgreementService agreementService,
            AgreementConfirmationArchive archive,
            AgreementFileService agreementFileService,
            AgreementRegistryService registryService,
            AuditJournalRepository auditJournalRepository
    ) {
        this.currentProfileService = currentProfileService;
        this.agreementService = agreementService;
        this.archive = archive;
        this.agreementFileService = agreementFileService;
        this.registryService = registryService;
        this.auditJournalRepository = auditJournalRepository;
    }

    @GetMapping("/agreement-activity-kinds")
    public List<ActivityKind> kinds(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "false") boolean includeArchived
    ) {
        currentProfileService.requireActiveProfile(user);
        return agreementService.kinds(includeArchived);
    }

    @PostMapping("/admin/agreement-activity-kinds")
    public ResponseEntity<ActivityKind> createKind(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ActivityKindCreateRequest request
    ) {
        ActivityKind created = agreementService.createKind(currentProfileService.requireActiveProfile(user), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/admin/agreement-activity-kinds/{id}")
    public ActivityKind updateKind(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ActivityKindUpdateRequest request
    ) {
        return agreementService.updateKind(currentProfileService.requireActiveProfile(user), uuid(id), request, idempotencyKey);
    }

    @GetMapping("/organizations/{id}/agreements")
    public List<AgreementSummary> list(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return agreementService.list(currentProfileService.requireActiveProfile(user), uuid(id));
    }

    @PostMapping("/organizations/{id}/agreements")
    public ResponseEntity<Agreement> create(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody AgreementRequest request,
            HttpServletRequest httpRequest
    ) {
        Agreement created = agreementService.create(
                currentProfileService.requireActiveProfile(user),
                uuid(id),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/organizations/{id}/agreement-options")
    public LinkOptions options(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return agreementService.linkOptions(currentProfileService.requireActiveProfile(user), uuid(id));
    }

    @GetMapping("/agreements/{id}")
    public Agreement get(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return agreementService.get(currentProfileService.requireActiveProfile(user), uuid(id));
    }

    @PatchMapping("/agreements/{id}")
    public Agreement update(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody AgreementRequest request,
            HttpServletRequest httpRequest
    ) {
        return agreementService.update(
                currentProfileService.requireActiveProfile(user),
                uuid(id),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        );
    }

    @PostMapping("/agreements/{id}/transitions")
    public Agreement transition(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody TransitionRequest request
    ) {
        return agreementService.transition(currentProfileService.requireActiveProfile(user), uuid(id), request, idempotencyKey);
    }

    @PostMapping(path = "/agreements/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AgreementFile> uploadFile(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestParam(required = false) String comment,
            @RequestParam(required = false) String supplementId,
            @RequestPart("file") MultipartFile file
    ) {
        AgreementFile created = agreementFileService.upload(
                currentProfileService.requireActiveProfile(user),
                uuid(id),
                supplementId == null || supplementId.isBlank() ? null : uuid(supplementId),
                comment,
                file,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/agreement-files/{id}/download")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            HttpServletRequest request
    ) throws IOException {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        AgreementFileService.DownloadedFile downloaded = agreementFileService.download(profile, uuid(id));
        try {
            auditJournalRepository.record(AuditAction.AGREEMENT_FILE_DOWNLOADED, profile.id(), "AGREEMENT_FILE",
                    downloaded.file().id(), downloaded.file().originalName(), "версия файла договора с вузом", RequestId.from(request));
        } catch (RuntimeException exception) {
            downloaded.content().close();
            throw exception;
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(downloaded.file().mediaType()))
                .contentLength(downloaded.file().sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(downloaded.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(output -> {
                    try (var input = downloaded.content()) {
                        input.transferTo(output);
                    }
                });
    }

    @PostMapping("/agreements/{id}/supplements")
    public ResponseEntity<Supplement> createSupplement(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody SupplementRequest request
    ) {
        Supplement created = agreementService.createSupplement(
                currentProfileService.requireActiveProfile(user), uuid(id), request, idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/agreement-supplements/{id}")
    public Supplement updateSupplement(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody SupplementRequest request
    ) {
        return agreementService.updateSupplement(currentProfileService.requireActiveProfile(user), uuid(id), request, idempotencyKey);
    }

    @GetMapping("/agreement-registry")
    public List<RegistryRow> registry(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(name = "status", required = false) List<AgreementStatus> statuses,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) UUID managerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresBefore,
            @RequestParam(required = false) Integer reviewLongerThanDays,
            @RequestParam(defaultValue = "false") boolean delayed
    ) {
        return registryService.registry(
                currentProfileService.requireActiveProfile(user),
                new RegistryQuery(statuses, organizationId, managerId, expiresBefore, reviewLongerThanDays, delayed)
        );
    }

    @GetMapping("/agreement-registry/file")
    public ResponseEntity<byte[]> registryFile(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(name = "status", required = false) List<AgreementStatus> statuses,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) UUID managerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresBefore,
            @RequestParam(required = false) Integer reviewLongerThanDays,
            @RequestParam(defaultValue = "false") boolean delayed,
            HttpServletRequest request
    ) throws IOException {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        List<RegistryRow> rows = registryService.registry(
                profile, new RegistryQuery(statuses, organizationId, managerId, expiresBefore, reviewLongerThanDays, delayed)
        );
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        registryService.writeRegistry(rows, OffsetDateTime.now(), output);
        String fileName = AgreementRegistryService.registryFileName(LocalDate.now(AgreementService.ZONE));
        auditJournalRepository.recordReportFileDownload(profile.id(), fileName, "формат XLSX, строк: " + rows.size(),
                RequestId.from(request));
        return file(fileName, ReportFormat.XLSX, output.toByteArray());
    }

    @GetMapping("/interactions/{id}/agreements")
    public WorkAgreements workAgreements(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return agreementService.workAgreements(currentProfileService.requireActiveProfile(user), uuid(id));
    }

    @GetMapping("/reports/agreements-period")
    public PeriodReport periodReport(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer reviewLongerThanDays
    ) {
        return registryService.period(currentProfileService.requireActiveProfile(user), from, to, reviewLongerThanDays);
    }

    @GetMapping("/reports/agreements-period/file")
    public ResponseEntity<byte[]> periodReportFile(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam ReportFormat format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer reviewLongerThanDays,
            HttpServletRequest request
    ) throws IOException {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        PeriodReport report = registryService.period(profile, from, to, reviewLongerThanDays);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        registryService.writePeriod(report, format, output);
        String fileName = AgreementRegistryService.periodFileName(report, format);
        auditJournalRepository.recordReportFileDownload(profile.id(), fileName,
                "формат " + format + ", переходов: " + report.transitions().size(), RequestId.from(request));
        return file(fileName, format, output.toByteArray());
    }

    @PostMapping("/agreements/{id}/activities")
    public ResponseEntity<Activity> createActivity(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ActivityRequest request
    ) {
        Activity created = agreementService.createActivity(
                currentProfileService.requireActiveProfile(user),
                uuid(id),
                request,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/agreement-activities/{id}")
    public Activity updateActivity(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ActivityRequest request
    ) {
        return agreementService.updateActivity(currentProfileService.requireActiveProfile(user), uuid(id), request, idempotencyKey);
    }

    @DeleteMapping("/agreement-activities/{id}")
    public ResponseEntity<Void> deleteActivity(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestParam(required = false) Integer version
    ) {
        agreementService.deleteActivity(currentProfileService.requireActiveProfile(user), uuid(id), version, idempotencyKey);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/agreement-confirmations")
    public List<Confirmation> confirmations(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) UUID agreementId,
            @RequestParam(required = false) UUID kindId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return agreementService.confirmations(
                currentProfileService.requireActiveProfile(user),
                new ConfirmationQuery(organizationId, agreementId, kindId, from, to)
        );
    }

    @GetMapping("/agreement-confirmations/archive")
    public void archive(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) UUID agreementId,
            @RequestParam(required = false) UUID kindId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        String fileName = "Подтверждения_" + (from == null ? "начало" : from) + "_" + (to == null ? "сегодня" : to) + ".zip";
        List<ConfirmationRow> rows = agreementService.archiveRows(
                currentProfileService.requireActiveProfile(user),
                new ConfirmationQuery(organizationId, agreementId, kindId, from, to),
                fileName,
                RequestId.from(request)
        );
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build()
                .toString());
        response.setHeader("X-Content-Type-Options", "nosniff");
        archive.write(rows, response.getOutputStream());
    }

    private static ResponseEntity<byte[]> file(String fileName, ReportFormat format, byte[] content) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(format.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
