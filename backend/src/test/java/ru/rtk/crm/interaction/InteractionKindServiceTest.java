package ru.rtk.crm.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import ru.rtk.crm.access.AdminCrmProfileAccessDeniedException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.CatalogLookupService;
import ru.rtk.crm.catalog.CatalogRepository;
import ru.rtk.crm.catalog.ContactRepository;
import ru.rtk.crm.catalog.ContactService;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKind;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindRequest;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindResolution;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindRule;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindRuleRequest;
import ru.rtk.crm.interaction.InteractionKindModels.KindSource;
import ru.rtk.crm.interaction.InteractionKindModels.TeamTemplateRequest;
import ru.rtk.crm.interaction.InteractionKindModels.TemplateSource;

// Вид взаимодействия и правила: справочник, права, автовыбор вида и маршрута при создании, импорте и из источника,
// смена вида без смены маршрута, обязательность будущего этапа. Схема и данные — из InteractionServiceTest.
@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "app.agreements.signing-stage-rule=false",
        "spring.datasource.url=jdbc:h2:mem:interaction-kinds;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
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
        ProductAgreementRepository.class,
        ProductAgreementService.class,
        CommandIdempotencyRepository.class,
        InteractionService.class,
        InteractionKindRepository.class,
        InteractionKindService.class,
        ru.rtk.crm.agreement.AgreementStages.class,
        ru.rtk.crm.audit.AuditJournalRepository.class,
        InteractionKindServiceTest.Configuration.class
})
class InteractionKindServiceTest {
    private static final UUID TEAM_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ORGANIZATION_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ORGANIZATION_B = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000019");

