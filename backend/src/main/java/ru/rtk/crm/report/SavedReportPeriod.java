package ru.rtk.crm.report;

import java.time.LocalDate;

public enum SavedReportPeriod {
    CURRENT_MONTH(0, 1),
    PREVIOUS_MONTH(-1, 1),
    CURRENT_QUARTER(0, 3),
    PREVIOUS_QUARTER(-1, 3);

    private final int shift;
    private final int months;

    SavedReportPeriod(int shift, int months) {
        this.shift = shift;
        this.months = months;
    }

    LocalDate from(LocalDate today) {
        int firstMonth = (today.getMonthValue() - 1) / months * months + 1;
        return LocalDate.of(today.getYear(), firstMonth, 1).plusMonths((long) shift * months);
    }

    LocalDate to(LocalDate today) {
        return from(today).plusMonths(months).minusDays(1);
    }
}
