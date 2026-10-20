package ru.rtk.crm.interaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// история карточки показывает загрузку документа без события, создание и смену статуса договора этой работы и
// запуск нового цикла в обеих карточках — с автором, временем и ссылкой, без повтора уже записанного.
@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:related-events;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InteractionRelatedEvents.class)
class InteractionRelatedEventsTest {
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID KAM = UUID.randomUUID();
    private static final UUID WORK = UUID.randomUUID();
    private static final UUID NEXT_WORK = UUID.randomUUID();
    private static final UUID OTHER_WORK = UUID.randomUUID();
    private static final UUID STAGE_1 = UUID.randomUUID();
    private static final UUID STAGE_2 = UUID.randomUUID();
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-01T10:00:00+03:00");

    @Autowired
    private InteractionRelatedEvents relatedEvents;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        for (String table : List.of("crm_user_profiles", "interactions", "interaction_stages", "attachments", "agreements",
                "agreement_status_events", "agreement_interactions", "interaction_cycles")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("CREATE TABLE crm_user_profiles (id UUID PRIMARY KEY, display_name VARCHAR(200) NOT NULL)");
        jdbc.execute("CREATE TABLE interactions (id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL)");
        jdbc.execute("CREATE TABLE interaction_stages (id UUID PRIMARY KEY, interaction_id UUID NOT NULL, name VARCHAR(200) NOT NULL)");
        jdbc.execute("""
                CREATE TABLE attachments (id UUID PRIMARY KEY, interaction_id UUID NOT NULL, stage_id UUID NOT NULL, event_id UUID,
                    original_name VARCHAR(255) NOT NULL, created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    deleted_at TIMESTAMP WITH TIME ZONE)
                """);
        jdbc.execute("CREATE TABLE agreements (id UUID PRIMARY KEY, organization_id UUID NOT NULL, number VARCHAR(100) NOT NULL)");
        jdbc.execute("""
                CREATE TABLE agreement_status_events (id UUID PRIMARY KEY, agreement_id UUID NOT NULL, from_status VARCHAR(16),
                    to_status VARCHAR(16) NOT NULL, comment VARCHAR(2000), actor_profile_id UUID, interaction_id UUID,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL)
                """);
        jdbc.execute("CREATE TABLE agreement_interactions (agreement_id UUID NOT NULL, interaction_id UUID NOT NULL)");
        jdbc.execute("""
                CREATE TABLE interaction_cycles (interaction_id UUID PRIMARY KEY, previous_interaction_id UUID NOT NULL,
                    created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL)
                """);
        jdbc.update("INSERT INTO crm_user_profiles VALUES (?, 'КАМ А')", KAM);
        jdbc.update("INSERT INTO interactions VALUES (?, ?, 'Первый цикл'), (?, ?, 'Второй цикл'), (?, ?, 'Другая работа')",
                WORK, ORGANIZATION, NEXT_WORK, ORGANIZATION, OTHER_WORK, ORGANIZATION);
        jdbc.update("INSERT INTO interaction_stages VALUES (?, ?, 'Поиск контакта'), (?, ?, 'Подписание')", STAGE_1, WORK, STAGE_2, WORK);
    }

