package ru.rtk.crm.people;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.enrolment.LearnerWorkbookReader;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.people.PeopleModels.QualificationResult;

// Шаблон и чтение списка повышения квалификации. Колонки ищутся по заголовку первой строки, поэтому порядок колонок
// в файле не важен. Значение ячейки, похожее на формулу, в шаблон не попадает: шаблон пустой.
final class QualificationWorkbook {
    static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    static final int MAX_ROWS = 1_000;
    static final String SHEET = "Повышение квалификации";

    enum Column {
        NAME("ФИО", true, "personName"),
        EMAIL("Электронная почта", false, "personEmail"),
        PROGRAM("Программа", true, "program"),
        STARTS_ON("Дата начала", true, "startsOn"),
        ENDS_ON("Дата окончания", true, "endsOn"),
        HOURS("Часы", true, "hours"),
        RESULT("Итог", false, "result"),
        CERTIFICATE_NUMBER("Номер удостоверения", false, "certificateNumber"),
        CERTIFICATE_DATE("Дата удостоверения", false, "certificateDate"),
        FRDO("Передано в ФРДО", false, "frdoSubmittedOn");

        final String title;
        final boolean required;
        // Имя поля записи: по нему ошибка разбора ячейки и ошибка правил записи сводятся в одно сообщение на поле.
        final String field;

        Column(String title, boolean required, String field) {
            this.title = title;
            this.required = required;
            this.field = field;
        }
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT);

    private QualificationWorkbook() {
    }

