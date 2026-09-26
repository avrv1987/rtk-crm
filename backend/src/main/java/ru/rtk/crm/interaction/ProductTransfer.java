package ru.rtk.crm.interaction;

import java.time.LocalDate;
import java.util.UUID;

public record ProductTransfer(
        ProductTransferKind kind,
        ProductTransferStatus status,
        LocalDate transferredOn,
        UUID attachmentId
) {
}
