package ru.rtk.crm.mail;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;

// Источники писем — существующие таблицы истории CRM; модуль их только читает. Получатель сразу отбирается по роли,
// активности и настройке «что присылать на почту», а перед отправкой всё перепроверяется (MailOutbox.dropReason).
@Repository
@MailEnabled
public class MailEventRepository {
    private final JdbcClient jdbcClient;

    public MailEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // Получатель — действующий профиль с ролью темы; менеджер учебного процесса событий по вузам не получает, как и в MAX.
    // Роли подставляются из перечисления, а не из ввода, поэтому склейка строки здесь безопасна.
    static String recipient(String alias, String column, MailTopic topic) {
        return " JOIN crm_user_profiles " + alias + " ON " + alias + ".id = " + column + " AND " + allowed(alias, topic) + " ";
    }

    static String allowed(String alias, MailTopic topic) {
        String roles = topic.roles().stream().map(role -> "'" + role.name() + "'").sorted().collect(Collectors.joining(", "));
        return alias + ".active = TRUE AND " + alias + ".pending_activation = FALSE AND " + alias + ".enrolment_operator = FALSE"
                + " AND " + alias + ".role IN (" + roles + ")"
                + " AND NOT EXISTS (SELECT 1 FROM mail_preferences mp WHERE mp.profile_id = " + alias + ".id AND mp.topic = '"
                + topic.name() + "')";
    }

    // Назначение самому себе не уведомляется: о нём сотрудник и так знает.
    public List<Event> assignments(OffsetDateTime since) {
        return jdbcClient.sql("""
                SELECT e.id AS event_id, e.organization_id, o.name AS organization_name, p.id AS profile_id,
                       CAST(NULL AS VARCHAR(100)) AS number, CAST(NULL AS DATE) AS valid_until
                FROM organization_assignment_events e
                JOIN organizations o ON o.id = e.organization_id
                """ + recipient("p", "e.owner_manager_id", MailTopic.ASSIGNMENT) + """
                WHERE e.occurred_at > :since AND e.actor_profile_id <> e.owner_manager_id
                """)
                .param("since", since)
                .query(Event.class)
                .list();
    }

    public List<Event> agreementCorrections(OffsetDateTime since) {
        return jdbcClient.sql("""
                SELECT ev.id AS event_id, a.organization_id, o.name AS organization_name, p.id AS profile_id,
                       a.number, a.valid_until
                FROM agreement_status_events ev
                JOIN agreements a ON a.id = ev.agreement_id
                JOIN organizations o ON o.id = a.organization_id
                """ + recipient("p", "o.owner_manager_id", MailTopic.AGREEMENT) + """
                WHERE ev.occurred_at > :since AND ev.to_status = 'CORRECTION'
                  AND (ev.from_status IS NULL OR ev.from_status <> 'CORRECTION')
                  AND (ev.actor_profile_id IS NULL OR ev.actor_profile_id <> o.owner_manager_id)
                """)
                .param("since", since)
                .query(Event.class)
                .list();
    }

    // Сопоставленная с вузом заявка — руководителю команды вуза; несопоставленные разбирает администратор. Отбор по
    // времени применения, а не создания: заявку неизвестного вуза применяют после сопоставления, иногда через дни.
    public List<Event> siteRequests(OffsetDateTime since) {
        return jdbcClient.sql("""
                SELECT r.id AS event_id, r.organization_id, o.name AS organization_name, p.id AS profile_id,
                       CAST(NULL AS VARCHAR(100)) AS number, CAST(NULL AS DATE) AS valid_until
                FROM source_records r
                JOIN organizations o ON o.id = r.organization_id
                JOIN crm_user_profiles leader ON leader.team_id = o.team_id AND leader.role = 'LEADER'
                """ + recipient("p", "leader.id", MailTopic.SITE_REQUEST) + """
                WHERE r.source = 'WEBSITE' AND r.record_type IN ('partnership_request', 'learning_application')
                  AND r.applied_at > :since
                """)
                .param("since", since)
                .query(Event.class)
                .list();
    }

