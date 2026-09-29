package ru.rtk.crm.interaction;

import java.util.Arrays;

public enum InteractionFlag {
    WAITING_UNIVERSITY("Ждём вуз", "i.waiting_on = 'UNIVERSITY'"),
    WAITING_RTK("Ждём РТК", "i.waiting_on = 'RTK'"),
    PROBLEM("Есть проблема", openIssue("AND open_issue.kind = 'PROBLEM'")),
    RISK("Есть риск", openIssue("AND open_issue.kind = 'RISK'")),
    RISK_OR_PROBLEM("Есть риск или проблема", openIssue(""));

    private final String label;
    private final String condition;

    InteractionFlag(String label, String condition) {
        this.label = label;
        this.condition = condition;
    }

    private static String openIssue(String kind) {
        return "EXISTS (SELECT 1 FROM interaction_issues open_issue WHERE open_issue.interaction_id = i.id"
                + " AND open_issue.status = 'OPEN' " + kind + ")";
    }

    static InteractionFlag from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(flag -> flag.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new InteractionValidationException("flag", "Такой признак не поддерживается"));
    }

    public String label() {
        return label;
    }

    public String condition() {
        return condition;
    }
}
