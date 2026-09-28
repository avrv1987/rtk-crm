package ru.rtk.crm.partner;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import ru.rtk.crm.partner.PartnerCabinetService.PartnerDownload;
import ru.rtk.crm.partner.PartnerModels.PartnerCabinet;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/partner")
public class PartnerCabinetApiController {
    private final CurrentProfileService currentProfileService;
    private final PartnerCabinetService partnerCabinetService;

    public PartnerCabinetApiController(CurrentProfileService currentProfileService, PartnerCabinetService partnerCabinetService) {
        this.currentProfileService = currentProfileService;
        this.partnerCabinetService = partnerCabinetService;
    }

    @GetMapping("/cabinet")
    public ResponseEntity<PartnerCabinet> cabinet(@AuthenticationPrincipal OidcUser user) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(partnerCabinetService.cabinet(currentProfileService.requirePartnerProfile(user)));
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            HttpServletRequest request
    ) {
        PartnerDownload download = partnerCabinetService.download(
                currentProfileService.requirePartnerProfile(user), parseId(id), RequestId.from(request)
        );
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.document().mediaType()))
                .contentLength(download.document().sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.document().originalName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(output -> {
                    try (var input = download.content()) {
                        input.transferTo(output);
                    }
                });
    }

    private UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
