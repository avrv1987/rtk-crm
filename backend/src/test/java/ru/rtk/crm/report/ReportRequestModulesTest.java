package ru.rtk.crm.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ReportRequestModulesTest {
    @Test
    void demandKeepsPaymentColumnsAndSortWhileTheEnrolmentModuleIsOn() {
        ReportRequest request = demand(null, ReportColumn.PAID_ORDERS).normalized(true);

        assertThat(request.columns()).contains(ReportColumn.PAID_ORDERS, ReportColumn.PAID_STREAMS);
        assertThat(request.sortBy()).isEqualTo(ReportColumn.PAID_ORDERS);
    }

    @Test
    void demandWithoutTheModuleDropsPaymentColumnsFromDefaultsAndFallsBackToApplicationsSort() {
        ReportRequest request = demand(null, ReportColumn.PAID_STREAMS).normalized(false);

        assertThat(request.columns()).doesNotContain(ReportColumn.PAID_ORDERS, ReportColumn.PAID_STREAMS)
                .contains(ReportColumn.APPLICATIONS, ReportColumn.PARTICIPANTS);
        assertThat(request.sortBy()).isEqualTo(ReportColumn.APPLICATIONS);
    }

    @Test
    void demandWithoutTheModuleDropsExplicitPaymentColumnsAndKeepsTheOthersInOrder() {
        ReportRequest request = demand(
                List.of(ReportColumn.PAID_ORDERS, ReportColumn.PROGRAM, ReportColumn.PAID_STREAMS, ReportColumn.APPLICATIONS),
                ReportColumn.PARTICIPANTS
        ).normalized(false);

        assertThat(request.columns()).containsExactly(ReportColumn.PROGRAM, ReportColumn.APPLICATIONS);
        assertThat(request.sortBy()).isEqualTo(ReportColumn.PARTICIPANTS);
    }

    @Test
    void otherReportKindsAreNotAffectedByTheModule() {
        ReportRequest request = new ReportRequest(ReportKind.SNAPSHOT, null, null, null, ReportFilters.none(), null, null, null,
                null, null, null, null).normalized(false);

        assertThat(request.columns()).isEqualTo(ReportKind.SNAPSHOT.defaultColumns());
    }

    private static ReportRequest demand(List<ReportColumn> columns, ReportColumn sortBy) {
        return new ReportRequest(ReportKind.DEMAND, null, null, null, ReportFilters.none(), columns, null, null, sortBy, null,
                null, null);
    }
}
