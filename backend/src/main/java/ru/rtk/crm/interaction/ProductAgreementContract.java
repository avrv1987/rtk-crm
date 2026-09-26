package ru.rtk.crm.interaction;

import java.util.UUID;

public record ProductAgreementContract(
        String contractNumber,
        Boolean licenseSigned,
        Integer licenseExpiryYear,
        UUID scanAttachmentId
) {
}
