package ru.rtk.crm.report;

import java.util.List;

public record MarketDemand(boolean applicable, Long vacancies, Long trendPercent) {
    public static final String NOT_APPLICABLE = "не применяется";
    public static final MarketDemand NOT_TRACKED = new MarketDemand(false, null, null);

    public static MarketDemand of(Integer beforePeriod, List<Integer> inPeriod) {
        if (inPeriod.isEmpty()) {
            return new MarketDemand(true, null, null);
        }
        Integer start = beforePeriod != null ? beforePeriod : inPeriod.size() > 1 ? inPeriod.getFirst() : null;
        long last = inPeriod.getLast();
        Long trend = start == null || start <= 0 ? null : Math.floorDiv((last - start) * 200 + start, start * 2L);
        return new MarketDemand(true, last, trend);
    }
}
