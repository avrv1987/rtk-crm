package ru.rtk.crm.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.attachment.AttachmentProperties;

// scripts/backup.sh после успешной копии пишет время в файл тома вложений; в саму копию файл не входит, поэтому после
// восстановления отметки нет до следующей копии, а не стоит время чужой копии.
@RestController
public class BackupStatusApiController {
    public static final String MARKER = ".last-backup";

    private final CurrentProfileService currentProfileService;
    private final Path marker;

    public BackupStatusApiController(CurrentProfileService currentProfileService, AttachmentProperties attachments) {
        this.currentProfileService = currentProfileService;
        this.marker = attachments.storageRoot().resolve(MARKER);
    }

    @GetMapping("/api/admin/backup-status")
    public BackupStatus status(@AuthenticationPrincipal OidcUser user) {
        AdminAuthorization.requireAdmin(currentProfileService.requireActiveProfile(user));
        try {
            return new BackupStatus(OffsetDateTime.parse(Files.readString(marker, StandardCharsets.US_ASCII).strip()));
        } catch (IOException | DateTimeParseException exception) {
            return new BackupStatus(null);
        }
    }

    public record BackupStatus(OffsetDateTime lastBackupAt) {
    }
}
