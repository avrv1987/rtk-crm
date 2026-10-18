package ru.rtk.crm.enrolment;

public record PaidOrderIssue(int position, String field, String message, boolean warning, String orderNumber) {
    PaidOrderIssue(int position, String field, String message) {
        this(position, field, message, false, null);
    }

    PaidOrderIssue(int position, String field, String message, boolean warning) {
        this(position, field, message, warning, null);
    }

    PaidOrderIssue withOrderNumber(String number) {
        return new PaidOrderIssue(position, field, message, warning, number);
    }
}
