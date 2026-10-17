package ru.rtk.crm.source;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.source.LaborMarketSettingsRepository.StoredSettings;

@Service
public class LaborMarketSettingsService {
    static final String TOKEN_CONTEXT = "source-settings:LABOR_MARKET:token";
    private static final String SUBJECT = "источника рынка труда";

    private final LaborMarketProperties properties;
    private final LaborMarketSettingsRepository repository;
    private final SourceTokenCipher cipher;
    private final AuditJournalRepository auditJournalRepository;
    private final LaborMarketClient client;

    public LaborMarketSettingsService(
            LaborMarketProperties properties,
            LaborMarketSettingsRepository repository,
            SourceTokenCipher cipher,
            AuditJournalRepository auditJournalRepository,
            LaborMarketClient client
    ) {
        this.properties = properties;
        this.repository = repository;
        this.cipher = cipher;
        this.auditJournalRepository = auditJournalRepository;
        this.client = client;
    }

    LaborMarketProperties connection() {
        return repository.find().map(this::connection).orElse(properties);
    }

    public LaborMarketSettingsView view(CrmProfile profile) {
        SourceSyncService.requireAdmin(profile);
        Optional<StoredSettings> stored = repository.find();
        return new LaborMarketSettingsView(
                SourceSettingsService.blankToNull(stored.map(this::connection).orElse(properties).baseUrl()),
                tokenState(stored),
                stored.isPresent(),
                stored.map(StoredSettings::updatedAt).orElse(null),
                stored.map(StoredSettings::updatedByName).orElse(null),
                cipher.available()
        );
    }

    public LaborMarketSettingsView update(CrmProfile profile, LaborMarketSettingsRequest request, String requestId) {
        SourceSyncService.requireAdmin(profile);
        Candidate candidate = validate(request);
        if (candidate.token() != null && !cipher.available()) {
            throw new InteractionValidationException("token",
                    "Токен нельзя сохранить: в конфигурации развёртывания не задан ключ шифрования SOURCES_SETTINGS_KEY");
        }
        Optional<StoredSettings> previous = repository.find();
        LaborMarketProperties before = previous.map(this::connection).orElse(properties);
        // Токен привязан к адресу: сохранённый токен на новый адрес не переносится.
        SourceSettingsService.requireTokenForNewAddress("token", SUBJECT, before.baseUrl(), candidate.baseUrl(), candidate.token(),
                SourceSettingsService.savedToken(previous.map(StoredSettings::tokenEncrypted), properties.token()));
        OffsetDateTime now = OffsetDateTime.now();
        repository.save(new StoredSettings(
                candidate.baseUrl(),
                candidate.token() != null
                        ? cipher.encrypt(candidate.token(), TOKEN_CONTEXT)
                        : previous.map(StoredSettings::tokenEncrypted).orElse(null),
                candidate.token() != null ? now : previous.map(StoredSettings::tokenChangedAt).orElse(null),
                now,
                null
        ), profile.id());
        List<String> changes = new ArrayList<>();
        SourceSettingsService.change(changes, "адрес рынка труда", SourceSettingsService.blankToNull(before.baseUrl()),
                candidate.baseUrl());
        if (candidate.token() != null) {
            changes.add("токен рынка труда заменён");
        }
        String details = changes.isEmpty() ? "значения сохранены без изменений" : String.join("; ", changes);
        auditJournalRepository.record(AuditAction.SOURCE_SETTINGS_CHANGED, profile.id(), "SOURCE", null,
                "Подключение источника «Рынок труда»", previous.isPresent() ? details : "первое сохранение на экране; " + details,
                requestId);
        return view(profile);
    }

    public void reset(CrmProfile profile, String requestId) {
        SourceSyncService.requireAdmin(profile);
        repository.delete();
        auditJournalRepository.record(AuditAction.SOURCE_SETTINGS_CHANGED, profile.id(), "SOURCE", null,
                "Подключение источника «Рынок труда»",
                "восстановлены значения конфигурации развёртывания; сохранённый на экране токен удалён", requestId);
    }

    public SourceConnectionCheck check(CrmProfile profile, LaborMarketSettingsRequest request) {
        SourceSyncService.requireAdmin(profile);
        Candidate candidate = validate(request);
        LaborMarketProperties current = connection();
        if (SourceSettingsService.savedTokenWouldGoToNewAddress(current.baseUrl(), candidate.baseUrl(), candidate.token(),
                current.token())) {
            return new SourceConnectionCheck(false, SourceSettingsService.NEW_ADDRESS_NEEDS_TOKEN, List.of());
        }
        LaborMarketProperties candidateConnection = current.with(candidate.baseUrl(),
                candidate.token() != null ? candidate.token() : current.token());
        if (!candidateConnection.configured()) {
            return new SourceConnectionCheck(false, "Для проверки укажите адрес источника рынка труда", List.of());
        }
        try {
            LaborMarketClient.Batch batch = client.check(candidateConnection);
            List<String> details = new ArrayList<>();
            details.add("Направлений в ответе: " + batch.items().stream().map(LaborMarketItem::directionCode).distinct().count());
            batch.items().stream().map(LaborMarketItem::dataSource).distinct().limit(3)
                    .forEach(source -> details.add("Источник данных: " + source));
            return new SourceConnectionCheck(true, "Связь с источником рынка труда есть: ответ соответствует контракту CRM", details);
        } catch (SourceFetchException exception) {
            return new SourceConnectionCheck(false, exception.getMessage(), List.of());
        }
    }

    // Нечитаемый сохранённый токен даёт null, а не токен конфигурации: администратор видит «нечитаем» и вводит токен
    // заново (как у остальных источников).
    private LaborMarketProperties connection(StoredSettings stored) {
        return properties.with(stored.baseUrl(),
                stored.tokenEncrypted() == null ? properties.token() : cipher.decrypt(stored.tokenEncrypted(), TOKEN_CONTEXT).orElse(null));
    }

    private TokenState tokenState(Optional<StoredSettings> stored) {
        if (stored.isPresent() && stored.get().tokenEncrypted() != null) {
            return new TokenState(
                    cipher.decrypt(stored.get().tokenEncrypted(), TOKEN_CONTEXT).isPresent() ? TokenOrigin.SCREEN : TokenOrigin.UNREADABLE,
                    stored.get().tokenChangedAt());
        }
        return new TokenState(SourceSettingsService.blankToNull(properties.token()) == null ? TokenOrigin.NONE : TokenOrigin.ENVIRONMENT, null);
    }

    private Candidate validate(LaborMarketSettingsRequest request) {
        if (request == null) {
            throw new InteractionValidationException("body", "Передайте настройки источника рынка труда");
        }
        return new Candidate(
                SourceSettingsService.url(request.baseUrl(), properties.baseUrl(), "baseUrl", SUBJECT),
                SourceSettingsService.token(request.token(), "token")
        );
    }

    private record Candidate(String baseUrl, String token) {
    }
}

record LaborMarketSettingsView(
        String baseUrl,
        TokenState token,
        boolean saved,
        OffsetDateTime updatedAt,
        String updatedByName,
        boolean encryptionAvailable
) {
}

record LaborMarketSettingsRequest(String baseUrl, String token) {
}
