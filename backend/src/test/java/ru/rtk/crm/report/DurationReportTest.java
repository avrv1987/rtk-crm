package ru.rtk.crm.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import ru.rtk.crm.report.ReportRepository.StageEntry;

class DurationReportTest {
    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-01-01T00:00:00+03:00");

    // Один долгий случай тянет среднее, но не медиану: 1, 2, 3, 4 и 40 дней — среднее 10, медиана 3, P90 40.
    @Test
    void medianAndP90AreShownBesideAverageWithNumberOfCompletedPassages() {
        List<StageEntry> entries = new ArrayList<>();
        for (int days : List.of(1, 2, 3, 4, 40)) {
            UUID interaction = UUID.randomUUID();
            entries.add(entry(interaction, "Встреча", START, 1, false));
            entries.add(entry(interaction, "Итог", START.plusDays(days), 2, true));
        }

        ReportRow cycle = DurationReport.rows(entries, List.of(), null, START.plusDays(100)).getFirst();

        assertThat(cycle.stageName()).isEqualTo(DurationReport.WHOLE_CYCLE);
        assertThat(cycle.completedCount()).isEqualTo(5L);
        assertThat(cycle.averageDays()).isEqualByComparingTo("10.0");
        assertThat(cycle.medianDays()).isEqualByComparingTo("3.0");
        assertThat(cycle.p90Days()).isEqualByComparingTo("40.0");
        assertThat(cycle.maxDays()).isEqualByComparingTo("40.0");
    }

    @Test
    void evenCountMedianIsMeanOfTwoMiddleValuesAndP90UsesNearestRank() {
        List<Long> sorted = new ArrayList<>();
        for (long value = 1; value <= 20; value++) {
            sorted.add(value);
        }

        assertThat(DurationReport.nearestRank(sorted, 90)).isEqualTo(18L);
        assertThat(DurationReport.nearestRank(List.of(7L), 90)).isEqualTo(7L);

        List<StageEntry> entries = new ArrayList<>();
        for (int days : List.of(2, 4)) {
            UUID interaction = UUID.randomUUID();
            entries.add(entry(interaction, "Встреча", START, 1, false));
            entries.add(entry(interaction, "Итог", START.plusDays(days), 2, true));
        }
        BigDecimal median = DurationReport.rows(entries, List.of(), null, START.plusDays(100)).getFirst().medianDays();
        assertThat(median).isEqualByComparingTo("3.0");
    }

    @Test
    void withoutCompletedPassagesMedianAndP90AreUnknownNotZero() {
        UUID interaction = UUID.randomUUID();
        ReportRow cycle = DurationReport.rows(List.of(entry(interaction, "Встреча", START, 1, false)), List.of(), null,
                START.plusDays(10)).getFirst();

        assertThat(cycle.completedCount()).isZero();
        assertThat(cycle.medianDays()).isNull();
        assertThat(cycle.p90Days()).isNull();
        assertThat(ReportColumn.MEDIAN_DAYS.text(cycle)).isEqualTo("нет данных");
    }

    private static StageEntry entry(UUID interaction, String stage, OffsetDateTime at, int order, boolean last) {
        return new StageEntry(interaction, START, TEAM, "Команда А", null, null, stage, at, order, last);
    }
}
