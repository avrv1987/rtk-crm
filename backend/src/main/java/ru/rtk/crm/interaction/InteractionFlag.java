package ru.rtk.crm.interaction;

import java.util.Arrays;

public enum InteractionFlag {
    WAITING_UNIVERSITY("Ждём вуз", "i.waiting_on = 'UNIVERSITY'"),
    WAITING_RTK("Ждём РТК", "i.waiting_on = 'RTK'"),
    PROBLEM("Есть проблема", "i.problem IS NOT NULL"),
    RISK("Есть риск", "i.risk_level IS NOT NULL"),
    RISK_OR_PROBLEM("Есть риск или проблема", "(i.risk_level IS NOT NULL OR i.problem IS NOT NULL)");

    private final String label;
    private final String condition;

    InteractionFlag(String label, String condition) {
        this.label = label;
        this.condition = condition;
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
