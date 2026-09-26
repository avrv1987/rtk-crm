package ru.rtk.crm.source;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

record SiteRecord(
        String externalId,
        String type,
        OffsetDateTime updatedAt,
        OffsetDateTime createdAt,
        String status,
        String organizationExternalId,
        String organizationName,
        String programName,
        String productName,
        String contactName,
        String contactEmail,
        String contactPhone,
        String contactPosition,
        Integer applicationsCount,
        String message,
        String payload,
        String problem
) {
    static final String PARTNERSHIP_REQUEST = "partnership_request";
    static final String LEARNING_APPLICATION = "learning_application";
    static final String WITHDRAWN = "withdrawn";
    static final int MAX_APPLICATIONS_COUNT = 100_000;

    static SiteRecord parse(JsonNode item) {
        List<String> problems = new ArrayList<>();
        if (item == null || !item.isObject()) {
            return new SiteRecord(null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, "элемент items не является объектом");
        }
        JsonNode organization = item.path("organization");
        JsonNode contact = item.path("contact");
        String type = text(item, "type", 64, problems);
        ObjectNode stored = item.deepCopy();
        if (LEARNING_APPLICATION.equals(type)) {
            stored.remove("contact");
        }
        return new SiteRecord(
                text(item, "externalId", 200, problems),
                type,
                dateTime(item, "updatedAt", problems),
                dateTime(item, "createdAt", problems),
                text(item, "status", 64, problems),
                text(organization, "externalId", 200, problems),
                text(organization, "name", 300, problems),
                text(item.path("program"), "name", 200, problems),
                text(item.path("product"), "name", 200, problems),
                text(contact, "name", 200, problems),
                text(contact, "email", 320, problems),
                text(contact, "phone", 50, problems),
                text(contact, "position", 200, problems),
                applicationsCount(item, problems),
                text(item, "message", 4_000, problems),
                stored.toString(),
                problems.isEmpty() ? null : String.join("; ", problems)
        );
    }

    static SiteRecord parseStored(ObjectMapper objectMapper, String payload) {
        try {
            return parse(objectMapper.readTree(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored source record payload cannot be read", exception);
        }
    }

    static String programKey(String programName) {
        return programName == null ? null : "name:" + normalized(programName);
    }

    String organizationKey() {
        if (organizationExternalId != null) {
            return "id:" + organizationExternalId;
        }
        return organizationName == null ? null : "name:" + normalized(organizationName);
    }

    boolean storable() {
        return externalId != null && type != null && updatedAt != null;
    }

    OffsetDateTime submittedAt() {
        return createdAt == null ? updatedAt : createdAt;
    }

    SourceRepository.RecordVersion version() {
        return new SourceRepository.RecordVersion(type, externalId, updatedAt, submittedAt(), status, payload);
    }

    private static String normalized(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field, int maxLength, List<String> problems) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual() && !value.isIntegralNumber()) {
            problems.add("поле " + field + " должно быть строкой");
            return null;
        }
        String text = value.asText().trim();
        if (text.length() > maxLength) {
            problems.add("поле " + field + " длиннее " + maxLength + " символов");
            return null;
        }
        return text.isEmpty() ? null : text;
    }

    private static OffsetDateTime dateTime(JsonNode node, String field, List<String> problems) {
        String value = text(node, field, 64, problems);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            problems.add("поле " + field + " должно быть датой ISO 8601 со смещением");
            return null;
        }
    }

    private static Integer applicationsCount(JsonNode item, List<String> problems) {
        JsonNode value = item.path("applicationsCount");
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt() || !value.isIntegralNumber()
                || value.intValue() < 1 || value.intValue() > MAX_APPLICATIONS_COUNT) {
            problems.add("поле applicationsCount должно быть целым от 1 до " + MAX_APPLICATIONS_COUNT);
            return null;
        }
        return value.intValue();
    }
}
