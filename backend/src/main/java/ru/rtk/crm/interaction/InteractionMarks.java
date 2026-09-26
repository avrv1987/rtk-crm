package ru.rtk.crm.interaction;

public record InteractionMarks(
        InteractionWorkStatus status,
        String statusReason,
        InteractionWaiting waitingOn,
        String waitingNote,
        String problem,
        InteractionRiskLevel riskLevel,
        String riskReason
) {
}
