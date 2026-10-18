package ru.rtk.crm.privacy;

public record ContactRestriction(Integer version, Boolean restricted, PersonalDataReason reason, String comment) {
}
