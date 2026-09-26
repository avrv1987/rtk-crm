package ru.rtk.crm.interaction;

public record InteractionStatusRequest(Integer version, InteractionWorkStatus status, String reason) {
}
