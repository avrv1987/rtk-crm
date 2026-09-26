package ru.rtk.crm.report;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

public enum ReportColumn {
    ORGANIZATION("Вуз", null, false, 28, ReportRow::organizationName),
    INTERACTION("Взаимодействие", null, false, 26, ReportRow::interactionTitle),
    DIRECTION("ИТ-направление", null, true, 30, ReportRow::directionName),
    PROGRAM("ИТ-программа", null, true, 26, ReportRow::programName),
    PRODUCTS("ИТ-продукты", null, true, 26, ReportRow::productNames),
    STAGE("Статус работы", "Этап", false, 22, ReportRow::stageName),
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
    PARALLEL_RUNS("Параллельные потоки (Moodle)", 14, ReportRow::parallelRuns);

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
        return kind == ReportKind.EVENTS && eventsTitle != null ? eventsTitle : title;
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

    public String text(ReportRow row) {
        return switch (value(row)) {
            case null -> emptyText();
            case OffsetDateTime dateTime -> DATE_TIME.format(dateTime.atZoneSameInstant(ReportRequest.ZONE));
            case Object other -> other.toString();
        };
    }
}
