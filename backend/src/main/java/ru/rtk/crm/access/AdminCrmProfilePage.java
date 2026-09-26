package ru.rtk.crm.access;

import java.util.List;

public record AdminCrmProfilePage(List<AdminCrmProfile> items, int page, int size, long total, long pendingTotal) {
}
