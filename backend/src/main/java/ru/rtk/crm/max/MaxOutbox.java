package ru.rtk.crm.max;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.OrganizationRepository;

// Исходящая очередь MAX. Сообщение кладётся в очередь в момент события, а решение «отправлять ли» принимается заново
// перед каждой отправкой: за время ожидания профиль могут заблокировать, вуз — передать другому КАМ, связь —
// остановить. Однократную доставку очередь не обещает: при потере ответа MAX сообщение может прийти дважды.
@Service
@MaxEnabled
public class MaxOutbox {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaxOutbox.class);
    static final int BATCH = 20;
    private static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(60)
    );
    private static final long PAUSE_MILLIS = 100;

    private final MaxQueueRepository queueRepository;
    private final MaxLinkRepository linkRepository;
    private final MaxSettingsService settings;
    private final MaxClient client;
    private final OrganizationRepository organizationRepository;
    private final MaxProperties properties;
    private final ObjectMapper objectMapper;

    public MaxOutbox(
            MaxQueueRepository queueRepository,
            MaxLinkRepository linkRepository,
            MaxSettingsService settings,
            MaxClient client,
            OrganizationRepository organizationRepository,
            MaxProperties properties,
            ObjectMapper objectMapper
    ) {
        this.queueRepository = queueRepository;
        this.linkRepository = linkRepository;
        this.settings = settings;
        this.client = client;
        this.organizationRepository = organizationRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean enqueue(String eventKey, UUID profileId, long maxUserId, MaxMessageKind kind, UUID organizationId, MaxMessage message) {
        return enqueue(eventKey, profileId, maxUserId, kind, organizationId, message, null);
    }

    // expiresAt — когда сообщение теряет смысл (сводка — к концу дня); null — общий срок app.max.message-ttl.
    public boolean enqueue(
            String eventKey,
            UUID profileId,
            long maxUserId,
            MaxMessageKind kind,
            UUID organizationId,
            MaxMessage message,
            OffsetDateTime expiresAt
    ) {
        // Время усечено до миллисекунд: база хранит микросекунды с округлением, и округлённое вверх время постановки
        // могло бы оказаться позже момента ближайшего прохода отправщика.
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        try {
            return queueRepository.enqueue(eventKey, profileId, maxUserId, kind, organizationId,
                    objectMapper.writeValueAsString(message.body()), now,
                    expiresAt == null ? now.plus(properties.messageTtl()) : expiresAt);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // Не больше одного сообщения одному пользователю за проход (MAX ограничивает частоту в диалог) и пауза между
    // запросами, чтобы держаться ниже общего лимита бота.
    public int sendBatch() {
        Optional<String> token = settings.token();
        if (token.isEmpty()) {
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.now();
        Set<Long> served = new HashSet<>();
        int sent = 0;
        for (MaxQueueRepository.OutboxRow row : queueRepository.findDue(now, BATCH)) {
            if (!served.add(row.maxUserId())) {
                continue;
            }
            String reason = dropReason(row, now);
            if (reason != null) {
                queueRepository.markDropped(row.id(), reason);
                continue;
            }
            if (send(token.get(), row, now)) {
                sent++;
            }
            pause();
        }
        return sent;
    }

    // Причина не отправлять или null. Проверки повторяют права CRM: тот же профиль, та же область видимости вуза.
    String dropReason(MaxQueueRepository.OutboxRow row, OffsetDateTime now) {
        if (!row.expiresAt().isAfter(now)) {
            return "сообщение устарело";
        }
        if (row.profileId() == null) {
            return null;
        }
        Optional<CrmProfile> profile = linkRepository.findChannelProfile(row.profileId());
        if (profile.isEmpty()) {
            return "профиль заблокирован или роль не получает уведомлений";
        }
        Optional<MaxLinkRepository.Link> link = linkRepository.findByProfile(row.profileId());
        if (link.isEmpty() || !link.get().active() || link.get().maxUserId() != row.maxUserId()) {
            return "аккаунт MAX отключён или бот остановлен";
        }
        if (!link.get().allows(row.kind())) {
            return "пользователь отключил этот вид уведомлений";
        }
        if (row.organizationId() != null && organizationRepository.findVisibleById(profile.get(), row.organizationId()).isEmpty()) {
            return "вуз больше не виден получателю";
        }
        return null;
    }

    private boolean send(String token, MaxQueueRepository.OutboxRow row, OffsetDateTime now) {
        MaxClient.Response response;
        try {
            response = client.post(token, "/messages?user_id=" + row.maxUserId(), body(row.body(), now));
        } catch (MaxException exception) {
            retry(row, now, exception.getMessage());
            return false;
        }
        if (response.ok()) {
            queueRepository.markSent(row.id(), now);
            return true;
        }
        int status = response.status();
        if (status == 403) {
            // 403 на отправку (chat.denied) — пользователь остановил бота: связь останавливается, очередь к нему сбрасывается.
            linkRepository.stop(row.maxUserId(), now);
            queueRepository.markDropped(row.id(), "бот остановлен пользователем: " + response.error());
            queueRepository.dropQueuedFor(row.maxUserId(), "бот остановлен пользователем");
        } else if (status == 429 || status >= 500) {
            retry(row, now, response.error());
        } else {
            LOGGER.warn("MAX rejected message kind={} status={}", row.kind(), status);
            queueRepository.markError(row.id(), response.error());
        }
        return false;
    }

    private void retry(MaxQueueRepository.OutboxRow row, OffsetDateTime now, String error) {
        Duration delay = BACKOFF.get(Math.min(row.attempts(), BACKOFF.size() - 1));
        queueRepository.markRetry(row.id(), now.plus(delay), error);
    }

    // Вне рабочего времени (до 9:00 и после 20:00 по Москве) сообщение приходит без звука: оно не задерживается до
    // утра, но и не будит.
    Object body(String stored, OffsetDateTime now) {
        try {
            ObjectNode body = (ObjectNode) objectMapper.readTree(stored);
            body.put("notify", !quiet(now));
            return body;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    boolean quiet(OffsetDateTime now) {
        int hour = now.atZoneSameInstant(MaxProperties.ZONE).getHour();
        return hour < properties.quietBeforeHour() || hour >= properties.quietAfterHour();
    }

    private static void pause() {
        try {
            Thread.sleep(PAUSE_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
