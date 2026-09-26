package ru.rtk.crm.interaction;

import java.util.UUID;

public record InteractionStage(UUID id, String name, int order, boolean optional) {
}
