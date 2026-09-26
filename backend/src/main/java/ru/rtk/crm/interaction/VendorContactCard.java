package ru.rtk.crm.interaction;

public record VendorContactCard(
        String name,
        String phone,
        String email,
        boolean prefersEmail,
        boolean prefersTelegram
) {
}
