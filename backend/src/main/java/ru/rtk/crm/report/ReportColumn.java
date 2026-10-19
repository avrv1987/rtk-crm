package ru.rtk.crm.report;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

public enum ReportColumn {
    ORGANIZATION("Вуз", null, false, 28, ReportRow::organizationName),
    INTERACTION("Взаимодействие", null, false, 26, ReportRow::interactionTitle),
    DIRECTION("ИТ-направление", null, true, 30, ReportRow::directionName),
    PROGRAM("ИТ-программа", null, true, 26, ReportRow::programName),
    PRODUCTS("ИТ-продукты", null, true, 26, ReportRow::productNames),
    VENDORS("Вендор", null, true, 24, ReportRow::vendorNames),
    CONTRACT_NUMBER("Номер договора", null, true, 22, ReportRow::contractNumbers),
    LICENSE_SIGNED("Подписание лицензии", null, true, 20, ReportRow::licenseSigned),
    LICENSE_EXPIRY_YEAR("Срок лицензии", null, true, 14, ReportRow::licenseExpiryYears),
    TRANSFER_STATUS("Статус передачи", null, true, 22, ReportRow::transferStatuses),
    MATERIALS_TRANSFERRED_ON("Передача материалов", null, true, 18, ReportRow::materialsTransferredOn),
    STAGE("Этап", null, false, 22, ReportRow::stageName),
    DAYS_ON_STAGE("Дней на этапе", null, false, 12, ReportRow::daysOnStage),
    WORK_STATUS("Статус взаимодействия", null, false, 18, ReportRow::workStatus),
    WAITING("Ожидание", null, false, 26, ReportRow::waiting),
    PROBLEM("Проблема", null, false, 30, ReportRow::problem),
    RISK("Риск", null, false, 30, ReportRow::risk),
    MANAGER("Ответственный", "Ответственный на момент события", true, 24, ReportRow::managerName),
    CREATED_AT("Создано", null, false, 18, ReportRow::createdAt),
    LAST_EVENT_AT("Последнее действие сотрудника", null, "не зафиксировано", 18, ReportRow::lastEventAt),
    NEXT_ACTION("Следующий шаг", null, false, 28, ReportRow::nextAction),
    NEXT_ACTION_AT("Срок", null, false, 18, ReportRow::nextActionAt),
    EVENT_AT("Дата события", null, false, 18, ReportRow::eventAt),
    EVENT_TYPE("Действие", null, false, 22, ReportRow::eventType),
    FROM_STAGE("Из этапа", null, false, 22, ReportRow::fromStageName),
    COMMENT("Комментарий", null, false, 38, ReportRow::comment),
    AUTHOR("Автор", null, false, 22, ReportRow::authorName),
    APPLICATIONS("Заявки (сайт)", 14, ReportRow::applications),
    PAID_ORDERS("Оплаченные заявки (сайт)", 14, ReportRow::paidOrders),
    PAID_STREAMS("Потоки с оплатами (сайт)", 14, ReportRow::paidStreams),
    PARTICIPANTS("Обучающиеся (Moodle)", 14, ReportRow::participants),
    LEARNERS_COMPLETED("Завершили (Moodle)", 14, ReportRow::completed),
    COMPLETION_SHARE("Доля завершивших среди известных, %", 14, ReportRow::completionPercent),
    COMPLETION_BASIS("Основание доли", null, "потоков в периоде нет", 34, ReportRow::completionBasis),
    PARALLEL_RUNS("Параллельные потоки (Moodle)", 14, ReportRow::parallelRuns),
    MARKET_VACANCIES("Спрос рынка: вакансии", 16, ReportRow::marketVacancies),
    MARKET_TREND("Тренд спроса, %", 14, ReportRow::marketTrend),
    TEAM("Команда", null, false, 22, ReportRow::teamName),
    LAST_STAFF_ACTION("Последнее действие сотрудника по вузу", null, "не зафиксировано", 20, ReportRow::lastEventAt),
    COMPLETED("Завершённых прохождений", 14, ReportRow::completedCount),
    MEDIAN_DAYS("Медиана длительности, дн.", 14, ReportRow::medianDays),
    P90_DAYS("90-й процентиль длительности, дн.", 14, ReportRow::p90Days),
    AVG_DAYS("Средняя длительность, дн.", 14, ReportRow::averageDays),
    MAX_DAYS("Максимальная длительность, дн.", 14, ReportRow::maxDays),
    CURRENT("На этапе на конец периода", 14, ReportRow::currentCount),
    CURRENT_MAX_DAYS("Дольше всех на этапе, дн.", 14, ReportRow::currentMaxDays),
    AGREEMENT("Договор", null, false, 22, agreement(ReportRow.AgreementLine::agreement)),
    AGREEMENT_STATUS("Статус договора", null, false, 14, agreement(ReportRow.AgreementLine::agreementStatus)),
    AGREEMENT_TERM("Срок действия", null, false, 18, agreement(ReportRow.AgreementLine::agreementTerm)),
    ACTIVITY_KIND("Вид мероприятия", null, false, 26, agreement(ReportRow.AgreementLine::activityKind)),
    ACTIVITY("Мероприятие", null, false, 28, agreement(ReportRow.AgreementLine::activity)),
    PLANNED_VOLUME("Объём по плану", null, false, 10, agreement(ReportRow.AgreementLine::plannedVolume)),
    ACTUAL_VOLUME("Объём факт", null, false, 10, agreement(ReportRow.AgreementLine::actualVolume)),
    VOLUME_UNIT("Единица", null, false, 12, agreement(ReportRow.AgreementLine::unit)),
    PLANNED_DATES("Плановые сроки", null, false, 18, agreement(ReportRow.AgreementLine::plannedDates)),
    ACTUAL_DATES("Фактические сроки", null, false, 18, agreement(ReportRow.AgreementLine::actualDates)),
    ACTIVITY_STATUS("Статус мероприятия", null, false, 14, agreement(ReportRow.AgreementLine::activityStatus)),
    WORKS("Связанные взаимодействия", null, false, 26, agreement(ReportRow.AgreementLine::works)),
    CONFIRMATIONS("Подтверждения", null, "нет", 30, agreement(ReportRow.AgreementLine::confirmations)),
    CONFIRMATION_LINKS("Ссылки на подтверждения", null, "", 40, agreement(ReportRow.AgreementLine::confirmationLinks)),
    STAGE_AT_START("Этап на начало периода", null, "—", 24, ReportRow::stageAtStart),
    STAGE_AT_END("Этап на конец периода", null, "—", 24, ReportRow::stageAtEnd),
    STAGE_POSITION("Положение в маршруте", null, "—", 16, ReportRow::stagePosition),
    TRANSITIONS("Переходов за период", 12, ReportRow::transitions),
    PERIOD_COMMENTS("Комментарии за период", null, "—", 50, ReportRow::periodComments),
    DUE_STATE("Состояние срока", null, "", 24, ReportRow::dueState),
    OVERDUE_DAYS("Просрочено, дн.", null, "", 12, ReportRow::overdueDays),
    LMS_LEARNING("Обучение (LMS) сейчас", null, "—", 40, ReportRow::lmsLearning),
    LMS_CHECK("Сверка этапа с LMS", null, "", 36, ReportRow::lmsCheck);

