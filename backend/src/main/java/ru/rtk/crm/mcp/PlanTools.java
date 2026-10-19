package ru.rtk.crm.mcp;

import static ru.rtk.crm.mcp.McpTools.enumProperty;
import static ru.rtk.crm.mcp.McpTools.property;
import static ru.rtk.crm.mcp.McpTools.schema;
import static ru.rtk.crm.mcp.McpTools.uuidProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.rtk.crm.interaction.InteractionIssueKind;
import ru.rtk.crm.interaction.InteractionRiskLevel;
import ru.rtk.crm.mcp.AgentPlanService.AgentPlan;
import ru.rtk.crm.mcp.AgentPlanService.Prepared;
import ru.rtk.crm.mcp.McpTools.Args;
import ru.rtk.crm.mcp.McpTools.Definition;
import ru.rtk.crm.mcp.McpTools.Result;

// Запись через план: у агента нет инструмента, который меняет данные CRM. Он готовит план и узнаёт его итог, а
// записывает сотрудник на экране CRM. Все инструменты видны только клиенту со scope записи.
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
class PlanTools {
    private static final Map<String, Object> DATE_TIME = Map.of("type", "string", "format", "date-time",
            "description", "Срок следующего шага с часовым поясом, например 2026-10-20T15:00:00+03:00");
    private static final String CONFIRM = " Данные CRM не меняются: сотрудник открывает ссылку confirmUrl, сверяет «было / "
            + "станет» и сам нажимает «Записать» или «Отклонить».";

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
        String lifetime = " План действует " + properties.planTtl().toMinutes() + " минут; итог — get_change_plan.";
        return List.of(
                plan("prepare_note_update", "Подготовить итог встречи и следующий шаг",
                        "Готовит план записи для одного взаимодействия: комментарий (итог встречи) и новый следующий шаг "
                                + "со сроком — оба поля шага обязательны. Напоминание «позвонить во вторник» — это тоже "
                                + "следующий шаг со сроком." + CONFIRM + lifetime,
                        schema(Map.of(
                                "interactionId", uuidProperty("Взаимодействие"),
                                "note", property("string", "Итог встречи — текст комментария, до 4000 символов"),
                                "nextAction", property("string", "Следующий шаг, до 500 символов"),
                                "nextActionAt", DATE_TIME),
                                "interactionId", "note", "nextAction", "nextActionAt"),
                        this::prepareNote),
                plan("prepare_stage_transition", "Подготовить переход этапа",
                        "Готовит переход взаимодействия на другой этап с обоснованием. Этап — из nextStages или "
                                + "nextStageOptions сводки interaction_brief: другие маршрут не разрешает. Возврат назад "
                                + "допустим, обоснование станет причиной возврата. Факты — идентификаторы событий истории "
                                + "этой карточки (recentEvents.eventId); CRM покажет их сотруднику сама, чужие отбросит."
                                + CONFIRM + lifetime,
                        schema(Map.of(
                                "interactionId", uuidProperty("Взаимодействие"),
                                "toStage", property("string", "Название этапа из nextStages или его stageId"),
                                "rationale", property("string", "Обоснование перехода по фактам карточки, до 4000 символов"),
                                "nextAction", property("string", "Новый следующий шаг, до 500 символов; только вместе со сроком"),
                                "nextActionAt", DATE_TIME,
                                "evidenceEventIds", Map.of("type", "array", "maxItems", AgentPlanService.EVIDENCE_LIMIT,
                                        "items", Map.of("type", "string", "format", "uuid"),
                                        "description", "До 10 событий истории карточки, на которые опирается обоснование")),
                                "interactionId", "toStage", "rationale"),
                        this::prepareTransition),
                plan("prepare_issue", "Подготовить проблему или риск",
                        "Готовит запись проблемы или риска во взаимодействии: описание, ответственный из "
                                + "eligibleResponsibles сводки interaction_brief и срок; для риска — уровень. Ответственный "
                                + "получит уведомление только после того, как сотрудник запишет план." + CONFIRM + lifetime,
                        schema(Map.of(
                                "interactionId", uuidProperty("Взаимодействие"),
                                "kind", enumProperty("PROBLEM — проблема, RISK — риск", "PROBLEM", "RISK"),
                                "description", property("string", "Описание, до 1000 символов"),
                                "riskLevel", enumProperty("Уровень риска, обязателен для RISK", "MEDIUM", "HIGH"),
                                "responsibleId", uuidProperty("Ответственный из eligibleResponsibles"),
                                "dueOn", Map.of("type", "string", "format", "date", "description", "Срок, ГГГГ-ММ-ДД, не раньше сегодня")),
                                "interactionId", "kind", "description", "responsibleId", "dueOn"),
                        this::prepareIssue),
                plan("prepare_issue_resolution", "Подготовить решение проблемы",
                        "Готовит отметку «решено» для открытой проблемы или риска из openIssues сводки interaction_brief "
                                + "с текстом решения. Повторно открыть решённую запись нельзя." + CONFIRM + lifetime,
                        schema(Map.of(
                                "interactionId", uuidProperty("Взаимодействие"),
                                "issueId", uuidProperty("Открытая проблема или риск из openIssues"),
                                "resolution", property("string", "Как решили, до 1000 символов")),
                                "interactionId", "issueId", "resolution"),
                        this::prepareResolution),
                plan("prepare_pause", "Подготовить паузу со сроком возврата",
                        "Готовит приостановку активного взаимодействия с причиной и датой возврата; в дату возврата CRM "
                                + "сама поставит шаг «вернуться к работе». Завершить взаимодействие агент не может." + CONFIRM
                                + lifetime,
                        schema(Map.of(
                                "interactionId", uuidProperty("Взаимодействие"),
                                "reason", property("string", "Причина паузы, до 1000 символов"),
                                "resumeOn", Map.of("type", "string", "format", "date",
                                        "description", "Дата возврата, ГГГГ-ММ-ДД, не раньше завтра")),
                                "interactionId", "reason", "resumeOn"),
                        this::preparePause),
                plan("prepare_resume", "Подготовить возобновление",
                        "Готовит возобновление приостановленного взаимодействия с комментарием." + CONFIRM + lifetime,
                        schema(Map.of(
                                "interactionId", uuidProperty("Взаимодействие"),
                                "comment", property("string", "Комментарий, до 1000 символов")),
                                "interactionId", "comment"),
                        this::prepareResume),
                new Definition("get_change_plan", "Статус плана записи",
                        "Статус плана, подготовленного инструментами prepare_*: PENDING — ждёт сотрудника, APPLIED — записан, "
                                + "REJECTED — отклонён, EXPIRED — истёк, STALE — карточку изменили после подготовки, FAILED — "
                                + "CRM не приняла запись. Видны только свои планы.",
                        schema(Map.of("planId", uuidProperty("План")), "planId"),
                        McpTools.KAM, this::status, McpTools.READ_ONLY, true)
        );
    }

    private static Definition plan(String name, String title, String description, Map<String, Object> schema,
                                   BiFunction<McpCaller, Args, Result> handler) {
        return new Definition(name, title, description, schema, McpTools.KAM, handler, McpTools.PREPARES_PLAN, true);
    }

    private Result prepareNote(McpCaller caller, Args args) {
        return prepared(new Prepared(agentPlanService.prepare(caller.profile(), caller.clientId(), args.requiredUuid("interactionId"),
                args.text("note"), args.text("nextAction"), args.dateTime("nextActionAt"), caller.requestId()), List.of(), 0));
    }

    private Result prepareTransition(McpCaller caller, Args args) {
        return prepared(agentPlanService.prepareTransition(caller.profile(), caller.clientId(), args.requiredUuid("interactionId"),
                args.text("toStage"), args.text("rationale"), args.text("nextAction"), args.dateTime("nextActionAt"),
                args.uuids("evidenceEventIds", AgentPlanService.EVIDENCE_LIMIT), caller.requestId()));
    }

    private Result prepareIssue(McpCaller caller, Args args) {
        return prepared(agentPlanService.prepareIssue(caller.profile(), caller.clientId(), args.requiredUuid("interactionId"),
                args.enumValue("kind", InteractionIssueKind.class), args.text("description"),
                args.enumValue("riskLevel", InteractionRiskLevel.class), args.uuid("responsibleId"), args.date("dueOn", null),
                caller.requestId()));
    }

    private Result prepareResolution(McpCaller caller, Args args) {
        return prepared(agentPlanService.prepareIssueResolution(caller.profile(), caller.clientId(), args.requiredUuid("interactionId"),
                args.requiredUuid("issueId"), args.text("resolution"), caller.requestId()));
    }

    private Result preparePause(McpCaller caller, Args args) {
        return prepared(agentPlanService.preparePause(caller.profile(), caller.clientId(), args.requiredUuid("interactionId"),
                args.text("reason"), args.date("resumeOn", null), caller.requestId()));
    }

    private Result prepareResume(McpCaller caller, Args args) {
        return prepared(agentPlanService.prepareResume(caller.profile(), caller.clientId(), args.requiredUuid("interactionId"),
                args.text("comment"), caller.requestId()));
    }

    private Result prepared(Prepared prepared) {
        AgentPlan plan = prepared.plan();
        Map<String, Object> data = view(plan);
        data.put("confirmUrl", publicBaseUrl + "/#/agent-plans/" + plan.id());
        data.put("warnings", prepared.warnings());
        if (prepared.droppedEvidence() > 0) {
            data.put("droppedEvidence", prepared.droppedEvidence());
        }
        return new Result(data, 1, List.of(
                "Данные CRM не изменены. Дайте сотруднику ссылку confirmUrl: записать план может только он сам, в CRM.",
                "Если до подтверждения карточку изменят, план станет STALE — подготовьте новый.",
                "warnings: BACKWARD — возврат на прежний этап, SKIP — переход через этап, PAST_DUE — срок в прошлом."));
    }

    private Result status(McpCaller caller, Args args) {
        AgentPlan plan = agentPlanService.get(caller.profile(), args.requiredUuid("planId"), caller.requestId());
        return new Result(view(plan), 1, List.of());
    }

    // Текст плана агенту не возвращается: он его и прислал, а повтор в ответе только увеличивает поток ПДн.
    private static Map<String, Object> view(AgentPlan plan) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planId", plan.id());
        data.put("kind", plan.kind());
        data.put("status", plan.state());
        data.put("interactionId", plan.interactionId());
        data.put("createdAt", plan.createdAt());
        data.put("expiresAt", plan.expiresAt());
        data.put("decidedAt", plan.decidedAt());
        data.put("errorCode", plan.errorCode());
        return data;
    }
}
