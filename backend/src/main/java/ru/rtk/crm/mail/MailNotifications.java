package ru.rtk.crm.mail;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.interaction.InteractionIssueKind;
import ru.rtk.crm.interaction.InteractionRiskLevel;
import ru.rtk.crm.mail.MailEventRepository.Event;
import ru.rtk.crm.mail.MailEventRepository.IssueEvent;
import ru.rtk.crm.mail.MailEventRepository.UnassignedEvent;
import ru.rtk.crm.mail.MailTemplates.Letter;
import ru.rtk.crm.work.WorkService;

// Сборщик писем. События CRM читаются из таблиц истории с перекрытием окна, как в модуле MAX: событие из транзакции,
// закоммиченной позже начала прошлого прохода, не теряется, а уникальный ключ события не даёт его продублировать.
@Service
@MailEnabled
public class MailNotifications {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailNotifications.class);
    static final Duration OVERLAP = Duration.ofMinutes(10);
    static final int EXPIRY_NOTICE_DAYS = 30;
    static final int EXPIRY_LAST_NOTICE_DAYS = 7;
    private static final Map<String, String> SOURCES = Map.of(
            "WEBSITE", "Сайт ИТ Школы",
            "MOODLE", "LMS Moodle",
            "LABOR_MARKET", "Рынок труда",
            "CUSTOMER_CRM", "CRM заказчика"
    );
    private static final Map<String, String> UNASSIGN_REASONS = Map.of(
            "PROFILE_BLOCKED", "заблокирован",
            "PROFILE_ROLE_CHANGED", "сменил роль",
            "PROFILE_TEAM_CHANGED", "перешёл в другую команду"
    );

    private final MailEventRepository events;
    private final MailRepository repository;
    private final MailOutbox outbox;
    private final MailTemplates templates;
    private final WorkService workService;

    public MailNotifications(
            MailEventRepository events,
            MailRepository repository,
            MailOutbox outbox,
            MailTemplates templates,
            WorkService workService
    ) {
        this.events = events;
        this.repository = repository;
        this.outbox = outbox;
        this.templates = templates;
        this.workService = workService;
    }

    // Первый проход после включения модуля не рассылает накопленную историю: окно начинается с текущего момента.
    public int collectEvents() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime collected = repository.settings().eventsCollectedUntil();
        if (collected == null) {
            repository.saveCollectedUntil(now);
            return 0;
        }
        OffsetDateTime since = collected.minus(OVERLAP);
        int queued = 0;
        for (Event event : events.assignments(since)) {
            queued += count(outbox.toProfile("assign:" + event.eventId(), MailKind.ASSIGNMENT, event.profileId(), event.organizationId(),
                    templates.assignment(event.organizationName(), event.organizationId())));
        }
        for (Event event : events.agreementCorrections(since)) {
            queued += count(outbox.toProfile("agreement-correction:" + event.eventId(), MailKind.AGREEMENT_CORRECTION, event.profileId(),
                    event.organizationId(), templates.agreementCorrection(event.number(), event.organizationName(), event.organizationId())));
        }
        for (Event event : events.siteRequests(since)) {
            queued += count(outbox.toProfile("site:" + event.eventId() + ":" + event.profileId(), MailKind.SITE_REQUEST, event.profileId(),
                    event.organizationId(), templates.siteRequest(event.organizationName(), event.organizationId())));
        }
        for (IssueEvent issue : events.issuesAssigned(since)) {
            InteractionIssueKind kind = InteractionIssueKind.valueOf(issue.kind());
            String level = issue.riskLevel() == null ? null : InteractionRiskLevel.valueOf(issue.riskLevel()).label();
            queued += count(outbox.toProfile("issue-assigned:" + issue.eventId(), MailKind.ISSUE_ASSIGNED, issue.profileId(),
                    issue.organizationId(), templates.issueAssigned(kind, level, issue.organizationName(), issue.dueOn(), issue.organizationId())));
        }
        for (IssueEvent issue : events.issuesResolved(since)) {
            queued += count(outbox.toProfile("issue-resolved:" + issue.eventId(), MailKind.ISSUE_RESOLVED, issue.profileId(),
                    issue.organizationId(), templates.issueResolved(InteractionIssueKind.valueOf(issue.kind()), issue.organizationName(),
                            issue.organizationId())));
        }
        // Срок письма — срок плана: после него «Записать» уже не сработает, и письмо не отправляется.
        for (MailEventRepository.AgentPlanEvent plan : events.agentPlans(since, now)) {
            queued += count(outbox.toProfile("agent-plan:" + plan.planId(), MailKind.AGENT_PLAN, plan.profileId(), plan.organizationId(),
                    templates.agentPlan(plan.organizationName(), plan.planId(), plan.expiresAt()), plan.expiresAt()));
        }
        queued += unassigned(since);
        queued += adminEvents(since);
        repository.saveCollectedUntil(now);
        return queued;
    }

    private int unassigned(OffsetDateTime since) {
        Map<String, List<UnassignedEvent>> groups = new LinkedHashMap<>();
        for (UnassignedEvent event : events.unassigned(since)) {
            groups.computeIfAbsent(event.commandId() + ":" + event.profileId(), key -> new ArrayList<>()).add(event);
        }
        int queued = 0;
        for (Map.Entry<String, List<UnassignedEvent>> group : groups.entrySet()) {
            UnassignedEvent first = group.getValue().getFirst();
            Letter letter = templates.unassigned(first.previousOwner(), UNASSIGN_REASONS.getOrDefault(first.reason(), "снят с вузов"),
                    group.getValue().stream().map(UnassignedEvent::organizationName).toList());
            queued += count(outbox.toProfile("unassigned:" + group.getKey(), MailKind.ORGANIZATIONS_UNASSIGNED, first.profileId(), null, letter));
        }
        return queued;
    }

    private int adminEvents(OffsetDateTime since) {
        List<UUID> admins = events.recipients(MailTopic.ADMIN_ALERTS);
        if (admins.isEmpty()) {
            return 0;
        }
        int queued = 0;
        for (MailEventRepository.SourceFailure failure : events.sourceFailures(since)) {
            Letter letter = templates.sourceFailure(SOURCES.getOrDefault(failure.source(), failure.source()), failure.errorCode());
            for (UUID admin : admins) {
                queued += count(outbox.toProfile("source-failure:" + failure.runId() + ":" + admin, MailKind.ADMIN_SOURCE_FAILURE, admin, null, letter));
            }
        }
        for (MailEventRepository.PendingProfile pending : events.pendingActivations(since)) {
            Letter letter = templates.pendingActivation();
            for (UUID admin : admins) {
                queued += count(outbox.toProfile("pending:" + pending.profileId() + ":" + pending.activationRequestedAt().toInstant() + ":" + admin,
                        MailKind.ADMIN_PENDING_ACTIVATION, admin, null, letter));
            }
        }
        return queued;
    }

    // Утренний проход в 9:00 МСК по рабочим дням: истекающие договоры, сводка КАМ и руководителю (тем же
    // WorkService.reminders, что колокольчик в CRM) и сводка администратора. Ошибка у одного сотрудника не
    // останавливает остальных.
    public int morning() {
        OffsetDateTime now = OffsetDateTime.now();
        LocalDate today = now.atZoneSameInstant(MailProperties.ZONE).toLocalDate();
        OffsetDateTime endOfDay = today.plusDays(1).atStartOfDay(MailProperties.ZONE).toOffsetDateTime();
        int queued = 0;
        for (Event event : events.expiringAgreements(today, today.plusDays(EXPIRY_NOTICE_DAYS))) {
            int bucket = event.validUntil().isAfter(today.plusDays(EXPIRY_LAST_NOTICE_DAYS)) ? EXPIRY_NOTICE_DAYS : EXPIRY_LAST_NOTICE_DAYS;
            queued += count(outbox.toProfile("agreement-expiry:" + event.eventId() + ":" + event.validUntil() + ":" + bucket + ":" + event.profileId(),
                    MailKind.AGREEMENT_EXPIRY, event.profileId(), event.organizationId(),
                    templates.agreementExpiry(event.number(), event.organizationName(), event.validUntil(), today, event.organizationId())));
        }
        for (CrmProfile profile : events.recipientProfiles(MailTopic.DIGEST)) {
            try {
                Letter letter = templates.digest(workService.reminders(profile));
                if (letter != null) {
                    queued += count(outbox.toProfile("digest:" + profile.id() + ":" + today, MailKind.DIGEST, profile.id(), null, letter, endOfDay));
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Mail digest was not built for profile={}", profile.id(), exception);
            }
        }
        Letter adminLetter = templates.adminDaily(events.adminCounts(now.minusDays(1)));
        if (adminLetter != null) {
            for (UUID admin : events.recipients(MailTopic.ADMIN_ALERTS)) {
                queued += count(outbox.toProfile("admin-daily:" + admin + ":" + today, MailKind.ADMIN_DAILY, admin, null, adminLetter, endOfDay));
            }
        }
        return queued;
    }

    // Понедельник, 9:00 МСК: руководителю — его команда, «Руководству» — все команды.
    public int weekly() {
        LocalDate today = LocalDate.now(MailProperties.ZONE);
        int queued = 0;
        for (CrmProfile profile : events.recipientProfiles(MailTopic.WEEKLY)) {
            try {
                Letter letter = profile.role() == UserRole.LEADER
                        ? (profile.teamId() == null ? null : templates.weeklyLeader(workService.teamIndicators(profile, null)))
                        : templates.weeklyManagement(workService.teamsSummary(profile, null));
                if (letter != null) {
                    queued += count(outbox.toProfile("weekly:" + profile.id() + ":" + today, MailKind.WEEKLY_SUMMARY, profile.id(), null, letter));
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Weekly mail summary was not built for profile={}", profile.id(), exception);
            }
        }
        return queued;
    }

    // Точки подключения «Запроса вузу»: событие ставит тот, кто создаёт запрос или принимает ответ координатора.
    // requestId — идентификатор запроса или ответа: повторный вызов с тем же идентификатором второго письма не ставит.
    public boolean partnerRequest(UUID partnerProfileId, UUID organizationId, String organizationName, UUID requestId) {
        return outbox.toProfile("partner-request:" + requestId + ":" + partnerProfileId, MailKind.PARTNER_REQUEST, partnerProfileId,
                organizationId, templates.partnerRequest(organizationName));
    }

    // responseId — возвращённый ответ: каждый возврат одного запроса даёт своё письмо.
    public boolean partnerReturned(UUID partnerProfileId, UUID organizationId, String organizationName, UUID responseId) {
        return outbox.toProfile("partner-returned:" + responseId + ":" + partnerProfileId, MailKind.PARTNER_RETURNED,
                partnerProfileId, organizationId, templates.partnerReturned(organizationName));
    }

    public boolean partnerReply(UUID managerProfileId, UUID organizationId, UUID interactionId, String organizationName,
                                UUID replyId) {
        return outbox.toProfile("partner-reply:" + replyId + ":" + managerProfileId, MailKind.PARTNER_REPLY, managerProfileId,
                organizationId, templates.partnerReply(organizationName, organizationId, interactionId));
    }

    private static int count(boolean queued) {
        return queued ? 1 : 0;
    }
}
