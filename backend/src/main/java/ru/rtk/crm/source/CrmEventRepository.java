package ru.rtk.crm.source;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CrmEventRepository {
    // Заказчику уходят события только по вузам, колледжам и школам и не по архивным: служебная организация оплат и
    // закрытые организации наружу не выходят.
    private static final String ORGANIZATION_SCOPE = "o.type IN ('UNIVERSITY', 'COLLEGE', 'SCHOOL') AND o.status <> 'ARCHIVED'";
    private static final String EVENT_SELECT = """
            SELECT id, kind, status, occurred_at, payload, attempts, next_attempt_at, last_error, sent_at
            FROM crm_exchange_events
            """;

    private final JdbcClient jdbcClient;

    public CrmEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // Окно сбора перекрывается, поэтому одно и то же событие истории встречается снова: по event_key повтор отсекается.
    // Одновременно идёт один запуск источника, поэтому проверка и вставка не гонятся.
    boolean insertIfAbsent(String eventKey, CrmEventKind kind, UUID organizationId, UUID interactionId, OffsetDateTime occurredAt,
                           String payload, OffsetDateTime now) {
        if (jdbcClient.sql("SELECT COUNT(*) FROM crm_exchange_events WHERE event_key = :eventKey")
                .param("eventKey", eventKey)
                .query(Long.class)
                .single() > 0) {
            return false;
        }
        jdbcClient.sql("""
                INSERT INTO crm_exchange_events (
                    id, event_key, kind, organization_id, interaction_id, occurred_at, payload, status, attempts,
                    next_attempt_at, created_at
                ) VALUES (
                    :id, :eventKey, :kind, :organizationId, :interactionId, :occurredAt, :payload, 'QUEUED', 0, :dueAt, :now
                )
                """)
                // Срок первой попытки — сейчас, округлённый вниз до секунд: округление в базе не должно сдвинуть его в
                // будущее, и событие уйдёт в этом же запуске.
                .param("dueAt", now.truncatedTo(ChronoUnit.SECONDS))
                .param("id", UUID.randomUUID())
                .param("eventKey", eventKey)
                .param("kind", kind.name())
                .param("organizationId", organizationId)
                .param("interactionId", interactionId)
                .param("occurredAt", occurredAt)
                .param("payload", payload)
                .param("now", now)
                .update();
        return true;
    }

    Optional<OffsetDateTime> findLatestOccurredAt(CrmEventKind kind) {
        return jdbcClient.sql("SELECT MAX(occurred_at) FROM crm_exchange_events WHERE kind = :kind")
                .param("kind", kind.name())
                .query((resultSet, rowNumber) -> resultSet.getObject(1, OffsetDateTime.class))
                .optional();
    }

    // Пачки идут в порядке возникновения событий: заказчик видит смены этапов в той же последовательности.
    List<StoredCrmEvent> findDue(OffsetDateTime now, int limit) {
        return jdbcClient.sql(EVENT_SELECT + """
                WHERE status = 'QUEUED' AND next_attempt_at <= :now
                ORDER BY occurred_at, id
                LIMIT :limit
                """)
                .param("now", now)
                .param("limit", limit)
                .query(this::mapEvent)
                .list();
    }

    List<StoredCrmEvent> findRecent(CrmEventStatus status, int limit) {
        return jdbcClient.sql(EVENT_SELECT + (status == null ? "" : "WHERE status = :status ") + """
                ORDER BY occurred_at DESC, id
                LIMIT :limit
                """)
                .params(status == null ? Map.of() : Map.of("status", status.name()))
                .param("limit", limit)
                .query(this::mapEvent)
                .list();
    }

    CrmEventCounts counts() {
        return jdbcClient.sql("""
                SELECT COALESCE(SUM(CASE WHEN status = 'QUEUED' THEN 1 ELSE 0 END), 0) AS queued,
                       COALESCE(SUM(CASE WHEN status = 'SENT' THEN 1 ELSE 0 END), 0) AS sent,
                       COALESCE(SUM(CASE WHEN status = 'ERROR' THEN 1 ELSE 0 END), 0) AS error
                FROM crm_exchange_events
                """)
                .query((resultSet, rowNumber) -> new CrmEventCounts(
                        resultSet.getLong("queued"), resultSet.getLong("sent"), resultSet.getLong("error")))
                .single();
    }

    void markSent(UUID id, UUID batchId, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE crm_exchange_events
                SET status = 'SENT', attempts = attempts + 1, batch_id = :batchId, sent_at = :now, last_error = NULL
                WHERE id = :id
                """)
                .param("id", id)
                .param("batchId", batchId)
                .param("now", now)
                .update();
    }

    // Отклонённое заказчиком событие сразу получает ERROR: паузы и повторы ему не положены.
    void markRejected(UUID id, UUID batchId, String reason) {
        jdbcClient.sql("""
                UPDATE crm_exchange_events
                SET status = 'ERROR', attempts = attempts + 1, batch_id = :batchId, last_error = :reason
                WHERE id = :id
                """)
                .param("id", id)
                .param("batchId", batchId)
                .param("reason", limit(reason))
                .update();
    }

    // exhausted — попытки исчерпаны: событие уходит в ERROR, иначе остаётся в очереди со сдвинутым сроком.
    void markFailed(UUID id, String error, boolean exhausted, OffsetDateTime nextAttemptAt) {
        jdbcClient.sql("""
                UPDATE crm_exchange_events
                SET status = :status, attempts = attempts + 1, last_error = :error, next_attempt_at = :nextAttemptAt
                WHERE id = :id
                """)
                .param("id", id)
                .param("status", exhausted ? CrmEventStatus.ERROR.name() : CrmEventStatus.QUEUED.name())
                .param("error", limit(error))
                .param("nextAttemptAt", nextAttemptAt)
                .update();
    }

    // Ручной возврат: счётчик попыток обнуляется, событие снова проходит весь цикл пауз.
    int requeueErrors(OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE crm_exchange_events
                SET status = 'QUEUED', attempts = 0, next_attempt_at = :now
                WHERE status = 'ERROR'
                """)
                .param("now", now.truncatedTo(ChronoUnit.SECONDS))
                .update();
    }

    List<WorkEventRow> findStageEvents(OffsetDateTime since, int limit) {
        return findWorkEvents("e.type IN ('CREATED', 'TRANSITIONED')", since, limit);
    }

    List<WorkEventRow> findPlanEvents(OffsetDateTime since, int limit) {
        return findWorkEvents("e.plan_changed = TRUE", since, limit);
    }

    List<AssignmentRow> findAssignmentEvents(OffsetDateTime since, int limit) {
        return jdbcClient.sql("""
                SELECT a.id, a.occurred_at, a.previous_owner_manager_id, a.previous_owner_manager_display_name,
                       a.owner_manager_id, a.new_owner_manager_display_name, o.id AS organization_id,
                       o.name AS organization_name, o.inn
                FROM organization_assignment_events a
                JOIN organizations o ON o.id = a.organization_id
                WHERE a.occurred_at >= :since AND %s
                ORDER BY a.occurred_at, a.id
                LIMIT :limit
                """.formatted(ORGANIZATION_SCOPE))
                .param("since", since)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new AssignmentRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("occurred_at", OffsetDateTime.class),
                        resultSet.getObject("previous_owner_manager_id", UUID.class),
                        resultSet.getString("previous_owner_manager_display_name"),
                        resultSet.getObject("owner_manager_id", UUID.class),
                        resultSet.getString("new_owner_manager_display_name"),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getString("inn")
                ))
                .list();
    }

    List<AgreementRow> findAgreementStatusEvents(OffsetDateTime since, int limit) {
        return jdbcClient.sql("""
                SELECT e.id AS event_id, e.from_status, e.to_status, e.occurred_at, a.id, a.number, a.concluded_on,
                       a.valid_until, o.id AS organization_id, o.name AS organization_name, o.inn, o.owner_manager_id,
                       m.display_name AS manager_name
                FROM agreement_status_events e
                JOIN agreements a ON a.id = e.agreement_id
                JOIN organizations o ON o.id = a.organization_id
                LEFT JOIN crm_user_profiles m ON m.id = o.owner_manager_id
                WHERE e.occurred_at >= :since AND %s
                ORDER BY e.occurred_at, e.id
                LIMIT :limit
                """.formatted(ORGANIZATION_SCOPE))
                .param("since", since)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new AgreementRow(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("number"),
                        resultSet.getString("from_status"),
                        resultSet.getString("to_status"),
                        resultSet.getObject("concluded_on", LocalDate.class),
                        resultSet.getObject("valid_until", LocalDate.class),
                        resultSet.getObject("occurred_at", OffsetDateTime.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getString("inn"),
                        resultSet.getObject("owner_manager_id", UUID.class),
                        resultSet.getString("manager_name")
                ))
                .list();
    }

    // Берётся самый ранний ключ сопоставления вида id:…, чтобы внешний ID организации в событиях не менялся при
    // появлении новых сопоставлений.
    Optional<String> findCrmOrganizationKey(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT external_key FROM source_mappings
                WHERE source = 'CUSTOMER_CRM' AND kind = 'ORGANIZATION' AND organization_id = :organizationId
                  AND external_key LIKE 'id:%'
                ORDER BY created_at, id
                LIMIT 1
                """)
                .param("organizationId", organizationId)
                .query(String.class)
                .optional();
    }

    // Этапы и ответственный берутся из снимков события, а не из текущих данных: переименование этапа или смена
    // ответственного историю не переписывает. Если to_stage пуст, берётся stage_name_snapshot. Граница since
    // включительная: повтор в окне допустим, дубли отсекает ключ события.
    private List<WorkEventRow> findWorkEvents(String condition, OffsetDateTime since, int limit) {
        return jdbcClient.sql("""
                SELECT e.id, e.interaction_id, e.occurred_at, e.from_stage_name_snapshot,
                       COALESCE(e.to_stage_name_snapshot, e.stage_name_snapshot) AS to_stage_name, e.next_action,
                       e.next_action_at, i.title, pr.name AS program_name, o.id AS organization_id,
                       o.name AS organization_name, o.inn, e.owner_manager_id_snapshot, m.display_name AS manager_name
                FROM interaction_events e
                JOIN interactions i ON i.id = e.interaction_id
                JOIN organizations o ON o.id = i.organization_id
                LEFT JOIN programs pr ON pr.id = i.program_id
                LEFT JOIN crm_user_profiles m ON m.id = e.owner_manager_id_snapshot
                WHERE %s AND e.occurred_at >= :since AND %s
                ORDER BY e.occurred_at, e.id
                LIMIT :limit
                """.formatted(condition, ORGANIZATION_SCOPE))
                .param("since", since)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new WorkEventRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getObject("occurred_at", OffsetDateTime.class),
                        resultSet.getString("from_stage_name_snapshot"),
                        resultSet.getString("to_stage_name"),
                        resultSet.getString("next_action"),
                        resultSet.getObject("next_action_at", OffsetDateTime.class),
                        resultSet.getString("title"),
                        resultSet.getString("program_name"),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getString("inn"),
                        resultSet.getObject("owner_manager_id_snapshot", UUID.class),
                        resultSet.getString("manager_name")
                ))
                .list();
    }

    private StoredCrmEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredCrmEvent(
                resultSet.getObject("id", UUID.class),
                CrmEventKind.valueOf(resultSet.getString("kind")),
                CrmEventStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("occurred_at", OffsetDateTime.class),
                resultSet.getString("payload"),
                resultSet.getInt("attempts"),
                resultSet.getObject("next_attempt_at", OffsetDateTime.class),
                resultSet.getString("last_error"),
                resultSet.getObject("sent_at", OffsetDateTime.class)
        );
    }

    private static String limit(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 499) + "…";
    }

    record StoredCrmEvent(
            UUID id,
            CrmEventKind kind,
            CrmEventStatus status,
            OffsetDateTime occurredAt,
            String payload,
            int attempts,
            OffsetDateTime nextAttemptAt,
            String lastError,
            OffsetDateTime sentAt
    ) {
    }

    record WorkEventRow(
            UUID eventId,
            UUID interactionId,
            OffsetDateTime occurredAt,
            String fromStage,
            String toStage,
            String nextAction,
            OffsetDateTime nextActionAt,
            String workTitle,
            String programName,
            UUID organizationId,
            String organizationName,
            String organizationInn,
            UUID managerId,
            String managerName
    ) {
    }

    record AssignmentRow(
            UUID eventId,
            OffsetDateTime occurredAt,
            UUID previousManagerId,
            String previousManagerName,
            UUID managerId,
            String managerName,
            UUID organizationId,
            String organizationName,
            String organizationInn
    ) {
    }

    record AgreementRow(
            UUID eventId,
            UUID id,
            String number,
            String previousStatus,
            String status,
            LocalDate concludedOn,
            LocalDate validUntil,
            OffsetDateTime occurredAt,
            UUID organizationId,
            String organizationName,
            String organizationInn,
            UUID managerId,
            String managerName
    ) {
    }
}
