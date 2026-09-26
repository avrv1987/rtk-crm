package ru.rtk.crm.enrolment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.rtk.crm.enrolment.LearnerTestData.TODAY;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFDataValidation;
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import ru.rtk.crm.interaction.InteractionValidationException;

class LmsRosterWorkbookTest {
    private static final List<String> ORGANIZER_HEADERS = List.of(
            "Фамилия", "Имя", "Отчествопри наличии)", "Номер телефона", "Email", "СНИЛС", "Серия паспорта",
            "Номер паспорта", "Кем выдан паспорт", "Дата выдачи паспорта", "Код подразделения", "Пол", "Дата рождения",
            "Регион регистрации", "Населенный пункт регистрации", "Улица регистрации", "Дом регистрации",
            "Квартира регистрации", "Индекс регистрации", "Имядательный падеж)", "Фамилиядательный падеж)",
            "Отчестводательный падеж)", "Образование", "Профессия по диплому", "Учебное заведение по диплому",
            "Фамилия, указанная в дипломе", "Номер диплома", "Серия диплома", "Регистрационный номер диплома",
            "Дата выдачи диплома"
    );

    private final LmsRosterWorkbookWriter writer = new LmsRosterWorkbookWriter();
    private final LearnerWorkbookReader reader = new LearnerWorkbookReader();

    @Test
    void writesOrganizerTemplateWithTypedCellsListsAndValidations() throws IOException {
        LearnerProfile full = LearnerTestData.fullProfile();
        LearnerProfile minimal = LearnerTestData.requiredOnly("Примеров", "Пётр", "+79000000002", "petr@example.test");
        LearnerProfile sameEmail = LearnerTestData.requiredOnly("Другой", "Пётр", "+79000000003", "PETR@example.test");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(write(List.of(full, minimal, sameEmail))))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            XSSFSheet sheet = workbook.getSheet("Лист1");
            Row header = sheet.getRow(0);
            assertThat(IntStream.range(0, header.getLastCellNum()).mapToObj(index -> header.getCell(index).getStringCellValue()))
                    .containsExactlyElementsOf(ORGANIZER_HEADERS)
                    .containsExactlyElementsOf(Arrays.stream(LearnerField.values()).map(LearnerField::header).toList());
            assertThat(sheet.getLastRowNum()).isEqualTo(2);

            Row first = sheet.getRow(1);
            Cell phone = cell(first, LearnerField.PHONE);
            assertThat(phone.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat((long) phone.getNumericCellValue()).isEqualTo(79000000001L);
            Cell birth = cell(first, LearnerField.BIRTH_DATE);
            assertThat(DateUtil.isCellDateFormatted(birth)).isTrue();
            assertThat(birth.getCellStyle().getDataFormatString()).isEqualTo("dd.mm.yyyy");
            assertThat(birth.getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2000, 2, 29));
            assertThat(cell(first, LearnerField.SNILS).getStringCellValue()).isEqualTo("112-233-445 95");
            assertThat(cell(first, LearnerField.PASSPORT_SERIES).getStringCellValue()).isEqualTo("0123");
            assertThat(cell(first, LearnerField.POSTAL_CODE).getStringCellValue()).isEqualTo("012345");
            assertThat(cell(first, LearnerField.GENDER).getStringCellValue()).isEqualTo("Ж");
            assertThat(cell(first, LearnerField.EDUCATION).getStringCellValue()).isEqualTo("Высшее образование – бакалавриат");

            Row second = sheet.getRow(2);
            assertThat(cell(second, LearnerField.LAST_NAME).getStringCellValue()).isEqualTo("Примеров");
            assertThat(second.getCell(LearnerField.SNILS.ordinal())).isNull();
            assertThat(second.getCell(LearnerField.BIRTH_DATE.ordinal())).isNull();
            assertThat(second.getCell(LearnerField.EDUCATION.ordinal())).isNull();

            Sheet lists = workbook.getSheet("Лист2");
            assertThat(List.of(text(lists, 0, 0), text(lists, 1, 0))).containsExactly("М", "Ж");
            assertThat(IntStream.range(0, 7).mapToObj(row -> text(lists, row, 1))).containsExactly(
                    "Без образования",
                    "Основное общее образование - 9 классов",
                    "Среднее общее образование - 11 классов",
                    "Среднее профессиональное образование",
                    "Высшее образование – бакалавриат",
                    "Высшее образование – специалитет, магистратура",
                    "Высшее образование – подготовка кадров высшей квалификации"
            );

