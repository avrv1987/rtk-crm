package ru.rtk.crm.training;

import java.time.LocalDate;
import java.util.UUID;

public record InteractionCycle(UUID interactionId, LocalDate startsOn, CycleLink previous, CycleLink next) {
}
