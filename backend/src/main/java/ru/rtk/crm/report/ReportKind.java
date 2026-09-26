package ru.rtk.crm.report;

import static ru.rtk.crm.report.ReportColumn.ACTIVITY;
import static ru.rtk.crm.report.ReportColumn.ACTIVITY_KIND;
import static ru.rtk.crm.report.ReportColumn.ACTIVITY_STATUS;
import static ru.rtk.crm.report.ReportColumn.ACTUAL_DATES;
import static ru.rtk.crm.report.ReportColumn.ACTUAL_VOLUME;
import static ru.rtk.crm.report.ReportColumn.AGREEMENT;
import static ru.rtk.crm.report.ReportColumn.AGREEMENT_STATUS;
import static ru.rtk.crm.report.ReportColumn.AGREEMENT_TERM;
import static ru.rtk.crm.report.ReportColumn.APPLICATIONS;
import static ru.rtk.crm.report.ReportColumn.AUTHOR;
import static ru.rtk.crm.report.ReportColumn.AVG_DAYS;
import static ru.rtk.crm.report.ReportColumn.COMMENT;
import static ru.rtk.crm.report.ReportColumn.CONTRACT_NUMBER;
import static ru.rtk.crm.report.ReportColumn.COMPLETED;
import static ru.rtk.crm.report.ReportColumn.CONFIRMATIONS;
import static ru.rtk.crm.report.ReportColumn.CONFIRMATION_LINKS;
import static ru.rtk.crm.report.ReportColumn.CREATED_AT;
import static ru.rtk.crm.report.ReportColumn.CURRENT;
import static ru.rtk.crm.report.ReportColumn.CURRENT_MAX_DAYS;
import static ru.rtk.crm.report.ReportColumn.DAYS_ON_STAGE;
import static ru.rtk.crm.report.ReportColumn.DIRECTION;
import static ru.rtk.crm.report.ReportColumn.EVENT_AT;
import static ru.rtk.crm.report.ReportColumn.EVENT_TYPE;
import static ru.rtk.crm.report.ReportColumn.FROM_STAGE;
import static ru.rtk.crm.report.ReportColumn.INTERACTION;
import static ru.rtk.crm.report.ReportColumn.LEARNERS_COMPLETED;
import static ru.rtk.crm.report.ReportColumn.LAST_EVENT_AT;
import static ru.rtk.crm.report.ReportColumn.LICENSE_EXPIRY_YEAR;
import static ru.rtk.crm.report.ReportColumn.LICENSE_SIGNED;
import static ru.rtk.crm.report.ReportColumn.MANAGER;
import static ru.rtk.crm.report.ReportColumn.MATERIALS_TRANSFERRED_ON;
import static ru.rtk.crm.report.ReportColumn.MAX_DAYS;
import static ru.rtk.crm.report.ReportColumn.NEXT_ACTION;
import static ru.rtk.crm.report.ReportColumn.NEXT_ACTION_AT;
import static ru.rtk.crm.report.ReportColumn.ORGANIZATION;
import static ru.rtk.crm.report.ReportColumn.PARALLEL_RUNS;
import static ru.rtk.crm.report.ReportColumn.PARTICIPANTS;
import static ru.rtk.crm.report.ReportColumn.PROBLEM;
import static ru.rtk.crm.report.ReportColumn.PLANNED_DATES;
import static ru.rtk.crm.report.ReportColumn.PLANNED_VOLUME;
import static ru.rtk.crm.report.ReportColumn.PRODUCTS;
import static ru.rtk.crm.report.ReportColumn.PROGRAM;
import static ru.rtk.crm.report.ReportColumn.RISK;
import static ru.rtk.crm.report.ReportColumn.STAGE;
import static ru.rtk.crm.report.ReportColumn.WAITING;
import static ru.rtk.crm.report.ReportColumn.WORK_STATUS;
import static ru.rtk.crm.report.ReportColumn.TRANSFER_STATUS;
import static ru.rtk.crm.report.ReportColumn.VENDORS;
import static ru.rtk.crm.report.ReportColumn.TEAM;
import static ru.rtk.crm.report.ReportColumn.VOLUME_UNIT;
import static ru.rtk.crm.report.ReportColumn.WORKS;

