package ru.rtk.crm.report;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class PdfReportWriter {
    public void write(ReportDocument document, OutputStream output) throws IOException {
        ReportKind kind = document.request().kind();
        List<ReportColumn> columns = document.columns();
        PdfLayout.write(document.title(), document.generatedAt(), document.notes(), output, layout -> {
            layout.paragraph("Строк в отчёте: " + document.rows().size(), PdfLayout.NOTE_SIZE);
            layout.gap(PdfLayout.NOTE_SIZE);
            layout.table(
                    columns.stream().map(column -> column.title(kind)).toList(),
                    columns.stream().mapToInt(ReportColumn::width).toArray()
            );
            for (ReportRow row : document.rows()) {
                layout.row(columns.stream().map(column -> column.text(row)).toList());
            }
            if (document.rows().isEmpty()) {
                layout.gap(PdfLayout.NOTE_SIZE);
                layout.paragraph("Нет строк, удовлетворяющих фильтрам", PdfLayout.NOTE_SIZE);
            }
        });
    }
}