    public static final String UNSPECIFIED = "Не указано";
    public static final String NO_DATA = "нет данных";
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final String title;
    private final String eventsTitle;
    private final String emptyText;
    private final int width;
    private final Function<ReportRow, Object> value;

    ReportColumn(String title, String eventsTitle, boolean dimension, int width, Function<ReportRow, Object> value) {
        this(title, eventsTitle, dimension ? UNSPECIFIED : "", width, value);
    }

    ReportColumn(String title, int width, Function<ReportRow, Object> value) {
        this(title, null, NO_DATA, width, value);
    }

    ReportColumn(String title, String eventsTitle, String emptyText, int width, Function<ReportRow, Object> value) {
        this.title = title;
        this.eventsTitle = eventsTitle;
        this.emptyText = emptyText;
        this.width = width;
        this.value = value;
    }

    public String title(ReportKind kind) {
        return switch (kind) {
            case EVENTS -> eventsTitle != null ? eventsTitle : title;
            case SNAPSHOT -> switch (this) {
                case STAGE -> "Этап на дату";
                case MANAGER -> "Ответственный на дату";
                case LAST_EVENT_AT -> "Последнее действие сотрудника до даты";
                default -> title;
            };
            case MANAGER_WORK -> switch (this) {
                case MANAGER -> "Менеджер";
                case INTERACTION -> "Взаимодействие";
                default -> title;
            };
            case PORTFOLIO, DURATION, DEMAND, AGREEMENTS -> title;
        };
    }

    public String emptyText() {
        return emptyText;
    }

    public int width() {
        return width;
    }

    public Object value(ReportRow row) {
        return value.apply(row);
    }

    private static Function<ReportRow, Object> agreement(Function<ReportRow.AgreementLine, Object> value) {
        return row -> row.agreement() == null ? null : value.apply(row.agreement());
    }

    public String text(ReportRow row) {
        return switch (value(row)) {
            case null -> emptyText;
            case OffsetDateTime dateTime -> DATE_TIME.format(dateTime.atZoneSameInstant(ReportRequest.ZONE));
            case BigDecimal decimal -> decimal.toPlainString().replace('.', ',');
            case Object other -> other.toString();
        };
    }
}
