package ru.rtk.crm.report;

import java.time.OffsetDateTime;
import java.util.UUID;

import ru.rtk.crm.interaction.InteractionDue;
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
        boolean completed
) {
    // Правило срока общее с «Моей работой» (InteractionDue): завершённой работе следующий шаг не нужен — она «В порядке»,
    // приостановленная оценивается по дате возврата и в «Без шага» не попадает.
    public ManagerWorkDue due(OffsetDateTime now) {
        InteractionWorkStatus status = marks == null ? InteractionWorkStatus.ACTIVE : marks.status();
        if (InteractionDue.OVERDUE.matches(status, nextAction, nextActionAt, now)) {
            return ManagerWorkDue.OVERDUE;
        }
        if (InteractionDue.NO_NEXT_STEP.matches(status, nextAction, nextActionAt, now)) {
            return ManagerWorkDue.NO_NEXT_STEP;
        }
        return InteractionDue.THIS_WEEK.matches(status, nextAction, nextActionAt, now) ? ManagerWorkDue.THIS_WEEK : ManagerWorkDue.IN_ORDER;
    }

    public boolean withIssues() {
        return marks != null && (marks.problemCount() > 0 || marks.riskCount() > 0);
    }
}
