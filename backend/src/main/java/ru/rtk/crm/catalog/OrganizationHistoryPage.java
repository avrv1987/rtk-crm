package ru.rtk.crm.catalog;

import java.util.List;

public record OrganizationHistoryPage(List<OrganizationHistoryItem> items, int page, int size, long total) {
}
