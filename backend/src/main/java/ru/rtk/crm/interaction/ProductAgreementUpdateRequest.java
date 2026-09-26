package ru.rtk.crm.interaction;

import java.util.List;

public record ProductAgreementUpdateRequest(
        Integer version,
        ProductAgreementContract contract,
        List<ProductTransfer> transfers
) {
}
