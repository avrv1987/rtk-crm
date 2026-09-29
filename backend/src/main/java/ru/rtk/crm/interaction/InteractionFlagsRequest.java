package ru.rtk.crm.interaction;

public record InteractionFlagsRequest(
        Integer version,
        InteractionWaiting waitingOn,
        String waitingNote
) {
}
