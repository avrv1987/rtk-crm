package ru.rtk.crm.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.CatalogLookupService;
import ru.rtk.crm.catalog.CatalogRepository;
import ru.rtk.crm.catalog.ContactRepository;
import ru.rtk.crm.catalog.ContactService;
import ru.rtk.crm.catalog.OrganizationRepository;

// Свойства этапа (V59): правило действующего договора на своём и базовом маршруте, мягкие обязательные документы
// (комментарий, чек-лист, сигнал в списке), свойства нового этапа в карточке, проверки шаблона и права на шаблоны.
// Схема и данные — из InteractionServiceTest, правило договора включено.
@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "app.agreements.signing-stage-rule=true",
        "spring.datasource.url=jdbc:h2:mem:stage-properties;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
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
        ru.rtk.crm.attachment.AttachmentRepository.class,
        ru.rtk.crm.attachment.DocumentKinds.class,
        ProductAgreementRepository.class,
        ProductAgreementService.class,
        CommandIdempotencyRepository.class,
        InteractionService.class,
        InteractionKindRepository.class,
        InteractionKindService.class,
        WorkflowTemplateService.class,
        ru.rtk.crm.agreement.AgreementStages.class,
        ru.rtk.crm.audit.AuditJournalRepository.class,
        StagePropertiesServiceTest.Configuration.class
})
class StagePropertiesServiceTest {
    private static final UUID TEAM_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000019");

