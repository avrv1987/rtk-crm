package ru.rtk.crm.privacy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

enum PersonalDataRequestKind {
    INFORMATION("сведения об обработке"),
    RECTIFICATION("уточнение"),
    DELETION("удаление"),
    CONSENT_WITHDRAWAL("отзыв согласия");

    private final String label;

    PersonalDataRequestKind(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}

enum PersonalDataRequestStatus {
    NEW("новое"),
    IN_PROGRESS("в работе"),
    DONE("исполнено"),
    REJECTED("отказано");

    private final String label;

    PersonalDataRequestStatus(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    boolean closed() {
        return this == DONE || this == REJECTED;
    }
}

enum PersonalDataDueState { SOON, OVERDUE }

record PersonalDataExecution(UUID auditEventId, String label, OffsetDateTime occurredAt, String details) {
}

record PersonalDataRequest(
        UUID id,
        LocalDate receivedOn,
        String subjectName,
        PersonalDataRequestKind kind,
        String channel,
        LocalDate dueOn,
        UUID assigneeId,
        String assigneeName,
        PersonalDataRequestStatus status,
        LocalDate completedOn,
        PersonalDataExecution execution,
        String note,
        PersonalDataDueState dueState
) {
}

record PersonalDataRequestInput(
        LocalDate receivedOn,
        String subjectName,
        PersonalDataRequestKind kind,
        String channel,
        LocalDate dueOn,
        UUID assigneeId,
        PersonalDataRequestStatus status,
        LocalDate completedOn,
        UUID auditEventId,
        String note
) {
}

record PersonalDataAssignee(UUID id, String name) {
}

record PersonalDataRequests(
        List<PersonalDataRequest> requests,
        List<PersonalDataExecution> executions,
        List<PersonalDataAssignee> assignees,
        int responseDays,
        boolean workingDays,
        int remindDays
) {
}

record AdminReminders(List<PersonalDataRequest> personalDataRequests, int remindDays) {
}
