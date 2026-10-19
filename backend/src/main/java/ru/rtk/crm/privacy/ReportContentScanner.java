package ru.rtk.crm.privacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

// Есть ли субъект обезличивания в готовом файле отчёта. Отчёт удаляется, только если субъект в нём найден; если
// содержимое прочитать нельзя — отчёт помечается «требует пересоздания», а не удаляется (решение 2.5, Н-2).
final class ReportContentScanner {
    enum Presence {
        FOUND,
        ABSENT,
        UNKNOWN
    }

    private ReportContentScanner() {
    }

    // employees — среди субъектов есть сотрудники. Диаграмма показывает только подписи групп: ФИО в ней может быть лишь
    // у группировки по менеджеру, а контактов вуза в диаграммах нет вовсе.
    static Presence scan(Path file, String format, String groupBy, boolean employees, List<SubjectTerms> subjects) {
        if (format == null) {
            return Presence.UNKNOWN;
        }
        if ("PNG".equals(format)) {
            return "MANAGER".equals(groupBy) && employees ? Presence.UNKNOWN : Presence.ABSENT;
        }
        String text;
        try {
            text = switch (format) {
                case "XLSX", "XLS" -> workbookText(file);
                case "PDF" -> pdfText(file);
                case "JSON" -> Files.readString(file, StandardCharsets.UTF_8);
                default -> null;
            };
        } catch (IOException | RuntimeException exception) {
            return Presence.UNKNOWN;
        }
        if (text == null) {
            return Presence.UNKNOWN;
        }
        return subjects.stream().anyMatch(subject -> subject.foundIn(text)) ? Presence.FOUND : Presence.ABSENT;
    }

    private static String workbookText(Path file) throws IOException {
        DataFormatter formatter = new DataFormatter();
        StringBuilder text = new StringBuilder();
        try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        text.append(formatter.formatCellValue(cell)).append('\n');
                    }
                }
            }
        }
        return text.toString();
    }

    private static String pdfText(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }
}
