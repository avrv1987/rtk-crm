package ru.rtk.crm.interaction;

import java.time.OffsetDateTime;

public record InteractionNextStep(String nextAction, OffsetDateTime nextActionAt) {
}