    @Test
    void unboundDocumentsAgreementStatusesAndCyclesJoinTheCardHistoryInTimeOrder() {
        UUID bound = UUID.randomUUID();
        UUID loose = UUID.randomUUID();
        UUID transitionId = UUID.randomUUID();
        jdbc.update("INSERT INTO attachments VALUES (?, ?, ?, ?, 'к переходу.pdf', ?, ?, NULL)", bound, WORK, STAGE_2, transitionId, KAM,
                T0.plusHours(2));
        jdbc.update("INSERT INTO attachments VALUES (?, ?, ?, NULL, 'проект.pdf', ?, ?, NULL)", loose, WORK, STAGE_1, KAM, T0.plusMinutes(30));

        UUID agreement = UUID.randomUUID();
        UUID foreignAgreement = UUID.randomUUID();
        jdbc.update("INSERT INTO agreements VALUES (?, ?, 'МК-001'), (?, ?, 'ЧУЖОЙ-1')", agreement, ORGANIZATION, foreignAgreement, ORGANIZATION);
        jdbc.update("INSERT INTO agreement_interactions VALUES (?, ?)", agreement, WORK);
        jdbc.update("INSERT INTO agreement_status_events VALUES (?, ?, NULL, 'DRAFT', NULL, ?, NULL, ?)",
                UUID.randomUUID(), agreement, KAM, T0.plusHours(3));
        jdbc.update("INSERT INTO agreement_status_events VALUES (?, ?, 'DRAFT', 'ACTIVE', NULL, ?, ?, ?)",
                UUID.randomUUID(), agreement, KAM, WORK, T0.plusHours(4));
        // Переход договора, отмеченный другой работой, и договор, не связанный с работой, в её историю не попадают.
        jdbc.update("INSERT INTO agreement_status_events VALUES (?, ?, 'ACTIVE', 'COMPLETED', NULL, ?, ?, ?)",
                UUID.randomUUID(), agreement, KAM, OTHER_WORK, T0.plusHours(5));
        jdbc.update("INSERT INTO agreement_status_events VALUES (?, ?, NULL, 'DRAFT', NULL, ?, NULL, ?)",
                UUID.randomUUID(), foreignAgreement, KAM, T0.plusHours(3));

        jdbc.update("INSERT INTO interaction_cycles VALUES (?, ?, ?, ?)", NEXT_WORK, WORK, KAM, T0.plusDays(1));

        List<InteractionEvent> own = List.of(
                event(InteractionEventType.CREATED, STAGE_1, "Поиск контакта", 0, T0),
                transition(transitionId, 1, T0.plusHours(2)),
                event(InteractionEventType.STATUS_CHANGED, STAGE_2, "Подписание", 2, T0.plusDays(1).minusHours(1))
        );

        List<InteractionEvent> merged = relatedEvents.merge(WORK, own);

        assertThat(merged).extracting(InteractionEvent::type).containsExactly(
                InteractionEventType.CREATED,
                InteractionEventType.DOCUMENT_ADDED,
                InteractionEventType.TRANSITIONED,
                InteractionEventType.AGREEMENT_STATUS_CHANGED,
                InteractionEventType.AGREEMENT_STATUS_CHANGED,
                InteractionEventType.STATUS_CHANGED,
                InteractionEventType.CYCLE_STARTED
        );
        assertThat(merged.get(1)).satisfies(document -> {
            assertThat(document.comment()).isEqualTo("Документ «проект.pdf»");
            assertThat(document.actorDisplayName()).isEqualTo("КАМ А");
            assertThat(document.stageNameSnapshot()).isEqualTo("Поиск контакта");
            assertThat(document.link()).isEqualTo(new InteractionEventLink(InteractionEventLink.Kind.ATTACHMENT, loose, null, "проект.pdf"));
        });
        assertThat(merged.get(3).comment()).isEqualTo("Договор № МК-001 создан в статусе «Проект»");
        assertThat(merged.get(3).stageNameSnapshot()).as("этап на момент события — после перехода").isEqualTo("Подписание");
        assertThat(merged.get(4).comment()).isEqualTo("Договор № МК-001: «Проект» → «Действует»");
        assertThat(merged.get(4).link()).isEqualTo(new InteractionEventLink(InteractionEventLink.Kind.AGREEMENT, agreement, ORGANIZATION, "№ МК-001"));
        assertThat(merged.getLast()).satisfies(cycle -> {
            assertThat(cycle.comment()).isEqualTo("Начат новый цикл «Второй цикл»");
            assertThat(cycle.occurredAt().toInstant()).isEqualTo(T0.plusDays(1).toInstant());
            assertThat(cycle.link()).isEqualTo(new InteractionEventLink(InteractionEventLink.Kind.INTERACTION, NEXT_WORK, ORGANIZATION, "Второй цикл"));
        });

        jdbc.update("INSERT INTO interaction_stages VALUES (?, ?, 'Поиск контакта')", UUID.randomUUID(), NEXT_WORK);
        UUID nextStage = jdbc.queryForObject("SELECT id FROM interaction_stages WHERE interaction_id = ?", UUID.class, NEXT_WORK);
        List<InteractionEvent> next = relatedEvents.merge(NEXT_WORK, List.of(event(InteractionEventType.CREATED, nextStage, "Поиск контакта", 0,
                T0.plusDays(1))));
        assertThat(next).last().satisfies(cycle -> {
            assertThat(cycle.type()).isEqualTo(InteractionEventType.CYCLE_STARTED);
            assertThat(cycle.comment()).isEqualTo("Новый цикл после взаимодействия «Первый цикл»");
            assertThat(cycle.link().id()).isEqualTo(WORK);
        });
    }

    @Test
    void cardWithoutRelatedRecordsKeepsItsHistoryAsIs() {
        List<InteractionEvent> own = List.of(event(InteractionEventType.CREATED, STAGE_1, "Поиск контакта", 0, T0));
        assertThat(relatedEvents.merge(WORK, own)).isEqualTo(own);
    }

    private static InteractionEvent event(InteractionEventType type, UUID stageId, String stageName, int version, OffsetDateTime at) {
        boolean created = type == InteractionEventType.CREATED;
        return new InteractionEvent(UUID.randomUUID(), type, UUID.randomUUID(), stageId, stageName, null, null,
                created ? stageId : null, created ? stageName : null, null, null, KAM, "КАМ А", null, version, at, false);
    }

    private static InteractionEvent transition(UUID id, int version, OffsetDateTime at) {
        return new InteractionEvent(id, InteractionEventType.TRANSITIONED, UUID.randomUUID(), STAGE_2, "Подписание", STAGE_1,
                "Поиск контакта", STAGE_2, "Подписание", null, null, KAM, "КАМ А", null, version, at, false);
    }
}
