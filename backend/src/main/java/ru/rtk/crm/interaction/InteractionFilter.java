package ru.rtk.crm.interaction;

import java.util.Arrays;
import java.util.UUID;

// Публичен для тонкого контроллера мини-приложения MAX, который вызывает InteractionService.list без OidcUser.
public record InteractionFilter(
        UUID organizationId,
        String search,
        InteractionDue due,
        String stage,
        InteractionWorkStatus status,
        InteractionFlag flag,
        Integer licenseExpiresBy,
        UUID responsibleId,
        boolean unassigned,
        Integer minDaysOnStage,
        Integer noContactDays
) {
    static final String UNASSIGNED = "UNASSIGNED";
    private static final String ALL_STATUSES = "ALL";
    private static final int MAX_TEXT_LENGTH = 200;
    private static final int MAX_DAYS_ON_STAGE = 3650;

    public static InteractionFilter from(UUID organizationId, String search, String due, String stage) {
        return from(organizationId, search, due, stage, null, null, null, null, null);
    }

    static InteractionFilter from(UUID organizationId, String search, String due, String stage, String status, String flag) {
        return from(organizationId, search, due, stage, status, flag, null, null, null);
    }

    static InteractionFilter from(UUID organizationId, String search, String due, String stage, Integer licenseExpiresBy) {
        return from(organizationId, search, due, stage, null, null, licenseExpiresBy, null, null);
    }

    static InteractionFilter from(
            UUID organizationId,
            String search,
            String due,
            String stage,
            String status,
            String flag,
            Integer licenseExpiresBy,
            String responsible,
            String minDaysOnStage
    ) {
        return from(organizationId, search, due, stage, status, flag, licenseExpiresBy, responsible, minDaysOnStage, null);
    }

    static InteractionFilter from(
            UUID organizationId,
            String search,
            String due,
            String stage,
            String status,
            String flag,
            Integer licenseExpiresBy,
            String responsible,
            String minDaysOnStage,
            String noContactDays
    ) {
        String responsibleValue = optionalText(responsible, "responsible");
        boolean unassigned = UNASSIGNED.equals(responsibleValue);
        return new InteractionFilter(
                organizationId,
                optionalText(search, "q"),
                InteractionDue.from(due),
                optionalText(stage, "stage"),
                status(status),
                InteractionFlag.from(flag),
                ProductAgreementRules.optionalLicenseYear(licenseExpiresBy, "licenseExpiresBy"),
                responsibleValue == null || unassigned ? null : responsibleId(responsibleValue),
                unassigned,
                days(minDaysOnStage, "minDaysOnStage"),
                days(noContactDays, "noContactDays")
        );
    }

    private static InteractionWorkStatus status(String value) {
        if (value == null || value.isBlank()) {
            return InteractionWorkStatus.ACTIVE;
        }
        if (ALL_STATUSES.equals(value)) {
            return null;
        }
        return Arrays.stream(InteractionWorkStatus.values())
                .filter(status -> status.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new InteractionValidationException("status", "Такой статус взаимодействия не поддерживается"));
    }

    private static UUID responsibleId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("responsible", "Выберите ответственного из списка или «Требует назначения»");
        }
    }

    private static Integer days(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int days;
        try {
            days = Integer.parseInt(value.strip());
        } catch (NumberFormatException exception) {
            throw invalidDays(field);
        }
        if (days < 1 || days > MAX_DAYS_ON_STAGE) {
            throw invalidDays(field);
        }
        return days;
    }

    private static InteractionValidationException invalidDays(String field) {
        return new InteractionValidationException(field, "Укажите целое число дней от 1 до " + MAX_DAYS_ON_STAGE);
    }

    private static String optionalText(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new InteractionValidationException(field, "Значение длиннее " + MAX_TEXT_LENGTH + " символов");
        }
        return text;
    }
}
