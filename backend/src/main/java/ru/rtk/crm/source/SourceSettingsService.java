package ru.rtk.crm.source;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    // Контекст входит в AAD шифротекста: токен одного источника нельзя подставить в поле другого.
    static final String MOODLE_TOKEN_CONTEXT = "source-settings:MOODLE:token";
    static final String WEBSITE_TOKEN_CONTEXT = "source-settings:WEBSITE:token";
    static final String CRM_TOKEN_CONTEXT = "source-settings:CUSTOMER_CRM:token";
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final Pattern ROLE = Pattern.compile("[a-z0-9_]{1,100}");
    private static final Pattern TOKEN = Pattern.compile("\\S{1,500}");
    static final String NEW_ADDRESS_NEEDS_TOKEN =
            "Адрес изменён: для проверки введите токен для нового адреса, сохранённый токен на него не отправляется";
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
    private final CrmClient crmClient;
    private final ApplicationEventPublisher events;

    public SourceSettingsService(
            SourceProperties properties,
            SourceSettingsRepository repository,
            SourceTokenCipher cipher,
            AuditJournalRepository auditJournalRepository,
            MoodleClient moodleClient,
            SiteApiClient siteApiClient,
            CrmClient crmClient,
            ApplicationEventPublisher events
    ) {
        this.properties = properties;
        this.repository = repository;
        this.cipher = cipher;
        this.auditJournalRepository = auditJournalRepository;
        this.moodleClient = moodleClient;
        this.siteApiClient = siteApiClient;
        this.crmClient = crmClient;
        this.events = events;
    }

    SourceProperties.Moodle moodle() {
        return repository.find().map(this::moodle).orElse(properties.moodle());
    }

    SourceProperties.Website website() {
        return repository.find().map(this::website).orElse(properties.website());
    }

    SourceProperties.Crm crm() {
        return repository.find().map(this::crm).orElse(properties.crm());
    }

    String syncCron() {
        return syncCron(repository.find());
    }

    public SourceSettingsView view(CrmProfile profile) {
        SourceSyncService.requireAdmin(profile);
        Optional<StoredSourceSettings> stored = repository.find();
        SourceProperties.Moodle moodle = stored.map(this::moodle).orElse(properties.moodle());
        SourceProperties.Website website = stored.map(this::website).orElse(properties.website());
        SourceProperties.Crm crm = stored.map(this::crm).orElse(properties.crm());
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
                cipher.available(),
                blankToNull(crm.baseUrl()),
                tokenState(stored.map(StoredSourceSettings::crmTokenEncrypted).orElse(null),
                        stored.map(StoredSourceSettings::crmTokenChangedAt).orElse(null), CRM_TOKEN_CONTEXT,
                        properties.crm().token())
        );
    }

    public SourceSettingsView update(CrmProfile profile, SourceSettingsRequest request, String requestId) {
        SourceSyncService.requireAdmin(profile);
        Candidate candidate = validate(request);
        // Без ключа шифрования токен не сохраняется: открытым текстом в базу он не попадёт.
        if ((candidate.moodleToken() != null || candidate.websiteToken() != null || candidate.crmToken() != null)
                && !cipher.available()) {
            throw new InteractionValidationException(candidate.moodleToken() != null ? "moodleToken"
                    : candidate.websiteToken() != null ? "websiteToken" : "crmToken",
                    "Токен нельзя сохранить: в конфигурации развёртывания не задан ключ шифрования SOURCES_SETTINGS_KEY");
        }
        Optional<StoredSourceSettings> previous = repository.find();
        SourceProperties.Moodle moodleBefore = previous.map(this::moodle).orElse(properties.moodle());
        SourceProperties.Website websiteBefore = previous.map(this::website).orElse(properties.website());
        SourceProperties.Crm crmBefore = previous.map(this::crm).orElse(properties.crm());
        // Токен привязан к адресу: при смене адреса сохранённый токен на новый адрес не уходит, нужен новый.
        requireTokenForNewAddress("moodleToken", "Moodle", moodleBefore.baseUrl(), candidate.moodleBaseUrl(),
                candidate.moodleToken(), savedToken(previous.map(StoredSourceSettings::moodleTokenEncrypted), properties.moodle().token()));
        requireTokenForNewAddress("websiteToken", "сайта", websiteBefore.baseUrl(), candidate.websiteBaseUrl(),
                candidate.websiteToken(), savedToken(previous.map(StoredSourceSettings::websiteTokenEncrypted), properties.website().token()));
        requireTokenForNewAddress("crmToken", "CRM заказчика", crmBefore.baseUrl(), candidate.crmBaseUrl(),
                candidate.crmToken(), savedToken(previous.map(StoredSourceSettings::crmTokenEncrypted), properties.crm().token()));
        String cronBefore = syncCron(previous);
        OffsetDateTime now = OffsetDateTime.now();
        // Пустое поле токена оставляет сохранённый токен; время смены токена меняется только при замене.
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
                candidate.crmBaseUrl(),
                candidate.crmToken() != null
                        ? cipher.encrypt(candidate.crmToken(), CRM_TOKEN_CONTEXT)
                        : previous.map(StoredSourceSettings::crmTokenEncrypted).orElse(null),
                candidate.crmToken() != null ? now : previous.map(StoredSourceSettings::crmTokenChangedAt).orElse(null),
                now,
                null
        );
        repository.save(next, profile.id());
        // В журнал идут значения адресов, курсов и ролей, но не токены: о замене токена пишется только факт.
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
        change(changes, "адрес CRM заказчика", blankToNull(crmBefore.baseUrl()), candidate.crmBaseUrl());
        if (candidate.crmToken() != null) {
            changes.add("токен CRM заказчика заменён");
        }
        change(changes, "расписание", scheduleText(cronBefore), scheduleText(candidate.syncCron()));
        String details = changes.isEmpty() ? "значения сохранены без изменений" : String.join("; ", changes);
        auditJournalRepository.record(AuditAction.SOURCE_SETTINGS_CHANGED, profile.id(), "SOURCE", null,
                "Подключение источников", previous.isPresent() ? details : "первое сохранение на экране; " + details, requestId);
        events.publishEvent(new SourceSettingsChanged());
        return view(profile);
    }

    // Сброс возвращает значения конфигурации целиком, включая токены: токены с экрана без своего адреса остались бы
    // привязанными к чужому.
    public void reset(CrmProfile profile, String requestId) {
        SourceSyncService.requireAdmin(profile);
        repository.delete();
        auditJournalRepository.record(AuditAction.SOURCE_SETTINGS_CHANGED, profile.id(), "SOURCE", null,
                "Подключение источников", "восстановлены значения конфигурации развёртывания; сохранённые на экране токены удалены",
                requestId);
        events.publishEvent(new SourceSettingsChanged());
    }

    // Проверка строит настройки только в памяти и ничего не сохраняет. Сохранённый токен не уходит на новый адрес и
    // здесь: иначе проверкой можно было бы увести токен на чужой сервер.
    public SourceConnectionCheck check(CrmProfile profile, SourceCode source, SourceSettingsRequest request) {
        SourceSyncService.requireAdmin(profile);
        if (source == SourceCode.LABOR_MARKET) {
            throw new InteractionValidationException("source",
                    "Связь с источником «Рынок труда» проверяется в его блоке подключения");
        }
        Candidate candidate = validate(request);
        try {
            if (source == SourceCode.MOODLE) {
                SourceProperties.Moodle current = moodle();
                if (savedTokenWouldGoToNewAddress(current.baseUrl(), candidate.moodleBaseUrl(), candidate.moodleToken(), current.token())) {
                    return new SourceConnectionCheck(false, NEW_ADDRESS_NEEDS_TOKEN, List.of());
                }
                SourceProperties.Moodle moodle = new SourceProperties.Moodle(
                        candidate.moodleBaseUrl(),
                        candidate.moodleToken() != null ? candidate.moodleToken() : current.token(),
                        candidate.moodleCourseIds(),
                        candidate.moodleStudentRoles(),
                        candidate.moodleTeacherRoles(),
                        current.connectTimeout(),
                        current.readTimeout(),
                        current.maxResponseSize(),
                        current.teacherCourseLabel()
                );
                if (!moodle.configured()) {
                    return new SourceConnectionCheck(false, "Для проверки укажите адрес Moodle, токен и хотя бы один курс", List.of());
                }
                return new SourceConnectionCheck(true, "Связь с Moodle есть: токен принят, курсы доступны сервису CRM",
                        moodleClient.check(moodle));
            }
            if (source == SourceCode.CUSTOMER_CRM) {
                SourceProperties.Crm current = crm();
                if (savedTokenWouldGoToNewAddress(current.baseUrl(), candidate.crmBaseUrl(), candidate.crmToken(), current.token())) {
                    return new SourceConnectionCheck(false, NEW_ADDRESS_NEEDS_TOKEN, List.of());
                }
                SourceProperties.Crm crm = new SourceProperties.Crm(
                        candidate.crmBaseUrl(),
                        candidate.crmToken() != null ? candidate.crmToken() : current.token(),
                        current.connectTimeout(),
                        current.readTimeout(),
                        current.maxPages(),
                        current.maxPageSize()
                );
                if (!crm.configured()) {
                    return new SourceConnectionCheck(false, "Для проверки укажите адрес CRM заказчика", List.of());
                }
                crmClient.check(crm);
                return new SourceConnectionCheck(true, "Связь с CRM заказчика есть: ответ соответствует контракту обмена", List.of());
            }
            SourceProperties.Website current = website();
            if (savedTokenWouldGoToNewAddress(current.baseUrl(), candidate.websiteBaseUrl(), candidate.websiteToken(), current.token())) {
                return new SourceConnectionCheck(false, NEW_ADDRESS_NEEDS_TOKEN, List.of());
            }
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

    // Адрес, курсы и роли берутся с экрана, а таймауты и лимиты всегда из конфигурации: на экране они не настраиваются.
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
                environment.maxResponseSize(),
                environment.teacherCourseLabel()
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

    private SourceProperties.Crm crm(StoredSourceSettings stored) {
        SourceProperties.Crm environment = properties.crm();
        return new SourceProperties.Crm(
                stored.crmBaseUrl(),
                token(stored.crmTokenEncrypted(), CRM_TOKEN_CONTEXT, environment.token()),
                environment.connectTimeout(),
                environment.readTimeout(),
                environment.maxPages(),
                environment.maxPageSize()
        );
    }

    /**
     * Сохранённый токен принадлежит прежнему адресу. Если администратор (или тот, кто получил его сеанс) указал другой
     * адрес и токен не ввёл, сохранённый токен ушёл бы на чужой сервер — при сохранении настроек и при проверке связи.
     */
    static void requireTokenForNewAddress(String field, String subject, String addressBefore, String addressAfter,
                                                  String enteredToken, boolean hasSavedToken) {
        if (hasSavedToken && enteredToken == null && addressAfter != null && !sameAddress(addressBefore, addressAfter)) {
            throw new InteractionValidationException(field,
                    "Адрес " + subject + " изменён: введите токен для нового адреса, сохранённый токен на него не отправляется");
        }
    }

    static boolean savedTokenWouldGoToNewAddress(String addressBefore, String addressAfter, String enteredToken,
                                                         String savedToken) {
        return enteredToken == null && blankToNull(savedToken) != null && addressAfter != null
                && !sameAddress(addressBefore, addressAfter);
    }

    static boolean savedToken(Optional<String> encrypted, String environmentToken) {
        return encrypted.orElse(null) != null || blankToNull(environmentToken) != null;
    }

    private static boolean sameAddress(String before, String after) {
        return Objects.equals(normalizedAddress(before), normalizedAddress(after));
    }

    // Регистр и хвостовые «/» сменой адреса не считаются: токен уходит на тот же сервер.
    private static String normalizedAddress(String address) {
        String value = blankToNull(address);
        if (value == null) {
            return null;
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end).toLowerCase(Locale.ROOT);
    }

    // Токен с экрана главнее токена конфигурации. Нечитаемый (другой ключ, повреждение) даёт null, а не откат на токен
    // конфигурации: администратор видит состояние «нечитаем» и вводит токен заново.
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
                cron(request.syncCron()),
                url(request.crmBaseUrl(), properties.crm().baseUrl(), "crmBaseUrl", "CRM заказчика"),
                token(request.crmToken(), "crmToken")
        );
    }

    // С экрана принимается только https без логина, параметров и якоря: токен не пойдёт по открытому каналу, а логин и
    // параметры не осядут в журналах.
    static String url(String value, String environmentValue, String field, String subject) {
        String url = blankToNull(value);
        if (url == null) {
            return null;
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw new InteractionValidationException(field, "Адрес " + subject + " длиннее " + MAX_URL_LENGTH + " символов");
        }
        // Адрес из конфигурации задаёт администратор сервера; проверке с экрана он не подлежит (в стенде может быть
        // http).
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

    static String token(String value, String field) {
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
        // Частые запуски нагружали бы источники, а запуск при незавершённом предыдущем всё равно отклоняется.
        if (Duration.between(first, second).compareTo(MIN_SCHEDULE_INTERVAL) < 0) {
            throw new InteractionValidationException("syncCron", "Синхронизация запускается не чаще раза в 5 минут");
        }
        return cron;
    }

    private static String environmentCron(String value) {
        String cron = blankToNull(value);
        return cron == null || cron.equals("-") ? null : cron;
    }

    static void change(List<String> changes, String name, String before, String after) {
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

    static String blankToNull(String value) {
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
            String syncCron,
            String crmBaseUrl,
            String crmToken
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
        boolean encryptionAvailable,
        String crmBaseUrl,
        TokenState crmToken
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
        String syncCron,
        String crmBaseUrl,
        String crmToken
) {
    SourceSettingsRequest(
            String moodleBaseUrl,
            String moodleToken,
            List<Long> moodleCourseIds,
            List<String> moodleStudentRoles,
            List<String> moodleTeacherRoles,
            String websiteBaseUrl,
            String websiteToken,
            String syncCron
    ) {
        this(moodleBaseUrl, moodleToken, moodleCourseIds, moodleStudentRoles, moodleTeacherRoles, websiteBaseUrl, websiteToken,
                syncCron, null, null);
    }
}

record SourceConnectionCheck(boolean ok, String message, List<String> details) {
}
