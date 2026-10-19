package ru.rtk.crm.web;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import ru.rtk.crm.interaction.InteractionValidationException;

// Общие правила для ФИО и почты на входе: файл оплат, анкета слушателя, сотрудник, контакт вуза. Одно место, чтобы
// значение, принятое на одном входе, не блокировало потом сохранение на другом.
public final class InputRules {
    public static final String NAME_RULE = "буквы, пробел, дефис, апостроф, точка";
    public static final String LOGIN_EMAIL_RULE = "Укажите почту латиницей в виде name@example.ru";
    public static final int EMAIL_LIMIT = 254;
    // Почта, по которой входят в Keycloak и на которую уходят письма: ASCII, домен с точкой, без пустых частей. Keycloak
    // отклоняет кириллицу и угловые скобки ответом 400, а «a@b» проходит, но письмо никуда не дойдёт.
    public static final String LOGIN_EMAIL_REGEX = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
            + "@[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*\\.[A-Za-z]{2,63}";
    private static final Pattern NAME = Pattern.compile("[\\p{L} .'’\\-]+");
    private static final Pattern LOGIN_EMAIL = Pattern.compile(LOGIN_EMAIL_REGEX);

    private InputRules() {
    }

    public static boolean isPersonName(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength && NAME.matcher(value).matches();
    }

    public static boolean isLoginEmail(String value) {
        return value != null && value.length() <= EMAIL_LIMIT && LOGIN_EMAIL.matcher(value).matches();
    }

    // Окно правдоподобных дат: опечатки вроде 1900 или 9999 принимались и портили сроки, плитки и отчёты.
    // yearsAhead = 0 — дата не позже сегодняшней. Пустое значение не проверяется: обязательность — забота вызывающего.
    public static void requireDateWithin(LocalDate value, String field, LocalDate today, int yearsBack, int yearsAhead) {
        if (value == null) {
            return;
        }
        LocalDate earliest = today.minusYears(yearsBack);
        LocalDate latest = today.plusYears(yearsAhead);
        if (value.isBefore(earliest) || value.isAfter(latest)) {
            throw new InteractionValidationException(field, yearsAhead == 0
                    ? "Дата должна быть не раньше " + DATE.format(earliest) + " и не позже сегодняшней"
                    : "Дата должна быть в пределах с " + DATE.format(earliest) + " по " + DATE.format(latest));
        }
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
}
