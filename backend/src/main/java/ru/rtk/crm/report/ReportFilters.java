package ru.rtk.crm.report;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.interaction.InteractionFlag;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.interaction.InteractionWorkStatus;

public record ReportFilters(
        List<UUID> organizationIds,
        List<UUID> directionIds,
        boolean includeNoDirection,
        List<UUID> programIds,
        boolean includeNoProgram,
        List<UUID> productIds,
        boolean includeNoProduct,
        List<UUID> managerIds,
        boolean includeNoManager,
        List<String> stages,
        OrganizationType organizationType,
        List<InteractionWorkStatus> workStatuses,
        List<InteractionFlag> flags,
        ReportAgreementFilters agreement,
        List<ReportEventType> eventTypes,
        Integer minDaysOnStage
) {
    private static final int MAX_DAYS_ON_STAGE = 3650;
    private static final int MAX_IDS = 500;
    private static final int MAX_STAGES = 100;
    private static final int MAX_STAGE_LENGTH = 200;

    public ReportFilters(
            List<UUID> organizationIds,
            List<UUID> directionIds,
            boolean includeNoDirection,
            List<UUID> programIds,
            boolean includeNoProgram,
            List<UUID> productIds,
            boolean includeNoProduct,
            List<UUID> managerIds,
            boolean includeNoManager,
            List<String> stages,
            OrganizationType organizationType
    ) {
        this(organizationIds, directionIds, includeNoDirection, programIds, includeNoProgram, productIds, includeNoProduct,
                managerIds, includeNoManager, stages, organizationType, List.of(), List.of(), ReportAgreementFilters.none(),
                List.of(), null);
    }

    public static ReportFilters none() {
        return new ReportFilters(List.of(), List.of(), false, List.of(), false, List.of(), false, List.of(), false,
                List.of(), null, List.of(), List.of(), ReportAgreementFilters.none(), List.of(), null);
    }

    public ReportFilters normalized() {
        return new ReportFilters(
                ids(organizationIds, "filters.organizationIds"),
                ids(directionIds, "filters.directionIds"),
                includeNoDirection,
                ids(programIds, "filters.programIds"),
                includeNoProgram,
                ids(productIds, "filters.productIds"),
                includeNoProduct,
                ids(managerIds, "filters.managerIds"),
                includeNoManager,
                stageNames(),
                organizationType,
                workStatuses == null ? List.of() : List.copyOf(new LinkedHashSet<>(workStatuses)),
                flags == null ? List.of() : List.copyOf(new LinkedHashSet<>(flags)),
                agreement == null ? ReportAgreementFilters.none() : agreement.normalized(),
                eventTypeList(),
                daysOnStage()
        );
    }

    private Integer daysOnStage() {
        if (minDaysOnStage != null && (minDaysOnStage < 1 || minDaysOnStage > MAX_DAYS_ON_STAGE)) {
            throw new InteractionValidationException(
                    "filters.minDaysOnStage", "Укажите целое число дней от 1 до " + MAX_DAYS_ON_STAGE
            );
        }
        return minDaysOnStage;
    }

    static List<UUID> ids(List<UUID> values, String field) {
        if (values == null) {
            return List.of();
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new InteractionValidationException(field, "Список не должен содержать пустых значений");
        }
        List<UUID> unique = List.copyOf(new LinkedHashSet<>(values));
        if (unique.size() > MAX_IDS) {
            throw new InteractionValidationException(field, "Можно выбрать не более " + MAX_IDS + " значений");
        }
        return unique;
    }

    private List<ReportEventType> eventTypeList() {
        if (eventTypes == null) {
            return List.of();
        }
        if (eventTypes.stream().anyMatch(Objects::isNull)) {
            throw new InteractionValidationException("filters.eventTypes", "Список не должен содержать пустых значений");
        }
        return List.copyOf(new LinkedHashSet<>(eventTypes));
    }

    private List<String> stageNames() {
        if (stages == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String stage : stages) {
            if (stage == null || stage.isBlank()) {
                throw new InteractionValidationException("filters.stages", "Название этапа не должно быть пустым");
            }
            String name = stage.trim();
            if (name.length() > MAX_STAGE_LENGTH) {
                throw new InteractionValidationException("filters.stages", "Название этапа слишком длинное");
            }
            unique.add(name);
        }
        if (unique.size() > MAX_STAGES) {
            throw new InteractionValidationException("filters.stages", "Можно выбрать не более " + MAX_STAGES + " этапов");
        }
        return new ArrayList<>(unique);
    }
}
