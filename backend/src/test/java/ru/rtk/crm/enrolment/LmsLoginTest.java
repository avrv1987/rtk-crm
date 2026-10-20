package ru.rtk.crm.enrolment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Решение владельца 21.10: логин в LMS — почта в нижнем регистре по умолчанию, правило задаётся настройкой
// app.enrolment.lms-login (APP_ENROLMENT_LMS_LOGIN).
class LmsLoginTest {
    @Test
    void defaultRuleIsLowercaseEmailAndLocalPartRuleIsAvailable() {
        assertThat(new LmsLogin(LmsLogin.Rule.EMAIL).of(" Anna.Pervova@Example.TEST ")).isEqualTo("anna.pervova@example.test");
        assertThat(new LmsLogin(LmsLogin.Rule.EMAIL_LOCAL_PART).of("Anna.Pervova@Example.TEST")).isEqualTo("anna.pervova");
        assertThat(new LmsLogin(LmsLogin.Rule.EMAIL).of(null)).isNull();
    }
}
