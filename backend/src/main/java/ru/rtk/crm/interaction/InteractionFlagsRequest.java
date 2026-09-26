package ru.rtk.crm.interaction;

public record InteractionFlagsRequest(
        Integer version,
        InteractionWaiting waitingOn,
        String waitingNote,
        String problem,
        InteractionRiskLevel riskLevel,
        String riskReason
) {
}
