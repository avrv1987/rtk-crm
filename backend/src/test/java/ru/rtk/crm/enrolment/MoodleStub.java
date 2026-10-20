package ru.rtk.crm.enrolment;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpServer;

// Заглушка REST Moodle для тестов записи: пользователи по почте, зачисления на курсы, состав групп, отказ создания
// пользователя по выбранной почте и разовый сбой выбранной функции — до действия или после него (ответ потерян).
final class MoodleStub {
    private final HttpServer server;
    private final Map<String, Long> users = new ConcurrentHashMap<>();
    private final Map<String, String[]> names = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> enrolments = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> groups = new ConcurrentHashMap<>();
    private final Map<String, Boolean> failures = new ConcurrentHashMap<>();
    private final Set<Long> unremovable = ConcurrentHashMap.newKeySet();
    private final Set<String> rejected = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> calls = new ConcurrentHashMap<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong(1000);
    private boolean stopped;
    private UserFields userFields = UserFields.PARTS;

    // Какие поля имени отдаёт core_user_get_users_by_field. Настоящий Moodle с токеном записи отдаёт только fullname
    // (id, username, fullname), а то и ни одного поля имени — заглушка должна уметь и так.
    enum UserFields { PARTS, FULLNAME_ONLY, NONE }

    private MoodleStub(HttpServer server) {
        this.server = server;
    }

    static MoodleStub start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        MoodleStub stub = new MoodleStub(server);
        server.createContext("/webservice/rest/server.php", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response = stub.handle(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        return stub;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void existing(String email, long id, String firstName, String lastName) {
        users.put(email, id);
        names.put(email, new String[] {firstName, lastName});
    }

    void userFields(UserFields fields) {
        userFields = fields;
    }

    void rejectCreate(String email) {
        rejected.add(email);
    }

    void allowCreate(String email) {
        rejected.remove(email);
    }

    // Следующий вызов функции отвечает ошибкой Moodle; applied — действие при этом всё же выполнено.
    void failOnce(String function, boolean applied) {
        failures.put(function, applied);
    }

    // Зачисление не вручную: enrol_manual_unenrol_users его не снимает.
    void notManual(long userId) {
        unremovable.add(userId);
    }

    boolean enrolled(long courseId, long userId) {
        return enrolments.getOrDefault(courseId, Set.of()).contains(userId);
    }

    boolean inGroup(long groupId, long userId) {
        return groups.getOrDefault(groupId, Set.of()).contains(userId);
    }

    long userId(String email) {
        return users.get(email);
    }

    int calls(String function) {
        return calls.getOrDefault(function, 0);
    }

    List<String> bodies() {
        return bodies;
    }

    synchronized void stop() {
        if (!stopped) {
            stopped = true;
            server.stop(0);
        }
    }

    private synchronized String handle(String body) {
        bodies.add(body);
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            form.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        String function = form.get("wsfunction");
        calls.merge(function, 1, Integer::sum);
        Boolean failure = failures.remove(function);
        if (failure != null) {
            if (failure) {
                apply(function, form);
            }
            return "{\"exception\":\"dml_write_exception\",\"errorcode\":\"dmlwriteexception\",\"message\":\"\"}";
        }
        return apply(function, form);
    }

    private String apply(String function, Map<String, String> form) {
        return switch (function) {
            case "core_user_get_users_by_field" -> {
                Long id = users.get(form.get("values[0]"));
                String[] name = names.getOrDefault(form.get("values[0]"), new String[] {"", ""});
                String fields = switch (userFields) {
                    case PARTS -> ",\"firstname\":\"" + name[0] + "\",\"lastname\":\"" + name[1] + "\",\"email\":\"e\"";
                    case FULLNAME_ONLY -> ",\"fullname\":\"" + name[0] + " " + name[1] + "\"";
                    case NONE -> "";
                };
                yield id == null ? "[]" : "[{\"id\":" + id + ",\"username\":\"u\"" + fields + "}]";
            }
            case "core_user_create_users" -> {
                String email = form.get("users[0][email]");
                if (rejected.contains(email)) {
                    yield "{\"exception\":\"invalid_parameter_exception\",\"errorcode\":\"invalidparameter\",\"message\":\"Username already exists\"}";
                }
                long id = ids.incrementAndGet();
                users.put(email, id);
                names.put(email, new String[] {form.get("users[0][firstname]"), form.get("users[0][lastname]")});
                yield "[{\"id\":" + id + ",\"username\":\"" + form.get("users[0][username]") + "\"}]";
            }
            case "core_user_update_users" -> "null";
            case "core_group_add_group_members" -> {
                groups.computeIfAbsent(Long.parseLong(form.get("members[0][groupid]")), ignored -> new HashSet<>())
                        .add(Long.parseLong(form.get("members[0][userid]")));
                yield "null";
            }
            case "core_group_delete_group_members" -> {
                groups.getOrDefault(Long.parseLong(form.get("members[0][groupid]")), new HashSet<>())
                        .remove(Long.parseLong(form.get("members[0][userid]")));
                yield "null";
            }
            case "core_group_get_group_members" -> {
                long group = Long.parseLong(form.get("groupids[0]"));
                yield "[{\"groupid\":" + group + ",\"userids\":["
                        + String.join(",", groups.getOrDefault(group, Set.of()).stream().map(String::valueOf).toList()) + "]}]";
            }
            case "enrol_manual_enrol_users" -> {
                enrolments.computeIfAbsent(Long.parseLong(form.get("enrolments[0][courseid]")), ignored -> new HashSet<>())
                        .add(Long.parseLong(form.get("enrolments[0][userid]")));
                yield "null";
            }
            case "enrol_manual_unenrol_users" -> {
                if (unremovable.contains(Long.parseLong(form.get("enrolments[0][userid]")))) {
                    yield "null";
                }
                enrolments.getOrDefault(Long.parseLong(form.get("enrolments[0][courseid]")), new HashSet<>())
                        .remove(Long.parseLong(form.get("enrolments[0][userid]")));
                yield "null";
            }
            case "core_enrol_get_enrolled_users" -> {
                Set<Long> enrolled = enrolments.getOrDefault(Long.parseLong(form.get("courseid")), Set.of());
                yield "[" + String.join(",", enrolled.stream().map(id -> "{\"id\":" + id + "}").toList()) + "]";
            }
            default -> "{\"exception\":\"moodle_exception\",\"errorcode\":\"invalidfunction\",\"message\":\"\"}";
        };
    }
}