    static byte[] template() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(SHEET);
            Row header = sheet.createRow(0);
            for (Column column : Column.values()) {
                header.createCell(column.ordinal()).setCellValue(column.title + (column.required ? " *" : ""));
                sheet.setColumnWidth(column.ordinal(), 22 * 256);
            }
            Sheet help = workbook.createSheet("Пояснения");
            String[] lines = {
                    "Колонки со звёздочкой обязательны. Даты — ДД.ММ.ГГГГ, часы — число (можно с одной цифрой после запятой).",
                    "Итог: «аттестован», «не аттестован» или «обучается» (пусто — обучается).",
                    "Передано в ФРДО — дата передачи сведений; заполняется только для аттестованных.",
                    "Преподаватель сопоставляется с контактом вуза по электронной почте; при совпадении ФИО берётся из контакта.",
                    "Единица часов берётся из настройки администратора для повышения квалификации."
            };
            for (int index = 0; index < lines.length; index++) {
                help.createRow(index).createCell(0).setCellValue(lines[index]);
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Qualification template cannot be written", exception);
        }
    }

    static List<ParsedRow> read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InteractionValidationException("file", "Выберите файл XLSX");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new InteractionValidationException("file", "Файл больше " + MAX_FILE_BYTES / (1024 * 1024) + " МиБ");
        }
        try {
            LearnerWorkbookReader.checkAggregateZipSize(file);
        } catch (IOException exception) {
            throw unreadable();
        }
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(sheet.getFirstRowNum() < 0 ? 0 : sheet.getFirstRowNum());
            if (header == null) {
                throw new InteractionValidationException("file", "В файле нет строки заголовков шаблона");
            }
            Map<Column, Integer> columns = columns(header);
            for (Column column : Column.values()) {
                if (column.required && !columns.containsKey(column)) {
                    throw new InteractionValidationException("file", "Нет колонки «" + column.title + "»: скачайте шаблон и заполните его");
                }
            }
            List<ParsedRow> rows = new ArrayList<>();
            for (int index = header.getRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || blank(row, columns)) {
                    continue;
                }
                if (rows.size() >= MAX_ROWS) {
                    throw new InteractionValidationException("file", "В файле больше " + MAX_ROWS + " строк");
                }
                rows.add(parse(row, columns));
            }
            if (rows.isEmpty()) {
                throw new InteractionValidationException("file", "В файле нет заполненных строк");
            }
            return rows;
        } catch (InteractionValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            // Битый, зашифрованный или чужой формат файла — это неверный ввод, а не сбой сервера.
            if (exception instanceof EncryptedDocumentException) {
                throw new InteractionValidationException("file", "Файл защищён паролем; снимите защиту и загрузите снова");
            }
            throw unreadable();
        }
    }

    private static InteractionValidationException unreadable() {
        return new InteractionValidationException("file", "Файл не читается как книга XLSX");
    }

    private static Map<Column, Integer> columns(Row header) {
        Map<String, Column> byTitle = new HashMap<>();
        for (Column column : Column.values()) {
            byTitle.put(key(column.title), column);
        }
        Map<Column, Integer> columns = new HashMap<>();
        for (Cell cell : header) {
            if (cell.getCellType() == CellType.STRING) {
                Column column = byTitle.get(key(cell.getStringCellValue()));
                if (column != null) {
                    columns.putIfAbsent(column, cell.getColumnIndex());
                }
            }
        }
        return columns;
    }

    private static String key(String title) {
        return title.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{Nd}]", "");
    }

    private static boolean blank(Row row, Map<Column, Integer> columns) {
        return columns.values().stream().allMatch(index -> {
            Cell cell = row.getCell(index);
            return cell == null || cell.getCellType() == CellType.BLANK
                    || cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank();
        });
    }

    private static ParsedRow parse(Row row, Map<Column, Integer> columns) {
        Map<String, String> problems = new LinkedHashMap<>();
        Map<Column, Cell> cells = new HashMap<>();
        columns.forEach((column, index) -> {
            Cell cell = row.getCell(index);
            if (cell != null) {
                cells.put(column, cell);
            }
        });
        return new ParsedRow(
                row.getRowNum() + 1,
                text(cells.get(Column.NAME)),
                text(cells.get(Column.EMAIL)),
                text(cells.get(Column.PROGRAM)),
                date(cells.get(Column.STARTS_ON), Column.STARTS_ON, problems),
                date(cells.get(Column.ENDS_ON), Column.ENDS_ON, problems),
                number(cells.get(Column.HOURS), problems),
                result(text(cells.get(Column.RESULT)), problems),
                text(cells.get(Column.CERTIFICATE_NUMBER)),
                date(cells.get(Column.CERTIFICATE_DATE), Column.CERTIFICATE_DATE, problems),
                date(cells.get(Column.FRDO), Column.FRDO, problems),
                problems
        );
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().strip();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? DATE.format(cell.getLocalDateTimeCellValue().toLocalDate())
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private static LocalDate date(Cell cell, Column column, Map<String, String> problems) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String value = text(cell);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value, DATE);
        } catch (DateTimeParseException exception) {
            problems.put(column.field, "«" + column.title + "»: дата должна быть в формате ДД.ММ.ГГГГ");
            return null;
        }
    }

    private static BigDecimal number(Cell cell, Map<String, String> problems) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
        }
        String value = text(cell);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(',', '.').replace(" ", ""));
        } catch (NumberFormatException exception) {
            problems.put(Column.HOURS.field, "«Часы»: нужно число");
            return null;
        }
    }

    private static QualificationResult result(String value, Map<String, String> problems) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String normalized = key(value);
        if (normalized.startsWith("не")) {
            return QualificationResult.NOT_CERTIFIED;
        }
        if (normalized.startsWith("аттест")) {
            return QualificationResult.CERTIFIED;
        }
        if (normalized.startsWith("обуча")) {
            return QualificationResult.IN_PROGRESS;
        }
        problems.put(Column.RESULT.field, "«Итог»: укажите «аттестован», «не аттестован» или «обучается»");
        return null;
    }

    record ParsedRow(
            int row,
            String name,
            String email,
            String program,
            LocalDate startsOn,
            LocalDate endsOn,
            BigDecimal hours,
            QualificationResult result,
            String certificateNumber,
            LocalDate certificateDate,
            LocalDate frdoSubmittedOn,
            Map<String, String> problems
    ) {
        @Override
        public String toString() {
            return "ParsedRow[" + row + "]";
        }
    }
}
