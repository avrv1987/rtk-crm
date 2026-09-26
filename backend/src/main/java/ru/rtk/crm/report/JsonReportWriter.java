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

        boolean demand = request.kind() == ReportKind.DEMAND;
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("rows", document.rows().size());
        if (demand) {
            totals.put("applications", sum(document, ReportRow::applications));
            totals.put("participations", sum(document, ReportRow::participants));
            totals.put("parallelRuns", sum(document, ReportRow::parallelRuns));
        } else {
            totals.put("organizations", document.organizationCount());
            totals.put("interactions", document.interactionCount());
        }

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("complete", true);
        quality.put("withoutDirection", document.countWithout(ReportColumn.DIRECTION));
        quality.put("withoutProgram", document.countWithout(ReportColumn.PROGRAM));
        if (demand) {
            quality.put("noData", Stream.of(ReportColumn.APPLICATIONS, ReportColumn.PARTICIPANTS, ReportColumn.PARALLEL_RUNS)
                    .filter(column -> document.countWithout(column) > 0)
                    .toList());
        } else {
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
        json.put("columns", ReportService.columnViews(request));
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
