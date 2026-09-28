package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class LmsSignalModels {
    private LmsSignalModels() {
    }

    public enum LmsSignalType {
        STUDENTS_APPEARED,
        NO_STUDENTS,
        LOW_COMPLETION
    }

    public record LmsSignals(OffsetDateTime calculatedAt, List<LmsSignal> items) {
    }

    public record LmsSignal(
            String id,
            LmsSignalType type,
            String title,
            String message,
            UUID interactionId,
            String interactionTitle,
            UUID organizationId,
            String organizationName,
            UUID ownerManagerId,
            String ownerManagerName,
            String currentStageName,
            UUID mappingId,
            String courseName,
            String groupName,
            LocalDate runStartsOn,
            LocalDate runEndsOn,
            int participants,
            Integer completed,
            OffsetDateTime observedAt,
            LmsSignalAction action,
            String actionHint,
            boolean dismissed
    ) {
    }

    public record LmsSignalAction(UUID stageId, String stageName, boolean commentRequired) {
    }

    public record LmsSignalDismissalRequest(LmsSignalType type, UUID mappingId) {
    }
}
