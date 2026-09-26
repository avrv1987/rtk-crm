package ru.rtk.crm.catalog;

import java.util.List;

public record AdminOrganizationPage(List<AdminOrganization> items, int page, int size, long total) {
}
