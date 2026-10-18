package ru.rtk.crm.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Запись в Moodle выключена по умолчанию и идёт отдельным токеном: у токена чтения (синхронизация обучения) нет и не
// должно быть прав создавать пользователей и зачислять на курсы.
@ConfigurationProperties("app.moodle-write")
public record MoodleWriteProperties(boolean enabled, String token, Integer studentRoleId) {
    public MoodleWriteProperties {
        studentRoleId = studentRoleId == null ? 5 : studentRoleId;
    }

    @Override
    public String toString() {
        return "MoodleWriteProperties[enabled=" + enabled + ", token=" + (token == null || token.isBlank() ? "none" : "masked")
                + ", studentRoleId=" + studentRoleId + "]";
    }
}
