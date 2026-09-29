package ru.rtk.crm.interaction;

import java.util.List;

public record InteractionIssuePage(List<InteractionIssue> items, int page, int size, long total) {
}
