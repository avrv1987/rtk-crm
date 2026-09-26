package ru.rtk.crm.work;

public class OrganizationDeputyNotFoundException extends RuntimeException {
    public OrganizationDeputyNotFoundException() {
        super("Organization deputy period is not found");
    }
}
