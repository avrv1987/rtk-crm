package ru.rtk.crm.source;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class MoodleClient {
    static final String REST_PATH = "/webservice/rest/server.php";

    private static final Set<String> ACCESS_ERRORS = Set.of(
            "invalidtoken", "accessexception", "nopermissions", "requireloginerror", "usernotfullysetup",
            "servicerequireslogin", "accessdenied", "errorcoursecontextnotvalid", "cannotviewreport"
    );
    private static final Set<String> UNKNOWN_USER_COMPLETION = Set.of("usernotenroled", "notenroled");

    private final SourceProperties.Moodle properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MoodleClient(SourceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties.moodle();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    boolean configured() {
        return properties.configured();
    }

    List<Long> courseIds() {
        return properties.courseIds();
    }

    List<LearningUnit> fetch(List<Long> courseIds) {
        String ids = courseIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        JsonNode courses = call("core_course_get_courses_by_field", params("field", "ids", "value", ids)).path("courses");
        if (!courses.isArray()) {
            throw SourceFetchException.invalidResponse("Ответ core_course_get_courses_by_field не содержит массив courses");
        }
        Map<Long, JsonNode> byId = new HashMap<>();
        courses.forEach(course -> byId.put(course.path("id").asLong(), course));
        List<LearningUnit> units = new ArrayList<>();
        for (Long courseId : courseIds) {
            JsonNode course = byId.get(courseId);
            if (course == null) {
                throw SourceFetchException.invalidResponse(
                        "Курс " + courseId + " из MOODLE_COURSE_IDS не найден в Moodle или недоступен сервису CRM"
                );
            }
            units.addAll(courseUnits(courseId, course));
        }
        return units;
    }

    private List<LearningUnit> courseUnits(long courseId, JsonNode course) {
        String id = Long.toString(courseId);
        JsonNode groups = array(call("core_group_get_course_groups", params("courseid", id)), "core_group_get_course_groups");
        JsonNode users = array(call("core_enrol_get_enrolled_users", params(
                "courseid", id,
                "options[0][name]", "onlyactive",
                "options[0][value]", "1",
                "options[1][name]", "userfields",
                "options[1][value]", "id,roles,groups"
        )), "core_enrol_get_enrolled_users");
        if (users.isEmpty() && !groups.isEmpty()) {
            throw SourceFetchException.unauthorized(
                    "Moodle не вернул ни одного участника курса " + courseId + ", хотя в нём есть группы;"
                            + " сервису CRM нужно право moodle/site:accessallgroups"
            );
        }
        Set<Long> students = new HashSet<>();
        Set<Long> teachers = new HashSet<>();
        Map<Long, Set<Long>> members = new HashMap<>();
        for (JsonNode user : users) {
            long userId = user.path("id").asLong();
            JsonNode roles = user.path("roles");
            if (!roles.isArray()) {
                throw SourceFetchException.invalidResponse("Moodle не вернул роли участников курса " + courseId + "; проверьте права сервиса CRM");
            }
            for (JsonNode role : roles) {
                String shortname = role.path("shortname").asText();
                if (properties.studentRoles().contains(shortname)) {
                    students.add(userId);
                }
                if (properties.teacherRoles().contains(shortname)) {
                    teachers.add(userId);
                }
            }
            user.path("groups").forEach(group -> members.computeIfAbsent(group.path("id").asLong(), key -> new HashSet<>()).add(userId));
        }
        Map<Long, Boolean> completion = course.path("enablecompletion").asInt() == 1 ? completion(courseId, students) : null;
        String shortname = course.path("shortname").asText();
        String fullname = course.path("fullname").asText();
        List<LearningUnit> groupUnits = new ArrayList<>();
        for (JsonNode group : groups) {
            long groupId = group.path("id").asLong();
            Set<Long> groupMembers = members.getOrDefault(groupId, Set.of());
            Set<Long> groupStudents = new HashSet<>(students);
            groupStudents.retainAll(groupMembers);
            Set<Long> groupTeachers = new HashSet<>(teachers);
            groupTeachers.retainAll(groupMembers);
            groupUnits.add(unit(courseId, groupId, shortname, fullname, group.path("name").asText(), groupStudents,
                    groupTeachers.size(), completion, 0));
        }
        int groupsWithParticipants = (int) groupUnits.stream().filter(unit -> unit.participants() > 0).count();
        List<LearningUnit> units = new ArrayList<>();
        units.add(unit(courseId, null, shortname, fullname, null, students, teachers.size(), completion, groupsWithParticipants));
        units.addAll(groupUnits);
        return units;
    }

    private Map<Long, Boolean> completion(long courseId, Set<Long> students) {
        Map<Long, Boolean> result = new HashMap<>();
        for (Long userId : students) {
            String function = "core_completion_get_course_completion_status";
            JsonNode body = post(function, params("courseid", Long.toString(courseId), "userid", Long.toString(userId)));
            String error = errorCode(body);
            if ("nocriteriaset".equals(error)) {
                return null;
            }
            if (error != null && UNKNOWN_USER_COMPLETION.contains(error)) {
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

    private static LearningUnit unit(
            long courseId,
            Long groupId,
            String shortname,
            String fullname,
            String groupName,
            Set<Long> students,
            int teachers,
            Map<Long, Boolean> completion,
            int groupsCount
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
                completed, notCompleted, unknown, groupsCount);
    }

    private JsonNode call(String function, Map<String, String> parameters) {
        JsonNode body = post(function, parameters);
        String error = errorCode(body);
        if (error != null) {
            throw moodleError(function, error);
        }
        return body;
    }

    private JsonNode post(String function, Map<String, String> parameters) {
        StringJoiner form = new StringJoiner("&");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("wstoken", properties.token());
        fields.put("wsfunction", function);
        fields.put("moodlewsrestformat", "json");
        fields.putAll(parameters);
        fields.forEach((name, value) -> form.add(encode(name) + "=" + encode(value)));
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .timeout(properties.readTimeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException exception) {
            throw SourceFetchException.unreachable("Moodle", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw SourceFetchException.unavailable("Синхронизация прервана");
        }
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw SourceFetchException.unauthorized("Moodle ответил HTTP " + status + "; проверьте MOODLE_TOKEN и внешний сервис");
            }
            if (status >= 300 && status < 400) {
                throw SourceFetchException.unavailable(
                        "Moodle ответил переадресацией HTTP " + status + "; MOODLE_BASE_URL должен совпадать с адресом Moodle (wwwroot)"
                );
            }
            if (status != 200) {
                throw SourceFetchException.unavailable("Moodle ответил HTTP " + status);
            }
            long limit = properties.maxResponseSize().toBytes();
            byte[] bytes = body.readNBytes(Math.toIntExact(limit + 1));
            if (bytes.length > limit) {
                throw SourceFetchException.invalidResponse("Ответ Moodle на " + function + " больше " + properties.maxResponseSize());
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

    private URI endpoint() {
        String base = properties.baseUrl().strip();
        try {
            URI uri = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + REST_PATH);
            if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                return uri;
            }
        } catch (IllegalArgumentException exception) {
            throw SourceFetchException.unavailable("Адрес Moodle в конфигурации развёртывания некорректен");
        }
        throw SourceFetchException.unavailable("Адрес Moodle в конфигурации развёртывания должен начинаться с http:// или https://");
    }

    private static JsonNode array(JsonNode body, String function) {
        if (!body.isArray()) {
            throw SourceFetchException.invalidResponse("Ответ " + function + " не является массивом");
        }
        return body;
    }

    private static String errorCode(JsonNode body) {
        return body.isObject() && body.has("exception") ? body.path("errorcode").asText("unknown") : null;
    }

    private static SourceFetchException moodleError(String function, String errorCode) {
        if (ACCESS_ERRORS.contains(errorCode)) {
            return SourceFetchException.unauthorized(
                    "Moodle отклонил запрос " + function + " (" + errorCode + "); проверьте MOODLE_TOKEN, внешний сервис и роль сервиса CRM"
            );
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
}