    private final CrmProfile kamA = new CrmProfile(UUID.fromString("00000000-0000-0000-0000-000000000011"), UserRole.USER, TEAM_A, 0);
    private final CrmProfile leaderA = new CrmProfile(UUID.fromString("00000000-0000-0000-0000-000000000013"), UserRole.LEADER, TEAM_A, 0);
    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);

    @Autowired
    private InteractionService interactionService;

    @Autowired
    private WorkflowTemplateService templateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TestConfiguration
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @BeforeEach
    void setUp() {
        // Полная таблица договоров создаётся раньше схемы InteractionServiceTest: там она урезана до номера.
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agreements (
                    id UUID PRIMARY KEY, organization_id UUID, number VARCHAR(100) NOT NULL, concluded_on DATE, valid_until DATE,
                    status VARCHAR(16) DEFAULT 'DRAFT' NOT NULL, status_changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
                )
                """);
        InteractionServiceTest fixture = new InteractionServiceTest();
        ReflectionTestUtils.setField(fixture, "jdbcTemplate", jdbcTemplate);
        fixture.setUp();
        jdbcTemplate.update("DELETE FROM audit_events");
        jdbcTemplate.update("MERGE INTO crm_user_profiles (id, display_name) KEY (id) VALUES (?, 'Администратор')", ADMIN);
    }

    @Test
    void agreementRuleFollowsTheStageMarkOnOwnRouteWithAnyActiveAgreementOfTheUniversity() {
        WorkflowTemplate template = templateService.create(leaderA, new WorkflowTemplateRequest("Свой маршрут", List.of(
                stage("Переговоры", 0, false, null, null),
                stage("Договор заключён", 1, true, null, null),
                stage("Работа", 2, false, null, null)
        ), chain(3), null, null), "template");
        assertThat(template.stages()).extracting(WorkflowTemplateStage::requiresAgreement).containsExactly(false, true, false);

        Interaction card = interactionService.create(kamA, create("Свой маршрут", template.id()), "card");
        assertThat(card.stages()).extracting(InteractionStage::requiresAgreement).containsExactly(false, true, false);
        Interaction second = move(card, 1, null);
        assertThatThrownBy(() -> move(second, 2, null))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> {
                    assertThat(error.field()).isEqualTo("agreement");
                    assertThat(error.getMessage()).contains("«Договор заключён»").contains("Создайте договор");
                });

        UUID agreement = agreement("Д-1", "COMPLETED", LocalDate.now().minusYears(1), null);
        assertThatThrownBy(() -> move(second, 2, null)).as("«Завершён» — не действующий").isInstanceOf(InteractionValidationException.class);
        jdbcTemplate.update("UPDATE agreements SET status = 'ACTIVE' WHERE id = ?", agreement);
        Interaction moved = move(second, 2, null);
        assertThat(moved.currentStageName()).as("договор вуза не связан с работой — правило его засчитывает").isEqualTo("Работа");
    }

    @Test
    void baseRouteKeepsTheRuleAfterMigrationMarks() {
        StagePropertiesTestSchema.markLegacyTemplateStages(jdbcTemplate);
        Interaction card = interactionService.create(kamA, create("Базовый", null), "base");
        InteractionStage signing = card.stages().stream().filter(InteractionStage::requiresAgreement).findFirst().orElseThrow();
        assertThat(signing.name()).isEqualTo("Подписание");
        assertThat(card.stages()).filteredOn(stage -> stage.lmsRole() != null)
                .extracting(InteractionStage::name, InteractionStage::lmsRole)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Обучение преподавателей", StageLmsRole.TEACHER_TRAINING),
                        org.assertj.core.groups.Tuple.tuple("Занятия", StageLmsRole.CLASSES),
                        org.assertj.core.groups.Tuple.tuple("Повышение квалификации", StageLmsRole.QUALIFICATION));
        jdbcTemplate.update("UPDATE interactions SET current_stage_id = ? WHERE id = ?", signing.id(), card.id());
        Interaction atSigning = interactionService.get(kamA, card.id());
        assertThatThrownBy(() -> move(atSigning, signing.order() + 1, null))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.field()).isEqualTo("agreement"));
        agreement("Д-2", "ACTIVE", LocalDate.now().minusDays(10), LocalDate.now().plusYears(1));
        assertThat(move(atSigning, signing.order() + 1, null).currentStageName()).isEqualTo("Передача материалов, лицензии и документации");
    }

    @Test
    void missingDocumentsRequireACommentWithTheListAndShowInChecklistAndList() {
        WorkflowTemplate template = templateService.create(leaderA, new WorkflowTemplateRequest("С документами", List.of(
                stage("Обмен", 0, false, null, List.of("CONTRACT", "ACT")),
                stage("Дальше", 1, false, null, null)
        ), chain(2), null, null), "docs-template");
        Interaction card = interactionService.create(kamA, create("Документы", template.id()), "docs-card");
        assertThat(card.documentChecklist()).extracting(Interaction.StageDocument::name, Interaction.StageDocument::attached)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Акт", false), org.assertj.core.groups.Tuple.tuple("Договор", false));
        assertThat(card.allowedTransitions()).singleElement()
                .satisfies(option -> assertThat(option.commentRequired()).as("переход вперёд требует комментария").isTrue());
        assertThat(listItem(card.id()).missingDocuments()).isTrue();

        jdbcTemplate.update("""
                INSERT INTO attachments (id, interaction_id, stage_id, original_name, media_type, size_bytes, storage_key, checksum,
                                         status, kind, created_by, created_at, updated_at)
                VALUES (?, ?, ?, 'договор.pdf', 'application/pdf', 4, ?, ?, 'CLEAN', 'CONTRACT', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), card.id(), card.currentStageId(), UUID.randomUUID(), "0".repeat(64), kamA.id());
        Interaction partly = interactionService.get(kamA, card.id());
        assertThat(partly.documentChecklist()).extracting(Interaction.StageDocument::attached).containsExactly(false, true);

        assertThatThrownBy(() -> move(partly, 1, null))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> {
                    assertThat(error.field()).isEqualTo("comment");
                    assertThat(error.getMessage()).startsWith("Не хватает документов: Акт.");
                });
        assertThatThrownBy(() -> move(partly, 1, "Не хватает документов: Акт.")).as("один перечень — не объяснение")
                .isInstanceOf(InteractionValidationException.class);
        Interaction moved = move(partly, 1, "Акт подпишут на встрече");
        assertThat(moved.currentStageName()).isEqualTo("Дальше");
        assertThat(jdbcTemplate.queryForObject("SELECT comment FROM interaction_events WHERE interaction_id = ? AND type = 'TRANSITIONED'",
                String.class, card.id())).isEqualTo("Не хватает документов: Акт. Акт подпишут на встрече");
        assertThat(listItem(card.id()).missingDocuments()).as("у нового этапа требований нет").isFalse();
    }

    @Test
    void newStageInTheCardGetsItsPropertiesAndTemplateRulesAreChecked() {
        Interaction card = interactionService.create(kamA, create("Правка этапов", null), "edit-card");
        InteractionStage current = card.stages().getFirst();
        Interaction edited = interactionService.stageEdits(kamA, card.id(), new InteractionStageEditRequest(card.version(), List.of(
                new InteractionStageEditOperation(InteractionStageEditOperation.Type.ADD_AFTER, null, current.id(), "Лекции", false,
                        true, StageLmsRole.CLASSES, List.of("CURRICULUM"))
        )), "edit");
        InteractionStage added = edited.stages().get(1);
        assertThat(added.name()).isEqualTo("Лекции");
        assertThat(added.requiresAgreement()).isTrue();
        assertThat(added.lmsRole()).isEqualTo(StageLmsRole.CLASSES);
        assertThat(added.documentKinds()).containsExactly("CURRICULUM");
        assertThat(jdbcTemplate.queryForObject("SELECT comment FROM interaction_events WHERE interaction_id = ? AND type = 'STAGES_EDITED'",
                String.class, card.id())).contains("требует действующего договора").contains("роль для LMS «Идут занятия»");
        assertThatThrownBy(() -> interactionService.stageEdits(kamA, card.id(), new InteractionStageEditRequest(edited.version(), List.of(
                new InteractionStageEditOperation(InteractionStageEditOperation.Type.ADD_AFTER, null, current.id(), "Ещё", false,
                        false, null, List.of("NO_SUCH_KIND"))
        )), "edit-bad")).isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.field()).isEqualTo("documentKinds"));

        assertThatThrownBy(() -> templateService.create(leaderA, new WorkflowTemplateRequest("Дубли", List.of(
                stage("Встреча", 0, false, null, null), stage(" встреча ", 1, false, null, null)), chain(2), null, null), "dup"))
                .hasMessageContaining("Названия этапов не должны повторяться");
        assertThatThrownBy(() -> templateService.create(leaderA, new WorkflowTemplateRequest("Ловушка", List.of(
                stage("А", 0, false, null, null), stage("Б", 1, false, null, null), stage("В", 2, false, null, null),
                stage("Г", 3, false, null, null)), List.of(
                new WorkflowTransitionInput(0, 1, false), new WorkflowTransitionInput(1, 2, false),
                new WorkflowTransitionInput(2, 1, false), new WorkflowTransitionInput(0, 3, true)), null, null), "trap"))
                .hasMessageContaining("С этапов «Б», «В» нельзя дойти до финального этапа «Г»");
        assertThatThrownBy(() -> templateService.create(leaderA, new WorkflowTemplateRequest("Неизвестный вид", List.of(
                stage("А", 0, false, null, List.of("NO_SUCH_KIND")), stage("Б", 1, false, null, null)), chain(2), null, null), "kind"))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.field()).isEqualTo("stages"));
    }

    @Test
    void templatePropertiesNeedTemplateRightsAndUsedTemplatesAreNotDeleted() {
        WorkflowTemplateRequest request = new WorkflowTemplateRequest("Права", List.of(
                stage("Один", 0, false, StageLmsRole.QUALIFICATION, null), stage("Два", 1, false, null, null)), chain(2), null, null);
        assertThatThrownBy(() -> templateService.create(kamA, request, "kam")).isInstanceOf(WorkflowTemplateAccessDeniedException.class);
        WorkflowTemplate team = templateService.create(leaderA, request, "leader");
        assertThat(team.teamId()).isEqualTo(TEAM_A);
        assertThat(team.stages().getFirst().lmsRole()).isEqualTo(StageLmsRole.QUALIFICATION);
        WorkflowTemplate common = templateService.create(admin, request, "admin");
        assertThat(common.teamId()).isNull();
        assertThatThrownBy(() -> templateService.update(leaderA, common.id(), new WorkflowTemplateRequest(null, request.stages(),
                request.transitions(), common.version(), null), "leader-common")).isInstanceOf(WorkflowTemplateAccessDeniedException.class);

        Interaction card = interactionService.create(kamA, create("По шаблону", team.id()), "used");
        assertThatThrownBy(() -> templateService.delete(leaderA, team.id(), team.version(), "delete"))
                .hasMessageContaining("незавершённые взаимодействия: 1");
        jdbcTemplate.update("UPDATE interactions SET work_status = 'COMPLETED' WHERE id = ?", card.id());
        jdbcTemplate.update("UPDATE interaction_kinds SET workflow_template_id = ? WHERE id = ?", team.id(), InteractionKindTestSchema.BASE_KIND);
        assertThatThrownBy(() -> templateService.delete(leaderA, team.id(), team.version(), "delete-2"))
                .hasMessageContaining("«Взаимодействие по ИТ-программе»");
        jdbcTemplate.update("UPDATE interaction_kinds SET workflow_template_id = NULL");
        templateService.delete(leaderA, team.id(), team.version(), "delete-3");
    }

    private Interaction move(Interaction card, int order, String comment) {
        InteractionStage target = card.stages().stream().filter(stage -> stage.order() == order).findFirst().orElseThrow();
        return interactionService.transition(kamA, card.id(), new InteractionTransitionRequest(card.version(), target.id(), comment),
                "move-" + UUID.randomUUID());
    }

    private InteractionSummary listItem(UUID interactionId) {
        return interactionService.list(kamA, InteractionFilter.from(ORGANIZATION_A, null, null, null, null),
                        InteractionQuery.from(0, 50, "createdAt,asc"))
                .items().stream().filter(item -> item.id().equals(interactionId)).findFirst().orElseThrow();
    }

    private UUID agreement(String number, String status, LocalDate concludedOn, LocalDate validUntil) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO agreements (id, organization_id, number, concluded_on, valid_until, status, status_changed_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)", id, ORGANIZATION_A, number, concludedOn, validUntil, status, OffsetDateTime.now());
        return id;
    }

    private static WorkflowStageInput stage(String name, int order, boolean requiresAgreement, StageLmsRole role, List<String> documents) {
        return new WorkflowStageInput(name, order, false, null, requiresAgreement, role, documents);
    }

    private static List<WorkflowTransitionInput> chain(int stages) {
        List<WorkflowTransitionInput> transitions = new java.util.ArrayList<>();
        for (int order = 0; order + 1 < stages; order++) {
            transitions.add(new WorkflowTransitionInput(order, order + 1, false));
            transitions.add(new WorkflowTransitionInput(order + 1, order, false));
        }
        return transitions;
    }

    private static InteractionCreateRequest create(String title, UUID templateId) {
        return new InteractionCreateRequest(ORGANIZATION_A, title, null, null, List.of(), null, List.of(), null, templateId, null);
    }
}
