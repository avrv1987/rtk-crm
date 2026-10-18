package ru.rtk.crm.interaction;

import java.time.LocalDate;

public record InteractionStatusRequest(Integer version, InteractionWorkStatus status, String reason, LocalDate resumeOn) {
    public InteractionStatusRequest(Integer version, InteractionWorkStatus status, String reason) {
        this(version, status, reason, null);
    }
}