import java.util.List;
import java.util.stream.Stream;

public enum ReportKind {
    PORTFOLIO(
            "Портфель взаимодействий: текущее состояние",
            "портфель",
            "взаимодействия",
            "Число взаимодействий",
            List.of(ORGANIZATION, INTERACTION, DIRECTION, PROGRAM, PRODUCTS, STAGE, DAYS_ON_STAGE, WORK_STATUS, WAITING, PROBLEM, RISK,
                    MANAGER, CREATED_AT, LAST_EVENT_AT, NEXT_ACTION, NEXT_ACTION_AT),
            List.of(VENDORS, CONTRACT_NUMBER, LICENSE_SIGNED, LICENSE_EXPIRY_YEAR, TRANSFER_STATUS, MATERIALS_TRANSFERRED_ON)
    ),
    EVENTS(
            "События взаимодействий за период",
            "события",
            "события",
            "Число событий",
            List.of(EVENT_AT, ORGANIZATION, INTERACTION, EVENT_TYPE, FROM_STAGE, STAGE, COMMENT, AUTHOR,
                    MANAGER, DIRECTION, PROGRAM, PRODUCTS),
            List.of()
    ),
    DEMAND(
            "Востребованность программ",
            "востребованность",
            "заявки",
            "Число заявок",
            List.of(DIRECTION, PROGRAM, APPLICATIONS, PARTICIPANTS, LEARNERS_COMPLETED, PARALLEL_RUNS),
            List.of()
    ),
    SNAPSHOT(
            "Состояние портфеля на дату",
            "состояние",
            "взаимодействия",
            "Число взаимодействий",
            List.of(ORGANIZATION, INTERACTION, DIRECTION, PROGRAM, PRODUCTS, STAGE, MANAGER, CREATED_AT, LAST_EVENT_AT),
            List.of()
    ),
    DURATION(
            "Длительность этапов и цикла",
            "длительность",
            "группы",
            "Число групп",
            List.of(TEAM, PROGRAM, STAGE, COMPLETED, AVG_DAYS, MAX_DAYS, CURRENT, CURRENT_MAX_DAYS),
            List.of()
    ),
    AGREEMENTS(
            "Реализация соглашений с вузами",
            "соглашения",
            "мероприятия",
            "Число мероприятий",
            List.of(ORGANIZATION, AGREEMENT, AGREEMENT_STATUS, AGREEMENT_TERM, ACTIVITY_KIND, ACTIVITY, PLANNED_VOLUME,
                    ACTUAL_VOLUME, VOLUME_UNIT, PLANNED_DATES, ACTUAL_DATES, MANAGER, ACTIVITY_STATUS, PARTICIPANTS, WORKS,
                    CONFIRMATIONS, CONFIRMATION_LINKS),
            List.of()
    );

    private final String title;
    private final String fileStem;
    private final String unit;
    private final String countTitle;
    private final List<ReportColumn> defaultColumns;
    private final List<ReportColumn> columns;

    ReportKind(
            String title,
            String fileStem,
            String unit,
            String countTitle,
            List<ReportColumn> defaultColumns,
            List<ReportColumn> optionalColumns
    ) {
        this.title = title;
        this.fileStem = fileStem;
        this.unit = unit;
        this.countTitle = countTitle;
        this.defaultColumns = defaultColumns;
        this.columns = Stream.concat(defaultColumns.stream(), optionalColumns.stream()).toList();
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

    public List<ReportColumn> defaultColumns() {
        return defaultColumns;
    }

    public List<ReportColumn> columns() {
        return columns;
    }
}
