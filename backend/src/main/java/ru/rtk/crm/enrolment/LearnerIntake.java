package ru.rtk.crm.enrolment;

public record LearnerIntake(
        int newLearners,
        int foundLearners,
        int enrolled,
        int repeatOrders,
        int movedStreams,
        int movedAfterLms,
        int contactsDiffer
) {
    public static final LearnerIntake NONE = new LearnerIntake(0, 0, 0, 0, 0, 0, 0);

    public LearnerIntake plus(LearnerIntake other) {
        return new LearnerIntake(
                newLearners + other.newLearners,
                foundLearners + other.foundLearners,
                enrolled + other.enrolled,
                repeatOrders + other.repeatOrders,
                movedStreams + other.movedStreams,
                movedAfterLms + other.movedAfterLms,
                contactsDiffer + other.contactsDiffer
        );
    }

    String details() {
        return "новых слушателей: " + newLearners + ", найдено: " + foundLearners + ", зачислений: " + enrolled
                + ", повторных заявок в потоке: " + repeatOrders + ", перенесено в другой поток: " + movedStreams
                + " (после выгрузки в LMS: " + movedAfterLms + "), контакты отличаются от анкеты: " + contactsDiffer;
    }
}
