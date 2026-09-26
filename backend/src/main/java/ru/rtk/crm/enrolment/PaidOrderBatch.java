package ru.rtk.crm.enrolment;

import java.util.List;

public record PaidOrderBatch(List<PaidOrder> orders, List<PaidOrderIssue> issues, int elements, int emptyElements, int duplicates) {
    public int received() {
        return elements - emptyElements;
    }

    public int rejected() {
        return received() - orders.size() - duplicates;
    }
}
