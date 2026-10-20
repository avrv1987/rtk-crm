package ru.rtk.crm.privacy;

public enum MentionPlace {
    COMMENT("комментарий"),
    PLAN_HISTORY("следующий шаг в истории"),
    NEXT_ACTION("текущий следующий шаг"),
    TITLE("название карточки"),
    AGENT_PLAN("план записи ИИ-агента"),
    WORK_STATUS("причина паузы или ожидания"),
    IMPORT("источник или автор импортированной записи"),
    STAGE_COMMENT("комментарий к отметке этапа"),
    ISSUE("проблема или риск"),
    AGREEMENT("договор: стороны, допсоглашение, комментарий"),
    AGREEMENT_FILE("имя файла договора"),
    AGREEMENT_ACTIVITY("мероприятие плана договора"),
    ACTIVITY_LIST("перечень мероприятий"),
    HANDOVER("записка при передаче вуза"),
    PARTNER_REQUEST("запрос вузу и ответ координатора"),
    CONSENT("согласие на обработку ПДн"),
    PERSONAL_DATA_REQUEST("обращение субъекта ПДн (не обезличивается)"),
    SAVED_REPORT("название сохранённого отчёта"),
    CONTACT_HISTORY("история изменений контакта"),
    PEOPLE_DOCUMENT("документ в учёте людей и часов"),
    VENDOR_CONTACT("контакт вендора");

    private final String label;

    MentionPlace(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
