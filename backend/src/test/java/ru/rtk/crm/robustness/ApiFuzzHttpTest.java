package ru.rtk.crm.robustness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponentsBuilder;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.attachment.AttachmentScanOutcome;
import ru.rtk.crm.attachment.AttachmentScanner;

// Фаззер API по openapi.yaml: каждая операция получает мусорные тела, параметры и файлы от роли с доступом.
// Ожидание — ответ 4xx с кодом из ApiError.code и без стека; 500 — находка. Схема — настоящие миграции на H2,
// чтобы длины столбцов и CHECK срабатывали как на сервере. Внешние системы не настроены: их ответ 503 с кодом
// из перечня допустим, это «зависимость недоступна», а не падение.
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.session.jdbc.initialize-schema=never",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-api-fuzz-test",
        "app.reports.storage-root=${java.io.tmpdir}/rtk-crm-api-fuzz-reports",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.api-rate-limit.per-minute=0",
        "app.api-rate-limit.anonymous-per-minute=0",
        "app.modules.enrolment.enabled=true",
        "app.modules.partner.enabled=true",
        "app.modules.mail.enabled=true",
        "app.modules.max.enabled=true",
        "app.max.jobs-enabled=false",
        "app.enrolment.active-key-version=v1",
        "app.enrolment.keys.v1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.enrolment.fingerprint-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
// Журнал не глушится свойством logging.level: LoggingSystem общий для JVM, и на CI тесты журнала,
// идущие после этого класса, теряли свои записи.
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("oidc")
class ApiFuzzHttpTest {
    private static final String DATABASE = "jdbc:h2:mem:api-fuzz;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final String LONG = "Я".repeat(100_000);
    // Пути входа OIDC — редиректы на Keycloak; вебхук MAX проверяется своим секретом и в тестах модуля.
    private static final Set<String> SKIPPED = Set.of("/api/auth/login", "/api/auth/callback/{registrationId}", "/api/max/webhook");

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000fa01");
    private static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-00000000fa02");
    private static final UUID KAM = UUID.fromString("00000000-0000-0000-0000-00000000fa03");
    private static final UUID MANAGEMENT = UUID.fromString("00000000-0000-0000-0000-00000000fa04");
    private static final UUID OPERATOR = UUID.fromString("00000000-0000-0000-0000-00000000fa05");
    private static final UUID PARTNER = UUID.fromString("00000000-0000-0000-0000-00000000fa06");
    private static final UUID FOREIGN_KAM = UUID.fromString("00000000-0000-0000-0000-00000000fa07");
    private static final UUID FOREIGN_TEAM = UUID.fromString("00000000-0000-0000-0000-00000000fb02");
    private static final UUID CONTACT = UUID.fromString("00000000-0000-0000-0000-00000000fc01");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AttachmentScanner attachmentScanner;

    private Map<String, Object> spec;
    private Set<String> errorCodes;
    private final Map<String, UUID> ids = new LinkedHashMap<>();
    private final Map<String, String> findings = new LinkedHashMap<>();
    private final Set<String> h2Only = new LinkedHashSet<>();
    private int requests;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws IOException {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL(DATABASE);
        dataSource.setUser("sa");
        JdbcTemplate schema = new JdbcTemplate(dataSource);
        Integer tables = schema.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ORGANIZATIONS'", Integer.class);
        if (tables == null || tables == 0) {
            H2Schema.apply(schema);
        }
        registry.add("spring.datasource.url", () -> DATABASE);
    }

    @Test
    void garbageInputNeverGivesServerError() throws Exception {
        when(attachmentScanner.scan(any(), anyLong())).thenReturn(AttachmentScanOutcome.CLEAN);
        loadSpec();
        seed();
        jsonLimitsAndDatesGetClearCodes();
        List<Operation> operations = operations();
        long started = System.nanoTime();
        for (Operation operation : operations) {
            fuzz(operation);
        }
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        System.out.println("api-fuzz: " + operations.size() + " operations, " + requests + " requests, " + seconds + " s");
        if (!h2Only.isEmpty()) {
            System.out.println("api-fuzz: SQL, which H2 cannot run (PostgreSQL-only, not a finding):");
            h2Only.forEach(line -> System.out.println("  " + line));
        }
        assertThat(operations).hasSizeGreaterThan(250);
        assertThat(findings).as("Операции, ответившие 500 или ответом без кода ApiError:\n"
                + String.join("\n", findings.values())).isEmpty();
    }

