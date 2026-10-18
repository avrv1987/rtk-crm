package ru.rtk.crm.mcp;

import static ru.rtk.crm.mcp.McpTools.property;
import static ru.rtk.crm.mcp.McpTools.schema;
import static ru.rtk.crm.mcp.McpTools.uuidProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.rtk.crm.mcp.AgentPlanService.AgentPlan;
import ru.rtk.crm.mcp.McpTools.Args;
import ru.rtk.crm.mcp.McpTools.Definition;
import ru.rtk.crm.mcp.McpTools.Result;

// Запись через план: у агента нет инструмента, который меняет данные CRM. Он готовит план и узнаёт его итог, а
// записывает сотрудник на экране CRM. Оба инструмента видны только клиенту со scope записи.
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
class PlanTools {
    private final AgentPlanService agentPlanService;
    private final McpProperties properties;
    private final String publicBaseUrl;

    PlanTools(
            AgentPlanService agentPlanService,
            McpProperties properties,
            @Value("${app.oidc.public-base-url:}") String publicBaseUrl
    ) {
        this.agentPlanService = agentPlanService;
        this.properties = properties;
        this.publicBaseUrl = publicBaseUrl;
    }

    List<Definition> definitions() {
        long minutes = properties.planTtl().toMinutes();
        return List.of(
                new Definition("prepare_note_update", "Подготовить итог встречи и следующий шаг",
                        "Готовит план записи для одного взаимодействия: комментарий (итог встречи) и новый следующий шаг "
                                + "со сроком — оба поля шага обязательны. Данные CRM не меняются: сотрудник открывает ссылку "
                                + "confirmUrl, сверяет «было / станет» и сам нажимает «Записать» или «Отклонить». План "
                                + "действует " + minutes + " минут; итог — get_change_plan.",
                        schema(Map.of(
                                "interactionId", uuidProperty("Взаимодействие"),
                                "note", property("string", "Итог встречи — текст комментария, до 4000 символов"),
                                "nextAction", property("string", "Следующий шаг, до 500 символов"),
                                "nextActionAt", Map.of("type", "string", "format", "date-time",
                                        "description", "Срок следующего шага с часовым поясом, например 2026-10-20T15:00:00+03:00")),
                                "interactionId", "note", "nextAction", "nextActionAt"),
                        McpTools.KAM, this::prepare, McpTools.PREPARES_PLAN, true),
                new Definition("get_change_plan", "Статус плана записи",
                        "Статус плана, подготовленного prepare_note_update: PENDING — ждёт сотрудника, APPLIED — записан, "
                                + "REJECTED — отклонён, EXPIRED — истёк, STALE — карточку изменили после подготовки, FAILED — "
                                + "CRM не приняла запись. Видны только свои планы.",
                        schema(Map.of("planId", uuidProperty("План")), "planId"),
                        McpTools.KAM, this::status, McpTools.READ_ONLY, true)
        );
    }

    private Result prepare(McpCaller caller, Args args) {
        AgentPlan plan = agentPlanService.prepare(
                caller.profile(),
                caller.clientId(),
                args.requiredUuid("interactionId"),
                args.text("note"),
                args.text("nextAction"),
                args.dateTime("nextActionAt"),
                caller.requestId()
        );
        Map<String, Object> data = view(plan);
        data.put("confirmUrl", publicBaseUrl + "/#/agent-plans/" + plan.id());
        return new Result(data, 1, List.of(
                "Данные CRM не изменены. Дайте сотруднику ссылку confirmUrl: записать план может только он сам, в CRM.",
                "Если до подтверждения карточку изменят, план станет STALE — подготовьте новый."));
    }

    private Result status(McpCaller caller, Args args) {
        AgentPlan plan = agentPlanService.get(caller.profile(), args.requiredUuid("planId"), caller.requestId());
        return new Result(view(plan), 1, List.of());
    }

    // Текст плана агенту не возвращается: он его и прислал, а повтор в ответе только увеличивает поток ПДн.
    private static Map<String, Object> view(AgentPlan plan) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planId", plan.id());
        data.put("status", plan.state());
        data.put("interactionId", plan.interactionId());
        data.put("createdAt", plan.createdAt());
        data.put("expiresAt", plan.expiresAt());
        data.put("decidedAt", plan.decidedAt());
        data.put("errorCode", plan.errorCode());
        return data;
    }
}
