package ru.rtk.crm.enrolment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.rtk.crm.source.MoodleWriteTarget;

// Клиент записи в Moodle (REST, функции core_user_*, enrol_manual_*, core_group_*). Записи не повторяются
// автоматически: повтор создания пользователя после таймаута мог бы создать второго; повтор делает оператор, и перед
// созданием пользователь всегда ищется по почте.
@Component
public class MoodleWriteClient {
    static final String REST_PATH = "/webservice/rest/server.php";
    private static final Set<String> ACCESS_ERRORS = Set.of("invalidtoken", "accessexception", "nopermissions",
            "requireloginerror", "servicerequireslogin", "accessdenied", "webservicesnotenabled");

    private final ObjectMapper objectMapper;

    public MoodleWriteClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // Набор полей зависит от прав токена: на настоящем Moodle токен записи может отдавать только id, username и fullname.
    record MoodleUser(long id, String username, String firstName, String lastName, String fullName, String email) {
        @Override
        public String toString() {
            return "MoodleUser[id=" + id + ", masked]";
        }
    }

    List<MoodleUser> findUsersByEmail(MoodleWriteTarget target, String email) {
        JsonNode body = call(target, "core_user_get_users_by_field", Map.of("field", "email", "values[0]", email));
        List<MoodleUser> users = new ArrayList<>();
        if (body.isArray()) {
            for (JsonNode user : body) {
                users.add(new MoodleUser(user.path("id").asLong(), user.path("username").asText(null), user.path("firstname").asText(null),
                        user.path("lastname").asText(null), user.path("fullname").asText(null), user.path("email").asText(null)));
            }
        }
        return users;
    }

    long createUser(MoodleWriteTarget target, String username, String firstName, String lastName, String email) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("users[0][username]", username);
        fields.put("users[0][auth]", "manual");
        fields.put("users[0][createpassword]", "1");
        fields.put("users[0][firstname]", firstName);
        fields.put("users[0][lastname]", lastName);
        fields.put("users[0][email]", email);
        JsonNode body = call(target, "core_user_create_users", fields);
        long id = body.path(0).path("id").asLong(0);
        if (id <= 0) {
            throw MoodleWriteException.invalid("Moodle не вернул идентификатор созданного пользователя");
        }
        return id;
    }

    void enrol(MoodleWriteTarget target, long userId, long courseId) {
        call(target, "enrol_manual_enrol_users", Map.of("enrolments[0][roleid]", Integer.toString(target.studentRoleId()),
                "enrolments[0][userid]", Long.toString(userId), "enrolments[0][courseid]", Long.toString(courseId)));
    }

    void unenrol(MoodleWriteTarget target, long userId, long courseId) {
        call(target, "enrol_manual_unenrol_users", Map.of("enrolments[0][userid]", Long.toString(userId),
                "enrolments[0][courseid]", Long.toString(courseId)));
    }

    void addToGroup(MoodleWriteTarget target, long userId, long groupId) {
        call(target, "core_group_add_group_members", Map.of("members[0][groupid]", Long.toString(groupId),
                "members[0][userid]", Long.toString(userId)));
    }

    // Только группа, которую задала CRM: прочие группы слушателя в курсе (их мог добавить преподаватель) не трогаются.
    void removeFromGroup(MoodleWriteTarget target, long userId, long groupId) {
        call(target, "core_group_delete_group_members", Map.of("members[0][groupid]", Long.toString(groupId),
                "members[0][userid]", Long.toString(userId)));
    }

    Set<Long> groupMemberIds(MoodleWriteTarget target, long groupId) {
        JsonNode body = call(target, "core_group_get_group_members", Map.of("groupids[0]", Long.toString(groupId)));
        if (!body.isArray()) {
            throw MoodleWriteException.invalid("Ответ core_group_get_group_members не является списком");
        }
        List<Long> ids = new ArrayList<>();
        body.forEach(group -> group.path("userids").forEach(id -> ids.add(id.asLong())));
        return ids.stream().collect(Collectors.toSet());
    }

    Set<Long> enrolledUserIds(MoodleWriteTarget target, long courseId) {
        JsonNode body = call(target, "core_enrol_get_enrolled_users", Map.of("courseid", Long.toString(courseId)));
        if (!body.isArray()) {
            throw MoodleWriteException.invalid("Ответ core_enrol_get_enrolled_users не является списком");
        }
        List<Long> ids = new ArrayList<>();
        body.forEach(user -> ids.add(user.path("id").asLong()));
        return ids.stream().collect(Collectors.toSet());
    }

    private JsonNode call(MoodleWriteTarget target, String function, Map<String, String> parameters) {
        StringJoiner form = new StringJoiner("&");
        Map<String, String> fields = new LinkedHashMap<>();
        // Токен идёт в теле POST, а не в адресе: адрес попадает в журналы прокси и тексты ошибок.
        fields.put("wstoken", target.token());
        fields.put("wsfunction", function);
        fields.put("moodlewsrestformat", "json");
        fields.putAll(parameters);
        fields.forEach((name, value) -> form.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(target.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(endpoint(target))
                .timeout(target.readTimeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException exception) {
            throw MoodleWriteException.unavailable("Moodle недоступен: " + exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw MoodleWriteException.unavailable("Передача в Moodle прервана");
        }
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw MoodleWriteException.access("Moodle ответил HTTP " + status + "; проверьте токен записи и внешний сервис");
            }
            if (status != 200) {
                throw MoodleWriteException.unavailable("Moodle ответил HTTP " + status);
            }
            long limit = target.maxResponseSize().toBytes();
            byte[] bytes = body.readNBytes(Math.toIntExact(limit + 1));
            if (bytes.length > limit) {
                throw MoodleWriteException.invalid("Ответ Moodle на " + function + " слишком большой");
            }
            JsonNode json = bytes.length == 0 ? objectMapper.nullNode() : objectMapper.readTree(bytes);
            // Ошибки Moodle приходят с HTTP 200 и телом {exception, errorcode, message}.
            if (json != null && json.isObject() && json.has("exception")) {
                String code = json.path("errorcode").asText("unknown");
                if (ACCESS_ERRORS.contains(code)) {
                    throw MoodleWriteException.access("Moodle отклонил " + function + " (" + code + "); проверьте права токена записи");
                }
                throw MoodleWriteException.rejected(function, code);
            }
            return json == null ? objectMapper.nullNode() : json;
        } catch (IOException exception) {
            throw MoodleWriteException.invalid("Ответ Moodle на " + function + " не является корректным JSON");
        }
    }

    private static URI endpoint(MoodleWriteTarget target) {
        String base = target.baseUrl().strip();
        URI uri = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + REST_PATH);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw MoodleWriteException.unavailable("Адрес Moodle должен начинаться с http:// или https://");
        }
        return uri;
    }
}
