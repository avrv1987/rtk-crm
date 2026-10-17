package ru.rtk.crm.report;

import java.time.OffsetDateTime;
import java.util.UUID;

import ru.rtk.crm.interaction.InteractionDue;
import ru.rtk.crm.interaction.InteractionMarks;

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
        boolean completed
) {
    public ManagerWorkDue due(OffsetDateTime now) {
        if (nextActionAt != null && nextActionAt.isBefore(now)) {
            return ManagerWorkDue.OVERDUE;
        }
        if (nextAction == null || nextActionAt == null) {
            return ManagerWorkDue.NO_NEXT_STEP;
        }
        return nextActionAt.isBefore(InteractionDue.weekStart(now).plusWeeks(1))
                ? ManagerWorkDue.THIS_WEEK
                : ManagerWorkDue.IN_ORDER;
    }

    public boolean withIssues() {
        return marks != null && (marks.problemCount() > 0 || marks.riskCount() > 0);
    }
}
