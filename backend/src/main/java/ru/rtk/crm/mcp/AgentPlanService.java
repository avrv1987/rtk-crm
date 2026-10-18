package ru.rtk.crm.mcp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
import ru.rtk.crm.interaction.Interaction;
import ru.rtk.crm.interaction.InteractionCommentRequest;
import ru.rtk.crm.interaction.InteractionCommentResult;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionNextStep;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionStage;
import ru.rtk.crm.interaction.InteractionValidationException;

// Запись через ИИ-агента: агент только сохраняет план, а исполняет его CRM по кнопке «Записать» сотрудника, для
// которого план создан. Права, версия карточки и ключ повтора — те же, что у комментария из интерфейса.
@Service
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public class AgentPlanService {
    private static final Logger log = LoggerFactory.getLogger(AgentPlanService.class);
    private static final String COLUMNS = """
            id, requester_profile_id, client_id, interaction_id, stage_id, expected_version, note_text, next_action,
            next_action_at, state, error_code, created_at, expires_at, decided_by, decided_at, command_id
            """;

    private final JdbcClient jdbc;
    private final InteractionService interactionService;
    private final OrganizationRepository organizationRepository;
    private final AuditJournalRepository auditJournalRepository;
    private final McpProperties properties;
    private final TransactionTemplate separateTransaction;

    public AgentPlanService(
            JdbcClient jdbc,
            InteractionService interactionService,
            OrganizationRepository organizationRepository,
            AuditJournalRepository auditJournalRepository,
            McpProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.interactionService = interactionService;
        this.organizationRepository = organizationRepository;
        this.auditJournalRepository = auditJournalRepository;
        this.properties = properties;
        this.separateTransaction = new TransactionTemplate(transactionManager);
        this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

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
        // Те же проверки, что у «Записать» из карточки: изменять может КАМ или руководитель, и только видимое ему
        // взаимодействие (свой вуз, своя команда). Чужая карточка выглядит как отсутствующая.
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        Interaction interaction = interactionService.get(profile, interactionId);
        String text = required(note, "note", 4_000);
        String action = required(nextAction, "nextAction", 500);
        if (nextActionAt == null) {
            throw new InteractionValidationException("nextActionAt", "Укажите срок следующего шага");
        }
        OffsetDateTime now = OffsetDateTime.now();
        requireQuota(profile, now);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO agent_change_plans (
                    id, requester_profile_id, client_id, interaction_id, stage_id, expected_version, note_text, next_action,
                    next_action_at, state, created_at, expires_at
                ) VALUES (
                    :id, :requester, :clientId, :interactionId, :stageId, :version, :note, :nextAction,
                    :nextActionAt, 'PENDING', :now, :expiresAt
                )
                """)
                .param("id", id)
                .param("requester", profile.id())
                .param("clientId", clientId, Types.VARCHAR)
                .param("interactionId", interactionId)
                .param("stageId", interaction.currentStageId())
                .param("version", interaction.version())
                .param("note", text)
                .param("nextAction", action)
                .param("nextActionAt", nextActionAt)
                .param("now", now)
                .param("expiresAt", now.plus(properties.planTtl()))
                .update();
        AgentPlan plan = find(id).orElseThrow();
        audit(AuditAction.AGENT_PLAN_PREPARED, profile, plan, requestId);
        return plan;
    }

    public AgentPlan get(CrmProfile profile, UUID planId, String requestId) {
        return expireIfDue(requireOwn(profile, planId), profile, requestId);
    }

    public AgentPlanScreen screen(CrmProfile profile, UUID planId, String requestId) {
        AgentPlan plan = get(profile, planId, requestId);
        Interaction interaction = interactionService.get(profile, plan.interactionId());
        Organization organization = organizationRepository.findVisibleById(profile, interaction.organizationId())
                .orElseThrow(InteractionNotFoundException::new);
        String stageName = interaction.stages().stream()
                .filter(stage -> stage.id().equals(plan.stageId()))
                .map(InteractionStage::name)
                .findFirst()
                .orElse(null);
        return new AgentPlanScreen(
                plan.id(), plan.state(), plan.clientId(),
                organization.id(), organization.name(), interaction.id(), interaction.title(), stageName,
                interaction.version() != plan.expectedVersion(),
                interaction.nextAction(), interaction.nextActionAt(),
                plan.note(), plan.nextAction(), plan.nextActionAt(),
                plan.createdAt(), plan.expiresAt(), plan.decidedAt(), plan.errorCode()
        );
    }

    // Без внешней транзакции. Комментарий и отметка APPLIED идут одной транзакцией; если доменная команда падает,
    // Spring помечает её «только откат», поэтому STALE или FAILED пишутся уже после отката, отдельной транзакцией.
    // Строку плана во внешней транзакции не блокируем: отдельная транзакция ждала бы эту блокировку вечно.
    public AgentPlan apply(CrmProfile profile, UUID planId, String requestId) {
        AgentPlan plan = get(profile, planId, requestId);
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
        // Шаг передаётся целиком: команда считает присланный nextStep заданным полностью, неполный стёр бы срок.
        InteractionCommentResult result = interactionService.comment(
                profile,
                plan.interactionId(),
                new InteractionCommentRequest(plan.expectedVersion(), plan.stageId(), plan.note(), List.of(),
                        new InteractionNextStep(plan.nextAction(), plan.nextActionAt())),
                "agent-plan:" + plan.id()
        );
        jdbc.sql("""
                UPDATE agent_change_plans
                SET state = 'APPLIED', decided_by = :by, decided_at = :now, command_id = :commandId
                WHERE id = :id
                """)
                .param("by", profile.id())
                .param("now", OffsetDateTime.now())
                .param("commandId", result.event().commandId())
                .param("id", plan.id())
                .update();
        audit(AuditAction.AGENT_PLAN_APPLIED, profile, plan, requestId);
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
    // строгий предел — блокировкой профиля, если это понадобится.
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

    // В журнале — кто, какой план и клиент, без текста комментария и шага: в них могут быть ПДн.
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
                "клиент: " + (plan.clientId() == null ? "не указан" : plan.clientId())
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
                row.getObject("command_id", UUID.class)
        );
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
            UUID commandId
    ) {
        public enum State { PENDING, APPLIED, REJECTED, EXPIRED, STALE, FAILED }
    }

    // Экран «было / станет»: текущие шаг и срок карточки рядом с предложенными агентом.
    public record AgentPlanScreen(
            UUID id,
            AgentPlan.State status,
            String clientId,
            UUID organizationId,
            String organizationName,
            UUID interactionId,
            String interactionTitle,
            String stageName,
            boolean cardChanged,
            String currentNextAction,
            OffsetDateTime currentNextActionAt,
            String note,
            String nextAction,
            OffsetDateTime nextActionAt,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            OffsetDateTime decidedAt,
            String errorCode
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
