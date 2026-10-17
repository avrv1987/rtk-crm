package ru.rtk.crm.report;

public enum ManagerWorkDue {
    OVERDUE("Просрочено"),
    THIS_WEEK("На этой неделе"),
    NO_NEXT_STEP("Без шага или срока"),
    IN_ORDER("В порядке");

    private final String label;

    ManagerWorkDue(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
