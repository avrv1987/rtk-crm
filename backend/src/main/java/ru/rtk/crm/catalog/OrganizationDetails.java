package ru.rtk.crm.catalog;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import ru.rtk.crm.interaction.InteractionValidationException;

public record OrganizationDetails(String name, OrganizationType type, String city, String website, String inn) {
    private static final int NAME_LIMIT = 300;
    private static final int CITY_LIMIT = 200;
    private static final int WEBSITE_LIMIT = 300;

    public static OrganizationDetails from(OrganizationDetailsRequest request) {
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные организации");
        }
        String name = CatalogNames.clean(request.name());
        if (name.isEmpty() || name.length() > NAME_LIMIT) {
            throw new InteractionValidationException("name", "Название должно содержать от 1 до 300 символов");
        }
        if (request.type() == null) {
            throw new InteractionValidationException("type", "Выберите тип организации");
        }
        if (request.type() == OrganizationType.OPEN_ENROLLMENT) {
            throw new InteractionValidationException("type", "Служебный тип «Открытый набор» создаётся системой; выберите вуз, колледж или школу");
        }
        String city = optional(request.city(), "city", CITY_LIMIT, "Город или регион длиннее 200 символов");
        return new OrganizationDetails(name, request.type(), city, website(request.website()), inn(request.inn()));
    }

    public List<String> describe() {
        List<String> parts = new ArrayList<>();
        parts.add("Тип: " + typeLabel(type));
        if (city != null) {
            parts.add("Город: " + city);
        }
        if (website != null) {
            parts.add("Сайт: " + website);
        }
        if (inn != null) {
            parts.add("ИНН: " + inn);
        }
        return parts;
    }

    public List<String> changesFrom(OrganizationDetails previous) {
        List<String> changes = new ArrayList<>();
        change(changes, "Название", previous.name, name);
        change(changes, "Тип", typeLabel(previous.type), typeLabel(type));
        change(changes, "Город", previous.city, city);
        change(changes, "Сайт", previous.website, website);
        change(changes, "ИНН", previous.inn, inn);
        return changes;
    }

    public static String typeLabel(OrganizationType type) {
        return switch (type) {
            case UNIVERSITY -> "Университет";
            case COLLEGE -> "Колледж (СПО)";
            case SCHOOL -> "Школа";
            case OPEN_ENROLLMENT -> "Открытый набор (физлица)";
        };
    }

    private static void change(List<String> changes, String label, String previous, String next) {
        if (!Objects.equals(previous, next)) {
            changes.add(label + ": " + (previous == null ? "—" : "«" + previous + "»")
                    + " → " + (next == null ? "—" : "«" + next + "»"));
        }
    }

    private static String optional(String value, String field, int limit, String message) {
        String cleaned = CatalogNames.clean(value);
        if (cleaned.length() > limit) {
            throw new InteractionValidationException(field, message);
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String website(String value) {
        String cleaned = optional(value, "website", WEBSITE_LIMIT, "Адрес сайта длиннее 300 символов");
        if (cleaned == null) {
            return null;
        }
        String candidate = cleaned.matches("(?i)^https?://.*") ? cleaned : "https://" + cleaned;
        String host;
        try {
            host = new URI(candidate).getHost();
        } catch (URISyntaxException exception) {
            host = null;
        }
        if (host == null || !host.contains(".") || candidate.contains(" ")) {
            throw new InteractionValidationException("website", "Укажите адрес сайта, например https://school1.ru");
        }
        return candidate;
    }

    private static String inn(String value) {
        String cleaned = CatalogNames.clean(value).replace(" ", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        if (!cleaned.matches("\\d{10}|\\d{12}")) {
            throw new InteractionValidationException("inn", "ИНН состоит из 10 цифр для организации или 12 цифр");
        }
        return cleaned;
    }
}
