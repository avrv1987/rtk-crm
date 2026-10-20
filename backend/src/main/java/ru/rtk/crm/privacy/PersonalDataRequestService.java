package ru.rtk.crm.privacy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditExportWriter;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.settings.SystemSetting;
import ru.rtk.crm.settings.SystemSettings;

// Журнал обращений субъектов ПДн: учёт поступления, срока ответа и исполнения. Само исполнение — выгрузка сведений,
// уточнение, ограничение или обезличивание — остаётся в «Персональных данных»; обращение ссылается на его запись в
// журнале аудита. В журнал аудита пишутся только вид, даты и статус, без имени субъекта.
@Service
public class PersonalDataRequestService {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String OBJECT_TYPE = "PERSONAL_DATA_REQUEST";
    // Записи журнала, которые могут быть исполнением обращения.
    static final List<AuditAction> EXECUTIONS = List.of(AuditAction.SUBJECT_EXPORTED, AuditAction.MAIL_SUBJECT_REPORT_SENT,
            AuditAction.CONTACT_RECTIFIED, AuditAction.CONTACT_RESTRICTED, AuditAction.LEARNER_RESTRICTED,
            AuditAction.SUBJECT_ANONYMIZED);
    private static final String SELECT = """
            SELECT r.id, r.received_on, r.subject_name, r.kind, r.channel, r.due_on, r.assignee_id,
                   assignee.display_name AS assignee_name, r.status, r.completed_on, r.audit_event_id, r.note,
                   a.action AS execution_action, a.occurred_at AS execution_at, a.details AS execution_details
            FROM personal_data_requests r
            LEFT JOIN crm_user_profiles assignee ON assignee.id = r.assignee_id
            LEFT JOIN audit_events a ON a.id = r.audit_event_id
            """;

    private final JdbcClient jdbcClient;
    private final SystemSettings settings;
    private final AuditJournalRepository auditJournalRepository;

    public PersonalDataRequestService(JdbcClient jdbcClient, SystemSettings settings, AuditJournalRepository auditJournalRepository) {
        this.jdbcClient = jdbcClient;
        this.settings = settings;
        this.auditJournalRepository = auditJournalRepository;
    }

    @Transactional(readOnly = true)
    public PersonalDataRequests list(CrmProfile actor) {
        AdminAuthorization.requireAdmin(actor);
        LocalDate today = today();
        int remind = settings.value(SystemSetting.PERSONAL_DATA_REMIND_DAYS);
        List<PersonalDataRequest> requests = jdbcClient.sql(SELECT + """
                        ORDER BY CASE WHEN r.status IN ('DONE', 'REJECTED') THEN 1 ELSE 0 END, r.due_on, r.received_on DESC
                        LIMIT 1000
                        """)
                .query((resultSet, rowNumber) -> map(resultSet, today, remind))
                .list();
        List<PersonalDataExecution> executions = jdbcClient.sql("""
                        SELECT id, action, occurred_at, details FROM audit_events
                        WHERE action IN (:actions)
                        ORDER BY occurred_at DESC, id DESC
                        LIMIT 50
                        """)
                .param("actions", EXECUTIONS.stream().map(Enum::name).toList())
                .query((resultSet, rowNumber) -> new PersonalDataExecution(
                        resultSet.getObject("id", UUID.class),
                        AuditAction.valueOf(resultSet.getString("action")).label(),
                        resultSet.getObject("occurred_at", OffsetDateTime.class),
                        resultSet.getString("details")))
                .list();
        List<PersonalDataAssignee> assignees = jdbcClient.sql("""
                        SELECT id, display_name FROM crm_user_profiles
                        WHERE role = 'ADMIN' AND active = TRUE AND pending_activation = FALSE
                        ORDER BY display_name, id
                        """)
                .query((resultSet, rowNumber) -> new PersonalDataAssignee(resultSet.getObject("id", UUID.class),
                        resultSet.getString("display_name")))
                .list();
        return new PersonalDataRequests(requests, executions, assignees, settings.value(SystemSetting.PERSONAL_DATA_RESPONSE_DAYS),
                settings.enabled(SystemSetting.PERSONAL_DATA_RESPONSE_WORKING_DAYS), remind);
    }

