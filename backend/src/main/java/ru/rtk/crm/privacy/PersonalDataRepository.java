package ru.rtk.crm.privacy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.privacy.SubjectTerms.SqlMatch;
import ru.rtk.crm.report.ReportKind;

@Repository
public class PersonalDataRepository {
    // Отчёты этих видов собраны из чисел и справочников, ПДн в них нет.
    private static final List<String> REPORTS_WITHOUT_PERSONAL_DATA = List.of(ReportKind.DEMAND.name(), ReportKind.DURATION.name());

    static final String RECTIFIED_VALUES = "; было → стало: ";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public PersonalDataRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    Optional<ContactState> lockContact(UUID contactId) {
        return jdbcClient.sql("SELECT id FROM contacts WHERE id = :contactId FOR UPDATE")
                .param("contactId", contactId)
                .query(UUID.class)
                .optional()
                .flatMap(id -> jdbcClient.sql("""
                        SELECT c.id, c.organization_id, o.name AS organization_name, c.name, c.position, c.email, c.phone,
                               c.personal_data_status, c.version
                        FROM contacts c
                        JOIN organizations o ON o.id = c.organization_id
                        WHERE c.id = :contactId
                        """)
                        .param("contactId", id)
                        .query((resultSet, rowNumber) -> new ContactState(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("organization_id", UUID.class),
                                resultSet.getString("organization_name"),
                                resultSet.getString("name"),
                                resultSet.getString("position"),
                                resultSet.getString("email"),
                                resultSet.getString("phone"),
                                PersonalDataStatus.valueOf(resultSet.getString("personal_data_status")),
                                resultSet.getInt("version")
                        ))
                        .optional());
    }

