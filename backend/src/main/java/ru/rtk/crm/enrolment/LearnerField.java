package ru.rtk.crm.enrolment;

enum LearnerField {
    LAST_NAME("Фамилия"),
    FIRST_NAME("Имя"),
    MIDDLE_NAME("Отчествопри наличии)", "Отчество"),
    PHONE("Номер телефона"),
    EMAIL("Email"),
    SNILS("СНИЛС"),
    PASSPORT_SERIES("Серия паспорта"),
    PASSPORT_NUMBER("Номер паспорта"),
    PASSPORT_ISSUED_BY("Кем выдан паспорт"),
    PASSPORT_ISSUE_DATE("Дата выдачи паспорта"),
    PASSPORT_DIVISION_CODE("Код подразделения"),
    GENDER("Пол"),
    BIRTH_DATE("Дата рождения"),
    REGION("Регион регистрации"),
    LOCALITY("Населенный пункт регистрации"),
    STREET("Улица регистрации"),
    HOUSE("Дом регистрации"),
    APARTMENT("Квартира регистрации"),
    POSTAL_CODE("Индекс регистрации"),
    FIRST_NAME_DATIVE("Имядательный падеж)", "Имя в дательном падеже"),
    LAST_NAME_DATIVE("Фамилиядательный падеж)", "Фамилия в дательном падеже"),
    MIDDLE_NAME_DATIVE("Отчестводательный падеж)", "Отчество в дательном падеже"),
    EDUCATION("Образование"),
    DIPLOMA_PROFESSION("Профессия по диплому"),
    DIPLOMA_INSTITUTION("Учебное заведение по диплому"),
    DIPLOMA_LAST_NAME("Фамилия, указанная в дипломе"),
    DIPLOMA_NUMBER("Номер диплома"),
    DIPLOMA_SERIES("Серия диплома"),
    DIPLOMA_REGISTRATION_NUMBER("Регистрационный номер диплома"),
    DIPLOMA_ISSUE_DATE("Дата выдачи диплома");

    private final String header;
    private final String label;

    LearnerField(String header) {
        this(header, header);
    }

    LearnerField(String header, String label) {
        this.header = header;
        this.label = label;
    }

    String header() {
        return header;
    }

    String label() {
        return label;
    }
}
