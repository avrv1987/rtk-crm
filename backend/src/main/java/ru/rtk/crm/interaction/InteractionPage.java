package ru.rtk.crm.interaction;

import java.util.List;

public record InteractionPage(List<InteractionSummary> items, int page, int size, long total) {
}
