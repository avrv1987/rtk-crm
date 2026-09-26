package ru.rtk.crm.catalog;

import java.util.List;
import java.util.UUID;

public record VendorContact(
        UUID id,
        UUID vendorId,
        String name,
        String phone,
        String email,
        boolean prefersEmail,
        boolean prefersTelegram,
        boolean archived,
        int version,
        List<CatalogReference> products
) {
}
