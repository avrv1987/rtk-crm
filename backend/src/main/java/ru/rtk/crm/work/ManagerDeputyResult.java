package ru.rtk.crm.work;

import java.util.List;
import java.util.UUID;

public record ManagerDeputyResult(List<OrganizationDeputy> assigned, List<Skipped> skipped) {
    public record Skipped(UUID organizationId, String organizationName, String reason) {
    }
}
