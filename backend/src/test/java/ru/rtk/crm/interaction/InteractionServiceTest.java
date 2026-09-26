package ru.rtk.crm.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.attachment.Attachment;
import ru.rtk.crm.attachment.AttachmentContentValidator;
import ru.rtk.crm.attachment.AttachmentRepository;
import ru.rtk.crm.attachment.AttachmentScanOutcome;
import ru.rtk.crm.attachment.AttachmentScanner;
import ru.rtk.crm.attachment.AttachmentService;
import ru.rtk.crm.attachment.AttachmentStorage;
import ru.rtk.crm.attachment.AttachmentUploadInspection;
import ru.rtk.crm.catalog.Contact;
import ru.rtk.crm.catalog.ContactCreateRequest;
import ru.rtk.crm.catalog.ContactRepository;
import ru.rtk.crm.catalog.ContactService;
import ru.rtk.crm.catalog.CatalogLookupService;
import ru.rtk.crm.catalog.CatalogQuery;
import ru.rtk.crm.catalog.CatalogRepository;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:interaction;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        ContactRepository.class,
        ContactService.class,
        CatalogRepository.class,
        CatalogLookupService.class,
        InteractionRepository.class,
        WorkflowTemplateRepository.class,
        AttachmentRepository.class,
        CommandIdempotencyRepository.class,
        InteractionService.class,
        InteractionServiceTest.JsonConfiguration.class
})
class InteractionServiceTest {
    private static final UUID TEAM_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MANAGER_A = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID MANAGER_B = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID LEADER_A = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private static final UUID LEADER_B = UUID.fromString("00000000-0000-0000-0000-000000000014");
    private static final UUID ORGANIZATION_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ORGANIZATION_B = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID ORGANIZATION_A2 = UUID.fromString("00000000-0000-0000-0000-000000000103");

    private final CrmProfile profileA = new CrmProfile(MANAGER_A, UserRole.USER, TEAM_A, 0);
    private final CrmProfile profileB = new CrmProfile(MANAGER_B, UserRole.USER, TEAM_B, 0);
    private final CrmProfile leaderA = new CrmProfile(LEADER_A, UserRole.LEADER, TEAM_A, 0);
    private final CrmProfile leaderB = new CrmProfile(LEADER_B, UserRole.LEADER, TEAM_B, 0);

    @Autowired
    private InteractionService interactionService;

    @Autowired
    private ContactService contactService;

