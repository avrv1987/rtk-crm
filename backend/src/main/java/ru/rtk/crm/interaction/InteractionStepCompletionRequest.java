package ru.rtk.crm.interaction;

public record InteractionStepCompletionRequest(Integer version, String result, InteractionNextStep nextStep) {
}
