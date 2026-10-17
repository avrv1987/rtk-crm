package ru.rtk.crm.source;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MoodleClient {
    static final String REST_PATH = "/webservice/rest/server.php";

    // Эти коды говорят о настройке доступа, а не о данных: пользователь получает «нет доступа», а не «некорректный
    // ответ».
    private static final Set<String> ACCESS_ERRORS = Set.of(
            "invalidtoken", "accessexception", "nopermissions", "requireloginerror", "usernotfullysetup",
            "servicerequireslogin", "accessdenied", "errorcoursecontextnotvalid", "cannotviewreport"
    );
    // Токен или сервис не принимаются целиком: продолжать остальные курсы бессмысленно.
    private static final Set<String> RUN_WIDE_ERRORS = Set.of(
            "invalidtoken", "servicerequireslogin", "requireloginerror", "usernotfullysetup"
    );
    private static final Set<String> UNKNOWN_USER_COMPLETION = Set.of("usernotenroled", "notenroled");
    // Статус завершения Moodle отдаёт только по действующему зачислению; у приостановленного — nottracked.
    private static final Set<String> UNREADABLE_ENDED_COMPLETION = Set.of("nottracked", "usernotenroled", "notenroled");
    private static final int SEPARATE_GROUPS = 1;
    private static final int ATTEMPTS = 3;
    private static final int UNAVAILABLE_COURSES_LIMIT = 3;
    private static final int DEFAULT_INACTIVE_AFTER_DAYS = 14;
    private static final long DAY_SECONDS = 86_400;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration retryPause;
    private final int inactiveAfterDays;

    @Autowired
    public MoodleClient(SourceProperties properties, ObjectMapper objectMapper,
                        @Value("${app.lms-signals.inactive-after-days:14}") int inactiveAfterDays) {
        this(properties, objectMapper, Duration.ofSeconds(1), inactiveAfterDays);
    }

    MoodleClient(SourceProperties properties, ObjectMapper objectMapper, Duration retryPause) {
        this(properties, objectMapper, retryPause, DEFAULT_INACTIVE_AFTER_DAYS);
    }

    MoodleClient(SourceProperties properties, ObjectMapper objectMapper, Duration retryPause, int inactiveAfterDays) {
        this.objectMapper = objectMapper;
        this.retryPause = retryPause;
        this.inactiveAfterDays = inactiveAfterDays;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.moodle().connectTimeout())
                // Переадресации не выполняются: токен лежит в теле POST, и переход на другой адрес отдал бы его чужому
                // хосту. Токен привязан к адресу Moodle, поэтому адрес, отличающийся от wwwroot, считается ошибкой
                // настройки.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    record CourseFailure(long courseId, SourceFetchException error) {
    }

    record Fetched(List<LearningUnit> units, List<CourseFailure> failures) {
    }

    /**
     * Ошибка одного курса не останавливает остальные: курс попадает в failures. Весь запуск прерывается, только если
     * Moodle не принимает токен, не отвечает по нескольким курсам подряд или не вернул данные ни по одному курсу.
     *
     * @param flowOfGroup ключ потока CRM (вуз, программа, начало) для группы по её внешнему ключу «курс:группа» или null,
     *                    если группа не сопоставлена; обучающийся, уже учтённый в другой группе того же потока,
     *                    второй раз не считается
     */
    Fetched fetch(SourceProperties.Moodle settings, List<Long> courseIds, Function<String, String> flowOfGroup) {
        Map<Long, JsonNode> courses = courses(settings, courseIds);
        List<LearningUnit> units = new ArrayList<>();
        List<CourseFailure> failures = new ArrayList<>();
        int unavailableInRow = 0;
        for (Long courseId : courseIds) {
            JsonNode course = courses.get(courseId);
            if (course == null) {
                failures.add(new CourseFailure(courseId, SourceFetchException.invalidResponse(notFound(List.of(courseId)))));
                unavailableInRow = 0;
                continue;
            }
            try {
                units.addAll(courseUnits(settings, courseId, course, flowOfGroup));
                unavailableInRow = 0;
            } catch (RunWideException exception) {
                // Стоит выше SourceFetchException: RunWideException — его подкласс, иначе отказ токена записался бы как
                // сбой одного курса.
                throw exception;
            } catch (SourceFetchException exception) {
                unavailableInRow = "SOURCE_UNAVAILABLE".equals(exception.code()) ? unavailableInRow + 1 : 0;
                // Несколько курсов подряд недоступны — скорее всего, недоступен сам Moodle: запуск прерывается, а не
                // копит ошибки по каждому курсу.
                if (unavailableInRow >= UNAVAILABLE_COURSES_LIMIT) {
                    throw exception;
                }
                failures.add(new CourseFailure(courseId, exception));
            }
        }
        // Если не получен ни один курс, запуск неуспешен: пустой результат иначе выглядел бы как успешная
        // синхронизация.
        if (units.isEmpty() && !failures.isEmpty()) {
            SourceFetchException first = failures.getFirst().error();
            throw failures.size() == 1 ? first : new SourceFetchException(first.code(),
                    "Данные не получены ни по одному курсу: " + failures.stream()
                            .map(failure -> failure.courseId() + " — " + failure.error().getMessage())
                            .collect(Collectors.joining("; ")));
        }
        return new Fetched(units, failures);
    }

    List<String> check(SourceProperties.Moodle settings) {
        Map<Long, JsonNode> courses = courses(settings, settings.courseIds());
        List<Long> missing = settings.courseIds().stream().filter(courseId -> !courses.containsKey(courseId)).toList();
        if (!missing.isEmpty()) {
            throw SourceFetchException.invalidResponse(notFound(missing));
        }
        return settings.courseIds().stream()
                .map(courseId -> courseId + " «" + courses.get(courseId).path("fullname").asText() + "»")
                .toList();
    }

    private static String notFound(List<Long> missing) {
        return (missing.size() == 1 ? "Курс " : "Курсы ")
                + missing.stream().map(String::valueOf).collect(Collectors.joining(", "))
                + " из списка курсов CRM не найден в Moodle или недоступен сервису CRM"
                + " (скрытый курс виден сервису только с правом moodle/course:viewhiddencourses)";
    }

    private Map<Long, JsonNode> courses(SourceProperties.Moodle settings, List<Long> courseIds) {
        String ids = courseIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        JsonNode courses = call(settings, "core_course_get_courses_by_field", params("field", "ids", "value", ids)).path("courses");
        if (!courses.isArray()) {
            throw SourceFetchException.invalidResponse("Ответ core_course_get_courses_by_field не содержит массив courses");
        }
        Map<Long, JsonNode> byId = new HashMap<>();
        courses.forEach(course -> byId.put(course.path("id").asLong(), course));
        return byId;
    }

    /**
     * lastAccess — секунды Unix последнего входа в курс, 0 — не заходил, null — Moodle поле не отдал (скрыто настройкой
     * hiddenuserfields). Хранится только в памяти запуска.
     */
    private record Member(long id, boolean student, boolean teacher, Set<Long> groups, Long lastAccess) {
    }

    private List<Member> members(SourceProperties.Moodle settings, long courseId, boolean onlyActive) {
        JsonNode users = array(call(settings, "core_enrol_get_enrolled_users", params(
                "courseid", Long.toString(courseId),
                "options[0][name]", "onlyactive",
                "options[0][value]", onlyActive ? "1" : "0",
                "options[1][name]", "userfields",
                "options[1][value]", "id,roles,groups,lastcourseaccess"
        )), "core_enrol_get_enrolled_users");
        List<Member> members = new ArrayList<>();
        for (JsonNode user : users) {
            JsonNode roles = user.path("roles");
            if (!roles.isArray()) {
                throw SourceFetchException.invalidResponse("Moodle не вернул роли участников курса " + courseId + "; проверьте права сервиса CRM");
            }
            boolean student = false;
            boolean teacher = false;
            for (JsonNode role : roles) {
                String shortname = role.path("shortname").asText();
                student |= settings.studentRoles().contains(shortname);
                teacher |= settings.teacherRoles().contains(shortname);
            }
            Set<Long> groups = new HashSet<>();
            user.path("groups").forEach(group -> groups.add(group.path("id").asLong()));
            JsonNode lastAccess = user.path("lastcourseaccess");
            members.add(new Member(user.path("id").asLong(), student, teacher, groups,
                    lastAccess.canConvertToLong() ? lastAccess.asLong() : null));
        }
        return members;
    }

    private List<LearningUnit> courseUnits(SourceProperties.Moodle settings, long courseId, JsonNode course,
                                           Function<String, String> flowOfGroup) {
        JsonNode groups = array(call(settings, "core_group_get_course_groups", params("courseid", Long.toString(courseId))),
                "core_group_get_course_groups");
        List<Member> active = members(settings, courseId, true);
        // Без права accessallgroups Moodle молча отдаёт пустой список, но только в режиме раздельных групп;
        // в остальных режимах пустой курс с заранее созданными группами — обычное состояние до зачисления.
        if (active.isEmpty() && !groups.isEmpty() && course.path("groupmode").asInt() == SEPARATE_GROUPS) {
            throw SourceFetchException.unauthorized(
                    "Moodle не вернул ни одного участника курса " + courseId + ", хотя в нём есть группы;"
                            + " сервису CRM нужно право moodle/site:accessallgroups"
                            + " (если курс ещё пуст, сообщение исчезнет после первого зачисления)"
            );
        }
        Set<Long> students = new HashSet<>();
        Set<Long> teachers = new HashSet<>();
        Map<Long, Set<Long>> members = new HashMap<>();
        Map<Long, Long> lastAccess = new HashMap<>();
        for (Member member : active) {
            lastAccess.put(member.id(), member.lastAccess());
            if (member.student()) {
                students.add(member.id());
            }
            if (member.teacher()) {
                teachers.add(member.id());
            }
            member.groups().forEach(groupId -> members.computeIfAbsent(groupId, key -> new HashSet<>()).add(member.id()));
        }
        // Без отслеживания завершения статус не запрашивается, а «завершили» остаётся null: это «нет данных», а не
        // нули.
        Map<Long, Boolean> completion = course.path("enablecompletion").asInt() == 1 ? completion(settings, courseId, students) : null;
        if (completion != null) {
            addCompletedAfterEnrolmentEnd(settings, courseId, active, students, members, completion, lastAccess);
        }
        String shortname = course.path("shortname").asText();
        String fullname = course.path("fullname").asText();
        long now = System.currentTimeMillis() / 1000;
        List<LearningUnit> groupUnits = new ArrayList<>();
        Map<String, Set<Long>> countedByFlow = new HashMap<>();
        int groupsWithParticipants = 0;
        for (JsonNode group : sortedById(groups)) {
            long groupId = group.path("id").asLong();
            // Группа считает только учащихся курса: участник группы с ролью не из studentRoles (преподаватель) в
            // учащиеся не попадает.
            Set<Long> groupStudents = new HashSet<>(students);
            groupStudents.retainAll(members.getOrDefault(groupId, Set.of()));
            Set<Long> groupTeachers = new HashSet<>(teachers);
            groupTeachers.retainAll(members.getOrDefault(groupId, Set.of()));
            if (!groupStudents.isEmpty()) {
                groupsWithParticipants++;
            }
            // Человек в двух группах одного потока CRM учитывается в группе с меньшим номером, в следующих остаются
            // ещё не учтённые. Группы разных потоков не связаны: у каждого потока свой счёт.
            String flow = flowOfGroup == null ? null : flowOfGroup.apply(courseId + ":" + groupId);
            int shared = 0;
            if (flow != null) {
                Set<Long> counted = countedByFlow.computeIfAbsent(flow, key -> new HashSet<>());
                Set<Long> already = new HashSet<>(groupStudents);
                already.retainAll(counted);
                shared = already.size();
                groupStudents.removeAll(already);
                counted.addAll(groupStudents);
            }
            groupUnits.add(unit(courseId, groupId, shortname, fullname, group.path("name").asText(), groupStudents,
                    groupTeachers.size(), completion, 0, shared, activity(groupStudents, groupTeachers, lastAccess, now)));
        }
        List<LearningUnit> units = new ArrayList<>();
        // Строка курса целиком считает всех учащихся независимо от групп: человек может состоять в нескольких группах
        // или ни в одной.
        units.add(unit(courseId, null, shortname, fullname, null, students, teachers.size(), completion, groupsWithParticipants, 0,
                activity(students, teachers, lastAccess, now)));
        units.addAll(groupUnits);
        return units;
    }

    private static List<JsonNode> sortedById(JsonNode groups) {
        List<JsonNode> sorted = new ArrayList<>();
        groups.forEach(sorted::add);
        sorted.sort(Comparator.comparingLong(group -> group.path("id").asLong()));
        return sorted;
    }

    /**
     * Завершившие по приостановленному или оконченному зачислению остаются в счёте, если Moodle отдаёт их статус.
     * Стандартная функция отдаёт статус только по действующему зачислению (nottracked): тогда эти люди выпадают
     * из счёта, а в CRM остаётся последнее наблюдение до окончания зачисления. Запросы идут, пока Moodle отвечает,
     * и прекращаются на первом отказе, чтобы не слать десятки заведомо отклоняемых запросов.
     */
    private void addCompletedAfterEnrolmentEnd(SourceProperties.Moodle settings, long courseId, List<Member> active,
                                               Set<Long> students, Map<Long, Set<Long>> members, Map<Long, Boolean> completion,
                                               Map<Long, Long> lastAccess) {
        Set<Long> activeIds = active.stream().map(Member::id).collect(Collectors.toSet());
        List<Member> ended = members(settings, courseId, false).stream()
                .filter(member -> member.student() && !activeIds.contains(member.id()))
                .toList();
        if (ended.isEmpty()) {
            return;
        }
        Map<Long, Boolean> endedCompletion = completion(settings, courseId, ended.stream().map(Member::id).toList(), true);
        for (Member member : ended) {
            // Возвращаются в счёт только подтверждённо завершившие: у остальных с оконченным зачислением статус
            // неизвестен.
            if (Boolean.TRUE.equals(endedCompletion.get(member.id()))) {
                students.add(member.id());
                completion.put(member.id(), true);
                lastAccess.put(member.id(), member.lastAccess());
                member.groups().forEach(groupId -> members.computeIfAbsent(groupId, key -> new HashSet<>()).add(member.id()));
            }
        }
    }

    private Map<Long, Boolean> completion(SourceProperties.Moodle settings, long courseId, Collection<Long> userIds) {
        return completion(settings, courseId, userIds, false);
    }

    private Map<Long, Boolean> completion(SourceProperties.Moodle settings, long courseId, Collection<Long> userIds,
                                          boolean enrolmentEnded) {
        Map<Long, Boolean> result = new HashMap<>();
        for (Long userId : userIds) {
            String function = "core_completion_get_course_completion_status";
            JsonNode body = post(settings, function, params("courseid", Long.toString(courseId), "userid", Long.toString(userId)));
            String error = errorCode(body);
            // Для оконченных зачислений первый отказ значит, что Moodle не отдаёт их статус вовсе: запросы
            // прекращаются, полученное остаётся.
            if (enrolmentEnded && error != null && (UNREADABLE_ENDED_COMPLETION.contains(error) || "nocriteriaset".equals(error))) {
                return result;
            }
            // В курсе не заданы условия завершения: статуса нет ни у кого, поэтому счёт «завершили» не ведётся вовсе
            // (null), а не нулевой.
            if ("nocriteriaset".equals(error)) {
                return null;
            }
            if (error != null && UNKNOWN_USER_COMPLETION.contains(error)) {
                // Не зачислен (статус недоступен): неизвестно, а не «не завершил».
                result.put(userId, null);
                continue;
            }
            if (error != null) {
                throw moodleError(function, error);
            }
            JsonNode completed = body.path("completionstatus").path("completed");
            if (!completed.isBoolean()) {
                throw SourceFetchException.invalidResponse("Ответ " + function + " не содержит completionstatus.completed");
            }
            result.put(userId, completed.booleanValue());
        }
        return result;
    }

    // Активность отдаётся только целиком: если Moodle скрыл вход хотя бы у одного участника (hiddenuserfields), счёт
    // был бы занижен и выглядел бы как неактивность.
    private LearningUnit.Activity activity(Set<Long> students, Set<Long> teachers, Map<Long, Long> lastAccess, long now) {
        if (students.stream().anyMatch(id -> lastAccess.get(id) == null) || teachers.stream().anyMatch(id -> lastAccess.get(id) == null)) {
            return null;
        }
        int active7d = 0;
        int active30d = 0;
        int never = 0;
        int inactive = 0;
        for (Long id : students) {
            long last = lastAccess.get(id);
            // 0 — ни разу не заходил: считается отдельно и в «неактивных» (заходили, но давно) не повторяется.
            if (last <= 0) {
                never++;
                continue;
            }
            active7d += last >= now - 7 * DAY_SECONDS ? 1 : 0;
            active30d += last >= now - 30 * DAY_SECONDS ? 1 : 0;
            inactive += last < now - inactiveAfterDays * DAY_SECONDS ? 1 : 0;
        }
        // Окна преподавателей фиксированы (7 и 14 дней); настраиваемый порог неактивности относится только к учащимся.
        int teachers7d = (int) teachers.stream().filter(id -> lastAccess.get(id) >= now - 7 * DAY_SECONDS).count();
        int teachers14d = (int) teachers.stream().filter(id -> lastAccess.get(id) >= now - 14 * DAY_SECONDS).count();
        return new LearningUnit.Activity(active7d, active30d, never, inactive, teachers7d, teachers14d);
    }

    private static LearningUnit unit(
            long courseId,
            Long groupId,
            String shortname,
            String fullname,
            String groupName,
            Set<Long> students,
            int teachers,
            Map<Long, Boolean> completion,
            int groupsCount,
            int shared,
            LearningUnit.Activity activity
    ) {
        Integer completed = null;
        Integer notCompleted = null;
        int unknown = students.size();
        if (completion != null) {
            completed = (int) students.stream().filter(userId -> Boolean.TRUE.equals(completion.get(userId))).count();
            notCompleted = (int) students.stream().filter(userId -> Boolean.FALSE.equals(completion.get(userId))).count();
            unknown = students.size() - completed - notCompleted;
        }
        return new LearningUnit(courseId, groupId, shortname, fullname, groupName, students.size(), teachers,
                completed, notCompleted, unknown, groupsCount, shared, activity);
    }

    private JsonNode call(SourceProperties.Moodle settings, String function, Map<String, String> parameters) {
        JsonNode body = post(settings, function, parameters);
        String error = errorCode(body);
        if (error != null) {
            throw moodleError(function, error);
        }
        return body;
    }

    /**
     * Чтения идемпотентны, поэтому таймаут и ответы 5xx повторяются: два повтора с растущей паузой. Остальные ошибки
     * (4xx, ответ с errorcode, неверный JSON) повторять бессмысленно.
     */
    private JsonNode post(SourceProperties.Moodle settings, String function, Map<String, String> parameters) {
        for (int attempt = 1; ; attempt++) {
            try {
                return send(settings, function, parameters);
            } catch (TemporaryFailure failure) {
                if (attempt == ATTEMPTS) {
                    throw failure.cause();
                }
                pause(retryPause.multipliedBy(attempt));
            }
        }
    }

    private static void pause(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw SourceFetchException.unavailable("Синхронизация прервана");
        }
    }

    private JsonNode send(SourceProperties.Moodle settings, String function, Map<String, String> parameters) {
        StringJoiner form = new StringJoiner("&");
        Map<String, String> fields = new LinkedHashMap<>();
        // Токен идёт в теле запроса, а не в адресе: адрес попадает в журналы прокси и тексты ошибок.
        fields.put("wstoken", settings.token());
        fields.put("wsfunction", function);
        fields.put("moodlewsrestformat", "json");
        fields.putAll(parameters);
        fields.forEach((name, value) -> form.add(encode(name) + "=" + encode(value)));
        HttpRequest request = HttpRequest.newBuilder(endpoint(settings))
                .timeout(settings.readTimeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException exception) {
            // Повторяется только таймаут: разовая медленная выдача возможна, а обрыв соединения сообщается сразу.
            throw new TemporaryFailure(SourceFetchException.unreachable("Moodle", exception));
        } catch (IOException exception) {
            throw SourceFetchException.unreachable("Moodle", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw SourceFetchException.unavailable("Синхронизация прервана");
        }
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            // HTTP-отказ означает отказ токена или сервиса целиком, как и invalidtoken: запуск прерывается.
            if (status == 401 || status == 403) {
                throw new RunWideException(SourceFetchException.unauthorized(
                        "Moodle ответил HTTP " + status + "; проверьте токен Moodle и внешний сервис"));
            }
            if (status >= 300 && status < 400) {
                throw SourceFetchException.unavailable(
                        "Moodle ответил переадресацией HTTP " + status + "; адрес Moodle должен совпадать с его wwwroot"
                );
            }
            if (status >= 500) {
                throw new TemporaryFailure(SourceFetchException.unavailable("Moodle ответил HTTP " + status));
            }
            if (status != 200) {
                throw SourceFetchException.unavailable("Moodle ответил HTTP " + status);
            }
            long limit = settings.maxResponseSize().toBytes();
            // Читается на байт больше лимита: так превышение отличается от ровно допустимого размера, а весь ответ в
            // память не грузится.
            byte[] bytes = body.readNBytes(Math.toIntExact(limit + 1));
            if (bytes.length > limit) {
                throw SourceFetchException.invalidResponse("Ответ Moodle на " + function + " больше " + settings.maxResponseSize());
            }
            JsonNode json = objectMapper.readTree(bytes);
            if (json == null || json.isMissingNode()) {
                throw SourceFetchException.invalidResponse("Moodle вернул пустой ответ на " + function);
            }
            return json;
        } catch (IOException exception) {
            throw SourceFetchException.invalidResponse("Ответ Moodle на " + function + " не является корректным JSON");
        }
    }

    private static URI endpoint(SourceProperties.Moodle settings) {
        String base = settings.baseUrl().strip();
        try {
            URI uri = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + REST_PATH);
            if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                return uri;
            }
        } catch (IllegalArgumentException exception) {
            throw SourceFetchException.unavailable("Адрес Moodle некорректен");
        }
        throw SourceFetchException.unavailable("Адрес Moodle должен начинаться с http:// или https://");
    }

    private static JsonNode array(JsonNode body, String function) {
        if (!body.isArray()) {
            throw SourceFetchException.invalidResponse("Ответ " + function + " не является массивом");
        }
        return body;
    }

    // Ошибки Moodle приходят с HTTP 200 и телом {exception, errorcode}, поэтому по статусу ответа их не увидеть.
    private static String errorCode(JsonNode body) {
        return body.isObject() && body.has("exception") ? body.path("errorcode").asText("unknown") : null;
    }

    private static SourceFetchException moodleError(String function, String errorCode) {
        if (ACCESS_ERRORS.contains(errorCode)) {
            SourceFetchException exception = SourceFetchException.unauthorized(
                    "Moodle отклонил запрос " + function + " (" + errorCode + "); проверьте токен Moodle, внешний сервис и роль сервиса CRM"
            );
            return RUN_WIDE_ERRORS.contains(errorCode) ? new RunWideException(exception) : exception;
        }
        return SourceFetchException.invalidResponse("Moodle вернул ошибку " + errorCode + " на " + function);
    }

    private static Map<String, String> params(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Отказ токена или сервиса: запуск прерывается целиком, а не по одному курсу.
     */
    private static final class RunWideException extends SourceFetchException {
        RunWideException(SourceFetchException cause) {
            super(cause.code(), cause.getMessage());
        }
    }

    /**
     * Временная ошибка (таймаут, HTTP 5xx): запрос стоит повторить. Не выходит за пределы {@link #post}.
     */
    private static final class TemporaryFailure extends RuntimeException {
        private final SourceFetchException cause;

        TemporaryFailure(SourceFetchException cause) {
            super(cause.getMessage(), null, false, false);
            this.cause = cause;
        }

        SourceFetchException cause() {
            return cause;
        }
    }
}
