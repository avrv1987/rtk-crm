package ru.rtk.crm.catalog;

import java.util.List;

public record OrganizationPage(List<Organization> items, int page, int size, long total) {
}
