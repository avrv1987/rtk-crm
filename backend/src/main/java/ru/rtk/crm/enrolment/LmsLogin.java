package ru.rtk.crm.enrolment;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Правило логина в LMS (решение владельца 21.10): по умолчанию — почта в нижнем регистре. Moodle хранит логины только
// строчными, поэтому любое правило приводит логин к нижнему регистру. Одно правило на CSV слушателей и преподавателей
// и на передачу через API, иначе файл и API создали бы разные учётные записи одному человеку.
@Component
public class LmsLogin {
    public enum Rule {
        // ivanov@example.ru
        EMAIL,
        // ivanov — часть почты до «@»; совпадения у разных доменов Moodle отклонит как занятый логин.
        EMAIL_LOCAL_PART
    }

    private final Rule rule;

    public LmsLogin(@Value("${app.enrolment.lms-login:EMAIL}") Rule rule) {
        this.rule = rule;
    }

    public String of(String email) {
        if (email == null) {
            return null;
        }
        String login = email.strip().toLowerCase(Locale.ROOT);
        int at = login.indexOf('@');
        return rule == Rule.EMAIL_LOCAL_PART && at > 0 ? login.substring(0, at) : login;
    }
}
