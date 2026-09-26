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
    STAGE("Статус работы", "Этап", false, 22, ReportRow::stageName),
    DAYS_ON_STAGE("Дней на этапе", null, false, 12, ReportRow::daysOnStage),
    WORK_STATUS("Состояние работы", null, false, 18, ReportRow::workStatus),
    WAITING("Ожидание", null, false, 26, ReportRow::waiting),
    PROBLEM("Проблема", null, false, 30, ReportRow::problem),
    RISK("Риск", null, false, 30, ReportRow::risk),
    MANAGER("Ответственный", "Ответственный на момент события", true, 24, ReportRow::managerName),
    CREATED_AT("Создано", null, false, 18, ReportRow::createdAt),
    LAST_EVENT_AT("Последнее событие", null, false, 18, ReportRow::lastEventAt),
    NEXT_ACTION("Следующий шаг", null, false, 28, ReportRow::nextAction),
    NEXT_ACTION_AT("Срок", null, false, 18, ReportRow::nextActionAt),
    EVENT_AT("Дата события", null, false, 18, ReportRow::eventAt),
    EVENT_TYPE("Действие", null, false, 22, ReportRow::eventType),
    FROM_STAGE("Из этапа", null, false, 22, ReportRow::fromStageName),
    COMMENT("Комментарий", null, false, 38, ReportRow::comment),
    AUTHOR("Автор", null, false, 22, ReportRow::authorName),
    APPLICATIONS("Заявки (сайт)", 14, ReportRow::applications),
    PARTICIPANTS("Обучающиеся (Moodle)", 14, ReportRow::participants),
    LEARNERS_COMPLETED("Завершили (Moodle)", 14, ReportRow::completed),
    PARALLEL_RUNS("Параллельные потоки (Moodle)", 14, ReportRow::parallelRuns),
    TEAM("Команда", null, false, 22, ReportRow::teamName),
    COMPLETED("Завершённых прохождений", 14, ReportRow::completedCount),
    AVG_DAYS("Средняя длительность, дн.", 14, ReportRow::averageDays),
    MAX_DAYS("Максимальная длительность, дн.", 14, ReportRow::maxDays),
    CURRENT("На этапе на конец периода", 14, ReportRow::currentCount),
    CURRENT_MAX_DAYS("Дольше всех на этапе, дн.", 14, ReportRow::currentMaxDays),
    AGREEMENT("Соглашение", null, false, 22, agreement(ReportRow.AgreementLine::agreement)),
    AGREEMENT_STATUS("Статус соглашения", null, false, 14, agreement(ReportRow.AgreementLine::agreementStatus)),
    AGREEMENT_TERM("Срок действия", null, false, 18, agreement(ReportRow.AgreementLine::agreementTerm)),
    ACTIVITY_KIND("Вид мероприятия", null, false, 26, agreement(ReportRow.AgreementLine::activityKind)),
    ACTIVITY("Мероприятие", null, false, 28, agreement(ReportRow.AgreementLine::activity)),
    PLANNED_VOLUME("Объём по плану", null, false, 10, agreement(ReportRow.AgreementLine::plannedVolume)),
    ACTUAL_VOLUME("Объём факт", null, false, 10, agreement(ReportRow.AgreementLine::actualVolume)),
    VOLUME_UNIT("Единица", null, false, 12, agreement(ReportRow.AgreementLine::unit)),
    PLANNED_DATES("Плановые сроки", null, false, 18, agreement(ReportRow.AgreementLine::plannedDates)),
    ACTUAL_DATES("Фактические сроки", null, false, 18, agreement(ReportRow.AgreementLine::actualDates)),
    ACTIVITY_STATUS("Статус мероприятия", null, false, 14, agreement(ReportRow.AgreementLine::activityStatus)),
    WORKS("Связанные работы", null, false, 26, agreement(ReportRow.AgreementLine::works)),
    CONFIRMATIONS("Подтверждения", null, "нет", 30, agreement(ReportRow.AgreementLine::confirmations)),
    CONFIRMATION_LINKS("Ссылки на подтверждения", null, "", 40, agreement(ReportRow.AgreementLine::confirmationLinks));

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
                case LAST_EVENT_AT -> "Последнее событие до даты";
                default -> title;
            };
            case DURATION -> this == STAGE ? "Этап" : title;
            case PORTFOLIO, DEMAND, AGREEMENTS -> title;
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
            case null -> emptyText();
            case OffsetDateTime dateTime -> DATE_TIME.format(dateTime.atZoneSameInstant(ReportRequest.ZONE));
            case BigDecimal decimal -> decimal.toPlainString().replace('.', ',');
            case Object other -> other.toString();
        };
    }
}
