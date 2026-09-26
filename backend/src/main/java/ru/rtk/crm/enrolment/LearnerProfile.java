package ru.rtk.crm.enrolment;

import java.time.LocalDate;

record LearnerProfile(
        String lastName,
        String firstName,
        String middleName,
        String phone,
        String email,
        String snils,
        String passportSeries,
        String passportNumber,
        String passportIssuedBy,
        LocalDate passportIssueDate,
        String passportDivisionCode,
        Gender gender,
        LocalDate birthDate,
        String region,
        String locality,
        String street,
        String house,
        String apartment,
        String postalCode,
        String firstNameDative,
        String lastNameDative,
        String middleNameDative,
        Education education,
        String diplomaProfession,
        String diplomaInstitution,
        String diplomaLastName,
        String diplomaNumber,
        String diplomaSeries,
        String diplomaRegistrationNumber,
        LocalDate diplomaIssueDate
) {
    LearnerProfile normalized() {
        return new LearnerProfile(
                text(lastName),
                text(firstName),
                text(middleName),
                normalizedOrText(phone, LearnerRules.normalizePhone(phone)),
                text(email),
                normalizedOrText(snils, LearnerRules.normalizeSnils(snils)),
                withoutSpaces(passportSeries),
                withoutSpaces(passportNumber),
                text(passportIssuedBy),
                passportIssueDate,
                normalizedOrText(passportDivisionCode, LearnerRules.normalizeDivisionCode(passportDivisionCode)),
                gender,
                birthDate,
                text(region),
                text(locality),
                text(street),
                text(house),
                text(apartment),
                withoutSpaces(postalCode),
                text(firstNameDative),
                text(lastNameDative),
                text(middleNameDative),
                education,
                text(diplomaProfession),
                text(diplomaInstitution),
                text(diplomaLastName),
                text(diplomaNumber),
                text(diplomaSeries),
                text(diplomaRegistrationNumber),
                diplomaIssueDate
        );
    }

    Object value(LearnerField field) {
        return switch (field) {
            case LAST_NAME -> lastName;
            case FIRST_NAME -> firstName;
            case MIDDLE_NAME -> middleName;
            case PHONE -> phone;
            case EMAIL -> email;
            case SNILS -> snils;
            case PASSPORT_SERIES -> passportSeries;
            case PASSPORT_NUMBER -> passportNumber;
            case PASSPORT_ISSUED_BY -> passportIssuedBy;
            case PASSPORT_ISSUE_DATE -> passportIssueDate;
            case PASSPORT_DIVISION_CODE -> passportDivisionCode;
            case GENDER -> gender;
            case BIRTH_DATE -> birthDate;
            case REGION -> region;
            case LOCALITY -> locality;
            case STREET -> street;
            case HOUSE -> house;
            case APARTMENT -> apartment;
            case POSTAL_CODE -> postalCode;
            case FIRST_NAME_DATIVE -> firstNameDative;
            case LAST_NAME_DATIVE -> lastNameDative;
            case MIDDLE_NAME_DATIVE -> middleNameDative;
            case EDUCATION -> education;
            case DIPLOMA_PROFESSION -> diplomaProfession;
            case DIPLOMA_INSTITUTION -> diplomaInstitution;
            case DIPLOMA_LAST_NAME -> diplomaLastName;
            case DIPLOMA_NUMBER -> diplomaNumber;
            case DIPLOMA_SERIES -> diplomaSeries;
            case DIPLOMA_REGISTRATION_NUMBER -> diplomaRegistrationNumber;
            case DIPLOMA_ISSUE_DATE -> diplomaIssueDate;
        };
    }

    @Override
    public String toString() {
        return "LearnerProfile[masked]";
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String collapsed = LearnerRules.collapseSpaces(value);
        return collapsed.isEmpty() ? null : collapsed;
    }

    private static String withoutSpaces(String value) {
        String text = text(value);
        return text == null ? null : text.replace(" ", "");
    }

    private static String normalizedOrText(String value, String normalized) {
        return normalized != null ? normalized : text(value);
    }
}
