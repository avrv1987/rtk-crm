package ru.rtk.crm.catalog;

public class OrganizationAssignmentAccessDeniedException extends RuntimeException {
    public OrganizationAssignmentAccessDeniedException() {
        super("The current profile cannot change the organization owner");
    }
}
