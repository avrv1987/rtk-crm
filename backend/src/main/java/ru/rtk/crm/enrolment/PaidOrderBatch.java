package ru.rtk.crm.enrolment;

import java.util.List;

// heldStreamTransfers — заявки, отложенные до подтверждения: перенос слушателя в ещё не существующий поток.
public record PaidOrderBatch(
        List<PaidOrder> orders, List<PaidOrderIssue> issues, int elements, int emptyElements, int duplicates, int heldStreamTransfers
) {
    public PaidOrderBatch(List<PaidOrder> orders, List<PaidOrderIssue> issues, int elements, int emptyElements, int duplicates) {
        this(orders, issues, elements, emptyElements, duplicates, 0);
    }

    public int received() {
        return elements - emptyElements;
    }

    public int rejected() {
        return received() - orders.size() - duplicates;
    }
}
