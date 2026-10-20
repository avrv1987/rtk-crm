package ru.rtk.crm.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.KeycloakAccountClient;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.work.WorkModels.ManagerIndicators;
import ru.rtk.crm.work.WorkModels.ReminderDigest;
import ru.rtk.crm.work.WorkModels.ReminderFeed;
import ru.rtk.crm.work.WorkModels.ReminderStep;
import ru.rtk.crm.work.WorkModels.TeamIndicators;
import ru.rtk.crm.work.WorkModels.TeamSummary;
import ru.rtk.crm.work.WorkModels.TeamsSummary;
import ru.rtk.crm.work.WorkService;

// Какие письма ставятся из событий CRM, утреннего и недельного проходов: кому, без повторов и без свободного текста.
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:mail-notifications;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-mail-notifications-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=https://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.sources.settings-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.modules.mail.enabled=true",
        "app.mail.jobs-enabled=false"
})
@ActiveProfiles("oidc")
class MailNotificationsTest {
    private static final UUID TEAM = MailHttpTest.TEAM;
    private static final UUID ADMIN = MailHttpTest.ADMIN;
    private static final UUID KAM = MailHttpTest.KAM;
    private static final UUID LEADER = MailHttpTest.LEADER;
    private static final UUID MANAGEMENT = MailHttpTest.MANAGEMENT;
    private static final UUID KAM_2 = UUID.fromString("81000000-0000-0000-0000-000000000007");
    private static final UUID ORGANIZATION = MailHttpTest.ORGANIZATION;
    private static final UUID OTHER = UUID.fromString("81000000-0000-0000-0000-000000000202");
    private static final String PHONE = "+7 900 123-45-67";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MailNotifications notifications;

    @Autowired
    private MailRepository repository;

    @MockitoBean
    private WorkService workService;

    @MockitoBean
    private OrganizationRepository organizationRepository;

    @MockitoBean
    private KeycloakAccountClient keycloakAccountClient;

    @BeforeEach
    void setUp() {
        MailTestSchema.reset(jdbcTemplate);
        MailTestSchema.profile(jdbcTemplate, ADMIN, "Администратор", "ADMIN", null);
        MailTestSchema.profile(jdbcTemplate, KAM, "Анна КАМ", "USER", TEAM);
        MailTestSchema.profile(jdbcTemplate, LEADER, "Лев Руководитель", "LEADER", TEAM);
        MailTestSchema.profile(jdbcTemplate, MANAGEMENT, "Руководство", "MANAGEMENT", null);
        MailTestSchema.profile(jdbcTemplate, KAM_2, "Борис КАМ", "USER", TEAM);
        MailTestSchema.profile(jdbcTemplate, MailHttpTest.PARTNER, "Координатор", "PARTNER", null);
        jdbcTemplate.update("INSERT INTO organizations (id, name, team_id, owner_manager_id) VALUES (?, 'Колледж связи', ?, ?)",
                ORGANIZATION, TEAM, KAM);
        jdbcTemplate.update("INSERT INTO organizations (id, name, team_id, owner_manager_id) VALUES (?, 'Технический университет', ?, NULL)",
                OTHER, TEAM);
        repository.saveCollectedUntil(OffsetDateTime.now().minusMinutes(1));
    }

    @Test
    void firstPassAfterEnablingDoesNotSendHistory() {
        repository.saveCollectedUntil(null);
        assignment(ADMIN, KAM, ORGANIZATION, OffsetDateTime.now().minusDays(3));
        assertThat(notifications.collectEvents()).isZero();
        assertThat(count()).isZero();
    }

