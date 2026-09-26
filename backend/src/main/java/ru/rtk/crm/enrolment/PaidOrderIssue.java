package ru.rtk.crm.enrolment;

public record PaidOrderIssue(int position, String field, String message, boolean warning) {
    PaidOrderIssue(int position, String field, String message) {
        this(position, field, message, false);
    }
}
