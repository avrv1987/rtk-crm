package ru.rtk.crm.interaction;

public enum InteractionWaiting {
    UNIVERSITY("Ждём вуз"),
    RTK("Ждём РТК");

    private final String label;

    InteractionWaiting(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
