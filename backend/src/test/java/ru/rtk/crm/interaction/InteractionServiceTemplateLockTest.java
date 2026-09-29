package ru.rtk.crm.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.rtk.crm.attachment.AttachmentRepository;
import ru.rtk.crm.catalog.CatalogRepository;
import ru.rtk.crm.catalog.ContactRepository;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationStatus;
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;

class InteractionServiceTemplateLockTest {
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID TEAM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private final CrmProfile profile = new CrmProfile(PROFILE_ID, UserRole.USER, TEAM_ID, 0);

    @Test
    void createsSnapshotFromLockedDefaultTemplate() {
        Fixture fixture = fixture();
        WorkflowTemplate template = template(null);
        when(fixture.workflowTemplateRepository().findDefaultForOrganizationForUpdate(ORGANIZATION_ID)).thenReturn(Optional.of(template));

        Interaction created = fixture.interactionService().create(
                profile,
                new InteractionCreateRequest(ORGANIZATION_ID, "Карточка", null, null, List.of()),
                "locked-default"
        );

        assertThat(created.stages()).extracting(InteractionStage::id)
                .doesNotContainAnyElementsOf(template.stages().stream().map(WorkflowTemplateStage::id).toList());
        verify(fixture.workflowTemplateRepository()).findDefaultForOrganizationForUpdate(ORGANIZATION_ID);
        verify(fixture.workflowTemplateRepository(), never()).findDefault();
    }

    @Test
    void createsSnapshotFromLockedSelectedTemplate() {
        Fixture fixture = fixture();
        UUID templateId = UUID.randomUUID();
        WorkflowTemplate template = template(templateId);
        WorkflowTemplateRepository.WorkflowTemplateRow row = new WorkflowTemplateRepository.WorkflowTemplateRow(
                templateId,
                TEAM_ID,
                template.name(),
                false,
                0
        );
        when(fixture.workflowTemplateRepository().findByIdForUpdate(templateId)).thenReturn(Optional.of(row));
        when(fixture.workflowTemplateRepository().toTemplate(row)).thenReturn(template);

        Interaction created = fixture.interactionService().create(
                profile,
                new InteractionCreateRequest(
                        ORGANIZATION_ID,
                        "Карточка по шаблону",
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        templateId
                ),
                "locked-selected"
        );

        assertThat(created.transitions()).singleElement().satisfies(transition -> {
            assertThat(transition.fromStageId()).isEqualTo(created.stages().getFirst().id());
            assertThat(transition.toStageId()).isEqualTo(created.stages().get(1).id());
        });
        verify(fixture.workflowTemplateRepository()).findByIdForUpdate(templateId);
        verify(fixture.workflowTemplateRepository(), never()).findById(templateId);
    }

    private Fixture fixture() {
        OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
        ContactRepository contactRepository = mock(ContactRepository.class);
        CatalogRepository catalogRepository = mock(CatalogRepository.class);
        InteractionRepository interactionRepository = mock(InteractionRepository.class);
        WorkflowTemplateRepository workflowTemplateRepository = mock(WorkflowTemplateRepository.class);
        AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);
        CommandIdempotencyRepository commandIdempotencyRepository = mock(CommandIdempotencyRepository.class);
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
        AtomicReference<UUID> currentStageId = new AtomicReference<>();
        AtomicReference<List<InteractionStage>> stages = new AtomicReference<>();
        AtomicReference<List<InteractionStageTransition>> transitions = new AtomicReference<>();

        when(organizationRepository.findVisibleById(profile, ORGANIZATION_ID)).thenReturn(Optional.of(organization));
        when(contactRepository.findIdsByOrganizationId(ORGANIZATION_ID, List.of())).thenReturn(Set.of());
        when(catalogRepository.findActiveProductIds(List.of())).thenReturn(Set.of());
        when(commandIdempotencyRepository.reserve(
                any(),
                eq(PROFILE_ID),
                eq(CommandOperation.CREATE_INTERACTION),
                anyString(),
                anyString(),
                any()
        )).thenReturn(true);
        doAnswer(invocation -> {
            currentStageId.set(invocation.getArgument(3));
            return null;
        }).when(interactionRepository).insertInteraction(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
        doAnswer(invocation -> {
            stages.set(List.copyOf(invocation.getArgument(1)));
            return null;
        }).when(interactionRepository).insertStages(any(), any());
        doAnswer(invocation -> {
            transitions.set(List.copyOf(invocation.getArgument(1)));
            return null;
        }).when(interactionRepository).insertTransitions(any(), any());
        when(interactionRepository.findById(any())).thenAnswer(invocation -> Optional.of(
                new InteractionRepository.InteractionRow(
                        invocation.getArgument(0),
                        ORGANIZATION_ID,
                        "Карточка",
                        currentStageId.get(),
                        "Старт",
                        null,
                        null,
                        null,
                        null,
                        0,
                        PROFILE_ID,
                        now,
                        now,
                        new InteractionMarks(InteractionWorkStatus.ACTIVE, null, null, null, 0, 0, null, null, null),
                        false
                )
        ));
        when(interactionRepository.findStages(any())).thenAnswer(invocation -> stages.get());
        when(interactionRepository.findTransitions(any())).thenAnswer(invocation -> transitions.get());
        when(interactionRepository.findContactIds(any())).thenReturn(List.of());
        when(interactionRepository.findProductIds(any())).thenReturn(List.of());
        when(interactionRepository.findProductAgreements(any())).thenReturn(List.of());
        when(attachmentRepository.findByInteractionId(any())).thenReturn(List.of());

        return new Fixture(
                new InteractionService(
                        organizationRepository,
                        contactRepository,
                        catalogRepository,
                        interactionRepository,
                        workflowTemplateRepository,
                        attachmentRepository,
                        commandIdempotencyRepository,
                        new ObjectMapper().findAndRegisterModules()
                ),
                workflowTemplateRepository
        );
    }

    private WorkflowTemplate template(UUID templateId) {
        UUID startId = UUID.randomUUID();
        UUID finishId = UUID.randomUUID();
        return new WorkflowTemplate(
                templateId == null ? UUID.randomUUID() : templateId,
                templateId == null ? null : TEAM_ID,
                "Процесс",
                List.of(
                        new WorkflowTemplateStage(startId, "Старт", 0, false),
                        new WorkflowTemplateStage(finishId, "Финиш", 1, false)
                ),
                List.of(new WorkflowTemplateTransition(startId, finishId, false)),
                false,
                0
        );
    }

    private record Fixture(InteractionService interactionService, WorkflowTemplateRepository workflowTemplateRepository) {
    }
}
