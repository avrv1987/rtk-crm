package ru.rtk.crm.max;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.ProfileSessionsEnded;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;

// Привязка аккаунта MAX к профилю CRM. Порядок: сотрудник в CRM получает одноразовую ссылку → открывает её в MAX →
// бот получает bot_started с кодом и запоминает найденный аккаунт → тот же сотрудник подтверждает его в CRM. Без
// подтверждения пересланная ссылка не привяжет чужой MAX: чужой аккаунт увидит владелец кода и нажмёт «Это не я».
@Service
@MaxEnabled
public class MaxLinkService {
    static final int CODE_LENGTH = 24;
    private static final int NAME_LIMIT = 200;

    static final String TEXT_CONFIRM_IN_CRM =
            "Вернитесь в CRM ИТ Школы РТК и подтвердите подключение этого аккаунта MAX. Без подтверждения уведомления не придут.";
    static final String TEXT_INVALID_LINK =
            "Ссылка подключения недействительна или уже использована. Получите новую в CRM: меню профиля → «Подключить MAX».";
    static final String TEXT_STRANGER =
            "Это служебный бот CRM ИТ Школы РТК. Чтобы получать уведомления, подключите MAX в CRM: меню профиля → «Подключить MAX».";
    static final String TEXT_LINKED = "Аккаунт MAX подключён к CRM ИТ Школы РТК. Сюда будут приходить уведомления по вашей работе.";
    static final String TEXT_RESUMED = "Уведомления CRM ИТ Школы РТК снова включены.";
    static final String TEXT_NO_CHAT = "Бот не принимает сообщения. Откройте CRM, чтобы посмотреть работу и записать результат.";

    private final MaxLinkRepository repository;
    private final MaxSettingsService settings;
    private final MaxOutbox outbox;
    private final MaxQueueRepository queueRepository;
    private final MaxSessionRepository sessions;
    private final MaxProperties properties;
    private final AuditJournalRepository auditJournalRepository;

    public MaxLinkService(
            MaxLinkRepository repository,
            MaxSettingsService settings,
            MaxOutbox outbox,
            MaxQueueRepository queueRepository,
            MaxSessionRepository sessions,
            MaxProperties properties,
            AuditJournalRepository auditJournalRepository
    ) {
        this.repository = repository;
        this.settings = settings;
        this.outbox = outbox;
        this.queueRepository = queueRepository;
        this.sessions = sessions;
        this.properties = properties;
        this.auditJournalRepository = auditJournalRepository;
    }

    @Transactional(readOnly = true)
    public LinkStatus status(CrmProfile profile) {
        requireLinkable(profile);
        return status(profile.id(), OffsetDateTime.now());
    }

    @Transactional
    public LinkStart start(CrmProfile profile, String requestId) {
        requireLinkable(profile);
        String botName = settings.botName().orElseThrow(MaxException::notConfigured);
        OffsetDateTime now = OffsetDateTime.now();
        String code = MaxCodes.randomCode(CODE_LENGTH);
        OffsetDateTime expiresAt = now.plus(properties.linkCodeTtl());
        repository.replaceCode(profile.id(), MaxCodes.sha256(code), expiresAt, now);
        auditJournalRepository.record(AuditAction.MAX_LINK_REQUESTED, profile.id(), "PROFILE", profile.id(),
                displayName(profile.id()), "ссылка действует до " + expiresAt.atZoneSameInstant(MaxProperties.ZONE).toLocalTime(), requestId);
        // Код виден только в этом ответе: в базе остаётся его SHA-256.
        return new LinkStart("https://max.ru/" + botName + "?start=" + code, expiresAt);
    }