    // Точечные проверки пределов разбора JSON и дат — до перебора, пока данные посева не тронуты.
    private void jsonLimitsAndDatesGetClearCodes() throws Exception {
        String report = "{\"kind\":\"PORTFOLIO\",\"filters\":{},\"columns\":[]";
        expect(report + ",\"note\":\"" + "x".repeat(100_000) + "\"}", 200, null);
        expect(report + ",\"note\":\"" + "x".repeat(1_000_001) + "\"}", 413, "PAYLOAD_TOO_LARGE");
        expect(report + ",\"note\":" + "[".repeat(60) + "]".repeat(60) + "}", 413, "PAYLOAD_TOO_LARGE");
        expect(report + ",\"note\":" + "9".repeat(101) + "}", 413, "PAYLOAD_TOO_LARGE");
        expect(report + ",\"from\":\"+10000-01-01\"}", 400, "VALIDATION_ERROR");
        expect(report + ",\"from\":\"0000-12-31\"}", 400, "VALIDATION_ERROR");
        expect(report + ",\"from\":\"9999-12-31\",\"to\":\"9999-12-31\"}", 200, null);
        expect("{\"kind\":\"PORTFOLIO\",\"filters\":{\"flags\":[null]},\"columns\":[]}", 400, "VALIDATION_ERROR");
        MockHttpServletResponse farDate = mockMvc.perform(MockMvcRequestBuilders.get("/api/work/control")
                .param("to", "+999999999-12-31").with(login(LEADER, UserRole.LEADER))).andReturn().getResponse();
        assertThat(farDate.getStatus()).isEqualTo(400);
        assertThat(farDate.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":\"VALIDATION_ERROR\"");
    }

    private void expect(String body, int status, String code) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post("/api/reports/preview")
                .with(login(LEADER, UserRole.LEADER)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(abbreviate(body)).isEqualTo(status);
        if (code != null) {
            assertThat(objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("code").asText()).isEqualTo(code);
        }
    }

    // ---------- данные ----------

