package ru.rtk.crm.training;

import java.time.LocalDate;
import java.util.UUID;

public record TeacherTrainingRequest(
        Integer version,
        UUID stageId,
        LocalDate trainedOn,
        String courseName,
        Integer enrolledCount,
        Integer completedCount,
        UUID attachmentId,
        LocalDate nextCycleOn,
        Boolean remind
) {
}
