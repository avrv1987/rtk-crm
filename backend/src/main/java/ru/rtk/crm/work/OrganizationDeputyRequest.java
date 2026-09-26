package ru.rtk.crm.work;

import java.time.LocalDate;
import java.util.UUID;

public record OrganizationDeputyRequest(UUID deputyProfileId, LocalDate startsOn, LocalDate endsOn) {
}
