package ru.rtk.crm.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.attachment.AttachmentContentValidator;
import ru.rtk.crm.attachment.AttachmentRepository;
import ru.rtk.crm.attachment.AttachmentScanOutcome;
import ru.rtk.crm.attachment.AttachmentScanner;
import ru.rtk.crm.attachment.AttachmentService;
import ru.rtk.crm.attachment.AttachmentStorage;
import ru.rtk.crm.attachment.AttachmentUploadInspection;
import ru.rtk.crm.attachment.AttachmentUploadRequest;
import ru.rtk.crm.attachment.AttachmentValidationException;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationStatus;
import ru.rtk.crm.catalog.OrganizationType;

class AttachmentServiceStageEditLockTest {
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID TEAM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID INTERACTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID STAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    @Test
    void rechecksTheStageUnderTheInteractionLockAfterScanning() {
        OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
        InteractionRepository interactionRepository = mock(InteractionRepository.class);
        AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);
        AttachmentContentValidator contentValidator = mock(AttachmentContentValidator.class);
        AttachmentStorage storage = mock(AttachmentStorage.class);
        AttachmentScanner scanner = mock(AttachmentScanner.class);
        CommandIdempotencyRepository commandIdempotencyRepository = mock(CommandIdempotencyRepository.class);
        CrmProfile profile = new CrmProfile(PROFILE_ID, UserRole.USER, TEAM_ID, 0);
        OffsetDateTime now = OffsetDateTime.parse("2026-09-23T12:00:00Z");
        Organization organization = new Organization(
                ORGANIZATION_ID,
                "Университет",
                OrganizationType.UNIVERSITY,
                TEAM_ID,
                PROFILE_ID,
                0,
                now,
                "Анна Смирнова",
                "Команда А",
                false,
                OrganizationStatus.ACTIVE,
                null,
                null,
                null,
                false,
                null,
                null
        );
        InteractionStage stage = new InteractionStage(STAGE_ID, "Этап", 0, false);
        MockMultipartFile file = new MockMultipartFile("file", "proof.pdf", "application/pdf", new byte[]{1});
        AttachmentUploadInspection inspection = new AttachmentUploadInspection(
                "proof.pdf",
                "application/pdf",
                1,
                "0".repeat(64)
        );

        when(interactionRepository.findOrganizationIdById(INTERACTION_ID)).thenReturn(Optional.of(ORGANIZATION_ID));
        when(interactionRepository.findOrganizationIdByIdForUpdate(INTERACTION_ID)).thenReturn(Optional.of(ORGANIZATION_ID));
        when(organizationRepository.findVisibleById(profile, ORGANIZATION_ID)).thenReturn(Optional.of(organization));
        when(interactionRepository.findStages(INTERACTION_ID)).thenReturn(List.of(stage), List.of());
        when(contentValidator.inspect(file)).thenReturn(inspection);
        when(commandIdempotencyRepository.reserve(
                any(),
                eq(PROFILE_ID),
                eq(CommandOperation.UPLOAD_ATTACHMENT),
                anyString(),
                anyString(),
                any()
        )).thenReturn(true);
        when(storage.open(any())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[]{1}));
        when(scanner.scan(any(), anyLong())).thenReturn(AttachmentScanOutcome.CLEAN);

        AttachmentService attachmentService = new AttachmentService(
                organizationRepository,
                interactionRepository,
                attachmentRepository,
                contentValidator,
                storage,
                scanner,
                commandIdempotencyRepository,
                mock(InteractionService.class),
                new ObjectMapper().findAndRegisterModules()
        );

        assertThatThrownBy(() -> attachmentService.upload(
                profile,
                INTERACTION_ID,
                new AttachmentUploadRequest(STAGE_ID, null, null),
                file,
                "attachment-race"
        ))
                .isInstanceOfSatisfying(AttachmentValidationException.class, exception -> {
                    assertThat(exception.field()).isEqualTo("stageId");
                });

        InOrder inOrder = inOrder(storage, scanner, interactionRepository);
        inOrder.verify(storage).store(any(), any(), eq(inspection));
        inOrder.verify(storage).open(any());
        inOrder.verify(scanner).scan(any(), anyLong());
        inOrder.verify(interactionRepository).findOrganizationIdByIdForUpdate(INTERACTION_ID);
        verify(storage).delete(any());
    }
}
