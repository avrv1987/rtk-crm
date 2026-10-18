package ru.rtk.crm.max;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.source.SourceSettingsService;

@Service
@MaxEnabled
public class MaxSettingsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaxSettingsService.class);
    static final String TOKEN_CONTEXT = "max:token";
    static final String SECRET_CONTEXT = "max:webhook-secret";
    static final List<String> UPDATE_TYPES = List.of("bot_started", "bot_stopped", "dialog_removed", "message_created");
    private static final int TOKEN_LIMIT = 500;
    private static final int SECRET_LENGTH = 32;

    private final MaxSettingsRepository repository;
    private final MaxQueueRepository queueRepository;
    private final SourceSettingsService secrets;
    private final MaxClient client;
    private final MaxProperties properties;
    private final AuditJournalRepository auditJournalRepository;

    public MaxSettingsService(
            MaxSettingsRepository repository,
            MaxQueueRepository queueRepository,
            SourceSettingsService secrets,
            MaxClient client,
            MaxProperties properties,
            AuditJournalRepository auditJournalRepository
    ) {
        this.repository = repository;
        this.queueRepository = queueRepository;
        this.secrets = secrets;
        this.client = client;
        this.properties = properties;
        this.auditJournalRepository = auditJournalRepository;
    }

    public Optional<String> token() {
        return secrets.decryptModuleSecret(TOKEN_CONTEXT, repository.find().tokenEncrypted());
    }

    public Optional<String> webhookSecret() {
        return secrets.decryptModuleSecret(SECRET_CONTEXT, repository.find().secretEncrypted());
    }

    public Optional<String> botName() {
        return Optional.ofNullable(repository.find().botName());
    }

    public boolean miniAppEnabled() {
        return repository.find().miniAppEnabled();
    }

    // Кнопка «Открыть»: мини-приложение, если администратор отметил, что оно подключено к боту на платформе MAX,
    // иначе обычная ссылка на CRM.
    public MaxMessage.Button openButton(String appPayload, String crmHash) {
        MaxSettingsRepository.Stored stored = repository.find();
        return stored.miniAppEnabled() && stored.botName() != null
                ? MaxMessage.Button.openApp("Открыть", stored.botName(), appPayload)
                : MaxMessage.Button.link("Открыть CRM", properties.crmUrl(crmHash));
    }

    @Transactional(readOnly = true)
    public View view(CrmProfile actor) {
        AdminAuthorization.requireAdmin(actor);
        return view();
    }

    // Новый токен получает и новый секрет вебхука: старая подписка с прежним секретом после этого не принимается.
    @Transactional
    public View update(CrmProfile actor, UpdateRequest request, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        OffsetDateTime now = OffsetDateTime.now();
        StringBuilder details = new StringBuilder();
        if (request.token() != null) {
            String token = request.token().strip();
            if (token.isEmpty() || token.length() > TOKEN_LIMIT || token.chars().anyMatch(Character::isWhitespace)) {
                throw new InteractionValidationException("token", "Вставьте токен бота из кабинета MAX для партнёров без пробелов");
            }
            if (!secrets.moduleSecretsAvailable()) {
                throw new InteractionValidationException("token",
                        "Ключ шифрования APP_SOURCES_SETTINGS_KEY не задан: токен нельзя сохранить");
            }
            repository.saveToken(
                    secrets.encryptModuleSecret(TOKEN_CONTEXT, token),
                    secrets.encryptModuleSecret(SECRET_CONTEXT, MaxCodes.randomCode(SECRET_LENGTH)),
                    now
            );
            details.append("токен бота заменён");
        }
        if (request.miniAppEnabled() != null) {
            repository.saveMiniApp(request.miniAppEnabled(), now);
            details.append(details.isEmpty() ? "" : "; ")
                    .append(request.miniAppEnabled() ? "кнопка мини-приложения включена" : "кнопка мини-приложения выключена");
        }
        if (!details.isEmpty()) {
            auditJournalRepository.record(AuditAction.MAX_SETTINGS_CHANGED, actor.id(), "MAX", null, "Бот MAX",
                    details.toString(), requestId);
        }
        return view();
    }

    @Transactional
    public CheckResult check(CrmProfile actor) {
        AdminAuthorization.requireAdmin(actor);
        String token = token().orElseThrow(MaxException::notConfigured);
        MaxClient.Response response = client.get(token, "/me");
        if (!response.ok()) {
            return new CheckResult(false, "MAX отклонил запрос: " + response.error(), null);
        }
        String username = response.body().path("username").asText("");
        if (username.isBlank()) {
            return new CheckResult(false, "MAX не вернул имя бота", null);
        }
        repository.saveBotName(username);
        return new CheckResult(true, "Связь с MAX есть", username);
    }

    @Transactional
    public View subscribe(CrmProfile actor) {
        AdminAuthorization.requireAdmin(actor);
        MaxClient.Response response = requestSubscription();
        if (!response.ok() || !response.body().path("success").asBoolean(false)) {
            throw new MaxException(HttpStatus.BAD_GATEWAY, "MAX_REQUEST_FAILED", "MAX не принял подписку: " + response.error());
        }
        repository.markSubscribed(OffsetDateTime.now());
        return view();
    }

    @Transactional
    public int requeue(CrmProfile actor, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        int count = queueRepository.requeueErrors(OffsetDateTime.now());
        auditJournalRepository.record(AuditAction.MAX_OUTBOX_REQUEUED, actor.id(), "MAX", null, "Очередь MAX",
                "возвращено сообщений: " + count, requestId);
        return count;
    }

    // Начальная настройка из MAX_BOT_TOKEN для сервера, куда через интерфейс никто не входит. Токен из окружения
    // пишется только в пустую базу тем же шифрованием, что из админки; дальше окружение управляет ботом, лишь пока
    // в базе тот же токен: сменил токен администратор — переменная ни на что не влияет. Имя бота и подписка
    // ставятся здесь же; вызывается при старте и каждый час, поэтому сбой MAX при старте повторится.
    public void bootstrap(String environmentToken) {
        String token = environmentToken == null ? "" : environmentToken.strip();
        if (token.isEmpty()) {
            return;
        }
        if (repository.find().tokenEncrypted() == null && !storeEnvironmentToken(token)) {
            return;
        }
        if (!token.equals(token().orElse(null))) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        try {
            if (repository.find().botName() == null) {
                MaxClient.Response me = client.get(token, "/me");
                String username = me.ok() ? me.body().path("username").asText("") : "";
                if (!username.isBlank()) {
                    repository.saveBotName(username);
                } else {
                    LOGGER.warn("MAX bot check failed: {}", me.error());
                }
            }
            String url = properties.webhookUrl();
            if (repository.find().subscribedAt() == null && url != null && url.startsWith("https://")) {
                MaxClient.Response response = requestSubscription();
                if (response.ok() && response.body().path("success").asBoolean(false)) {
                    repository.markSubscribed(now);
                    LOGGER.info("MAX webhook subscribed url={}", url);
                } else {
                    repository.markSubscriptionChecked(false, "MAX не принял подписку: " + response.error(), now);
                }
            }
        } catch (MaxException exception) {
            repository.markSubscriptionChecked(false, exception.getMessage(), now);
        }
    }

    private boolean storeEnvironmentToken(String token) {
        if (token.length() > TOKEN_LIMIT || token.chars().anyMatch(Character::isWhitespace)) {
            LOGGER.warn("MAX_BOT_TOKEN ignored: it must be one line without spaces, up to {} characters", TOKEN_LIMIT);
            return false;
        }
        if (!secrets.moduleSecretsAvailable()) {
            LOGGER.warn("MAX_BOT_TOKEN ignored: APP_SOURCES_SETTINGS_KEY is not set, the token cannot be encrypted");
            return false;
        }
        boolean stored = repository.saveInitialToken(
                secrets.encryptModuleSecret(TOKEN_CONTEXT, token),
                secrets.encryptModuleSecret(SECRET_CONTEXT, MaxCodes.randomCode(SECRET_LENGTH)),
                OffsetDateTime.now()
        );
        if (stored) {
            auditJournalRepository.record(AuditAction.MAX_SETTINGS_CHANGED, null, "MAX", null, "Бот MAX",
                    "токен бота задан из окружения (MAX_BOT_TOKEN)", null);
            LOGGER.info("MAX bot token stored from MAX_BOT_TOKEN");
        }
        return true;
    }

    // MAX снимает подписку после 8 часов без успешного ответа. Раз в час подписка сверяется; если нашего адреса в
    // ней нет, CRM подписывается заново тем же секретом.
    public void watch() {
        Optional<String> token = token();
        String url = properties.webhookUrl();
        if (token.isEmpty() || url == null || repository.find().subscribedAt() == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        try {
            MaxClient.Response response = client.get(token.get(), "/subscriptions");
            if (!response.ok()) {
                repository.markSubscriptionChecked(false, response.error(), now);
                return;
            }
            for (JsonNode subscription : response.body().path("subscriptions")) {
                if (url.equals(subscription.path("url").asText())) {
                    repository.markSubscriptionChecked(true, null, now);
                    return;
                }
            }
            MaxClient.Response renewed = requestSubscription();
            boolean ok = renewed.ok() && renewed.body().path("success").asBoolean(false);
            repository.markSubscriptionChecked(ok, ok ? null : "подписка пропала и не восстановлена: " + renewed.error(), now);
            LOGGER.warn("MAX webhook subscription was missing; renewed={}", ok);
        } catch (MaxException exception) {
            repository.markSubscriptionChecked(false, exception.getMessage(), now);
        }
    }

    private MaxClient.Response requestSubscription() {
        String token = token().orElseThrow(MaxException::notConfigured);
        String secret = webhookSecret().orElseThrow(MaxException::notConfigured);
        String url = properties.webhookUrl();
        if (url == null || !url.startsWith("https://")) {
            throw new MaxException(HttpStatus.CONFLICT, "MAX_WEBHOOK_URL_INVALID",
                    "Вебхук MAX принимается только по HTTPS: задайте публичный адрес CRM (PUBLIC_ORIGIN или MAX_PUBLIC_BASE_URL)");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("update_types", UPDATE_TYPES);
        body.put("secret", secret);
        return client.post(token, "/subscriptions", body);
    }

    private View view() {
        MaxSettingsRepository.Stored stored = repository.find();
        String tokenState = stored.tokenEncrypted() == null ? "NONE"
                : secrets.decryptModuleSecret(TOKEN_CONTEXT, stored.tokenEncrypted()).isPresent() ? "SCREEN" : "UNREADABLE";
        String url = properties.webhookUrl();
        return new View(
                tokenState,
                secrets.moduleSecretsAvailable(),
                stored.botName(),
                stored.miniAppEnabled(),
                url,
                url != null && url.startsWith("https://"),
                stored.subscribedAt(),
                stored.subscriptionCheckedAt(),
                stored.subscriptionOk(),
                stored.subscriptionError(),
                stored.lastEventAt(),
                queueRepository.counts(OffsetDateTime.now().minusHours(24))
        );
    }

    public record UpdateRequest(String token, Boolean miniAppEnabled) {
    }

    public record CheckResult(boolean ok, String message, String botName) {
    }

    public record View(
            String tokenState,
            boolean encryptionAvailable,
            String botName,
            boolean miniAppEnabled,
            String webhookUrl,
            boolean webhookUrlValid,
            OffsetDateTime subscribedAt,
            OffsetDateTime subscriptionCheckedAt,
            Boolean subscriptionOk,
            String subscriptionError,
            OffsetDateTime lastEventAt,
            MaxQueueRepository.QueueCounts queue
    ) {
    }
}
