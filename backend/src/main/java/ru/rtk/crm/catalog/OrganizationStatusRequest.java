package ru.rtk.crm.catalog;

public record OrganizationStatusRequest(OrganizationStatusAction action, Integer version, String reason) {
}