    @Transactional
    public LinkStatus confirm(CrmProfile profile, String requestId) {
        requireLinkable(profile);
        OffsetDateTime now = OffsetDateTime.now();
        MaxLinkRepository.Code code = repository.findCodeByProfile(profile.id())
                .filter(found -> found.expiresAt().isAfter(now) && found.foundMaxUserId() != null)
                .orElseThrow(() -> new MaxException(HttpStatus.CONFLICT, "MAX_LINK_EXPIRED",
                        "Подключение не найдено или ссылка истекла: получите новую ссылку"));
        long maxUserId = code.foundMaxUserId();
        Optional<MaxLinkRepository.Link> taken = repository.findByMaxUser(maxUserId);
        if (taken.isPresent() && !taken.get().profileId().equals(profile.id())) {
            throw new MaxException(HttpStatus.CONFLICT, "MAX_ACCOUNT_TAKEN",
                    "Этот аккаунт MAX уже подключён к другому профилю CRM");
        }
        repository.replaceLink(profile.id(), maxUserId, code.foundName(), now);
        repository.deleteCode(profile.id());
        auditJournalRepository.record(AuditAction.MAX_LINKED, profile.id(), "PROFILE", profile.id(), displayName(profile.id()),
                "аккаунт MAX " + code.foundName() + " (id " + maxUserId + ")", requestId);
        outbox.enqueue("linked:" + profile.id() + ":" + now.toInstant().toEpochMilli(), null, maxUserId, MaxMessageKind.REPLY, null,
                MaxMessage.text(TEXT_LINKED));
        return status(profile.id(), now);
    }

    @Transactional
    public LinkStatus reject(CrmProfile profile, String requestId) {
        requireLinkable(profile);
        Optional<MaxLinkRepository.Code> code = repository.findCodeByProfile(profile.id());
        repository.deleteCode(profile.id());
        if (code.isPresent() && code.get().foundMaxUserId() != null) {
            auditJournalRepository.record(AuditAction.MAX_LINK_REJECTED, profile.id(), "PROFILE", profile.id(),
                    displayName(profile.id()), "отклонён аккаунт MAX " + code.get().foundName() + " (id " + code.get().foundMaxUserId() + ")",
                    requestId);
        }
        return status(profile.id(), OffsetDateTime.now());
    }

    // Отвязать может сам сотрудник; это действие разрешено любой роли, чтобы снятая роль не оставляла связь висеть.
    @Transactional
    public LinkStatus unlink(CrmProfile profile, String requestId) {
        remove(profile.id(), profile.id(), "отключено сотрудником", requestId);
        return status(profile.id(), OffsetDateTime.now());
    }

