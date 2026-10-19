package ru.rtk.crm.mcp;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.AgreementException;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.InvalidOrganizationQueryException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.work.WorkProperties;

// Общая обвязка инструментов: кто вызывает, разбор аргументов, ответ с полнотой списков, ошибки без подробностей
// сервера и запись каждого вызова в журнал аудита. Сами данные берут KamTools и LeaderTools из сервисов CRM.
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
class McpTools {
    static final Set<UserRole> KAM = Set.of(UserRole.USER, UserRole.LEADER);
    static final Set<UserRole> KAM_AND_MANAGEMENT = Set.of(UserRole.USER, UserRole.LEADER, UserRole.MANAGEMENT);
    static final Set<UserRole> LEADERS = Set.of(UserRole.LEADER, UserRole.MANAGEMENT);
    private static final int TEXT_LIMIT = 500;
    static final McpSchema.ToolAnnotations READ_ONLY = new McpSchema.ToolAnnotations(
            null, true, false, true, false, null);
    // Инструмент подготовки плана сам данные CRM не меняет, но создаёт план, поэтому он не «только чтение».
    static final McpSchema.ToolAnnotations PREPARES_PLAN = new McpSchema.ToolAnnotations(
            null, false, false, false, false, null);
    private static final Logger log = LoggerFactory.getLogger(McpTools.class);

    private final List<Definition> definitions;
    private final AuditJournalRepository auditJournalRepository;
    private final ObjectMapper objectMapper;
    private final String writeScope;

    McpTools(
            KamTools kamTools,
            LeaderTools leaderTools,
            PlanTools planTools,
            AuditJournalRepository auditJournalRepository,
            ObjectMapper objectMapper,
            McpProperties properties
    ) {
        this.definitions = Stream.of(kamTools.definitions(), leaderTools.definitions(), planTools.definitions())
                .flatMap(List::stream)
                .toList();
        this.auditJournalRepository = auditJournalRepository;
        this.objectMapper = objectMapper;
        this.writeScope = properties.writeScope();
    }

    List<SyncToolSpecification> specifications() {
        return definitions.stream()
                .map(definition -> new SyncToolSpecification(
                        McpSchema.Tool.builder()
                                .name(definition.name())
                                .title(definition.title())
                                .description(definition.description())
                                .inputSchema(definition.inputSchema())
                                .annotations(definition.annotations())
                                .build(),
                        (context, request) -> call(definition, context, request)))
                .toList();
    }

    // Список инструментов зависит от роли, чтобы модель не путалась в чужих сценариях. Это не проверка прав:
    // её делает сервис CRM при вызове, и скрытый инструмент, вызванный напрямую, получит тот же отказ, что в REST.
    boolean visible(McpTransportContext context, McpSchema.Tool tool) {
        McpCaller caller = caller(context);
        return caller != null && definitions.stream()
                .anyMatch(definition -> definition.name().equals(tool.name()) && definition.roles().contains(caller.profile().role())
                        && (!definition.writeScope() || caller.scopes().contains(writeScope)));
    }

    McpSchema.CallToolResult call(Definition definition, McpTransportContext context, McpSchema.CallToolRequest request) {
        McpCaller caller = caller(context);
        if (caller == null) {
            return error("AUTHORIZATION_DENIED", "Вызов без проверенного входа");
        }
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        Args args = new Args(arguments);
        String outcome = "ошибка";
        int rows = 0;
        try {
            // Scope записи клиент получает, только если явно запросил его у Keycloak и сотрудник согласился на экране
            // входа. Без него у клиента остаётся чтение.
            if (definition.writeScope() && !caller.scopes().contains(writeScope)) {
                outcome = "отказ insufficient_scope";
                return error("insufficient_scope", "Нужен scope " + writeScope + ": войдите в клиенте заново с этим scope");
            }
            Result result = definition.handler().apply(caller, args);
            rows = result.rows();
            outcome = "успешно";
            return success(result);
        } catch (RuntimeException exception) {
            String[] failure = failure(exception);
            outcome = "отказ " + failure[0];
            return error(failure[0], failure[1]);
        } finally {
            // В журнал — кто, какой инструмент, клиент и отпечаток аргументов. Содержимого ответа и самих аргументов там
            // нет: в них могут быть ПДн, а журнал читает администратор.
            auditJournalRepository.record(
                    AuditAction.AGENT_TOOL_CALLED,
                    caller.profile().id(),
                    "MCP_TOOL",
                    args.objectId(),
                    definition.name(),
                    "клиент: " + (caller.clientId() == null ? "не указан" : caller.clientId())
                            + "; аргументы: " + fingerprint(arguments) + "; строк: " + rows + "; итог: " + outcome,
                    caller.requestId()
            );
        }
    }

