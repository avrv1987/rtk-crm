package ru.rtk.crm.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.ContactRepository;
import ru.rtk.crm.enrolment.EnrolmentRepository;
import ru.rtk.crm.enrolment.LearnerPrivacyService;
import ru.rtk.crm.enrolment.LearnerRepository;
import ru.rtk.crm.enrolment.LearnerService;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.robustness.H2Schema;

// Б-1, Б-2 проверки 2.5.3: реестр мест с ПДн совпадает со схемой из миграций, а субъект, записанный во все места
// реестра (и в карточки другого вуза), находится поиском везде и после обезличивания не остаётся нигде в базе.
@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:personal_data_places;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PersonalDataRequestService.class, ru.rtk.crm.settings.TestSystemSettings.class,
        PersonalDataService.class,
        PersonalDataRepository.class,
        SubjectSearchRepository.class,
        SubjectReportWriter.class,
        RetentionService.class,
        AuditJournalRepository.class,
        CommandIdempotencyRepository.class,
        ContactRepository.class,
        LearnerPrivacyService.class,
        LearnerService.class,
        LearnerRepository.class,
        EnrolmentRepository.class,
        PersonalDataServiceTest.StorageConfiguration.class
})
class PersonalDataPlacesTest {
    private static final String NAME = "УАТБ Субъектов Пётр Иванович";
    private static final String EMAIL = "uatb.subject@example.test";
    private static final String PHONE_TEXT = "+7 (900) 555-12-34";
    private static final String TEXT = "Созвон: " + NAME + ", " + EMAIL + ", тел. " + PHONE_TEXT;
    private static final UUID ADMIN = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private static final UUID ALPHA = UUID.fromString("82000000-0000-0000-0000-000000000001");
    private static final UUID BETA = UUID.fromString("82000000-0000-0000-0000-000000000002");
    private static final UUID SUBJECT = UUID.fromString("83000000-0000-0000-0000-000000000001");
    private static final UUID BETA_INTERACTION = UUID.fromString("84000000-0000-0000-0000-000000000001");
    private static final UUID BETA_AGREEMENT = UUID.fromString("85000000-0000-0000-0000-000000000001");
    private static final UUID BETA_LIST = UUID.fromString("86000000-0000-0000-0000-000000000001");
    private static final UUID BETA_REQUEST = UUID.fromString("87000000-0000-0000-0000-000000000001");