    // Диапазон, а не точная дата: пропущенный день не теряет письмо, а ключ события не даёт прислать его дважды.
    public List<Event> expiringAgreements(LocalDate today, LocalDate until) {
        return jdbcClient.sql("""
                SELECT a.id AS event_id, a.organization_id, o.name AS organization_name, p.id AS profile_id,
                       a.number, a.valid_until
                FROM agreements a
                JOIN organizations o ON o.id = a.organization_id
                """ + recipient("p", "o.owner_manager_id", MailTopic.AGREEMENT) + """
                WHERE a.status = 'ACTIVE' AND a.valid_until >= :today AND a.valid_until <= :until
                """)
                .param("today", today)
                .param("until", until)
                .query(Event.class)
                .list();
    }

    // Эскалация: риск или проблему завёл один человек, а ответственным назначил другого.
    public List<IssueEvent> issuesAssigned(OffsetDateTime since) {
        return jdbcClient.sql("""
                SELECT ii.id AS event_id, i.organization_id, o.name AS organization_name, p.id AS profile_id,
                       ii.kind, ii.risk_level, ii.due_on
                FROM interaction_issues ii
                JOIN interactions i ON i.id = ii.interaction_id
                JOIN organizations o ON o.id = i.organization_id
                """ + recipient("p", "ii.responsible_profile_id", MailTopic.ISSUES) + """
                WHERE ii.created_at > :since AND ii.created_by <> ii.responsible_profile_id
                """)
                .param("since", since)
                .query(IssueEvent.class)
                .list();
    }

    // Решение по эскалации — тому, кто её завёл, если решил не он сам.
    public List<IssueEvent> issuesResolved(OffsetDateTime since) {
        return jdbcClient.sql("""
                SELECT ii.id AS event_id, i.organization_id, o.name AS organization_name, p.id AS profile_id,
                       ii.kind, ii.risk_level, ii.due_on
                FROM interaction_issues ii
                JOIN interactions i ON i.id = ii.interaction_id
                JOIN organizations o ON o.id = i.organization_id
                """ + recipient("p", "ii.created_by", MailTopic.ISSUES) + """
                WHERE ii.status = 'RESOLVED' AND ii.resolved_at > :since AND ii.resolved_by <> ii.created_by
                """)
                .param("since", since)
                .query(IssueEvent.class)
                .list();
    }

    // План ИИ-агента ждёт подтверждения — тому, для кого он подготовлен. Уже решённые и истёкшие планы не уведомляются.
    public List<AgentPlanEvent> agentPlans(OffsetDateTime since, OffsetDateTime now) {
        return jdbcClient.sql("""
                SELECT ap.id AS plan_id, i.organization_id, o.name AS organization_name, p.id AS profile_id, ap.expires_at
                FROM agent_change_plans ap
                JOIN interactions i ON i.id = ap.interaction_id
                JOIN organizations o ON o.id = i.organization_id
                """ + recipient("p", "ap.requester_profile_id", MailTopic.AGENT_PLANS) + """
                WHERE ap.created_at > :since AND ap.state = 'PENDING' AND ap.expires_at > :now
                """)
                .param("since", since)
                .param("now", now)
                .query(AgentPlanEvent.class)
                .list();
    }

    // Снятие КАМ при блокировке, смене роли или команды. Все вузы одного действия администратора записаны одной
    // командой (command_id) в одной транзакции, поэтому руководитель получает одно письмо на действие.
    public List<UnassignedEvent> unassigned(OffsetDateTime since) {
        return jdbcClient.sql("""
                SELECT e.command_id, e.reason, e.previous_owner_manager_display_name AS previous_owner,
                       o.name AS organization_name, p.id AS profile_id
                FROM organization_assignment_events e
                JOIN organizations o ON o.id = e.organization_id AND o.owner_manager_id IS NULL
                JOIN crm_user_profiles leader ON leader.team_id = o.team_id AND leader.role = 'LEADER'
                """ + recipient("p", "leader.id", MailTopic.UNASSIGNED) + """
                WHERE e.occurred_at > :since AND e.owner_manager_id IS NULL
                  AND e.reason IN ('PROFILE_BLOCKED', 'PROFILE_ROLE_CHANGED', 'PROFILE_TEAM_CHANGED')
                  AND (e.previous_owner_manager_id IS NULL OR e.previous_owner_manager_id <> p.id)
                ORDER BY o.name
                """)
                .param("since", since)
                .query(UnassignedEvent.class)
                .list();
    }

