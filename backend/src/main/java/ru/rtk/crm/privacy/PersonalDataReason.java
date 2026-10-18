package ru.rtk.crm.privacy;

public enum PersonalDataReason {
    SUBJECT_REQUEST("обращение субъекта ПДн"),
    CONSENT_WITHDRAWN("отзыв согласия"),
    PURPOSE_ACHIEVED("достигнута цель обработки"),
    UNLAWFUL_PROCESSING("выявлена неправомерная обработка"),
    REGULATOR_REQUEST("требование Роскомнадзора"),
    OTHER("другое");

    private final String label;

    PersonalDataReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
