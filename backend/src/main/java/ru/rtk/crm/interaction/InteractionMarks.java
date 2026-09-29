package ru.rtk.crm.interaction;

public record InteractionMarks(
        InteractionWorkStatus status,
        String statusReason,
        InteractionWaiting waitingOn,
        String waitingNote,
        int problemCount,
        int riskCount,
        InteractionRiskLevel riskLevel,
        String problems,
        String risks
) {
    InteractionMarks withStatus(InteractionWorkStatus nextStatus, String nextReason) {
        return new InteractionMarks(nextStatus, nextReason, waitingOn, waitingNote, problemCount, riskCount, riskLevel, problems, risks);
    }

    InteractionMarks withWaiting(InteractionWaiting nextWaitingOn, String nextWaitingNote) {
        return new InteractionMarks(status, statusReason, nextWaitingOn, nextWaitingNote, problemCount, riskCount, riskLevel, problems, risks);
    }
}
