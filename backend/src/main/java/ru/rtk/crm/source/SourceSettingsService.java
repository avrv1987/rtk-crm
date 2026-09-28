package ru.rtk.crm.source;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.source.SourceSettingsRepository.StoredSourceSettings;

@Service
public class SourceSettingsService {
    static final String MOODLE_TOKEN_CONTEXT = "source-settings:MOODLE:token";
    static final String WEBSITE_TOKEN_CONTEXT = "source-settings:WEBSITE:token";
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final Pattern ROLE = Pattern.compile("[a-z0-9_]{1,100}");
    private static final Pattern TOKEN = Pattern.compile("\\S{1,500}");
    private static final int MAX_URL_LENGTH = 500;
    private static final int MAX_COURSES = 100;
    private static final int MAX_ROLES = 20;
    private static final Duration MIN_SCHEDULE_INTERVAL = Duration.ofMinutes(5);

    private final SourceProperties properties;
    private final SourceSettingsRepository repository;
    private final SourceTokenCipher cipher;
    private final AuditJournalRepository auditJournalRepository;
    private final MoodleClient moodleClient;
    private final SiteApiClient siteApiClient;
    private final ApplicationEventPublisher events;

    public SourceSettingsService(
            SourceProperties properties,
            SourceSettingsRepository repository,
            SourceTokenCipher cipher,
            AuditJournalRepository auditJournalRepository,
            MoodleClient moodleClient,
            SiteApiClient siteApiClient,
            ApplicationEventPublisher events
    ) {
        this.properties = properties;
        this.repository = repository;
        this.cipher = cipher;
        this.auditJournalRepository = auditJournalRepository;
        this.moodleClient = moodleClient;
        this.siteApiClient = siteApiClient;
        this.events = events;
    }

    SourceProperties.Moodle moodle() {
        return repository.find().map(this::moodle).orElse(properties.moodle());
    }

    SourceProperties.Website website() {
        return repository.find().map(this::website).orElse(properties.website());
    }

    String syncCron() {
        return syncCron(repository.find());
    }

    public SourceSettingsView view(CrmProfile profile) {
        SourceSyncService.requireAdmin(profile);
        Optional<StoredSourceSettings> stored = repository.find();
        SourceProperties.Moodle moodle = stored.map(this::moodle).orElse(properties.moodle());
        SourceProperties.Website website = stored.map(this::website).orElse(properties.website());
        return new SourceSettingsView(
                blankToNull(moodle.baseUrl()),
                moodle.courseIds(),
                moodle.studentRoles(),
                moodle.teacherRoles(),
                tokenState(stored.map(StoredSourceSettings::moodleTokenEncrypted).orElse(null),
                        stored.map(StoredSourceSettings::moodleTokenChangedAt).orElse(null), MOODLE_TOKEN_CONTEXT,
                        properties.moodle().token()),
                blankToNull(website.baseUrl()),
                tokenState(stored.map(StoredSourceSettings::websiteTokenEncrypted).orElse(null),
                        stored.map(StoredSourceSettings::websiteTokenChangedAt).orElse(null), WEBSITE_TOKEN_CONTEXT,
                        properties.website().token()),
                syncCron(stored),
                stored.isPresent(),
                stored.map(StoredSourceSettings::updatedAt).orElse(null),
                stored.map(StoredSourceSettings::updatedByName).orElse(null),
                cipher.available()
        );
    }

