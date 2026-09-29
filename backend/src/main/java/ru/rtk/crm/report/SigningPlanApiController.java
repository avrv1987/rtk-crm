package ru.rtk.crm.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.security.RequestId;

@RestController
public class SigningPlanApiController {
    private final CurrentProfileService currentProfileService;
    private final SigningPlanService signingPlanService;
    private final AuditJournalRepository auditJournalRepository;

    public SigningPlanApiController(
            CurrentProfileService currentProfileService,
            SigningPlanService signingPlanService,
            AuditJournalRepository auditJournalRepository
    ) {
        this.currentProfileService = currentProfileService;
        this.signingPlanService = signingPlanService;
        this.auditJournalRepository = auditJournalRepository;
    }

    @GetMapping("/api/reports/signing-plan")
    public SigningPlan plan(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer quarter
    ) {
        return signingPlanService.plan(currentProfileService.requireActiveProfile(user), year, quarter);
    }

    @GetMapping("/api/reports/signing-plan/file")
    public ResponseEntity<byte[]> file(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam ReportFormat format,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer quarter,
            HttpServletRequest request
    ) throws IOException {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        SigningPlan plan = signingPlanService.plan(profile, year, quarter);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        signingPlanService.write(plan, format, output);
        String fileName = SigningPlanService.fileName(plan, format);
        auditJournalRepository.recordReportFileDownload(profile.id(), fileName,
                "формат " + format + ", строк: " + plan.rows().size(), RequestId.from(request));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(format.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(output.toByteArray());
    }
}
