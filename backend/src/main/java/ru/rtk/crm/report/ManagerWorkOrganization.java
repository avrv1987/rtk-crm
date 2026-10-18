package ru.rtk.crm.report;

import java.util.UUID;

public record ManagerWorkOrganization(UUID id, String name, UUID teamId, String teamName, UUID managerId, String managerName) {
}
