package ru.rtk.crm.source;

import java.time.LocalDate;
import java.time.YearMonth;

record LaborMarketItem(
        String directionCode,
        String directionName,
        YearMonth month,
        int openVacancies,
        Integer medianSalary,
        String dataSource,
        LocalDate asOf
) {
    String key() {
        return directionCode + "|" + month;
    }
}
