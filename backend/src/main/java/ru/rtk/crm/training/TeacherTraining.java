package ru.rtk.crm.training;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TeacherTraining(
        UUID id,
        UUID interactionId,
        UUID eventId,
        LocalDate trainedOn,
        String courseName,
        int enrolledCount,
        Integer completedCount,
        UUID attachmentId,
        String attachmentName,
        LocalDate nextCycleOn,
        String createdByName,
        OffsetDateTime createdAt
) {
}
