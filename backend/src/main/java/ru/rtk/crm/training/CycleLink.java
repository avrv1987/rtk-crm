package ru.rtk.crm.training;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CycleLink(UUID interactionId, String title, LocalDate startsOn, OffsetDateTime createdAt) {
}
