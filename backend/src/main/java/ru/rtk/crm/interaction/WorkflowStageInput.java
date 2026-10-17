package ru.rtk.crm.interaction;

import java.util.UUID;

public record WorkflowStageInput(String name, Integer order, Boolean optional, UUID id) {
}
