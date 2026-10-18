package ru.rtk.crm.max;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;

// Короткий сеанс мини-приложения вместо cookie: поведение cookie во webview MAX не описано, а веб-версия может открыть
// приложение во фрейме, где сторонние cookie режутся. Токен живёт только в памяти страницы и приходит заголовком
// Authorization: Bearer, поэтому CSRF здесь не нужен. В базе — только SHA-256 токена.
@Service
@MaxEnabled
public class MaxSessionService {
    private final MaxSessionRepository sessions;
    private final MaxLinkRepository links;
    private final MaxSettingsService settings;
    private final MaxProperties properties;
    private final AuditJournalRepository auditJournalRepository;
    private final ObjectMapper objectMapper;

    public MaxSessionService(
            MaxSessionRepository sessions,
            MaxLinkRepository links,
            MaxSettingsService settings,
            MaxProperties properties,
            AuditJournalRepository auditJournalRepository,
            ObjectMapper objectMapper
    ) {
        this.sessions = sessions;
        this.links = links;
        this.settings = settings;
        this.properties = properties;
        this.auditJournalRepository = auditJournalRepository;
        this.objectMapper = objectMapper;
    }

    // Вход: подпись и свежесть initData → подтверждённая активная связь → активный профиль КАМ или руководителя.
    // Администратор, «Руководство», представитель вуза и оператор зачисления сеанс не получают.
    @Transactional
    public Started start(String initData, String requestId) {
        String token = settings.token().orElseThrow(MaxException::notConfigured);
        MaxInitData.Verified verified = MaxInitData.verify(initData, token, properties.initDataMaxAge(), Instant.now(), objectMapper);
        MaxLinkRepository.Link link = links.findByMaxUser(verified.userId())
                .filter(MaxLinkRepository.Link::active)
                .orElseThrow(() -> new MaxException(HttpStatus.FORBIDDEN, "MAX_NOT_LINKED",
                        "Аккаунт MAX не подключён к CRM: подключите его в CRM, меню профиля → «Подключить MAX»"));
        CrmProfile profile = links.findChannelProfile(link.profileId())
                .orElseThrow(() -> new MaxException(HttpStatus.FORBIDDEN, "MAX_ROLE_NOT_ALLOWED",
                        "Мини-приложение доступно КАМ и руководителю команды"));
        OffsetDateTime now = OffsetDateTime.now();
        String sessionToken = MaxCodes.sessionToken();
        OffsetDateTime expiresAt = now.plus(properties.sessionLifetime());
        sessions.insert(MaxCodes.sha256(sessionToken), profile.id(), link.maxUserId(), profile.accessRevision(), now, expiresAt);
        String displayName = links.findDisplayName(profile.id()).orElse(null);
        auditJournalRepository.record(AuditAction.MAX_SESSION_STARTED, profile.id(), "PROFILE", profile.id(), displayName,
                "вход в мини-приложение MAX", requestId);
        return new Started(sessionToken, expiresAt, properties.sessionIdle().toMinutes(), profile.role().name(), displayName,
                verified.startParam());
    }

    // Проверка на каждом запросе. Сеанс закрывается, если истёк срок или простой, профиль заблокирован, сменилась
    // роль, команда или флаг оператора (access_revision), связь MAX отключена или бот остановлен.
    @Transactional
    public Optional<CrmProfile> authenticate(String token) {
        String hash = MaxCodes.sha256(token);
        Optional<MaxSessionRepository.Session> found = sessions.find(hash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MaxSessionRepository.Session session = found.get();
        OffsetDateTime now = OffsetDateTime.now();
        Optional<CrmProfile> profile = links.findChannelProfile(session.profileId())
                .filter(current -> current.accessRevision() == session.accessRevision());
        boolean linked = links.findByProfile(session.profileId())
                .filter(link -> link.active() && link.maxUserId() == session.maxUserId())
                .isPresent();
        if (!session.expiresAt().isAfter(now) || session.lastSeenAt().plus(properties.sessionIdle()).isBefore(now)
                || profile.isEmpty() || !linked) {
            sessions.delete(hash);
            return Optional.empty();
        }
        sessions.touch(hash, now);
        return profile;
    }

    @Transactional
    public void end(String token) {
        sessions.delete(MaxCodes.sha256(token));
    }

    public record Started(
            String token,
            OffsetDateTime expiresAt,
            long idleMinutes,
            String role,
            String displayName,
            String startParam
    ) {
    }
}
