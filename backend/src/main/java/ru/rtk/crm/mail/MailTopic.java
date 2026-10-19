package ru.rtk.crm.mail;

import java.util.EnumSet;
import java.util.Set;

import ru.rtk.crm.access.UserRole;

// Что сотрудник может отключить в «Уведомлениях на почту». Приглашения, тестовое письмо и сведения субъекту ПДн
// отправляются по прямому действию человека и темой не отключаются.
public enum MailTopic {
    DIGEST("Утренняя сводка в 9:00 по рабочим дням", EnumSet.of(UserRole.USER, UserRole.LEADER)),
    ASSIGNMENT("Мне назначили или передали вуз", EnumSet.of(UserRole.USER, UserRole.LEADER)),
    AGREEMENT("Договор вернули на доработку, срок договора истекает", EnumSet.of(UserRole.USER, UserRole.LEADER)),
    ISSUES("Меня назначили ответственным за риск или проблему, по моей проблеме приняли решение",
            EnumSet.of(UserRole.USER, UserRole.LEADER)),
    PARTNER_REPLIES("Ответ координатора вуза на запрос", EnumSet.of(UserRole.USER, UserRole.LEADER)),
    AGENT_PLANS("ИИ-агент подготовил план, который ждёт моего подтверждения в CRM", EnumSet.of(UserRole.USER, UserRole.LEADER)),
    SITE_REQUEST("Новая заявка с сайта по вузу команды", EnumSet.of(UserRole.LEADER)),
    UNASSIGNED("Вузы команды остались без КАМ", EnumSet.of(UserRole.LEADER)),
    WEEKLY("Недельная сводка по командам по понедельникам", EnumSet.of(UserRole.LEADER, UserRole.MANAGEMENT)),
    ADMIN_ALERTS("Сбой источника, сотрудник ждёт активации, ежедневная сводка администратора", EnumSet.of(UserRole.ADMIN));

    private final String label;
    private final Set<UserRole> roles;

    MailTopic(String label, Set<UserRole> roles) {
        this.label = label;
        this.roles = roles;
    }

    public String label() {
        return label;
    }

    public Set<UserRole> roles() {
        return roles;
    }
}
