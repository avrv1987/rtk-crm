package ru.rtk.crm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OpenApiContractTest {
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Test
    void everyOperationHasRussianSummaryAndInternalErrorResponse() throws IOException {
        Map<String, Object> paths = section(contract(), "paths");
        List<String> incomplete = new ArrayList<>();
        paths.forEach((path, value) -> section(value).forEach((method, operationValue) -> {
            if (!METHODS.contains(method)) {
                return;
            }
            Map<String, Object> operation = section(operationValue);
            Object summary = operation.get("summary");
            boolean russianSummary = summary instanceof String text && text.codePoints().anyMatch(point -> Character.UnicodeBlock.of(point) == Character.UnicodeBlock.CYRILLIC);
            if (!russianSummary || !section(operation, "responses").containsKey("500")) {
                incomplete.add(method + " " + path);
            }
        }));

        assertThat(incomplete).isEmpty();
    }

    @Test
    void apiErrorCodesIncludeReportHttpErrors() throws IOException {
        Map<String, Object> apiError = section(section(section(contract(), "components"), "schemas"), "ApiError");
        Object codes = section(section(apiError, "properties"), "code").get("enum");

        assertThat(codes).asList().contains(
                "REPORT_NOT_READY",
                "REPORT_ACCESS_CHANGED",
                "REPORT_RESULT_UNAVAILABLE",
                "REPORT_CAPACITY_EXCEEDED",
                "PAYLOAD_TOO_LARGE"
        );
    }

    private static Map<String, Object> contract() throws IOException {
        try (InputStream input = OpenApiContractTest.class.getResourceAsStream("/static/openapi.yaml")) {
            return new Yaml().load(input);
        }
    }

    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        return section(parent.get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