    public List<SourceFailure> sourceFailures(OffsetDateTime since) {
        return jdbcClient.sql("""
                SELECT r.id AS run_id, r.source, r.error_code
                FROM sync_runs r
                WHERE r.status = 'FAILED' AND r.finished_at > :since
                """)
                .param("since", since)
                .query(SourceFailure.class)
                .list();
    }

    public List<PendingProfile> pendingActivations(OffsetDateTime since) {
        return jdbcClient.sql("""
                SELECT id AS profile_id, activation_requested_at
                FROM crm_user_profiles
                WHERE pending_activation = TRUE AND activation_requested_at > :since
                """)
                .param("since", since)
                .query(PendingProfile.class)
                .list();
    }

    public List<UUID> recipients(MailTopic topic) {
        return jdbcClient.sql("SELECT p.id FROM crm_user_profiles p WHERE " + allowed("p", topic) + " ORDER BY p.id")
                .query(UUID.class)
                .list();
    }

    public List<CrmProfile> recipientProfiles(MailTopic topic) {
        return jdbcClient.sql("SELECT p.id, p.role, p.team_id, p.access_revision FROM crm_user_profiles p WHERE "
                        + allowed("p", topic) + " ORDER BY p.id")
                .query((rs, row) -> new CrmProfile(
                        rs.getObject("id", UUID.class),
                        UserRole.valueOf(rs.getString("role")),
                        rs.getObject("team_id", UUID.class),
                        rs.getInt("access_revision")
                ))
                .list();
    }

    // Счётчики ежедневной сводки администратора. Ошибки очереди CRM и почты — текущие, сбои и отказы во входе — за сутки.
    public AdminCounts adminCounts(OffsetDateTime since) {
        return jdbcClient.sql("""
                SELECT
                    (SELECT COUNT(*) FROM crm_user_profiles WHERE pending_activation = TRUE) AS pending_profiles,
                    (SELECT COUNT(*) FROM sync_runs WHERE status = 'FAILED' AND finished_at > :since) AS failed_runs,
                    (SELECT COUNT(*) FROM crm_exchange_events WHERE status = 'ERROR') AS crm_errors,
                    (SELECT COUNT(*) FROM mail_outbox WHERE status = 'ERROR') AS mail_errors,
                    (SELECT COUNT(*) FROM audit_events WHERE action = 'LOGIN_DENIED' AND occurred_at > :since) AS login_denials
                """)
                .param("since", since)
                .query(AdminCounts.class)
                .single();
    }

    public record Event(UUID eventId, UUID organizationId, String organizationName, UUID profileId, String number, LocalDate validUntil) {
    }

    public record IssueEvent(
            UUID eventId,
            UUID organizationId,
            String organizationName,
            UUID profileId,
            String kind,
            String riskLevel,
            LocalDate dueOn
    ) {
    }

    public record AgentPlanEvent(UUID planId, UUID organizationId, String organizationName, UUID profileId, OffsetDateTime expiresAt) {
    }

    public record UnassignedEvent(UUID commandId, String reason, String previousOwner, String organizationName, UUID profileId) {
    }

    public record SourceFailure(UUID runId, String source, String errorCode) {
    }

    public record PendingProfile(UUID profileId, OffsetDateTime activationRequestedAt) {
    }

    public record AdminCounts(long pendingProfiles, long failedRuns, long crmErrors, long mailErrors, long loginDenials) {
        long total() {
            return pendingProfiles + failedRuns + crmErrors + mailErrors + loginDenials;
        }
    }
}
