package ru.rtk.crm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rtk.crm.access.AdminCrmProfileAccessDeniedException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.attachment.AttachmentProperties;

class BackupStatusApiControllerTest {
    @TempDir
    Path storage;

    @Test
    void administratorSeesTheMarkLeftByBackupScriptAndNothingWithoutIt() throws IOException {
        BackupStatusApiController controller = controller(UserRole.ADMIN);
        assertThat(controller.status(null).lastBackupAt()).isNull();

        Files.writeString(storage.resolve(BackupStatusApiController.MARKER), "2026-10-18T00:30:00Z\n");
        assertThat(controller.status(null).lastBackupAt()).isEqualTo(OffsetDateTime.parse("2026-10-18T00:30:00Z"));

        Files.writeString(storage.resolve(BackupStatusApiController.MARKER), "не дата");
        assertThat(controller.status(null).lastBackupAt()).isNull();
    }

    @Test
    void otherRolesAreRefused() {
        assertThatThrownBy(() -> controller(UserRole.LEADER).status(null)).isInstanceOf(AdminCrmProfileAccessDeniedException.class);
    }

    private BackupStatusApiController controller(UserRole role) {
        CurrentProfileService profiles = mock(CurrentProfileService.class);
        when(profiles.requireActiveProfile(any())).thenReturn(new CrmProfile(UUID.randomUUID(), role, null, 0));
        return new BackupStatusApiController(profiles, new AttachmentProperties(storage, null, 0, null, null, null));
    }
}
