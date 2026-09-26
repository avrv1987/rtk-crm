package ru.rtk.crm.interaction;

public enum ProductTransferKind {
    MATERIALS("материалы"),
    LICENSE("лицензия"),
    DOCUMENTATION("документация");

    private final String title;

    ProductTransferKind(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
