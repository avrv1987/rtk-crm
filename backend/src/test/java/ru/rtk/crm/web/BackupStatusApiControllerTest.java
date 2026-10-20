package ru.rtk.crm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rtk.crm.access.AdminCrmProfileAccessDeniedException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.attachment.AttachmentProperties;
import ru.rtk.crm.web.BackupStatusApiController.BackupStatus;
import ru.rtk.crm.web.BackupStatusApiController.BackupWarning;
import ru.rtk.crm.web.BackupStatusApiController.Failure;

class BackupStatusApiControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");

    @TempDir
    Path storage;

    @Test
    void withoutMarksBothTheCopyAndTheRestoreCheckAreReportedMissing() {
        BackupStatus status = status(UserRole.ADMIN);

        assertThat(status.lastBackupAt()).isNull();
        assertThat(status.lastRestoreCheckAt()).isNull();
        assertThat(status.warnings()).containsExactly(BackupWarning.BACKUP_MISSING, BackupWarning.RESTORE_CHECK_MISSING);
    }

    @Test
    void freshCopyAndCheckGiveDateSizeDurationAndNoWarnings() throws IOException {
        mark(BackupStatusApiController.BACKUP_MARK, "status=ok\nat=2026-10-20T02:04:00Z\nok_at=2026-10-20T02:04:00Z\nbytes=1048576\n");
        mark(BackupStatusApiController.RESTORE_CHECK_MARK, "status=ok\nat=2026-10-01T03:30:00Z\nok_at=2026-10-01T03:30:00Z\nseconds=94\n");

        BackupStatus status = status(UserRole.ADMIN);

        assertThat(status.lastBackupAt()).isEqualTo(OffsetDateTime.parse("2026-10-20T02:04:00Z"));
        assertThat(status.lastBackupBytes()).isEqualTo(1_048_576L);
        assertThat(status.lastRestoreCheckAt()).isEqualTo(OffsetDateTime.parse("2026-10-01T03:30:00Z"));
        assertThat(status.lastRestoreCheckSeconds()).isEqualTo(94L);
        assertThat(status.backupFailure()).isNull();
        assertThat(status.warnings()).isEmpty();
    }

    @Test
    void copyOlderThanADayAndCheckOlderThanThirtyFiveDaysAreStale() throws IOException {
        mark(BackupStatusApiController.BACKUP_MARK, "status=ok\nok_at=2026-10-19T08:59:59Z\n");
        mark(BackupStatusApiController.RESTORE_CHECK_MARK, "status=ok\nok_at=2026-09-15T08:59:59Z\n");
        assertThat(status(UserRole.ADMIN).warnings()).containsExactly(BackupWarning.BACKUP_STALE, BackupWarning.RESTORE_CHECK_STALE);

        mark(BackupStatusApiController.BACKUP_MARK, "status=ok\nok_at=2026-10-19T09:00:00Z\n");
        mark(BackupStatusApiController.RESTORE_CHECK_MARK, "status=ok\nok_at=2026-09-15T09:00:00Z\n");
        assertThat(status(UserRole.ADMIN).warnings()).isEmpty();
    }

    @Test
    void failedRunKeepsTheLastSuccessAndAddsTheReason() throws IOException {
        mark(BackupStatusApiController.BACKUP_MARK,
                "status=failed\nat=2026-10-20T02:01:00Z\nmessage=backup.sh exited with code 1\nok_at=2026-10-19T02:03:00Z\nbytes=2048\n");
        mark(BackupStatusApiController.RESTORE_CHECK_MARK, "status=failed\nat=2026-10-20T03:00:00Z\nmessage=" + "x".repeat(400) + "\n");

        BackupStatus status = status(UserRole.ADMIN);

        assertThat(status.lastBackupAt()).isEqualTo(OffsetDateTime.parse("2026-10-19T02:03:00Z"));
        assertThat(status.lastBackupBytes()).isEqualTo(2048L);
        assertThat(status.backupFailure()).isEqualTo(new Failure(OffsetDateTime.parse("2026-10-20T02:01:00Z"), "backup.sh exited with code 1"));
        assertThat(status.restoreCheckFailure().message()).hasSize(300);
        assertThat(status.lastRestoreCheckAt()).isNull();
        assertThat(status.warnings()).containsExactly(
                BackupWarning.BACKUP_STALE, BackupWarning.BACKUP_FAILED, BackupWarning.RESTORE_CHECK_MISSING, BackupWarning.RESTORE_CHECK_FAILED);
    }

    @Test
    void markOfThePreviousFormatIsTheTimeOfTheLastCopyAndGarbageIsIgnored() throws IOException {
        mark(BackupStatusApiController.BACKUP_MARK, "2026-10-20T00:30:00Z\n");
        assertThat(status(UserRole.ADMIN).lastBackupAt()).isEqualTo(OffsetDateTime.parse("2026-10-20T00:30:00Z"));

        mark(BackupStatusApiController.BACKUP_MARK, "не дата");
        mark(BackupStatusApiController.RESTORE_CHECK_MARK, "status=ok\nok_at=вчера\nseconds=много\n");
        BackupStatus status = status(UserRole.ADMIN);
        assertThat(status.lastBackupAt()).isNull();
        assertThat(status.lastRestoreCheckSeconds()).isNull();
        assertThat(status.warnings()).containsExactly(BackupWarning.BACKUP_MISSING, BackupWarning.RESTORE_CHECK_MISSING);
    }

    @Test
    void otherRolesAreRefused() {
        assertThatThrownBy(() -> status(UserRole.LEADER)).isInstanceOf(AdminCrmProfileAccessDeniedException.class);
    }

    private void mark(String name, String content) throws IOException {
        Files.writeString(storage.resolve(name), content);
    }

    private BackupStatus status(UserRole role) {
        CurrentProfileService profiles = mock(CurrentProfileService.class);
        when(profiles.requireActiveProfile(any())).thenReturn(new CrmProfile(UUID.randomUUID(), role, null, 0));
        return new BackupStatusApiController(profiles, new AttachmentProperties(storage, null, 0, null, null, null),
                Clock.fixed(NOW, ZoneOffset.UTC)).status(null);
    }
}
