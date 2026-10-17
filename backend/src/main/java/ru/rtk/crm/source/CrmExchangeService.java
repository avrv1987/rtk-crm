package ru.rtk.crm.source;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.source.CrmClient.CrmDelivery;
import ru.rtk.crm.source.CrmClient.CrmFetch;
import ru.rtk.crm.source.CrmEventRepository.AgreementRow;
import ru.rtk.crm.source.CrmEventRepository.AssignmentRow;
import ru.rtk.crm.source.CrmEventRepository.StoredCrmEvent;
import ru.rtk.crm.source.CrmEventRepository.WorkEventRow;
import ru.rtk.crm.source.SourceRepository.SyncTotals;

@Service
public class CrmExchangeService {
    private static final Logger log = LoggerFactory.getLogger(CrmExchangeService.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter MOSCOW = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Duration EXPORT_WINDOW = Duration.ofDays(30);
    private static final Duration OVERLAP = Duration.ofMinutes(5);
    private static final List<Duration> PAUSES = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1));
    private static final int COLLECT_LIMIT = 1000;
    private static final int BATCH_SIZE = 50;
    private static final int BATCHES_PER_RUN = 10;
    private static final int MAX_ATTEMPTS = 5;
    private static final int REAPPLY_LIMIT = 500;
    private static final int LIST_LIMIT = 100;
    private static final int PREVIEW_LIMIT = 100;

    private final SourceRepository repository;
    private final CrmEventRepository eventRepository;
    private final CrmRecordApplier applier;
    private final CrmClient client;
    private final SourceSettingsService settings;
    private final AuditJournalRepository auditJournalRepository;
    private final ObjectMapper objectMapper;

    public CrmExchangeService(
            SourceRepository repository,
            CrmEventRepository eventRepository,
            CrmRecordApplier applier,
            CrmClient client,
            SourceSettingsService settings,
            AuditJournalRepository auditJournalRepository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.applier = applier;
        this.client = client;
        this.settings = settings;
        this.auditJournalRepository = auditJournalRepository;
        this.objectMapper = objectMapper;
    }

    // Сбой приёма не мешает отправке накопленных событий заказчику: отправка идёт всегда, а ошибка приёма
    // пробрасывается после неё и делает запуск неуспешным.
    void run(UUID runId, OffsetDateTime updatedSince, UUID actorProfileId) {
        SourceProperties.Crm crm = settings.crm();
        SourceFetchException inboundFailure = null;
        InboundResult inbound = null;
        try {
            inbound = receive(crm, runId, updatedSince, actorProfileId);
        } catch (SourceFetchException exception) {
            inboundFailure = exception;
        }
        String delivery = exportEvents(crm);
        if (inboundFailure != null) {
            throw inboundFailure;
        }
        String message = inbound.unstorable() == 0 ? null : "Записей без externalId или updatedAt: " + inbound.unstorable() + "; они не сохранены";
        repository.completeRun(runId, inbound.totals(), joined(message, delivery), OffsetDateTime.now());
        if (inbound.latest() != null) {
            repository.advanceUpdatedSince(SourceCode.CUSTOMER_CRM, inbound.latest(), OffsetDateTime.now());
        }
    }

    public CrmPreview preview(CrmProfile profile) {
        SourceSyncService.requireAdmin(profile);
        SourceProperties.Crm crm = settings.crm();
        if (!crm.configured()) {
            throw SourceException.notConfigured(SourceCode.CUSTOMER_CRM);
        }
        OffsetDateTime updatedSince = repository.findUpdatedSince(SourceCode.CUSTOMER_CRM).orElse(null);
        CrmFetch fetched;
        try {
            fetched = client.fetch(crm, updatedSince);
        } catch (SourceFetchException exception) {
            throw SourceException.crmExchangeFailed(exception.getMessage());
        }
        Map<String, UUID> resolved = new HashMap<>();
        List<CrmPreviewItem> items = new ArrayList<>();
        for (SiteRecord item : fetched.organizations()) {
            items.add(applier.preview(item, resolved));
        }
        for (SiteRecord item : fetched.contacts()) {
            items.add(applier.preview(item, resolved));
        }
        Map<CrmPreviewOutcome, Integer> counts = new EnumMap<>(CrmPreviewOutcome.class);
        items.forEach(item -> counts.merge(item.outcome(), 1, Integer::sum));
        List<CrmPreviewItem> attention = items.stream()
                .sorted(Comparator.comparing(item -> attentionOrder(item.outcome())))
                .limit(PREVIEW_LIMIT)
                .toList();
        return new CrmPreview(
                updatedSince,
                fetched.organizations().size(),
                fetched.contacts().size(),
                counts.entrySet().stream().map(entry -> new CrmPreviewCount(entry.getKey(), entry.getValue())).toList(),
                attention,
                items.size() > PREVIEW_LIMIT
        );
    }

    public CrmEventList events(CrmProfile profile, CrmEventStatus status) {
        SourceSyncService.requireAdmin(profile);
        return new CrmEventList(
                eventRepository.counts(),
                eventRepository.findRecent(status, LIST_LIMIT).stream().map(this::view).toList()
        );
    }

    public CrmEventsRequeued requeue(CrmProfile profile, String requestId) {
        SourceSyncService.requireAdmin(profile);
        int requeued = eventRepository.requeueErrors(OffsetDateTime.now());
        auditJournalRepository.record(AuditAction.CRM_EVENTS_REQUEUED, profile.id(), "SOURCE", null,
                "Обмен с CRM заказчика", "возвращено в очередь событий с ошибкой: " + requeued, requestId);
        return new CrmEventsRequeued(requeued);
    }

    private InboundResult receive(SourceProperties.Crm crm, UUID runId, OffsetDateTime updatedSince, UUID actorProfileId) {
        CrmFetch fetched = client.fetch(crm, updatedSince);
        List<SiteRecord> items = new ArrayList<>(fetched.organizations());
        items.addAll(fetched.contacts());
        int[] counts = new int[SyncOutcome.values().length];
        for (SiteRecord item : items) {
            counts[(item.storable() ? applyOne(item, runId, actorProfileId) : SyncOutcome.FAILED).ordinal()]++;
        }
        // После приёма пересчитываются ожидающие сопоставления: организация из этой же загрузки могла разрешить
        // контакт, применённый раньше неё.
        for (UUID recordId : repository.findNeedsMappingIds(SourceCode.CUSTOMER_CRM, REAPPLY_LIMIT)) {
            reapply(recordId, actorProfileId);
        }
        OffsetDateTime latest = items.stream()
                .filter(SiteRecord::storable)
                .map(SiteRecord::updatedAt)
                .max(OffsetDateTime::compareTo)
                .orElse(null);
        return new InboundResult(
                new SyncTotals(items.size(), counts[SyncOutcome.CREATED.ordinal()], counts[SyncOutcome.UPDATED.ordinal()],
                        counts[SyncOutcome.SKIPPED.ordinal()], counts[SyncOutcome.NEEDS_MAPPING.ordinal()],
                        counts[SyncOutcome.FAILED.ordinal()]),
                (int) items.stream().filter(item -> !item.storable()).count(),
                latest
        );
    }

    private SyncOutcome applyOne(SiteRecord item, UUID runId, UUID actorProfileId) {
        try {
            return applier.apply(item, runId, actorProfileId);
        } catch (RuntimeException exception) {
            log.warn("CRM record {} {} was not applied", item.type(), item.externalId(), exception);
            applier.recordFailure(item, runId, "Запись не применена из-за внутренней ошибки; подробности в журнале сервера");
            return SyncOutcome.FAILED;
        }
    }

    private void reapply(UUID recordId, UUID actorProfileId) {
        try {
            applier.reapply(recordId, actorProfileId);
        } catch (RuntimeException exception) {
            log.warn("CRM record {} was not applied again", recordId, exception);
            applier.markFailed(recordId, "Запись не применена из-за внутренней ошибки; подробности в журнале сервера");
        }
    }

    // Сбой очереди не должен ронять приём: он превращается в сообщение запуска, а подробности остаются в журнале
    // сервера.
    private String exportEvents(SourceProperties.Crm crm) {
        try {
            int collected = collect(OffsetDateTime.now());
            int sent = deliver(crm);
            CrmEventCounts counts = eventRepository.counts();
            return "События для CRM заказчика: добавлено в очередь " + collected + ", отправлено " + sent
                    + ", в очереди " + counts.queued() + ", с ошибкой " + counts.error();
        } catch (RuntimeException exception) {
            log.error("Exchange events for the customer CRM were not processed", exception);
            return "События для CRM заказчика не обработаны; подробности в журнале сервера";
        }
    }

    // События сначала пишутся в очередь и только потом отправляются: падение между записью и отправкой событие не
    // теряет.
    private int collect(OffsetDateTime now) {
        Map<UUID, String> crmIds = new HashMap<>();
        int added = 0;
        for (WorkEventRow row : eventRepository.findStageEvents(since(CrmEventKind.STAGE_CHANGED, now), COLLECT_LIMIT)) {
            ObjectNode event = base("stage:" + row.eventId(), CrmEventKind.STAGE_CHANGED, row.occurredAt(), row.organizationId(),
                    row.organizationName(), row.organizationInn(), crmIds);
            work(event, row);
            event.putObject("stage").put("from", row.fromStage()).put("to", row.toStage());
            manager(event.putObject("manager"), row.managerId(), row.managerName());
            added += insert(event, "stage:" + row.eventId(), CrmEventKind.STAGE_CHANGED, row.organizationId(), row.interactionId(),
                    row.occurredAt(), now);
        }
        for (WorkEventRow row : eventRepository.findPlanEvents(since(CrmEventKind.NEXT_STEP_CHANGED, now), COLLECT_LIMIT)) {
            ObjectNode event = base("plan:" + row.eventId(), CrmEventKind.NEXT_STEP_CHANGED, row.occurredAt(), row.organizationId(),
                    row.organizationName(), row.organizationInn(), crmIds);
            work(event, row);
            ObjectNode step = event.putObject("nextStep").put("text", row.nextAction());
            step.put("dueAt", row.nextActionAt() == null ? null : ISO.format(row.nextActionAt().atZoneSameInstant(ZONE)));
            manager(event.putObject("manager"), row.managerId(), row.managerName());
            added += insert(event, "plan:" + row.eventId(), CrmEventKind.NEXT_STEP_CHANGED, row.organizationId(),
                    row.interactionId(), row.occurredAt(), now);
        }
        for (AssignmentRow row : eventRepository.findAssignmentEvents(since(CrmEventKind.MANAGER_CHANGED, now), COLLECT_LIMIT)) {
            ObjectNode event = base("manager:" + row.eventId(), CrmEventKind.MANAGER_CHANGED, row.occurredAt(),
                    row.organizationId(), row.organizationName(), row.organizationInn(), crmIds);
            manager(event.putObject("manager"), row.managerId(), row.managerName());
            manager(event.putObject("previousManager"), row.previousManagerId(), row.previousManagerName());
            added += insert(event, "manager:" + row.eventId(), CrmEventKind.MANAGER_CHANGED, row.organizationId(), null,
                    row.occurredAt(), now);
        }
        for (AgreementRow row : eventRepository.findAgreementStatusEvents(since(CrmEventKind.AGREEMENT_STATUS, now), COLLECT_LIMIT)) {
            String key = "agreement:" + row.eventId();
            ObjectNode event = base(key, CrmEventKind.AGREEMENT_STATUS, row.occurredAt(), row.organizationId(),
                    row.organizationName(), row.organizationInn(), crmIds);
            ObjectNode agreement = event.putObject("agreement").put("id", row.id().toString()).put("number", row.number())
                    .put("status", row.status()).put("previousStatus", row.previousStatus());
            agreement.put("concludedOn", row.concludedOn() == null ? null : row.concludedOn().toString());
            agreement.put("validUntil", row.validUntil() == null ? null : row.validUntil().toString());
            manager(event.putObject("manager"), row.managerId(), row.managerName());
            added += insert(event, key, CrmEventKind.AGREEMENT_STATUS, row.organizationId(), null, row.occurredAt(), now);
        }
        return added;
    }

    // Сбор идёт от последнего зафиксированного события вида с перекрытием OVERLAP: запоздало записанные события не
    // теряются, а дубли отсекает ключ события (insertIfAbsent). В первый раз берётся окно EXPORT_WINDOW.
    private OffsetDateTime since(CrmEventKind kind, OffsetDateTime now) {
        return eventRepository.findLatestOccurredAt(kind).map(latest -> latest.minus(OVERLAP)).orElse(now.minus(EXPORT_WINDOW));
    }

    private ObjectNode base(String key, CrmEventKind kind, OffsetDateTime occurredAt, UUID organizationId, String organizationName,
                            String inn, Map<UUID, String> crmIds) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("eventId", key).put("kind", kind.name()).put("occurredAt", ISO.format(occurredAt.atZoneSameInstant(ZONE)));
        // Внешний ID организации у заказчика берётся из сопоставления (кэш на сбор); без сопоставления остаётся null.
        String externalId = crmIds.computeIfAbsent(organizationId, id -> eventRepository.findCrmOrganizationKey(id)
                .map(value -> value.substring("id:".length()))
                .orElse(""));
        ObjectNode organization = event.putObject("organization").put("id", organizationId.toString());
        organization.put("externalId", externalId.isEmpty() ? null : externalId);
        organization.put("name", organizationName).put("inn", inn);
        return event;
    }

    private static void work(ObjectNode event, WorkEventRow row) {
        event.putObject("work").put("id", row.interactionId().toString()).put("title", row.workTitle())
                .put("program", row.programName());
    }

    private static void manager(ObjectNode node, UUID id, String name) {
        node.put("id", id == null ? null : id.toString()).put("name", name);
    }

    // Ключ события строится из идентификатора записи истории: повторный сбор того же события второй копии в очереди не
    // создаёт.
    private int insert(ObjectNode event, String key, CrmEventKind kind, UUID organizationId, UUID interactionId,
                       OffsetDateTime occurredAt, OffsetDateTime now) {
        return eventRepository.insertIfAbsent(key, kind, organizationId, interactionId, occurredAt, event.toString(), now) ? 1 : 0;
    }

    // За запуск не больше BATCHES_PER_RUN пачек, остаток уйдёт следующим запуском. Доставка «минимум один раз»: событие
    // помечается отправленным только после ответа заказчика, поэтому обрыв между приёмом и отметкой повторит отправку,
    // а у события стабильный eventId, по которому заказчик отличает повтор.
    private int deliver(SourceProperties.Crm crm) {
        int sent = 0;
        for (int batchNumber = 0; batchNumber < BATCHES_PER_RUN; batchNumber++) {
            List<StoredCrmEvent> batch = eventRepository.findDue(OffsetDateTime.now(), BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            // Идентификатор пачки новый на каждую попытку и защищает только от дубля самого запроса; повтор события
            // распознаётся по eventId.
            UUID batchId = UUID.randomUUID();
            try {
                CrmDelivery delivery = client.send(crm, batchId, batch.stream().map(StoredCrmEvent::payload).toList());
                for (StoredCrmEvent event : batch) {
                    String rejection = delivery.rejected().get(eventId(event));
                    if (rejection == null) {
                        eventRepository.markSent(event.id(), batchId, OffsetDateTime.now());
                        sent++;
                    } else {
                        // Отклонение заказчиком окончательно: событие уходит в ERROR без повторов (тот же текст он
                        // отклонил бы снова), вернуть его можно вручную.
                        eventRepository.markRejected(event.id(), batchId, "CRM заказчика не приняла событие: " + rejection);
                    }
                }
            } catch (SourceFetchException exception) {
                // Сбой доставки: пауза растёт с числом попыток (PAUSES), после MAX_ATTEMPTS событие уходит в ERROR и
                // ждёт ручного возврата в очередь. Остальные пачки не пробуются: источник недоступен, следующий запуск
                // повторит.
                OffsetDateTime now = OffsetDateTime.now();
                for (StoredCrmEvent event : batch) {
                    int attempts = event.attempts() + 1;
                    eventRepository.markFailed(event.id(), exception.getMessage(), attempts >= MAX_ATTEMPTS,
                            now.plus(PAUSES.get(Math.min(attempts, PAUSES.size()) - 1)));
                }
                break;
            }
        }
        return sent;
    }

    private String eventId(StoredCrmEvent event) {
        return payload(event).path("eventId").asText();
    }

    private CrmEventView view(StoredCrmEvent event) {
        JsonNode payload = payload(event);
        return new CrmEventView(
                event.id(),
                event.kind(),
                event.status(),
                event.occurredAt(),
                payload.path("organization").path("name").asText(null),
                payload.path("work").path("title").asText(null),
                describe(event.kind(), payload),
                event.attempts(),
                event.status() == CrmEventStatus.QUEUED ? event.nextAttemptAt() : null,
                event.lastError(),
                event.sentAt()
        );
    }

    private JsonNode payload(StoredCrmEvent event) {
        try {
            return objectMapper.readTree(event.payload());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored exchange event cannot be read", exception);
        }
    }

    private static String describe(CrmEventKind kind, JsonNode payload) {
        return switch (kind) {
            case STAGE_CHANGED -> payload.path("stage").path("from").isNull()
                    ? "Взаимодействие создано на этапе «" + payload.path("stage").path("to").asText() + "»"
                    : "Этап: «" + payload.path("stage").path("from").asText() + "» → «" + payload.path("stage").path("to").asText() + "»";
            case NEXT_STEP_CHANGED -> payload.path("nextStep").path("text").isNull()
                    ? "Следующий шаг снят"
                    : "Следующий шаг: " + payload.path("nextStep").path("text").asText()
                            + (payload.path("nextStep").path("dueAt").isNull() ? ""
                                    : ", срок " + MOSCOW.format(OffsetDateTime.parse(payload.path("nextStep").path("dueAt").asText())
                                            .atZoneSameInstant(ZONE)) + " (МСК)");
            case MANAGER_CHANGED -> "Ответственный: " + payload.path("previousManager").path("name").asText("не назначен")
                    + " → " + payload.path("manager").path("name").asText("не назначен");
            case AGREEMENT_STATUS -> "Договор № " + payload.path("agreement").path("number").asText() + ": "
                    + (payload.path("agreement").path("previousStatus").isNull() ? ""
                            : "«" + statusTitle(payload.path("agreement").path("previousStatus").asText()) + "» → ")
                    + "«" + statusTitle(payload.path("agreement").path("status").asText()) + "»";
        };
    }

    private static String statusTitle(String status) {
        return Arrays.stream(AgreementStatus.values()).filter(value -> value.name().equals(status)).findFirst()
                .map(AgreementStatus::title).orElse(status);
    }

    // Сначала то, что требует решения человека (ошибки, неоднозначность), в конце — не требующее внимания.
    private static int attentionOrder(CrmPreviewOutcome outcome) {
        return switch (outcome) {
            case INVALID -> 0;
            case AMBIGUOUS -> 1;
            case NOT_FOUND, ORGANIZATION_UNRESOLVED -> 2;
            case RESTRICTED -> 3;
            case CREATE, UPDATE, FOUND -> 4;
            case UNCHANGED -> 5;
        };
    }

    private static String joined(String first, String second) {
        return first == null ? second : second == null ? first : first + "; " + second;
    }

    private record InboundResult(SyncTotals totals, int unstorable, OffsetDateTime latest) {
    }
}
