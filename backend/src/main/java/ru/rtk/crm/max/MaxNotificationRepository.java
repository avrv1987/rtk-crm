package ru.rtk.crm.max;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;

// Источники уведомлений — существующие таблицы истории CRM; модуль их только читает. Получатель сразу отбирается по
// активной связи и настройке «что присылать», а перед отправкой всё перепроверяется (MaxOutbox.dropReason).
@Repository
@MaxEnabled
public class MaxNotificationRepository {
    private static final String CHANNEL_PROFILE = """
            JOIN crm_user_profiles p ON p.id = l.profile_id AND p.active = TRUE AND p.role IN ('USER', 'LEADER')
                 AND p.enrolment_operator = FALSE
            """;

    private final JdbcClient jdbcClient;

    public MaxNotificationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // Назначение самому себе не уведомляется: о нём сотрудник и так знает.
    public List<Event> assignments(OffsetDateTime since) {
        return jdbcClient.sql("""
                SELECT e.id AS event_id, e.organization_id, o.name AS organization_name, l.profile_id, l.max_user_id,
                       CAST(NULL AS VARCHAR(100)) AS number, CAST(NULL AS DATE) AS valid_until
                FROM organization_assignment_events e
                JOIN organizations o ON o.id = e.organization_id
                JOIN max_links l ON l.profile_id = e.owner_manager_id AND l.status = 'ACTIVE' AND l.notify_assignment = TRUE
                """ + CHANNEL_PROFILE + """
                WHERE e.occurred_at > :since AND e.actor_profile_id <> e.owner_manager_id
                """)
                .param("since", since)
                .query(Event.class)
                .list();
    }

    // «Вернули на доработку» — переход договора в статус CORRECTION; получатель — ответственный КАМ вуза, если вернул
    // не он сам.
    public List<Event> agreementCorrections(OffsetDateTime since) {
        return jdbcClient.sql("""
                SELECT ev.id AS event_id, a.organization_id, o.name AS organization_name, l.profile_id, l.max_user_id,
                       a.number, a.valid_until
                FROM agreement_status_events ev
                JOIN agreements a ON a.id = ev.agreement_id
                JOIN organizations o ON o.id = a.organization_id
                JOIN max_links l ON l.profile_id = o.owner_manager_id AND l.status = 'ACTIVE' AND l.notify_agreement = TRUE
                """ + CHANNEL_PROFILE + """
                WHERE ev.occurred_at > :since AND ev.to_status = 'CORRECTION'
                  AND (ev.from_status IS NULL OR ev.from_status <> 'CORRECTION')
                  AND (ev.actor_profile_id IS NULL OR ev.actor_profile_id <> o.owner_manager_id)
                """)
                .param("since", since)
                .query(Event.class)
                .list();
    }

    // Новая заявка с сайта, сопоставленная с вузом, — руководителю команды этого вуза. Несопоставленные заявки разбирает
    // администратор в «Источниках данных».
    public List<Event> siteRequests(OffsetDateTime since) {
        return jdbcClient.sql("""
                SELECT r.id AS event_id, r.organization_id, o.name AS organization_name, l.profile_id, l.max_user_id,
                       CAST(NULL AS VARCHAR(100)) AS number, CAST(NULL AS DATE) AS valid_until
                FROM source_records r
                JOIN organizations o ON o.id = r.organization_id
                JOIN crm_user_profiles leader ON leader.team_id = o.team_id AND leader.role = 'LEADER'
                JOIN max_links l ON l.profile_id = leader.id AND l.status = 'ACTIVE' AND l.notify_site_request = TRUE
                """ + CHANNEL_PROFILE + """
                WHERE r.source = 'WEBSITE' AND r.record_type IN ('partnership_request', 'learning_application')
                  AND r.applied_at > :since
                """)
                .param("since", since)
                .query(Event.class)
                .list();
    }

    // Действующие договоры со сроком в ближайшие 30 дней. Диапазон, а не точная дата: пропущенный день (выходной,
    // остановка сервера) не теряет уведомление, а ключ события не даёт прислать его дважды.
    public List<Event> expiringAgreements(LocalDate today, LocalDate until) {
        return jdbcClient.sql("""
                SELECT a.id AS event_id, a.organization_id, o.name AS organization_name, l.profile_id, l.max_user_id,
                       a.number, a.valid_until
                FROM agreements a
                JOIN organizations o ON o.id = a.organization_id
                JOIN max_links l ON l.profile_id = o.owner_manager_id AND l.status = 'ACTIVE' AND l.notify_agreement = TRUE
                """ + CHANNEL_PROFILE + """
                WHERE a.status = 'ACTIVE' AND a.valid_until >= :today AND a.valid_until <= :until
                """)
                .param("today", today)
                .param("until", until)
                .query(Event.class)
                .list();
    }

    public List<DigestRecipient> digestRecipients() {
        return jdbcClient.sql("""
                SELECT p.id, p.role, p.team_id, p.access_revision, l.max_user_id
                FROM max_links l
                """ + CHANNEL_PROFILE + """
                WHERE l.status = 'ACTIVE' AND l.notify_digest = TRUE
                ORDER BY p.id
                """)
                .query((rs, row) -> new DigestRecipient(
                        new CrmProfile(
                                rs.getObject("id", UUID.class),
                                UserRole.valueOf(rs.getString("role")),
                                rs.getObject("team_id", UUID.class),
                                rs.getInt("access_revision")
                        ),
                        rs.getLong("max_user_id")
                ))
                .list();
    }

    // План ИИ-агента ждёт подтверждения — тому, для кого он подготовлен; решённые и истёкшие не уведомляются.
    public List<AgentPlanEvent> agentPlans(OffsetDateTime since, OffsetDateTime now) {
        return jdbcClient.sql("""
                SELECT ap.id AS plan_id, i.organization_id, o.name AS organization_name, l.profile_id, l.max_user_id, ap.expires_at
                FROM agent_change_plans ap
                JOIN interactions i ON i.id = ap.interaction_id
                JOIN organizations o ON o.id = i.organization_id
                JOIN max_links l ON l.profile_id = ap.requester_profile_id AND l.status = 'ACTIVE'
                """ + CHANNEL_PROFILE + """
                WHERE ap.created_at > :since AND ap.state = 'PENDING' AND ap.expires_at > :now
                """)
                .param("since", since)
                .param("now", now)
                .query(AgentPlanEvent.class)
                .list();
    }

    public record AgentPlanEvent(UUID planId, UUID organizationId, String organizationName, UUID profileId, long maxUserId,
                                 OffsetDateTime expiresAt) {
    }

    public record Event(
            UUID eventId,
            UUID organizationId,
            String organizationName,
            UUID profileId,
            long maxUserId,
            String number,
            LocalDate validUntil
    ) {
    }

    public record DigestRecipient(CrmProfile profile, long maxUserId) {
    }
}
