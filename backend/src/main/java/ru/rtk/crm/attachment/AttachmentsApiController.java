package ru.rtk.crm.attachment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/attachments")
public class AttachmentsApiController {
    private static final String PREVIEW_POLICY =
            "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; object-src 'self'; frame-ancestors 'self'";

    private final CurrentProfileService currentProfileService;
    private final AttachmentService attachmentService;
    private final AuditJournalRepository auditJournalRepository;

    public AttachmentsApiController(
            CurrentProfileService currentProfileService,
            AttachmentService attachmentService,
            AuditJournalRepository auditJournalRepository
    ) {
        this.currentProfileService = currentProfileService;
        this.attachmentService = attachmentService;
        this.auditJournalRepository = auditJournalRepository;
    }

    @GetMapping("/{id}")
    public Attachment get(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return attachmentService.get(currentProfileService.requireActiveProfile(user), parseRequiredUuid(id));
    }

    @PatchMapping("/{id}")
    public Attachment updateKind(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestBody AttachmentKindUpdateRequest request
    ) {
        return attachmentService.updateKind(currentProfileService.requireActiveProfile(user), parseRequiredUuid(id), request);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            HttpServletRequest request
    ) throws IOException {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        UUID attachmentId = parseRequiredUuid(id);
        AttachmentService.DownloadedAttachment downloaded = audited(
                attachmentService.download(profile, attachmentId), AuditAction.ATTACHMENT_DOWNLOADED, profile, attachmentId, request
        );
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.parseMediaType(downloaded.attachment().mediaType()))
                .contentLength(downloaded.attachment().sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(downloaded.attachment().originalName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(body(downloaded));
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<StreamingResponseBody> preview(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            HttpServletRequest request
    ) throws IOException {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        UUID attachmentId = parseRequiredUuid(id);
        AttachmentService.DownloadedAttachment previewed = audited(
                attachmentService.preview(profile, attachmentId), AuditAction.ATTACHMENT_PREVIEWED, profile, attachmentId, request
        );
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.parseMediaType(previewed.attachment().mediaType()))
                .contentLength(previewed.attachment().sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(previewed.attachment().originalName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", PREVIEW_POLICY)
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body(previewed));
    }

    private AttachmentService.DownloadedAttachment audited(
            AttachmentService.DownloadedAttachment file,
            AuditAction action,
            CrmProfile profile,
            UUID attachmentId,
            HttpServletRequest request
    ) throws IOException {
        try {
            auditJournalRepository.recordAttachmentAccess(action, profile.id(), attachmentId, RequestId.from(request));
        } catch (RuntimeException exception) {
            file.content().close();
            throw exception;
        }
        return file;
    }

    private StreamingResponseBody body(AttachmentService.DownloadedAttachment attachment) {
        return output -> {
            try (var input = attachment.content()) {
                input.transferTo(output);
            }
        };
    }

    private UUID parseRequiredUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException("id", "Укажите идентификатор");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
