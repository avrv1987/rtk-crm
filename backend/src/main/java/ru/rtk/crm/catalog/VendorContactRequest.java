package ru.rtk.crm.catalog;

import java.util.List;
import java.util.UUID;

public record VendorContactRequest(
        String name,
        String phone,
        String email,
        Boolean prefersEmail,
        Boolean prefersTelegram,
        Boolean archived,
        List<UUID> productIds,
        Integer version
) {
}
