package ru.rtk.crm.privacy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/admin")
public class PersonalDataApiController {
    private final CurrentProfileService currentProfileService;
    private final PersonalDataService personalDataService;
    private final RetentionService retentionService;

    public PersonalDataApiController(
            CurrentProfileService currentProfileService,
            PersonalDataService personalDataService,
            RetentionService retentionService
    ) {
        this.currentProfileService = currentProfileService;
        this.personalDataService = personalDataService;
        this.retentionService = retentionService;
    }

    @PostMapping("/personal-data/search")
    public SubjectSearchResult search(
            @AuthenticationPrincipal OidcUser user,
            @RequestBody(required = false) SubjectQuery query,
            HttpServletRequest request
    ) {
        return personalDataService.search(currentProfileService.requireActiveProfile(user), query, RequestId.from(request));
    }

    @PostMapping("/personal-data/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "JSON") SubjectExportFormat format,
            @RequestBody(required = false) SubjectQuery query,
            HttpServletRequest request
    ) {
        PersonalDataService.SubjectExport export = personalDataService.export(
                currentProfileService.requireActiveProfile(user), query, format, RequestId.from(request)
        );
        boolean pdf = export.format() == SubjectExportFormat.PDF;
        String fileName = "Сведения о субъекте ПДн " + LocalDate.now() + (pdf ? ".pdf" : ".json");
        return ResponseEntity.ok()
                .contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_JSON)
                .contentLength(export.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(export.content());
    }

    @PatchMapping("/personal-data/contacts/{id}")
    public SubjectContact rectify(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ContactRectification rectification,
            HttpServletRequest request
    ) {
        return personalDataService.rectify(
                currentProfileService.requireActiveProfile(user), parseId(id), rectification, idempotencyKey, RequestId.from(request)
        );
    }

    @PostMapping("/personal-data/contacts/{id}/restriction")
    public SubjectContact restrict(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ContactRestriction restriction,
            HttpServletRequest request
    ) {
        return personalDataService.restrict(
                currentProfileService.requireActiveProfile(user), parseId(id), restriction, idempotencyKey, RequestId.from(request)
        );
    }

    @PostMapping("/personal-data/anonymization")
    public AnonymizationResult anonymize(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) AnonymizationRequest anonymization,
            HttpServletRequest request
    ) {
        return personalDataService.anonymize(
                currentProfileService.requireActiveProfile(user), anonymization, idempotencyKey, RequestId.from(request)
        );
    }

    @GetMapping("/retention")
    public RetentionPolicy retention(@AuthenticationPrincipal OidcUser user) {
        return retentionService.policy(currentProfileService.requireActiveProfile(user));
    }

    @PostMapping("/retention/run")
    public RetentionRun applyRetention(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        return retentionService.run(currentProfileService.requireActiveProfile(user), RequestId.from(request));
    }

    private UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