    @Test
    void crmEventsBecomeLettersOnceAndOnlyForTheRightPeople() {
        assignment(ADMIN, KAM, ORGANIZATION, OffsetDateTime.now());
        assignment(KAM, KAM, ORGANIZATION, OffsetDateTime.now());
        UUID agreement = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO agreements (id, organization_id, number, status) VALUES (?, ?, 'Д-7', 'REVIEW')", agreement, ORGANIZATION);
        jdbcTemplate.update("INSERT INTO agreement_status_events (id, agreement_id, from_status, to_status, actor_profile_id, occurred_at) VALUES (?, ?, 'REVIEW', 'CORRECTION', ?, ?)",
                UUID.randomUUID(), agreement, ADMIN, OffsetDateTime.now());
        jdbcTemplate.update("INSERT INTO source_records (id, source, record_type, organization_id, created_at, applied_at) VALUES (?, 'WEBSITE', 'partnership_request', ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), ORGANIZATION, OffsetDateTime.now());
        UUID interaction = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO interactions (id, organization_id) VALUES (?, ?)", interaction, ORGANIZATION);
        UUID issue = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO interaction_issues (id, interaction_id, kind, description, risk_level, responsible_profile_id, due_on, status,
                                                created_by, created_at)
                VALUES (?, ?, 'RISK', ?, 'HIGH', ?, ?, 'OPEN', ?, ?)
                """, issue, interaction, "Ректор Иванов против, звонить " + PHONE, LEADER, LocalDate.of(2026, 11, 2), KAM, OffsetDateTime.now());
        UUID resolved = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO interaction_issues (id, interaction_id, kind, description, responsible_profile_id, status, resolution,
                                                created_by, created_at, resolved_by, resolved_at)
                VALUES (?, ?, 'PROBLEM', 'нет подписанта', ?, 'RESOLVED', ?, ?, ?, ?, ?)
                """, resolved, interaction, LEADER, "Договорились с " + PHONE, KAM, OffsetDateTime.now().minusDays(1), LEADER, OffsetDateTime.now());

        int queued = notifications.collectEvents();

        assertThat(notifications.collectEvents()).isZero();
        assertThat(queued).isEqualTo(5);
        assertThat(subjects(KAM)).containsExactlyInAnyOrder(
                "Вам назначен вуз «Колледж связи»", "Договор № Д-7 вернули на доработку", "Решение по вузу «Колледж связи»");
        assertThat(subjects(LEADER)).containsExactlyInAnyOrder(
                "Новая заявка с сайта: «Колледж связи»", "Риск по вузу «Колледж связи»: вы ответственный");
        assertThat(text(LEADER, "ISSUE_ASSIGNED")).contains("Уровень риска: высокий", "Срок: 02.11.2026",
                "https://crm.test/#/organizations/" + ORGANIZATION, "Настроить уведомления: https://crm.test/#/mail-settings");
        assertThat(jdbcTemplate.queryForList("SELECT body_text || body_html FROM mail_outbox", String.class))
                .noneMatch(body -> body.contains(PHONE) || body.contains("Иванов") || body.contains("Договорились"));
    }

    // Заявка неизвестного вуза: запись создана давно и ждала сопоставления, письмо уходит после применения и один раз.
    // План ИИ-агента: письмо тому, для кого план, без текста плана, со сроком письма до срока плана; решённый, истёкший
    // и план сотрудника, отключившего тему, письма не дают.
    @Test
    void pendingAgentPlanIsLetteredToItsOwnerOnceWithoutItsText() {
        UUID interaction = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO interactions (id, organization_id) VALUES (?, ?)", interaction, ORGANIZATION);
        OffsetDateTime now = OffsetDateTime.now();
        UUID plan = agentPlan(KAM, interaction, "PENDING", now, now.plusMinutes(20));
        agentPlan(KAM, interaction, "APPLIED", now, now.plusMinutes(20));
        agentPlan(KAM, interaction, "PENDING", now.minusMinutes(5), now.minusMinutes(1));
        agentPlan(KAM_2, interaction, "PENDING", now, now.plusMinutes(20));
        jdbcTemplate.update("INSERT INTO mail_preferences (profile_id, topic) VALUES (?, 'AGENT_PLANS')", KAM_2);

        assertThat(notifications.collectEvents()).isEqualTo(1);
        assertThat(notifications.collectEvents()).isZero();
        assertThat(subjects(KAM)).containsExactly("План ИИ-агента ждёт подтверждения: «Колледж связи»");
        assertThat(subjects(KAM_2)).isEmpty();
        assertThat(text(KAM, "AGENT_PLAN")).contains("https://crm.test/#/agent-plans/" + plan, "Данные CRM не изменены");
        assertThat(jdbcTemplate.queryForObject("SELECT expires_at FROM mail_outbox WHERE profile_id = ?", OffsetDateTime.class, KAM))
                .isEqualTo(jdbcTemplate.queryForObject("SELECT expires_at FROM agent_change_plans WHERE id = ?", OffsetDateTime.class, plan));
    }

