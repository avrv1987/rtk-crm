package ru.rtk.crm.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.attachment.AttachmentProperties;

// Отметки пишут scripts/backup.sh, backup-scheduled.sh и restore-check.sh в том вложений строками «ключ=значение».
// В саму копию они не входят, поэтому после восстановления отметок нет до следующего запуска, а не стоит время чужой копии.
@RestController
public class BackupStatusApiController {
    public static final String BACKUP_MARK = ".last-backup";
    public static final String RESTORE_CHECK_MARK = ".last-restore-check";
    // Решение владельца: копия каждую ночь, проверка восстановления раз в месяц; пороги — «больше суток» и «больше 35 дней».
    static final Duration BACKUP_MAX_AGE = Duration.ofHours(24);
    static final Duration RESTORE_CHECK_MAX_AGE = Duration.ofDays(35);
    private static final int MESSAGE_LIMIT = 300;

    private final CurrentProfileService currentProfileService;
    private final Path storageRoot;
    private final Clock clock;

    @Autowired
    public BackupStatusApiController(CurrentProfileService currentProfileService, AttachmentProperties attachments) {
        this(currentProfileService, attachments, Clock.systemUTC());
    }

    BackupStatusApiController(CurrentProfileService currentProfileService, AttachmentProperties attachments, Clock clock) {
        this.currentProfileService = currentProfileService;
        this.storageRoot = attachments.storageRoot();
        this.clock = clock;
    }

    @GetMapping("/api/admin/backup-status")
    public BackupStatus status(@AuthenticationPrincipal OidcUser user) {
        AdminAuthorization.requireAdmin(currentProfileService.requireActiveProfile(user));
        Map<String, String> backup = read(BACKUP_MARK);
        Map<String, String> check = read(RESTORE_CHECK_MARK);
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime lastBackupAt = time(backup.get("ok_at"));
        OffsetDateTime lastCheckAt = time(check.get("ok_at"));
        Failure backupFailure = failure(backup);
        Failure checkFailure = failure(check);
        List<BackupWarning> warnings = new ArrayList<>();
        if (lastBackupAt == null) {
            warnings.add(BackupWarning.BACKUP_MISSING);
        } else if (lastBackupAt.plus(BACKUP_MAX_AGE).isBefore(now)) {
            warnings.add(BackupWarning.BACKUP_STALE);
        }
        if (backupFailure != null) {
            warnings.add(BackupWarning.BACKUP_FAILED);
        }
        if (lastCheckAt == null) {
            warnings.add(BackupWarning.RESTORE_CHECK_MISSING);
        } else if (lastCheckAt.plus(RESTORE_CHECK_MAX_AGE).isBefore(now)) {
            warnings.add(BackupWarning.RESTORE_CHECK_STALE);
        }
        if (checkFailure != null) {
            warnings.add(BackupWarning.RESTORE_CHECK_FAILED);
        }
        return new BackupStatus(
                lastBackupAt,
                lastBackupAt == null ? null : number(backup.get("bytes")),
                backupFailure,
                lastCheckAt,
                lastCheckAt == null ? null : number(check.get("seconds")),
                checkFailure,
                warnings
        );
    }

    // Отметка прежнего формата — одна строка со временем успешной копии.
    private Map<String, String> read(String name) {
        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(storageRoot.resolve(name), StandardCharsets.UTF_8)) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    values.put(line.substring(0, separator).strip(), line.substring(separator + 1).strip());
                } else if (!line.isBlank() && values.isEmpty()) {
                    values.put("ok_at", line.strip());
                }
            }
        } catch (IOException exception) {
            return Map.of();
        }
        return values;
    }

    private static Failure failure(Map<String, String> values) {
        if (!"failed".equals(values.get("status"))) {
            return null;
        }
        String message = values.getOrDefault("message", "");
        return new Failure(time(values.get("at")), message.length() > MESSAGE_LIMIT ? message.substring(0, MESSAGE_LIMIT) : message);
    }

    private static OffsetDateTime time(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static Long number(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public enum BackupWarning {
        BACKUP_MISSING,
        BACKUP_STALE,
        BACKUP_FAILED,
        RESTORE_CHECK_MISSING,
        RESTORE_CHECK_STALE,
        RESTORE_CHECK_FAILED
    }

    public record Failure(OffsetDateTime at, String message) {
    }

    public record BackupStatus(
            OffsetDateTime lastBackupAt,
            Long lastBackupBytes,
            Failure backupFailure,
            OffsetDateTime lastRestoreCheckAt,
            Long lastRestoreCheckSeconds,
            Failure restoreCheckFailure,
            List<BackupWarning> warnings
    ) {
    }
}
