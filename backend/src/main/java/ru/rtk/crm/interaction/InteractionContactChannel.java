package ru.rtk.crm.interaction;

public enum InteractionContactChannel {
    MEETING("встреча"),
    CALL("звонок"),
    EMAIL("письмо"),
    MESSENGER("мессенджер"),
    EVENT("мероприятие");

    private final String label;

    InteractionContactChannel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
