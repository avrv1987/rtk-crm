package ru.rtk.crm.max;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.work.WorkModels.ReminderAgreement;
import ru.rtk.crm.work.WorkModels.ReminderDigest;
import ru.rtk.crm.work.WorkModels.ReminderFeed;
import ru.rtk.crm.work.WorkModels.ReminderStep;
import ru.rtk.crm.work.WorkService;

// Уведомления: что попадает в очередь из событий CRM и утреннего прохода, кому и без повторов.
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:max-notifications;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:ru/rtk/crm/max/base-schema.sql'",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-max-notifications-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=https://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.sources.settings-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.modules.max.enabled=true",
        "app.max.jobs-enabled=false"
})
@ActiveProfiles("oidc")
class MaxNotificationsTest {
    private static final UUID TEAM = UUID.fromString("71000000-0000-0000-0000-000000000100");
    private static final UUID ADMIN = UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID KAM = UUID.fromString("71000000-0000-0000-0000-000000000002");
    private static final UUID LEADER = UUID.fromString("71000000-0000-0000-0000-000000000003");
    private static final UUID UNLINKED_KAM = UUID.fromString("71000000-0000-0000-0000-000000000004");
    private static final UUID MANAGEMENT = UUID.fromString("71000000-0000-0000-0000-000000000005");
    private static final UUID ORGANIZATION = UUID.fromString("71000000-0000-0000-0000-000000000201");
    private static final UUID OTHER_ORGANIZATION = UUID.fromString("71000000-0000-0000-0000-000000000202");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MaxNotifications notifications;

    @Autowired
    private MaxLinkRepository linkRepository;

    @Autowired
    private MaxSettingsRepository settingsRepository;

    @MockitoBean
    private WorkService workService;

    @MockitoBean
    private MaxClient client;

    @MockitoBean
    private OrganizationRepository organizationRepository;