    @Autowired
    private CatalogLookupService catalogLookupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private InteractionRepository interactionRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private CommandIdempotencyRepository commandIdempotencyRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        createSchema();
        jdbcTemplate.update("DELETE FROM attachments");
        jdbcTemplate.update("DELETE FROM interaction_events");
        jdbcTemplate.update("DELETE FROM command_idempotency_records");
        jdbcTemplate.update("DELETE FROM interaction_contacts");
        jdbcTemplate.update("DELETE FROM product_agreements");
        jdbcTemplate.update("DELETE FROM interaction_stage_transitions");
        jdbcTemplate.update("DELETE FROM interaction_stages");
        jdbcTemplate.update("DELETE FROM interactions");
        jdbcTemplate.update("DELETE FROM workflow_template_transitions");
        jdbcTemplate.update("DELETE FROM workflow_template_stages");
        jdbcTemplate.update("DELETE FROM workflow_templates");
        jdbcTemplate.update("DELETE FROM contacts");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM vendors");
        jdbcTemplate.update("DELETE FROM programs");
        jdbcTemplate.update("DELETE FROM directions");
        jdbcTemplate.update("DELETE FROM organizations");
        jdbcTemplate.update("DELETE FROM crm_user_profiles");
        insertProfile(MANAGER_A, "Анна Смирнова");
        insertProfile(MANAGER_B, "Борис Орлов");
        insertProfile(LEADER_A, "Вера Ковалёва");
        insertProfile(LEADER_B, "Глеб Соколов");
        insertOrganization(ORGANIZATION_A, "Университет А", TEAM_A, MANAGER_A);
        insertOrganization(ORGANIZATION_B, "Университет Б", TEAM_B, MANAGER_B);
        insertDefaultWorkflowTemplate();
    }

    @Test
    void createsPrivateWorkflowSnapshotWithNextActionAndSameOrganizationContacts() {
        Contact contact = contactService.create(
                profileA,
                ORGANIZATION_A,
                new ContactCreateRequest("Ирина Петрова", "Куратор", "irina@example.test", null),
                "contact-a"
        );

        Interaction created = interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Запуск программы",
                        "Согласовать встречу",
                        OffsetDateTime.parse("2026-10-01T10:00:00+03:00"),
                        List.of(contact.id())
                ),
                "create-a"
        );

        assertThat(created.title()).isEqualTo("Запуск программы");
        assertThat(created.version()).isZero();
        assertThat(created.nextAction()).isEqualTo("Согласовать встречу");
        assertThat(created.nextActionAt()).isEqualTo(OffsetDateTime.parse("2026-10-01T10:00:00+03:00"));
        assertThat(created.contactIds()).containsExactly(contact.id());
        assertThat(created.stages()).hasSize(13);
        assertThat(created.stages()).extracting(InteractionStage::id).doesNotHaveDuplicates();
        assertThat(created.currentStageName()).isEqualTo("Поиск контакта");
        assertThat(created.allowedTransitions()).singleElement()
                .extracting(InteractionTransitionOption::stageName)
                .isEqualTo("Уточнение актуальности");

        List<InteractionEvent> events = interactionService.events(profileA, created.id());
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(InteractionEventType.CREATED);
            assertThat(event.stageId()).isEqualTo(created.currentStageId());
            assertThat(event.stageNameSnapshot()).isEqualTo("Поиск контакта");
            assertThat(event.toStageNameSnapshot()).isEqualTo("Поиск контакта");
            assertThat(event.actorProfileId()).isEqualTo(MANAGER_A);
            assertThat(event.ownerManagerIdSnapshot()).isEqualTo(MANAGER_A);
            assertThat(event.commandId()).isNotNull();
            assertThat(event.occurredAt()).isNotNull();
        });
        assertThatThrownBy(() -> interactionService.get(profileB, created.id()))
                .isInstanceOf(InteractionNotFoundException.class);
    }

    @Test
    void clonesOnlyTheSelectedTeamTemplateIntoAnIndependentInteractionSnapshot() {
        WorkflowTemplateFixture selected = insertWorkflowTemplate(TEAM_A, "Процесс команды А");
        WorkflowTemplateFixture foreign = insertWorkflowTemplate(TEAM_B, "Процесс команды Б");

        Interaction created = interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Карточка по шаблону",
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        selected.id()
                ),
                "create-selected-template"
        );

        assertThat(created.stages()).extracting(InteractionStage::name)
                .containsExactly("Старт команды", "Финиш команды");
        assertThat(created.stages()).extracting(InteractionStage::id)
                .doesNotContainAnyElementsOf(List.of(selected.startStageId(), selected.finishStageId()));
        assertThat(created.transitions()).singleElement().satisfies(transition -> {
            assertThat(transition.fromStageId()).isEqualTo(created.stages().getFirst().id());
            assertThat(transition.toStageId()).isEqualTo(created.stages().get(1).id());
        });
        assertThatThrownBy(() -> interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Недоступный шаблон",
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        foreign.id()
                ),
                "create-foreign-template"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("templateId");
        });
    }

    @Test
    void persistsNextActionAndContactsForSubsequentReadAndList() {
        Contact contact = contactService.create(
                profileA,
                ORGANIZATION_A,
                new ContactCreateRequest("Мария Иванова", "Координатор", null, null),
                "contact-persist"
        );
        OffsetDateTime dueAt = OffsetDateTime.parse("2000-01-01T10:00:00+03:00");
        Interaction scheduled = interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Следующее действие",
                        "Провести встречу",
                        dueAt,
                        List.of(contact.id())
                ),
                "create-persist-scheduled"
        );
        Interaction withoutNextAction = interactionService.create(
                profileA,
                new InteractionCreateRequest(ORGANIZATION_A, "Без следующего действия", null, null, List.of()),
                "create-persist-empty"
        );

        Interaction reloaded = interactionService.get(profileA, scheduled.id());
        Interaction noActionReloaded = interactionService.get(profileA, withoutNextAction.id());
        InteractionPage page = interactionService.list(
                profileA,
                InteractionFilter.from(ORGANIZATION_A, null, null, null),
                InteractionQuery.from(0, 25, "createdAt,asc")
        );

        assertThat(reloaded.nextAction()).isEqualTo("Провести встречу");
        assertThat(reloaded.nextActionAt()).isEqualTo(dueAt);
        assertThat(reloaded.contactIds()).containsExactly(contact.id());
        assertThat(noActionReloaded.nextAction()).isNull();
        assertThat(noActionReloaded.nextActionAt()).isNull();
        assertThat(page.items()).filteredOn(item -> item.id().equals(scheduled.id())).singleElement().satisfies(item -> {
            assertThat(item.nextAction()).isEqualTo("Провести встречу");
            assertThat(item.nextActionAt()).isEqualTo(dueAt);
            assertThat(item.contactIds()).containsExactly(contact.id());
        });
    }

    @Test
    void rejectsContactFromAnotherOrganizationWithoutCreatingInteraction() {
        Contact foreignContact = contactService.create(
                profileB,
                ORGANIZATION_B,
                new ContactCreateRequest("Алексей Смирнов", null, null, null),
                "contact-b"
        );

        assertThatThrownBy(() -> interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Чужой контакт",
                        null,
                        null,
                        List.of(foreignContact.id())
                ),
                "create-cross-contact"
        )).isInstanceOf(InteractionValidationException.class)
                .hasMessage("Все контакты должны относиться к вузу взаимодействия");
        assertThat(count("interactions")).isZero();
    }

    @Test
    void rejectsUnknownContactWithoutBlockingVisibleContactList() {
        Contact contact = contactService.create(
                profileA,
                ORGANIZATION_A,
                new ContactCreateRequest("Олег Новиков", null, null, null),
                "contact-known"
        );

        assertThatThrownBy(() -> interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Несуществующий контакт",
                        null,
                        null,
                        List.of(UUID.randomUUID())
                ),
                "create-unknown-contact"
        )).isInstanceOf(InteractionValidationException.class)
                .hasMessage("Все контакты должны относиться к вузу взаимодействия");

        assertThat(contactService.list(profileA, ORGANIZATION_A)).extracting(Contact::id).containsExactly(contact.id());
        assertThat(count("interactions")).isZero();
    }

    @Test
    void listsOnlyInteractionsOfOneVisibleOrganizationAndRejectsHiddenOrganization() {
        Interaction first = createA("Сопровождение", "create-list-1");
        Interaction second = createA("Обучение", "create-list-2");
        Interaction foreign = interactionService.create(
                profileB,
                new InteractionCreateRequest(ORGANIZATION_B, "Чужая работа", null, null, List.of()),
                "create-list-b"
        );

        InteractionPage page = interactionService.list(
                profileA,
                InteractionFilter.from(ORGANIZATION_A, null, null, null),
                InteractionQuery.from(0, 1, "createdAt,asc")
        );

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items()).extracting(InteractionSummary::id).containsAnyOf(first.id(), second.id());
        assertThatThrownBy(() -> interactionService.list(
                profileA,
                InteractionFilter.from(ORGANIZATION_B, null, null, null),
                InteractionQuery.from(0, 25, "updatedAt,desc")
        )).isInstanceOf(OrganizationNotFoundException.class);
        assertThatThrownBy(() -> interactionService.get(profileA, foreign.id()))
                .isInstanceOf(InteractionNotFoundException.class);
    }

    @Test
    void sameTeamLeaderMaintainsTeamCardsAsAuthorAndForeignCardsStayHidden() {
        Interaction managerInteraction = createA("Карточка КАМ", "leader-team-interaction");

        Contact leaderContact = contactService.create(
                leaderA,
                ORGANIZATION_A,
                new ContactCreateRequest("Людмила Орлова", "Куратор", null, null),
                "leader-create-contact"
        );
        Interaction leaderInteraction = interactionService.create(
                leaderA,
                new InteractionCreateRequest(ORGANIZATION_A, "Карточка руководителя", null, null, List.of(leaderContact.id())),
                "leader-create-interaction"
        );
        Interaction transitioned = interactionService.transition(
                leaderA,
                managerInteraction.id(),
                new InteractionTransitionRequest(0, managerInteraction.stages().get(1).id(), null),
                "leader-transition"
        );
        InteractionCommentResult commented = interactionService.comment(
                leaderA,
                managerInteraction.id(),
                new InteractionCommentRequest(1, transitioned.currentStageId(), "Комментарий руководителя"),
                "leader-comment"
        );
        Interaction planned = interactionService.updatePlan(
                leaderA,
                managerInteraction.id(),
                new InteractionPlanRequest(2, Optional.of("Шаг руководителя"), null, null, null),
                "leader-plan"
        );
        InteractionStage futureStage = planned.stages().getLast();
        Interaction edited = interactionService.stageEdits(
                leaderA,
                managerInteraction.id(),
                new InteractionStageEditRequest(3, List.of(new InteractionStageEditOperation(
                        InteractionStageEditOperation.Type.RENAME,
                        futureStage.id(),
                        null,
                        "Этап руководителя",
                        null
                ))),
                "leader-stage-edit"
        );

        assertThat(leaderContact.createdBy()).isEqualTo(LEADER_A);
        assertThat(leaderInteraction.currentStageId()).isEqualTo(leaderInteraction.stages().getFirst().id());
        assertThat(commented.event().actorProfileId()).isEqualTo(LEADER_A);
        assertThat(commented.event().actorDisplayName()).isEqualTo("Вера Ковалёва");
        assertThat(edited.version()).isEqualTo(4);
        assertThat(edited.stages()).filteredOn(stage -> stage.id().equals(futureStage.id()))
                .singleElement()
                .satisfies(stage -> assertThat(stage.name()).isEqualTo("Этап руководителя"));
        assertThat(interactionService.events(profileA, managerInteraction.id()))
                .filteredOn(event -> event.version() > 0)
                .extracting(InteractionEvent::actorProfileId)
                .containsOnly(LEADER_A);

        long eventsBefore = count("interaction_events");
        assertThatThrownBy(() -> contactService.create(
                leaderB,
                ORGANIZATION_A,
                new ContactCreateRequest("Чужой контакт", null, null, null),
                "foreign-leader-contact-after-leader"
        )).isInstanceOf(OrganizationNotFoundException.class);
        assertThatThrownBy(() -> interactionService.updatePlan(
                leaderB,
                managerInteraction.id(),
                new InteractionPlanRequest(4, Optional.of("Чужой шаг"), null, null, null),
                "foreign-leader-plan-after-leader"
        )).isInstanceOf(InteractionNotFoundException.class);
        assertThat(count("interaction_events")).isEqualTo(eventsBefore);
    }

    @Test
    void sameTeamLeaderUploadsAndBindsAttachmentsOnlyOnTeamCards() {
        Interaction managerInteraction = createA("Вложение руководителя", "leader-attachment-interaction");
        MockMultipartFile file = new MockMultipartFile("file", "leader.pdf", "application/pdf", new byte[]{1});
        AttachmentContentValidator contentValidator = mock(AttachmentContentValidator.class);
        AttachmentStorage storage = mock(AttachmentStorage.class);
        AttachmentScanner scanner = mock(AttachmentScanner.class);
        when(contentValidator.inspect(file)).thenReturn(new AttachmentUploadInspection("leader.pdf", "application/pdf", 1, "0".repeat(64)));
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
                objectMapper
        );

        Attachment uploaded = attachmentService.upload(
                leaderA,
                managerInteraction.id(),
                managerInteraction.currentStageId(),
                file,
                "leader-upload"
        );
        InteractionCommentResult commented = interactionService.comment(
                leaderA,
                managerInteraction.id(),
                new InteractionCommentRequest(
                        managerInteraction.version(),
                        managerInteraction.currentStageId(),
                        "Файл руководителя",
                        List.of(uploaded.id())
                ),
                "leader-attachment-comment"
        );

        assertThat(jdbcTemplate.queryForObject("SELECT created_by FROM attachments WHERE id = ?", UUID.class, uploaded.id()))
                .isEqualTo(LEADER_A);
        assertThat(commented.event().actorProfileId()).isEqualTo(LEADER_A);
        assertThat(commented.interaction().attachments()).singleElement()
                .satisfies(attachment -> assertThat(attachment.eventId()).isEqualTo(commented.event().id()));
        assertThatThrownBy(() -> attachmentService.upload(
                leaderB,
                managerInteraction.id(),
                managerInteraction.currentStageId(),
                file,
                "foreign-leader-upload"
        )).isInstanceOf(InteractionNotFoundException.class);
        assertThat(count("attachments")).isEqualTo(1);
    }

    @Test
    void foreignLeaderGetsNotFoundBeforeMutationRoleChecks() {
        Interaction interaction = createA("Чужая команда", "foreign-leader-interaction");
        long contactsBefore = count("contacts");
        long interactionsBefore = count("interactions");
        long eventsBefore = count("interaction_events");
        long commandsBefore = count("command_idempotency_records");

        assertThatThrownBy(() -> contactService.create(
                leaderB,
                ORGANIZATION_A,
                new ContactCreateRequest("Недоступный контакт", null, null, null),
                "foreign-leader-contact"
        )).isInstanceOf(OrganizationNotFoundException.class);
        assertThatThrownBy(() -> interactionService.create(
                leaderB,
                new InteractionCreateRequest(ORGANIZATION_A, "Недоступное взаимодействие", null, null, List.of()),
                "foreign-leader-create"
        )).isInstanceOf(OrganizationNotFoundException.class);
        assertThatThrownBy(() -> interactionService.transition(
                leaderB,
                interaction.id(),
                new InteractionTransitionRequest(0, interaction.stages().get(1).id(), null),
                "foreign-leader-transition"
        )).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> interactionService.comment(
                leaderB,
                interaction.id(),
                new InteractionCommentRequest(0, interaction.currentStageId(), "Недоступный комментарий"),
                "foreign-leader-comment"
        )).isInstanceOf(InteractionNotFoundException.class);

        assertThat(count("contacts")).isEqualTo(contactsBefore);
        assertThat(count("interactions")).isEqualTo(interactionsBefore);
        assertThat(count("interaction_events")).isEqualTo(eventsBefore);
        assertThat(count("command_idempotency_records")).isEqualTo(commandsBefore);
    }

    @Test
    void replaysCreatePerActorAndOperationWithoutDuplicatingStateAndRejectsChangedPayload() {
        InteractionCreateRequest request = new InteractionCreateRequest(
                ORGANIZATION_A,
                "Повторяемая команда",
                null,
                null,
                List.of()
        );

        Interaction created = interactionService.create(profileA, request, "same-key");
        Interaction replayed = interactionService.create(profileA, request, "same-key");

        assertThat(replayed).isEqualTo(created);
        assertThat(count("interactions")).isEqualTo(1);
        assertThat(interactionService.events(profileA, created.id())).hasSize(1);
        assertThatThrownBy(() -> interactionService.create(
                profileA,
                new InteractionCreateRequest(ORGANIZATION_A, "Другой payload", null, null, List.of()),
                "same-key"
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(exception.currentVersion()).isNull();
                });
    }

    @Test
    void transitionsAllowAdjacentAndOptionalSkipWithReplayAndVersionConflict() {
        Interaction created = createA("Переходы", "create-transition");
        InteractionStage stage1 = created.stages().get(1);
        InteractionStage stage2 = created.stages().get(2);
        InteractionStage stage3 = created.stages().get(3);
        InteractionStage signing = created.stages().get(5);

        assertThatThrownBy(() -> interactionService.transition(
                profileA,
                created.id(),
                new InteractionTransitionRequest(0, stage2.id(), null),
                "transition-invalid-target"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("toStageId");
        });

        Interaction afterFirst = interactionService.transition(
                profileA,
                created.id(),
                new InteractionTransitionRequest(0, stage1.id(), null),
                "transition-1"
        );
        Interaction replayed = interactionService.transition(
                profileA,
                created.id(),
                new InteractionTransitionRequest(0, stage1.id(), null),
                "transition-1"
        );

        assertThat(afterFirst.version()).isEqualTo(1);
        assertThat(afterFirst.allowedTransitions()).allSatisfy(option -> assertThat(option.commentRequired()).isFalse());
        assertThat(replayed).isEqualTo(afterFirst);
        assertThat(interactionService.events(profileA, created.id())).hasSize(2);
        assertThatThrownBy(() -> interactionService.transition(
                profileA,
                created.id(),
                new InteractionTransitionRequest(0, stage2.id(), null),
                "stale-transition"
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
                    assertThat(exception.currentVersion()).isEqualTo(1);
                });

        Interaction afterSecond = interactionService.transition(
                profileA,
                created.id(),
                new InteractionTransitionRequest(afterFirst.version(), stage2.id(), null),
                "transition-2"
        );
        Interaction afterDocuments = interactionService.transition(
                profileA,
                created.id(),
                new InteractionTransitionRequest(afterSecond.version(), stage3.id(), null),
                "transition-3"
        );
        InteractionTransitionOption correctionSkip = afterDocuments.allowedTransitions().stream()
                .filter(option -> option.stageId().equals(signing.id()))
                .findFirst()
                .orElseThrow();
        assertThat(correctionSkip.commentRequired()).isTrue();
        assertThat(afterDocuments.allowedTransitions().stream()
                .filter(option -> !option.stageId().equals(signing.id()))
                .map(InteractionTransitionOption::commentRequired)
                .toList()).containsOnly(false);
        assertThatThrownBy(() -> interactionService.transition(
                profileA,
                created.id(),
                new InteractionTransitionRequest(afterDocuments.version(), signing.id(), null),
                "transition-skip-without-comment"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("comment");
            assertThat(exception).hasMessage("Заполните значение");
        });
        Interaction afterSkip = interactionService.transition(
                profileA,
                created.id(),
                new InteractionTransitionRequest(afterDocuments.version(), signing.id(), "Корректировка не требуется"),
                "transition-skip"
        );

        assertThat(afterSkip.currentStageId()).isEqualTo(signing.id());
        assertThat(afterSkip.version()).isEqualTo(4);
        assertThat(interactionService.events(profileA, created.id())
                .getLast().toStageNameSnapshot()).isEqualTo("Подписание");
    }

    @Test
    void commentIsVersionedAndIdempotentAndContactCreationReplays() {
        Contact first = contactService.create(
                profileA,
                ORGANIZATION_A,
                new ContactCreateRequest("Елена Иванова", null, "elena@example.test", "+70000000000"),
                "contact-replay"
        );
        Contact replayedContact = contactService.create(
                profileA,
                ORGANIZATION_A,
                new ContactCreateRequest("Елена Иванова", null, "elena@example.test", "+70000000000"),
                "contact-replay"
        );
        Interaction created = createA("Комментарий", "create-comment");

        InteractionCommentResult commented = interactionService.comment(
                profileA,
                created.id(),
                new InteractionCommentRequest(0, created.currentStageId(), "Нужна дополнительная информация"),
                "comment-1"
        );
        InteractionCommentResult replayedComment = interactionService.comment(
                profileA,
                created.id(),
                new InteractionCommentRequest(0, created.currentStageId(), "Нужна дополнительная информация"),
                "comment-1"
        );

        assertThat(replayedContact).isEqualTo(first);
        assertThat(contactService.list(profileA, ORGANIZATION_A)).hasSize(1);
        assertThat(commented.interaction().version()).isEqualTo(1);
        assertThat(replayedComment).isEqualTo(commented);
        List<InteractionEvent> history = interactionService.events(profileA, created.id());
        assertThat(history).hasSize(2);
        assertThat(commented.event()).satisfies(event -> {
            assertThat(event.type()).isEqualTo(InteractionEventType.COMMENTED);
            assertThat(event.stageId()).isEqualTo(created.currentStageId());
            assertThat(event.comment()).isEqualTo("Нужна дополнительная информация");
            assertThat(event.version()).isEqualTo(1);
        });
        assertThat(history.getLast().id()).isEqualTo(commented.event().id());
    }

    @Test
    void bindsOnlyCleanAttachmentToTheImmutableCommentEvent() {
        Interaction created = createA("Вложение комментария", "create-attachment-comment");
        UUID attachmentId = UUID.randomUUID();
        OffsetDateTime uploadedAt = OffsetDateTime.parse("2026-09-23T08:00:00Z");
        attachmentRepository.insert(
                attachmentId,
                created.id(),
                created.currentStageId(),
                "notes.pdf",
                "application/pdf",
                8,
                UUID.randomUUID(),
                "0000000000000000000000000000000000000000000000000000000000000000",
                profileA.id(),
                uploadedAt
        );
        attachmentRepository.updateStatus(attachmentId, ru.rtk.crm.attachment.AttachmentStatus.CLEAN, uploadedAt);

        InteractionCommentResult commented = interactionService.comment(
                profileA,
                created.id(),
                new InteractionCommentRequest(
                        created.version(),
                        created.currentStageId(),
                        "Вложение проверено",
                        List.of(attachmentId)
                ),
                "comment-with-attachment"
        );

        assertThat(commented.interaction().attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.id()).isEqualTo(attachmentId);
            assertThat(attachment.eventId()).isEqualTo(commented.event().id());
            assertThat(attachment.status()).isEqualTo(ru.rtk.crm.attachment.AttachmentStatus.CLEAN);
        });
        assertThat(interactionService.events(profileA, created.id())).hasSize(2);
    }

    @Test
    void rejectsCommentStageFromAnotherInteractionAndOverflowingPaginationOffset() {
        Interaction first = createA("Первая работа", "create-first-stage-check");
        Interaction second = createA("Вторая работа", "create-second-stage-check");

        assertThatThrownBy(() -> interactionService.comment(
                profileA,
                first.id(),
                new InteractionCommentRequest(0, second.currentStageId(), "Не тот этап"),
                "comment-foreign-stage"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("stageId");
            assertThat(exception).hasMessage("Этап не относится к этому взаимодействию");
        });
        assertThat(interactionService.events(profileA, first.id())).hasSize(1);
        assertThatThrownBy(() -> InteractionQuery.from(Integer.MAX_VALUE, 100, "updatedAt,desc").offset())
                .isInstanceOf(InteractionValidationException.class)
                .hasMessage("Слишком большой номер страницы");
    }

    @Test
    void createsAndReadsNullableProgramProductsAndExplicitLastContact() {
        UUID programId = insertProgram("Программа сопровождения", false);
        UUID firstProductId = insertProduct("Первый продукт", false);
        UUID secondProductId = insertProduct("Второй продукт", false);
        OffsetDateTime contactedAt = OffsetDateTime.parse("2026-09-22T11:30:00+03:00");

        Interaction created = interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Карточка с каталогом",
                        null,
                        null,
                        List.of(),
                        programId,
                        List.of(secondProductId, firstProductId),
                        contactedAt
                ),
                "create-catalog-links"
        );

        assertThat(created.programId()).isEqualTo(programId);
        assertThat(created.program()).isNotNull().satisfies(program -> {
            assertThat(program.name()).isEqualTo("Программа сопровождения");
            assertThat(program.archived()).isFalse();
        });
        assertThat(created.productIds()).containsExactlyInAnyOrder(firstProductId, secondProductId);
        assertThat(created.productAgreements()).hasSize(2).allSatisfy(agreement -> {
            assertThat(agreement.contractNumber()).isNull();
            assertThat(agreement.licenseSigned()).isNull();
            assertThat(agreement.licenseExpiryYear()).isNull();
            assertThat(agreement.transferStatus()).isNull();
        });
        assertThat(created.lastContactAt()).isEqualTo(contactedAt);
        assertThat(count("product_agreements")).isEqualTo(2);

        Interaction replayed = interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Карточка с каталогом",
                        null,
                        null,
                        List.of(),
                        programId,
                        List.of(firstProductId, secondProductId),
                        contactedAt
                ),
                "create-catalog-links"
        );
        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(count("product_agreements")).isEqualTo(2);

        jdbcTemplate.update("UPDATE programs SET archived = TRUE WHERE id = ?", programId);
        Interaction reloaded = interactionService.get(profileA, created.id());
        InteractionPage page = interactionService.list(
                profileA,
                InteractionFilter.from(ORGANIZATION_A, null, null, null),
                InteractionQuery.from(0, 25, "createdAt,asc")
        );
        assertThat(reloaded.programId()).isEqualTo(programId);
        assertThat(reloaded.program()).isNotNull().satisfies(program -> {
            assertThat(program.name()).isEqualTo("Программа сопровождения");
            assertThat(program.archived()).isTrue();
        });
        assertThat(reloaded.productAgreements()).extracting(ProductAgreement::productId)
                .containsExactlyInAnyOrder(firstProductId, secondProductId);
        assertThat(reloaded.lastContactAt()).isEqualTo(contactedAt);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.programId()).isEqualTo(programId);
            assertThat(item.productIds()).containsExactlyInAnyOrder(firstProductId, secondProductId);
            assertThat(item.lastContactAt()).isEqualTo(contactedAt);
        });
    }

    @Test
    void rejectsUnknownArchivedAndDuplicateCatalogReferencesWithoutPersistingCommands() {
        UUID archivedProgramId = insertProgram("Архивная программа", true);
        UUID archivedProductId = insertProduct("Архивный продукт", true);
        UUID activeProductId = insertProduct("Активный продукт", false);

        assertThatThrownBy(() -> interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Неизвестная программа",
                        null,
                        null,
                        List.of(),
                        UUID.randomUUID(),
                        List.of(),
                        null
                ),
                null
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("programId");
        });
        assertThatThrownBy(() -> interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Архивные ссылки",
                        null,
                        null,
                        List.of(),
                        archivedProgramId,
                        List.of(archivedProductId),
                        null
                ),
                "create-archived-catalog"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("programId");
        });
        assertThatThrownBy(() -> interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Архивный продукт",
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(archivedProductId),
                        null
                ),
                "create-archived-product"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("productIds");
        });
        assertThatThrownBy(() -> interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Повтор продукта",
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(activeProductId, activeProductId),
                        null
                ),
                "create-duplicate-product"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("productIds");
        });

        assertThat(count("interactions")).isZero();
        assertThat(count("product_agreements")).isZero();
        assertThat(count("interaction_events")).isZero();
        assertThat(count("command_idempotency_records")).isZero();
    }

    @Test
    void listsOnlyActiveCatalogEntriesInBusinessScope() {
        UUID activeProgramId = insertProgram("Активная программа", false);
        insertProgram("Архивная программа", true);
        UUID activeProductId = insertProduct("Активный продукт", false);
        insertProduct("Архивный продукт", true);

        assertThat(catalogLookupService.listPrograms(profileA, CatalogQuery.from(0, 25)))
                .satisfies(page -> {
                    assertThat(page.total()).isEqualTo(1);
                    assertThat(page.items()).extracting(item -> item.id()).containsExactly(activeProgramId);
                });
        assertThat(catalogLookupService.listProducts(leaderA, CatalogQuery.from(0, 25)))
                .satisfies(page -> {
                    assertThat(page.total()).isEqualTo(1);
                    assertThat(page.items()).extracting(item -> item.id()).containsExactly(activeProductId);
                });
        CrmProfile admin = new CrmProfile(UUID.randomUUID(), UserRole.ADMIN, null, 0);
        assertThat(catalogLookupService.listPrograms(admin, CatalogQuery.from(0, 25)).items()).isEmpty();
        assertThat(catalogLookupService.listProducts(admin, CatalogQuery.from(0, 25)).total()).isZero();
    }

    @Test
    void editsOnlyFutureUnusedSnapshotStagesWithVersionReplayAndScopeChecks() {
        Interaction created = createA("Локальный процесс", "create-stage-edit");
        InteractionStage current = created.stages().getFirst();
        InteractionStage moving = created.stages().get(2);
        InteractionStage protectedFuture = created.stages().get(1);

        InteractionStageEditRequest addRequest = new InteractionStageEditRequest(
                created.version(),
                List.of(new InteractionStageEditOperation(
                        InteractionStageEditOperation.Type.ADD_AFTER,
                        null,
                        current.id(),
                        "Дополнительная проверка",
                        false
                ))
        );
        Interaction afterAdd = interactionService.stageEdits(profileA, created.id(), addRequest, "stage-edit-add");
        Interaction replayed = interactionService.stageEdits(profileA, created.id(), addRequest, "stage-edit-add");
        InteractionStage added = afterAdd.stages().stream()
                .filter(stage -> stage.name().equals("Дополнительная проверка"))
                .findFirst()
                .orElseThrow();

        assertThat(afterAdd.version()).isEqualTo(1);
        assertThat(replayed).isEqualTo(afterAdd);
        assertThat(afterAdd.transitions()).anySatisfy(transition -> {
            assertThat(transition.fromStageId()).isEqualTo(current.id());
            assertThat(transition.toStageId()).isEqualTo(added.id());
        });
        assertThatThrownBy(() -> interactionService.stageEdits(
                profileA,
                created.id(),
                new InteractionStageEditRequest(created.version(), List.of(new InteractionStageEditOperation(
                        InteractionStageEditOperation.Type.RENAME,
                        current.id(),
                        null,
                        "Устаревшая версия",
                        null
                ))),
                "stage-edit-stale"
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
            assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
            assertThat(exception.currentVersion()).isEqualTo(afterAdd.version());
        });
        assertThatThrownBy(() -> interactionService.stageEdits(
                profileA,
                created.id(),
                new InteractionStageEditRequest(created.version(), List.of(new InteractionStageEditOperation(
                        InteractionStageEditOperation.Type.ADD_AFTER,
                        null,
                        current.id(),
                        "Другой payload",
                        false
                ))),
                "stage-edit-add"
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
            assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        });

        Interaction afterMove = interactionService.stageEdits(
                profileA,
                created.id(),
                new InteractionStageEditRequest(
                        afterAdd.version(),
                        List.of(new InteractionStageEditOperation(
                                InteractionStageEditOperation.Type.MOVE_AFTER,
                                moving.id(),
                                current.id(),
                                null,
                                null
                        ))
                ),
                "stage-edit-move"
        );
        Interaction afterRename = interactionService.stageEdits(
                profileA,
                created.id(),
                new InteractionStageEditRequest(
                        afterMove.version(),
                        List.of(new InteractionStageEditOperation(
                                InteractionStageEditOperation.Type.RENAME,
                                current.id(),
                                null,
                                "Первый контакт",
                                null
                        ))
                ),
                "stage-edit-rename"
        );
        InteractionCommentResult protectedComment = interactionService.comment(
                profileA,
                created.id(),
                new InteractionCommentRequest(afterRename.version(), protectedFuture.id(), "Этап использован"),
                "stage-edit-protect"
        );

        assertThat(afterMove.stages()).extracting(InteractionStage::id)
                .containsSubsequence(current.id(), moving.id());
        assertThat(afterRename.currentStageName()).isEqualTo("Первый контакт");
        assertThat(interactionService.events(profileA, created.id()).getFirst().stageNameSnapshot())
                .isEqualTo("Поиск контакта");
        assertThatThrownBy(() -> interactionService.stageEdits(
                profileA,
                created.id(),
                new InteractionStageEditRequest(
                        protectedComment.interaction().version(),
                        List.of(new InteractionStageEditOperation(
                                InteractionStageEditOperation.Type.MOVE_AFTER,
                                protectedFuture.id(),
                                added.id(),
                                null,
                                null
                        ))
                ),
                "stage-edit-used-move"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("id");
        });
        assertThatThrownBy(() -> interactionService.stageEdits(
                profileA,
                created.id(),
                new InteractionStageEditRequest(
                        protectedComment.interaction().version(),
                        List.of(new InteractionStageEditOperation(
                                InteractionStageEditOperation.Type.MOVE_AFTER,
                                current.id(),
                                added.id(),
                                null,
                                null
                        ))
                ),
                "stage-edit-current-move"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("id");
        });
        assertThatThrownBy(() -> interactionService.stageEdits(
                profileA,
                created.id(),
                new InteractionStageEditRequest(
                        protectedComment.interaction().version(),
                        List.of(new InteractionStageEditOperation(
                                InteractionStageEditOperation.Type.DELETE,
                                protectedFuture.id(),
                                null,
                                null,
                                null
                        ))
                ),
                "stage-edit-used-delete"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("id");
        });
        assertThatThrownBy(() -> interactionService.stageEdits(
                profileA,
                created.id(),
                new InteractionStageEditRequest(
                        protectedComment.interaction().version(),
                        List.of(new InteractionStageEditOperation(
                                InteractionStageEditOperation.Type.DELETE,
                                current.id(),
                                null,
                                null,
                                null
                        ))
                ),
                "stage-edit-current-delete"
        )).isInstanceOf(InteractionValidationException.class);

        Interaction afterDelete = interactionService.stageEdits(
                profileA,
                created.id(),
                new InteractionStageEditRequest(
                        protectedComment.interaction().version(),
                        List.of(new InteractionStageEditOperation(
                                InteractionStageEditOperation.Type.DELETE,
                                added.id(),
                                null,
                                null,
                                null
                        ))
                ),
                "stage-edit-delete"
        );
        assertThat(afterDelete.stages()).extracting(InteractionStage::id).doesNotContain(added.id());
        assertThat(afterDelete.version()).isEqualTo(protectedComment.interaction().version() + 1);
        assertThatThrownBy(() -> interactionService.stageEdits(
                leaderB,
                created.id(),
                new InteractionStageEditRequest(afterDelete.version(), List.of(new InteractionStageEditOperation(
                        InteractionStageEditOperation.Type.RENAME,
                        current.id(),
                        null,
                        "Чужое изменение",
                        null
                ))),
                "stage-edit-foreign"
        )).isInstanceOf(InteractionNotFoundException.class);
        Interaction leaderEdit = interactionService.stageEdits(
                leaderA,
                created.id(),
                new InteractionStageEditRequest(afterDelete.version(), List.of(new InteractionStageEditOperation(
                        InteractionStageEditOperation.Type.RENAME,
                        moving.id(),
                        null,
                        "Этап руководителя",
                        null
                ))),
                "stage-edit-leader"
        );
        assertThat(leaderEdit.version()).isEqualTo(afterDelete.version() + 1);
    }

    @Test
    void updatesPlanWithHistoryReplayVersionConflictAndScope() {
        UUID programId = insertProgram("Программа плана", false);
        UUID keptProductId = insertProduct("Сохраняемый продукт", false);
        UUID removedProductId = insertProduct("Снимаемый продукт", false);
        UUID addedProductId = insertProduct("Добавляемый продукт", false);
        OffsetDateTime firstDueAt = OffsetDateTime.parse("2026-10-01T10:00:00+03:00");
        Interaction created = interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "План работы",
                        "Первый звонок",
                        firstDueAt,
                        List.of(),
                        null,
                        List.of(keptProductId, removedProductId),
                        null
                ),
                "plan-create"
        );
        OffsetDateTime dueAt = OffsetDateTime.parse("2026-10-05T12:00:00+03:00");
        InteractionPlanRequest request = new InteractionPlanRequest(
                0,
                Optional.of("Провести встречу"),
                Optional.of(dueAt),
                Optional.of(programId),
                Optional.of(List.of(keptProductId, addedProductId))
        );

        Interaction updated = interactionService.updatePlan(profileA, created.id(), request, "plan-update");
        Interaction replayed = interactionService.updatePlan(profileA, created.id(), request, "plan-update");

        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.nextAction()).isEqualTo("Провести встречу");
        assertThat(updated.nextActionAt()).isEqualTo(dueAt);
        assertThat(updated.programId()).isEqualTo(programId);
        assertThat(updated.productIds()).containsExactlyInAnyOrder(keptProductId, addedProductId);
        assertThat(replayed).isEqualTo(updated);
        List<InteractionEvent> history = interactionService.events(profileA, created.id());
        assertThat(history).hasSize(2);
        assertThat(history.getFirst().nextStep()).isEqualTo(new InteractionNextStep("Первый звонок", firstDueAt));
        assertThat(history.getLast()).satisfies(event -> {
            assertThat(event.type()).isEqualTo(InteractionEventType.PLAN_UPDATED);
            assertThat(event.version()).isEqualTo(1);
            assertThat(event.stageId()).isEqualTo(created.currentStageId());
            assertThat(event.nextStep()).isEqualTo(new InteractionNextStep("Провести встречу", dueAt));
            assertThat(event.comment()).contains(
                    "Программа: «Программа плана»",
                    "Добавлены продукты: «Добавляемый продукт»",
                    "Удалены продукты: «Снимаемый продукт»"
            );
            assertThat(event.actorProfileId()).isEqualTo(MANAGER_A);
            assertThat(event.actorDisplayName()).isEqualTo("Анна Смирнова");
        });

        assertThatThrownBy(() -> interactionService.updatePlan(
                profileA,
                created.id(),
                new InteractionPlanRequest(0, Optional.of("Другой шаг"), null, null, null),
                "plan-update"
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
            assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        });
        assertThatThrownBy(() -> interactionService.updatePlan(
                profileA,
                created.id(),
                new InteractionPlanRequest(0, Optional.of("Устаревший шаг"), null, null, null),
                "plan-stale"
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
            assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
            assertThat(exception.currentVersion()).isEqualTo(1);
        });
        assertThatThrownBy(() -> interactionService.updatePlan(
                profileB,
                created.id(),
                new InteractionPlanRequest(1, Optional.of("Чужой шаг"), null, null, null),
                "plan-foreign-user"
        )).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> interactionService.updatePlan(
                leaderB,
                created.id(),
                new InteractionPlanRequest(1, Optional.of("Чужой руководитель"), null, null, null),
                "plan-foreign-leader"
        )).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> interactionService.updatePlan(
                profileA,
                created.id(),
                new InteractionPlanRequest(1, null, null, null, null),
                "plan-empty"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("body");
        });
        jdbcTemplate.update(
                "UPDATE product_agreements SET contract_number = ? WHERE interaction_id = ? AND product_id = ?",
                "Д-17",
                created.id(),
                keptProductId
        );
        interactionRepository.deleteEmptyProductAgreements(created.id(), List.of(keptProductId));
        assertThat(interactionService.get(profileA, created.id()).productIds()).contains(keptProductId);
        assertThatThrownBy(() -> interactionService.updatePlan(
                profileA,
                created.id(),
                new InteractionPlanRequest(1, null, null, null, Optional.of(List.of(addedProductId))),
                "plan-remove-filled"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("productIds");
        });
        UUID archivedProductId = insertProduct("Архивный продукт плана", true);
        assertThatThrownBy(() -> interactionService.updatePlan(
                profileA,
                created.id(),
                new InteractionPlanRequest(
                        1,
                        null,
                        null,
                        null,
                        Optional.of(List.of(keptProductId, addedProductId, archivedProductId))
                ),
                "plan-add-archived"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("productIds");
        });
        assertThat(interactionService.events(profileA, created.id())).hasSize(2);
        assertThat(interactionService.get(profileA, created.id()).version()).isEqualTo(1);

        Interaction cleared = interactionService.updatePlan(
                profileA,
                created.id(),
                new InteractionPlanRequest(1, null, Optional.empty(), Optional.empty(), null),
                "plan-clear"
        );

        assertThat(cleared.version()).isEqualTo(2);
        assertThat(cleared.nextAction()).isEqualTo("Провести встречу");
        assertThat(cleared.nextActionAt()).isNull();
        assertThat(cleared.programId()).isNull();
        assertThat(interactionService.events(profileA, created.id()).getLast()).satisfies(event -> {
            assertThat(event.nextStep()).isEqualTo(new InteractionNextStep("Провести встречу", null));
            assertThat(event.comment()).isEqualTo("Программа снята");
        });
    }

    @Test
    void planRequestDistinguishesAbsentFieldsFromExplicitNull() throws Exception {
        InteractionPlanRequest request = objectMapper.readValue(
                "{\"version\":3,\"nextActionAt\":null,\"productIds\":[]}",
                InteractionPlanRequest.class
        );

        assertThat(request.version()).isEqualTo(3);
        assertThat(request.nextAction()).isNull();
        assertThat(request.nextActionAt()).isEmpty();
        assertThat(request.programId()).isNull();
        assertThat(request.productIds()).contains(List.of());
        assertThat(objectMapper.readValue(
                "{\"version\":1,\"toStageId\":\"00000000-0000-0000-0000-000000000301\"}",
                InteractionTransitionRequest.class
        ).nextStep()).isNull();
    }

    @Test
    void transitionAndCommentReplaceNextStepInTheSameEvent() {
        Interaction created = createA("Контроль исполнения", "control-create");
        OffsetDateTime dueAt = OffsetDateTime.parse("2026-10-07T09:30:00+03:00");

        Interaction moved = interactionService.transition(
                profileA,
                created.id(),
                new InteractionTransitionRequest(
                        0,
                        created.stages().get(1).id(),
                        null,
                        List.of(),
                        new InteractionNextStep("Уточнить потребность", dueAt)
                ),
                "control-transition"
        );
        InteractionCommentResult unchanged = interactionService.comment(
                profileA,
                created.id(),
                new InteractionCommentRequest(1, moved.currentStageId(), "План не меняется"),
                "control-comment"
        );
        InteractionCommentResult cleared = interactionService.comment(
                profileA,
                created.id(),
                new InteractionCommentRequest(
                        2,
                        moved.currentStageId(),
                        "Срок снят до ответа",
                        List.of(),
                        new InteractionNextStep("Уточнить потребность", null)
                ),
                "control-comment-clear"
        );

        assertThat(moved.version()).isEqualTo(1);
        assertThat(moved.nextAction()).isEqualTo("Уточнить потребность");
        assertThat(moved.nextActionAt()).isEqualTo(dueAt);
        assertThat(unchanged.event().nextStep()).isNull();
        assertThat(unchanged.interaction().nextActionAt()).isEqualTo(dueAt);
        assertThat(cleared.interaction().nextAction()).isEqualTo("Уточнить потребность");
        assertThat(cleared.interaction().nextActionAt()).isNull();
        assertThat(cleared.event().nextStep()).isEqualTo(new InteractionNextStep("Уточнить потребность", null));
        assertThat(cleared.event().actorDisplayName()).isEqualTo("Анна Смирнова");
        assertThat(interactionService.events(profileA, created.id()))
                .filteredOn(event -> event.type() == InteractionEventType.TRANSITIONED)
                .singleElement()
                .satisfies(event -> assertThat(event.nextStep())
                        .isEqualTo(new InteractionNextStep("Уточнить потребность", dueAt)));
    }

    @Test
    void addingMandatoryStageRemovesDirectBypassAndRecordsHistory() {
        Interaction created = createA("Обязательный этап", "graph-add-create");
        InteractionStage documents = created.stages().get(3);
        InteractionStage correction = created.stages().get(4);
        InteractionStage signing = created.stages().get(5);

        assertThat(created.transitions()).contains(new InteractionStageTransition(documents.id(), signing.id(), true));

        Interaction edited = interactionService.stageEdits(
                profileA,
                created.id(),
                new InteractionStageEditRequest(0, List.of(new InteractionStageEditOperation(
                        InteractionStageEditOperation.Type.ADD_AFTER,
                        null,
                        correction.id(),
                        "Согласование с юристом",
                        false
                ))),
                "graph-add"
        );
        InteractionStage added = stageNamed(edited, "Согласование с юристом");

        assertThat(edited.stages()).extracting(InteractionStage::id)
                .containsSubsequence(documents.id(), correction.id(), added.id(), signing.id());
        assertThat(edited.transitions()).contains(
                new InteractionStageTransition(correction.id(), added.id(), false),
                new InteractionStageTransition(added.id(), correction.id(), false),
                new InteractionStageTransition(added.id(), signing.id(), false),
                new InteractionStageTransition(signing.id(), added.id(), false),
                new InteractionStageTransition(documents.id(), added.id(), true)
        );
        assertThat(edited.transitions())
                .noneMatch(transition -> transition.toStageId().equals(signing.id())
                        && (transition.fromStageId().equals(correction.id())
                        || transition.fromStageId().equals(documents.id())));
        assertAdjacentOrCommented(edited);
        assertThat(interactionService.events(profileA, created.id()).getLast()).satisfies(event -> {
            assertThat(event.type()).isEqualTo(InteractionEventType.STAGES_EDITED);
            assertThat(event.stageId()).isEqualTo(created.currentStageId());
            assertThat(event.version()).isEqualTo(1);
            assertThat(event.comment())
                    .isEqualTo("Добавлен обязательный этап «Согласование с юристом» после «Корректировка документов»");
        });
    }

    @Test
    void movingAndDeletingStagesRebuildNeighbourTransitionsInBothDirections() {
        Interaction created = createA("Перестановка этапов", "graph-move-create");
        InteractionStage documents = created.stages().get(3);
        InteractionStage correction = created.stages().get(4);
        InteractionStage signing = created.stages().get(5);
        InteractionStage transfer = created.stages().get(6);
        InteractionStage support = created.stages().get(7);

        Interaction moved = interactionService.stageEdits(
                profileA,
                created.id(),
                new InteractionStageEditRequest(0, List.of(new InteractionStageEditOperation(
                        InteractionStageEditOperation.Type.MOVE_AFTER,
                        signing.id(),
                        transfer.id(),
                        null,
                        null
                ))),
                "graph-move"
        );

        assertThat(moved.stages()).extracting(InteractionStage::id)
                .containsSubsequence(correction.id(), transfer.id(), signing.id(), support.id());
        assertThat(moved.transitions()).contains(
                new InteractionStageTransition(correction.id(), transfer.id(), false),
                new InteractionStageTransition(transfer.id(), correction.id(), false),
                new InteractionStageTransition(transfer.id(), signing.id(), false),
                new InteractionStageTransition(signing.id(), transfer.id(), false),
                new InteractionStageTransition(signing.id(), support.id(), false),
                new InteractionStageTransition(support.id(), signing.id(), false),
                new InteractionStageTransition(documents.id(), transfer.id(), true)
        );
        assertThat(moved.transitions()).noneMatch(transition -> transition.fromStageId().equals(transfer.id())
                && transition.toStageId().equals(support.id()));
        assertAdjacentOrCommented(moved);

        Interaction deleted = interactionService.stageEdits(
                profileA,
                created.id(),
                new InteractionStageEditRequest(1, List.of(new InteractionStageEditOperation(
                        InteractionStageEditOperation.Type.DELETE,
                        correction.id(),
                        null,
                        null,
                        null
                ))),
                "graph-delete"
        );

        assertThat(deleted.transitions()).contains(
                new InteractionStageTransition(documents.id(), transfer.id(), false),
                new InteractionStageTransition(transfer.id(), documents.id(), false)
        );
        assertThat(deleted.transitions()).noneMatch(transition -> transition.fromStageId().equals(correction.id())
                || transition.toStageId().equals(correction.id()));
        assertAdjacentOrCommented(deleted);
        assertThat(interactionService.events(profileA, created.id()))
                .filteredOn(event -> event.type() == InteractionEventType.STAGES_EDITED)
                .extracting(InteractionEvent::comment)
                .containsExactly(
                        "Этап «Подписание» перемещён после «Передача материалов, лицензии и документации»",
                        "Удалён этап «Корректировка документов»"
                );
    }

    @Test
    void structuralEditsDoNotAddReturnsToTemplateWithoutThem() {
        UUID templateId = insertForwardOnlyTemplate();
        Interaction created = interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Без возвратов",
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        templateId
                ),
                "forward-create"
        );
        InteractionStage start = created.stages().get(0);
        InteractionStage review = created.stages().get(1);
        InteractionStage optional = created.stages().get(2);
        InteractionStage finish = created.stages().get(3);
        Interaction atReview = interactionService.transition(
                profileA,
                created.id(),
                new InteractionTransitionRequest(0, review.id(), null),
                "forward-review"
        );

        Interaction added = interactionService.stageEdits(
                profileA,
                created.id(),
                new InteractionStageEditRequest(atReview.version(), List.of(new InteractionStageEditOperation(
                        InteractionStageEditOperation.Type.ADD_AFTER,
                        null,
                        review.id(),
                        "Вставленный",
                        false
                ))),
                "forward-add"
        );
        InteractionStage inserted = stageNamed(added, "Вставленный");

        assertThat(added.transitions()).containsExactlyInAnyOrder(
                new InteractionStageTransition(start.id(), review.id(), false),
                new InteractionStageTransition(review.id(), inserted.id(), false),
                new InteractionStageTransition(inserted.id(), optional.id(), false),
                new InteractionStageTransition(optional.id(), finish.id(), false)
        );

        Interaction edited = interactionService.stageEdits(
                profileA,
                created.id(),
                new InteractionStageEditRequest(added.version(), List.of(
                        new InteractionStageEditOperation(
                                InteractionStageEditOperation.Type.MOVE_AFTER,
                                optional.id(),
                                review.id(),
                                null,
                                null
                        ),
                        new InteractionStageEditOperation(
                                InteractionStageEditOperation.Type.DELETE,
                                inserted.id(),
                                null,
                                null,
                                null
                        )
                )),
                "forward-move-delete"
        );

        assertThat(edited.transitions()).containsExactlyInAnyOrder(
                new InteractionStageTransition(start.id(), review.id(), false),
                new InteractionStageTransition(review.id(), optional.id(), false),
                new InteractionStageTransition(review.id(), finish.id(), true),
                new InteractionStageTransition(optional.id(), finish.id(), false)
        );
        assertThat(edited.allowedTransitions()).extracting(InteractionTransitionOption::stageId)
                .doesNotContain(start.id());
        assertThatThrownBy(() -> interactionService.transition(
                profileA,
                created.id(),
                new InteractionTransitionRequest(edited.version(), start.id(), null),
                "forward-return"
        )).isInstanceOf(InteractionValidationException.class);
    }

    @Test
    void listLoadsLinkedIdsForTheWholePage() {
        UUID productId = insertProduct("Продукт страницы", false);
        Contact contact = contactService.create(
                profileA,
                ORGANIZATION_A,
                new ContactCreateRequest("Павел Никитин", null, null, null),
                "page-contact"
        );
        Interaction linked = interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Со связями",
                        null,
                        null,
                        List.of(contact.id()),
                        null,
                        List.of(productId),
                        null
                ),
                "page-linked"
        );
        Interaction empty = createA("Без связей", "page-empty");

        Map<UUID, InteractionSummary> items = interactionService.list(
                profileA,
                InteractionFilter.from(ORGANIZATION_A, null, null, null),
                InteractionQuery.from(0, 25, "createdAt,asc")
        ).items().stream().collect(Collectors.toMap(InteractionSummary::id, Function.identity()));

        assertThat(items.get(linked.id()).contactIds()).containsExactly(contact.id());
        assertThat(items.get(linked.id()).productIds()).containsExactly(productId);
        assertThat(items.get(empty.id()).contactIds()).isEmpty();
        assertThat(items.get(empty.id()).productIds()).isEmpty();
    }

    private InteractionStage stageNamed(Interaction interaction, String name) {
        return interaction.stages().stream()
                .filter(stage -> stage.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private void assertAdjacentOrCommented(Interaction interaction) {
        Map<UUID, Integer> orders = interaction.stages().stream()
                .collect(Collectors.toMap(InteractionStage::id, InteractionStage::order));
        assertThat(interaction.transitions()).allSatisfy(transition -> assertThat(
                transition.commentRequired()
                        || Math.abs(orders.get(transition.fromStageId()) - orders.get(transition.toStageId())) == 1
        ).isTrue());
    }

    @Test
    void listsVisibleInteractionsAcrossOrganizationsWithWorkFilters() {
        insertOrganization(ORGANIZATION_A2, "Колледж А2", TEAM_A, MANAGER_A);
        OffsetDateTime weekStart = InteractionDue.weekStart(OffsetDateTime.now());
        OffsetDateTime longAgo = OffsetDateTime.parse("2000-01-01T10:00:00+03:00");
        Interaction overdue = interactionService.create(
                profileA,
                new InteractionCreateRequest(ORGANIZATION_A, "Просроченная встреча", "Позвонить", longAgo, List.of()),
                "work-overdue"
        );
        Interaction thisWeek = interactionService.create(
                profileA,
                new InteractionCreateRequest(ORGANIZATION_A2, "Встреча 100 процентов", "Встретиться", weekStart.plusHours(1), List.of()),
                "work-this-week"
        );
        Interaction nextWeek = interactionService.create(
                profileA,
                new InteractionCreateRequest(ORGANIZATION_A, "Встреча через неделю", "Подготовить план", weekStart.plusDays(8), List.of()),
                "work-next-week"
        );
        Interaction withoutNextStep = interactionService.create(
                profileA,
                new InteractionCreateRequest(ORGANIZATION_A2, "Без шага", null, null, List.of()),
                "work-without-step"
        );
        interactionService.create(
                profileB,
                new InteractionCreateRequest(ORGANIZATION_B, "Чужая встреча", "Позвонить", longAgo, List.of()),
                "work-foreign"
        );

        InteractionPage all = interactionService.list(
                profileA,
                InteractionFilter.from(null, null, null, null),
                InteractionQuery.from(0, 25, "nextActionAt,asc")
        );
        assertThat(all.total()).isEqualTo(4);
        assertThat(all.items()).extracting(InteractionSummary::id)
                .containsExactly(overdue.id(), thisWeek.id(), nextWeek.id(), withoutNextStep.id());
        assertThat(all.items().get(1)).satisfies(summary -> {
            assertThat(summary.organizationName()).isEqualTo("Колледж А2");
            assertThat(summary.ownerManagerName()).isEqualTo("Анна Смирнова");
            assertThat(summary.programName()).isNull();
        });

        assertThat(ids(profileA, InteractionFilter.from(null, null, "OVERDUE", null)))
                .contains(overdue.id())
                .doesNotContain(nextWeek.id(), withoutNextStep.id());
        assertThat(ids(profileA, InteractionFilter.from(null, null, "THIS_WEEK", null))).containsExactly(thisWeek.id());
        assertThat(ids(profileA, InteractionFilter.from(null, null, "NO_NEXT_STEP", null))).containsExactly(withoutNextStep.id());
        assertThat(ids(profileA, InteractionFilter.from(null, "колледж", null, null)))
                .containsExactlyInAnyOrder(thisWeek.id(), withoutNextStep.id());
        assertThat(ids(profileA, InteractionFilter.from(null, "ЧЕРЕЗ НЕДЕЛЮ", null, null))).containsExactly(nextWeek.id());
        assertThat(ids(profileA, InteractionFilter.from(null, "100%", null, null))).isEmpty();
        assertThat(ids(profileA, InteractionFilter.from(null, null, null, "Поиск контакта"))).hasSize(4);
        assertThat(ids(profileA, InteractionFilter.from(null, null, null, "Нет такого этапа"))).isEmpty();
        assertThat(ids(profileA, InteractionFilter.from(ORGANIZATION_A2, null, null, null)))
                .containsExactlyInAnyOrder(thisWeek.id(), withoutNextStep.id());
        assertThat(ids(leaderA, InteractionFilter.from(null, null, null, null))).hasSize(4);
        assertThat(ids(leaderB, InteractionFilter.from(null, null, null, null))).hasSize(1);
        assertThat(interactionService.list(
                new CrmProfile(UUID.randomUUID(), UserRole.ADMIN, TEAM_A, 0),
                InteractionFilter.from(null, null, null, null),
                InteractionQuery.from(0, 25, "nextActionAt,asc")
        ).total()).isZero();
        InteractionPage secondPage = interactionService.list(
                profileA,
                InteractionFilter.from(null, null, null, null),
                InteractionQuery.from(1, 3, "nextActionAt,asc")
        );
        assertThat(secondPage.total()).isEqualTo(4);
        assertThat(secondPage.items()).extracting(InteractionSummary::id).containsExactly(withoutNextStep.id());
        assertThatThrownBy(() -> InteractionFilter.from(null, null, "LATER", null))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
                    assertThat(exception.field()).isEqualTo("due");
                });
    }

    @Test
    void deadlineWithoutNextActionFallsOnlyIntoNoNextStep() {
        OffsetDateTime weekStart = InteractionDue.weekStart(OffsetDateTime.now());
        Interaction pastDeadline = interactionService.create(
                profileA,
                new InteractionCreateRequest(
                        ORGANIZATION_A,
                        "Срок прошёл, шага нет",
                        null,
                        OffsetDateTime.parse("2000-01-01T10:00:00+03:00"),
                        List.of()
                ),
                "work-past-deadline-without-action"
        );
        Interaction weekDeadline = interactionService.create(
                profileA,
                new InteractionCreateRequest(ORGANIZATION_A, "Срок на неделе, шага нет", null, weekStart.plusDays(6), List.of()),
                "work-week-deadline-without-action"
        );

        assertThat(ids(profileA, InteractionFilter.from(null, null, "OVERDUE", null))).isEmpty();
        assertThat(ids(profileA, InteractionFilter.from(null, null, "THIS_WEEK", null))).isEmpty();
        assertThat(ids(profileA, InteractionFilter.from(null, null, "NO_NEXT_STEP", null)))
                .containsExactlyInAnyOrder(pastDeadline.id(), weekDeadline.id());
    }

    private List<UUID> ids(CrmProfile profile, InteractionFilter filter) {
        return interactionService.list(profile, filter, InteractionQuery.from(0, 25, "nextActionAt,asc"))
                .items()
                .stream()
                .map(InteractionSummary::id)
                .toList();
    }

    private void insertProfile(UUID id, String displayName) {
        jdbcTemplate.update("INSERT INTO crm_user_profiles (id, display_name) VALUES (?, ?)", id, displayName);
    }

    private Interaction createA(String title, String idempotencyKey) {
        return interactionService.create(
                profileA,
                new InteractionCreateRequest(ORGANIZATION_A, title, null, null, List.of()),
                idempotencyKey
        );
    }

    private UUID insertProgram(String name, boolean archived) {
        UUID directionId = UUID.randomUUID();
        UUID programId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO directions (id, name, archived, version) VALUES (?, ?, ?, ?)",
                directionId,
                "Направление " + directionId,
                false,
                0
        );
        jdbcTemplate.update(
                "INSERT INTO programs (id, direction_id, name, archived, version) VALUES (?, ?, ?, ?, ?)",
                programId,
                directionId,
                name,
                archived,
                0
        );
        return programId;
    }

    private UUID insertProduct(String name, boolean archived) {
        UUID vendorId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO vendors (id, name, archived, version) VALUES (?, ?, ?, ?)",
                vendorId,
                "Вендор " + vendorId,
                false,
                0
        );
        jdbcTemplate.update(
                "INSERT INTO products (id, vendor_id, name, archived, version) VALUES (?, ?, ?, ?, ?)",
                productId,
                vendorId,
                name,
                archived,
                0
        );
        return productId;
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY,
                    name VARCHAR(160) NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_user_profiles (
                    id UUID PRIMARY KEY,
                    display_name VARCHAR(200) NOT NULL,
                    role VARCHAR(16) NOT NULL DEFAULT 'USER',
                    team_id UUID,
                    active BOOLEAN NOT NULL DEFAULT TRUE
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY,
                    name VARCHAR(300) NOT NULL,
                    type VARCHAR(16) NOT NULL,
                    team_id UUID NOT NULL,
                    owner_manager_id UUID,
                    version INTEGER NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS contacts (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    position VARCHAR(200),
                    email VARCHAR(320),
                    phone VARCHAR(50),
                    version INTEGER NOT NULL,
                    created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS directions (
                    id UUID PRIMARY KEY,
                    name VARCHAR(200) NOT NULL,
                    archived BOOLEAN NOT NULL,
                    version INTEGER NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS programs (
                    id UUID PRIMARY KEY,
                    direction_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    archived BOOLEAN NOT NULL,
                    version INTEGER NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS vendors (
                    id UUID PRIMARY KEY,
                    name VARCHAR(200) NOT NULL,
                    archived BOOLEAN NOT NULL,
                    version INTEGER NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS products (
                    id UUID PRIMARY KEY,
                    vendor_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    archived BOOLEAN NOT NULL,
                    version INTEGER NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS interactions (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    title VARCHAR(200) NOT NULL,
                    current_stage_id UUID NOT NULL,
                    next_action VARCHAR(500),
                    next_action_at TIMESTAMP WITH TIME ZONE,
                    program_id UUID,
                    last_contact_at TIMESTAMP WITH TIME ZONE,
                    version INTEGER NOT NULL,
                    created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS product_agreements (
                    id UUID PRIMARY KEY,
                    interaction_id UUID NOT NULL,
                    product_id UUID NOT NULL,
                    contract_number VARCHAR(200),
                    license_signed BOOLEAN,
                    license_expiry_year INTEGER,
                    transfer_status VARCHAR(160),
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS interaction_stages (
                    id UUID PRIMARY KEY,
                    interaction_id UUID NOT NULL,
                    stage_order INTEGER NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    optional BOOLEAN NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS workflow_templates (
                    id UUID PRIMARY KEY,
                    team_id UUID,
                    name VARCHAR(200) NOT NULL,
                    default_template BOOLEAN NOT NULL,
                    version INTEGER NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS workflow_template_stages (
                    id UUID PRIMARY KEY,
                    template_id UUID NOT NULL,
                    stage_order INTEGER NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    optional BOOLEAN NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS workflow_template_transitions (
                    template_id UUID NOT NULL,
                    from_stage_id UUID NOT NULL,
                    to_stage_id UUID NOT NULL,
                    comment_required BOOLEAN NOT NULL,
                    PRIMARY KEY (template_id, from_stage_id, to_stage_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS interaction_stage_transitions (
                    interaction_id UUID NOT NULL,
                    from_stage_id UUID NOT NULL,
                    to_stage_id UUID NOT NULL,
                    comment_required BOOLEAN NOT NULL,
                    PRIMARY KEY (interaction_id, from_stage_id, to_stage_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS interaction_contacts (
                    interaction_id UUID NOT NULL,
                    organization_id UUID NOT NULL,
                    contact_id UUID NOT NULL,
                    PRIMARY KEY (interaction_id, contact_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS command_idempotency_records (
                    id UUID PRIMARY KEY,
                    actor_profile_id UUID NOT NULL,
                    operation VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    request_fingerprint CHAR(64) NOT NULL,
                    result_json VARCHAR(100000),
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (actor_profile_id, operation, idempotency_key)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS attachments (
                    id UUID PRIMARY KEY,
                    interaction_id UUID NOT NULL,
                    stage_id UUID NOT NULL,
                    event_id UUID,
                    original_name VARCHAR(255) NOT NULL,
                    media_type VARCHAR(160) NOT NULL,
                    size_bytes BIGINT NOT NULL,
                    storage_key UUID NOT NULL,
                    checksum CHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS interaction_events (
                    id UUID PRIMARY KEY,
                    interaction_id UUID NOT NULL,
                    command_id UUID NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    stage_id UUID NOT NULL,
                    stage_name_snapshot VARCHAR(200) NOT NULL,
                    from_stage_id UUID,
                    from_stage_name_snapshot VARCHAR(200),
                    to_stage_id UUID,
                    to_stage_name_snapshot VARCHAR(200),
                    comment VARCHAR(4000),
                    plan_changed BOOLEAN NOT NULL DEFAULT FALSE,
                    next_action VARCHAR(500),
                    next_action_at TIMESTAMP WITH TIME ZONE,
                    actor_profile_id UUID NOT NULL,
                    owner_manager_id_snapshot UUID,
                    version INTEGER NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
    }

    private void insertDefaultWorkflowTemplate() {
        UUID templateId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-23T12:00:00Z");
        jdbcTemplate.update(
                """
                        INSERT INTO workflow_templates (
                            id, team_id, name, default_template, version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                templateId,
                null,
                "Базовый процесс ИТ Школы РТК",
                true,
                0,
                now,
                now
        );
        List<String> names = List.of(
                "Поиск контакта",
                "Уточнение актуальности",
                "Встреча",
                "Обмен документами",
                "Корректировка документов",
                "Подписание",
                "Передача материалов, лицензии и документации",
                "Сопровождение внедрения",
                "Обучение преподавателей",
                "Актуализация программы",
                "Занятия",
                "Обновление документации и материалов",
                "Повышение квалификации"
        );
        List<UUID> stageIds = new java.util.ArrayList<>();
        for (int order = 0; order < names.size(); order++) {
            UUID stageId = UUID.randomUUID();
            stageIds.add(stageId);
            jdbcTemplate.update(
                    """
                            INSERT INTO workflow_template_stages (id, template_id, stage_order, name, optional)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    stageId,
                    templateId,
                    order,
                    names.get(order),
                    order == 4
            );
        }
        for (int order = 0; order + 1 < stageIds.size(); order++) {
            insertTemplateTransition(templateId, stageIds.get(order), stageIds.get(order + 1), false);
            insertTemplateTransition(templateId, stageIds.get(order + 1), stageIds.get(order), false);
        }
        insertTemplateTransition(templateId, stageIds.get(3), stageIds.get(5), true);
    }

    private UUID insertForwardOnlyTemplate() {
        UUID templateId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-23T12:00:00Z");
        jdbcTemplate.update(
                """
                        INSERT INTO workflow_templates (
                            id, team_id, name, default_template, version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                templateId,
                TEAM_A,
                "Процесс без возвратов",
                false,
                0,
                now,
                now
        );
        List<UUID> stageIds = new java.util.ArrayList<>();
        List<String> names = List.of("Старт", "Проверка", "Необязательный", "Финиш");
        for (int order = 0; order < names.size(); order++) {
            UUID stageId = UUID.randomUUID();
            stageIds.add(stageId);
            jdbcTemplate.update(
                    """
                            INSERT INTO workflow_template_stages (id, template_id, stage_order, name, optional)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    stageId,
                    templateId,
                    order,
                    names.get(order),
                    order == 2
            );
        }
        insertTemplateTransition(templateId, stageIds.get(0), stageIds.get(1), false);
        insertTemplateTransition(templateId, stageIds.get(1), stageIds.get(2), false);
        insertTemplateTransition(templateId, stageIds.get(2), stageIds.get(3), false);
        insertTemplateTransition(templateId, stageIds.get(1), stageIds.get(3), true);
        return templateId;
    }

    private WorkflowTemplateFixture insertWorkflowTemplate(UUID teamId, String name) {
        UUID templateId = UUID.randomUUID();
        UUID startStageId = UUID.randomUUID();
        UUID finishStageId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-23T12:00:00Z");
        jdbcTemplate.update(
                """
                        INSERT INTO workflow_templates (
                            id, team_id, name, default_template, version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                templateId,
                teamId,
                name,
                false,
                0,
                now,
                now
        );
        jdbcTemplate.update(
                """
                        INSERT INTO workflow_template_stages (id, template_id, stage_order, name, optional)
                        VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)
                        """,
                startStageId,
                templateId,
                0,
                "Старт команды",
                false,
                finishStageId,
                templateId,
                1,
                "Финиш команды",
                false
        );
        insertTemplateTransition(templateId, startStageId, finishStageId, false);
        return new WorkflowTemplateFixture(templateId, startStageId, finishStageId);
    }

    private void insertTemplateTransition(UUID templateId, UUID fromStageId, UUID toStageId, boolean commentRequired) {
        jdbcTemplate.update(
                """
                        INSERT INTO workflow_template_transitions (
                            template_id, from_stage_id, to_stage_id, comment_required
                        ) VALUES (?, ?, ?, ?)
                        """,
                templateId,
                fromStageId,
                toStageId,
                commentRequired
        );
    }

    private void insertOrganization(UUID id, String name, UUID teamId, UUID ownerManagerId) {
        jdbcTemplate.update(
                """
                        INSERT INTO organizations (id, name, type, team_id, owner_manager_id, version, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                name,
                "UNIVERSITY",
                teamId,
                ownerManagerId,
                0,
                OffsetDateTime.parse("2026-09-22T12:00:00+00:00")
        );
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private record WorkflowTemplateFixture(UUID id, UUID startStageId, UUID finishStageId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfiguration {
        @Bean
        @ConditionalOnMissingBean(ObjectMapper.class)
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
