package ru.rtk.crm.catalog;

public class CatalogChangeAccessDeniedException extends RuntimeException {
    public CatalogChangeAccessDeniedException() {
        super("The current profile cannot change this catalog record");
    }
}
