package ru.rtk.crm.enrolment;

public class EnrolmentNotFoundException extends RuntimeException {
    public EnrolmentNotFoundException(String message) {
        super(message);
    }
}
