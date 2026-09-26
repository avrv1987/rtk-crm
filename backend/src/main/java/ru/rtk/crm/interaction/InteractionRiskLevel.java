package ru.rtk.crm.interaction;

public enum InteractionRiskLevel {
    MEDIUM("средний"),
    HIGH("высокий");

    private final String label;

    InteractionRiskLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
