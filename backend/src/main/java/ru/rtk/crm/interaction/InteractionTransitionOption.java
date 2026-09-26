package ru.rtk.crm.interaction;

import java.util.UUID;

public record InteractionTransitionOption(UUID stageId, String stageName, boolean commentRequired) {
}