    public SourceSettingsView update(CrmProfile profile, SourceSettingsRequest request, String requestId) {
        SourceSyncService.requireAdmin(profile);
        Candidate candidate = validate(request);
        if ((candidate.moodleToken() != null || candidate.websiteToken() != null) && !cipher.available()) {
            throw new InteractionValidationException(candidate.moodleToken() != null ? "moodleToken" : "websiteToken",
                    "Токен нельзя сохранить: в конфигурации развёртывания не задан ключ шифрования SOURCES_SETTINGS_KEY");
        }
        Optional<StoredSourceSettings> previous = repository.find();
        SourceProperties.Moodle moodleBefore = previous.map(this::moodle).orElse(properties.moodle());
        SourceProperties.Website websiteBefore = previous.map(this::website).orElse(properties.website());
        String cronBefore = syncCron(previous);
        OffsetDateTime now = OffsetDateTime.now();
        StoredSourceSettings next = new StoredSourceSettings(
                candidate.moodleBaseUrl(),
                candidate.moodleToken() != null
                        ? cipher.encrypt(candidate.moodleToken(), MOODLE_TOKEN_CONTEXT)
                        : previous.map(StoredSourceSettings::moodleTokenEncrypted).orElse(null),
                candidate.moodleToken() != null ? now : previous.map(StoredSourceSettings::moodleTokenChangedAt).orElse(null),
                candidate.moodleCourseIds(),
                candidate.moodleStudentRoles(),
                candidate.moodleTeacherRoles(),
                candidate.websiteBaseUrl(),
                candidate.websiteToken() != null
                        ? cipher.encrypt(candidate.websiteToken(), WEBSITE_TOKEN_CONTEXT)
                        : previous.map(StoredSourceSettings::websiteTokenEncrypted).orElse(null),
                candidate.websiteToken() != null ? now : previous.map(StoredSourceSettings::websiteTokenChangedAt).orElse(null),
                candidate.syncCron(),
                now,
                null
        );
        repository.save(next, profile.id());
        List<String> changes = new ArrayList<>();
        change(changes, "адрес Moodle", blankToNull(moodleBefore.baseUrl()), candidate.moodleBaseUrl());
        change(changes, "курсы Moodle", join(moodleBefore.courseIds()), join(candidate.moodleCourseIds()));
        change(changes, "роли обучающихся", join(moodleBefore.studentRoles()), join(candidate.moodleStudentRoles()));
        change(changes, "роли преподавателей", join(moodleBefore.teacherRoles()), join(candidate.moodleTeacherRoles()));
        if (candidate.moodleToken() != null) {
            changes.add("токен Moodle заменён");
        }
        change(changes, "адрес сайта", blankToNull(websiteBefore.baseUrl()), candidate.websiteBaseUrl());
        if (candidate.websiteToken() != null) {
            changes.add("токен сайта заменён");
        }
        change(changes, "расписание", scheduleText(cronBefore), scheduleText(candidate.syncCron()));
        String details = changes.isEmpty() ? "значения сохранены без изменений" : String.join("; ", changes);
        auditJournalRepository.record(AuditAction.SOURCE_SETTINGS_CHANGED, profile.id(), "SOURCE", null,
                "Подключение источников", previous.isPresent() ? details : "первое сохранение на экране; " + details, requestId);
        events.publishEvent(new SourceSettingsChanged());
        return view(profile);
    }

    public void reset(CrmProfile profile, String requestId) {
        SourceSyncService.requireAdmin(profile);
        repository.delete();
        auditJournalRepository.record(AuditAction.SOURCE_SETTINGS_CHANGED, profile.id(), "SOURCE", null,
                "Подключение источников", "восстановлены значения конфигурации развёртывания; сохранённые на экране токены удалены",
                requestId);
        events.publishEvent(new SourceSettingsChanged());
    }

    public SourceConnectionCheck check(CrmProfile profile, SourceCode source, SourceSettingsRequest request) {
        SourceSyncService.requireAdmin(profile);
        Candidate candidate = validate(request);
        try {
            if (source == SourceCode.MOODLE) {
                SourceProperties.Moodle current = moodle();
                SourceProperties.Moodle moodle = new SourceProperties.Moodle(
                        candidate.moodleBaseUrl(),
                        candidate.moodleToken() != null ? candidate.moodleToken() : current.token(),
                        candidate.moodleCourseIds(),
                        candidate.moodleStudentRoles(),
                        candidate.moodleTeacherRoles(),
                        current.connectTimeout(),
                        current.readTimeout(),
                        current.maxResponseSize()
                );
                if (!moodle.configured()) {
                    return new SourceConnectionCheck(false, "Для проверки укажите адрес Moodle, токен и хотя бы один курс", List.of());
                }
                return new SourceConnectionCheck(true, "Связь с Moodle есть: токен принят, курсы доступны сервису CRM",
                        moodleClient.check(moodle));
            }
            SourceProperties.Website current = website();
            SourceProperties.Website website = new SourceProperties.Website(
                    candidate.websiteBaseUrl(),
                    candidate.websiteToken() != null ? candidate.websiteToken() : current.token(),
                    current.connectTimeout(),
                    current.readTimeout(),
                    current.maxPages(),
                    current.maxPageSize()
            );
            if (!website.configured()) {
                return new SourceConnectionCheck(false, "Для проверки укажите адрес сайта", List.of());
            }
            siteApiClient.check(website);
            return new SourceConnectionCheck(true, "Связь с сайтом есть: ответ соответствует контракту CRM", List.of());
        } catch (SourceFetchException exception) {
            return new SourceConnectionCheck(false, exception.getMessage(), List.of());
        }
    }