    private void seed() throws Exception {
        UUID team = jdbc.queryForObject("SELECT id FROM teams ORDER BY created_at LIMIT 1", UUID.class);
        UUID organization = jdbc.queryForObject("SELECT id FROM organizations LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO teams (id, name) VALUES (?, 'Чужая команда')", FOREIGN_TEAM);
        jdbc.update("UPDATE organizations SET team_id = ? WHERE id = ?", team, organization);
        profile(ADMIN, UserRole.ADMIN, null, false);
        profile(LEADER, UserRole.LEADER, team, false);
        profile(KAM, UserRole.USER, team, false);
        profile(MANAGEMENT, UserRole.MANAGEMENT, null, false);
        profile(OPERATOR, UserRole.USER, team, true);
        profile(FOREIGN_KAM, UserRole.USER, FOREIGN_TEAM, false);
        jdbc.update("UPDATE organizations SET owner_manager_id = ? WHERE id = ?", KAM, organization);
        jdbc.update("INSERT INTO contacts (id, organization_id, name, email, created_by) VALUES (?, ?, 'Иван Петров', 'ivan@example.test', ?)",
                CONTACT, organization, KAM);
        jdbc.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, partner_organization_id, partner_contact_id)
                VALUES (?, ?, ?, 'Вуз', 'PARTNER', NULL, ?, ?)""", PARTNER, ISSUER, PARTNER.toString(), organization, CONTACT);
        ids.put("team", team);
        ids.put("organization", organization);
        ids.put("contact", CONTACT);
        ids.put("profile", KAM);
        ids.put("template", jdbc.queryForObject("SELECT id FROM workflow_templates LIMIT 1", UUID.class));
        ids.put("kind", jdbc.queryForObject("SELECT id FROM interaction_kinds LIMIT 1", UUID.class));
        MockHttpServletResponse created = mockMvc.perform(MockMvcRequestBuilders.post("/api/interactions")
                .with(login(KAM, UserRole.USER)).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"organizationId\":\"" + organization + "\",\"title\":\"Фаззер\",\"contactIds\":[]}")).andReturn().getResponse();
        if (created.getStatus() == 201) {
            JsonNode body = objectMapper.readTree(created.getContentAsString(StandardCharsets.UTF_8));
            ids.put("interaction", UUID.fromString(body.path("id").asText()));
            if (body.hasNonNull("currentStageId")) {
                ids.put("stage", UUID.fromString(body.path("currentStageId").asText()));
            }
        } else {
            System.out.println("api-fuzz: seed interaction " + created.getStatus() + " " + created.getContentAsString(StandardCharsets.UTF_8));
        }
    }

    private void profile(UUID id, UserRole role, UUID team, boolean operator) {
        jdbc.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, enrolment_operator, login)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, ISSUER, id.toString(), role + " " + id.toString().substring(32), role.name(),
                team, operator, "fuzz-" + id.toString().substring(32));
    }

    private static OidcLoginRequestPostProcessor login(UUID profile, UserRole role) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject(profile.toString())
                .claim("realm_access", Map.of("roles", List.of(role.keycloakRole()))));
    }

    private UUID idFor(String hint) {
        String name = hint.toLowerCase();
        if (name.contains("organization") || name.contains("universit")) {
            return ids.get("organization");
        }
        if (name.contains("contact")) {
            return ids.get("contact");
        }
        if (name.contains("interaction") && !name.contains("kind")) {
            return ids.getOrDefault("interaction", UUID.randomUUID());
        }
        if (name.contains("stage")) {
            return ids.getOrDefault("stage", UUID.randomUUID());
        }
        if (name.contains("team")) {
            return ids.get("team");
        }
        if (name.contains("template")) {
            return ids.get("template");
        }
        if (name.contains("kind")) {
            return ids.get("kind");
        }
        if (name.contains("profile") || name.contains("manager") || name.contains("owner") || name.contains("deputy")
                || name.contains("crm-profiles") || name.contains("user")) {
            return KAM;
        }
        return UUID.randomUUID();
    }

    // ---------- спецификация ----------

    @SuppressWarnings("unchecked")
    private void loadSpec() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(20 * 1024 * 1024);
        try (InputStream input = getClass().getResourceAsStream("/static/openapi.yaml")) {
            spec = new Yaml(options).load(input);
        }
        Map<String, Object> apiError = (Map<String, Object>) resolve(Map.of("$ref", "#/components/schemas/ApiError"));
        Map<String, Object> code = (Map<String, Object>) ((Map<String, Object>) apiError.get("properties")).get("code");
        errorCodes = Set.copyOf((Collection<String>) code.get("enum"));
    }

    record Operation(String method, String path, List<Map<String, Object>> parameters, String contentType, Map<String, Object> body) {
        boolean destructive() {
            return method.equals("DELETE") || path.matches(".*(anonymization|archive|deletion|retention|logout|cancel|reject).*");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Operation> operations() {
        List<Operation> safe = new ArrayList<>();
        List<Operation> destructive = new ArrayList<>();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        paths.forEach((path, item) -> {
            if (SKIPPED.contains(path)) {
                return;
            }
            Map<String, Object> pathItem = (Map<String, Object>) item;
            List<Object> shared = (List<Object>) pathItem.getOrDefault("parameters", List.of());
            pathItem.forEach((method, value) -> {
                if (!Set.of("get", "post", "put", "patch", "delete").contains(method)) {
                    return;
                }
                Map<String, Object> operation = (Map<String, Object>) value;
                List<Map<String, Object>> parameters = new ArrayList<>();
                for (Object parameter : concat(shared, (List<Object>) operation.getOrDefault("parameters", List.of()))) {
                    parameters.add((Map<String, Object>) resolve(parameter));
                }
                String contentType = null;
                Map<String, Object> body = null;
                if (operation.get("requestBody") instanceof Map<?, ?> requestBody) {
                    Map<String, Object> content = (Map<String, Object>) ((Map<String, Object>) resolve(requestBody)).get("content");
                    contentType = content.containsKey("application/json") ? "application/json" : content.keySet().iterator().next();
                    Object schema = ((Map<String, Object>) content.get(contentType)).get("schema");
                    body = schema == null ? Map.of() : (Map<String, Object>) resolve(schema);
                }
                Operation parsed = new Operation(method.toUpperCase(), path, parameters, contentType, body);
                (parsed.destructive() ? destructive : safe).add(parsed);
            });
        });
        safe.addAll(destructive);
        return safe;
    }

    private static List<Object> concat(List<Object> first, List<Object> second) {
        List<Object> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    @SuppressWarnings("unchecked")
    private Object resolve(Object node) {
        Object current = node;
        while (current instanceof Map<?, ?> map && map.get("$ref") instanceof String ref) {
            Object target = spec;
            for (String part : ref.substring(2).split("/")) {
                target = ((Map<String, Object>) target).get(part);
            }
            current = target;
        }
        return current;
    }

    // Схема с объединёнными allOf и выбранной ненулевой ветвью oneOf/anyOf.
    @SuppressWarnings("unchecked")
    private Map<String, Object> schema(Object node) {
        Map<String, Object> schema = (Map<String, Object>) resolve(node);
        if (schema == null) {
            return Map.of();
        }
        if (schema.get("allOf") instanceof List<?> parts) {
            Map<String, Object> merged = new LinkedHashMap<>(schema);
            Map<String, Object> properties = new LinkedHashMap<>();
            List<Object> required = new ArrayList<>();
            for (Object part : parts) {
                Map<String, Object> resolved = schema(part);
                properties.putAll((Map<String, Object>) resolved.getOrDefault("properties", Map.of()));
                required.addAll((List<Object>) resolved.getOrDefault("required", List.of()));
            }
            merged.remove("allOf");
            merged.put("type", "object");
            merged.put("properties", properties);
            merged.put("required", required);
            return merged;
        }
        for (String key : List.of("oneOf", "anyOf")) {
            if (schema.get(key) instanceof List<?> options) {
                for (Object option : options) {
                    Map<String, Object> resolved = schema(option);
                    if (!"null".equals(resolved.get("type"))) {
                        return resolved;
                    }
                }
            }
        }
        return schema;
    }

    private static String type(Map<String, Object> schema) {
        Object type = schema.get("type");
        if (type instanceof List<?> types) {
            return types.stream().map(String::valueOf).filter(value -> !value.equals("null")).findFirst().orElse("string");
        }
        if (type == null) {
            return schema.containsKey("properties") ? "object" : schema.containsKey("items") ? "array" : "string";
        }
        return String.valueOf(type);
    }

    // ---------- корректный образец ----------

    @SuppressWarnings("unchecked")
    private Object sample(String name, Object node, int depth) {
        Map<String, Object> schema = schema(node);
        if (schema.get("enum") instanceof List<?> values && !values.isEmpty()) {
            return values.stream().filter(value -> value != null).findFirst().orElse(null);
        }
        if (schema.containsKey("const")) {
            return schema.get("const");
        }
        String format = String.valueOf(schema.get("format"));
        return switch (type(schema)) {
            case "object" -> {
                Map<String, Object> value = new LinkedHashMap<>();
                if (depth < 6) {
                    List<Object> required = (List<Object>) schema.getOrDefault("required", List.of());
                    ((Map<String, Object>) schema.getOrDefault("properties", Map.of())).forEach((property, child) -> {
                        // Образец — только обязательные поля: необязательные по одному добавляют мутации, иначе
                        // случайное сочетание необязательных полей отсекается проверкой раньше, чем дойдёт до базы.
                        if (required.contains(property)) {
                            value.put(property, sample(property, child, depth + 1));
                        }
                    });
                }
                yield value;
            }
            case "array" -> {
                List<Object> value = new ArrayList<>();
                if (depth < 6 && schema.get("items") != null) {
                    value.add(sample(name, schema.get("items"), depth + 1));
                }
                yield value;
            }
            case "integer", "number" -> name.toLowerCase().contains("version") ? 0
                    : schema.get("minimum") instanceof Number minimum ? minimum : 1;
            case "boolean" -> false;
            default -> switch (format) {
                case "uuid" -> idFor(name).toString();
                case "date" -> LocalDate.now().toString();
                case "date-time" -> "2026-10-19T10:00:00Z";
                case "email" -> "fuzz@example.test";
                case "uri" -> "https://example.test/";
                case "binary" -> "x";
                default -> name.toLowerCase().contains("email") ? "fuzz@example.test" : "Фаззер";
            };
        };
    }

    // ---------- перебор ----------

    record Variant(String name, UUID profile, UserRole role, boolean csrf, Function<Operation, MockHttpServletRequestBuilder> request) {
    }

    private void fuzz(Operation operation) throws Exception {
        UUID profile;
        UserRole role;
        if (operation.path().startsWith("/api/admin") || operation.path().startsWith("/api/imports")
                || operation.path().contains("/admin/")) {
            profile = ADMIN;
            role = UserRole.ADMIN;
        } else if (operation.path().startsWith("/api/enrolment")) {
            profile = OPERATOR;
            role = UserRole.USER;
        } else if (operation.path().startsWith("/api/partner/")) {
            profile = PARTNER;
            role = UserRole.PARTNER;
        } else {
            profile = LEADER;
            role = UserRole.LEADER;
        }
        Map<String, String> pathValues = pathValues(operation, null);
        Map<String, String> query = validQuery(operation);
        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant("valid", profile, role, true, op -> build(op, pathValues, query, validBody(op), op.contentType())));
        variants.add(new Variant("valid as KAM", KAM, UserRole.USER, true, op -> build(op, pathValues, query, validBody(op), op.contentType())));
        variants.add(new Variant("valid as foreign KAM", FOREIGN_KAM, UserRole.USER, true,
                op -> build(op, pathValues, query, validBody(op), op.contentType())));
        variants.add(new Variant("valid as management", MANAGEMENT, UserRole.MANAGEMENT, true,
                op -> build(op, pathValues, query, validBody(op), op.contentType())));
        if (!operation.method().equals("GET")) {
            variants.add(new Variant("no CSRF", profile, role, false, op -> build(op, pathValues, query, validBody(op), op.contentType())));
        }
        for (Map<String, Object> parameter : operation.parameters()) {
            if (!"path".equals(parameter.get("in"))) {
                continue;
            }
            String name = String.valueOf(parameter.get("name"));
            for (String bad : List.of(UUID.randomUUID().toString(), "not-a-uuid", "-1", "99999999999999999999", LONG.substring(0, 2000))) {
                Map<String, String> changed = pathValues(operation, Map.of(name, bad));
                variants.add(new Variant("path " + name + "=" + abbreviate(bad), profile, role, true,
                        op -> build(op, changed, query, validBody(op), op.contentType())));
            }
        }
        for (Map<String, Object> parameter : operation.parameters()) {
            if (!"query".equals(parameter.get("in"))) {
                continue;
            }
            String name = String.valueOf(parameter.get("name"));
            for (String bad : badScalars(schema(parameter.get("schema")))) {
                Map<String, String> changed = new LinkedHashMap<>(query);
                changed.put(name, bad);
                variants.add(new Variant("query " + name + "=" + abbreviate(bad), profile, role, true,
                        op -> build(op, pathValues, changed, validBody(op), op.contentType())));
            }
        }
        if (hasParameter(operation, "from") && hasParameter(operation, "to")) {
            Map<String, String> reversed = new LinkedHashMap<>(query);
            reversed.put("from", "2026-12-31");
            reversed.put("to", "2020-01-01");
            variants.add(new Variant("query to before from", profile, role, true,
                    op -> build(op, pathValues, reversed, validBody(op), op.contentType())));
        }
        if ("application/json".equals(operation.contentType())) {
            jsonVariants(operation, profile, role, pathValues, query, variants);
        } else if (operation.contentType() != null && operation.contentType().startsWith("multipart/")) {
            multipartVariants(operation, profile, role, pathValues, query, variants);
        }
        for (Variant variant : variants) {
            perform(operation, variant);
        }
    }

    private boolean hasParameter(Operation operation, String name) {
        return operation.parameters().stream().anyMatch(parameter -> "query".equals(parameter.get("in")) && name.equals(parameter.get("name")));
    }

    private Map<String, String> pathValues(Operation operation, Map<String, String> overrides) {
        Map<String, String> values = new LinkedHashMap<>();
        String[] segments = operation.path().split("/");
        for (int i = 0; i < segments.length; i++) {
            if (!segments[i].startsWith("{")) {
                continue;
            }
            String name = segments[i].substring(1, segments[i].length() - 1);
            Map<String, Object> schema = operation.parameters().stream()
                    .filter(parameter -> "path".equals(parameter.get("in")) && name.equals(parameter.get("name")))
                    .findFirst().map(parameter -> schema(parameter.get("schema"))).orElse(Map.of());
            Object sample = sample(name.equals("id") && i > 0 ? segments[i - 1] : name, schema, 0);
            values.put(name, String.valueOf(sample));
        }
        if (overrides != null) {
            values.putAll(overrides);
        }
        return values;
    }

    private Map<String, String> validQuery(Operation operation) {
        Map<String, String> query = new LinkedHashMap<>();
        for (Map<String, Object> parameter : operation.parameters()) {
            if ("query".equals(parameter.get("in")) && Boolean.TRUE.equals(parameter.get("required"))) {
                String name = String.valueOf(parameter.get("name"));
                Object sample = sample(name, parameter.get("schema"), 0);
                query.put(name, sample instanceof List<?> list ? list.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("")
                        : String.valueOf(sample));
            }
        }
        return query;
    }

    private static List<String> badScalars(Map<String, Object> schema) {
        String type = type(schema);
        String format = String.valueOf(schema.get("format"));
        List<String> values = new ArrayList<>(List.of("", LONG));
        switch (type) {
            case "integer", "number" -> values.addAll(List.of("abc", "-1", "-2147483649", "2147483648", "99999999999999999999", "1e400", "NaN"));
            case "boolean" -> values.add("maybe");
            case "array" -> values.addAll(List.of("not-a-uuid,,", String.join(",", java.util.Collections.nCopies(2000, UUID.randomUUID().toString()))));
            default -> {
                switch (format) {
                    case "uuid" -> values.add("not-a-uuid");
                    case "date" -> values.addAll(List.of("2026-02-30", "+999999999-12-31", "1000000000-01-01", "+1000000000-01-01", "-999999999-01-01", "0000-01-01", "19.10.2026"));
                    case "date-time" -> values.addAll(List.of("2026-13-01T00:00:00Z", "+1000000000-01-01T00:00:00Z", "yesterday"));
                    default -> values.add("unknownField,desc");
                }
                if (schema.containsKey("enum")) {
                    values.add("NOT_A_VALUE");
                }
            }
        }
        return values;
    }

    private Object validBody(Operation operation) {
        if (operation.body() == null || !"application/json".equals(operation.contentType())) {
            return null;
        }
        return sample("body", operation.body(), 0);
    }

    @SuppressWarnings("unchecked")
    private void jsonVariants(Operation operation, UUID profile, UserRole role, Map<String, String> pathValues, Map<String, String> query,
                              List<Variant> variants) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("empty body", "");
        raw.put("broken JSON", "{\"a\":");
        raw.put("JSON null", "null");
        raw.put("empty object", "{}");
        raw.put("empty array", "[]");
        raw.put("JSON string", "\"text\"");
        raw.put("nesting 100", "{\"a\":".repeat(100) + "1" + "}".repeat(100));
        raw.put("array nesting 100", "[".repeat(100) + "]".repeat(100));
        raw.put("huge number", "{\"version\":" + "9".repeat(2000) + "}");
        raw.forEach((name, content) -> variants.add(new Variant(name, profile, role, true, op -> build(op, pathValues, query, new RawBody(content), "application/json"))));
        variants.add(new Variant("wrong content type", profile, role, true, op -> build(op, pathValues, query, validBody(op), "text/plain")));
        Object valid = validBody(operation);
        if (!(valid instanceof Map<?, ?> map)) {
            return;
        }
        Map<String, Object> skeleton = (Map<String, Object>) map;
        Map<String, Object> extra = new LinkedHashMap<>(skeleton);
        extra.put("unexpectedField", Map.of("x", List.of(1, 2)));
        variants.add(new Variant("extra fields", profile, role, true, op -> build(op, pathValues, query, extra, "application/json")));
        Map<String, Object> allWrong = new LinkedHashMap<>();
        skeleton.forEach((key, value) -> allWrong.put(key, wrongType(value)));
        variants.add(new Variant("all fields wrong type", profile, role, true, op -> build(op, pathValues, query, allWrong, "application/json")));
        if (skeleton.containsKey("from") && skeleton.containsKey("to")) {
            Map<String, Object> reversed = new LinkedHashMap<>(skeleton);
            reversed.put("from", "2026-12-31");
            reversed.put("to", "2020-01-01");
            variants.add(new Variant("body to before from", profile, role, true, op -> build(op, pathValues, query, reversed, "application/json")));
        }
        int budget = 150;
        for (Mutation mutation : mutations(operation.body(), skeleton, "", 0)) {
            if (budget-- == 0) {
                break;
            }
            variants.add(new Variant("body " + mutation.name(), profile, role, true,
                    op -> build(op, pathValues, query, mutation.body(), "application/json")));
        }
    }

    record Mutation(String name, Object body) {
    }

    // Порча одного поля при остальных корректных: так ввод доходит до сервиса и базы, а не отсекается первой ошибкой.
    @SuppressWarnings("unchecked")
    private List<Mutation> mutations(Map<String, Object> schemaNode, Map<String, Object> skeleton, String prefix, int depth) {
        List<Mutation> result = new ArrayList<>();
        Map<String, Object> schema = schema(schemaNode);
        Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            String key = property.getKey();
            Map<String, Object> child = schema(property.getValue());
            String path = prefix + key;
            List<Object> bad = new ArrayList<>();
            bad.add(null);
            Object current = skeleton.containsKey(key) ? skeleton.get(key) : sample(key, child, depth + 1);
            bad.add(wrongType(current));
            String format = String.valueOf(child.get("format"));
            switch (type(child)) {
                case "integer", "number" -> bad.addAll(List.of(-1, 2147483648L, -2147483649L, new java.math.BigInteger("99999999999999999999999"), 1e300));
                case "array" -> {
                    Object item = current instanceof List<?> list && !list.isEmpty() ? list.getFirst() : "x";
                    bad.add(java.util.Collections.nCopies(10_000, item));
                    bad.add(List.of("not-a-uuid"));
                    bad.add(java.util.Arrays.asList((Object) null));
                }
                case "object" -> {
                    if (depth < 2 && current instanceof Map<?, ?> nested) {
                        for (Mutation inner : mutations(child, (Map<String, Object>) nested, path + ".", depth + 1)) {
                            Map<String, Object> copy = new LinkedHashMap<>(skeleton);
                            copy.put(key, inner.body());
                            result.add(new Mutation(inner.name(), copy));
                        }
                    }
                }
                case "boolean" -> bad.add("true");
                default -> {
                    bad.add(LONG);
                    if (child.containsKey("enum")) {
                        bad.add("NOT_A_VALUE");
                    }
                    switch (format) {
                        case "uuid" -> bad.addAll(List.of("not-a-uuid", UUID.randomUUID().toString()));
                        case "date" -> bad.addAll(List.of("2026-02-30", "+999999999-12-31", "1000000000-01-01", "+1000000000-01-01", "-999999999-01-01"));
                        case "date-time" -> bad.addAll(List.of("2026-13-01T00:00:00Z", "+1000000000-01-01T00:00:00Z"));
                        default -> bad.add("");
                    }
                }
            }
            for (Object value : bad) {
                Map<String, Object> copy = new LinkedHashMap<>(skeleton);
                copy.put(key, value);
                result.add(new Mutation(path + "=" + describe(value), copy));
            }
        }
        return result;
    }

    private static Object wrongType(Object value) {
        if (value instanceof String) {
            return Map.of("nested", true);
        }
        if (value instanceof Number) {
            return "abc";
        }
        if (value instanceof Boolean) {
            return "yes";
        }
        if (value instanceof List<?>) {
            return "not-a-list";
        }
        if (value instanceof Map<?, ?>) {
            return 5;
        }
        return List.of(1);
    }

    private void multipartVariants(Operation operation, UUID profile, UserRole role, Map<String, String> pathValues, Map<String, String> query,
                                   List<Variant> variants) {
        String part = filePart(operation);
        Map<String, String> fields = multipartFields(operation, part);
        byte[] bomb = zipBomb();
        Map<String, MockMultipartFile> files = new LinkedHashMap<>();
        files.put("empty file", new MockMultipartFile(part, "empty.xlsx", "application/octet-stream", new byte[0]));
        files.put("garbage xlsx", new MockMultipartFile(part, "garbage.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "not a zip at all".getBytes(StandardCharsets.UTF_8)));
        files.put("zip bomb xlsx", new MockMultipartFile(part, "bomb.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bomb));
        files.put("zip bomb docx", new MockMultipartFile(part, "bomb.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bomb));
        files.put("garbage pdf", new MockMultipartFile(part, "x.pdf", "application/pdf", "%PDF-1.4\n\0\0garbage".getBytes(StandardCharsets.ISO_8859_1)));
        files.put("long name", new MockMultipartFile(part, "Я".repeat(5000) + ".pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.ISO_8859_1)));
        files.put("wrong part name", new MockMultipartFile("unexpected", "a.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.ISO_8859_1)));
        files.forEach((name, file) -> variants.add(new Variant(name, profile, role, true, op -> {
            MockMultipartHttpServletRequestBuilder builder = MockMvcRequestBuilders.multipart(HttpMethod.valueOf(op.method()), uri(op, pathValues, query));
            builder.file(file);
            fields.forEach(builder::param);
            return builder;
        })));
        variants.add(new Variant("no parts", profile, role, true, op -> MockMvcRequestBuilders.multipart(HttpMethod.valueOf(op.method()), uri(op, pathValues, query))));
        variants.add(new Variant("broken multipart", profile, role, true, op -> MockMvcRequestBuilders.request(HttpMethod.valueOf(op.method()), uri(op, pathValues, query))
                .contentType("multipart/form-data; boundary=xyz").content("--xyz\r\ngarbage without headers")
                .with(request -> new BrokenMultipartRequest(request))));
    }

    @SuppressWarnings("unchecked")
    private String filePart(Operation operation) {
        Map<String, Object> properties = (Map<String, Object>) schema(operation.body()).getOrDefault("properties", Map.of());
        return properties.entrySet().stream()
                .filter(entry -> "binary".equals(schema(entry.getValue()).get("format")))
                .map(Map.Entry::getKey).findFirst().orElse("file");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> multipartFields(Operation operation, String filePart) {
        Map<String, String> fields = new LinkedHashMap<>();
        List<Object> required = (List<Object>) schema(operation.body()).getOrDefault("required", List.of());
        ((Map<String, Object>) schema(operation.body()).getOrDefault("properties", Map.of())).forEach((name, child) -> {
            if (!name.equals(filePart) && required.contains(name)) {
                fields.put(name, String.valueOf(sample(name, child, 5)));
            }
        });
        return fields;
    }

    // Маленький архив, который распаковывается в 200 МБ: так проверяется, что разбор офисных файлов его отвергает.
    private static byte[] zipBomb() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.setLevel(Deflater.BEST_COMPRESSION);
                for (String name : List.of("[Content_Types].xml", "word/document.xml", "xl/workbook.xml")) {
                    zip.putNextEntry(new ZipEntry(name));
                    byte[] zeros = new byte[1024 * 1024];
                    for (int i = 0; i < (name.startsWith("[") ? 1 : 100); i++) {
                        zip.write(zeros);
                    }
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // Tomcat при битом multipart бросает из getParts(); MockMvc сам multipart не разбирает, поэтому сбой подставлен.
    private static final class BrokenMultipartRequest extends MockHttpServletRequest {
        private BrokenMultipartRequest(MockHttpServletRequest source) {
            super(source.getServletContext(), source.getMethod(), source.getRequestURI());
            setContentType(source.getContentType());
            setContent(source.getContentAsByteArray());
            setQueryString(source.getQueryString());
            source.getParameterMap().forEach(this::setParameter);
            for (String header : java.util.Collections.list(source.getHeaderNames())) {
                for (String value : java.util.Collections.list(source.getHeaders(header))) {
                    addHeader(header, value);
                }
            }
            for (String attribute : java.util.Collections.list(source.getAttributeNames())) {
                setAttribute(attribute, source.getAttribute(attribute));
            }
            setSession(source.getSession());
            setUserPrincipal(source.getUserPrincipal());
        }

        @Override
        public Collection<Part> getParts() throws IOException {
            throw new IOException("Stream ended unexpectedly");
        }
    }

    record RawBody(String content) {
    }

    private URI uri(Operation operation, Map<String, String> pathValues, Map<String, String> query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(operation.path());
        query.forEach(builder::queryParam);
        return builder.encode().buildAndExpand(pathValues).toUri();
    }

    private MockHttpServletRequestBuilder build(Operation operation, Map<String, String> pathValues, Map<String, String> query,
                                                Object body, String contentType) {
        if (operation.contentType() != null && operation.contentType().startsWith("multipart/") && body == null) {
            return MockMvcRequestBuilders.multipart(HttpMethod.valueOf(operation.method()), uri(operation, pathValues, query));
        }
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(HttpMethod.valueOf(operation.method()), uri(operation, pathValues, query));
        if (body != null || operation.contentType() != null) {
            builder.contentType(contentType == null ? "application/json" : contentType);
            try {
                builder.content(body instanceof RawBody raw ? raw.content() : body == null ? "" : objectMapper.writeValueAsString(body));
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
        return builder;
    }

    private void perform(Operation operation, Variant variant) throws Exception {
        MockHttpServletRequestBuilder builder = variant.request().apply(operation)
                .with(login(variant.profile(), variant.role()))
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (variant.csrf()) {
            builder.with(csrf());
        }
        String key = operation.method() + " " + operation.path() + " [" + variant.name() + ", " + variant.role() + "]";
        requests++;
        MockHttpServletResponse response;
        try {
            response = mockMvc.perform(builder).andReturn().getResponse();
        } catch (Exception exception) {
            Throwable root = rootCause(exception);
            if (root instanceof org.h2.jdbc.JdbcSQLSyntaxErrorException) {
                h2Only.add(operation.method() + " " + operation.path() + ": " + abbreviate(String.valueOf(root.getMessage()).split("\n")[0]));
                return;
            }
            findings.put(key, key + " → 500 " + root.getClass().getName() + ": " + abbreviate(String.valueOf(root.getMessage())));
            return;
        }
        int status = response.getStatus();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        if (status >= 500 && status != 503) {
            findings.put(key, key + " → " + status + " " + abbreviate(body));
            return;
        }
        if (body.contains("Exception") || body.contains("\tat ") || body.contains("org.springframework") || body.contains("java.lang")) {
            findings.put(key, key + " → " + status + " с внутренностями в ответе: " + abbreviate(body));
            return;
        }
        if (status < 400) {
            return;
        }
        if (body.isEmpty()) {
            // Без тела отвечает только sendError контейнера: в настоящем запросе его превращает в ApiError ApiErrorController.
            if (response.getErrorMessage() == null && status != 401 && status != 403) {
                findings.put(key, key + " → " + status + " без тела ApiError");
            }
            return;
        }
        try {
            JsonNode error = objectMapper.readTree(body);
            if (!errorCodes.contains(error.path("code").asText())) {
                findings.put(key, key + " → " + status + " код вне ApiError.code: " + abbreviate(body));
            } else if (status == 503 && error.path("code").asText().equals("INTERNAL_ERROR")) {
                findings.put(key, key + " → 503 INTERNAL_ERROR");
            }
        } catch (IOException exception) {
            findings.put(key, key + " → " + status + " не JSON: " + abbreviate(body));
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            if (current instanceof SQLException) {
                return current;
            }
            current = current.getCause();
        }
        return current;
    }

    private static String describe(Object value) {
        if (value instanceof String text) {
            return text.length() > 30 ? "строка " + text.length() : '"' + text + '"';
        }
        if (value instanceof List<?> list) {
            return "массив " + list.size();
        }
        return String.valueOf(value);
    }

    private static String abbreviate(String text) {
        String flat = text.replace('\n', ' ');
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }
}
