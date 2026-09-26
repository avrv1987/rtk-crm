package ru.rtk.crm.catalog;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class VendorContactRules {
    public static final int NAME_LIMIT = 200;
    public static final int EMAIL_LIMIT = 320;
    public static final String PHONE_RULE = "Телефон должен содержать 10 цифр после +7 или 8";
    public static final String EMAIL_RULE = "Почта указана неверно";

    private static final Pattern PHONE_INPUT = Pattern.compile("\\+?[0-9()\\-\\s]+");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Set<String> EMAIL_CHANNELS = Set.of("почта", "email", "e-mail");
    private static final Set<String> TELEGRAM_CHANNELS = Set.of("чат в тг", "тг", "telegram", "телеграм");

    private VendorContactRules() {
    }

    public static Optional<String> phone(String value) {
        String text = CatalogNames.clean(value);
        if (!PHONE_INPUT.matcher(text).matches()) {
            return Optional.empty();
        }
        String digits = text.replaceAll("\\D", "");
        if (digits.length() == 11 && (digits.charAt(0) == '7' || digits.charAt(0) == '8')) {
            return Optional.of("+7" + digits.substring(1));
        }
        return digits.length() == 10 && !text.startsWith("+") ? Optional.of("+7" + digits) : Optional.empty();
    }

    public static boolean email(String value) {
        return value.length() <= EMAIL_LIMIT && EMAIL.matcher(value).matches();
    }

    public static String emailKey(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    public static Optional<Channels> channels(String value) {
        boolean email = false;
        boolean telegram = false;
        for (String part : Arrays.stream(value.split("[,;]")).map(CatalogNames::normalized).filter(part -> !part.isEmpty()).toList()) {
            if (EMAIL_CHANNELS.contains(part)) {
                email = true;
            } else if (TELEGRAM_CHANNELS.contains(part)) {
                telegram = true;
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(new Channels(email, telegram));
    }

    public static String channelsLabel(boolean email, boolean telegram) {
        if (email && telegram) {
            return "Почта, Telegram";
        }
        return email ? "Почта" : telegram ? "Telegram" : "";
    }

    public record Channels(boolean email, boolean telegram) {
    }
}