    private String syncCron(Optional<StoredSourceSettings> stored) {
        return stored.isPresent() ? stored.get().syncCron() : environmentCron(properties.syncCron());
    }

    private SourceProperties.Moodle moodle(StoredSourceSettings stored) {
        SourceProperties.Moodle environment = properties.moodle();
        return new SourceProperties.Moodle(
                stored.moodleBaseUrl(),
                token(stored.moodleTokenEncrypted(), MOODLE_TOKEN_CONTEXT, environment.token()),
                stored.moodleCourseIds(),
                stored.moodleStudentRoles(),
                stored.moodleTeacherRoles(),
                environment.connectTimeout(),
                environment.readTimeout(),
                environment.maxResponseSize()
        );
    }

    private SourceProperties.Website website(StoredSourceSettings stored) {
        SourceProperties.Website environment = properties.website();
        return new SourceProperties.Website(
                stored.websiteBaseUrl(),
                token(stored.websiteTokenEncrypted(), WEBSITE_TOKEN_CONTEXT, environment.token()),
                environment.connectTimeout(),
                environment.readTimeout(),
                environment.maxPages(),
                environment.maxPageSize()
        );
    }

    private String token(String encrypted, String context, String environmentToken) {
        return encrypted == null ? environmentToken : cipher.decrypt(encrypted, context).orElse(null);
    }

    private TokenState tokenState(String encrypted, OffsetDateTime changedAt, String context, String environmentToken) {
        if (encrypted != null) {
            return new TokenState(cipher.decrypt(encrypted, context).isPresent() ? TokenOrigin.SCREEN : TokenOrigin.UNREADABLE,
                    changedAt);
        }
        return new TokenState(blankToNull(environmentToken) == null ? TokenOrigin.NONE : TokenOrigin.ENVIRONMENT, null);
    }

    private Candidate validate(SourceSettingsRequest request) {
        if (request == null) {
            throw new InteractionValidationException("body", "Передайте настройки источников");
        }
        List<Long> courses = request.moodleCourseIds() == null ? List.of() : request.moodleCourseIds();
        if (courses.stream().anyMatch(courseId -> courseId == null || courseId <= 0)) {
            throw new InteractionValidationException("moodleCourseIds", "Номера курсов Moodle — целые положительные числа");
        }
        if (courses.size() > MAX_COURSES) {
            throw new InteractionValidationException("moodleCourseIds", "Курсов Moodle не больше " + MAX_COURSES);
        }
        List<String> studentRoles = roles(request.moodleStudentRoles(), "moodleStudentRoles");
        if (studentRoles.isEmpty()) {
            throw new InteractionValidationException("moodleStudentRoles", "Укажите хотя бы одну роль обучающихся Moodle");
        }
        return new Candidate(
                url(request.moodleBaseUrl(), properties.moodle().baseUrl(), "moodleBaseUrl", "Moodle"),
                token(request.moodleToken(), "moodleToken"),
                courses.stream().distinct().toList(),
                studentRoles,
                roles(request.moodleTeacherRoles(), "moodleTeacherRoles"),
                url(request.websiteBaseUrl(), properties.website().baseUrl(), "websiteBaseUrl", "сайта"),
                token(request.websiteToken(), "websiteToken"),
                cron(request.syncCron())
        );
    }

