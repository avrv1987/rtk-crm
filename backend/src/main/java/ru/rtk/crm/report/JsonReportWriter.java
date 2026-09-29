package ru.rtk.crm.report;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class JsonReportWriter {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public JsonReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
    }

    public void write(ReportDocument document, OutputStream output) throws IOException {
        ReportRequest request = document.request();
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("from", request.from());
        period.put("to", request.to());
        period.put("basis", request.periodBasis());
        period.put("fromAt", request.fromAt());
        period.put("toAt", request.toAt());
        period.put("asOf", request.asOf());

        boolean demand = request.kind() == ReportKind.DEMAND;
        boolean duration = request.kind() == ReportKind.DURATION;
        boolean agreements = request.kind() == ReportKind.AGREEMENTS;
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("rows", document.rows().size());
        if (agreements) {
            totals.put("organizations", document.organizationCount());
            totals.put("agreements", document.rows().stream().map(row -> row.agreement().agreementId()).distinct().count());
            totals.put("activities", document.rows().stream().map(row -> row.agreement().activityId())
                    .filter(Objects::nonNull).distinct().count());
        } else if (demand) {
            totals.put("applications", sum(document, ReportRow::applications));
            totals.put("paidOrders", sum(document, ReportRow::paidOrders));
            totals.put("participations", sum(document, ReportRow::participants));
            totals.put("completed", sum(document, ReportRow::completed));
            totals.put("parallelRuns", sum(document, ReportRow::parallelRuns));
        } else if (!duration) {
            totals.put("organizations", document.organizationCount());
            totals.put("interactions", document.interactionCount());
        }

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("complete", true);
        if (agreements) {
            quality.put("withoutActivities", document.countWithout(ReportColumn.ACTIVITY));
            quality.put("withoutActualVolume", document.countWithout(ReportColumn.ACTUAL_VOLUME));
            quality.put("withoutConfirmations", document.countWithout(ReportColumn.CONFIRMATIONS));
        } else {
            quality.put("withoutDirection", document.countWithout(ReportColumn.DIRECTION));
            quality.put("withoutProgram", document.countWithout(ReportColumn.PROGRAM));
        }
        if (demand) {
            quality.put("noData", Stream.of(ReportColumn.APPLICATIONS, ReportColumn.PAID_ORDERS, ReportColumn.PAID_STREAMS,
                            ReportColumn.PARTICIPANTS, ReportColumn.LEARNERS_COMPLETED, ReportColumn.COMPLETION_SHARE,
                            ReportColumn.PARALLEL_RUNS)
                    .filter(column -> document.countWithout(column) > 0)
                    .toList());
        } else if (duration) {
            quality.put("noData", Stream.of(ReportColumn.AVG_DAYS, ReportColumn.MAX_DAYS, ReportColumn.CURRENT_MAX_DAYS)
                    .filter(column -> document.countWithout(column) > 0)
                    .toList());
        } else if (!agreements) {
            quality.put("withoutProducts", document.countWithout(ReportColumn.PRODUCTS));
            quality.put("withoutManager", document.countWithout(ReportColumn.MANAGER));
        }

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("schemaVersion", SCHEMA_VERSION);
        json.put("kind", request.kind());
        json.put("title", document.title());
        json.put("generatedAt", document.generatedAt());
        json.put("timezone", ReportRequest.ZONE.getId());
        json.put("period", period);
        json.put("filters", request.filters());
        json.put("notes", document.notes());
        json.put("columns", request.columns().stream()
                .map(column -> ReportColumnView.of(column, document.columnTitle(column)))
                .toList());
        json.put("rows", document.rows().stream().map(row -> ReportService.values(request.columns(), row)).toList());
        json.put("totals", totals);
        json.put("quality", quality);
        objectMapper.writeValue(output, json);
    }

    private static Long sum(ReportDocument document, Function<ReportRow, Long> value) {
        List<Long> values = document.rows().stream().map(value).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToLong(Long::longValue).sum();
    }
}
