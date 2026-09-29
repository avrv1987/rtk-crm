package ru.rtk.crm.audit;

public enum AuditAction {
    PROFILE_CHANGED(AuditCategory.PROFILE, "Изменён профиль CRM"),
    ACTIVATION_REQUESTED(AuditCategory.PROFILE, "Сотрудник попросил активировать профиль"),
    ENROLMENT_OPERATOR_CHANGED(AuditCategory.LEARNER, "Изменён флаг «Оператор зачисления»"),
    KAM_ASSIGNED(AuditCategory.ASSIGNMENT, "Назначен ответственный КАМ"),
    KAM_CHANGED(AuditCategory.ASSIGNMENT, "Сменён ответственный КАМ"),
    KAM_UNASSIGNED(AuditCategory.ASSIGNMENT, "Снят ответственный КАМ"),
    ORGANIZATION_TEAM_CHANGED(AuditCategory.ORGANIZATION, "Вуз перенесён в другую команду"),
    AGREEMENT_PLAN_CHANGED(AuditCategory.ORGANIZATION, "Изменён план подписания или продления соглашения"),
    TEAM_CREATED(AuditCategory.TEAM, "Создана команда"),
    TEAM_RENAMED(AuditCategory.TEAM, "Переименована команда"),
    TEAM_ARCHIVED(AuditCategory.TEAM, "Команда перенесена в архив"),
    TEAM_RESTORED(AuditCategory.TEAM, "Команда восстановлена из архива"),
    SYNC_STARTED(AuditCategory.SYNC, "Запуск синхронизации источника"),
    SOURCE_SETTINGS_CHANGED(AuditCategory.SYNC, "Изменены настройки подключения источников"),
    ATTACHMENT_DOWNLOADED(AuditCategory.DOWNLOAD, "Скачан файл карточки"),
    ATTACHMENT_PREVIEWED(AuditCategory.DOWNLOAD, "Просмотрен файл карточки"),
    REPORT_DOWNLOADED(AuditCategory.DOWNLOAD, "Скачан файл отчёта"),
    JOURNAL_EXPORTED(AuditCategory.DOWNLOAD, "Выгружен журнал"),
    CONFIRMATIONS_DOWNLOADED(AuditCategory.DOWNLOAD, "Скачан архив подтверждений соглашений"),
    SUBJECT_SEARCHED(AuditCategory.PERSONAL_DATA, "Поиск данных субъекта ПДн"),
    SUBJECT_EXPORTED(AuditCategory.PERSONAL_DATA, "Выгружены сведения о субъекте ПДн"),
    CONTACT_RECTIFIED(AuditCategory.PERSONAL_DATA, "Уточнены данные контакта"),
    CONTACT_RESTRICTED(AuditCategory.PERSONAL_DATA, "Ограничена обработка данных контакта"),
    CONTACT_RESTRICTION_LIFTED(AuditCategory.PERSONAL_DATA, "Снято ограничение обработки контакта"),
    SUBJECT_ANONYMIZED(AuditCategory.PERSONAL_DATA, "Обезличены данные субъекта ПДн"),
    RETENTION_APPLIED(AuditCategory.RETENTION, "Применены сроки хранения"),
    ACCOUNT_DISABLED(AuditCategory.ACCOUNT, "Учётная запись Keycloak отключена"),
    ACCOUNT_ENABLED(AuditCategory.ACCOUNT, "Учётная запись Keycloak включена"),
    ACCOUNT_SYNC_FAILED(AuditCategory.ACCOUNT, "Учётная запись Keycloak не синхронизирована"),
    ACCOUNT_CREATED(AuditCategory.ACCOUNT, "Создана учётная запись Keycloak"),
    ACCOUNT_PASSWORD_RESET(AuditCategory.ACCOUNT, "Выдан новый временный пароль Keycloak"),
    ACCOUNT_SESSIONS_ENDED(AuditCategory.ACCOUNT, "Завершены сеансы учётной записи"),
    ACCOUNT_EMAIL_CHANGED(AuditCategory.ACCOUNT, "Изменена почта учётной записи Keycloak"),
    ACCOUNT_SECOND_FACTOR_RESET(AuditCategory.ACCOUNT, "Сброшен второй фактор учётной записи"),
    LEARNER_RESTRICTED(AuditCategory.PERSONAL_DATA, "Ограничена обработка анкеты слушателя"),
    LEARNER_RESTRICTION_LIFTED(AuditCategory.PERSONAL_DATA, "Снято ограничение обработки анкеты слушателя"),
    PAID_ORDERS_UPLOADED(AuditCategory.LEARNER, "Загружен файл оплат"),
    LEARNERS_SYNCED(AuditCategory.LEARNER, "Оплаты сайта приняты в модуль «Слушатели»"),
    LEARNER_LIST_VIEWED(AuditCategory.LEARNER, "Открыт список слушателей потока"),
    LEARNER_SEARCHED(AuditCategory.LEARNER, "Поиск слушателя"),
    LEARNER_VIEWED(AuditCategory.LEARNER, "Открыта анкета слушателя"),
    LEARNER_FIELDS_REVEALED(AuditCategory.LEARNER, "Показаны скрытые поля анкеты"),
    LEARNER_CHANGED(AuditCategory.LEARNER, "Изменена анкета слушателя"),
    LEARNER_ENROLMENTS_MOVED(AuditCategory.LEARNER, "Зачисления перенесены к слушателю с тем же СНИЛС"),
    LEARNER_TEMPLATE_PREVIEWED(AuditCategory.LEARNER, "Предпросмотр заполненного шаблона анкет"),
    LEARNER_TEMPLATE_IMPORTED(AuditCategory.LEARNER, "Применён заполненный шаблон анкет"),
    LMS_ROSTER_EXPORTED(AuditCategory.LEARNER, "Сформирован файл для LMS"),
    LMS_ROSTER_MARKED(AuditCategory.LEARNER, "Слушатели отмечены переданными в LMS"),
    STREAM_END_DATE_CHANGED(AuditCategory.LEARNER, "Изменена дата окончания потока"),
    TEACHER_ROSTER_EXPORTED(AuditCategory.DOWNLOAD, "Скачан файл преподавателей вуза для LMS"),
    TEACHER_ROSTER_MARKED(AuditCategory.DOWNLOAD, "Преподаватели отмечены переданными LMS-команде");

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
