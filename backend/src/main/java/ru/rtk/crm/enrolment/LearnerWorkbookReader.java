package ru.rtk.crm.enrolment;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.interaction.InteractionValidationException;

@Component
public class LearnerWorkbookReader {
    static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;
    static final int MAX_ROWS = 5_000;
    static final int MAX_COLUMNS = 60;
    static final int HEADER_SEARCH_ROWS = 20;
    private static final long MAX_DECOMPRESSED_BYTES = 12L * 1024L * 1024L;
    private static final String DATE_MESSAGE = "Дата должна быть в формате ДД.ММ.ГГГГ";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final String LEADING_ZEROS = "Восстановлены ведущие нули: Excel сохранил значение числом";
    private static final Set<LearnerField> REQUIRED_COLUMNS = Set.of(LearnerField.LAST_NAME, LearnerField.FIRST_NAME);
    private static final Set<LearnerField> CONTACT_COLUMNS = Set.of(LearnerField.PHONE, LearnerField.EMAIL);
    private static final Map<LearnerField, Integer> DIGIT_COLUMNS = Map.of(
            LearnerField.SNILS, 11,
            LearnerField.PASSPORT_SERIES, 4,
            LearnerField.PASSPORT_NUMBER, 6,
            LearnerField.PASSPORT_DIVISION_CODE, 6,
            LearnerField.POSTAL_CODE, 6
    );
    private static final Map<String, LearnerField> FIELDS_BY_HEADER = Arrays.stream(LearnerField.values())
            .flatMap(field -> Stream.of(field.header(), field.label()).map(title -> Map.entry(headerKey(title), field)))
            .distinct()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    public LearnerWorkbookReader() {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(MAX_DECOMPRESSED_BYTES);
    }

