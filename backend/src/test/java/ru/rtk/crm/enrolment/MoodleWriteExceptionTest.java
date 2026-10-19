package ru.rtk.crm.enrolment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Отказы Moodle в строке партии — русским текстом с кодом в скобках, без исходного английского сообщения.
class MoodleWriteExceptionTest {
    @Test
    void moodleRefusalsReadInRussianWithCodeInBrackets() {
        assertThat(MoodleWriteException.rejected("enrol_manual_enrol_users", "wsusercannotassign").getMessage())
                .isEqualTo("Moodle: у сервиса CRM нет права назначать роль студента на курсе, обратитесь к администратору Moodle"
                        + " (wsusercannotassign)");
        assertThat(MoodleWriteException.rejected("core_user_create_users", "invalidparameter").getMessage())
                .contains("пользователь с такой почтой или логином уже есть", "moodle/site:viewuseridentity").endsWith("(invalidparameter)");
        assertThat(MoodleWriteException.rejected("core_group_add_group_members", "invalidparameter").getMessage())
                .isEqualTo("Moodle: данные запроса не приняты, проверьте курс, группу и анкету слушателя (invalidparameter)");
        assertThat(MoodleWriteException.rejected("core_user_create_users", "usernameexists").getMessage())
                .isEqualTo("Moodle: пользователь с такой почтой или логином уже есть (usernameexists)");
        assertThat(MoodleWriteException.rejected("core_group_add_group_members", "invalidrecord").getMessage())
                .isEqualTo("Moodle: пользователь не найден (invalidrecord)");
        assertThat(MoodleWriteException.rejected("enrol_manual_enrol_users", "somethingnew").getMessage())
                .isEqualTo("Moodle: запрос отклонён, обратитесь к администратору Moodle (somethingnew)");
        assertThat(MoodleWriteException.rejected("core_user_create_users", "codingerror").getMessage())
                .isEqualTo("Moodle: сбой на стороне Moodle, обратитесь к администратору Moodle, после исправления повторите передачу"
                        + " (codingerror)");
        assertThat(MoodleWriteException.rejected("enrol_manual_enrol_users", "somethingnew").code()).isEqualTo("somethingnew");
    }
}
