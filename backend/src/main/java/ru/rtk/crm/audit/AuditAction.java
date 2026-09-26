package ru.rtk.crm.audit;

public enum AuditAction {
    PROFILE_CHANGED(AuditCategory.PROFILE, "Изменён профиль CRM"),
    ACTIVATION_REQUESTED(AuditCategory.PROFILE, "Сотрудник попросил активировать профиль"),
    KAM_ASSIGNED(AuditCategory.ASSIGNMENT, "Назначен ответственный КАМ"),
    KAM_CHANGED(AuditCategory.ASSIGNMENT, "Сменён ответственный КАМ"),
    KAM_UNASSIGNED(AuditCategory.ASSIGNMENT, "Снят ответственный КАМ"),
    ORGANIZATION_TEAM_CHANGED(AuditCategory.ORGANIZATION, "Вуз перенесён в другую команду"),
    TEAM_CREATED(AuditCategory.TEAM, "Создана команда"),
    TEAM_RENAMED(AuditCategory.TEAM, "Переименована команда"),
    TEAM_ARCHIVED(AuditCategory.TEAM, "Команда перенесена в архив"),
    TEAM_RESTORED(AuditCategory.TEAM, "Команда восстановлена из архива"),
    SYNC_STARTED(AuditCategory.SYNC, "Запуск синхронизации источника"),
    ATTACHMENT_DOWNLOADED(AuditCategory.DOWNLOAD, "Скачан файл карточки"),
    REPORT_DOWNLOADED(AuditCategory.DOWNLOAD, "Скачан файл отчёта"),
    JOURNAL_EXPORTED(AuditCategory.DOWNLOAD, "Выгружен журнал"),
    SUBJECT_SEARCHED(AuditCategory.PERSONAL_DATA, "Поиск данных субъекта ПДн"),
    SUBJECT_EXPORTED(AuditCategory.PERSONAL_DATA, "Выгружены сведения о субъекте ПДн"),
    CONTACT_RECTIFIED(AuditCategory.PERSONAL_DATA, "Уточнены данные контакта"),
    CONTACT_RESTRICTED(AuditCategory.PERSONAL_DATA, "Ограничена обработка данных контакта"),
    CONTACT_RESTRICTION_LIFTED(AuditCategory.PERSONAL_DATA, "Снято ограничение обработки контакта"),
    SUBJECT_ANONYMIZED(AuditCategory.PERSONAL_DATA, "Обезличены данные субъекта ПДн"),
    RETENTION_APPLIED(AuditCategory.RETENTION, "Применены сроки хранения"),
    ACCOUNT_DISABLED(AuditCategory.ACCOUNT, "Учётная запись Keycloak отключена"),
    ACCOUNT_ENABLED(AuditCategory.ACCOUNT, "Учётная запись Keycloak включена"),
    ACCOUNT_SYNC_FAILED(AuditCategory.ACCOUNT, "Учётная запись Keycloak не синхронизирована");

    private final AuditCategory category;
    private final String label;

    AuditAction(AuditCategory category, String label) {
        this.category = category;
        this.label = label;
    }

    public AuditCategory category() {
        return category;
    }

    public String label() {
        return label;
    }
}
