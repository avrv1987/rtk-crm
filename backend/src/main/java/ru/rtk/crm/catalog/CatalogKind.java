package ru.rtk.crm.catalog;

public enum CatalogKind {
    DIRECTIONS("directions", null, null, CatalogEntityType.DIRECTION, "ИТ-направление"),
    PROGRAMS("programs", "direction_id", "directions", CatalogEntityType.PROGRAM, "ИТ-программа"),
    VENDORS("vendors", null, null, CatalogEntityType.VENDOR, "Вендор"),
    PRODUCTS("products", "vendor_id", "vendors", CatalogEntityType.PRODUCT, "ИТ-продукт");

    private final String table;
    private final String parentColumn;
    private final String parentTable;
    private final CatalogEntityType entityType;
    private final String label;

    CatalogKind(String table, String parentColumn, String parentTable, CatalogEntityType entityType, String label) {
        this.table = table;
        this.parentColumn = parentColumn;
        this.parentTable = parentTable;
        this.entityType = entityType;
        this.label = label;
    }

    public String table() {
        return table;
    }

    public String parentColumn() {
        return parentColumn;
    }

    public String parentTable() {
        return parentTable;
    }

    public CatalogEntityType entityType() {
        return entityType;
    }

    public String label() {
        return label;
    }

    public boolean hasParent() {
        return parentColumn != null;
    }

    public static CatalogKind parse(String value) {
        for (CatalogKind kind : values()) {
            if (kind.table.equals(value)) {
                return kind;
            }
        }
        throw new InvalidOrganizationQueryException("kind", "Такого справочника нет");
    }
}
