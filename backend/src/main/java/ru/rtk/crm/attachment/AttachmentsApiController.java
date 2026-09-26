package ru.rtk.crm.attachment;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;

@RestController
@RequestMapping("/api/attachments")
public class AttachmentsApiController {
    private final CurrentProfileService currentProfileService;
    private final AttachmentService attachmentService;

    public AttachmentsApiController(CurrentProfileService currentProfileService, AttachmentService attachmentService) {
        this.currentProfileService = currentProfileService;
        this.attachmentService = attachmentService;
    }

    @GetMapping("/{id}")
    public Attachment get(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return attachmentService.get(currentProfileService.requireActiveProfile(user), parseRequiredUuid(id));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        AttachmentService.DownloadedAttachment downloaded = attachmentService.download(
                currentProfileService.requireActiveProfile(user),
                parseRequiredUuid(id)
        );
        StreamingResponseBody body = output -> {
            try (var input = downloaded.content()) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.parseMediaType(downloaded.attachment().mediaType()))
                .contentLength(downloaded.attachment().sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(downloaded.attachment().originalName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
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
