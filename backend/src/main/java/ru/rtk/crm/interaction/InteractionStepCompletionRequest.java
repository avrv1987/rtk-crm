package ru.rtk.crm.interaction;

public record InteractionStepCompletionRequest(
        Integer version,
        String result,
        InteractionNextStep nextStep,
        InteractionContactMark contact
) {
    public InteractionStepCompletionRequest(Integer version, String result, InteractionNextStep nextStep) {
        this(version, result, nextStep, null);
    }
}