    private static String url(String value, String environmentValue, String field, String subject) {
        String url = blankToNull(value);
        if (url == null) {
            return null;
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw new InteractionValidationException(field, "Адрес " + subject + " длиннее " + MAX_URL_LENGTH + " символов");
        }
        if (url.equals(blankToNull(environmentValue))) {
            return url;
        }
        try {
            URI uri = new URI(url);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null) {
                return url;
            }
        } catch (URISyntaxException exception) {
            throw new InteractionValidationException(field, "Адрес " + subject + " некорректен");
        }
        throw new InteractionValidationException(field,
                "Адрес " + subject + " должен начинаться с https:// и не содержать логина, параметров и якоря");
    }

    private static String token(String value, String field) {
        String token = blankToNull(value);
        if (token != null && !TOKEN.matcher(token).matches()) {
            throw new InteractionValidationException(field, "Токен — до 500 символов без пробелов");
        }
        return token;
    }

    private static List<String> roles(List<String> values, String field) {
        List<String> roles = values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::strip)
                .filter(role -> !role.isEmpty()).distinct().toList();
        if (roles.size() > MAX_ROLES || roles.stream().anyMatch(role -> !ROLE.matcher(role).matches())) {
            throw new InteractionValidationException(field,
                    "Роли Moodle — краткие имена из строчных латинских букв, цифр и «_», не больше " + MAX_ROLES);
        }
        return roles;
    }

    private static String cron(String value) {
        String cron = environmentCron(value);
        if (cron == null) {
            return null;
        }
        if (cron.split("\\s+").length != 6 || !CronExpression.isValidExpression(cron)) {
            throw new InteractionValidationException("syncCron",
                    "Расписание — выражение cron из шести полей: секунды, минуты, часы, день, месяц, день недели");
        }
        CronExpression expression = CronExpression.parse(cron);
        ZonedDateTime first = expression.next(ZonedDateTime.now(ZONE));
        ZonedDateTime second = first == null ? null : expression.next(first);
        if (second == null) {
            throw new InteractionValidationException("syncCron", "По этому расписанию синхронизация не будет запускаться регулярно");
        }
        if (Duration.between(first, second).compareTo(MIN_SCHEDULE_INTERVAL) < 0) {
            throw new InteractionValidationException("syncCron", "Синхронизация запускается не чаще раза в 5 минут");
        }
        return cron;
    }

    private static String environmentCron(String value) {
        String cron = blankToNull(value);
        return cron == null || cron.equals("-") ? null : cron;
    }

    private static void change(List<String> changes, String name, String before, String after) {
        if (!Objects.equals(before, after)) {
            changes.add(name + ": " + (before == null ? "не задан" : before) + " → " + (after == null ? "не задан" : after));
        }
    }

    private static String scheduleText(String cron) {
        return cron == null ? "выключено" : cron;
    }

    private static String join(List<?> values) {
        return values.isEmpty() ? null : values.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record Candidate(
            String moodleBaseUrl,
            String moodleToken,
            List<Long> moodleCourseIds,
            List<String> moodleStudentRoles,
            List<String> moodleTeacherRoles,
            String websiteBaseUrl,
            String websiteToken,
            String syncCron
    ) {
    }
}

record SourceSettingsChanged() {
}

enum TokenOrigin {
    NONE,
    ENVIRONMENT,
    SCREEN,
    UNREADABLE
}

record TokenState(TokenOrigin origin, OffsetDateTime changedAt) {
}

record SourceSettingsView(
        String moodleBaseUrl,
        List<Long> moodleCourseIds,
        List<String> moodleStudentRoles,
        List<String> moodleTeacherRoles,
        TokenState moodleToken,
        String websiteBaseUrl,
        TokenState websiteToken,
        String syncCron,
        boolean saved,
        OffsetDateTime updatedAt,
        String updatedByName,
        boolean encryptionAvailable
) {
}

record SourceSettingsRequest(
        String moodleBaseUrl,
        String moodleToken,
        List<Long> moodleCourseIds,
        List<String> moodleStudentRoles,
        List<String> moodleTeacherRoles,
        String websiteBaseUrl,
        String websiteToken,
        String syncCron
) {
}

record SourceConnectionCheck(boolean ok, String message, List<String> details) {
}
