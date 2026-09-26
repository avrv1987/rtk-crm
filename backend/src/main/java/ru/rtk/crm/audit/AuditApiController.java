package ru.rtk.crm.audit;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/admin/audit-events")
public class AuditApiController {
    private static final int EXPORT_LIMIT = 10_000;
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private final CurrentProfileService currentProfileService;
    private final AuditJournalRepository auditJournalRepository;
    private final AuditExportWriter auditExportWriter;

    public AuditApiController(
            CurrentProfileService currentProfileService,
            AuditJournalRepository auditJournalRepository,
            AuditExportWriter auditExportWriter
    ) {
        this.currentProfileService = currentProfileService;
        this.auditJournalRepository = auditJournalRepository;
        this.auditExportWriter = auditExportWriter;
    }

    @GetMapping
    public AuditEntryPage list(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String object,
            @RequestParam(required = false) AuditCategory category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        AdminAuthorization.requireAdmin(currentProfileService.requireActiveProfile(user));
        return auditJournalRepository.findPage(AuditQuery.of(from, to, actor, object, category, page, size));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "XLSX") AuditExportFormat format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String object,
            @RequestParam(required = false) AuditCategory category,
            HttpServletRequest request
    ) {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        AdminAuthorization.requireAdmin(profile);
        AuditQuery query = AuditQuery.of(from, to, actor, object, category, 0, 1).firstRows(EXPORT_LIMIT);
        List<AuditEntry> entries = auditJournalRepository.findRows(query);
        boolean csv = format == AuditExportFormat.CSV;
        byte[] content = csv ? auditExportWriter.csv(entries) : auditExportWriter.xlsx(entries);
        auditJournalRepository.record(
                AuditAction.JOURNAL_EXPORTED, profile.id(), "JOURNAL", null, null,
                "формат " + format + ", строк: " + entries.size(), RequestId.from(request)
        );
        String fileName = "Журнал администратора " + LocalDate.now(AuditQuery.ZONE) + (csv ? ".csv" : ".xlsx");
        return ResponseEntity.ok()
                .contentType(csv ? CSV : XLSX)
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
    }

    public enum AuditExportFormat {
        XLSX,
        CSV
    }
}
