package ru.rtk.crm.enrolment;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.rtk.crm.enrolment.LearnerTestData.TODAY;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LearnerRulesTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "7 (900) 000-00-01|+79000000001",
            "8 900 000 00 01|+79000000001",
            "+7(900)0000001|+79000000001",
            "9000000001|+79000000001",
            "  +7 900 000-00-01 |+79000000001"
    })
    void normalizesRussianPhoneNumbers(String input, String expected) {
        assertThat(LearnerRules.normalizePhone(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "+1 900 000 00 01", "7 900 000 00 01 2", "7 (900) abc-00-01", "7+900 000 00 01", "",
            "+8 900 000 00 01", "+9000000001", "0000000000", "2000000001", "8 100 000 00 01", "+7 200 000 00 01"})
    void rejectsPhoneNumbersThatAreNotRussianMobileOrLandline(String input) {
        assertThat(LearnerRules.normalizePhone(input)).isNull();
    }

    @Test
    void rejectsStoredPhoneWithImpossibleAreaCode() {
        LearnerProfile profile = LearnerTestData.requiredOnly("Тестова", "Анна", "+70000000000", "anna@example.test");

        assertThat(messages(LearnerRules.check(profile.normalized(), TODAY)))
                .containsExactly("PHONE: Телефон должен содержать 10 цифр после +7 или 8, например +7 900 000-00-00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"11223344595", "12345678964", "92000010000", "92000010100", "99610000000", "00100199965"})
    void acceptsSnilsWithMatchingControlNumber(String snils) {
        assertThat(LearnerRules.snilsChecksumMatches(snils)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"11223344596", "12345678965", "92000010001", "99610000001", "00100199900"})
    void rejectsSnilsWithWrongControlNumber(String snils) {
        assertThat(LearnerRules.snilsChecksumMatches(snils)).isFalse();
    }

    @Test
    void skipsControlNumberUpToFirstCheckedSnils() {
        assertThat(LearnerRules.snilsChecksumMatches("00100199877")).isTrue();
        assertThat(LearnerRules.snilsChecksumMatches("00000000100")).isTrue();
        assertThat(LearnerRules.snilsChecksumMatches("00100199964")).isFalse();
    }

    @Test
    void acceptsFullSyntheticProfileAndNormalizesInputFormats() {
        LearnerProfile raw = withDocuments(LearnerTestData.fullProfile(), " 112 233 445 95 ", "01 23", "770001");

        LearnerProfile profile = raw.normalized();

        assertThat(profile.snils()).isEqualTo("11223344595");
        assertThat(profile.passportSeries()).isEqualTo("0123");
        assertThat(profile.passportDivisionCode()).isEqualTo("770-001");
        assertThat(LearnerRules.check(profile, TODAY)).isEmpty();
        assertThat(profile.toString()).doesNotContain("Тестова", "11223344595");
    }

    @Test
    void reportsFieldErrorsInRussianWithoutValues() {
        LearnerProfile profile = withDocuments(
                LearnerTestData.requiredOnly(" ", "Анна", "12345", "anna@"), "112-233-445 96", "123", "77-0001"
        ).normalized();

        assertThat(messages(LearnerRules.check(profile, TODAY))).containsExactly(
                "PHONE: Телефон должен содержать 10 цифр после +7 или 8, например +7 900 000-00-00",
                "EMAIL: Email указан неверно",
                "SNILS: Контрольное число СНИЛС не совпадает с номером",
                "PASSPORT_SERIES: Серия паспорта — 4 цифры",
                "PASSPORT_DIVISION_CODE: Код подразделения — 6 цифр в формате 000-000",
                "PASSPORT_NUMBER: Паспорт заполняется целиком: заполните поле «Номер паспорта»",
                "PASSPORT_ISSUED_BY: Паспорт заполняется целиком: заполните поле «Кем выдан паспорт»",
                "PASSPORT_ISSUE_DATE: Паспорт заполняется целиком: заполните поле «Дата выдачи паспорта»"
        );
        assertThat(messages(LearnerRules.clearedRequired(profile, Set.of(LearnerField.LAST_NAME, LearnerField.SNILS))))
                .containsExactly("LAST_NAME: Не заполнено обязательное поле «Фамилия»");
        assertThat(LearnerRules.clearedRequired(profile, Set.of(LearnerField.SNILS))).isEmpty();
        LearnerProfile shortSnils = withDocuments(LearnerTestData.fullProfile(), "1234567890", "0123", "770-001").normalized();
        assertThat(messages(LearnerRules.check(shortSnils, TODAY))).containsExactly("SNILS: СНИЛС должен состоять из 11 цифр");
    }

    @Test
    void checksBirthAndIssueDates() {
        assertThat(messages(LearnerRules.check(withDates(TODAY.plusDays(1), null, null), TODAY)))
                .containsExactly("BIRTH_DATE: Дата рождения не может быть в будущем");
        assertThat(messages(LearnerRules.check(withDates(LocalDate.of(1899, 12, 31), null, null), TODAY)))
                .containsExactly("BIRTH_DATE: Дата рождения не может быть раньше 01.01.1900");
        assertThat(LearnerRules.check(withDates(LocalDate.of(1900, 1, 1), null, TODAY), TODAY)).isEmpty();

        LocalDate birth = LocalDate.of(2000, 2, 29);
        assertThat(messages(LearnerRules.check(withDates(birth, birth, birth.minusDays(1)), TODAY))).containsExactly(
                "PASSPORT_ISSUE_DATE: Дата выдачи паспорта раньше 14-летия",
                "DIPLOMA_ISSUE_DATE: Дата выдачи диплома должна быть позже даты рождения"
        );
        assertThat(messages(LearnerRules.check(withDates(birth, TODAY.plusDays(1), TODAY.plusDays(1)), TODAY))).containsExactly(
                "PASSPORT_ISSUE_DATE: Дата выдачи паспорта не может быть в будущем",
                "DIPLOMA_ISSUE_DATE: Дата выдачи диплома не может быть в будущем"
        );
    }

    @Test
    void refusesMinorsAndPassportsIssuedBeforeFourteenthBirthday() {
        assertThat(messages(LearnerRules.check(withDates(TODAY.minusYears(18).plusDays(1), null, null), TODAY)))
                .containsExactly("BIRTH_DATE: Слушатель младше 18 лет: обработка данных несовершеннолетних не предусмотрена");
        assertThat(LearnerRules.check(withDates(TODAY.minusYears(18), null, null), TODAY)).isEmpty();

        LocalDate birth = LocalDate.of(1995, 2, 10);
        assertThat(messages(LearnerRules.check(withDates(birth, LocalDate.of(2005, 3, 15), null), TODAY)))
                .containsExactly("PASSPORT_ISSUE_DATE: Дата выдачи паспорта раньше 14-летия");
        assertThat(messages(LearnerRules.check(withDates(birth, LocalDate.of(2009, 2, 9), null), TODAY)))
                .containsExactly("PASSPORT_ISSUE_DATE: Дата выдачи паспорта раньше 14-летия");
        assertThat(LearnerRules.check(withDates(birth, LocalDate.of(2009, 2, 10), null), TODAY)).isEmpty();
    }

    @Test
    void passportIsFilledCompletelyOrNotAtAllAndCompletenessSkipsOptionalFields() {
        LearnerProfile full = LearnerTestData.fullProfile();
        LearnerProfile withoutNumber = full.with(Collections.singletonMap(LearnerField.PASSPORT_NUMBER, null));

        assertThat(messages(LearnerRules.check(withoutNumber, TODAY)))
                .containsExactly("PASSPORT_NUMBER: Паспорт заполняется целиком: заполните поле «Номер паспорта»");
        assertThat(LearnerRules.check(LearnerTestData.requiredOnly("Тестова", "Анна", "+79000000001", "a@example.test"), TODAY))
                .isEmpty();
        assertThat(LearnerRules.completeness(EnumSet.noneOf(LearnerField.class))).isEqualTo(new LearnerCompleteness(28, 28));
        assertThat(LearnerRules.completeness(EnumSet.of(LearnerField.MIDDLE_NAME, LearnerField.MIDDLE_NAME_DATIVE,
                LearnerField.APARTMENT))).isEqualTo(new LearnerCompleteness(27, 27));
        assertThat(LearnerRules.completeness(EnumSet.of(LearnerField.MIDDLE_NAME_DATIVE, LearnerField.SNILS)))
                .isEqualTo(new LearnerCompleteness(26, 28));
        assertThat(LearnerRules.missingForLms(EnumSet.of(LearnerField.PHONE, LearnerField.SNILS))).containsExactly(LearnerField.PHONE);
    }

    @Test
    void limitsNamesAddressAndDiplomaCodesByTheirRules() {
        LearnerProfile full = LearnerTestData.fullProfile();
        LearnerProfile profile = full.with(Map.of(
                LearnerField.LAST_NAME, "Тестова2",
                LearnerField.MIDDLE_NAME, "д'Арк-Мария Сергеевна",
                LearnerField.FIRST_NAME_DATIVE, "А".repeat(LearnerRules.MAX_NAME_LENGTH + 1),
                LearnerField.HOUSE, "1".repeat(21),
                LearnerField.STREET, "ул. " + "С".repeat(197),
                LearnerField.DIPLOMA_NUMBER, "№ 000123",
                LearnerField.DIPLOMA_SERIES, "AB 12-34"
        ));

        assertThat(messages(LearnerRules.check(profile, TODAY))).containsExactly(
                "LAST_NAME: «Фамилия»: до 100 символов — буквы, пробел, дефис, апостроф, точка",
                "STREET: «Улица регистрации»: не длиннее 200 символов",
                "HOUSE: «Дом регистрации»: не длиннее 20 символов",
                "FIRST_NAME_DATIVE: «Имя в дательном падеже»: до 100 символов — буквы, пробел, дефис, апостроф, точка",
                "DIPLOMA_NUMBER: «Номер диплома»: до 50 символов — буквы, цифры, пробел, дефис"
        );
    }

    @Test
    void samePersonComparesMiddleNameOnlyWhenBothHaveIt() {
        LearnerProfile profile = LearnerTestData.fullProfile();

        assertThat(LearnerRules.samePerson(profile, " тестова ", "АННА", null)).isTrue();
        assertThat(LearnerRules.samePerson(profile, "Тестова", "Анна", "Сергеевна")).isTrue();
        assertThat(LearnerRules.samePerson(profile, "Тестова", "Анна", "Петровна")).isFalse();
        assertThat(LearnerRules.samePerson(profile, "Тестова", "Анастасия", null)).isFalse();
        assertThat(LearnerRules.samePerson(profile, null, "Анна", null)).isFalse();
    }

    @Test
    void limitsTextLengthAndPostalCode() {
        LearnerProfile full = LearnerTestData.fullProfile();
        LearnerProfile profile = new LearnerProfile(
                full.lastName(), full.firstName(), full.middleName(), full.phone(), full.email(), full.snils(),
                full.passportSeries(), full.passportNumber(), "К".repeat(LearnerRules.MAX_TEXT_LENGTH + 1),
                full.passportIssueDate(), full.passportDivisionCode(), full.gender(), full.birthDate(), full.region(),
                full.locality(), full.street(), full.house(), full.apartment(), "12345", full.firstNameDative(),
                full.lastNameDative(), full.middleNameDative(), full.education(), full.diplomaProfession(),
                full.diplomaInstitution(), full.diplomaLastName(), full.diplomaNumber(), full.diplomaSeries(),
                full.diplomaRegistrationNumber(), full.diplomaIssueDate()
        );

        assertThat(messages(LearnerRules.check(profile, TODAY))).containsExactly(
                "PASSPORT_ISSUED_BY: Значение длиннее 500 символов",
                "POSTAL_CODE: Индекс — 6 цифр"
        );
    }

    private static List<String> messages(List<LearnerFieldError> errors) {
        return errors.stream().map(error -> error.field() + ": " + error.message()).toList();
    }

    private static LearnerProfile withDocuments(LearnerProfile base, String snils, String series, String divisionCode) {
        return new LearnerProfile(
                base.lastName(), base.firstName(), base.middleName(), base.phone(), base.email(), snils, series,
                base.passportNumber(), base.passportIssuedBy(), base.passportIssueDate(), divisionCode, base.gender(),
                base.birthDate(), base.region(), base.locality(), base.street(), base.house(), base.apartment(),
                base.postalCode(), base.firstNameDative(), base.lastNameDative(), base.middleNameDative(),
                base.education(), base.diplomaProfession(), base.diplomaInstitution(), base.diplomaLastName(),
                base.diplomaNumber(), base.diplomaSeries(), base.diplomaRegistrationNumber(), base.diplomaIssueDate()
        );
    }

    private static LearnerProfile withDates(LocalDate birth, LocalDate passportIssued, LocalDate diplomaIssued) {
        LearnerProfile base = LearnerTestData.fullProfile();
        boolean passport = passportIssued != null;
        return new LearnerProfile(
                base.lastName(), base.firstName(), base.middleName(), base.phone(), base.email(), base.snils(),
                passport ? base.passportSeries() : null, passport ? base.passportNumber() : null,
                passport ? base.passportIssuedBy() : null, passportIssued,
                passport ? base.passportDivisionCode() : null, base.gender(), birth, base.region(), base.locality(), base.street(),
                base.house(), base.apartment(), base.postalCode(), base.firstNameDative(), base.lastNameDative(),
                base.middleNameDative(), base.education(), base.diplomaProfession(), base.diplomaInstitution(),
                base.diplomaLastName(), base.diplomaNumber(), base.diplomaSeries(), base.diplomaRegistrationNumber(),
                diplomaIssued
        );
    }
}
