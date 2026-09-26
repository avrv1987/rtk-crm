package ru.rtk.crm.enrolment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LearnerRules {
    static final int MAX_TEXT_LENGTH = 500;
    static final int MAX_EMAIL_LENGTH = 254;
    static final LocalDate EARLIEST_BIRTH_DATE = LocalDate.of(1900, 1, 1);
    static final long LAST_UNCHECKED_SNILS = 1_001_998L;
    static final int ADULT_AGE = 18;
    static final int PASSPORT_AGE = 14;
    static final List<LearnerField> REQUIRED =
            List.of(LearnerField.LAST_NAME, LearnerField.FIRST_NAME, LearnerField.PHONE, LearnerField.EMAIL);
    static final List<LearnerField> PASSPORT = List.of(
            LearnerField.PASSPORT_SERIES, LearnerField.PASSPORT_NUMBER, LearnerField.PASSPORT_ISSUED_BY,
            LearnerField.PASSPORT_ISSUE_DATE, LearnerField.PASSPORT_DIVISION_CODE
    );
    static final Set<LearnerField> OPTIONAL = EnumSet.of(LearnerField.MIDDLE_NAME, LearnerField.APARTMENT);
    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_DIPLOMA_CODE_LENGTH = 50;
    private static final Set<LearnerField> NAMES = EnumSet.of(
            LearnerField.LAST_NAME, LearnerField.FIRST_NAME, LearnerField.MIDDLE_NAME, LearnerField.FIRST_NAME_DATIVE,
            LearnerField.LAST_NAME_DATIVE, LearnerField.MIDDLE_NAME_DATIVE, LearnerField.DIPLOMA_LAST_NAME
    );
    private static final Map<LearnerField, Integer> ADDRESS_LENGTHS = Map.of(
            LearnerField.REGION, 200, LearnerField.LOCALITY, 200, LearnerField.STREET, 200,
            LearnerField.HOUSE, 20, LearnerField.APARTMENT, 20
    );
    private static final Set<LearnerField> DIPLOMA_CODES = EnumSet.of(
            LearnerField.DIPLOMA_NUMBER, LearnerField.DIPLOMA_SERIES, LearnerField.DIPLOMA_REGISTRATION_NUMBER
    );
    private static final Pattern NAME = Pattern.compile("[\\p{L} .'’\\-]{1," + MAX_NAME_LENGTH + "}");
    private static final Pattern DIPLOMA_CODE = Pattern.compile("[\\p{L}\\p{Nd} \\-]{1," + MAX_DIPLOMA_CODE_LENGTH + "}");

    private static final Pattern SPACES = Pattern.compile("(?U)\\s+");
    private static final Pattern PHONE_INPUT = Pattern.compile("(?U)\\+?[0-9()\\-\\s]+");
    private static final Pattern PHONE = Pattern.compile("\\+7[3-9]\\d{9}");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern SNILS_INPUT = Pattern.compile("(?U)[0-9\\-\\s]+");
    private static final Pattern SNILS = Pattern.compile("\\d{11}");
    private static final Pattern DIVISION_CODE_INPUT = Pattern.compile("(?U)(\\d{3})\\s*-?\\s*(\\d{3})");
    private static final Pattern DIVISION_CODE = Pattern.compile("\\d{3}-\\d{3}");
    private static final Pattern PASSPORT_SERIES = Pattern.compile("\\d{4}");
    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

    private LearnerRules() {
    }

    static String collapseSpaces(String value) {
        return SPACES.matcher(value).replaceAll(" ").strip();
    }

    static String normalizePhone(String value) {
        if (value == null || !PHONE_INPUT.matcher(value.strip()).matches()) {
            return null;
        }
        boolean plus = value.strip().startsWith("+");
        String digits = value.replaceAll("\\D", "");
        if (digits.length() == 11 && (digits.charAt(0) == '7' || digits.charAt(0) == '8' && !plus)) {
            digits = digits.substring(1);
        } else if (digits.length() != 10 || plus) {
            return null;
        }
        return digits.charAt(0) >= '3' ? "+7" + digits : null;
    }

    static String normalizeSnils(String value) {
        if (value == null || !SNILS_INPUT.matcher(value.strip()).matches()) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.length() == 11 ? digits : null;
    }

    static String normalizeDivisionCode(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = DIVISION_CODE_INPUT.matcher(value.strip());
        return matcher.matches() ? matcher.group(1) + "-" + matcher.group(2) : null;
    }

    static boolean isEmail(String value) {
        return value.length() <= MAX_EMAIL_LENGTH && EMAIL.matcher(value).matches();
    }

    static String nameKey(String value) {
        return collapseSpaces(value).toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    static String emailKey(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    static boolean snilsChecksumMatches(String digits) {
        if (Long.parseLong(digits.substring(0, 9)) <= LAST_UNCHECKED_SNILS) {
            return true;
        }
        int sum = 0;
        for (int index = 0; index < 9; index++) {
            sum += (digits.charAt(index) - '0') * (9 - index);
        }
        int control = sum % 101;
        return (control == 100 ? 0 : control) == Integer.parseInt(digits.substring(9));
    }

    static List<LearnerFieldError> check(LearnerProfile profile, LocalDate today) {
        List<LearnerFieldError> errors = new ArrayList<>();
        for (LearnerField field : LearnerField.values()) {
            if (profile.value(field) instanceof String text) {
                if (text.length() > MAX_TEXT_LENGTH) {
                    errors.add(new LearnerFieldError(field, "Значение длиннее " + MAX_TEXT_LENGTH + " символов"));
                } else if (NAMES.contains(field) && !NAME.matcher(text).matches()) {
                    errors.add(new LearnerFieldError(field, "«" + field.label() + "»: до " + MAX_NAME_LENGTH
                            + " символов — буквы, пробел, дефис, апостроф, точка"));
                } else if (ADDRESS_LENGTHS.containsKey(field) && text.length() > ADDRESS_LENGTHS.get(field)) {
                    errors.add(new LearnerFieldError(field, "«" + field.label() + "»: не длиннее " + ADDRESS_LENGTHS.get(field)
                            + " символов"));
                } else if (DIPLOMA_CODES.contains(field) && !DIPLOMA_CODE.matcher(text).matches()) {
                    errors.add(new LearnerFieldError(field, "«" + field.label() + "»: до " + MAX_DIPLOMA_CODE_LENGTH
                            + " символов — буквы, цифры, пробел, дефис"));
                }
            }
        }
        matches(errors, LearnerField.PHONE, profile.phone(), PHONE,
                "Телефон должен содержать 10 цифр после +7 или 8, например +7 900 000-00-00");
        if (profile.email() != null && !isEmail(profile.email())) {
            errors.add(new LearnerFieldError(LearnerField.EMAIL, "Email указан неверно"));
        }
        if (profile.snils() != null) {
            if (!SNILS.matcher(profile.snils()).matches()) {
                errors.add(new LearnerFieldError(LearnerField.SNILS, "СНИЛС должен состоять из 11 цифр"));
            } else if (!snilsChecksumMatches(profile.snils())) {
                errors.add(new LearnerFieldError(LearnerField.SNILS, "Контрольное число СНИЛС не совпадает с номером"));
            }
        }
        matches(errors, LearnerField.PASSPORT_SERIES, profile.passportSeries(), PASSPORT_SERIES,
                "Серия паспорта — 4 цифры");
        matches(errors, LearnerField.PASSPORT_NUMBER, profile.passportNumber(), SIX_DIGITS,
                "Номер паспорта — 6 цифр");
        matches(errors, LearnerField.PASSPORT_DIVISION_CODE, profile.passportDivisionCode(), DIVISION_CODE,
                "Код подразделения — 6 цифр в формате 000-000");
        matches(errors, LearnerField.POSTAL_CODE, profile.postalCode(), SIX_DIGITS, "Индекс — 6 цифр");
        if (profile.birthDate() != null) {
            if (profile.birthDate().isAfter(today)) {
                errors.add(new LearnerFieldError(LearnerField.BIRTH_DATE, "Дата рождения не может быть в будущем"));
            } else if (profile.birthDate().isBefore(EARLIEST_BIRTH_DATE)) {
                errors.add(new LearnerFieldError(LearnerField.BIRTH_DATE, "Дата рождения не может быть раньше 01.01.1900"));
            } else if (profile.birthDate().plusYears(ADULT_AGE).isAfter(today)) {
                errors.add(new LearnerFieldError(LearnerField.BIRTH_DATE,
                        "Слушатель младше 18 лет: обработка данных несовершеннолетних не предусмотрена"));
            }
        }
        passportIssueDate(errors, profile.passportIssueDate(), profile.birthDate(), today);
        issueDate(errors, LearnerField.DIPLOMA_ISSUE_DATE, profile.diplomaIssueDate(), profile.birthDate(), today);
        if (PASSPORT.stream().anyMatch(field -> profile.value(field) != null)) {
            PASSPORT.stream().filter(field -> profile.value(field) == null).forEach(field -> errors.add(new LearnerFieldError(
                    field, "Паспорт заполняется целиком: заполните поле «" + field.label() + "»"
            )));
        }
        return List.copyOf(errors);
    }

    static List<LearnerFieldError> clearedRequired(LearnerProfile profile, Set<LearnerField> changed) {
        return REQUIRED.stream()
                .filter(field -> changed.contains(field) && profile.value(field) == null)
                .map(field -> new LearnerFieldError(field, "Не заполнено обязательное поле «" + field.label() + "»"))
                .toList();
    }

    static boolean samePerson(LearnerProfile profile, String lastName, String firstName, String middleName) {
        return profile != null && profile.lastName() != null && profile.firstName() != null && lastName != null && firstName != null
                && nameKey(profile.lastName()).equals(nameKey(lastName))
                && nameKey(profile.firstName()).equals(nameKey(firstName))
                && (middleName == null || profile.middleName() == null || nameKey(profile.middleName()).equals(nameKey(middleName)));
    }

    static LearnerCompleteness completeness(Set<LearnerField> missing) {
        Set<LearnerField> counted = EnumSet.complementOf(EnumSet.copyOf(OPTIONAL));
        if (missing.contains(LearnerField.MIDDLE_NAME)) {
            counted.remove(LearnerField.MIDDLE_NAME_DATIVE);
        }
        int filled = (int) counted.stream().filter(field -> !missing.contains(field)).count();
        return new LearnerCompleteness(filled, counted.size());
    }

    static Set<LearnerField> missingForLms(Set<LearnerField> missing) {
        Set<LearnerField> fields = EnumSet.noneOf(LearnerField.class);
        REQUIRED.stream().filter(missing::contains).forEach(fields::add);
        return fields;
    }

    private static void matches(
            List<LearnerFieldError> errors, LearnerField field, String value, Pattern pattern, String message
    ) {
        if (value != null && !pattern.matcher(value).matches()) {
            errors.add(new LearnerFieldError(field, message));
        }
    }

    private static void passportIssueDate(List<LearnerFieldError> errors, LocalDate date, LocalDate birthDate, LocalDate today) {
        if (date == null) {
            return;
        }
        if (date.isAfter(today)) {
            errors.add(new LearnerFieldError(LearnerField.PASSPORT_ISSUE_DATE, "Дата выдачи паспорта не может быть в будущем"));
        } else if (birthDate != null && date.isBefore(birthDate.plusYears(PASSPORT_AGE))) {
            errors.add(new LearnerFieldError(LearnerField.PASSPORT_ISSUE_DATE, "Дата выдачи паспорта раньше 14-летия"));
        }
    }

    private static void issueDate(
            List<LearnerFieldError> errors, LearnerField field, LocalDate date, LocalDate birthDate, LocalDate today
    ) {
        if (date == null) {
            return;
        }
        if (date.isAfter(today)) {
            errors.add(new LearnerFieldError(field, field.label() + " не может быть в будущем"));
        } else if (birthDate != null && !date.isAfter(birthDate)) {
            errors.add(new LearnerFieldError(field, field.label() + " должна быть позже даты рождения"));
        }
    }
}
