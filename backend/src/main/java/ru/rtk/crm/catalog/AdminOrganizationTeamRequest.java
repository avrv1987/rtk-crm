package ru.rtk.crm.catalog;

import java.util.UUID;

public record AdminOrganizationTeamRequest(UUID teamId, Integer version) {
}
