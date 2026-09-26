package ru.rtk.crm.privacy;

public enum MentionPlace {
    COMMENT("комментарий"),
    PLAN_HISTORY("следующий шаг в истории"),
    NEXT_ACTION("текущий следующий шаг"),
    TITLE("название карточки");

    private final String label;

    MentionPlace(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
