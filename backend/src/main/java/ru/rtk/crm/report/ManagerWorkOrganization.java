package ru.rtk.crm.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import ru.rtk.crm.catalog.OrganizationType;

public record ManagerWorkOrganization(
        UUID id,
        String name,
        UUID teamId,
        String teamName,
        UUID managerId,
        String managerName,
        OffsetDateTime lastActionAt,
        OrganizationType type
) {
    public ManagerWorkOrganization(UUID id, String name, UUID teamId, String teamName, UUID managerId, String managerName,
                                   OffsetDateTime lastActionAt) {
        this(id, name, teamId, teamName, managerId, managerName, lastActionAt, null);
    }

    // Вузы без идущего взаимодействия входят в числа «Контроля» и в итоги его выгрузки, только когда не заданы период и
    // отборы по направлению, программе, продукту, виду взаимодействия и этапу: у такого вуза нет ни этапа, ни продукта, а период
    // показывает изменения, а не состояние. Тогда число вузов совпадает с разделом «Вузы». Тип организации у вуза есть, поэтому
    // отбор по типу сужает список, как в отчётах.
    public static boolean counted(ReportFilters filters, LocalDate from, LocalDate to) {
        return from == null && to == null && filters.directionIds().isEmpty() && !filters.includeNoDirection()
                && filters.programIds().isEmpty() && !filters.includeNoProgram() && filters.productIds().isEmpty()
                && !filters.includeNoProduct() && filters.stages().isEmpty() && filters.change() == null
                && filters.kindIds().isEmpty();
    }

    public boolean selected(ReportFilters filters) {
        if (!filters.organizationIds().isEmpty() && !filters.organizationIds().contains(id)) {
            return false;
        }
        if (filters.organizationType() != null && filters.organizationType() != type) {
            return false;
        }
        if (filters.managerIds().isEmpty() && !filters.includeNoManager()) {
            return true;
        }
        return managerId == null ? filters.includeNoManager() : filters.managerIds().contains(managerId);
    }
}
