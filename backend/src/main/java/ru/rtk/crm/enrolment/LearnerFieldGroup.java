package ru.rtk.crm.enrolment;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

enum LearnerFieldGroup {
    MAIN(false, EnumSet.range(LearnerField.LAST_NAME, LearnerField.EMAIL)),
    DOCUMENTS(true, EnumSet.range(LearnerField.SNILS, LearnerField.PASSPORT_DIVISION_CODE)),
    PERSONAL(true, EnumSet.range(LearnerField.GENDER, LearnerField.BIRTH_DATE)),
    ADDRESS(true, EnumSet.range(LearnerField.REGION, LearnerField.POSTAL_CODE)),
    DATIVE(false, EnumSet.range(LearnerField.FIRST_NAME_DATIVE, LearnerField.MIDDLE_NAME_DATIVE)),
    EDUCATION(true, EnumSet.range(LearnerField.EDUCATION, LearnerField.DIPLOMA_ISSUE_DATE));

    private static final String HIDDEN = "***";
    private static final Pattern PHONE_FORMAT = Pattern.compile("\\+7\\d{10}");
    private static final Pattern SNILS_FORMAT = Pattern.compile("\\d{11}");

    private final boolean masked;
    private final Set<LearnerField> fields;

    LearnerFieldGroup(boolean masked, Set<LearnerField> fields) {
        this.masked = masked;
        this.fields = fields;
    }

    Set<LearnerField> fields() {
        return fields;
    }

    static LearnerFieldGroup of(LearnerField field) {
        return Arrays.stream(values()).filter(group -> group.fields.contains(field)).findFirst().orElseThrow();
    }

    static String mask(LearnerField field, String value) {
        if (value == null) {
            return null;
        }
        return switch (field) {
            case PHONE -> PHONE_FORMAT.matcher(value).matches()
                    ? "+7 " + value.charAt(2) + "** ***-**-" + value.substring(10)
                    : HIDDEN;
            case EMAIL -> value.lastIndexOf('@') > 0 ? value.charAt(0) + HIDDEN + value.substring(value.lastIndexOf('@')) : HIDDEN;
            case SNILS -> SNILS_FORMAT.matcher(value).matches() ? "***-***-*** " + value.substring(9) : HIDDEN;
            case PASSPORT_NUMBER -> value.length() > 2 ? "****" + value.substring(value.length() - 2) : HIDDEN;
            default -> of(field).masked ? HIDDEN : value;
        };
    }
}
