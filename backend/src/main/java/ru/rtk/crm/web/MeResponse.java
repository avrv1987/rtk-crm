package ru.rtk.crm.web;

import java.util.UUID;

import ru.rtk.crm.access.UserRole;

public record MeResponse(UUID id, UserRole role, UUID teamId, String teamName, int accessRevision, boolean enrolmentOperator) {
}
