package ru.rtk.crm.interaction;

public enum InteractionIssueKind {
    PROBLEM("Проблема", "проблема"),
    RISK("Риск", "риск");

    private final String label;
    private final String lowerLabel;

    InteractionIssueKind(String label, String lowerLabel) {
        this.label = label;
        this.lowerLabel = lowerLabel;
    }

    public String label() {
        return label;
    }

    public String lowerLabel() {
        return lowerLabel;
    }
}
