package ru.rtk.crm.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:workflow-template;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        WorkflowTemplateRepository.class,
        CommandIdempotencyRepository.class,
        WorkflowTemplateService.class,
        WorkflowTemplateServiceTest.JsonConfiguration.class
})
class WorkflowTemplateServiceTest {
    private static final UUID TEAM_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final CrmProfile leaderA = new CrmProfile(
            UUID.fromString("00000000-0000-0000-0000-000000000011"), UserRole.LEADER, TEAM_A, 0
    );
    private final CrmProfile leaderB = new CrmProfile(
            UUID.fromString("00000000-0000-0000-0000-000000000012"), UserRole.LEADER, TEAM_B, 0
    );
    private final CrmProfile userA = new CrmProfile(
            UUID.fromString("00000000-0000-0000-0000-000000000013"), UserRole.USER, TEAM_A, 0
    );
    private final CrmProfile admin = new CrmProfile(
            UUID.fromString("00000000-0000-0000-0000-000000000014"), UserRole.ADMIN, null, 0
    );

    @Autowired
    private WorkflowTemplateService workflowTemplateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        createSchema();
        jdbcTemplate.update("DELETE FROM workflow_template_transitions");
        jdbcTemplate.update("DELETE FROM workflow_template_stages");
        jdbcTemplate.update("DELETE FROM workflow_templates");
        jdbcTemplate.update("DELETE FROM command_idempotency_records");
    }

    @Test
    void validatesTemplateGraphAndClonesTeamScopedCrudWithReplay() {
        assertThatThrownBy(() -> workflowTemplateService.create(
                leaderA,
                request("Ошибка графа", List.of()),
                "template-invalid"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("transitions");
        });
        assertThatThrownBy(() -> workflowTemplateService.create(
                leaderA,
                new WorkflowTemplateRequest(
                        "Висячее ребро",
                        List.of(
                                new WorkflowStageInput("Старт", 0, false),
                                new WorkflowStageInput("Финиш", 1, false)
                        ),
                        List.of(new WorkflowTransitionInput(0, 2, false)),
                        null
                ),
                "template-dangling"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("transitions");
        });
        assertThatThrownBy(() -> workflowTemplateService.create(
                leaderA,
                new WorkflowTemplateRequest(
                        "Дублирующее ребро",
                        List.of(
                                new WorkflowStageInput("Старт", 0, false),
                                new WorkflowStageInput("Финиш", 1, false)
                        ),
                        List.of(
                                new WorkflowTransitionInput(0, 1, false),
                                new WorkflowTransitionInput(0, 1, false)
                        ),
                        null
                ),
                "template-duplicate"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("transitions");
        });
        assertThatThrownBy(() -> WorkflowGraph.validate(
                List.of(new InteractionStage(UUID.randomUUID(), "Без старта", 1, false)),
                List.of(),
                "transitions"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("transitions");
        });

        WorkflowTemplate created = workflowTemplateService.create(
                leaderA,
                request("Процесс команды", List.of(new WorkflowTransitionInput(0, 1, false))),
                "template-create"
        );
        WorkflowTemplate replayed = workflowTemplateService.create(
                leaderA,
                request("Процесс команды", List.of(new WorkflowTransitionInput(0, 1, false))),
                "template-create"
        );

        assertThat(created.teamId()).isEqualTo(TEAM_A);
        assertThat(created.defaultTemplate()).isFalse();
        assertThat(created.stages()).hasSize(2);
        assertThat(created.transitions()).singleElement().satisfies(transition -> {
            assertThat(transition.fromStageId()).isEqualTo(created.stages().getFirst().id());
            assertThat(transition.toStageId()).isEqualTo(created.stages().get(1).id());
        });
        assertThat(replayed).isEqualTo(created);
        assertThat(workflowTemplateService.listAvailable(userA, WorkflowTemplateQuery.from(0, 25, "name,asc"))
                .items()).extracting(WorkflowTemplate::id).contains(created.id());
        assertThatThrownBy(() -> workflowTemplateService.getManaged(leaderB, created.id()))
                .isInstanceOf(WorkflowTemplateNotFoundException.class);
        assertThatThrownBy(() -> workflowTemplateService.getManaged(userA, created.id()))
                .isInstanceOf(WorkflowTemplateAccessDeniedException.class);

        assertThatThrownBy(() -> workflowTemplateService.update(
                leaderA,
                created.id(),
                new WorkflowTemplateRequest(
                        "Неполная замена",
                        List.of(
                                new WorkflowStageInput("Старт", 0, false),
                                new WorkflowStageInput("Финиш", 1, false)
                        ),
                        null,
                        created.version()
                ),
                "template-update-invalid"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("transitions");
        });

        WorkflowTemplateRequest updateRequest = new WorkflowTemplateRequest(
                "Переименованный процесс",
                List.of(
                        new WorkflowStageInput("Старт", 0, false),
                        new WorkflowStageInput("Проверка", 1, true),
                        new WorkflowStageInput("Финиш", 2, false)
                ),
                List.of(
                        new WorkflowTransitionInput(0, 1, false),
                        new WorkflowTransitionInput(1, 2, true)
                ),
                created.version()
        );
        WorkflowTemplate updated = workflowTemplateService.update(
                leaderA,
                created.id(),
                updateRequest,
                "template-update"
        );
        WorkflowTemplate updatedReplay = workflowTemplateService.update(
                leaderA,
                created.id(),
                updateRequest,
                "template-update"
        );

        assertThat(updated.name()).isEqualTo("Переименованный процесс");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.stages()).hasSize(3);
        assertThat(updated.transitions()).hasSize(2);
        assertThat(updatedReplay).isEqualTo(updated);
        workflowTemplateService.delete(leaderA, updated.id(), updated.version(), "template-delete");
        assertThat(workflowTemplateService.listAvailable(userA, WorkflowTemplateQuery.from(0, 25, "name,asc"))
                .items()).isEmpty();
    }

    @Test
    void administratorManagesOnlyGlobalTemplatesAndLeaderManagesOnlyOwnTeamTemplates() {
        WorkflowTemplate global = workflowTemplateService.create(
                admin,
                request("Глобальный процесс", List.of(new WorkflowTransitionInput(0, 1, false))),
                "global-template"
        );
        WorkflowTemplate team = workflowTemplateService.create(
                leaderA,
                request("Процесс команды", List.of(new WorkflowTransitionInput(0, 1, false))),
                "team-template"
        );

        assertThat(global.teamId()).isNull();
        assertThat(workflowTemplateService.listAvailable(userA, WorkflowTemplateQuery.from(0, 25, "name,asc"))
                .items()).extracting(WorkflowTemplate::id).containsExactlyInAnyOrder(global.id(), team.id());
        assertThat(workflowTemplateService.listManaged(leaderA, WorkflowTemplateQuery.from(0, 25, "name,asc"))
                .items()).extracting(WorkflowTemplate::id).containsExactly(team.id());
        assertThat(workflowTemplateService.listManaged(admin, WorkflowTemplateQuery.from(0, 25, "name,asc"))
                .items()).extracting(WorkflowTemplate::id).containsExactly(global.id());
    }

    @Test
    void rejectsUncommentedSkipsReturnsAndDeadEndStages() {
        List<WorkflowStageInput> stages = List.of(
                new WorkflowStageInput("Старт", 0, false),
                new WorkflowStageInput("Обязательная проверка", 1, false),
                new WorkflowStageInput("Финиш", 2, false)
        );

        assertThatThrownBy(() -> workflowTemplateService.create(
                leaderA,
                new WorkflowTemplateRequest("Пропуск без комментария", stages, List.of(
                        new WorkflowTransitionInput(0, 1, false),
                        new WorkflowTransitionInput(1, 2, false),
                        new WorkflowTransitionInput(0, 2, false)
                ), null),
                "template-skip-without-comment"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("transitions");
            assertThat(exception).hasMessageContaining("обязательного комментария");
        });
        assertThatThrownBy(() -> workflowTemplateService.create(
                leaderA,
                new WorkflowTemplateRequest("Возврат без комментария", stages, List.of(
                        new WorkflowTransitionInput(0, 1, false),
                        new WorkflowTransitionInput(1, 2, false),
                        new WorkflowTransitionInput(2, 0, false)
                ), null),
                "template-return-without-comment"
        )).isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> workflowTemplateService.create(
                leaderA,
                new WorkflowTemplateRequest("Тупиковый этап", stages, List.of(
                        new WorkflowTransitionInput(0, 1, false),
                        new WorkflowTransitionInput(0, 2, true)
                ), null),
                "template-dead-end"
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception).hasMessageContaining("исходящий переход");
        });

        WorkflowTemplate commentedSkip = workflowTemplateService.create(
                leaderA,
                new WorkflowTemplateRequest("Пропуск с комментарием", stages, List.of(
                        new WorkflowTransitionInput(0, 1, false),
                        new WorkflowTransitionInput(1, 2, false),
                        new WorkflowTransitionInput(0, 2, true),
                        new WorkflowTransitionInput(2, 0, true)
                ), null),
                "template-commented-skip"
        );
        assertThat(commentedSkip.transitions()).hasSize(4);
    }

    @Test
    void leaderSeesDefaultTemplateToCopyButCannotChangeIt() {
        WorkflowTemplate global = workflowTemplateService.create(
                admin,
                request("Базовый процесс", List.of(new WorkflowTransitionInput(0, 1, false))),
                "default-template"
        );
        jdbcTemplate.update("UPDATE workflow_templates SET default_template = TRUE WHERE id = ?", global.id());
        WorkflowTemplate otherGlobal = workflowTemplateService.create(
                admin,
                request("Другой общий процесс", List.of(new WorkflowTransitionInput(0, 1, false))),
                "other-global-template"
        );
        WorkflowTemplate foreignTeam = workflowTemplateService.create(
                leaderB,
                request("Процесс команды Б", List.of(new WorkflowTransitionInput(0, 1, false))),
                "foreign-team-template"
        );

        List<WorkflowTemplate> managed = workflowTemplateService
                .listManaged(leaderA, WorkflowTemplateQuery.from(0, 25, "name,asc"))
                .items();

        assertThat(managed).extracting(WorkflowTemplate::id).containsExactly(global.id());
        assertThat(managed.getFirst().defaultTemplate()).isTrue();
        assertThat(managed).extracting(WorkflowTemplate::id).doesNotContain(foreignTeam.id());
        assertThat(workflowTemplateService.getManaged(leaderA, global.id())).isEqualTo(managed.getFirst());
        assertThatThrownBy(() -> workflowTemplateService.getManaged(leaderA, otherGlobal.id()))
                .isInstanceOf(WorkflowTemplateAccessDeniedException.class);
        assertThatThrownBy(() -> workflowTemplateService.update(
                leaderA,
                global.id(),
                new WorkflowTemplateRequest("Захват базового", null, null, 0),
                "leader-update-default"
        )).isInstanceOf(WorkflowTemplateAccessDeniedException.class);
        assertThatThrownBy(() -> workflowTemplateService.delete(leaderA, global.id(), 0, "leader-delete-default"))
                .isInstanceOf(WorkflowTemplateAccessDeniedException.class);

        WorkflowTemplate copy = workflowTemplateService.create(
                leaderA,
                new WorkflowTemplateRequest(
                        "Базовый процесс (копия команды)",
                        List.of(
                                new WorkflowStageInput("Старт", 0, false),
                                new WorkflowStageInput("Финиш", 1, false)
                        ),
                        List.of(new WorkflowTransitionInput(0, 1, false)),
                        null
                ),
                "leader-copy-default"
        );

        assertThat(copy.teamId()).isEqualTo(TEAM_A);
        assertThat(copy.defaultTemplate()).isFalse();
        assertThat(workflowTemplateService.listManaged(leaderA, WorkflowTemplateQuery.from(0, 25, "name,asc")).items())
                .extracting(WorkflowTemplate::id)
                .containsExactly(global.id(), copy.id());
    }

    private WorkflowTemplateRequest request(String name, List<WorkflowTransitionInput> transitions) {
        return new WorkflowTemplateRequest(
                name,
                List.of(
                        new WorkflowStageInput("Старт", 0, false),
                        new WorkflowStageInput("Финиш", 1, false)
                ),
                transitions,
                null
        );
    }

    private void createSchema() {
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
                CREATE TABLE IF NOT EXISTS command_idempotency_records (
                    id UUID PRIMARY KEY,
                    actor_profile_id UUID NOT NULL,
                    operation VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    request_fingerprint CHAR(64) NOT NULL,
                    result_json VARCHAR(10000),
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (actor_profile_id, operation, idempotency_key)
                )
                """);
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
