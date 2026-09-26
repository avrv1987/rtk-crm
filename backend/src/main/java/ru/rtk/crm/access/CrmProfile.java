package ru.rtk.crm.access;

import java.util.UUID;

public record CrmProfile(UUID id, UserRole role, UUID teamId, int accessRevision) {
}