    @Transactional
    public PersonalDataRequest create(CrmProfile actor, PersonalDataRequestInput input, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        Valid valid = validate(input, null);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbcClient.sql("""
                        INSERT INTO personal_data_requests (
                            id, received_on, subject_name, kind, channel, due_on, assignee_id, status, completed_on,
                            audit_event_id, note, created_by, created_at, updated_at
                        ) VALUES (
                            :id, :receivedOn, :subjectName, :kind, :channel, :dueOn, :assigneeId, :status, :completedOn,
                            :auditEventId, :note, :actor, :now, :now
                        )
                        """)
                .params(valid.parameters())
                .param("id", id)
                .param("assigneeId", valid.assigneeId() == null ? actor.id() : valid.assigneeId())
                .param("actor", actor.id())
                .param("now", now)
                .update();
        PersonalDataRequest created = find(id);
        auditJournalRepository.record(AuditAction.PERSONAL_DATA_REQUEST_CHANGED, actor.id(), OBJECT_TYPE, id, title(created),
                "зарегистрировано: " + created.kind().label() + ", канал «" + created.channel() + "», срок ответа до "
                        + DATE.format(created.dueOn()) + "; статус: " + created.status().label(), requestId);
        return created;
    }

    @Transactional
    public PersonalDataRequest update(CrmProfile actor, UUID id, PersonalDataRequestInput input, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        PersonalDataRequest before = find(id);
        Valid valid = validate(input, before);
        jdbcClient.sql("""
                        UPDATE personal_data_requests
                        SET received_on = :receivedOn, subject_name = :subjectName, kind = :kind, channel = :channel,
                            due_on = :dueOn, assignee_id = :assigneeId, status = :status, completed_on = :completedOn,
                            audit_event_id = :auditEventId, note = :note, updated_at = :now
                        WHERE id = :id
                        """)
                .params(valid.parameters())
                .param("assigneeId", valid.assigneeId() == null ? before.assigneeId() : valid.assigneeId())
                .param("id", id)
                .param("now", OffsetDateTime.now())
                .update();
        PersonalDataRequest after = find(id);
        auditJournalRepository.record(AuditAction.PERSONAL_DATA_REQUEST_CHANGED, actor.id(), OBJECT_TYPE, id, title(after),
                changes(before, after), requestId);
        return after;
    }

