package ru.rtk.crm.enrolment;

import java.time.LocalDate;

final class LearnerTestData {
    static final LocalDate TODAY = LocalDate.of(2026, 9, 26);
    static final String VALID_SNILS = "112-233-445 95";

    private LearnerTestData() {
    }

    static LearnerProfile fullProfile() {
        return new LearnerProfile(
                "Тестова",
                "Анна",
                "Сергеевна",
                "+79000000001",
                "anna.testova@example.test",
                "11223344595",
                "0123",
                "045678",
                "Отделом тестирования № 1",
                LocalDate.of(2019, 5, 20),
                "770-001",
                Gender.FEMALE,
                LocalDate.of(2000, 2, 29),
                "Тестовая область",
                "г. Примерный",
                "ул. Синтетическая",
                "12/3",
                "45",
                "012345",
                "Анне",
                "Тестовой",
                "Сергеевне",
                Education.HIGHER_BACHELOR,
                "Инженер-тестировщик",
                "Примерный университет",
                "Тестова",
                "000123",
                "1077",
                "РН-0042",
                LocalDate.of(2022, 6, 30)
        );
    }

    static LearnerProfile requiredOnly(String lastName, String firstName, String phone, String email) {
        return new LearnerProfile(
                lastName, firstName, null, phone, email, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
    }
}