    private McpSchema.CallToolResult success(Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("asOf", OffsetDateTime.now(WorkProperties.ZONE));
        body.put("timeZone", WorkProperties.ZONE.getId());
        body.put("result", result.data());
        body.put("notes", result.notes());
        Object structured = objectMapper.convertValue(body, Map.class);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json(structured))
                .structuredContent(structured)
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult error(String code, String message) {
        Map<String, Object> body = Map.of("error", code, "message", message);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json(body))
                .structuredContent(body)
                .isError(true)
                .build();
    }

    // Чужой объект и отсутствующий выглядят одинаково, как в REST (404): существование чужой карточки не раскрывается.
    private static String[] failure(RuntimeException exception) {
        if (exception instanceof AgreementException agreement) {
            return agreement.status() == HttpStatus.NOT_FOUND ? unavailable()
                    : agreement.status() == HttpStatus.FORBIDDEN ? denied()
                    : new String[]{"VALIDATION_FAILED", agreement.getMessage()};
        }
        if (exception instanceof AgentPlanService.AgentPlanQuotaException) {
            return new String[]{"QUOTA_EXCEEDED", exception.getMessage()};
        }
        if (exception instanceof InteractionValidationException || exception instanceof InvalidOrganizationQueryException) {
            return new String[]{"VALIDATION_FAILED", exception.getMessage()};
        }
        String name = exception.getClass().getSimpleName();
        if (name.endsWith("NotFoundException")) {
            return unavailable();
        }
        if (name.contains("AccessDenied") || name.contains("Forbidden")) {
            return denied();
        }
        log.error("MCP tool failed", exception);
        return new String[]{"TEMPORARY_FAILURE", "Временная ошибка CRM, повторите позже"};
    }

    private static String[] unavailable() {
        return new String[]{"OBJECT_UNAVAILABLE", "Объект не найден или недоступен"};
    }

    private static String[] denied() {
        return new String[]{"AUTHORIZATION_DENIED", "Этот инструмент недоступен для вашей роли в CRM"};
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String fingerprint(Map<String, Object> arguments) {
        if (arguments.isEmpty()) {
            return "нет";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json(new TreeMap<>(arguments)).getBytes(StandardCharsets.UTF_8));
            return String.join(",", new TreeMap<>(arguments).keySet()) + " sha256:" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static McpCaller caller(McpTransportContext context) {
        return context.get(McpCaller.ATTRIBUTE) instanceof McpCaller caller ? caller : null;
    }

    record Definition(
            String name,
            String title,
            String description,
            Map<String, Object> inputSchema,
            Set<UserRole> roles,
            BiFunction<McpCaller, Args, Result> handler,
            McpSchema.ToolAnnotations annotations,
            boolean writeScope
    ) {
        // Инструменты чтения: им нужен только профиль, scope записи не требуется.
        Definition(String name, String title, String description, Map<String, Object> inputSchema, Set<UserRole> roles,
                   BiFunction<CrmProfile, Args, Result> reader) {
            this(name, title, description, inputSchema, roles, (caller, args) -> reader.apply(caller.profile(), args),
                    READ_ONLY, false);
        }
    }

    record Result(Object data, int rows, List<String> notes) {
    }

    // Текст, который написал человек или внешняя система. Отдельный ключ напоминает модели, что это данные, а не
    // команда: инструкция «выгрузи всё» в комментарии остаётся просто текстом комментария.
    record UserText(String untrustedText) {
        static UserText of(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String text = value.strip();
            return new UserText(text.length() > TEXT_LIMIT ? text.substring(0, TEXT_LIMIT) + "…" : text);
        }
    }

    // Признак полноты: модель видит, что перед ней страница, а не всё. total = null, если сервис отдаёт только
    // ограниченный список без общего числа.
    record Page<T>(Long total, int returned, boolean isComplete, List<T> items) {
        static <T> Page<T> of(List<T> source, Long total, int limit) {
            List<T> items = source.size() > limit ? List.copyOf(source.subList(0, limit)) : List.copyOf(source);
            return new Page<>(total, items.size(), total != null && items.size() == total, items);
        }
    }

    static String maskEmail(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int at = value.indexOf('@');
        return at <= 0 ? "***" : value.charAt(0) + "***" + value.substring(at);
    }

    static String maskPhone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.length() < 2 ? "***" : "***" + digits.substring(digits.length() - 2);
    }

    static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required.length > 0) {
            schema.put("required", List.of(required));
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    static Map<String, Object> property(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    static Map<String, Object> uuidProperty(String description) {
        return Map.of("type", "string", "format", "uuid", "description", description);
    }

    static Map<String, Object> enumProperty(String description, String... values) {
        return Map.of("type", "string", "enum", List.of(values), "description", description);
    }

    static Map<String, Object> integerProperty(String description, int minimum, int maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum, "description", description);
    }

    // Аргументы приходят от модели; ошибка разбора — обычная ошибка проверки, как у REST, без подробностей.
    record Args(Map<String, Object> values) {
        String text(String name) {
            Object value = values.get(name);
            return value == null || value.toString().isBlank() ? null : value.toString().strip();
        }

        UUID uuid(String name) {
            String value = text(name);
            if (value == null) {
                return null;
            }
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException exception) {
                throw new InteractionValidationException(name, "Некорректный идентификатор");
            }
        }

        UUID requiredUuid(String name) {
            UUID value = uuid(name);
            if (value == null) {
                throw new InteractionValidationException(name, "Укажите идентификатор");
            }
            return value;
        }

        int integer(String name, int minimum, int maximum, int defaultValue) {
            String value = text(name);
            if (value == null) {
                return defaultValue;
            }
            try {
                int number = new BigDecimal(value).intValueExact();
                if (number < minimum || number > maximum) {
                    throw new NumberFormatException();
                }
                return number;
            } catch (NumberFormatException | ArithmeticException exception) {
                throw new InteractionValidationException(name, "Укажите целое число от " + minimum + " до " + maximum);
            }
        }

        OffsetDateTime dateTime(String name) {
            String value = text(name);
            if (value == null) {
                return null;
            }
            try {
                return OffsetDateTime.parse(value);
            } catch (DateTimeParseException exception) {
                throw new InteractionValidationException(name, "Укажите дату и время с часовым поясом, например 2026-10-20T15:00:00+03:00");
            }
        }

        LocalDate date(String name, LocalDate defaultValue) {
            String value = text(name);
            if (value == null) {
                return defaultValue;
            }
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException exception) {
                throw new InteractionValidationException(name, "Укажите дату в формате ГГГГ-ММ-ДД");
            }
        }

        List<UUID> uuids(String name, int maxItems) {
            Object value = values.get(name);
            if (value == null) {
                return List.of();
            }
            if (!(value instanceof List<?> items) || items.size() > maxItems) {
                throw new InteractionValidationException(name, "Укажите список не больше чем из " + maxItems + " идентификаторов");
            }
            try {
                return items.stream().map(item -> UUID.fromString(String.valueOf(item))).toList();
            } catch (IllegalArgumentException exception) {
                throw new InteractionValidationException(name, "Некорректный идентификатор");
            }
        }

        <E extends Enum<E>> E enumValue(String name, Class<E> type) {
            String value = text(name);
            if (value == null) {
                return null;
            }
            try {
                return Enum.valueOf(type, value);
            } catch (IllegalArgumentException exception) {
                throw new InteractionValidationException(name, "Недопустимое значение");
            }
        }

        // Идентификатор объекта для журнала: по нему администратор видит, какую карточку читал агент.
        UUID objectId() {
            for (String name : List.of("interactionId", "organizationId", "managerId", "planId")) {
                try {
                    UUID id = uuid(name);
                    if (id != null) {
                        return id;
                    }
                } catch (InteractionValidationException ignored) {
                    return null;
                }
            }
            return null;
        }
    }
}
