package ru.rtk.crm.catalog;

import java.util.UUID;

public record CatalogArchivedEvent(UUID organizationId, UUID contactId, UUID actorProfileId, String requestId) {
}
