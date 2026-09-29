package ru.rtk.crm.agreement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.agreement.AgreementModels.Activity;
import ru.rtk.crm.agreement.AgreementModels.ActivityKind;
import ru.rtk.crm.agreement.AgreementModels.ActivityKindCreateRequest;
import ru.rtk.crm.agreement.AgreementModels.ActivityKindUpdateRequest;
import ru.rtk.crm.agreement.AgreementModels.ActivityRequest;
import ru.rtk.crm.agreement.AgreementModels.Agreement;
import ru.rtk.crm.agreement.AgreementModels.AgreementRequest;
import ru.rtk.crm.agreement.AgreementModels.AgreementSummary;
import ru.rtk.crm.agreement.AgreementModels.Confirmation;
import ru.rtk.crm.agreement.AgreementModels.ConfirmationQuery;
import ru.rtk.crm.agreement.AgreementModels.LinkOptions;
import ru.rtk.crm.agreement.AgreementRepository.ConfirmationRow;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api")
public class AgreementsApiController {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CurrentProfileService currentProfileService;
    private final AgreementService agreementService;
    private final AgreementConfirmationArchive archive;

    public AgreementsApiController(
            CurrentProfileService currentProfileService,
            AgreementService agreementService,
            AgreementConfirmationArchive archive
    ) {
        this.currentProfileService = currentProfileService;
        this.agreementService = agreementService;
        this.archive = archive;
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

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
