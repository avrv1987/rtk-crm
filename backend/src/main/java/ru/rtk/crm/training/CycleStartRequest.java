package ru.rtk.crm.training;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CycleStartRequest(String title, UUID templateId, LocalDate startsOn, String nextAction, OffsetDateTime nextActionAt,
                                UUID kindId) {
    public CycleStartRequest(String title, UUID templateId, LocalDate startsOn, String nextAction, OffsetDateTime nextActionAt) {
        this(title, templateId, startsOn, nextAction, nextActionAt, null);
    }

}
