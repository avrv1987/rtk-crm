package ru.rtk.crm.catalog;

import java.util.UUID;

// Почта контакта изменилась. Кабинет вуза держит по ней учётную запись координатора: письма уходят на адрес из Keycloak.
public record ContactEmailChangedEvent(UUID organizationId, UUID contactId, String email, UUID actorProfileId) {
}
