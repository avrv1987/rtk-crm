package ru.rtk.crm.settings;

// Настройки, которые администратор меняет на экране. Значение по умолчанию — из конфигурации сервера (property),
// fallback — если свойства в конфигурации нет. Все значения — целые в единице unit.
public enum SystemSetting {
    RETENTION_REPORT_FILES(Group.RETENTION, "Файлы заказанных отчётов", Unit.DAYS, 1, 3650, "app.retention.report-files", 7),
    RETENTION_INACTIVE_CONTACTS(Group.RETENTION, "Контакты вузов без изменений", Unit.DAYS, 1, 36500,
            "app.retention.inactive-contacts", 1095),
    RETENTION_DISMISSED_PROFILES(Group.RETENTION, "Профили сотрудников с закрытым доступом", Unit.DAYS, 1, 36500,
            "app.retention.dismissed-profiles", 365),
    RETENTION_AUDIT_EVENTS(Group.RETENTION, "Журнал администратора и безопасности", Unit.DAYS, 1, 36500,
            "app.retention.audit-events", 1095),
    RETENTION_AGENT_PLANS(Group.RETENTION, "Планы записи ИИ-агента", Unit.DAYS, 1, 3650, "app.retention.agent-plans", 30),
    RETENTION_LEARNER_PROFILES(Group.RETENTION, "Анкеты слушателей после окончания потока", Unit.MONTHS, 1, 1200,
            "app.retention.learner-profiles", 36),
    RETENTION_MAIL_LETTERS(Group.RETENTION, "Журнал отправленных писем", Unit.DAYS, 1, 3650, "app.mail.retention", 30),
    RETENTION_MAX_INBOX(Group.RETENTION, "Входящие сообщения MAX", Unit.DAYS, 1, 3650, "app.max.inbox-retention", 7),
    RETENTION_MAX_OUTBOX(Group.RETENTION, "Исходящие сообщения MAX", Unit.DAYS, 1, 3650, "app.max.outbox-retention", 30),
    RETENTION_PERSONAL_DATA_REQUESTS(Group.RETENTION, "Закрытые обращения субъектов ПДн", Unit.DAYS, 1, 36500,
            "app.privacy.requests-retention", 1095),
    STUCK_DAYS(Group.SIGNALS, "«Долго на этапе», если у этапа нет норматива", Unit.DAYS, 1, 3650, "app.work.stuck-days", 30),
    SOURCES_STALE_HOURS(Group.SIGNALS, "Данные LMS и источников устарели через", Unit.HOURS, 1, 720, "app.sources.stale-after", 26),
    LMS_COMPLETION_COVERAGE(Group.SIGNALS, "Покрытие статусов завершения для доли завершивших", Unit.PERCENT, 1, 100,
            "app.lms-signals.completion-coverage-percent", 70),
    PERSONAL_DATA_RESPONSE_DAYS(Group.PRIVACY, "Срок ответа на обращение субъекта ПДн", Unit.DAYS, 1, 90,
            "app.privacy.request-response-days", 10),
    PERSONAL_DATA_RESPONSE_WORKING_DAYS(Group.PRIVACY, "Срок ответа считать в рабочих днях (пн–пт, без праздников)", Unit.FLAG, 0, 1,
            "app.privacy.request-response-working-days", 0),
        PERSONAL_DATA_REMIND_DAYS(Group.PRIVACY, "Напоминать о сроке ответа за", Unit.DAYS, 0, 30, "app.privacy.request-remind-days", 3),
    CONSENTS_CONTACTS(Group.PRIVACY, "Учёт согласий контактов вузов", Unit.FLAG, 0, 1, "app.privacy.consents.contacts", 0),
    CONSENTS_LEARNERS(Group.PRIVACY, "Учёт согласий слушателей", Unit.FLAG, 0, 1, "app.privacy.consents.learners", 0);

    public enum Group { RETENTION, SIGNALS, PRIVACY }

    public enum Unit { DAYS, HOURS, MONTHS, PERCENT, FLAG }

    // Свойства с числом дней без единицы (app.work.stuck-days: 30, а не 30d) читаются как число, а не Duration.
    private static final java.util.Set<String> PLAIN_NUMBERS = java.util.Set.of("app.work.stuck-days",
            "app.privacy.request-response-days", "app.privacy.request-remind-days");

    private final Group group;
    private final String title;
    private final Unit unit;
    private final int min;
    private final int max;
    private final String property;
    private final int fallback;

    SystemSetting(Group group, String title, Unit unit, int min, int max, String property, int fallback) {
        this.group = group;
        this.title = title;
        this.unit = unit;
        this.min = min;
        this.max = max;
        this.property = property;
        this.fallback = fallback;
    }

    public Group group() {
        return group;
    }

    public String title() {
        return title;
    }

    public Unit unit() {
        return unit;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    String property() {
        return property;
    }

    int fallback() {
        return fallback;
    }

    boolean plainNumber() {
        return PLAIN_NUMBERS.contains(property);
    }

    String format(int value) {
        return switch (unit) {
            case DAYS -> value + " дн.";
            case HOURS -> value + " ч";
            case MONTHS -> value + " мес.";
            case PERCENT -> value + " %";
            case FLAG -> value == 0 ? "выключено" : "включено";
        };
    }
}
