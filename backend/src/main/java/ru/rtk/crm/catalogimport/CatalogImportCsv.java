package ru.rtk.crm.catalogimport;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ru.rtk.crm.interaction.InteractionValidationException;

// CSV читается как лист «CSV» с теми же правилами, что лист книги: первая непустая строка — заголовки, лимиты строк,
// столбцов и длины ячейки общие. Excel в русской локали сохраняет CSV в Windows-1251 с «;», выгрузки систем — в UTF-8
// с «,» или «;», поэтому кодировка и разделитель определяются по файлу. Кавычки — по RFC 4180, перевод строки внутри
// кавычек остаётся частью ячейки (многострочные комментарии истории).
final class CatalogImportCsv {
    static final String SHEET = "CSV";
    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");

    private CatalogImportCsv() {
    }

    static boolean isCsv(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    static CatalogImportWorkbookSheet read(byte[] content) {
        String text = decode(content);
        List<List<String>> records = records(text, delimiter(text));
        int header = 0;
        while (header < records.size() && records.get(header).stream().allMatch(String::isBlank)) {
            header++;
        }
        if (header == records.size()) {
            throw new InteractionValidationException("file", "В файле CSV нет строки заголовков");
        }
        String location = "Файл CSV, строка " + (header + 1);
        Map<Integer, String> headers = new LinkedHashMap<>();
        Set<String> unique = new LinkedHashSet<>();
        List<String> headerCells = records.get(header);
        for (int column = 0; column < headerCells.size(); column++) {
            String name = headerCells.get(column).strip();
            if (name.isEmpty()) {
                continue;
            }
            if (!unique.add(name)) {
                throw new InteractionValidationException("file", location + ": заголовок «" + name + "» повторяется");
            }
            headers.put(column, name);
        }
        if (headers.size() > CatalogImportWorkbookReader.MAX_COLUMNS) {
            throw new InteractionValidationException("file", location + ": больше " + CatalogImportWorkbookReader.MAX_COLUMNS + " столбцов");
        }
        List<CatalogImportWorkbookRow> rows = new ArrayList<>();
        for (int index = header + 1; index < records.size(); index++) {
            List<String> cells = records.get(index);
            Map<String, String> values = new LinkedHashMap<>();
            Map<String, String> errors = new LinkedHashMap<>();
            boolean hasValue = false;
            for (Map.Entry<Integer, String> column : headers.entrySet()) {
                String value = column.getKey() < cells.size() ? cells.get(column.getKey()).strip() : "";
                if (value.length() > CatalogImportWorkbookReader.MAX_CELL_LENGTH) {
                    errors.put(column.getValue(), "Значение длиннее " + CatalogImportWorkbookReader.MAX_CELL_LENGTH + " символов");
                }
                values.put(column.getValue(), value);
                hasValue = hasValue || !value.isEmpty();
            }
            if (!hasValue) {
                continue;
            }
            if (rows.size() == CatalogImportWorkbookReader.MAX_ROWS) {
                throw new InteractionValidationException(
                        "file", "Файл CSV: больше " + CatalogImportWorkbookReader.MAX_ROWS + " строк с данными; разделите файл"
                );
            }
            rows.add(new CatalogImportWorkbookRow(index + 1, Map.copyOf(values), Map.copyOf(errors)));
        }
        return new CatalogImportWorkbookSheet(SHEET, List.copyOf(headers.values()), List.copyOf(rows));
    }

    // Строгое чтение UTF-8: если байты не складываются в UTF-8, файл сохранён в Windows-1251 (Excel «CSV (разделители — запятые)»).
    private static String decode(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                throw new InteractionValidationException("file", "Файл не похож на текстовый CSV");
            }
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException exception) {
            text = new String(content, WINDOWS_1251);
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    // Разделитель — тот из «;», «,» и табуляции, который чаще встречается в первой строке вне кавычек.
    private static char delimiter(String text) {
        int semicolons = 0;
        int commas = 0;
        int tabs = 0;
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '"') {
                quoted = !quoted;
            } else if (!quoted && (ch == '\n' || ch == '\r')) {
                break;
            } else if (!quoted && ch == ';') {
                semicolons++;
            } else if (!quoted && ch == ',') {
                commas++;
            } else if (!quoted && ch == '\t') {
                tabs++;
            }
        }
        if (tabs > semicolons && tabs > commas) {
            return '\t';
        }
        return semicolons >= commas ? ';' : ',';
    }

    static List<List<String>> records(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (quoted) {
                if (ch == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    cell.append('"');
                    index++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == delimiter) {
                record.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                record.add(cell.toString());
                cell.setLength(0);
                records.add(record);
                record = new ArrayList<>();
                if (records.size() > CatalogImportWorkbookReader.MAX_ROWS * 2) {
                    throw new InteractionValidationException(
                            "file", "Файл CSV: больше " + CatalogImportWorkbookReader.MAX_ROWS + " строк с данными; разделите файл"
                    );
                }
            } else {
                cell.append(ch);
            }
        }
        if (quoted) {
            throw new InteractionValidationException("file", "Файл CSV: кавычка в последней ячейке не закрыта");
        }
        if (cell.length() > 0 || !record.isEmpty()) {
            record.add(cell.toString());
            records.add(record);
        }
        return records;
    }
}
