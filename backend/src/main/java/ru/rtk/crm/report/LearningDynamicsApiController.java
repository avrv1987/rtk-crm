package ru.rtk.crm.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
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
import ru.rtk.crm.report.LearningDynamics.SeriesBy;
import ru.rtk.crm.security.RequestId;

@RestController
public class LearningDynamicsApiController {
    private final CurrentProfileService currentProfileService;
    private final LearningDynamicsService learningDynamicsService;
    private final AuditJournalRepository auditJournalRepository;

    public LearningDynamicsApiController(
            CurrentProfileService currentProfileService,
            LearningDynamicsService learningDynamicsService,
            AuditJournalRepository auditJournalRepository
    ) {
        this.currentProfileService = currentProfileService;
        this.learningDynamicsService = learningDynamicsService;
        this.auditJournalRepository = auditJournalRepository;
    }

    @GetMapping("/api/reports/learning-dynamics")
    public LearningDynamics dynamics(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) SeriesBy seriesBy,
            @RequestParam(required = false) List<UUID> organizationIds,
            @RequestParam(required = false) List<UUID> programIds
    ) {
        return learningDynamicsService.dynamics(currentProfileService.requireActiveProfile(user), from, to, seriesBy,
                organizationIds, programIds);
    }

    @GetMapping("/api/reports/learning-dynamics/file")
    public ResponseEntity<byte[]> file(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam ReportFormat format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> organizationIds,
            @RequestParam(required = false) List<UUID> programIds,
            HttpServletRequest request
    ) throws IOException {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        LearningDynamics dynamics = learningDynamicsService.dynamics(profile, from, to, null, organizationIds, programIds);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        learningDynamicsService.write(dynamics, format, output);
        String fileName = LearningDynamicsService.fileName(dynamics, format);
        auditJournalRepository.recordReportFileDownload(profile.id(), fileName,
                "формат " + format + ", строк: " + dynamics.rows().size(), RequestId.from(request));
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
