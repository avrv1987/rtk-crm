package ru.rtk.crm.report;

public enum StatisticsGroupBy {
    STAGE("по этапам", "Этап", false),
    ORGANIZATION("по вузам", "Вуз", false),
    DIRECTION("по ИТ-направлениям", "ИТ-направление", true),
    PROGRAM("по ИТ-программам", "ИТ-программа", true),
    PRODUCT("по ИТ-продуктам", "ИТ-продукт", true),
    MANAGER("по ответственным", "Ответственный", true),
    MONTH("по месяцам", "Месяц", false);

    private final String title;
    private final String header;
    private final boolean mayBeUnspecified;

    StatisticsGroupBy(String title, String header, boolean mayBeUnspecified) {
        this.title = title;
        this.header = header;
        this.mayBeUnspecified = mayBeUnspecified;
    }

    public String title() {
        return title;
    }

    public String header(ReportKind kind) {
        return this == MANAGER ? ReportColumn.MANAGER.title(kind) : header;
    }

    public boolean mayBeUnspecified() {
        return mayBeUnspecified;
    }
}
