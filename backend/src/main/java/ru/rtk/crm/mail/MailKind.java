package ru.rtk.crm.mail;

import java.util.EnumSet;
import java.util.Set;

import ru.rtk.crm.access.UserRole;

// Вид письма: кому его можно отправить (роль проверяется заново перед отправкой), какой темой оно отключается и
// привязано ли к вузу, который должен оставаться видимым получателю.
public enum MailKind {
    INVITATION("Приглашение: вход в CRM", null, EnumSet.allOf(UserRole.class), false),
    PASSWORD_RESET("Новый пароль: вход в CRM", null, EnumSet.allOf(UserRole.class), false),
    DIGEST("Утренняя сводка", MailTopic.DIGEST, null, false),
    ASSIGNMENT("Назначен вуз", MailTopic.ASSIGNMENT, null, true),
    AGREEMENT_CORRECTION("Договор вернули на доработку", MailTopic.AGREEMENT, null, true),
    AGREEMENT_EXPIRY("Срок договора истекает", MailTopic.AGREEMENT, null, true),
    ISSUE_ASSIGNED("Назначен ответственным за риск или проблему", MailTopic.ISSUES, null, true),
    ISSUE_RESOLVED("Решение по риску или проблеме", MailTopic.ISSUES, null, true),
    PARTNER_REQUEST("Запрос вузу", null, EnumSet.of(UserRole.PARTNER), true),
    PARTNER_RETURNED("Ответ возвращён на доработку", null, EnumSet.of(UserRole.PARTNER), true),
    PARTNER_ACCEPTED("Ответ принят школой", MailTopic.PARTNER_ACCEPTED, null, true),
    PARTNER_REPLY("Ответ координатора вуза", MailTopic.PARTNER_REPLIES, null, true),
    SITE_REQUEST("Новая заявка с сайта", MailTopic.SITE_REQUEST, null, true),
    AGENT_PLAN("План ИИ-агента ждёт подтверждения", MailTopic.AGENT_PLANS, null, true),
    ORGANIZATIONS_UNASSIGNED("Вузы остались без КАМ", MailTopic.UNASSIGNED, null, false),
    WEEKLY_SUMMARY("Недельная сводка", MailTopic.WEEKLY, null, false),
    ADMIN_SOURCE_FAILURE("Сбой источника данных", MailTopic.ADMIN_ALERTS, null, false),
    ADMIN_PENDING_ACTIVATION("Сотрудник ждёт активации", MailTopic.ADMIN_ALERTS, null, false),
    ADMIN_DAILY("Сводка администратора", MailTopic.ADMIN_ALERTS, null, false),
    TEST("Тестовое письмо", null, EnumSet.of(UserRole.ADMIN), false),
    SUBJECT_REPORT("Сведения субъекту ПДн", null, EnumSet.noneOf(UserRole.class), false);

    private final String title;
    private final MailTopic topic;
    private final Set<UserRole> roles;
    private final boolean organizationBound;

    MailKind(String title, MailTopic topic, Set<UserRole> roles, boolean organizationBound) {
        this.title = title;
        this.topic = topic;
        this.roles = roles == null ? topic.roles() : roles;
        this.organizationBound = organizationBound;
    }

    public String title() {
        return title;
    }

    public MailTopic topic() {
        return topic;
    }

    public Set<UserRole> roles() {
        return roles;
    }

    public boolean organizationBound() {
        return organizationBound;
    }
}
