package ru.rtk.crm.enrolment;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

enum Gender {
    MALE("М"),
    FEMALE("Ж");

    private final String title;

    Gender(String title) {
        this.title = title;
    }

    String title() {
        return title;
    }

    static Optional<Gender> fromTitle(String value) {
        String key = value.strip();
        return Arrays.stream(values()).filter(gender -> gender.title.equalsIgnoreCase(key)).findFirst();
    }
}

enum Education {
    NONE("Без образования"),
    BASIC_GENERAL("Основное общее образование - 9 классов"),
    SECONDARY_GENERAL("Среднее общее образование - 11 классов"),
    SECONDARY_VOCATIONAL("Среднее профессиональное образование"),
    HIGHER_BACHELOR("Высшее образование \u2013 бакалавриат"),
    HIGHER_SPECIALIST_MASTER("Высшее образование \u2013 специалитет, магистратура"),
    HIGHER_QUALIFICATION("Высшее образование \u2013 подготовка кадров высшей квалификации");

    private final String title;

    Education(String title) {
        this.title = title;
    }

    String title() {
        return title;
    }

    static Optional<Education> fromTitle(String value) {
        String key = key(value);
        return Arrays.stream(values()).filter(education -> key(education.title).equals(key)).findFirst();
    }

    private static String key(String value) {
        return LearnerRules.collapseSpaces(value)
                .replaceAll("\\s*[-\u2013\u2014]\\s*", " - ")
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е');
    }
}

record LearnerFieldError(LearnerField field, String message) {
}

record LearnerWorkbookRow(int rowNumber, LearnerProfile profile, List<LearnerFieldError> errors) {
    boolean valid() {
        return errors.isEmpty();
    }
}

record LearnerWorkbook(List<String> ignoredHeaders, List<LearnerWorkbookRow> rows) {
}

record PaidOrder(
        String orderNumber,
        String course,
        int streamNumber,
        String lastName,
        String firstName,
        String middleName,
        String phone,
        String email,
        String version
) {
    String emailKey() {
        return LearnerRules.emailKey(email);
    }

    @Override
    public String toString() {
        return "PaidOrder[orderNumber=" + orderNumber + ", course=" + course + ", streamNumber=" + streamNumber + "]";
    }
}

record PaidOrderIssue(int position, String field, String message, boolean warning) {
    PaidOrderIssue(int position, String field, String message) {
        this(position, field, message, false);
    }
}

record PaidOrderBatch(List<PaidOrder> orders, List<PaidOrderIssue> issues, int emptyElements) {
}
