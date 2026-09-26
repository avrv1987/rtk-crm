package ru.rtk.crm.attachment;

public enum AttachmentKind {
    CONTRACT("Договор"),
    LICENSE_AGREEMENT("Лицензионное соглашение"),
    APPENDIX("Приложение"),
    ACT("Акт"),
    SIGNED_SCAN("Скан подписанного документа"),
    MATERIALS("Учебные материалы"),
    DOCUMENTATION("Документация продукта"),
    CURRICULUM("Рабочая программа"),
    QUALIFICATION("Документ о повышении квалификации"),
    OTHER("Иное");

    private final String title;

    AttachmentKind(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