    @Transactional
    public byte[] export(CrmProfile actor, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        List<PersonalDataRequest> requests = list(actor).requests();
        StringBuilder csv = new StringBuilder("﻿");
        AuditExportWriter.line(csv, List.of("Дата поступления", "Субъект", "Вид", "Канал", "Срок ответа", "Исполнитель", "Статус",
                "Дата исполнения", "Исполнение в журнале", "Примечание"));
        for (PersonalDataRequest request : requests) {
            AuditExportWriter.line(csv, List.of(
                    DATE.format(request.receivedOn()),
                    request.subjectName(),
                    request.kind().label(),
                    request.channel(),
                    DATE.format(request.dueOn()),
                    Objects.toString(request.assigneeName(), ""),
                    request.status().label(),
                    request.completedOn() == null ? "" : DATE.format(request.completedOn()),
                    request.execution() == null ? "" : request.execution().label() + ", "
                            + DATE.format(request.execution().occurredAt().atZoneSameInstant(MOSCOW)),
                    Objects.toString(request.note(), "")));
        }
        auditJournalRepository.record(AuditAction.PERSONAL_DATA_REQUESTS_EXPORTED, actor.id(), OBJECT_TYPE, null, null,
                "строк: " + requests.size(), requestId);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Колокольчик администратора: открытые обращения, срок ответа которых истёк или наступает в ближайшие дни. */
    @Transactional(readOnly = true)
    public AdminReminders reminders(CrmProfile actor) {
        AdminAuthorization.requireAdmin(actor);
        LocalDate today = today();
        int remind = settings.value(SystemSetting.PERSONAL_DATA_REMIND_DAYS);
        List<PersonalDataRequest> due = jdbcClient.sql(SELECT + """
                        WHERE r.status IN ('NEW', 'IN_PROGRESS') AND r.due_on <= :until
                        ORDER BY r.due_on, r.received_on
                        LIMIT 50
                        """)
                .param("until", today.plusDays(remind))
                .query((resultSet, rowNumber) -> map(resultSet, today, remind))
                .list();
        return new AdminReminders(due, remind);
    }

    int deleteClosedBefore(LocalDate cutoff) {
        return jdbcClient.sql("DELETE FROM personal_data_requests WHERE status IN ('DONE', 'REJECTED') AND completed_on < :cutoff")
                .param("cutoff", cutoff)
                .update();
    }

    private PersonalDataRequest find(UUID id) {
        LocalDate today = today();
        int remind = settings.value(SystemSetting.PERSONAL_DATA_REMIND_DAYS);
        return jdbcClient.sql(SELECT + " WHERE r.id = :id")
                .param("id", id)
                .query((resultSet, rowNumber) -> map(resultSet, today, remind))
                .optional()
                .orElseThrow(PrivacyException::requestNotFound);
    }

    private Valid validate(PersonalDataRequestInput input, PersonalDataRequest before) {
        if (input == null) {
            throw new InteractionValidationException("body", "Передайте данные обращения");
        }
        LocalDate today = today();
        if (input.receivedOn() == null || input.receivedOn().isAfter(today)) {
            throw new InteractionValidationException("receivedOn", "Укажите дату поступления не позже сегодняшней");
        }
        String subject = text(input.subjectName(), "subjectName", "Укажите субъекта: ФИО или другое описание", 300, true);
        if (input.kind() == null) {
            throw new InteractionValidationException("kind", "Выберите вид обращения");
        }
        String channel = text(input.channel(), "channel", "Укажите канал поступления", 200, true);
        LocalDate dueOn = input.dueOn() != null ? input.dueOn()
                : before != null && before.receivedOn().equals(input.receivedOn()) ? before.dueOn()
                : dueDate(input.receivedOn(), settings.value(SystemSetting.PERSONAL_DATA_RESPONSE_DAYS),
                        settings.enabled(SystemSetting.PERSONAL_DATA_RESPONSE_WORKING_DAYS));
        if (dueOn.isBefore(input.receivedOn())) {
            throw new InteractionValidationException("dueOn", "Срок ответа не может быть раньше даты поступления");
        }
        PersonalDataRequestStatus status = input.status() == null ? PersonalDataRequestStatus.NEW : input.status();
        LocalDate completedOn = null;
        if (status.closed()) {
            completedOn = input.completedOn() == null ? today : input.completedOn();
            if (completedOn.isBefore(input.receivedOn()) || completedOn.isAfter(today)) {
                throw new InteractionValidationException("completedOn",
                        "Дата исполнения — не раньше поступления и не позже сегодняшней");
            }
        }
        if (input.assigneeId() != null && jdbcClient.sql("""
                        SELECT COUNT(*) FROM crm_user_profiles WHERE id = :id AND role = 'ADMIN' AND active = TRUE
                        """)
                .param("id", input.assigneeId()).query(Long.class).single() == 0) {
            throw new InteractionValidationException("assigneeId", "Исполнитель — действующий администратор");
        }
        if (input.auditEventId() != null && jdbcClient.sql("SELECT COUNT(*) FROM audit_events WHERE id = :id AND action IN (:actions)")
                .param("id", input.auditEventId())
                .param("actions", EXECUTIONS.stream().map(Enum::name).toList())
                .query(Long.class).single() == 0) {
            throw new InteractionValidationException("auditEventId", "Выберите запись журнала о выгрузке, уточнении, ограничении"
                    + " или обезличивании");
        }
        String note = text(input.note(), "note", null, 1000, false);
        return new Valid(input.receivedOn(), subject, input.kind(), channel, dueOn, input.assigneeId(), status, completedOn,
                input.auditEventId(), note);
    }

    private static String text(String value, String field, String required, int limit, boolean mandatory) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty()) {
            if (mandatory) {
                throw new InteractionValidationException(field, required);
            }
            return null;
        }
        if (stripped.length() > limit) {
            throw new InteractionValidationException(field, "Не длиннее " + limit + " символов");
        }
        return stripped;
    }

    private static PersonalDataRequest map(java.sql.ResultSet resultSet, LocalDate today, int remind) throws java.sql.SQLException {
        PersonalDataRequestStatus status = PersonalDataRequestStatus.valueOf(resultSet.getString("status"));
        LocalDate dueOn = resultSet.getObject("due_on", LocalDate.class);
        String action = resultSet.getString("execution_action");
        return new PersonalDataRequest(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("received_on", LocalDate.class),
                resultSet.getString("subject_name"),
                PersonalDataRequestKind.valueOf(resultSet.getString("kind")),
                resultSet.getString("channel"),
                dueOn,
                resultSet.getObject("assignee_id", UUID.class),
                resultSet.getString("assignee_name"),
                status,
                resultSet.getObject("completed_on", LocalDate.class),
                action == null ? null : new PersonalDataExecution(resultSet.getObject("audit_event_id", UUID.class),
                        AuditAction.valueOf(action).label(), resultSet.getObject("execution_at", OffsetDateTime.class),
                        resultSet.getString("execution_details")),
                resultSet.getString("note"),
                dueState(status, dueOn, today, remind)
        );
    }

    // Рабочие дни — понедельник–пятница; праздники и переносы не учитываются (сказано в справке), поэтому срок у
    // обращения можно поправить вручную.
    static LocalDate dueDate(LocalDate receivedOn, int days, boolean workingDays) {
        if (!workingDays) {
            return receivedOn.plusDays(days);
        }
        LocalDate due = receivedOn;
        for (int added = 0; added < days; ) {
            due = due.plusDays(1);
            if (due.getDayOfWeek().getValue() <= 5) {
                added++;
            }
        }
        return due;
    }

    static PersonalDataDueState dueState(PersonalDataRequestStatus status, LocalDate dueOn, LocalDate today, int remind) {
        if (status.closed()) {
            return null;
        }
        if (dueOn.isBefore(today)) {
            return PersonalDataDueState.OVERDUE;
        }
        return dueOn.isAfter(today.plusDays(remind)) ? null : PersonalDataDueState.SOON;
    }

    private static String title(PersonalDataRequest request) {
        return "обращение от " + DATE.format(request.receivedOn());
    }

    private static String changes(PersonalDataRequest before, PersonalDataRequest after) {
        List<String> changes = new ArrayList<>();
        change(changes, "дата поступления", DATE.format(before.receivedOn()), DATE.format(after.receivedOn()));
        if (!before.subjectName().equals(after.subjectName())) {
            changes.add("субъект уточнён");
        }
        change(changes, "вид", before.kind().label(), after.kind().label());
        change(changes, "канал", before.channel(), after.channel());
        change(changes, "срок ответа", DATE.format(before.dueOn()), DATE.format(after.dueOn()));
        change(changes, "исполнитель", before.assigneeName(), after.assigneeName());
        change(changes, "статус", before.status().label(), after.status().label());
        change(changes, "дата исполнения", before.completedOn() == null ? "нет" : DATE.format(before.completedOn()),
                after.completedOn() == null ? "нет" : DATE.format(after.completedOn()));
        change(changes, "исполнение", before.execution() == null ? "не указано" : before.execution().label(),
                after.execution() == null ? "не указано" : after.execution().label());
        if (!Objects.equals(before.note(), after.note())) {
            changes.add("примечание изменено");
        }
        return changes.isEmpty() ? "сохранено без изменений" : String.join("; ", changes);
    }

    private static void change(List<String> changes, String label, String before, String after) {
        if (!Objects.equals(before, after)) {
            changes.add(label + ": " + Objects.toString(before, "нет") + " → " + Objects.toString(after, "нет"));
        }
    }

    private static LocalDate today() {
        return LocalDate.now(MOSCOW);
    }

    private record Valid(LocalDate receivedOn, String subjectName, PersonalDataRequestKind kind, String channel, LocalDate dueOn,
                         UUID assigneeId, PersonalDataRequestStatus status, LocalDate completedOn, UUID auditEventId, String note) {
        java.util.Map<String, Object> parameters() {
            java.util.Map<String, Object> parameters = new java.util.HashMap<>();
            parameters.put("receivedOn", receivedOn);
            parameters.put("subjectName", subjectName);
            parameters.put("kind", kind.name());
            parameters.put("channel", channel);
            parameters.put("dueOn", dueOn);
            parameters.put("status", status.name());
            parameters.put("completedOn", completedOn);
            parameters.put("auditEventId", auditEventId);
            parameters.put("note", note);
            return parameters;
        }
    }
}