    boolean updateContact(UUID contactId, int expectedVersion, ContactValues values, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE contacts
                SET name = :name, position = :position, email = :email, phone = :phone,
                    version = version + 1, updated_at = :updatedAt
                WHERE id = :contactId AND version = :expectedVersion AND personal_data_status <> 'ANONYMIZED'
                """)
                .param("contactId", contactId)
                .param("expectedVersion", expectedVersion)
                .param("name", values.name())
                .param("position", values.position())
                .param("email", values.email())
                .param("phone", values.phone())
                .param("updatedAt", now)
                .update() == 1;
    }

    boolean updateContactStatus(UUID contactId, int expectedVersion, PersonalDataStatus status, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE contacts
                SET personal_data_status = :status, version = version + 1, updated_at = :updatedAt
                WHERE id = :contactId AND version = :expectedVersion AND personal_data_status <> 'ANONYMIZED'
                """)
                .param("contactId", contactId)
                .param("expectedVersion", expectedVersion)
                .param("status", status.name())
                .param("updatedAt", now)
                .update() == 1;
    }

    // Обезличивание записи учёта людей: ФИО заменяется отметкой, сопутствующие данные человека (почта, номер
    // удостоверения, документ стажёра) стираются; часы и даты остаются для отчётов. dryRun считает, не меняя.
    boolean anonymizePersonRecord(SubjectPersonRecord.Ref ref, OffsetDateTime now, boolean dryRun) {
        String table;
        String set;
        String pending;
        String marker;
        switch (ref.kind()) {
            case TEACHING_PERFORMER -> {
                table = "university_teaching";
                set = "performer_name = :marker";
                pending = "performer_profile_id IS NULL AND performer_name <> :marker";
                marker = "Исполнитель обезличен";
            }
            case INTERN -> {
                table = "internships";
                set = "intern_name = :marker, document = NULL, anonymized_at = :now";
                pending = "anonymized_at IS NULL";
                marker = "Стажёр обезличен";
            }
            case MENTOR -> {
                table = "internships";
                set = "mentor_name = :marker";
                pending = "mentor_profile_id IS NULL AND mentor_name <> :marker";
                marker = "Наставник обезличен";
            }
            default -> {
                table = "teacher_qualifications";
                set = "person_name = :marker, person_email = NULL, certificate_number = NULL, contact_id = NULL, anonymized_at = :now";
                pending = "anonymized_at IS NULL";
                marker = "Преподаватель обезличен";
            }
        }
        JdbcClient.StatementSpec statement = jdbcClient.sql(dryRun
                        ? "SELECT COUNT(*) FROM " + table + " WHERE id = :id AND " + pending
                        : "UPDATE " + table + " SET " + set + ", version = version + 1, updated_at = :now WHERE id = :id AND " + pending)
                .param("id", ref.id())
                .param("marker", marker);
        if (!dryRun) {
            return statement.param("now", now).update() == 1;
        }
        return statement.query(Long.class).single() > 0;
    }

    void anonymizeContact(UUID contactId, String marker, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE contacts
                SET name = :marker, position = NULL, email = NULL, phone = NULL, external_key = NULL,
                    personal_data_status = 'ANONYMIZED', version = version + 1, updated_at = :updatedAt
                WHERE id = :contactId
                """)
                .param("contactId", contactId)
                .param("marker", marker)
                .param("updatedAt", now)
                .update();
        jdbcClient.sql("""
                UPDATE audit_events
                SET details = LEFT(details, POSITION(:separator IN details) - 1)
                WHERE object_type = 'CONTACT' AND object_id = :contactId AND action = 'CONTACT_RECTIFIED'
                  AND POSITION(:separator IN details) > 0
                """)
                .param("contactId", contactId)
                .param("separator", RECTIFIED_VALUES)
                .update();
    }

    Optional<ProfileState> lockProfile(UUID profileId) {
        return jdbcClient.sql("SELECT id FROM crm_user_profiles WHERE id = :profileId FOR UPDATE")
                .param("profileId", profileId)
                .query(UUID.class)
                .optional()
                .flatMap(id -> jdbcClient.sql("""
                        SELECT id, display_name, login, active, anonymized_at
                        FROM crm_user_profiles
                        WHERE id = :profileId
                        """)
                        .param("profileId", id)
                        .query((resultSet, rowNumber) -> new ProfileState(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("display_name"),
                                resultSet.getString("login"),
                                resultSet.getBoolean("active"),
                                resultSet.getObject("anonymized_at", OffsetDateTime.class) != null
                        ))
                        .optional());
    }

    void anonymizeProfile(UUID profileId, String marker, String previousMarker, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE crm_user_profiles
                SET display_name = :marker, login = NULL, anonymized_at = :now, version = version + 1, updated_at = :now
                WHERE id = :profileId
                """)
                .param("profileId", profileId)
                .param("marker", marker)
                .param("now", now)
                .update();
        jdbcClient.sql("""
                UPDATE crm_profile_events
                SET previous_display_name = CASE WHEN previous_display_name = display_name THEN :marker ELSE :previousMarker END,
                    display_name = :marker
                WHERE profile_id = :profileId
                """)
                .param("profileId", profileId)
                .param("marker", marker)
                .param("previousMarker", previousMarker)
                .update();
        for (String statement : List.of(
                "UPDATE crm_profile_events SET actor_display_name = :marker WHERE actor_profile_id = :profileId",
                "UPDATE organization_assignment_events SET previous_owner_manager_display_name = :marker WHERE previous_owner_manager_id = :profileId",
                "UPDATE organization_assignment_events SET new_owner_manager_display_name = :marker WHERE owner_manager_id = :profileId",
                "UPDATE organization_assignment_events SET actor_display_name = :marker WHERE actor_profile_id = :profileId",
                "UPDATE organization_team_events SET actor_display_name = :marker WHERE actor_profile_id = :profileId",
                "UPDATE organization_deputies SET deputy_display_name = :marker WHERE deputy_profile_id = :profileId",
                "UPDATE organization_deputies SET actor_display_name = :marker WHERE actor_profile_id = :profileId",
                "UPDATE organization_deputies SET ended_by_display_name = :marker WHERE ended_by_profile_id = :profileId",
                "UPDATE catalog_change_events SET actor_display_name = :marker WHERE actor_profile_id = :profileId",
                "UPDATE audit_events SET actor_display_name = :marker WHERE actor_profile_id = :profileId"
        )) {
            jdbcClient.sql(statement).param("profileId", profileId).param("marker", marker).update();
        }
    }

    // Замена идёт по тем же местам реестра и тем же признакам, что и поиск субъекта: что поиск показал, то
    // «Обезличить» и заменит, в любом вузе. Признаки строгие — ФИО целиком, полная почта, телефон от 7 цифр, — поэтому
    // одиночные слова и чужие файлы не задеваются. Файлы, отмеченные к удалению, не переименовываются.
    int replaceText(PersonalDataPlace place, SubjectTerms terms, String marker, List<UUID> deletedFiles, boolean dryRun) {
        if (terms.isEmpty() || place.kind() == PersonalDataPlace.Kind.RETAINED) {
            return 0;
        }
        String column = "x." + place.column();
        SqlMatch match = place.fileName() ? terms.attachmentMatch(column) : terms.textMatch(column);
        List<String> key = place.key();
        String excluded = place.kind() == PersonalDataPlace.Kind.ATTACHMENT && !deletedFiles.isEmpty()
                ? " AND x.id NOT IN (:deletedFiles)" : "";
        JdbcClient.StatementSpec select = jdbcClient.sql("SELECT " + String.join(", ", key.stream().map(k -> "x." + k).toList())
                + ", " + column + " AS pd_text FROM " + place.table() + " x WHERE " + match.sql() + excluded);
        match.params().forEach(select::param);
        if (!excluded.isEmpty()) {
            select.param("deletedFiles", deletedFiles);
        }
        List<StoredText> candidates = select.query((resultSet, rowNumber) -> {
            List<Object> values = new ArrayList<>();
            for (String part : key) {
                values.add(resultSet.getObject(part));
            }
            return new StoredText(values, resultSet.getString("pd_text"));
        }).list();
        int changed = 0;
        for (StoredText candidate : candidates) {
            String replaced = place.fileName()
                    ? terms.replaceInAttachmentName(candidate.text(), marker)
                    : place.json()
                    ? terms.replaceInJson(candidate.text(), marker, objectMapper)
                    : terms.replace(candidate.text(), marker);
            if (replaced == null || replaced.equals(candidate.text())) {
                continue;
            }
            if (!dryRun) {
                List<String> conditions = new ArrayList<>();
                for (int index = 0; index < key.size(); index++) {
                    conditions.add(key.get(index) + " = :k" + index);
                }
                JdbcClient.StatementSpec update = jdbcClient.sql("UPDATE " + place.table() + " SET " + place.column() + " = :text"
                                + (place.versioned() ? ", version = version + 1" : "")
                                + (place.clearedWith() == null ? "" : ", " + place.clearedWith() + " = NULL")
                                + " WHERE " + String.join(" AND ", conditions))
                        .param("text", place.fit(replaced));
                for (int index = 0; index < key.size(); index++) {
                    update.param("k" + index, candidate.key().get(index));
                }
                update.update();
            }
            changed++;
        }
        return changed;
    }

    // Прежние ФИО, почта и телефон контакта из истории его правок: КАМ мог сменить телефон, а старый остался в текстах.
    List<String> previousContactValues(UUID contactId, String field) {
        List<String> values = new ArrayList<>();
        for (String changes : jdbcClient.sql("SELECT changes FROM contact_events WHERE contact_id = :contactId")
                .param("contactId", contactId)
                .query(String.class)
                .list()) {
            try {
                for (com.fasterxml.jackson.databind.JsonNode change : objectMapper.readTree(changes)) {
                    if (field.equals(change.path("field").asText()) && change.path("previousValue").isTextual()) {
                        values.add(change.path("previousValue").asText());
                    }
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                // Нечитаемая запись истории не мешает обезличиванию: её текст всё равно проходит замену по реестру.
            }
        }
        return values;
    }

    List<StoredFile> findAttachmentFiles(List<UUID> attachmentIds) {
        if (attachmentIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("SELECT id, storage_key FROM attachments WHERE id IN (:ids)")
                .param("ids", attachmentIds)
                .query(this::mapFile)
                .list();
    }

    void deleteAttachment(UUID attachmentId) {
        jdbcClient.sql("DELETE FROM attachments WHERE id = :id").param("id", attachmentId).update();
    }

    List<UUID> expireReportResults(OffsetDateTime finishedBefore, String message) {
        List<UUID> expired = new ArrayList<>();
        for (StoredFile candidate : jdbcClient.sql("""
                        SELECT id, result_storage_key
                        FROM report_jobs
                        WHERE status = 'SUCCEEDED' AND result_storage_key IS NOT NULL AND finished_at < :finishedBefore
                        """)
                .param("finishedBefore", finishedBefore)
                .query(this::mapReportFile)
                .list()) {
            if (expireReport(candidate, message)) {
                expired.add(candidate.storageKey());
            }
        }
        return expired;
    }

    // Кандидаты — все готовые отчёты видов с текстами и ФИО (не «Востребованность» и не «Длительность»): упоминание
    // субъекта могло попасть и в карточку другого вуза, а с ней — в отчёт другой команды. Удалять ли кандидата, решает
    // проверка содержимого файла (ReportContentScanner): отчёт без субъекта остаётся.
    List<SubjectReport> findSubjectReports() {
        return jdbcClient.sql("""
                SELECT id, result_storage_key, format, group_by
                FROM report_jobs
                WHERE status = 'SUCCEEDED' AND result_storage_key IS NOT NULL AND kind NOT IN (:safeKinds)
                ORDER BY id
                """)
                .param("safeKinds", REPORTS_WITHOUT_PERSONAL_DATA)
                .query((resultSet, row) -> new SubjectReport(mapReportFile(resultSet, row), resultSet.getString("format"),
                        resultSet.getString("group_by")))
                .list();
    }

    // Файл остаётся, но в списке выгрузок отчёт помечен: проверить его на данные субъекта не удалось.
    boolean markReportForRebuild(StoredFile report, String message) {
        return jdbcClient.sql("""
                UPDATE report_jobs
                SET error_code = 'REPORT_NEEDS_REBUILD', error_message = :message
                WHERE id = :jobId AND result_storage_key = :storageKey
                """)
                .param("jobId", report.id())
                .param("storageKey", report.storageKey())
                .param("message", message)
                .update() == 1;
    }

    // Условие по ключу файла: параллельное скачивание или повторный запуск не удалят файл дважды.
    boolean expireReport(StoredFile report, String message) {
        return jdbcClient.sql("""
                UPDATE report_jobs
                SET result_storage_key = NULL, error_code = 'REPORT_RESULT_EXPIRED', error_message = :message
                WHERE id = :jobId AND result_storage_key = :storageKey
                """)
                .param("jobId", report.id())
                .param("storageKey", report.storageKey())
                .param("message", message)
                .update() == 1;
    }

    List<UUID> findInactiveContactIds(OffsetDateTime cutoff, int limit) {
        return jdbcClient.sql("""
                SELECT c.id
                FROM contacts c
                WHERE c.personal_data_status <> 'ANONYMIZED'
                  AND c.updated_at < :cutoff
                  AND NOT EXISTS (
                      SELECT 1 FROM interactions i
                      WHERE i.organization_id = c.organization_id AND i.updated_at >= :cutoff
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM interaction_events e
                      JOIN interactions i ON i.id = e.interaction_id
                      WHERE i.organization_id = c.organization_id AND e.occurred_at >= :cutoff
                  )
                ORDER BY c.updated_at, c.id
                LIMIT :limit
                """)
                .param("cutoff", cutoff)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    // Условное UPDATE только из PENDING, как при открытии плана: параллельное «Записать» выигрывает, если успело первым.
    // decided_by у истёкшего пуст — его никто не решал.
    List<ExpiredPlan> expireAgentPlans(OffsetDateTime now) {
        List<ExpiredPlan> due = jdbcClient.sql("""
                        SELECT id, interaction_id, client_id, expected_version
                        FROM agent_change_plans
                        WHERE state = 'PENDING' AND expires_at <= :now
                        """)
                .param("now", now)
                .query((resultSet, rowNumber) -> new ExpiredPlan(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getString("client_id"),
                        resultSet.getInt("expected_version")
                ))
                .list();
        return due.stream()
                .filter(plan -> jdbcClient.sql("""
                                UPDATE agent_change_plans SET state = 'EXPIRED', decided_at = :now
                                WHERE id = :id AND state = 'PENDING'
                                """)
                        .param("now", now)
                        .param("id", plan.id())
                        .update() == 1)
                .toList();
    }

    int deleteDecidedAgentPlans(OffsetDateTime decidedBefore) {
        return jdbcClient.sql("""
                        DELETE FROM agent_change_plans
                        WHERE state <> 'PENDING' AND COALESCE(decided_at, expires_at) < :decidedBefore
                        """)
                .param("decidedBefore", decidedBefore)
                .update();
    }

    List<UUID> findDismissedProfileIds(OffsetDateTime cutoff, int limit) {
        return jdbcClient.sql("""
                SELECT p.id
                FROM crm_user_profiles p
                WHERE p.active = FALSE AND p.pending_activation = FALSE AND p.anonymized_at IS NULL
                  AND COALESCE((
                      SELECT MAX(e.occurred_at) FROM crm_profile_events e
                      WHERE e.profile_id = p.id AND e.previous_active = TRUE AND e.active = FALSE
                  ), p.updated_at) < :cutoff
                ORDER BY p.updated_at, p.id
                LIMIT :limit
                """)
                .param("cutoff", cutoff)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    private StoredFile mapFile(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredFile(resultSet.getObject("id", UUID.class), resultSet.getObject("storage_key", UUID.class));
    }

    private StoredFile mapReportFile(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredFile(resultSet.getObject("id", UUID.class), resultSet.getObject("result_storage_key", UUID.class));
    }

    private record StoredText(List<Object> key, String text) {
    }

    record SubjectReport(StoredFile file, String format, String groupBy) {
    }

    record StoredFile(UUID id, UUID storageKey) {
    }

    record ContactState(
            UUID id,
            UUID organizationId,
            String organizationName,
            String name,
            String position,
            String email,
            String phone,
            PersonalDataStatus status,
            int version
    ) {
    }

    record ExpiredPlan(UUID id, UUID interactionId, String clientId, int expectedVersion) {
    }

    record ContactValues(String name, String position, String email, String phone) {
    }

    record ProfileState(UUID id, String displayName, String login, boolean active, boolean anonymized) {
    }
}
