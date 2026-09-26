package ru.rtk.crm.catalogimport;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;

@RestController
@RequestMapping("/api/imports")
public class CatalogImportsApiController {
    private final CurrentProfileService currentProfileService;
    private final CatalogImportService catalogImportService;

    public CatalogImportsApiController(
            CurrentProfileService currentProfileService,
            CatalogImportService catalogImportService
    ) {
        this.currentProfileService = currentProfileService;
        this.catalogImportService = catalogImportService;
    }

    @PostMapping(path = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CatalogImportInspectResponse inspect(
            @AuthenticationPrincipal OidcUser user,
            @RequestPart("file") MultipartFile file
    ) {
        return catalogImportService.inspect(currentProfileService.requireActiveProfile(user), file);
    }

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CatalogImportPreviewResponse> preview(
            @AuthenticationPrincipal OidcUser user,
            @RequestPart("file") MultipartFile file,
            @RequestPart("profile") String profile,
            @RequestPart("sheet") String sheet,
            @RequestPart("mapping") CatalogImportMapping mapping
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(catalogImportService.preview(
                currentProfileService.requireActiveProfile(user),
                file,
                parseProfile(profile),
                sheet,
                mapping
        ));
    }

    @GetMapping("/{id}")
    public CatalogImportView get(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return catalogImportService.get(currentProfileService.requireActiveProfile(user), parseUuid(id, "id"));
    }

    @PostMapping("/{id}/apply")
    public ResponseEntity<CatalogImportApplyResponse> apply(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CatalogImportApplyRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(catalogImportService.apply(
                currentProfileService.requireActiveProfile(user),
                parseUuid(id, "id"),
                request,
                idempotencyKey
        ));
    }

    private CatalogImportProfile parseProfile(String value) {
        try {
            return CatalogImportProfile.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InteractionValidationException("profile", "Выберите профиль импорта");
        }
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InteractionValidationException(field, "Некорректный идентификатор");
        }
    }
}
