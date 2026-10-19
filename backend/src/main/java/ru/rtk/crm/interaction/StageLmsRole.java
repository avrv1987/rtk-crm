package ru.rtk.crm.interaction;

// Роль этапа для LMS: сигналы, подсказки и вехи отчётов ищут этап по роли, а не по названию. Этап без роли (null)
// с LMS не связан.
public enum StageLmsRole {
    TEACHER_TRAINING("Обучение преподавателей"),
    CLASSES("Идут занятия"),
    QUALIFICATION("Повышение квалификации");

    private final String title;

    StageLmsRole(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
