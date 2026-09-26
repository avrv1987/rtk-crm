package ru.rtk.crm.report;

import static ru.rtk.crm.report.ReportColumn.APPLICATIONS;
import static ru.rtk.crm.report.ReportColumn.AUTHOR;
import static ru.rtk.crm.report.ReportColumn.COMMENT;
import static ru.rtk.crm.report.ReportColumn.CREATED_AT;
import static ru.rtk.crm.report.ReportColumn.DIRECTION;
import static ru.rtk.crm.report.ReportColumn.EVENT_AT;
import static ru.rtk.crm.report.ReportColumn.EVENT_TYPE;
import static ru.rtk.crm.report.ReportColumn.FROM_STAGE;
import static ru.rtk.crm.report.ReportColumn.INTERACTION;
import static ru.rtk.crm.report.ReportColumn.LAST_EVENT_AT;
import static ru.rtk.crm.report.ReportColumn.MANAGER;
import static ru.rtk.crm.report.ReportColumn.NEXT_ACTION;
import static ru.rtk.crm.report.ReportColumn.NEXT_ACTION_AT;
import static ru.rtk.crm.report.ReportColumn.ORGANIZATION;
import static ru.rtk.crm.report.ReportColumn.PARALLEL_RUNS;
import static ru.rtk.crm.report.ReportColumn.PARTICIPANTS;
import static ru.rtk.crm.report.ReportColumn.PRODUCTS;
import static ru.rtk.crm.report.ReportColumn.PROGRAM;
import static ru.rtk.crm.report.ReportColumn.STAGE;

import java.util.List;

public enum ReportKind {
    PORTFOLIO(
            "Портфель взаимодействий: текущее состояние",
            "портфель",
            "взаимодействия",
            "Число взаимодействий",
            List.of(ORGANIZATION, INTERACTION, DIRECTION, PROGRAM, PRODUCTS, STAGE, MANAGER,
                    CREATED_AT, LAST_EVENT_AT, NEXT_ACTION, NEXT_ACTION_AT)
    ),
    EVENTS(
            "События взаимодействий за период",
            "события",
            "события",
            "Число событий",
            List.of(EVENT_AT, ORGANIZATION, INTERACTION, EVENT_TYPE, FROM_STAGE, STAGE, COMMENT, AUTHOR,
                    MANAGER, DIRECTION, PROGRAM, PRODUCTS)
    ),
    DEMAND(
            "Востребованность программ",
            "востребованность",
            "заявки",
            "Число заявок",
            List.of(DIRECTION, PROGRAM, APPLICATIONS, PARTICIPANTS, PARALLEL_RUNS)
    );

    private final String title;
    private final String fileStem;
    private final String unit;
    private final String countTitle;
    private final List<ReportColumn> columns;

    ReportKind(String title, String fileStem, String unit, String countTitle, List<ReportColumn> columns) {
        this.title = title;
        this.fileStem = fileStem;
        this.unit = unit;
        this.countTitle = countTitle;
        this.columns = columns;
    }

    public String title() {
        return title;
    }

    public String fileStem() {
        return fileStem;
    }

    public String unit() {
        return unit;
    }

    public String countTitle() {
        return countTitle;
    }

    public List<ReportColumn> columns() {
        return columns;
    }
}
