package ru.rtk.crm.interaction;

import java.util.Arrays;
import java.util.UUID;

public record InteractionIssueFilter(
        InteractionIssueKind kind,
        InteractionRiskLevel riskLevel,
        UUID responsibleId,
        UUID organizationId,
        boolean overdue,
        InteractionIssueStatus status
) {
    private static final String ALL_STATUSES = "ALL";

    public static InteractionIssueFilter from(
            String kind,
            String riskLevel,
            String responsible,
            String organizationId,
            boolean overdue,
            String status
    ) {
        return new InteractionIssueFilter(
                value(InteractionIssueKind.values(), kind, "kind"),
                value(InteractionRiskLevel.values(), riskLevel, "riskLevel"),
                uuid(responsible, "responsible"),
                uuid(organizationId, "organizationId"),
                overdue,
                status == null || status.isBlank()
                        ? InteractionIssueStatus.OPEN
                        : ALL_STATUSES.equals(status) ? null : value(InteractionIssueStatus.values(), status, "status")
        );
    }

    private static <T extends Enum<T>> T value(T[] values, String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(values)
                .filter(candidate -> candidate.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new InteractionValidationException(field, "Такое значение не поддерживается"));
    }

    private static UUID uuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException(field, "Некорректный идентификатор");
        }
    }
}
