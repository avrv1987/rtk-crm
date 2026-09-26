package ru.rtk.crm.enrolment;

public record PaidOrder(
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
        return email == null ? null : LearnerRules.emailKey(email);
    }

    @Override
    public String toString() {
        return "PaidOrder[orderNumber=" + orderNumber + ", course=" + course + ", streamNumber=" + streamNumber + "]";
    }
}
