package ru.rtk.crm.interaction;

public enum InteractionWorkStatus {
    ACTIVE("Активна"),
    PAUSED("Приостановлена"),
    COMPLETED("Завершена");

    private final String label;

    InteractionWorkStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