    private UUID agentPlan(UUID profile, UUID interaction, String state, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO agent_change_plans (id, requester_profile_id, interaction_id, state, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, profile, interaction, state, createdAt, expiresAt);
        return id;
    }

    @Test
    void siteRequestMappedLaterIsLetteredOnceAfterApplying() {
        UUID record = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO source_records (id, source, record_type, organization_id, created_at) VALUES (?, 'WEBSITE', 'partnership_request', NULL, ?)",
                record, OffsetDateTime.now().minusDays(3));
        assertThat(notifications.collectEvents()).isZero();

        jdbcTemplate.update("UPDATE source_records SET organization_id = ?, applied_at = ? WHERE id = ?", ORGANIZATION, OffsetDateTime.now(), record);

        assertThat(notifications.collectEvents()).isEqualTo(1);
        assertThat(notifications.collectEvents()).isZero();
        assertThat(subjects(LEADER)).containsExactly("Новая заявка с сайта: «Колледж связи»");
    }

    @Test
    void blockedKamGivesTheLeaderOneLetterWithAllHisOrganizations() {
        UUID command = UUID.randomUUID();
        for (UUID organization : List.of(ORGANIZATION, OTHER)) {
            jdbcTemplate.update("UPDATE organizations SET owner_manager_id = NULL WHERE id = ?", organization);
            jdbcTemplate.update("""
                    INSERT INTO organization_assignment_events (id, organization_id, command_id, previous_owner_manager_id,
                        previous_owner_manager_display_name, owner_manager_id, actor_profile_id, reason, occurred_at)
                    VALUES (?, ?, ?, ?, 'Анна КАМ', NULL, ?, 'PROFILE_BLOCKED', ?)
                    """, UUID.randomUUID(), organization, command, KAM, ADMIN, OffsetDateTime.now());
        }
        notifications.collectEvents();
        notifications.collectEvents();
        assertThat(subjects(LEADER)).containsExactly("Вузы остались без КАМ: 2");
        assertThat(text(LEADER, "ORGANIZATIONS_UNASSIGNED")).contains("Анна КАМ заблокирован", "• Колледж связи", "• Технический университет");
    }

    @Test
    void administratorHearsAboutSourceFailuresAndWaitingEmployees() {
        jdbcTemplate.update("INSERT INTO sync_runs (id, source, status, error_code, error_message, finished_at) VALUES (?, 'MOODLE', 'FAILED', 'MOODLE_UNAVAILABLE', 'token=abc', ?)",
                UUID.randomUUID(), OffsetDateTime.now());
        jdbcTemplate.update("UPDATE crm_user_profiles SET pending_activation = TRUE, active = FALSE, activation_requested_at = ? WHERE id = ?",
                OffsetDateTime.now(), KAM_2);
        notifications.collectEvents();
        notifications.collectEvents();
        assertThat(subjects(ADMIN)).containsExactlyInAnyOrder("Сбой источника: LMS Moodle", "Сотрудник ждёт активации");
        assertThat(text(ADMIN, "ADMIN_SOURCE_FAILURE")).contains("MOODLE_UNAVAILABLE").doesNotContain("token=abc");
        assertThat(text(ADMIN, "ADMIN_PENDING_ACTIVATION")).doesNotContain("Борис");
    }

    @Test
    void morningSendsExpiringAgreementsDigestAndAdministratorSummaryOnce() {
        LocalDate today = LocalDate.now(MailProperties.ZONE);
        jdbcTemplate.update("INSERT INTO agreements (id, organization_id, number, valid_until, status) VALUES (?, ?, 'Д-30', ?, 'ACTIVE')",
                UUID.randomUUID(), ORGANIZATION, today.plusDays(20));
        jdbcTemplate.update("INSERT INTO agreements (id, organization_id, number, valid_until, status) VALUES (?, ?, 'Д-далеко', ?, 'ACTIVE')",
                UUID.randomUUID(), ORGANIZATION, today.plusDays(60));
        jdbcTemplate.update("INSERT INTO crm_exchange_events (id, status) VALUES (?, 'ERROR')", UUID.randomUUID());
        ReminderStep step = new ReminderStep(UUID.randomUUID(), ORGANIZATION, "Встреча", "Колледж связи", "Позвонить Иванову " + PHONE,
                OffsetDateTime.now().minusDays(1), "Анна");
        when(workService.reminders(any())).thenAnswer(invocation -> ((CrmProfile) invocation.getArgument(0)).id().equals(KAM)
                ? digest(new ReminderFeed(1, 2, 3, 0, 0, 0, 0), List.of(step))
                : digest(new ReminderFeed(0, 0, 0, 0, 0, 0, 0), List.of()));

        notifications.morning();
        notifications.morning();

        assertThat(subjects(KAM)).hasSize(2).contains("Работа на сегодня").anyMatch(subject -> subject.startsWith("Срок договора № Д-30"));
        assertThat(text(KAM, "DIGEST")).contains("Просрочено: 1", "Колледж связи").doesNotContain("Иванову").doesNotContain(PHONE);
        assertThat(subjects(LEADER)).isEmpty();
        assertThat(subjects(ADMIN)).containsExactly("Сводка администратора");
        assertThat(text(ADMIN, "ADMIN_DAILY")).contains("событий для CRM заказчика с ошибкой: 1");
        assertThat(jdbcTemplate.queryForObject("SELECT expires_at FROM mail_outbox WHERE kind = 'DIGEST'", OffsetDateTime.class))
                .isBefore(OffsetDateTime.now().plusDays(1));
    }

    @Test
    void weeklySummaryGoesToLeaderAndManagement() {
        when(workService.teamIndicators(any(), isNull())).thenReturn(new TeamIndicators(OffsetDateTime.now(), 14, 1,
                List.of(new ManagerIndicators(KAM, "Анна КАМ", 3, 5, 2, 1, 1, 1))));
        TeamSummary team = new TeamSummary(TEAM, "Команда Север", 3, 1, 5, 2, 1, 1, 1, 0, 0, 0, 0);
        when(workService.teamsSummary(any(), isNull())).thenReturn(new TeamsSummary(OffsetDateTime.now(), 14, List.of(team), team));
        notifications.weekly();
        notifications.weekly();
        assertThat(subjects(LEADER)).containsExactly("Неделя команды: просрочено 2");
        assertThat(subjects(MANAGEMENT)).containsExactly("Неделя по командам: просрочено 2");
        assertThat(text(MANAGEMENT, "WEEKLY_SUMMARY")).contains("• Команда Север");
        assertThat(subjects(KAM)).isEmpty();
    }

    @Test
    void partnerRequestHooksQueueOneLetterPerRequest() {
        UUID request = UUID.randomUUID();
        assertThat(notifications.partnerRequest(MailHttpTest.PARTNER, ORGANIZATION, "Колледж связи", request)).isTrue();
        assertThat(notifications.partnerRequest(MailHttpTest.PARTNER, ORGANIZATION, "Колледж связи", request)).isFalse();
        UUID interaction = UUID.randomUUID();
        assertThat(notifications.partnerReply(KAM, ORGANIZATION, interaction, "Колледж связи", UUID.randomUUID())).isTrue();
        assertThat(subjects(KAM)).containsExactly("Координатор ответил: «Колледж связи»");
        assertThat(text(KAM, "PARTNER_REPLY")).contains("#/organizations/" + ORGANIZATION + "/" + interaction)
                .contains("«Запросы вузу»");
    }

    // Возврат ответа — отдельный вид письма со своей темой; каждый возврат (новый ответ) — своё письмо.
    @Test
    void returnedAnswerLetterSaysReturnedAndCarriesNoDetails() {
        UUID answer = UUID.randomUUID();
        assertThat(notifications.partnerReturned(MailHttpTest.PARTNER, ORGANIZATION, "Колледж связи", answer)).isTrue();
        assertThat(notifications.partnerReturned(MailHttpTest.PARTNER, ORGANIZATION, "Колледж связи", answer)).isFalse();
        assertThat(notifications.partnerReturned(MailHttpTest.PARTNER, ORGANIZATION, "Колледж связи", UUID.randomUUID())).isTrue();
        assertThat(subjects(MailHttpTest.PARTNER)).containsOnly("Ответ возвращён на доработку: «Колледж связи»").hasSize(2);
        assertThat(jdbcTemplate.queryForList("SELECT body_text FROM mail_outbox WHERE kind = 'PARTNER_RETURNED'", String.class))
                .allSatisfy(body -> assertThat(body).contains("вернула ответ по «Колледж связи» на доработку")
                        .contains("#/partner").doesNotContain("Новый запрос").doesNotContain("Настроить уведомления"));
    }

    // Решение владельца 21.10: приёмка ответа — короткое письмо координатору без текстов и ФИО, со ссылкой в кабинет;
    // тему координатор отключает сам (отправщик не шлёт письмо отключённой темы — MailHttpTest).
    @Test
    void acceptedAnswerLetterIsShortAndItsTopicCanBeTurnedOff() {
        UUID answer = UUID.randomUUID();
        assertThat(notifications.partnerAccepted(MailHttpTest.PARTNER, ORGANIZATION, "Колледж связи", answer)).isTrue();
        assertThat(notifications.partnerAccepted(MailHttpTest.PARTNER, ORGANIZATION, "Колледж связи", answer)).isFalse();
        assertThat(subjects(MailHttpTest.PARTNER)).containsExactly("Ответ принят: «Колледж связи»");
        assertThat(text(MailHttpTest.PARTNER, "PARTNER_ACCEPTED")).contains("приняла ответ по «Колледж связи»")
                .contains("#/partner").contains("Настроить уведомления");
        assertThat(MailKind.PARTNER_ACCEPTED.topic()).isEqualTo(MailTopic.PARTNER_ACCEPTED);
        assertThat(MailKind.PARTNER_ACCEPTED.organizationBound()).isEqualTo(MailKind.PARTNER_RETURNED.organizationBound());
        assertThat(MailTopic.PARTNER_ACCEPTED.roles()).containsExactly(ru.rtk.crm.access.UserRole.PARTNER);
    }

    @Test
    void disabledTopicIsNotQueued() {
        jdbcTemplate.update("INSERT INTO mail_preferences (profile_id, topic) VALUES (?, 'ASSIGNMENT')", KAM);
        assignment(ADMIN, KAM, ORGANIZATION, OffsetDateTime.now());
        assertThat(notifications.collectEvents()).isZero();
    }

    private void assignment(UUID actor, UUID owner, UUID organization, OffsetDateTime at) {
        jdbcTemplate.update("INSERT INTO organization_assignment_events (id, organization_id, owner_manager_id, actor_profile_id, occurred_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), organization, owner, actor, at);
    }

    private List<String> subjects(UUID profile) {
        return jdbcTemplate.queryForList("SELECT subject FROM mail_outbox WHERE profile_id = ?", String.class, profile);
    }

    private String text(UUID profile, String kind) {
        return jdbcTemplate.queryForObject("SELECT body_text FROM mail_outbox WHERE profile_id = ? AND kind = ?", String.class, profile, kind);
    }

    private int count() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM mail_outbox", Integer.class);
    }

    private static ReminderDigest digest(ReminderFeed feed, List<ReminderStep> overdue) {
        return new ReminderDigest(true, OffsetDateTime.now(), 7, feed.overdue(), feed.today(), feed.thisWeek(), overdue, List.of(),
                List.of(), List.of(), 2027, List.of(), 90, List.of(), feed);
    }
}
