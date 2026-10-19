package ru.rtk.crm.report;

import java.time.OffsetDateTime;
import java.util.UUID;

import ru.rtk.crm.interaction.InteractionMarks;
import ru.rtk.crm.interaction.InteractionWorkStatus;

public record ManagerWorkLine(
        UUID interactionId,
        String title,
        UUID organizationId,
        String organizationName,
        UUID teamId,
        String teamName,
        UUID managerId,
        String managerName,
        String directionName,
        String programName,
        String stageName,
        int stagePosition,
        int stageTotal,
        Long daysOnStage,
        String stageAtStart,
        String stageAtEnd,
        String nextAction,
        OffsetDateTime nextActionAt,
        InteractionMarks marks,
        long transitions,
        boolean created,
        boolean completed,
        UUID programId,
        UUID kindId,
        UUID routeId,
        boolean missingDocuments
) {
    public ManagerWorkLine(UUID interactionId, String title, UUID organizationId, String organizationName, UUID teamId, String teamName,
                           UUID managerId, String managerName, String directionName, String programName, String stageName,
                           int stagePosition, int stageTotal, Long daysOnStage, String stageAtStart, String stageAtEnd,
                           String nextAction, OffsetDateTime nextActionAt, InteractionMarks marks, long transitions,
                           boolean created, boolean completed) {
        this(interactionId, title, organizationId, organizationName, teamId, teamName, managerId, managerName, directionName,
                programName, stageName, stagePosition, stageTotal, daysOnStage, stageAtStart, stageAtEnd, nextAction, nextActionAt,
                marks, transitions, created, completed, null, null, null, false);
    }

    public ManagerWorkDue due(OffsetDateTime now) {
        return ManagerWorkDue.of(marks == null ? InteractionWorkStatus.ACTIVE : marks.status(), nextAction, nextActionAt, now);
    }

    public boolean withIssues() {
        return marks != null && (marks.problemCount() > 0 || marks.riskCount() > 0);
    }
}