    @Transactional
    public LinkStatus savePreferences(CrmProfile profile, MaxLinkRepository.Preferences preferences) {
        requireLinkable(profile);
        if (preferences == null) {
            throw new InteractionValidationException("body", "Не переданы настройки уведомлений");
        }
        if (repository.findByProfile(profile.id()).isEmpty()) {
            throw new MaxException(HttpStatus.CONFLICT, "MAX_NOT_LINKED", "Аккаунт MAX не подключён");
        }
        repository.savePreferences(profile.id(), preferences);
        return status(profile.id(), OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<MaxLinkRepository.AdminLink> adminLinks(CrmProfile actor) {
        AdminAuthorization.requireAdmin(actor);
        return repository.findAll();
    }

    @Transactional
    public void adminUnlink(CrmProfile actor, UUID profileId, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        if (!remove(actor.id(), profileId, "отключено администратором", requestId)) {
            throw new MaxException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Подключение MAX не найдено");
        }
    }

    // События бота. Повторная доставка того же события безопасна: код закрепляется за первым аккаунтом, смена статуса
    // связи применяется только к более свежему событию.
    @Transactional
    public void onStarted(long maxUserId, String name, String payload, OffsetDateTime eventAt, String eventKey) {
        Optional<MaxLinkRepository.Link> link = repository.findByMaxUser(maxUserId);
        boolean resumed = link.isPresent() && !link.get().active() && repository.changeStatus(maxUserId, "ACTIVE", eventAt);
        String text;
        if (payload != null && !payload.isBlank()) {
            MaxLinkRepository.FoundResult result = repository.markFound(
                    MaxCodes.sha256(payload.strip()), maxUserId, truncate(name), OffsetDateTime.now());
            text = result == MaxLinkRepository.FoundResult.FOUND ? TEXT_CONFIRM_IN_CRM : TEXT_INVALID_LINK;
        } else if (link.isPresent()) {
            text = resumed ? TEXT_RESUMED : null;
        } else {
            text = TEXT_STRANGER;
        }
        if (text != null) {
            outbox.enqueue("reply:" + eventKey, null, maxUserId, MaxMessageKind.REPLY, null, MaxMessage.text(text));
        }
    }

    @Transactional
    public void onStopped(long maxUserId, OffsetDateTime eventAt) {
        if (repository.changeStatus(maxUserId, "STOPPED", eventAt)) {
            queueRepository.dropQueuedFor(maxUserId, "бот остановлен пользователем");
        }
    }

    // Свободный текст бот не принимает: на любое сообщение — одна фраза и кнопка «Открыть» для подключённых.
    @Transactional
    public void onMessage(long maxUserId, String eventKey) {
        Optional<MaxLinkRepository.Link> link = repository.findByMaxUser(maxUserId).filter(MaxLinkRepository.Link::active);
        MaxMessage message = link.isPresent()
                ? new MaxMessage(TEXT_NO_CHAT, List.of(settings.openButton("work", "#/work")))
                : MaxMessage.text(TEXT_STRANGER);
        outbox.enqueue("reply:" + eventKey, null, maxUserId, MaxMessageKind.REPLY, null, message);
    }

    // «Завершить сеансы» у администратора закрывает и сеансы мини-приложения: ревизия доступа при этом не меняется.
    @EventListener
    public void onProfileSessionsEnded(ProfileSessionsEnded event) {
        sessions.deleteByProfile(event.profileId());
    }

    private boolean remove(UUID actorId, UUID profileId, String details, String requestId) {
        Optional<MaxLinkRepository.Link> link = repository.findByProfile(profileId);
        repository.deleteCode(profileId);
        sessions.deleteByProfile(profileId);
        queueRepository.dropQueuedForProfile(profileId, "аккаунт MAX отключён");
        if (link.isEmpty() || !repository.deleteLink(profileId)) {
            return false;
        }
        auditJournalRepository.record(AuditAction.MAX_UNLINKED, actorId, "PROFILE", profileId, displayName(profileId),
                details + "; аккаунт MAX " + link.get().maxName() + " (id " + link.get().maxUserId() + ")", requestId);
        return true;
    }

    private LinkStatus status(UUID profileId, OffsetDateTime now) {
        String botName = settings.botName().orElse(null);
        Optional<MaxLinkRepository.Link> link = repository.findByProfile(profileId);
        if (link.isPresent()) {
            MaxLinkRepository.Link value = link.get();
            return new LinkStatus(value.active() ? "ACTIVE" : "STOPPED", botName, null, null, value.maxName(),
                    value.confirmedAt(), value.preferences());
        }
        Optional<MaxLinkRepository.Code> code = repository.findCodeByProfile(profileId).filter(found -> found.expiresAt().isAfter(now));
        if (code.isPresent()) {
            MaxLinkRepository.Code value = code.get();
            return value.foundMaxUserId() == null
                    ? new LinkStatus("WAITING", botName, value.expiresAt(), null, null, null, null)
                    : new LinkStatus("FOUND", botName, value.expiresAt(), value.foundName(), null, null, null);
        }
        return new LinkStatus("NONE", botName, null, null, null, null, null);
    }

    private void requireLinkable(CrmProfile profile) {
        if (repository.findChannelProfile(profile.id()).isEmpty()) {
            throw MaxException.forbidden();
        }
    }

    private String displayName(UUID profileId) {
        return repository.findDisplayName(profileId).orElse(null);
    }

    static String name(String firstName, String lastName, String username) {
        StringBuilder name = new StringBuilder(firstName == null ? "" : firstName.strip());
        if (lastName != null && !lastName.isBlank()) {
            name.append(name.isEmpty() ? "" : " ").append(lastName.strip());
        }
        if (username != null && !username.isBlank()) {
            name.append(name.isEmpty() ? "@" + username.strip() : " (@" + username.strip() + ")");
        }
        return name.isEmpty() ? "без имени" : name.toString();
    }

    private static String truncate(String value) {
        return value.length() > NAME_LIMIT ? value.substring(0, NAME_LIMIT) : value;
    }

    public record LinkStart(String link, OffsetDateTime expiresAt) {
    }

    public record LinkStatus(
            String state,
            String botName,
            OffsetDateTime expiresAt,
            String candidateName,
            String maxName,
            OffsetDateTime confirmedAt,
            MaxLinkRepository.Preferences preferences
    ) {
    }
}
