package ru.rtk.crm.report;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class PdfReportWriter {
    public void write(ReportDocument document, OutputStream output) throws IOException {
        List<ReportColumn> columns = document.columns();
        PdfLayout.write(document.title(), document.generatedAt(), document.notes(), output, layout -> {
            layout.paragraph("Строк в отчёте: " + document.rows().size(), PdfLayout.NOTE_SIZE);
            layout.gap(PdfLayout.NOTE_SIZE);
            layout.table(
                    columns.stream().map(document::columnTitle).toList(),
                    columns.stream().mapToInt(ReportColumn::width).toArray()
            );
            for (ReportRow row : document.rows()) {
                layout.row(columns.stream().map(column -> column.text(row)).toList());
            }
            if (document.rows().isEmpty()) {
                layout.gap(PdfLayout.NOTE_SIZE);
                layout.paragraph("Нет строк, удовлетворяющих фильтрам", PdfLayout.NOTE_SIZE);
            }
            if (!document.groupTotals().isEmpty()) {
                layout.gap(PdfLayout.NOTE_SIZE);
                layout.paragraph(ExcelReportWriter.TOTALS_SHEET, PdfLayout.NOTE_SIZE);
                layout.table(document.totalTitles(ManagerWorkTotal.TITLES), ManagerWorkTotal.WIDTHS);
                for (ManagerWorkTotal total : document.groupTotals()) {
                    layout.row(texts(total.values()));
                }
            }
            groupSection(layout, document, ExcelReportWriter.PRODUCT_TOTALS_SHEET, "Продукт", document.productTotals());
            groupSection(layout, document, ExcelReportWriter.ORGANIZATION_TOTALS_SHEET, "Вуз", document.organizationTotals());
        });
    }

    private static void groupSection(PdfLayout layout, ReportDocument document, String title, String first, List<ManagerWorkGroupTotal> totals) throws IOException {
        if (totals.isEmpty()) {
            return;
        }
        layout.gap(PdfLayout.NOTE_SIZE);
        layout.paragraph(title, PdfLayout.NOTE_SIZE);
        layout.table(document.totalTitles(ManagerWorkGroupTotal.titles(first)), ManagerWorkGroupTotal.widths());
        for (ManagerWorkGroupTotal total : totals) {
            layout.row(texts(total.values()));
        }
    }

    private static List<String> texts(List<Object> values) {
        return values.stream().map(value -> value == null ? "" : value.toString()).toList();
    }
}
