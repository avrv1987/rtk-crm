package ru.rtk.crm.interaction;

public record WorkflowTransitionInput(Integer fromOrder, Integer toOrder, Boolean commentRequired) {
}