    public LearnerWorkbook read(MultipartFile file) {
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new InteractionValidationException("file", "Файл больше " + MAX_FILE_BYTES / (1024 * 1024) + " МиБ");
        }
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            for (Sheet sheet : workbook) {
                for (int rowIndex = Math.max(sheet.getFirstRowNum(), 0);
                     rowIndex <= Math.min(sheet.getLastRowNum(), HEADER_SEARCH_ROWS - 1); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    Map<LearnerField, Integer> columns = row == null ? Map.of() : columns(sheet, row);
                    if (columns.keySet().containsAll(REQUIRED_COLUMNS)
                            && CONTACT_COLUMNS.stream().anyMatch(columns::containsKey)) {
                        return new LearnerWorkbook(ignoredHeaders(row), described(row, columns), rows(sheet, rowIndex, columns));
                    }
                }
            }
            throw new InteractionValidationException("file", "Не найдена строка заголовков шаблона «Загрузка пользователей»: "
                    + "нужны столбцы Фамилия, Имя и для сопоставления со слушателями Email или Номер телефона");
        } catch (InteractionValidationException exception) {
            throw exception;
        } catch (EncryptedDocumentException exception) {
            throw new InteractionValidationException("file", "Книга защищена паролем; сохраните её без пароля");
        } catch (IOException | RuntimeException exception) {
            throw new InteractionValidationException("file", "Файл не читается как книга XLS или XLSX");
        }
    }

    static String headerKey(String header) {
        return header.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{Nd}]", "");
    }

    private static List<String> ignoredHeaders(Row header) {
        List<String> ignored = new ArrayList<>();
        for (Cell cell : header) {
            if (cell.getCellType() == CellType.STRING && !cell.getStringCellValue().isBlank()
                    && !FIELDS_BY_HEADER.containsKey(headerKey(cell.getStringCellValue()))) {
                ignored.add(LearnerRules.collapseSpaces(cell.getStringCellValue()));
            }
        }
        return List.copyOf(ignored);
    }

    private Map<LearnerField, Integer> columns(Sheet sheet, Row header) {
        Map<LearnerField, Integer> columns = new EnumMap<>(LearnerField.class);
        int filled = 0;
        for (Cell cell : header) {
            if (cell.getCellType() != CellType.STRING || cell.getStringCellValue().isBlank()) {
                continue;
            }
            if (++filled > MAX_COLUMNS) {
                throw new InteractionValidationException(
                        "file", location(sheet, header) + ": больше " + MAX_COLUMNS + " столбцов с заголовками"
                );
            }
            LearnerField field = FIELDS_BY_HEADER.get(headerKey(cell.getStringCellValue()));
            if (field != null && columns.putIfAbsent(field, cell.getColumnIndex()) != null) {
                throw new InteractionValidationException(
                        "file", location(sheet, header) + ": столбец «" + field.header() + "» встречается дважды"
                );
            }
        }
        return columns;
    }

    private static Map<LearnerField, LearnerWorkbookColumn> described(Row header, Map<LearnerField, Integer> columns) {
        Map<LearnerField, LearnerWorkbookColumn> described = new EnumMap<>(LearnerField.class);
        columns.forEach((field, index) -> described.put(field, new LearnerWorkbookColumn(
                CellReference.convertNumToColString(index), LearnerRules.collapseSpaces(header.getCell(index).getStringCellValue())
        )));
        return described;
    }

    private List<LearnerWorkbookRow> rows(Sheet sheet, int headerIndex, Map<LearnerField, Integer> columns) {
        List<LearnerWorkbookRow> rows = new ArrayList<>();
        for (int rowIndex = headerIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            RowParser parser = new RowParser(row, columns);
            if (parser.empty()) {
                continue;
            }
            if (rows.size() == MAX_ROWS) {
                throw new InteractionValidationException(
                        "file", "Лист «" + sheet.getSheetName() + "»: больше " + MAX_ROWS + " строк с данными; разделите файл"
                );
            }
            LearnerProfile profile = parser.profile().normalized();
            rows.add(new LearnerWorkbookRow(rowIndex + 1, profile, List.copyOf(parser.errors), List.copyOf(parser.warnings)));
        }
        return List.copyOf(rows);
    }

    private static String location(Sheet sheet, Row row) {
        return "Лист «" + sheet.getSheetName() + "», строка " + (row.getRowNum() + 1);
    }

    private static final class RowParser {
        private final Row row;
        private final Map<LearnerField, Integer> columns;
        private final List<LearnerFieldError> errors = new ArrayList<>();
        private final List<LearnerFieldError> warnings = new ArrayList<>();

        private RowParser(Row row, Map<LearnerField, Integer> columns) {
            this.row = row;
            this.columns = columns;
        }

        private boolean empty() {
            for (int column : columns.values()) {
                Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (cell != null && !(cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank())) {
                    return false;
                }
            }
            return true;
        }

        private LearnerProfile profile() {
            return new LearnerProfile(
                    text(LearnerField.LAST_NAME),
                    text(LearnerField.FIRST_NAME),
                    text(LearnerField.MIDDLE_NAME),
                    text(LearnerField.PHONE),
                    text(LearnerField.EMAIL),
                    text(LearnerField.SNILS),
                    text(LearnerField.PASSPORT_SERIES),
                    text(LearnerField.PASSPORT_NUMBER),
                    text(LearnerField.PASSPORT_ISSUED_BY),
                    date(LearnerField.PASSPORT_ISSUE_DATE),
                    text(LearnerField.PASSPORT_DIVISION_CODE),
                    gender(),
                    date(LearnerField.BIRTH_DATE),
                    text(LearnerField.REGION),
                    text(LearnerField.LOCALITY),
                    text(LearnerField.STREET),
                    text(LearnerField.HOUSE),
                    text(LearnerField.APARTMENT),
                    text(LearnerField.POSTAL_CODE),
                    text(LearnerField.FIRST_NAME_DATIVE),
                    text(LearnerField.LAST_NAME_DATIVE),
                    text(LearnerField.MIDDLE_NAME_DATIVE),
                    education(),
                    text(LearnerField.DIPLOMA_PROFESSION),
                    text(LearnerField.DIPLOMA_INSTITUTION),
                    text(LearnerField.DIPLOMA_LAST_NAME),
                    text(LearnerField.DIPLOMA_NUMBER),
                    text(LearnerField.DIPLOMA_SERIES),
                    text(LearnerField.DIPLOMA_REGISTRATION_NUMBER),
                    date(LearnerField.DIPLOMA_ISSUE_DATE)
            );
        }

        private String text(LearnerField field) {
            Cell cell = cell(field);
            if (cell == null) {
                return null;
            }
            return switch (type(field, cell)) {
                case STRING -> cell.getStringCellValue();
                case BOOLEAN -> cell.getBooleanCellValue() ? "true" : "false";
                case NUMERIC -> {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        yield fail(field, "Excel сохранил значение как дату; введите его как текст");
                    }
                    String digits = BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
                    Integer length = DIGIT_COLUMNS.get(field);
                    if (length != null && digits.matches("\\d+") && digits.length() < length) {
                        warnings.add(new LearnerFieldError(field, LEADING_ZEROS));
                        yield "0".repeat(length - digits.length()) + digits;
                    }
                    yield digits;
                }
                default -> null;
            };
        }

        private LocalDate date(LearnerField field) {
            Cell cell = cell(field);
            if (cell == null) {
                return null;
            }
            CellType type = type(field, cell);
            if (type == CellType.BLANK || type == CellType.STRING && cell.getStringCellValue().isBlank()) {
                return null;
            }
            if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            }
            if (type == CellType.STRING) {
                try {
                    return LocalDate.parse(cell.getStringCellValue().strip(), DATE);
                } catch (DateTimeParseException exception) {
                    return fail(field, DATE_MESSAGE);
                }
            }
            return fail(field, DATE_MESSAGE);
        }

        private Gender gender() {
            String value = text(LearnerField.GENDER);
            if (value == null || value.isBlank()) {
                return null;
            }
            return Gender.fromTitle(value).orElseGet(() -> fail(LearnerField.GENDER, "Пол: выберите «М» или «Ж»"));
        }

        private Education education() {
            String value = text(LearnerField.EDUCATION);
            if (value == null || value.isBlank()) {
                return null;
            }
            return Education.fromTitle(value)
                    .orElseGet(() -> fail(LearnerField.EDUCATION, "Образование: выберите значение из списка на листе «Лист2»"));
        }

        private Cell cell(LearnerField field) {
            Integer column = columns.get(field);
            return column == null ? null : row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        }

        private CellType type(LearnerField field, Cell cell) {
            CellType type = cell.getCellType();
            if (type == CellType.FORMULA) {
                if (cell instanceof XSSFCell xssfCell && !xssfCell.getCTCell().isSetV()) {
                    fail(field, "Формула без сохранённого значения; откройте и сохраните файл в Excel");
                    return CellType.BLANK;
                }
                type = cell.getCachedFormulaResultType();
            }
            if (type == CellType.ERROR) {
                fail(field, "Ячейка содержит ошибку Excel");
                return CellType.BLANK;
            }
            return type;
        }

        private <T> T fail(LearnerField field, String message) {
            errors.add(new LearnerFieldError(field, message));
            return null;
        }
    }
}
