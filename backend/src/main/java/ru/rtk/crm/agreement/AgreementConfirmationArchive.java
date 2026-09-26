package ru.rtk.crm.agreement;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Component;
import ru.rtk.crm.agreement.AgreementModels.Confirmation;
import ru.rtk.crm.agreement.AgreementRepository.ConfirmationRow;
import ru.rtk.crm.attachment.AttachmentStatus;
import ru.rtk.crm.attachment.AttachmentStorage;
import ru.rtk.crm.attachment.AttachmentStorageException;
import ru.rtk.crm.report.ReportRequest;

@Component
public class AgreementConfirmationArchive {
    static final String INVENTORY_NAME = "опись.csv";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int SEGMENT_LIMIT = 90;
    private static final List<String> HEADER = List.of(
            "№", "Вуз", "Соглашение", "Вид мероприятия", "Мероприятие", "Сроки мероприятия", "Работа",
            "Исходное имя файла", "Загружен", "Размер, байт", "Файл в архиве", "Примечание"
    );

    private final AttachmentStorage storage;

    public AgreementConfirmationArchive(AttachmentStorage storage) {
        this.storage = storage;
    }

    public void write(List<ConfirmationRow> rows, OutputStream output) throws IOException {
        List<List<String>> inventory = new ArrayList<>();
        inventory.add(HEADER);
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            int index = 0;
            for (ConfirmationRow row : rows) {
                index++;
                Confirmation confirmation = row.confirmation();
                String entryName = entryName(index, confirmation);
                String note = "";
                if (!AttachmentStatus.CLEAN.name().equals(confirmation.status())) {
                    entryName = "";
                    note = "Не включён: файл не прошёл проверку";
                } else {
                    try (InputStream input = storage.open(row.storageKey())) {
                        zip.putNextEntry(new ZipEntry(entryName));
                        input.transferTo(zip);
                        zip.closeEntry();
                    } catch (AttachmentStorageException exception) {
                        entryName = "";
                        note = "Не включён: файл не найден в хранилище";
                    }
                }
                inventory.add(List.of(
                        Integer.toString(index),
                        confirmation.organizationName(),
                        confirmation.agreementNumber(),
                        confirmation.kindName(),
                        confirmation.activityTitle(),
                        period(confirmation.activityStart(), confirmation.activityEnd()),
                        confirmation.interactionTitle(),
                        confirmation.originalName(),
                        DATE_TIME.format(confirmation.createdAt().atZoneSameInstant(ReportRequest.ZONE)),
                        Long.toString(confirmation.sizeBytes()),
                        entryName,
                        note
                ));
            }
            zip.putNextEntry(new ZipEntry(INVENTORY_NAME));
            zip.write(csv(inventory).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    static String period(LocalDate start, LocalDate end) {
        if (start == null && end == null) {
            return "";
        }
        if (start != null && start.equals(end)) {
            return DATE.format(start);
        }
        return (start == null ? "…" : DATE.format(start)) + " – " + (end == null ? "…" : DATE.format(end));
    }

    private static String entryName(int index, Confirmation confirmation) {
        return segment(confirmation.kindName()) + "/"
                + segment(confirmation.organizationName() + " — " + confirmation.agreementNumber()) + "/"
                + "%03d_".formatted(index) + fileName(confirmation.originalName());
    }

    private static String fileName(String value) {
        String clean = clean(value);
        int dot = clean.lastIndexOf('.');
        if (clean.length() <= SEGMENT_LIMIT || dot < 1 || clean.length() - dot > 10) {
            return truncate(clean);
        }
        String extension = clean.substring(dot);
        return clean.substring(0, SEGMENT_LIMIT - extension.length()) + extension;
    }

    private static String segment(String value) {
        return truncate(clean(value));
    }

    private static String clean(String value) {
        String clean = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ").strip();
        clean = clean.replaceAll("^[. ]+|[. ]+$", "");
        return clean.isEmpty() ? "_" : clean;
    }

    private static String truncate(String value) {
        return value.length() <= SEGMENT_LIMIT ? value : value.substring(0, SEGMENT_LIMIT);
    }

    private static String csv(List<List<String>> rows) {
        StringBuilder builder = new StringBuilder("\uFEFF");
        for (List<String> row : rows) {
            List<String> cells = row.stream().map(AgreementConfirmationArchive::cell).toList();
            builder.append(String.join(";", cells)).append("\r\n");
        }
        return builder.toString();
    }

    private static String cell(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
