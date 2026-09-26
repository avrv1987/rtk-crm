package ru.rtk.crm.privacy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.rtk.crm.report.PdfLayout;

@Component
public class SubjectReportWriter {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Moscow"));

    private final ObjectMapper objectMapper;

    public SubjectReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    byte[] json(SubjectReport report) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
        } catch (IOException exception) {
            throw new UncheckedIOException("Subject report cannot be written as JSON", exception);
        }
    }

    byte[] pdf(SubjectReport report) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            PdfLayout.write(
                    "Сведения о субъекте персональных данных",
                    report.generatedAt(),
                    notes(report),
                    output,
                    layout -> {
                        section(layout, "1. Цели и правовые основания обработки", List.of("Субъекты", "Цель", "Основание"),
                                new int[]{20, 40, 40}, report.purposes(),
                                purpose -> List.of(purpose.subjects(), purpose.purpose(), purpose.legalBasis()));
                        section(layout, "2. Контакты представителей вузов и школ",
                                List.of("Вуз", "ФИО", "Должность", "Почта", "Телефон", "Статус", "Добавлен"),
                                new int[]{18, 18, 14, 16, 12, 10, 12}, report.data().contacts(),
                                contact -> List.of(contact.organizationName(), contact.name(), text(contact.position()),
                                        text(contact.email()), text(contact.phone()), statusLabel(contact),
                                        date(contact.createdAt()) + " " + text(contact.createdByName())));
                        section(layout, "3. Профили сотрудников в CRM", List.of("Имя", "Логин", "Состояние"),
                                new int[]{40, 30, 30}, report.data().profiles(),
                                profile -> List.of(profile.displayName(), text(profile.login()), profileState(profile)));
                        section(layout, "4. Упоминания в карточках взаимодействий",
                                List.of("Вуз", "Карточка", "Где", "Текст", "Дата"),
                                new int[]{16, 18, 14, 40, 12}, report.data().mentions(),
                                mention -> List.of(mention.organizationName(), mention.interactionTitle(),
                                        mention.place().label(), mention.text(), date(mention.occurredAt())));
                        section(layout, "5. Файлы, в названии которых есть данные субъекта",
                                List.of("Вуз", "Карточка", "Файл", "Загружен"),
                                new int[]{22, 26, 36, 16}, report.data().attachments(),
                                attachment -> List.of(attachment.organizationName(), attachment.interactionTitle(),
                                        attachment.fileName(), date(attachment.createdAt())));
                        section(layout, "6. Записи источников (сайт, LMS)",
                                List.of("Источник", "Тип", "Идентификатор", "Статус", "Вуз", "Дата"),
                                new int[]{12, 16, 20, 14, 22, 16}, report.data().sourceRecords(),
                                record -> List.of(record.source(), record.recordType(), record.externalId(), record.status(),
                                        text(record.organizationName()), date(record.submittedAt())));
                        layout.gap(PdfLayout.NOTE_SIZE);
                        layout.paragraph("7. Сроки хранения", PdfLayout.TITLE_SIZE);
                        for (String line : report.retention()) {
                            layout.paragraph(line, PdfLayout.NOTE_SIZE);
                        }
                        layout.gap(PdfLayout.NOTE_SIZE);
                        layout.paragraph("8. Источники и получатели", PdfLayout.TITLE_SIZE);
                        layout.paragraph("Источники: " + report.sources(), PdfLayout.NOTE_SIZE);
                        layout.paragraph("Получатели: " + report.recipients(), PdfLayout.NOTE_SIZE);
                    }
            );
        } catch (IOException exception) {
            throw new UncheckedIOException("Subject report cannot be written as PDF", exception);
        }
        return output.toByteArray();
    }

    private static List<String> notes(SubjectReport report) {
        List<String> notes = new ArrayList<>();
        notes.add("Оператор: " + report.operator());
        SubjectQuery terms = report.searchTerms();
        notes.add("Поиск: ФИО «" + text(terms.name()) + "», почта «" + text(terms.email()) + "», телефон «"
                + text(terms.phone()) + "», другие написания «" + text(terms.otherSpellings()) + "»");
        if (report.data().truncated()) {
            notes.add("Показаны первые 200 строк каждого раздела; уточните условия поиска");
        }
        return notes;
    }

    private static <T> void section(
            PdfLayout layout,
            String title,
            List<String> headers,
            int[] weights,
            List<T> rows,
            Function<T, List<String>> values
    ) throws IOException {
        layout.gap(PdfLayout.NOTE_SIZE);
        layout.paragraph(title, PdfLayout.TITLE_SIZE);
        if (rows.isEmpty()) {
            layout.paragraph("Не найдено", PdfLayout.NOTE_SIZE);
            return;
        }
        layout.table(headers, weights);
        for (T row : rows) {
            layout.row(values.apply(row));
        }
    }

    private static String statusLabel(SubjectContact contact) {
        return switch (contact.status()) {
            case ACTIVE -> "обрабатывается";
            case RESTRICTED -> "обработка ограничена";
            case ANONYMIZED -> "обезличен";
        };
    }

    private static String profileState(SubjectProfile profile) {
        if (profile.anonymized()) {
            return "обезличен";
        }
        if (profile.active()) {
            return "доступ открыт";
        }
        return profile.pendingActivation() ? "ожидает активации" : "доступ закрыт";
    }

    private static String date(OffsetDateTime value) {
        return value == null ? "" : DATE_TIME.format(value);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
