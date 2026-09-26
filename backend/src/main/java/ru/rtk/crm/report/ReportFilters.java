package ru.rtk.crm.report;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.interaction.InteractionValidationException;

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
        OrganizationType organizationType
) {
    private static final int MAX_IDS = 500;
    private static final int MAX_STAGES = 100;
    private static final int MAX_STAGE_LENGTH = 200;

    public static ReportFilters none() {
        return new ReportFilters(List.of(), List.of(), false, List.of(), false, List.of(), false, List.of(), false,
                List.of(), null);
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
                organizationType
        );
    }

    private static List<UUID> ids(List<UUID> values, String field) {
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