    @BeforeEach
    void setUp() {
        new ResourceDatabasePopulator(new ClassPathResource("ru/rtk/crm/max/notification-schema.sql")).execute(jdbcTemplate.getDataSource());
        for (String table : List.of("max_outbox", "max_links", "organization_assignment_events", "agreement_status_events", "agreements",
                "source_records", "organizations", "crm_user_profiles")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        profile(ADMIN, "ADMIN", null);
        profile(KAM, "USER", TEAM);
        profile(LEADER, "LEADER", TEAM);
        profile(UNLINKED_KAM, "USER", TEAM);
        profile(MANAGEMENT, "MANAGEMENT", null);
        OffsetDateTime now = OffsetDateTime.now();
        linkRepository.replaceLink(KAM, 1001, "Анна", now);
        linkRepository.replaceLink(LEADER, 1002, "Лев", now);
        linkRepository.replaceLink(MANAGEMENT, 1005, "Руководство", now);
        jdbcTemplate.update("INSERT INTO organizations (id, name, team_id, owner_manager_id) VALUES (?, ?, ?, ?)",
                ORGANIZATION, "Колледж связи", TEAM, KAM);
        jdbcTemplate.update("INSERT INTO organizations (id, name, team_id, owner_manager_id) VALUES (?, ?, ?, ?)",
                OTHER_ORGANIZATION, "Технический университет", TEAM, UNLINKED_KAM);
        settingsRepository.saveCollectedUntil(now.minusMinutes(1));
    }

    @Test
    void firstPassAfterEnablingDoesNotSendHistory() {
        settingsRepository.saveCollectedUntil(null);
        assignment(ADMIN, KAM, ORGANIZATION, OffsetDateTime.now().minusDays(3));
        assertThat(notifications.collectEvents()).isZero();
        assertThat(settingsRepository.find().eventsCollectedUntil()).isNotNull();
        assertThat(count()).isZero();
    }

    @Test
    void assignmentGoesToTheNewOwnerOnlyOnceAndNotForSelfAssignment() {
        assignment(ADMIN, KAM, ORGANIZATION, OffsetDateTime.now());
        assignment(KAM, KAM, ORGANIZATION, OffsetDateTime.now());
        assignment(ADMIN, UNLINKED_KAM, OTHER_ORGANIZATION, OffsetDateTime.now());
        assertThat(notifications.collectEvents()).isEqualTo(1);
        assertThat(notifications.collectEvents()).isZero();
        assertThat(texts("ASSIGNMENT")).containsExactly("Вам назначен вуз «Колледж связи».");
        assertThat(jdbcTemplate.queryForObject("SELECT organization_id FROM max_outbox", UUID.class)).isEqualTo(ORGANIZATION);
    }

    @Test
    void agreementReturnedForCorrectionNotifiesTheOwner() {
        UUID agreement = agreement(ORGANIZATION, "Д-7", null, "REVIEW");
        statusEvent(agreement, "REVIEW", "CORRECTION", ADMIN);
        statusEvent(agreement, "CORRECTION", "CORRECTION", ADMIN);
        statusEvent(agreement, "REVIEW", "CORRECTION", KAM);
        statusEvent(agreement, "DRAFT", "REVIEW", ADMIN);
        notifications.collectEvents();
        assertThat(texts("AGREEMENT_CORRECTION")).containsExactly("Договор № Д-7 с «Колледж связи» вернули на доработку.");
    }

    @Test
    void newSiteRequestGoesToTheTeamLeader() {
        jdbcTemplate.update("INSERT INTO source_records (id, source, record_type, organization_id, created_at, applied_at) VALUES (?, 'WEBSITE', 'partnership_request', ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), ORGANIZATION, OffsetDateTime.now());
        jdbcTemplate.update("INSERT INTO source_records (id, source, record_type, organization_id, created_at, applied_at) VALUES (?, 'WEBSITE', 'paid_order', ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), ORGANIZATION, OffsetDateTime.now());
        jdbcTemplate.update("INSERT INTO source_records (id, source, record_type, organization_id, created_at, applied_at) VALUES (?, 'WEBSITE', 'learning_application', NULL, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), OffsetDateTime.now());
        notifications.collectEvents();
        assertThat(jdbcTemplate.queryForList("SELECT profile_id FROM max_outbox WHERE kind = 'SITE_REQUEST'", UUID.class)).containsExactly(LEADER);
        assertThat(texts("SITE_REQUEST")).containsExactly("Новая заявка с сайта: «Колледж связи». Разберите её в CRM.");
    }

    @Test
    void expiringAgreementIsNotifiedAt30AndAt7DaysOnce() {
        LocalDate today = LocalDate.now(MaxProperties.ZONE);
        agreement(ORGANIZATION, "Д-30", today.plusDays(20), "ACTIVE");
        agreement(ORGANIZATION, "Д-7", today.plusDays(5), "ACTIVE");
        agreement(ORGANIZATION, "Д-далеко", today.plusDays(60), "ACTIVE");
        agreement(ORGANIZATION, "Д-закрыт", today.plusDays(5), "TERMINATED");
        when(workService.reminders(any())).thenReturn(digest(new ReminderFeed(0, 0, 0, 0, 0, 0, 0), List.of(), List.of()));
        notifications.morning();
        notifications.morning();
        assertThat(texts("AGREEMENT_EXPIRY")).hasSize(2)
                .anyMatch(text -> text.startsWith("Срок договора № Д-30 с «Колледж связи» истекает") && text.endsWith("(через 20 дн.)."))
                .anyMatch(text -> text.startsWith("Срок договора № Д-7") && text.endsWith("(через 5 дн.)."));
        assertThat(jdbcTemplate.queryForList("SELECT event_key FROM max_outbox", String.class))
                .anyMatch(key -> key.endsWith(":30")).anyMatch(key -> key.endsWith(":7"));
    }

    @Test
    void morningDigestGoesToKamAndLeaderButNotManagementAndNotWhenEmpty() {
        ReminderStep step = new ReminderStep(UUID.randomUUID(), ORGANIZATION, "Встреча", "Колледж связи", "Позвонить Иванову +7 900",
                OffsetDateTime.now().minusDays(1), "Анна");
        when(workService.reminders(any())).thenAnswer(invocation -> ((CrmProfile) invocation.getArgument(0)).id().equals(KAM)
                ? digest(new ReminderFeed(1, 2, 3, 0, 0, 0, 0), List.of(step), List.of())
                : digest(new ReminderFeed(0, 0, 0, 0, 0, 0, 0), List.of(), List.of()));
        notifications.morning();
        notifications.morning();
        List<String> digests = texts("DIGEST");
        assertThat(digests).hasSize(1);
        assertThat(digests.getFirst()).contains("Просрочено: 1", "Сегодня: 2", "На неделе: 3", "Колледж связи").doesNotContain("Иванову");
        assertThat(jdbcTemplate.queryForList("SELECT profile_id FROM max_outbox WHERE kind = 'DIGEST'", UUID.class)).containsExactly(KAM);
        assertThat(jdbcTemplate.queryForObject("SELECT body FROM max_outbox WHERE kind = 'DIGEST'", String.class)).contains("inline_keyboard");
    }

    @Test
    void digestFormatterListsAtMostFiveStepsAndExpiringAgreements() {
        List<ReminderStep> steps = IntStream.range(0, 7)
                .mapToObj(index -> new ReminderStep(UUID.randomUUID(), ORGANIZATION, "t", "Вуз " + index, "шаг", OffsetDateTime.now().plusDays(1), "А"))
                .toList();
        ReminderAgreement expiring = new ReminderAgreement(UUID.randomUUID(), ORGANIZATION, "Колледж связи", "Д-1",
                LocalDate.of(2026, 11, 1), null, null, "Анна");
        String text = MaxDigestFormatter.digest(digest(new ReminderFeed(0, 0, 7, 0, 0, 0, 0), List.of(), steps, List.of(expiring)));
        assertThat(text).contains("• Вуз 4", "… и ещё 2", "№ Д-1, Колледж связи — до 01.11.2026").doesNotContain("Вуз 5");
        assertThat(MaxDigestFormatter.digest(digest(new ReminderFeed(0, 0, 0, 0, 0, 0, 0), List.of(), List.of()))).isNull();
    }

    private List<String> texts(String kind) {
        return jdbcTemplate.queryForList("SELECT body FROM max_outbox WHERE kind = ?", String.class, kind).stream()
                .map(body -> (String) JsonPath.read(body, "$.text")).toList();
    }

    private int count() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM max_outbox", Integer.class);
    }

    private void profile(UUID id, String role, UUID team) {
        jdbcTemplate.update("INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id) VALUES (?, 'i', ?, ?, ?, ?)",
                id, id.toString(), role, role, team);
    }

    private void assignment(UUID actor, UUID owner, UUID organization, OffsetDateTime at) {
        jdbcTemplate.update("INSERT INTO organization_assignment_events (id, organization_id, owner_manager_id, actor_profile_id, occurred_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), organization, owner, actor, at);
    }

    private UUID agreement(UUID organization, String number, LocalDate validUntil, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO agreements (id, organization_id, number, valid_until, status) VALUES (?, ?, ?, ?, ?)",
                id, organization, number, validUntil, status);
        return id;
    }

    private void statusEvent(UUID agreement, String from, String to, UUID actor) {
        jdbcTemplate.update("INSERT INTO agreement_status_events (id, agreement_id, from_status, to_status, actor_profile_id, occurred_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), agreement, from, to, actor, OffsetDateTime.now());
    }

    private static ReminderDigest digest(ReminderFeed feed, List<ReminderStep> overdue, List<ReminderStep> upcoming) {
        return digest(feed, overdue, upcoming, List.of());
    }

    private static ReminderDigest digest(ReminderFeed feed, List<ReminderStep> overdue, List<ReminderStep> upcoming, List<ReminderAgreement> agreements) {
        return new ReminderDigest(true, OffsetDateTime.now(), 7, feed.overdue(), feed.today(), feed.thisWeek(), overdue, upcoming,
                List.of(), List.of(), 2027, List.of(), 90, agreements, feed);
    }
}