            List<XSSFDataValidation> validations = sheet.getDataValidations();
            assertThat(validations).hasSize(2);
            assertValidation(validations.get(0), "Лист2!$A$1:$A$2", "L1:L1001");
            assertValidation(validations.get(1), "Лист2!$B$1:$B$7", "W1:W1001");
        }
    }

    @Test
    void countsSkippedDuplicateEmailsAndRefusesProfilesWithoutEmail() throws IOException {
        LearnerProfile full = LearnerTestData.fullProfile();
        LearnerProfile minimal = LearnerTestData.requiredOnly("Примеров", "Пётр", "+79000000002", "petr@example.test");
        LearnerProfile sameEmail = LearnerTestData.requiredOnly("Другой", "Пётр", "+79000000003", "PETR@example.test");
        LearnerProfile noEmail = LearnerTestData.requiredOnly("Тестов", "Иван", "+79000000004", null);

        assertThat(writer.write(List.of(full, minimal, sameEmail), OutputStream.nullOutputStream())).isEqualTo(1);
        assertThat(writer.write(List.of(full, minimal), OutputStream.nullOutputStream())).isZero();
        assertThatThrownBy(() -> writer.write(List.of(full, noEmail), OutputStream.nullOutputStream()))
                .isInstanceOf(InteractionValidationException.class)
                .hasMessage("Анкета № 2: не заполнен Email; файл для LMS не сформирован");
    }

    @Test
    void readsBackGeneratedWorkbookWithoutChanges() throws IOException {
        LearnerProfile full = LearnerTestData.fullProfile();
        LearnerProfile minimal = LearnerTestData.requiredOnly("Примеров", "Пётр", "+79000000002", "petr@example.test");

        LearnerWorkbook workbook = reader.read(file(write(List.of(full, minimal))), TODAY);
        List<LearnerWorkbookRow> rows = workbook.rows();

        assertThat(workbook.ignoredHeaders()).isEmpty();

        assertThat(rows).extracting(LearnerWorkbookRow::rowNumber).containsExactly(2, 3);
        assertThat(rows).allSatisfy(row -> assertThat(row.errors()).isEmpty());
        assertThat(rows).extracting(LearnerWorkbookRow::profile).containsExactly(full, minimal);
    }

    @Test
    void readsFilledTemplateWithHeaderVariationsAndReportsErrorsByRowAndField() throws IOException {
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet("Слушатели");
        sheet.createRow(0).createCell(0).setCellValue("Поток 3, синтетические данные");
        row(sheet, 2, "  фамилия ", "ИМЯ", "Отчество (при наличии)", "Номер  телефона", "email", "СНИЛС",
                "Серия паспорта", "Дата выдачи паспорта", "Пол", "Дата рождения", "Образование", "Комментарий");
        Row valid = row(sheet, 3, "Тестова", "Анна", "", null, "anna@example.test", "112-233-445 95",
                null, "20.05.2019", "ж", "29.02.2000", "Высшее образование - бакалавриат", "любой текст");
        valid.createCell(3).setCellValue(79000000001d);
        valid.createCell(6).setCellValue(123d);
        Row invalid = row(sheet, 5, "Тестов", "Иван", null, "12345", "ivan@example.test", "112-233-445 96",
                "12", "31.02.2001", "Мужчина", null, "Аспирантура", null);
        Cell futureBirth = invalid.createCell(9);
        futureBirth.setCellValue(TODAY.plusDays(1));
        CellStyle dateStyle = workbook.createCellStyle();
        dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd.mm.yyyy"));
        futureBirth.setCellStyle(dateStyle);
        Row formulas = row(sheet, 6, null, null, null, "8 900 000 00 03", " ANNA@example.test ", null,
                null, null, null, null, null, null);
        formulas.createCell(0).setCellFormula("\"Тест\"&\"ов\"");
        XSSFFormulaEvaluator.evaluateAllFormulaCells(workbook);
        formulas.createCell(1).setCellFormula("\"Ив\"&\"ан\"");

        LearnerWorkbook result = reader.read(file(bytes(workbook)), TODAY);
        List<LearnerWorkbookRow> rows = result.rows();

        assertThat(result.ignoredHeaders()).containsExactly("Комментарий");
        assertThat(rows).extracting(LearnerWorkbookRow::rowNumber).containsExactly(4, 6, 7);
        LearnerWorkbookRow first = rows.get(0);
        assertThat(first.errors()).isEmpty();
        assertThat(first.profile().phone()).isEqualTo("+79000000001");
        assertThat(first.profile().middleName()).isNull();
        assertThat(first.profile().passportSeries()).isEqualTo("0123");
        assertThat(first.profile().gender()).isEqualTo(Gender.FEMALE);
        assertThat(first.profile().birthDate()).isEqualTo(LocalDate.of(2000, 2, 29));
        assertThat(first.profile().education()).isEqualTo(Education.HIGHER_BACHELOR);

        assertThat(messages(rows.get(1))).containsExactly(
                "PASSPORT_ISSUE_DATE: Дата должна быть в формате ДД.ММ.ГГГГ",
                "GENDER: Пол: выберите «М» или «Ж»",
                "EDUCATION: Образование: выберите значение из списка на листе «Лист2»",
                "PHONE: Телефон должен содержать 10 цифр после +7 или 8, например +7 900 000-00-00",
                "SNILS: Контрольное число СНИЛС не совпадает с номером",
                "PASSPORT_SERIES: Серия паспорта — 4 цифры",
                "BIRTH_DATE: Дата рождения не может быть в будущем"
        );
        assertThat(messages(rows.get(2))).containsExactly(
                "FIRST_NAME: Формула без сохранённого значения; откройте и сохраните файл в Excel",
                "EMAIL: Email совпадает со строкой 4"
        );
        assertThat(rows.get(2).profile().lastName()).isEqualTo("Тестов");
    }

    @Test
    void mapsFieldLabelsAndRejectsNumbersWithoutDateFormatInDateColumns() throws IOException {
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet("Лист1");
        row(sheet, 0, "Фамилия", "Имя", "Отчество", "Номер телефона", "Email", "Дата рождения",
                "Имя в дательном падеже", "Телефон  родителя");
        Row data = row(sheet, 1, "Тестова", "Анна", "Сергеевна", "+7 900 000-00-01", "anna@example.test", null,
                "Анне", "8 900 000 00 09");
        data.createCell(5).setCellValue(2000d);

        LearnerWorkbook result = reader.read(file(bytes(workbook)), TODAY);

        assertThat(result.ignoredHeaders()).containsExactly("Телефон родителя");
        LearnerWorkbookRow row = result.rows().get(0);
        assertThat(row.profile().middleName()).isEqualTo("Сергеевна");
        assertThat(row.profile().firstNameDative()).isEqualTo("Анне");
        assertThat(row.profile().birthDate()).isNull();
        assertThat(messages(row)).containsExactly("BIRTH_DATE: Дата должна быть в формате ДД.ММ.ГГГГ");
    }

    @Test
    void rejectsFilesWithoutTemplateHeadersOrBeyondLimits() throws IOException {
        XSSFWorkbook noHeaders = new XSSFWorkbook();
        row(noHeaders.createSheet("Лист1"), 0, "Фамилия", "Имя", "Телефон");
        assertFileError(bytes(noHeaders), "Не найдена строка заголовков шаблона «Загрузка пользователей»: "
                + "нужны столбцы Фамилия, Имя, Номер телефона и Email");

        XSSFWorkbook duplicate = new XSSFWorkbook();
        row(duplicate.createSheet("Лист1"), 0, "Фамилия", "Имя", "Номер телефона", "Email", "E-mail");
        assertFileError(bytes(duplicate), "Лист «Лист1», строка 1: столбец «Email» встречается дважды");

        XSSFWorkbook wide = new XSSFWorkbook();
        Row header = row(wide.createSheet("Лист1"), 0, "Фамилия", "Имя", "Номер телефона", "Email");
        for (int column = 4; column <= LearnerWorkbookReader.MAX_COLUMNS; column++) {
            header.createCell(column).setCellValue("Столбец " + column);
        }
        assertFileError(bytes(wide), "Лист «Лист1», строка 1: больше 60 столбцов с заголовками");

        XSSFWorkbook tall = new XSSFWorkbook();
        Sheet sheet = tall.createSheet("Лист1");
        row(sheet, 0, "Фамилия", "Имя", "Номер телефона", "Email");
        for (int index = 1; index <= LearnerWorkbookReader.MAX_ROWS + 1; index++) {
            sheet.createRow(index).createCell(0).setCellValue("Тестов");
        }
        assertFileError(bytes(tall), "Лист «Лист1»: больше 5000 строк с данными; разделите файл");

        assertFileError("не книга".getBytes(), "Файл не читается как книга XLS или XLSX");
        assertFileError(new byte[(int) LearnerWorkbookReader.MAX_FILE_BYTES + 1], "Файл больше 5 МиБ");
    }

    private byte[] write(List<LearnerProfile> profiles) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writer.write(profiles, output);
        return output.toByteArray();
    }

    private void assertFileError(byte[] content, String message) {
        assertThatThrownBy(() -> reader.read(file(content), TODAY))
                .isInstanceOf(InteractionValidationException.class)
                .hasMessage(message);
    }

    private static void assertValidation(DataValidation validation, String formula, String range) {
        assertThat(validation.getValidationConstraint().getFormula1()).isEqualTo(formula);
        assertThat(validation.getRegions().getCellRangeAddresses())
                .extracting(CellRangeAddress::formatAsString)
                .containsExactly(range);
        assertThat(validation.getShowErrorBox()).isTrue();
        assertThat(validation.getEmptyCellAllowed()).isTrue();
    }

    private static Row row(Sheet sheet, int index, String... values) {
        Row row = sheet.createRow(index);
        for (int column = 0; column < values.length; column++) {
            if (values[column] != null) {
                row.createCell(column).setCellValue(values[column]);
            }
        }
        return row;
    }

    private static Cell cell(Row row, LearnerField field) {
        return row.getCell(field.ordinal());
    }

    private static String text(Sheet sheet, int row, int column) {
        return sheet.getRow(row).getCell(column).getStringCellValue();
    }

    private static List<String> messages(LearnerWorkbookRow row) {
        return row.errors().stream().map(error -> error.field() + ": " + error.message()).toList();
    }

    private static byte[] bytes(Workbook workbook) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        workbook.close();
        return output.toByteArray();
    }

    private static MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "learners.xlsx", null, content);
    }
}