    private final CrmProfile kamA = new CrmProfile(UUID.fromString("00000000-0000-0000-0000-000000000011"), UserRole.USER, TEAM_A, 0);
    private final CrmProfile kamB = new CrmProfile(UUID.fromString("00000000-0000-0000-0000-000000000012"), UserRole.USER, TEAM_B, 0);
    private final CrmProfile leaderA = new CrmProfile(UUID.fromString("00000000-0000-0000-0000-000000000013"), UserRole.LEADER, TEAM_A, 0);
    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);

    @Autowired
    private InteractionKindService kindService;

    @Autowired
    private InteractionService interactionService;

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
        InteractionServiceTest fixture = new InteractionServiceTest();
        ReflectionTestUtils.setField(fixture, "jdbcTemplate", jdbcTemplate);
        fixture.setUp();
        jdbcTemplate.update("DELETE FROM audit_events");
        jdbcTemplate.update("MERGE INTO crm_user_profiles (id, display_name) KEY (id) VALUES (?, 'Администратор')", ADMIN);
    }

    @Test
    void kindTemplateIsUsedForNewCardAndManualTemplateStillWins() {
        UUID courseRoute = template(null, "Повышение квалификации", "Заявка", "Курс");
        InteractionKind kind = kindService.create(admin, request("Повышение квалификации", courseRoute, null), "kind-create");

        Interaction byKind = interactionService.create(kamA, create("ПК для кафедры", kind.id(), null, List.of()), "card-kind");
        assertThat(byKind.kind().name()).isEqualTo("Повышение квалификации");
        assertThat(byKind.route().templateId()).isEqualTo(courseRoute);
        assertThat(byKind.stages()).extracting(InteractionStage::name).containsExactly("Заявка", "Курс");

        UUID manual = template(null, "Ручной", "Один", "Два");
        Interaction byHand = interactionService.create(kamA, create("Ручной маршрут", kind.id(), manual, List.of()), "card-manual");
        assertThat(byHand.kind().id()).isEqualTo(kind.id());
        assertThat(byHand.route().templateId()).isEqualTo(manual);

        Interaction plain = interactionService.create(kamA, create("Без вида", null, null, List.of()), "card-plain");
        assertThat(plain.kind().id()).isEqualTo(InteractionKindTestSchema.BASE_KIND);
        assertThat(plain.stages()).hasSize(13);
        assertThat(auditDetails("INTERACTION_KIND_CHANGED")).singleElement().asString().startsWith("добавлен; «Повышение квалификации»");
    }

    @Test
    void teamTemplateOfKindIsPreferredOnlyForThatTeam() {
        UUID common = template(null, "Общий ПК", "Заявка", "Курс");
        UUID teamA = template(TEAM_A, "ПК команды А", "Звонок", "Договор", "Курс");
        InteractionKind kind = kindService.create(admin, request("Повышение квалификации", common, null), "kind");

        InteractionKind overridden = kindService.setTeamTemplate(leaderA, kind.id(), new TeamTemplateRequest(teamA), "team-template");
        assertThat(overridden.teamTemplateId()).isEqualTo(teamA);

        assertThat(kindService.resolve(kamA, ORGANIZATION_A, kind.id(), null, List.of()))
                .extracting(InteractionKindResolution::templateId, InteractionKindResolution::templateSource)
                .containsExactly(teamA, TemplateSource.TEAM_KIND);
        assertThat(kindService.resolve(kamB, ORGANIZATION_B, kind.id(), null, List.of()))
                .extracting(InteractionKindResolution::templateId, InteractionKindResolution::templateSource)
                .containsExactly(common, TemplateSource.KIND);
        assertThat(interactionService.create(kamA, create("ПК А", kind.id(), null, List.of()), "a").route().templateId())
                .isEqualTo(teamA);
        assertThat(interactionService.create(kamB, createB("ПК Б", kind.id()), "b").route().templateId()).isEqualTo(common);

        kindService.setTeamTemplate(leaderA, kind.id(), new TeamTemplateRequest(null), "team-reset");
        assertThat(kindService.resolve(kamA, ORGANIZATION_A, kind.id(), null, List.of()).templateSource()).isEqualTo(TemplateSource.KIND);
    }

    @Test
    void rulesPickKindByOrganizationProgramAndProductWithPriority() {
        UUID product = insertProduct("1С:Предприятие");
        UUID program = insertProgram("Анализ данных");
        InteractionKind training = kindService.create(admin, request("Обучение преподавателей", null, null), "k1");
        InteractionKind courses = kindService.create(admin, request("Повышение квалификации", null, null), "k2");
        kindService.createRule(admin, new InteractionKindRuleRequest(null, courses.id(), OrganizationType.UNIVERSITY, null, null, 50), "r1");
        InteractionKindRule productRule = kindService.createRule(admin,
                new InteractionKindRuleRequest(null, training.id(), OrganizationType.UNIVERSITY, null, product, 10), "r2");
        kindService.createRule(admin, new InteractionKindRuleRequest(null, training.id(), null, program, null, 50), "r3");

        assertThat(kindService.resolve(kamA, ORGANIZATION_A, null, null, List.of(product)))
                .extracting(InteractionKindResolution::kindId, InteractionKindResolution::kindSource)
                .containsExactly(training.id(), KindSource.RULE);
        // Подходят два правила с приоритетом 50 и одним условием каждое: решает порядок вида в справочнике.
        assertThat(kindService.resolve(kamA, ORGANIZATION_A, null, program, List.of()).kindId()).isEqualTo(training.id());
        assertThat(kindService.resolve(kamA, ORGANIZATION_A, null, null, List.of()).kindId()).isEqualTo(courses.id());
        assertThat(kindService.resolve(kamA, ORGANIZATION_A, courses.id(), null, List.of(product)).kindSource())
                .isEqualTo(KindSource.SELECTED);

        jdbcTemplate.update("UPDATE organizations SET type = 'SCHOOL' WHERE id = ?", ORGANIZATION_A);
        assertThat(kindService.resolve(kamA, ORGANIZATION_A, null, null, List.of(product)))
                .extracting(InteractionKindResolution::kindId, InteractionKindResolution::kindSource)
                .containsExactly(InteractionKindTestSchema.BASE_KIND, KindSource.FIRST_ACTIVE);
        jdbcTemplate.update("UPDATE organizations SET type = 'UNIVERSITY' WHERE id = ?", ORGANIZATION_A);

        Interaction created = interactionService.create(kamA, create("С продуктом", null, null, List.of(product)), "card");
        assertThat(created.kind().id()).isEqualTo(training.id());

        kindService.deleteRule(admin, productRule.id(), productRule.version(), "delete-rule");
        assertThat(kindService.rules(admin)).extracting(InteractionKindRule::kindId).containsExactly(training.id(), courses.id());

        assertThat(auditDetails("INTERACTION_KIND_RULE_CHANGED")).hasSize(4)
                .anySatisfy(details -> assertThat(details).startsWith("удалено: вид «Обучение преподавателей», тип UNIVERSITY"));
    }

    @Test
    void importAndSourceRecordsGetKindAndRouteByRules() {
        UUID product = insertProduct("Astra Linux");
        UUID program = insertProgram("Python");
        UUID productRoute = template(null, "Внедрение продукта", "Пилот", "Лицензия");
        UUID programRoute = template(null, "Обучение", "Набор", "Занятия");
        InteractionKind byProduct = kindService.create(admin, request("ИТ-продукт", productRoute, null), "k1");
        InteractionKind byProgram = kindService.create(admin, request("ИТ-программа", programRoute, null), "k2");
        kindService.createRule(admin, new InteractionKindRuleRequest(null, byProduct.id(), null, null, product, 10), "r1");
        kindService.createRule(admin, new InteractionKindRuleRequest(null, byProgram.id(), null, program, null, 20), "r2");
        OffsetDateTime now = OffsetDateTime.now();

        UUID imported = interactionService.createImportedInteraction(ORGANIZATION_A, kamA.id(), "Импорт", product, kamA.id(),
                UUID.randomUUID(), now);
        UUID fromSource = interactionService.createSourceInteraction(ORGANIZATION_A, kamA.id(), "Заявка сайта", program, List.of(),
                List.of(), null, kamA.id(), UUID.randomUUID(), now);
        UUID plainImport = interactionService.createImportedInteraction(ORGANIZATION_A, kamA.id(), "Без продукта", null, kamA.id(),
                UUID.randomUUID(), now);

        assertThat(interactionService.get(kamA, imported))
                .satisfies(work -> {
                    assertThat(work.kind().id()).isEqualTo(byProduct.id());
                    assertThat(work.route().templateId()).isEqualTo(productRoute);
                });
        assertThat(interactionService.get(kamA, fromSource))
                .satisfies(work -> {
                    assertThat(work.kind().id()).isEqualTo(byProgram.id());
                    assertThat(work.stages()).extracting(InteractionStage::name).containsExactly("Набор", "Занятия");
                });
        assertThat(interactionService.get(kamA, plainImport).kind().id()).isEqualTo(InteractionKindTestSchema.BASE_KIND);
        assertThat(interactionService.list(kamA, InteractionFilter.from(null, null, null, null).withKind(byProgram.id()),
                InteractionQuery.from(0, 25, "updatedAt,desc")).items())
                .extracting(InteractionSummary::id, InteractionSummary::kindName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(fromSource, "ИТ-программа"));
    }

    @Test
    void onlyAdministratorManagesKindsAndRulesAndOnlyLeaderSetsTeamTemplate() {
        InteractionKindRequest kind = request("Новый вид", null, null);
        assertThatThrownBy(() -> kindService.create(kamA, kind, "kam")).isInstanceOf(AdminCrmProfileAccessDeniedException.class);
        assertThatThrownBy(() -> kindService.create(leaderA, kind, "leader")).isInstanceOf(AdminCrmProfileAccessDeniedException.class);
        assertThatThrownBy(() -> kindService.rules(leaderA)).isInstanceOf(AdminCrmProfileAccessDeniedException.class);
        assertThatThrownBy(() -> kindService.managed(kamA)).isInstanceOf(WorkflowTemplateAccessDeniedException.class);
        assertThat(kindService.managed(leaderA)).extracting(InteractionKind::id).containsExactly(InteractionKindTestSchema.BASE_KIND);
        assertThatThrownBy(() -> kindService.setTeamTemplate(admin, InteractionKindTestSchema.BASE_KIND, new TeamTemplateRequest(null), "a"))
                .isInstanceOf(WorkflowTemplateAccessDeniedException.class);
        assertThatThrownBy(() -> kindService.setTeamTemplate(kamA, InteractionKindTestSchema.BASE_KIND, new TeamTemplateRequest(null), "k"))
                .isInstanceOf(WorkflowTemplateAccessDeniedException.class);
        UUID foreign = template(TEAM_B, "Шаблон команды Б", "Раз", "Два");
        assertThatThrownBy(() -> kindService.setTeamTemplate(leaderA, InteractionKindTestSchema.BASE_KIND, new TeamTemplateRequest(foreign), "f"))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.field()).isEqualTo("templateId"));
        assertThatThrownBy(() -> kindService.create(admin, request("Командный", foreign, null), "team-template"))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.field()).isEqualTo("templateId"));
        assertThatThrownBy(() -> kindService.create(admin, request("взаимодействие по ИТ-программе", null, null), "dup"))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.field()).isEqualTo("name"));
        assertThatThrownBy(() -> kindService.update(admin, InteractionKindTestSchema.BASE_KIND,
                new InteractionKindRequest(0, "Взаимодействие по ИТ-программе", null, false, null, 0), "off"))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.field()).isEqualTo("active"));
        assertThatThrownBy(() -> kindService.createRule(admin,
                new InteractionKindRuleRequest(null, InteractionKindTestSchema.BASE_KIND, null, null, null, 10), "empty"))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.field()).isEqualTo("organizationType"));
    }

    @Test
    void inactiveKindIsNotOfferedAndCannotBeChosen() {
        InteractionKind kind = kindService.create(admin, request("Архивный", null, null), "k");
        InteractionKind off = kindService.update(admin, kind.id(), new InteractionKindRequest(kind.version(), "Архивный", null, false, null, 5), "off");
        assertThat(off.active()).isFalse();
        assertThat(kindService.available(kamA)).extracting(InteractionKind::id).containsExactly(InteractionKindTestSchema.BASE_KIND);
        assertThatThrownBy(() -> interactionService.create(kamA, create("Карточка", kind.id(), null, List.of()), "c"))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.field()).isEqualTo("kindId"));
        assertThatThrownBy(() -> kindService.update(admin, kind.id(), new InteractionKindRequest(0, "Архивный", null, true, null, 5), "stale"))
                .isInstanceOf(InteractionConflictException.class);
    }

    @Test
    void changingKindKeepsTheRouteAndIsWrittenToHistory() {
        InteractionKind other = kindService.create(admin, request("Повышение квалификации", template(null, "ПК", "А", "Б"), null), "k");
        Interaction created = interactionService.create(kamA, create("Карточка", null, null, List.of()), "c");
        InteractionPlanRequest change = new InteractionPlanRequest(created.version());
        change.setKindId(other.id());

        Interaction changed = interactionService.updatePlan(kamA, created.id(), change, "kind-change");

        assertThat(changed.kind().id()).isEqualTo(other.id());
        assertThat(changed.stages()).extracting(InteractionStage::id).isEqualTo(created.stages().stream().map(InteractionStage::id).toList());
        assertThat(changed.route()).isEqualTo(created.route());
        assertThat(interactionService.events(kamA, created.id()).getLast().comment())
                .isEqualTo("Вид взаимодействия: «Взаимодействие по ИТ-программе» → «Повышение квалификации»; маршрут не изменён");
    }

    // Аудит видов: после смены вида маршрут помечен как оставшийся от прежнего вида, пока он не совпадает с маршрутом вида.
    @Test
    void routeAfterKindChangeIsMarkedAsFromPreviousKind() {
        InteractionKind other = kindService.create(admin, request("Повышение квалификации", template(null, "ПК", "А", "Б"), null), "k");
        Interaction created = interactionService.create(kamA, create("Карточка ПК", other.id(), null, List.of()), "c");
        assertThat(created.route().fromPreviousKind()).isFalse();
        InteractionPlanRequest change = new InteractionPlanRequest(created.version());
        change.setKindId(InteractionKindTestSchema.BASE_KIND);

        Interaction changed = interactionService.updatePlan(kamA, created.id(), change, "kind-change");

        assertThat(changed.route().templateName()).isEqualTo("ПК");
        assertThat(changed.route().fromPreviousKind()).isTrue();
        jdbcTemplate.update("UPDATE interaction_kinds SET workflow_template_id = ? WHERE id = ?", changed.route().templateId(),
                InteractionKindTestSchema.BASE_KIND);
        assertThat(interactionService.get(kamA, created.id()).route().fromPreviousKind())
                .as("маршрут совпал с маршрутом нынешнего вида").isFalse();
    }

    @Test
    void optionalityChangesOnlyForFutureStagesAndRebuildsSkips() {
        Interaction created = interactionService.create(kamA, create("Карточка", null, null, List.of()), "c");
        InteractionStage meeting = created.stages().get(2);
        InteractionStage current = created.stages().getFirst();

        Interaction optional = interactionService.stageEdits(kamA, created.id(), new InteractionStageEditRequest(created.version(),
                List.of(new InteractionStageEditOperation(InteractionStageEditOperation.Type.SET_OPTIONAL, meeting.id(), null, null, true))),
                "optional");

        assertThat(optional.stages().get(2).optional()).isTrue();
        // Необязательный этап можно пропустить с комментарием: появился переход «Уточнение актуальности» → «Обмен документами».
        assertThat(optional.transitions()).anySatisfy(transition -> {
            assertThat(transition.fromStageId()).isEqualTo(created.stages().get(1).id());
            assertThat(transition.toStageId()).isEqualTo(created.stages().get(3).id());
            assertThat(transition.commentRequired()).isTrue();
        });
        assertThat(interactionService.events(kamA, created.id()).getLast().comment()).isEqualTo("Этап «Встреча» стал необязательным");

        Interaction required = interactionService.stageEdits(kamA, created.id(), new InteractionStageEditRequest(optional.version(),
                List.of(new InteractionStageEditOperation(InteractionStageEditOperation.Type.SET_OPTIONAL, meeting.id(), null, null, false))),
                "required");
        assertThat(required.stages().get(2).optional()).isFalse();
        assertThat(required.transitions()).noneSatisfy(transition -> {
            assertThat(transition.fromStageId()).isEqualTo(created.stages().get(1).id());
            assertThat(transition.toStageId()).isEqualTo(created.stages().get(3).id());
        });
        assertThatThrownBy(() -> interactionService.stageEdits(kamA, created.id(), new InteractionStageEditRequest(required.version(),
                List.of(new InteractionStageEditOperation(InteractionStageEditOperation.Type.SET_OPTIONAL, current.id(), null, null, true))),
                "current"))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.field()).isEqualTo("id"));
    }

    // Н-16 (2.5): новый вид с порядком 0 не становится видом по умолчанию; вид по умолчанию назначается явно и один.
    @Test
    void defaultKindIsChosenExplicitlyAndNewKindWithZeroOrderDoesNotTakeOver() {
        InteractionKind first = kindService.create(admin, request("Новый с порядком 0", null, 0), "zero");
        assertThat(kindService.resolve(kamA, ORGANIZATION_A, null, null, List.of()).kindId())
                .isEqualTo(InteractionKindTestSchema.BASE_KIND);
        InteractionKind base = kindService.managed(admin).stream()
                .filter(kind -> kind.id().equals(InteractionKindTestSchema.BASE_KIND)).findFirst().orElseThrow();
        assertThat(base.defaultKind()).isTrue();
        assertThatThrownBy(() -> kindService.update(admin, base.id(),
                new InteractionKindRequest(base.version(), base.name(), null, true, null, base.sortOrder(), false), "unset"))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.field()).isEqualTo("defaultKind"));

        InteractionKind chosen = kindService.update(admin, first.id(),
                new InteractionKindRequest(first.version(), first.name(), null, true, null, 0, true), "make-default");
        assertThat(chosen.defaultKind()).isTrue();
        assertThat(kindService.managed(admin)).filteredOn(InteractionKind::defaultKind).extracting(InteractionKind::id)
                .containsExactly(first.id());
        assertThat(kindService.resolve(kamA, ORGANIZATION_A, null, null, List.of()).kindId()).isEqualTo(first.id());
        assertThatThrownBy(() -> kindService.update(admin, first.id(),
                new InteractionKindRequest(chosen.version(), first.name(), null, false, null, 0), "deactivate-default"))
                .isInstanceOf(InteractionValidationException.class);
    }

    // Н-15 (2.5): правило с теми же условиями и приоритетом не сохраняется — одно из двух никогда бы не сработало.
    @Test
    void ruleTwinWithSameConditionsAndPriorityIsRejected() {
        InteractionKind courses = kindService.create(admin, request("Курсы", null, 5), "courses");
        kindService.createRule(admin, new InteractionKindRuleRequest(null, courses.id(), OrganizationType.COLLEGE, null, null, 50), "rule");
        assertThatThrownBy(() -> kindService.createRule(admin,
                new InteractionKindRuleRequest(null, courses.id(), OrganizationType.COLLEGE, null, null, 50), "twin"))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.getMessage()).contains("уже есть"));
        assertThatThrownBy(() -> kindService.createRule(admin, new InteractionKindRuleRequest(null, InteractionKindTestSchema.BASE_KIND,
                OrganizationType.COLLEGE, null, null, 50), "rival"))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.getMessage()).contains("никогда"));
        kindService.createRule(admin, new InteractionKindRuleRequest(null, InteractionKindTestSchema.BASE_KIND,
                OrganizationType.COLLEGE, null, null, 40), "other-priority");
    }

    // Аудит видов, Н-2 и неудобство 10: отключённое правило не действует и двойником не считается, правило отключённого
    // вида тоже; отключённый вид остаётся в списке для фильтров с числом незавершённых карточек (С-2).
    @Test
    void disabledRuleOrKindIsNotATwinAndDisabledKindStaysInFilterList() {
        InteractionKind courses = kindService.create(admin, request("Курсы", null, 5), "courses");
        InteractionKindRule rule = kindService.createRule(admin,
                new InteractionKindRuleRequest(null, courses.id(), OrganizationType.UNIVERSITY, null, null, 50), "rule");
        assertThat(kindService.resolve(kamA, ORGANIZATION_A, null, null, List.of()).kindId()).isEqualTo(courses.id());

        InteractionKindRule disabled = kindService.updateRule(admin, rule.id(), new InteractionKindRuleRequest(rule.version(),
                courses.id(), OrganizationType.UNIVERSITY, null, null, 50, false), "disable-rule");
        assertThat(disabled.active()).isFalse();
        assertThat(kindService.resolve(kamA, ORGANIZATION_A, null, null, List.of()).kindId()).isEqualTo(InteractionKindTestSchema.BASE_KIND);
        assertThat(auditDetails("INTERACTION_KIND_RULE_CHANGED")).anySatisfy(details -> assertThat(details).endsWith("отключено"));
        InteractionKindRule rival = kindService.createRule(admin, new InteractionKindRuleRequest(null, InteractionKindTestSchema.BASE_KIND,
                OrganizationType.UNIVERSITY, null, null, 50), "rival");
        assertThatThrownBy(() -> kindService.updateRule(admin, disabled.id(), new InteractionKindRuleRequest(disabled.version(),
                courses.id(), OrganizationType.UNIVERSITY, null, null, 50, true), "enable-rule"))
                .as("включение правила снова проверяет двойников").isInstanceOf(InteractionValidationException.class);
        kindService.deleteRule(admin, rival.id(), rival.version(), "delete-rival");
        kindService.updateRule(admin, disabled.id(), new InteractionKindRuleRequest(disabled.version(),
                courses.id(), OrganizationType.UNIVERSITY, null, null, 50, true), "enable-rule");

        interactionService.create(kamA, create("Курсы для кафедры", courses.id(), null, List.of()), "courses-card");
        InteractionKind off = kindService.update(admin, courses.id(),
                new InteractionKindRequest(courses.version(), courses.name(), null, false, null, 5), "courses-off");
        kindService.createRule(admin, new InteractionKindRuleRequest(null, InteractionKindTestSchema.BASE_KIND,
                OrganizationType.UNIVERSITY, null, null, 50), "after-kind-off");

        assertThat(kindService.available(kamA)).extracting(InteractionKind::id).doesNotContain(off.id());
        assertThat(kindService.availableWithInactive(kamA)).filteredOn(kind -> kind.id().equals(off.id())).singleElement()
                .satisfies(kind -> {
                    assertThat(kind.active()).isFalse();
                    assertThat(kind.interactionCount()).isEqualTo(1);
                });
    }

    private static InteractionKindRequest request(String name, UUID templateId, Integer sortOrder) {
        return new InteractionKindRequest(null, name, null, true, templateId, sortOrder);
    }

    private static InteractionCreateRequest create(String title, UUID kindId, UUID templateId, List<UUID> products) {
        return new InteractionCreateRequest(ORGANIZATION_A, title, null, null, List.of(), null, products, null, templateId, kindId);
    }

    private static InteractionCreateRequest createB(String title, UUID kindId) {
        return new InteractionCreateRequest(ORGANIZATION_B, title, null, null, List.of(), null, List.of(), null, null, kindId);
    }

    private List<String> auditDetails(String action) {
        return jdbcTemplate.queryForList("SELECT details FROM audit_events WHERE action = ? ORDER BY occurred_at, id",
                String.class, action);
    }

    // Шаблон с последовательными этапами и переходами вперёд-назад; версия маршрута 1, как у созданного через сервис.
    private UUID template(UUID teamId, String name, String... stages) {
        UUID templateId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO workflow_templates (id, team_id, name, default_template, version, created_at, updated_at)"
                + " VALUES (?, ?, ?, FALSE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", templateId, teamId, name);
        jdbcTemplate.update("INSERT INTO workflow_template_versions (template_id, version_number, name, content, created_at)"
                + " VALUES (?, 1, ?, '{}', CURRENT_TIMESTAMP)", templateId, name);

        UUID previous = null;
        for (int order = 0; order < stages.length; order++) {
            UUID stageId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO workflow_template_stages (id, template_id, stage_order, name, optional) VALUES (?, ?, ?, ?, FALSE)",
                    stageId, templateId, order, stages[order]);
            if (previous != null) {
                jdbcTemplate.update("INSERT INTO workflow_template_transitions (template_id, from_stage_id, to_stage_id, comment_required)"
                        + " VALUES (?, ?, ?, FALSE), (?, ?, ?, FALSE)", templateId, previous, stageId, templateId, stageId, previous);
            }
            previous = stageId;
        }
        return templateId;
    }

    private UUID insertProgram(String name) {
        UUID direction = UUID.randomUUID();
        UUID program = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO directions (id, name, archived, version) VALUES (?, ?, FALSE, 0)", direction, "Направление " + name);
        jdbcTemplate.update("INSERT INTO programs (id, direction_id, name, archived, version) VALUES (?, ?, ?, FALSE, 0)", program, direction, name);
        return program;
    }

    private UUID insertProduct(String name) {
        UUID vendor = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO vendors (id, name, archived, version) VALUES (?, ?, FALSE, 0)", vendor, "Вендор " + name);
        jdbcTemplate.update("INSERT INTO products (id, vendor_id, name, archived, version) VALUES (?, ?, ?, FALSE, 0)", product, vendor, name);
        return product;
    }
}
