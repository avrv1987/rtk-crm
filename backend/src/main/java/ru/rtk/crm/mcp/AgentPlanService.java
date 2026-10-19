package ru.rtk.crm.mcp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.Interaction;
import ru.rtk.crm.interaction.InteractionCommentRequest;
import ru.rtk.crm.interaction.InteractionCommentResult;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionEvent;
import ru.rtk.crm.interaction.InteractionIssue;
import ru.rtk.crm.interaction.InteractionIssueKind;
import ru.rtk.crm.interaction.InteractionIssueList;
import ru.rtk.crm.interaction.InteractionIssueNotFoundException;
import ru.rtk.crm.interaction.InteractionIssueRequest;
import ru.rtk.crm.interaction.InteractionIssueResolution;
import ru.rtk.crm.interaction.InteractionIssueService;
import ru.rtk.crm.interaction.InteractionIssueStatus;
import ru.rtk.crm.interaction.InteractionNextStep;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionRiskLevel;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionStage;
import ru.rtk.crm.interaction.InteractionStatusRequest;
import ru.rtk.crm.interaction.InteractionTransitionOption;
import ru.rtk.crm.interaction.InteractionTransitionRequest;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.interaction.InteractionWorkStatus;

// Запись через ИИ-агента: агент только сохраняет план, а исполняет его CRM по кнопке «Записать» сотрудника, для
// которого план создан. Каждый вид плана исполняется той же командой, что и действие из карточки: права проверяются
// заново от имени подтвердившего, версия карточки и ключ повтора agent-plan:<id> — те же, что у интерфейса.
@Service
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public class AgentPlanService {
    private static final Logger log = LoggerFactory.getLogger(AgentPlanService.class);
    private static final String COLUMNS = """
            id, requester_profile_id, client_id, interaction_id, stage_id, expected_version, note_text, next_action,
            next_action_at, state, error_code, created_at, expires_at, decided_by, decided_at, command_id, kind, to_stage_id,
            issue_id, issue_kind, risk_level, responsible_profile_id, due_on, work_status, resume_on, evidence_event_ids
            """;
    private static final int LIST_LIMIT = 100;
    static final int EVIDENCE_LIMIT = 10;
    // Пределы текстов — те же, что у команд: комментарий и обоснование перехода до 4000, проблема и причина паузы до 1000.
    private static final int NOTE_LIMIT = 4_000;
    private static final int SHORT_TEXT_LIMIT = 1_000;

    private final JdbcClient jdbc;
    private final InteractionService interactionService;
    private final InteractionIssueService interactionIssueService;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final OrganizationRepository organizationRepository;
    private final AuditJournalRepository auditJournalRepository;
    private final McpProperties properties;
    private final TransactionTemplate separateTransaction;

    public AgentPlanService(
            JdbcClient jdbc,
            InteractionService interactionService,
            InteractionIssueService interactionIssueService,
            CommandIdempotencyRepository commandIdempotencyRepository,
            OrganizationRepository organizationRepository,
            AuditJournalRepository auditJournalRepository,
            McpProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.interactionService = interactionService;
        this.interactionIssueService = interactionIssueService;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.organizationRepository = organizationRepository;
        this.auditJournalRepository = auditJournalRepository;
        this.properties = properties;
        this.separateTransaction = new TransactionTemplate(transactionManager);
        this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // Итог встречи и следующий шаг. «Напоминание» — тот же план: новый шаг со сроком, отдельной сущности нет (ТЗ
    // запрещает универсальные задачи).
    @Transactional
    public AgentPlan prepare(
            CrmProfile profile,
            String clientId,
            UUID interactionId,
            String note,
            String nextAction,
            OffsetDateTime nextActionAt,
            String requestId
    ) {
        Interaction interaction = editable(profile, interactionId);
        String text = required(note, "note", NOTE_LIMIT);
        String action = required(nextAction, "nextAction", 500);
        if (nextActionAt == null) {
            throw new InteractionValidationException("nextActionAt", "Укажите срок следующего шага");
        }
        return insert(profile, clientId, interaction, Draft.of(Kind.NOTE_UPDATE, text).step(action, nextActionAt), requestId);
    }

    @Transactional
    public Prepared prepareTransition(
            CrmProfile profile,
            String clientId,
            UUID interactionId,
            String toStage,
            String rationale,
            String nextAction,
            OffsetDateTime nextActionAt,
            List<UUID> evidenceEventIds,
            String requestId
    ) {
        Interaction interaction = editable(profile, interactionId);
        InteractionTransitionOption target = allowedTarget(interaction, toStage);
        // Обоснование обязательно всегда: при возврате оно станет причиной возврата, которую требует команда.
        String text = required(rationale, "rationale", NOTE_LIMIT);
        Draft draft = Draft.of(Kind.STAGE_TRANSITION, text);
        draft.toStageId = target.stageId();
        nextStep(draft, nextAction, nextActionAt);
        List<UUID> requested = evidenceEventIds == null ? List.of() : evidenceEventIds;
        if (requested.size() > EVIDENCE_LIMIT) {
            throw new InteractionValidationException("evidenceEventIds", "Не больше " + EVIDENCE_LIMIT + " событий");
        }
        // Факты — только события этой карточки: идентификаторы чужих или выдуманных событий отбрасываются молча.
        Set<UUID> own = interactionService.events(profile, interactionId).stream().map(InteractionEvent::id).collect(Collectors.toSet());
        draft.evidence = requested.stream().filter(own::contains).distinct().toList();
        AgentPlan plan = insert(profile, clientId, interaction, draft, requestId);
        return new Prepared(plan, warnings(plan, interaction, OffsetDateTime.now()), requested.size() - draft.evidence.size());
    }

    @Transactional
    public Prepared prepareIssue(
            CrmProfile profile,
            String clientId,
            UUID interactionId,
            InteractionIssueKind kind,
            String description,
            InteractionRiskLevel riskLevel,
            UUID responsibleId,
            LocalDate dueOn,
            String requestId
    ) {
        Interaction interaction = editable(profile, interactionId);
        if (kind == null) {
            throw new InteractionValidationException("kind", "Выберите: проблема или риск");
        }
        if (kind == InteractionIssueKind.RISK && riskLevel == null) {
            throw new InteractionValidationException("riskLevel", "Укажите уровень риска");
        }
        String text = required(description, "description", SHORT_TEXT_LIMIT);
        // Ответственный — из тех же допустимых, что в форме карточки; при исполнении сервис проверит это ещё раз.
        if (responsibleId == null || interactionIssueService.list(profile, interactionId).responsibleOptions().stream()
                .noneMatch(option -> option.id().equals(responsibleId))) {
            throw new InteractionValidationException("responsibleId", "Выберите ответственного из eligibleResponsibles");
        }
        if (dueOn == null || dueOn.isBefore(today())) {
            throw new InteractionValidationException("dueOn", "Укажите срок не раньше сегодняшнего дня");
        }
        Draft draft = Draft.of(Kind.ISSUE_CREATE, text);
        draft.issueKind = kind;
        draft.riskLevel = kind == InteractionIssueKind.RISK ? riskLevel : null;
        draft.responsibleId = responsibleId;
        draft.dueOn = dueOn;
        return prepared(insert(profile, clientId, interaction, draft, requestId), interaction);
    }

    @Transactional
    public Prepared prepareIssueResolution(
            CrmProfile profile,
            String clientId,
            UUID interactionId,
            UUID issueId,
            String resolution,
            String requestId
    ) {
        Interaction interaction = editable(profile, interactionId);
        InteractionIssue issue = interactionIssueService.list(profile, interactionId).items().stream()
                .filter(item -> item.id().equals(issueId))
                .findFirst()
                .orElseThrow(InteractionIssueNotFoundException::new);
        if (issue.status() == InteractionIssueStatus.RESOLVED) {
            throw new InteractionValidationException("issueId", "Запись уже решена");
        }
        Draft draft = Draft.of(Kind.ISSUE_RESOLVE, required(resolution, "resolution", SHORT_TEXT_LIMIT));
        draft.issueId = issueId;
        return prepared(insert(profile, clientId, interaction, draft, requestId), interaction);
    }

    @Transactional
    public Prepared preparePause(CrmProfile profile, String clientId, UUID interactionId, String reason, LocalDate resumeOn,
                                 String requestId) {
        Interaction interaction = editable(profile, interactionId);
        requireStatus(interaction, InteractionWorkStatus.ACTIVE, "Приостановить можно только активное взаимодействие");
        String text = required(reason, "reason", SHORT_TEXT_LIMIT);
        if (resumeOn == null || !resumeOn.isAfter(today())) {
            throw new InteractionValidationException("resumeOn", "Дата возврата должна быть не раньше завтрашнего дня");
        }
        Draft draft = Draft.of(Kind.STATUS_CHANGE, text);
        draft.workStatus = InteractionWorkStatus.PAUSED;
        draft.resumeOn = resumeOn;
        return prepared(insert(profile, clientId, interaction, draft, requestId), interaction);
    }

    @Transactional
    public Prepared prepareResume(CrmProfile profile, String clientId, UUID interactionId, String comment, String requestId) {
        Interaction interaction = editable(profile, interactionId);
        requireStatus(interaction, InteractionWorkStatus.PAUSED, "Возобновить можно только приостановленное взаимодействие");
        Draft draft = Draft.of(Kind.STATUS_CHANGE, required(comment, "comment", SHORT_TEXT_LIMIT));
        draft.workStatus = InteractionWorkStatus.ACTIVE;
        return prepared(insert(profile, clientId, interaction, draft, requestId), interaction);
    }

    public AgentPlan get(CrmProfile profile, UUID planId, String requestId) {
        return staleIfChanged(expireIfDue(requireOwn(profile, planId), profile, requestId), profile, requestId);
    }

    // Карточку изменили после подготовки: записать план уже нельзя, поэтому он закрывается STALE при чтении, как истёкший,
    // а не числится «ждёт подтверждения» до конца срока.
    private AgentPlan staleIfChanged(AgentPlan plan, CrmProfile profile, String requestId) {
        if (plan.state() != AgentPlan.State.PENDING) {
            return plan;
        }
        Integer version = jdbc.sql("SELECT version FROM interactions WHERE id = :id").param("id", plan.interactionId())
                .query(Integer.class).optional().orElse(null);
        return version != null && version != plan.expectedVersion()
                ? close(plan, AgentPlan.State.STALE, "VERSION_CONFLICT", profile, requestId)
                : plan;
    }

    public AgentPlanScreen screen(CrmProfile profile, UUID planId, String requestId) {
        AgentPlan plan = get(profile, planId, requestId);
        Interaction interaction = interactionService.get(profile, plan.interactionId());
        Organization organization = organizationRepository.findVisibleById(profile, interaction.organizationId())
                .orElseThrow(InteractionNotFoundException::new);
        List<InteractionStage> stages = interaction.stages();
        Optional<InteractionStage> current = stage(stages, plan.stageId());
        Optional<InteractionStage> target = plan.toStageId() == null ? Optional.empty() : stage(stages, plan.toStageId());
        String responsibleName = null;
        String issueDescription = null;
        InteractionIssueStatus issueStatus = null;
        if (plan.kind() == Kind.ISSUE_CREATE || plan.kind() == Kind.ISSUE_RESOLVE) {
            InteractionIssueList issues = interactionIssueService.list(profile, plan.interactionId());
            responsibleName = issues.responsibleOptions().stream()
                    .filter(option -> option.id().equals(plan.responsibleId()))
                    .map(InteractionIssueList.ResponsibleOption::displayName)
                    .findFirst()
                    .orElse(null);
            Optional<InteractionIssue> issue = issues.items().stream().filter(item -> item.id().equals(plan.issueId())).findFirst();
            issueDescription = issue.map(InteractionIssue::description).orElse(null);
            issueStatus = issue.map(InteractionIssue::status).orElse(null);
        }
        // Факты рисует CRM из истории со своими датами и авторами: текст агента фактом не считается.
        List<Evidence> evidence = plan.evidenceEventIds().isEmpty() ? List.of() : interactionService.events(profile, plan.interactionId())
                .stream()
                .filter(event -> plan.evidenceEventIds().contains(event.id()))
                .map(event -> new Evidence(event.id(), event.occurredAt(), event.type().name(), event.actorDisplayName(),
                        event.comment()))
                .toList();
        InteractionNextStep resumeStep = plan.resumeOn() == null ? null : InteractionService.resumeStep(plan.resumeOn());
        return new AgentPlanScreen(
                plan.id(), plan.state(), plan.kind(), plan.clientId(),
                organization.id(), organization.name(), interaction.id(), interaction.title(),
                current.map(InteractionStage::name).orElse(null), position(stages, plan.stageId()), stages.size(),
                target.map(InteractionStage::name).orElse(null), plan.toStageId() == null ? null : position(stages, plan.toStageId()),
                interaction.version() != plan.expectedVersion(),
                interaction.nextAction(), interaction.nextActionAt(),
                plan.note(), plan.nextAction(), plan.nextActionAt(),
                interaction.marks() == null ? null : interaction.marks().status(), plan.workStatus(), plan.resumeOn(),
                resumeStep == null ? null : resumeStep.nextAction(), resumeStep == null ? null : resumeStep.nextActionAt(),
                plan.issueKind(), plan.riskLevel(), responsibleName, plan.dueOn(), issueDescription, issueStatus,
                evidence, warnings(plan, interaction, OffsetDateTime.now()),
                plan.createdAt(), plan.expiresAt(), plan.decidedAt(), plan.errorCode()
        );
    }

    // Список своих планов для экрана #/agent-plans и колокольчика. Истёкший, но ещё не закрытый план показывается
    // истёкшим, план по изменённой карточке — устаревшим (STALE) и в колокольчик не попадает; вуз, который сотрудник больше не видит, в список не попадает.
    public List<AgentPlanListItem> list(CrmProfile profile, Kind kind, boolean pendingOnly) {
        OffsetDateTime now = OffsetDateTime.now();
        String sql = """
                SELECT p.id, p.kind, p.state, p.client_id, p.created_at, p.expires_at, p.interaction_id, i.title AS interaction_title,
                       i.organization_id, o.name AS organization_name, p.expected_version, i.version AS interaction_version
                FROM agent_change_plans p
                JOIN interactions i ON i.id = p.interaction_id
                JOIN organizations o ON o.id = i.organization_id
                WHERE p.requester_profile_id = :profile
                """
                + (kind == null ? "" : " AND p.kind = :kind")
                + (pendingOnly ? " AND p.state = 'PENDING' AND p.expires_at > :now AND i.version = p.expected_version" : "")
                + " ORDER BY p.created_at DESC, p.id DESC LIMIT " + LIST_LIMIT;
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("profile", profile.id());
        if (kind != null) {
            statement = statement.param("kind", kind.name());
        }
        if (pendingOnly) {
            statement = statement.param("now", now);
        }
        List<AgentPlanListItem> rows = statement.query((row, number) -> {
            AgentPlan.State state = AgentPlan.State.valueOf(row.getString("state"));
            OffsetDateTime expiresAt = row.getObject("expires_at", OffsetDateTime.class);
            return new AgentPlanListItem(
                    row.getObject("id", UUID.class),
                    Kind.valueOf(row.getString("kind")),
                    state != AgentPlan.State.PENDING ? state
                            : !expiresAt.isAfter(now) ? AgentPlan.State.EXPIRED
                            : row.getInt("interaction_version") != row.getInt("expected_version") ? AgentPlan.State.STALE : state,
                    row.getString("client_id"),
                    row.getObject("organization_id", UUID.class),
                    row.getString("organization_name"),
                    row.getObject("interaction_id", UUID.class),
                    row.getString("interaction_title"),
                    row.getObject("created_at", OffsetDateTime.class),
                    expiresAt
            );
        }).list();
        Map<UUID, Boolean> visible = new HashMap<>();
        return rows.stream()
                .filter(item -> visible.computeIfAbsent(item.organizationId(),
                        id -> organizationRepository.findVisibleById(profile, id).isPresent()))
                .toList();
    }

    // Без внешней транзакции. Команда и отметка APPLIED идут одной транзакцией; если доменная команда падает,
    // Spring помечает её «только откат», поэтому STALE или FAILED пишутся уже после отката, отдельной транзакцией.
    // Строку плана во внешней транзакции не блокируем: отдельная транзакция ждала бы эту блокировку вечно.
    public AgentPlan apply(CrmProfile profile, UUID planId, String requestId) {
        // Без staleIfChanged: исход записи (STALE или FAILED с кодом) определяет сама команда.
        AgentPlan plan = expireIfDue(requireOwn(profile, planId), profile, requestId);
        if (plan.state() != AgentPlan.State.PENDING) {
            return plan;
        }
        try {
            separateTransaction.executeWithoutResult(status -> execute(profile, plan, requestId));
        } catch (InteractionConflictException exception) {
            boolean stale = "VERSION_CONFLICT".equals(exception.code());
            return close(plan, stale ? AgentPlan.State.STALE : AgentPlan.State.FAILED, exception.code(), profile, requestId);
        } catch (RuntimeException exception) {
            return close(plan, AgentPlan.State.FAILED, errorCode(exception), profile, requestId);
        }
        return get(profile, planId, requestId);
    }

    public AgentPlan reject(CrmProfile profile, UUID planId, String requestId) {
        AgentPlan plan = get(profile, planId, requestId);
        return plan.state() == AgentPlan.State.PENDING ? close(plan, AgentPlan.State.REJECTED, null, profile, requestId) : plan;
    }

    private void execute(CrmProfile profile, AgentPlan plan, String requestId) {
        // Блокировка строки — внутри транзакции исполнения: второе одновременное «Записать» дождётся первого и увидит
        // уже не PENDING. Ключ повтора от planId — вторая защита от дубля, если план всё же исполнят дважды.
        boolean pending = jdbc.sql("""
                        SELECT id FROM agent_change_plans
                        WHERE id = :id AND state = 'PENDING' AND expires_at > :now
                        FOR UPDATE
                        """)
                .param("id", plan.id())
                .param("now", OffsetDateTime.now())
                .query(UUID.class)
                .optional()
                .isPresent();
        if (!pending) {
            return;
        }
        String key = "agent-plan:" + plan.id();
        int version = plan.expectedVersion();
        UUID interactionId = plan.interactionId();
        // Команда исполняется от имени подтвердившего: её собственные проверки прав, маршрута, договора и
        // ответственного повторяются сейчас, а не берутся из момента подготовки.
        UUID commandId = switch (plan.kind()) {
            case NOTE_UPDATE -> {
                // Шаг передаётся целиком: команда считает присланный nextStep заданным полностью, неполный стёр бы срок.
                InteractionCommentResult result = interactionService.comment(profile, interactionId,
                        new InteractionCommentRequest(version, plan.stageId(), plan.note(), List.of(),
                                new InteractionNextStep(plan.nextAction(), plan.nextActionAt())), key);
                yield result.event().commandId();
            }
            case STAGE_TRANSITION -> {
                interactionService.transition(profile, interactionId, new InteractionTransitionRequest(version, plan.toStageId(),
                        plan.note(), List.of(), plan.nextAction() == null ? null
                                : new InteractionNextStep(plan.nextAction(), plan.nextActionAt())), key);
                yield command(profile, CommandOperation.TRANSITION_INTERACTION, key);
            }
            case ISSUE_CREATE -> {
                interactionIssueService.create(profile, interactionId, new InteractionIssueRequest(version, plan.issueKind(),
                        plan.note(), plan.riskLevel(), plan.responsibleId(), plan.dueOn()), key);
                yield command(profile, CommandOperation.CREATE_INTERACTION_ISSUE, key);
            }
            case ISSUE_RESOLVE -> {
                interactionIssueService.resolve(profile, interactionId, plan.issueId(),
                        new InteractionIssueResolution(version, plan.note()), key);
                yield command(profile, CommandOperation.RESOLVE_INTERACTION_ISSUE, key);
            }
            case STATUS_CHANGE -> {
                interactionService.changeStatus(profile, interactionId,
                        new InteractionStatusRequest(version, plan.workStatus(), plan.note(), plan.resumeOn()), key);
                yield command(profile, CommandOperation.CHANGE_INTERACTION_STATUS, key);
            }
        };
        jdbc.sql("""
                UPDATE agent_change_plans
                SET state = 'APPLIED', decided_by = :by, decided_at = :now, command_id = :commandId
                WHERE id = :id
                """)
                .param("by", profile.id())
                .param("now", OffsetDateTime.now())
                .param("commandId", commandId)
                .param("id", plan.id())
                .update();
        audit(AuditAction.AGENT_PLAN_APPLIED, profile, plan, requestId);
    }

    private UUID command(CrmProfile profile, CommandOperation operation, String key) {
        return commandIdempotencyRepository.find(profile.id(), operation, key)
                .map(CommandIdempotencyRepository.CommandRecord::id)
                .orElseThrow(() -> new IllegalStateException("Agent plan command was not recorded"));
    }

    // Те же проверки, что у действий карточки: изменять может КАМ или руководитель, и только видимое ему
    // взаимодействие (свой вуз, своя команда). Чужая карточка выглядит как отсутствующая.
    private Interaction editable(CrmProfile profile, UUID interactionId) {
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        return interactionService.get(profile, interactionId);
    }

    // Этап-цель — только из разрешённых маршрутом переходов из текущего этапа: по идентификатору или названию.
    private static InteractionTransitionOption allowedTarget(Interaction interaction, String toStage) {
        if (toStage == null || toStage.isBlank()) {
            throw new InteractionValidationException("toStage", "Укажите этап из nextStages");
        }
        String wanted = toStage.strip();
        return interaction.allowedTransitions().stream()
                .filter(option -> option.stageId().toString().equalsIgnoreCase(wanted) || option.stageName().equalsIgnoreCase(wanted))
                .findFirst()
                .orElseThrow(() -> new InteractionValidationException("toStage", "Переход на этап «" + wanted
                        + "» из текущего маршрутом не разрешён. Доступны: " + interaction.allowedTransitions().stream()
                        .map(InteractionTransitionOption::stageName).collect(Collectors.joining(", "))));
    }

    private static void nextStep(Draft draft, String nextAction, OffsetDateTime nextActionAt) {
        if (nextAction == null && nextActionAt == null) {
            return;
        }
        if (nextAction == null || nextActionAt == null) {
            throw new InteractionValidationException(nextAction == null ? "nextAction" : "nextActionAt",
                    "Шаг и срок передаются оба или ни одного");
        }
        draft.step(required(nextAction, "nextAction", 500), nextActionAt);
    }

    private static void requireStatus(Interaction interaction, InteractionWorkStatus expected, String message) {
        InteractionWorkStatus status = interaction.marks() == null || interaction.marks().status() == null
                ? InteractionWorkStatus.ACTIVE : interaction.marks().status();
        if (status != expected) {
            throw new InteractionValidationException("status", message);
        }
    }

    private Prepared prepared(AgentPlan plan, Interaction interaction) {
        return new Prepared(plan, warnings(plan, interaction, OffsetDateTime.now()), 0);
    }

    // Предупреждения экрана: возврат, переход через этап и срок в прошлом. Решение всё равно за сотрудником.
    static List<String> warnings(AgentPlan plan, Interaction interaction, OffsetDateTime now) {
        List<String> warnings = new ArrayList<>();
        if (plan.kind() == Kind.STAGE_TRANSITION) {
            List<InteractionStage> stages = interaction.stages();
            Integer from = order(stages, plan.stageId());
            Integer to = order(stages, plan.toStageId());
            if (from != null && to != null) {
                if (to < from) {
                    warnings.add("BACKWARD");
                } else if (stages.stream().anyMatch(stage -> stage.order() > from && stage.order() < to && !stage.optional())) {
                    warnings.add("SKIP");
                }
            }
        }
        LocalDate today = now.atZoneSameInstant(InteractionIssueService.ZONE).toLocalDate();
        if ((plan.nextActionAt() != null && plan.nextActionAt().isBefore(now)) || (plan.dueOn() != null && plan.dueOn().isBefore(today))) {
            warnings.add("PAST_DUE");
        }
        return warnings;
    }

    private static Integer order(List<InteractionStage> stages, UUID stageId) {
        return stage(stages, stageId).map(InteractionStage::order).orElse(null);
    }

    private static Optional<InteractionStage> stage(List<InteractionStage> stages, UUID stageId) {
        return stages.stream().filter(stage -> stage.id().equals(stageId)).findFirst();
    }

    private static Integer position(List<InteractionStage> stages, UUID stageId) {
        int index = stages.stream().map(InteractionStage::id).toList().indexOf(stageId);
        return index < 0 ? null : index + 1;
    }

    private static LocalDate today() {
        return LocalDate.now(InteractionIssueService.ZONE);
    }

    private AgentPlan insert(CrmProfile profile, String clientId, Interaction interaction, Draft draft, String requestId) {
        OffsetDateTime now = OffsetDateTime.now();
        requireQuota(profile, now);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO agent_change_plans (
                    id, requester_profile_id, client_id, interaction_id, stage_id, expected_version, note_text, next_action,
                    next_action_at, state, created_at, expires_at, kind, to_stage_id, issue_id, issue_kind, risk_level,
                    responsible_profile_id, due_on, work_status, resume_on, evidence_event_ids
                ) VALUES (
                    :id, :requester, :clientId, :interactionId, :stageId, :version, :note, :nextAction,
                    :nextActionAt, 'PENDING', :now, :expiresAt, :kind, :toStageId, :issueId, :issueKind, :riskLevel,
                    :responsibleId, :dueOn, :workStatus, :resumeOn, :evidence
                )
                """)
                .param("id", id)
                .param("requester", profile.id())
                .param("clientId", clientId, Types.VARCHAR)
                .param("interactionId", interaction.id())
                .param("stageId", interaction.currentStageId())
                .param("version", interaction.version())
                .param("note", draft.note, Types.VARCHAR)
                .param("nextAction", draft.nextAction, Types.VARCHAR)
                .param("nextActionAt", draft.nextActionAt)
                .param("now", now)
                .param("expiresAt", now.plus(properties.planTtl()))
                .param("kind", draft.kind.name())
                .param("toStageId", draft.toStageId)
                .param("issueId", draft.issueId)
                .param("issueKind", draft.issueKind == null ? null : draft.issueKind.name(), Types.VARCHAR)
                .param("riskLevel", draft.riskLevel == null ? null : draft.riskLevel.name(), Types.VARCHAR)
                .param("responsibleId", draft.responsibleId)
                .param("dueOn", draft.dueOn)
                .param("workStatus", draft.workStatus == null ? null : draft.workStatus.name(), Types.VARCHAR)
                .param("resumeOn", draft.resumeOn)
                .param("evidence", draft.evidence.isEmpty() ? null
                        : draft.evidence.stream().map(UUID::toString).collect(Collectors.joining(",")), Types.VARCHAR)
                .update();
        AgentPlan plan = find(id).orElseThrow();
        audit(AuditAction.AGENT_PLAN_PREPARED, profile, plan, requestId);
        return plan;
    }

    private AgentPlan expireIfDue(AgentPlan plan, CrmProfile profile, String requestId) {
        return plan.state() == AgentPlan.State.PENDING && !plan.expiresAt().isAfter(OffsetDateTime.now())
                ? close(plan, AgentPlan.State.EXPIRED, null, profile, requestId)
                : plan;
    }

    // Итог без исполнения пишется условно (только из PENDING), отдельной транзакцией: так он сохраняется и после
    // отката команды, а гонку с параллельным «Записать» выигрывает тот, кто первым сменил состояние.
    private AgentPlan close(AgentPlan plan, AgentPlan.State state, String errorCode, CrmProfile profile, String requestId) {
        separateTransaction.executeWithoutResult(status -> {
            // Истечение никто не решал, поэтому decided_by у EXPIRED остаётся пустым.
            int updated = jdbc.sql("UPDATE agent_change_plans SET state = :state, error_code = :errorCode, decided_at = :now"
                            + (state == AgentPlan.State.EXPIRED ? "" : ", decided_by = :by")
                            + " WHERE id = :id AND state = 'PENDING'")
                    .param("state", state.name())
                    .param("errorCode", errorCode, Types.VARCHAR)
                    .param("now", OffsetDateTime.now())
                    .param("by", profile.id())
                    .param("id", plan.id())
                    .update();
            if (updated == 1) {
                audit(switch (state) {
                    case REJECTED -> AuditAction.AGENT_PLAN_REJECTED;
                    case EXPIRED -> AuditAction.AGENT_PLAN_EXPIRED;
                    case STALE -> AuditAction.AGENT_PLAN_STALE;
                    default -> AuditAction.AGENT_PLAN_FAILED;
                }, profile, plan, requestId, errorCode);
            }
        });
        return find(plan.id()).orElseThrow();
    }

    // ponytail: проверка квоты без блокировки — два одновременных запроса могут превысить её на один план;
    // строгий предел — блокировкой профиля, если это понадобится. Квота общая для всех видов плана.
    private void requireQuota(CrmProfile profile, OffsetDateTime now) {
        long pending = jdbc.sql("""
                        SELECT COUNT(*) FROM agent_change_plans
                        WHERE requester_profile_id = :profile AND state = 'PENDING' AND expires_at > :now
                        """)
                .param("profile", profile.id())
                .param("now", now)
                .query(Long.class)
                .single();
        if (pending >= properties.planPendingLimit()) {
            throw new AgentPlanQuotaException("Уже есть " + pending + " планов, ждущих подтверждения в CRM: "
                    + "подтвердите или отклоните их");
        }
        long daily = jdbc.sql("""
                        SELECT COUNT(*) FROM agent_change_plans
                        WHERE requester_profile_id = :profile AND created_at > :since
                        """)
                .param("profile", profile.id())
                .param("since", now.minusDays(1))
                .query(Long.class)
                .single();
        if (daily >= properties.planDailyLimit()) {
            throw new AgentPlanQuotaException("За сутки подготовлено " + daily + " планов: больше нельзя");
        }
    }

    // Чужой план и несуществующий неразличимы: существование чужого плана не раскрывается.
    private AgentPlan requireOwn(CrmProfile profile, UUID planId) {
        return find(planId)
                .filter(plan -> plan.requesterProfileId().equals(profile.id()))
                .orElseThrow(AgentPlanNotFoundException::new);
    }

    private Optional<AgentPlan> find(UUID planId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent_change_plans WHERE id = :id")
                .param("id", planId)
                .query(AgentPlanService::plan)
                .optional();
    }

    // В журнале — кто, какой план, вид и клиент, без текста плана: в нём могут быть ПДн.
    private void audit(AuditAction action, CrmProfile profile, AgentPlan plan, String requestId) {
        audit(action, profile, plan, requestId, null);
    }

    private void audit(AuditAction action, CrmProfile profile, AgentPlan plan, String requestId, String errorCode) {
        auditJournalRepository.record(
                action,
                profile.id(),
                "INTERACTION",
                plan.interactionId(),
                "план " + plan.id(),
                "вид: " + plan.kind()
                        + "; клиент: " + (plan.clientId() == null ? "не указан" : plan.clientId())
                        + "; версия карточки: " + plan.expectedVersion()
                        + (errorCode == null ? "" : "; причина: " + errorCode),
                requestId
        );
    }

    private static String errorCode(RuntimeException exception) {
        if (exception instanceof InteractionValidationException) {
            return "VALIDATION_FAILED";
        }
        String name = exception.getClass().getSimpleName();
        if (name.endsWith("NotFoundException")) {
            return "OBJECT_UNAVAILABLE";
        }
        if (name.contains("AccessDenied")) {
            return "AUTHORIZATION_DENIED";
        }
        log.error("Agent change plan command failed", exception);
        return "COMMAND_FAILED";
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException(field, "Заполните значение");
        }
        String text = value.strip();
        if (text.length() > maxLength) {
            throw new InteractionValidationException(field, "Не длиннее " + maxLength + " символов");
        }
        return text;
    }

    private static AgentPlan plan(ResultSet row, int number) throws SQLException {
        String issueKind = row.getString("issue_kind");
        String riskLevel = row.getString("risk_level");
        String workStatus = row.getString("work_status");
        String evidence = row.getString("evidence_event_ids");
        return new AgentPlan(
                row.getObject("id", UUID.class),
                row.getObject("requester_profile_id", UUID.class),
                row.getString("client_id"),
                row.getObject("interaction_id", UUID.class),
                row.getObject("stage_id", UUID.class),
                row.getInt("expected_version"),
                row.getString("note_text"),
                row.getString("next_action"),
                row.getObject("next_action_at", OffsetDateTime.class),
                AgentPlan.State.valueOf(row.getString("state")),
                row.getString("error_code"),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("expires_at", OffsetDateTime.class),
                row.getObject("decided_by", UUID.class),
                row.getObject("decided_at", OffsetDateTime.class),
                row.getObject("command_id", UUID.class),
                Kind.valueOf(row.getString("kind")),
                row.getObject("to_stage_id", UUID.class),
                row.getObject("issue_id", UUID.class),
                issueKind == null ? null : InteractionIssueKind.valueOf(issueKind),
                riskLevel == null ? null : InteractionRiskLevel.valueOf(riskLevel),
                row.getObject("responsible_profile_id", UUID.class),
                row.getObject("due_on", LocalDate.class),
                workStatus == null ? null : InteractionWorkStatus.valueOf(workStatus),
                row.getObject("resume_on", LocalDate.class),
                evidence == null ? List.of() : Arrays.stream(evidence.split(",")).map(UUID::fromString).toList()
        );
    }

    public enum Kind { NOTE_UPDATE, STAGE_TRANSITION, ISSUE_CREATE, ISSUE_RESOLVE, STATUS_CHANGE }

    // Поля будущего плана; у каждого вида свои обязательные, их же требует проверка таблицы (V57).
    private static final class Draft {
        private final Kind kind;
        private final String note;
        private String nextAction;
        private OffsetDateTime nextActionAt;
        private UUID toStageId;
        private UUID issueId;
        private InteractionIssueKind issueKind;
        private InteractionRiskLevel riskLevel;
        private UUID responsibleId;
        private LocalDate dueOn;
        private InteractionWorkStatus workStatus;
        private LocalDate resumeOn;
        private List<UUID> evidence = List.of();

        private Draft(Kind kind, String note) {
            this.kind = kind;
            this.note = note;
        }

        static Draft of(Kind kind, String note) {
            return new Draft(kind, note);
        }

        Draft step(String action, OffsetDateTime at) {
            this.nextAction = action;
            this.nextActionAt = at;
            return this;
        }
    }

    public record AgentPlan(
            UUID id,
            UUID requesterProfileId,
            String clientId,
            UUID interactionId,
            UUID stageId,
            int expectedVersion,
            String note,
            String nextAction,
            OffsetDateTime nextActionAt,
            State state,
            String errorCode,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            UUID decidedBy,
            OffsetDateTime decidedAt,
            UUID commandId,
            Kind kind,
            UUID toStageId,
            UUID issueId,
            InteractionIssueKind issueKind,
            InteractionRiskLevel riskLevel,
            UUID responsibleId,
            LocalDate dueOn,
            InteractionWorkStatus workStatus,
            LocalDate resumeOn,
            List<UUID> evidenceEventIds
    ) {
        public enum State { PENDING, APPLIED, REJECTED, EXPIRED, STALE, FAILED }
    }

    // droppedEvidence — сколько присланных фактов отброшено: событий с такими идентификаторами в карточке нет.
    public record Prepared(AgentPlan plan, List<String> warnings, int droppedEvidence) {
    }

    // Экран «было / станет»: текущее состояние карточки рядом с тем, что предлагает агент, по виду плана.
    public record AgentPlanScreen(
            UUID id,
            AgentPlan.State status,
            Kind kind,
            String clientId,
            UUID organizationId,
            String organizationName,
            UUID interactionId,
            String interactionTitle,
            String stageName,
            Integer stagePosition,
            int stageTotal,
            String toStageName,
            Integer toStagePosition,
            boolean cardChanged,
            String currentNextAction,
            OffsetDateTime currentNextActionAt,
            String note,
            String nextAction,
            OffsetDateTime nextActionAt,
            InteractionWorkStatus currentStatus,
            InteractionWorkStatus workStatus,
            LocalDate resumeOn,
            String resumeAction,
            OffsetDateTime resumeActionAt,
            InteractionIssueKind issueKind,
            InteractionRiskLevel riskLevel,
            String responsibleName,
            LocalDate dueOn,
            String issueDescription,
            InteractionIssueStatus issueStatus,
            List<Evidence> evidence,
            List<String> warnings,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            OffsetDateTime decidedAt,
            String errorCode
    ) {
    }

    public record Evidence(UUID id, OffsetDateTime occurredAt, String type, String actorName, String comment) {
    }

    public record AgentPlanListItem(
            UUID id,
            Kind kind,
            AgentPlan.State status,
            String clientId,
            UUID organizationId,
            String organizationName,
            UUID interactionId,
            String interactionTitle,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt
    ) {
    }

    public static class AgentPlanNotFoundException extends RuntimeException {
    }

    public static class AgentPlanQuotaException extends RuntimeException {
        AgentPlanQuotaException(String message) {
            super(message);
        }
    }
}
