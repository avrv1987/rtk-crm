package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

public record ProductAgreement(
        UUID id,
        UUID productId,
        String productName,
        boolean productArchived,
        String contractNumber,
        Boolean licenseSigned,
        Integer licenseExpiryYear,
        String transferStatus,
        boolean archived,
        String vendorName,
        UUID scanAttachmentId,
        List<ProductTransfer> transfers
) {
}
