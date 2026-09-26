package ru.rtk.crm.catalog;

import java.util.List;

public record VendorContactList(List<VendorContact> contacts, List<CatalogReference> products) {
}