    // Код, статус, ключ, хэш: короткие служебные значения, свободного текста в них нет.
    private static final Set<String> CODES = Set.of(
            "status", "kind", "type", "code", "role", "previous_role", "format", "mode", "source", "result", "action", "category",
            "state", "unit", "hour_unit", "teaching_unit", "internship_unit", "qualification_unit", "personal_data_status",
            "error_code", "checksum", "request_id", "request_fingerprint", "idempotency_key", "media_type", "attachment_type",
            "lms_role", "kind_code", "event_key", "entity_type", "object_type", "from_status", "to_status", "work_status",
            "work_status_after", "waiting_on", "risk_level", "issue_kind", "intern_kind", "transport", "topic", "token_hash",
            "subject_type", "signal_type", "setting_key", "series_by", "chart_type", "group_by", "security", "run_trigger",
            "run_kind", "result_source", "record_type", "receipt_number", "reason", "period_preset", "period_kind",
            "planned_kind", "payload_hash", "owner_role", "origin", "organization_type", "operation", "key_version",
            "external_status", "decision_role", "decision", "data_version", "contact_channel", "confirmation_status",
            "change_type", "channel", "session_id", "primary_id", "session_primary_id", "external_code", "inn",
            "email_hmac", "phone_hmac", "snils_hmac", "name_hmac", "last_name_hmac", "profile"
    );
    private static final int CODE_LENGTH = 64;
    // Таблицы целиком: справочники и настройки администратора (о процессах, не о людях), зашифрованные анкеты,
    // сеансы входа.
    private static final Map<String, String> TABLES = Map.ofEntries(
            Map.entry("organizations", "вуз: название, город, сайт, ИНН"),
            Map.entry("teams", "справочник"), Map.entry("products", "справочник"), Map.entry("programs", "справочник"),
            Map.entry("directions", "справочник"), Map.entry("vendors", "справочник"),
            Map.entry("document_kinds", "справочник"), Map.entry("interaction_kinds", "справочник"),
            Map.entry("interaction_kind_rules", "справочник"), Map.entry("interaction_stages", "этапы маршрута"),
            Map.entry("interaction_stage_documents", "этапы маршрута"), Map.entry("workflow_templates", "маршруты"),
            Map.entry("workflow_template_stages", "маршруты"), Map.entry("workflow_template_stage_documents", "маршруты"),
            Map.entry("workflow_template_versions", "маршруты"), Map.entry("agreement_activity_kinds", "справочник"),
            Map.entry("agreement_reference_items", "справочник"), Map.entry("agreement_reporting_settings", "настройки"),
            Map.entry("agreement_reporting_deadlines", "настройки"), Map.entry("people_settings", "настройки"),
            Map.entry("import_settings", "настройки"), Map.entry("system_settings", "настройки"),
            Map.entry("mail_settings", "настройки подключения"), Map.entry("source_settings", "настройки подключения"),
            Map.entry("labor_market_settings", "настройки подключения"), Map.entry("labor_market_mappings", "справочник"),
            Map.entry("labor_market_snapshots", "открытые данные рынка труда"), Map.entry("sources", "справочник"),
            Map.entry("source_mappings", "сопоставление справочников"), Map.entry("sync_runs", "запуски синхронизации"),
            Map.entry("mail_preferences", "темы писем"), Map.entry("mail_invitations", "хэш приглашения"),
            Map.entry("catalog_name_locks", "блокировки"), Map.entry("lms_signal_dismissals", "служебные отметки"),
            Map.entry("product_agreements", "лицензии ПО"), Map.entry("product_transfers", "передачи ПО"),
            Map.entry("enrolment_streams", "потоки курсов"), Map.entry("learning_snapshots", "курсы и группы LMS"),
            Map.entry("teacher_trainings", "курсы"), Map.entry("teacher_rosters", "курсы и группы LMS"),
            Map.entry("learners", "анкеты слушателей зашифрованы; обезличивает LearnerPrivacyService"),
            Map.entry("spring_session", "сеанс входа"), Map.entry("spring_session_attributes", "сеанс входа")
    );
    private static final String SNAPSHOT = "снимок имени сотрудника: заменяется по идентификатору профиля";
    private static final String RECORD = "запись самого субъекта: обезличивается целиком";
    private static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("contacts.name", RECORD), Map.entry("contacts.position", RECORD), Map.entry("contacts.email", RECORD),
            Map.entry("contacts.phone", RECORD), Map.entry("contacts.external_key", RECORD),
            Map.entry("crm_user_profiles.display_name", RECORD), Map.entry("crm_user_profiles.login", RECORD),
            Map.entry("crm_user_profiles.issuer", "адрес сервера входа"), Map.entry("crm_user_profiles.subject", "идентификатор входа"),
            Map.entry("crm_profile_events.actor_display_name", SNAPSHOT),
            Map.entry("crm_profile_events.previous_display_name", SNAPSHOT), Map.entry("crm_profile_events.display_name", SNAPSHOT),
            Map.entry("organization_assignment_events.previous_owner_manager_display_name", SNAPSHOT),
            Map.entry("organization_assignment_events.new_owner_manager_display_name", SNAPSHOT),
            Map.entry("organization_assignment_events.actor_display_name", SNAPSHOT),
            Map.entry("organization_team_events.actor_display_name", SNAPSHOT),
            Map.entry("organization_deputies.deputy_display_name", SNAPSHOT),
            Map.entry("organization_deputies.actor_display_name", SNAPSHOT),
            Map.entry("organization_deputies.ended_by_display_name", SNAPSHOT),
            Map.entry("audit_events.actor_display_name", SNAPSHOT), Map.entry("catalog_change_events.actor_display_name", SNAPSHOT),
            Map.entry("mail_outbox.mime_message", "закодированная копия письма: стирается при правке письма"),
            Map.entry("mail_outbox.recipient_masked", "адрес со звёздочками"),
            Map.entry("mail_outbox.attachment_type", "тип файла"), Map.entry("mail_outbox.event_key", "ключ события"),
            Map.entry("crm_exchange_events.event_key", "ключ события"), Map.entry("attachments.media_type", "тип файла"),
            Map.entry("agreement_files.media_type", "тип файла"),
            Map.entry("command_idempotency_records.idempotency_key", "ключ повтора от клиента"),
            Map.entry("report_jobs.idempotency_key", "ключ повтора от клиента"),
            Map.entry("mail_outbox.last_error", "текст ошибки системы"), Map.entry("crm_exchange_events.last_error", "текст ошибки системы"),
            Map.entry("report_jobs.error_message", "текст ошибки системы"),
            Map.entry("interaction_events.stage_name_snapshot", "название этапа"),
            Map.entry("interaction_events.from_stage_name_snapshot", "название этапа"),
            Map.entry("interaction_events.to_stage_name_snapshot", "название этапа"),
            Map.entry("interaction_events.external_key", "ключ импорта"), Map.entry("interactions.external_key", "ключ импорта"),
            Map.entry("vendor_contacts.external_key", "ключ импорта"), Map.entry("source_records.external_id", "ключ источника"),
            Map.entry("agreements.number", "номер договора"), Map.entry("agreement_supplements.number", "номер допсоглашения"),
            Map.entry("agreement_activity_lists.period_label", "подпись периода"),
            Map.entry("agreement_activity_list_items.agreement_number", "копия номера договора"),
            Map.entry("agreement_activity_list_items.kind_name", "копия справочника"),
            Map.entry("agreement_activity_list_items.education_level", "копия справочника"),
            Map.entry("agent_change_plans.client_id", "идентификатор клиента MCP"),
            Map.entry("agent_change_plans.evidence_event_ids", "идентификаторы событий"),
            Map.entry("catalog_imports.mapping_json", "сопоставление столбцов файла"),
            Map.entry("catalog_import_rows.sheet_name", "имя листа файла импорта"),
            Map.entry("internships.place", "место стажировки"), Map.entry("university_teaching.discipline", "дисциплина"),
            Map.entry("university_teaching.performer_company", "организация исполнителя"),
            Map.entry("university_teaching.student_group", "группа студентов"),
            Map.entry("teacher_qualifications.program", "программа"),
            Map.entry("teacher_qualifications.certificate_number", "номер удостоверения: стирается при обезличивании записи")
    );

    private final CrmProfile administrator = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PersonalDataService personalDataService;

    private static boolean schemaReady;

    @BeforeEach
    void setUp() throws IOException {
        if (!schemaReady) {
            H2Schema.apply(jdbc);
            // Строки ниже заполняются обобщённо: проверки значений и внешние ключи здесь не нужны.
            for (Map<String, Object> check : jdbc.queryForList(
                    "SELECT TABLE_NAME, CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_TYPE = 'CHECK'"
                            + " AND TABLE_SCHEMA = 'PUBLIC'")) {
                jdbc.execute("ALTER TABLE \"" + check.get("TABLE_NAME") + "\" DROP CONSTRAINT \"" + check.get("CONSTRAINT_NAME") + "\"");
            }
            schemaReady = true;
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
    }

    @Test
    void registryMatchesTheSchemaAndEveryTextColumnIsClassified() {
        Map<String, Integer> columns = textColumns();
        Set<String> registry = Arrays.stream(PersonalDataPlace.values())
                .map(place -> place.table() + "." + place.column())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(columns.keySet()).as("места реестра есть в схеме").containsAll(registry);
        assertThat(columns.keySet()).as("исключения не устарели").containsAll(COLUMNS.keySet());
        assertThat(registry).as("место не может быть и в реестре, и в исключениях").doesNotContainAnyElementsOf(COLUMNS.keySet());
        Set<String> tables = columns.keySet().stream().map(column -> column.substring(0, column.indexOf('.'))).collect(Collectors.toSet());
        assertThat(tables).containsAll(TABLES.keySet());

        List<String> unclassified = new ArrayList<>();
        columns.forEach((column, length) -> {
            String table = column.substring(0, column.indexOf('.'));
            String name = column.substring(column.indexOf('.') + 1);
            boolean code = CODES.contains(name) && length != null && length <= CODE_LENGTH;
            if (!registry.contains(column) && !COLUMNS.containsKey(column) && !TABLES.containsKey(table) && !code) {
                unclassified.add(column);
            }
        });
        assertThat(unclassified)
                .as("новое текстовое поле: добавьте его в PersonalDataPlace или объясните здесь, почему в нём нет ПДн")
                .isEmpty();
    }

    @Test
    void subjectInEveryRegistryPlaceIsFoundAndLeavesNoTraceAfterAnonymization() {
        clearAll();
        insert("crm_user_profiles", Map.of("id", ADMIN, "display_name", "Администратор", "role", "ADMIN"));
        insert("organizations", Map.of("id", ALPHA, "name", "УАТ-Б Университет Альфа"));
        insert("organizations", Map.of("id", BETA, "name", "УАТ-Б Университет Бета"));
        insert("contacts", Map.of("id", SUBJECT, "organization_id", ALPHA, "name", NAME, "email", EMAIL,
                "phone", "+7 900 555-77-88"));
        // КАМ сменил телефон: прежний остался в текстах и в истории контакта (contact_events).
        Map<String, List<PersonalDataPlace>> byTable = new LinkedHashMap<>();
        for (PersonalDataPlace place : PersonalDataPlace.values()) {
            byTable.computeIfAbsent(place.table(), table -> new ArrayList<>()).add(place);
        }
        Map<String, Integer> lengths = textColumns();
        byTable.forEach((table, places) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (PersonalDataPlace place : places) {
                Integer length = lengths.get(table + "." + place.column());
                values.put(place.column(), text(place, length));
            }
            values.putAll(fixedRow(table));
            insert(table, values);
        });
        jdbc.update("UPDATE contact_events SET changes = ? WHERE contact_id = ?",
                "[{\"field\":\"position\",\"previousValue\":\"" + NAME + ", методист\",\"value\":\"проректор\"},"
                        + "{\"field\":\"phone\",\"previousValue\":\"+7 900 555-12-34\",\"value\":\"+7 900 555-77-88\"}]", SUBJECT);
        jdbc.update("UPDATE mail_outbox SET mime_message = 'MIME copy'");

        SubjectSearchResult found = personalDataService.search(administrator, new SubjectQuery(NAME, null, null, null, null), "rq-1");
        Set<MentionPlace> expected = EnumSet.noneOf(MentionPlace.class);
        Arrays.stream(PersonalDataPlace.values())
                .filter(place -> place.kind() == PersonalDataPlace.Kind.MENTION || place.kind() == PersonalDataPlace.Kind.RETAINED)
                .forEach(place -> expected.add(place.place()));
        assertThat(found.mentions()).extracting(SubjectMention::place).containsAll(expected);
        assertThat(found.mentions()).filteredOn(mention -> mention.place() == MentionPlace.COMMENT)
                .extracting(SubjectMention::organizationName).containsOnly("УАТ-Б Университет Бета");
        assertThat(found.peopleRecords()).extracting(SubjectPersonRecord::kind).containsExactlyInAnyOrder(SubjectPersonRecord.Kind.values());
        assertThat(found.attachments()).hasSize(1);
        assertThat(found.sourceRecords()).hasSize(1);
        assertThat(personalDataService.search(administrator, new SubjectQuery(null, null, "+7 900 555 12 34", null, null), "rq-2")
                .mentions()).extracting(SubjectMention::place).containsAll(expected);

        AnonymizationRequest request = new AnonymizationRequest(new SubjectQuery(NAME, EMAIL, PHONE_TEXT, null, null),
                List.of(SUBJECT), List.of(), List.of(), List.of(), List.of(), PersonalDataReason.SUBJECT_REQUEST, null);
        AnonymizationResult preview = personalDataService.previewAnonymization(administrator, request);
        AnonymizationResult result = personalDataService.anonymize(administrator, request, "anonymize-everywhere", "rq-3");

        assertThat(result.mentions()).isEqualTo(preview.mentions()).isPositive();
        assertThat(result.technicalRecords()).isEqualTo(preview.technicalRecords()).isPositive();
        assertThat(leftovers()).as("ФИО, почта и телефон после обезличивания")
                .containsOnlyKeys("personal_data_requests.subject_name");
        assertThat(jdbc.queryForList("SELECT mime_message FROM mail_outbox", String.class)).containsOnlyNulls();
        assertThat(personalDataService.search(administrator, new SubjectQuery(NAME, EMAIL, PHONE_TEXT, null, null), "rq-4")
                .mentions()).extracting(SubjectMention::place).containsOnly(MentionPlace.PERSONAL_DATA_REQUEST);
    }

    // Все текстовые столбцы, где после обезличивания осталось ФИО, почта или телефон (в любой записи).
    private Map<String, Long> leftovers() {
        Map<String, Long> found = new TreeMap<>();
        textColumns().keySet().forEach(qualified -> {
            String table = qualified.substring(0, qualified.indexOf('.'));
            String column = qualified.substring(qualified.indexOf('.') + 1);
            String value = "LOWER(CAST(" + column + " AS VARCHAR))";
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + value + " LIKE '%субъектов%' OR "
                    + value + " LIKE '%uatb.subject%' OR REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(" + value
                    + ", ' ', ''), '-', ''), '(', ''), ')', ''), '+', '') LIKE '%900555%'", Long.class);
            if (count != null && count > 0) {
                found.put(qualified, count);
            }
        });
        return found;
    }

    private static String text(PersonalDataPlace place, Integer length) {
        if (place.json()) {
            return "{\"note\":\"" + TEXT + "\",\"phone\":\"+7 900 555-12-34\"}";
        }
        if (length != null && length < TEXT.length()) {
            return length >= NAME.length() ? NAME : "+7 900 555-12-34";
        }
        return TEXT;
    }

    // Связи строк с вузом Бета — другим вузом, не вузом субъекта.
    private static Map<String, Object> fixedRow(String table) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("organization_id", BETA);
        values.put("interaction_id", BETA_INTERACTION);
        values.put("agreement_id", BETA_AGREEMENT);
        values.put("list_id", BETA_LIST);
        values.put("request_id", BETA_REQUEST);
        values.put("contact_id", SUBJECT);
        values.put("subject_type", "CONTACT");
        values.put("subject_id", SUBJECT);
        switch (table) {
            case "interactions" -> values.put("id", BETA_INTERACTION);
            case "agreements" -> values.put("id", BETA_AGREEMENT);
            case "agreement_activity_lists" -> values.put("id", BETA_LIST);
            case "partner_requests" -> values.put("id", BETA_REQUEST);
            default -> { }
        }
        return values;
    }

    private void insert(String table, Map<String, Object> given) {
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> column : jdbc.queryForList("""
                SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, CHARACTER_MAXIMUM_LENGTH
                FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ?
                """, table.toUpperCase(Locale.ROOT))) {
            String name = ((String) column.get("COLUMN_NAME")).toLowerCase(Locale.ROOT);
            String type = (String) column.get("DATA_TYPE");
            Object value = given.get(name);
            if (value instanceof UUID && !"UUID".equals(type)) {
                value = null;
            }
            if (value == null && "NO".equals(column.get("IS_NULLABLE")) && column.get("COLUMN_DEFAULT") == null) {
                value = filler(type, name);
            }
            if (value != null) {
                names.add(name);
                values.add(value);
            }
        }
        jdbc.update("INSERT INTO " + table + " (" + String.join(", ", names) + ") VALUES ("
                + names.stream().map(name -> "?").collect(Collectors.joining(", ")) + ")", values.toArray());
    }

    private static Object filler(String type, String name) {
        return switch (type) {
            case "UUID" -> UUID.randomUUID();
            case "BOOLEAN" -> false;
            case "DATE" -> LocalDate.now();
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMP" -> OffsetDateTime.now();
            case "INTEGER", "BIGINT", "SMALLINT", "NUMERIC", "DECIMAL", "DOUBLE PRECISION", "REAL" -> 1;
            case "BINARY VARYING", "BINARY LARGE OBJECT" -> new byte[]{1};
            case "JSON" -> "{}";
            default -> name.endsWith("_json") ? "{}" : "x";
        };
    }

    private void clearAll() {
        for (String table : jdbc.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'",
                String.class)) {
            jdbc.execute("DELETE FROM \"" + table + "\"");
        }
    }

    private Map<String, Integer> textColumns() {
        Map<String, Integer> columns = new TreeMap<>();
        jdbc.query("""
                SELECT TABLE_NAME, COLUMN_NAME, CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = 'PUBLIC' AND DATA_TYPE IN ('CHARACTER VARYING', 'CHARACTER LARGE OBJECT', 'CHARACTER', 'JSON')
                """, resultSet -> {
            long length = resultSet.getLong(3);
            columns.put(resultSet.getString(1).toLowerCase(Locale.ROOT) + "." + resultSet.getString(2).toLowerCase(Locale.ROOT),
                    resultSet.wasNull() ? null : (int) Math.min(length, Integer.MAX_VALUE));
        });
        return columns;
    }
}
