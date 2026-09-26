package ru.rtk.crm.enrolment;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class LmsRosterWorkbookWriter {
    static final String DATA_SHEET = "Лист1";
    static final String LISTS_SHEET = "Лист2";
    static final int MIN_VALIDATION_LAST_ROW = 1000;
    static final String DATE_FORMAT = "dd.mm.yyyy";

    private static final Pattern PHONE = Pattern.compile("\\+7\\d{10}");
    private static final Pattern SNILS = Pattern.compile("\\d{11}");
    private static final Set<LearnerField> TEXT_COLUMNS = EnumSet.of(
            LearnerField.SNILS,
            LearnerField.PASSPORT_SERIES,
            LearnerField.PASSPORT_NUMBER,
            LearnerField.PASSPORT_DIVISION_CODE,
            LearnerField.POSTAL_CODE,
            LearnerField.DIPLOMA_NUMBER,
            LearnerField.DIPLOMA_SERIES,
            LearnerField.DIPLOMA_REGISTRATION_NUMBER
    );
    private static final Set<LearnerField> DATE_COLUMNS = EnumSet.of(
            LearnerField.PASSPORT_ISSUE_DATE, LearnerField.BIRTH_DATE, LearnerField.DIPLOMA_ISSUE_DATE
    );

    public void write(List<LearnerProfile> profiles, OutputStream output) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet(DATA_SHEET);
            XSSFSheet lists = workbook.createSheet(LISTS_SHEET);
            Styles styles = new Styles(workbook);
            fillLists(lists);

            LearnerField[] fields = LearnerField.values();
            Row header = sheet.createRow(0);
            for (int column = 0; column < fields.length; column++) {
                header.createCell(column).setCellValue(fields[column].header());
                sheet.setColumnWidth(column, Math.max(14, fields[column].header().length() + 4) * 256);
                if (TEXT_COLUMNS.contains(fields[column])) {
                    sheet.setDefaultColumnStyle(column, styles.text);
                } else if (DATE_COLUMNS.contains(fields[column])) {
                    sheet.setDefaultColumnStyle(column, styles.date);
                }
            }

            int rowIndex = 1;
            for (LearnerProfile profile : profiles) {
                Row row = sheet.createRow(rowIndex++);
                for (int column = 0; column < fields.length; column++) {
                    Object value = profile.value(fields[column]);
                    if (value != null) {
                        write(row.createCell(column), fields[column], value, styles);
                    }
                }
            }

            int lastValidatedRow = Math.max(MIN_VALIDATION_LAST_ROW, rowIndex - 1);
            listValidation(sheet, LISTS_SHEET + "!$A$1:$A$" + Gender.values().length,
                    LearnerField.GENDER.ordinal(), lastValidatedRow);
            listValidation(sheet, LISTS_SHEET + "!$B$1:$B$" + Education.values().length,
                    LearnerField.EDUCATION.ordinal(), lastValidatedRow);
            workbook.setActiveSheet(0);
            workbook.write(output);
        }
    }

    private static void fillLists(XSSFSheet lists) {
        Gender[] genders = Gender.values();
        Education[] levels = Education.values();
        for (int index = 0; index < Math.max(genders.length, levels.length); index++) {
            Row row = lists.createRow(index);
            if (index < genders.length) {
                row.createCell(0).setCellValue(genders[index].title());
            }
            if (index < levels.length) {
                row.createCell(1).setCellValue(levels[index].title());
            }
        }
        lists.setColumnWidth(1, 60 * 256);
    }

    private static void write(Cell cell, LearnerField field, Object value, Styles styles) {
        if (value instanceof LocalDate date) {
            cell.setCellValue(date);
            cell.setCellStyle(styles.date);
        } else if (value instanceof Gender gender) {
            cell.setCellValue(gender.title());
        } else if (value instanceof Education education) {
            cell.setCellValue(education.title());
        } else if (field == LearnerField.PHONE && PHONE.matcher((String) value).matches()) {
            cell.setCellValue(Long.parseLong(((String) value).substring(1)));
            cell.setCellStyle(styles.phone);
        } else if (field == LearnerField.SNILS && SNILS.matcher((String) value).matches()) {
            String digits = (String) value;
            cell.setCellValue(digits.substring(0, 3) + "-" + digits.substring(3, 6) + "-" + digits.substring(6, 9)
                    + " " + digits.substring(9));
            cell.setCellStyle(styles.text);
        } else {
            String text = (String) value;
            cell.setCellValue(text);
            cell.setCellStyle(isFormulaLike(text) ? styles.quoted : TEXT_COLUMNS.contains(field) ? styles.text : styles.plain);
        }
    }

    static boolean isFormulaLike(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }

    private static void listValidation(XSSFSheet sheet, String formula, int column, int lastRow) {
        XSSFDataValidationHelper helper = new XSSFDataValidationHelper(sheet);
        DataValidation validation = helper.createValidation(
                helper.createFormulaListConstraint(formula), new CellRangeAddressList(0, lastRow, column, column)
        );
        validation.setShowErrorBox(true);
        validation.setEmptyCellAllowed(true);
        sheet.addValidationData(validation);
    }

    private static final class Styles {
        private final CellStyle plain;
        private final CellStyle text;
        private final CellStyle quoted;
        private final CellStyle date;
        private final CellStyle phone;

        private Styles(XSSFWorkbook workbook) {
            DataFormat format = workbook.createDataFormat();
            plain = workbook.createCellStyle();
            text = workbook.createCellStyle();
            text.setDataFormat(format.getFormat("@"));
            quoted = workbook.createCellStyle();
            quoted.setQuotePrefixed(true);
            date = workbook.createCellStyle();
            date.setDataFormat(format.getFormat(DATE_FORMAT));
            phone = workbook.createCellStyle();
            phone.setDataFormat(format.getFormat("0"));
        }
    }
}
