package ru.rtk.crm.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import ru.rtk.crm.access.AdminCrmProfileAccessDeniedException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.attachment.AttachmentProperties;
import ru.rtk.crm.attachment.AttachmentStorage;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.ContactRepository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.enrolment.EnrolmentProperties;
import ru.rtk.crm.enrolment.EnrolmentRepository;
import ru.rtk.crm.enrolment.LearnerDataCipher;
import ru.rtk.crm.enrolment.LearnerPrivacyService;
import ru.rtk.crm.enrolment.LearnerPrivacyService.SubjectLearner;
import ru.rtk.crm.enrolment.LearnerPrivacyService.SubjectLearnerEnrolment;
import ru.rtk.crm.enrolment.LearnerRepository;
import ru.rtk.crm.enrolment.LearnerService;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.ReportProperties;
import ru.rtk.crm.report.ReportStorage;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:personal_data;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
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
class PersonalDataServiceTest {
    private static final UUID TEAM = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN = UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID LEADER = UUID.fromString("71000000-0000-0000-0000-000000000002");
    private static final UUID KAM = UUID.fromString("71000000-0000-0000-0000-000000000003");
    private static final UUID DISMISSED = UUID.fromString("71000000-0000-0000-0000-000000000004");
    private static final UUID RECENTLY_BLOCKED = UUID.fromString("71000000-0000-0000-0000-000000000005");
    private static final UUID UNIVERSITY = UUID.fromString("72000000-0000-0000-0000-000000000001");
    private static final UUID IDLE_UNIVERSITY = UUID.fromString("72000000-0000-0000-0000-000000000002");
    private static final UUID SUBJECT = UUID.fromString("73000000-0000-0000-0000-000000000001");
    private static final UUID COLLEAGUE = UUID.fromString("73000000-0000-0000-0000-000000000002");
    private static final UUID IDLE_CONTACT = UUID.fromString("73000000-0000-0000-0000-000000000003");
    private static final UUID INTERACTION = UUID.fromString("74000000-0000-0000-0000-000000000001");
    private static final UUID MENTION = UUID.fromString("75000000-0000-0000-0000-000000000001");
    private static final UUID PHONE_MENTION = UUID.fromString("75000000-0000-0000-0000-000000000002");
    private static final UUID NEUTRAL_EVENT = UUID.fromString("75000000-0000-0000-0000-000000000003");
    private static final UUID SUBJECT_FILE = UUID.fromString("76000000-0000-0000-0000-000000000001");
    private static final UUID PROGRAM_FILE = UUID.fromString("76000000-0000-0000-0000-000000000002");
    private static final UUID SITE_RECORD = UUID.fromString("77000000-0000-0000-0000-000000000001");
    private static final UUID OLD_REPORT = UUID.fromString("78000000-0000-0000-0000-000000000001");
    private static final UUID FRESH_REPORT = UUID.fromString("78000000-0000-0000-0000-000000000002");
    private static final String SUBJECT_NAME = "UAT Субъект Тестовый";
    private static final String SUBJECT_EMAIL = "uat.subject@example.test";
    private static final OffsetDateTime NOW = OffsetDateTime.now();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CrmProfile administrator = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);
    private final CrmProfile leader = new CrmProfile(LEADER, UserRole.LEADER, TEAM, 0);

    @Autowired
    private PersonalDataService personalDataService;

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private AttachmentProperties attachmentProperties;

    @Autowired
    private ReportProperties reportProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuditJournalRepository auditJournalRepository;

    @Autowired
    private LearnerDataCipher learnerDataCipher;

    private final UUID subjectFileKey = UUID.randomUUID();
    private final UUID programFileKey = UUID.randomUUID();
    private final UUID oldReportKey = UUID.randomUUID();
    private final UUID freshReportKey = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        createSchema();
        for (String table : List.of(
                "learner_enrolments", "learners", "enrolment_streams",
                "audit_events", "crm_profile_events", "organization_assignment_events", "organization_team_events",
                "report_jobs", "catalog_import_rows", "source_records", "attachments", "interaction_contacts",
                "interaction_events", "interactions", "contacts", "command_idempotency_records", "organizations",
                "crm_user_profiles"
        )) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        profile(ADMIN, "Администратор", "admin", "ADMIN", true);
        profile(LEADER, "Руководитель", "leader", "LEADER", true);
        profile(KAM, "КАМ А", "kam-a", "USER", true);
        profile(DISMISSED, "Пётр Уволенный", "petr", "USER", false);
        profile(RECENTLY_BLOCKED, "Олег Заблокированный", "oleg", "USER", false);
        organization(UNIVERSITY, "Университет А");
        organization(IDLE_UNIVERSITY, "Университет Тишины");
        contact(SUBJECT, UNIVERSITY, SUBJECT_NAME, SUBJECT_EMAIL, "+7 000 000-00-99", NOW);
        contact(COLLEAGUE, UNIVERSITY, "Ирина Другая", "irina@example.test", "+7 000 000-00-11", NOW.minusYears(4));
        contact(IDLE_CONTACT, IDLE_UNIVERSITY, "Семён Старый", "semen@example.test", null, NOW.minusYears(4));
        jdbcTemplate.update("""
                INSERT INTO interactions (id, organization_id, title, next_action, version, created_at, updated_at)
                VALUES (?, ?, 'Демо: внедрение', 'Позвонить UAT Субъект Тестовый', 0, ?, ?)
                """, INTERACTION, UNIVERSITY, NOW, NOW);
        jdbcTemplate.update("INSERT INTO interaction_contacts (interaction_id, organization_id, contact_id) VALUES (?, ?, ?)",
                INTERACTION, UNIVERSITY, SUBJECT);
        event(MENTION, "Созвонились с UAT Субъект Тестовый, ждём программу");
        event(PHONE_MENTION, "Телефон 8 (000) 000 00 99 уточнён");
        event(NEUTRAL_EVENT, "Без упоминаний");
        attachment(SUBJECT_FILE, "uat-subject-Testovyy.pdf", subjectFileKey);
        attachment(PROGRAM_FILE, "program.pdf", programFileKey);
        jdbcTemplate.update("""
                INSERT INTO source_records (id, source, record_type, external_id, status, organization_id, submitted_at, payload)
                VALUES (?, 'WEBSITE', 'PARTNERSHIP_REQUEST', 'pr-1', 'APPLIED', ?, ?, ?)
                """, SITE_RECORD, UNIVERSITY, NOW,
                "{\"representative\":{\"name\":\"UAT Субъект Тестовый\",\"email\":\"uat.subject@example.test\"}}");
        jdbcTemplate.update("INSERT INTO catalog_import_rows (id, plan_json) VALUES (?, ?)",
                UUID.randomUUID(), "{\"contactName\":\"UAT Субъект Тестовый\"}");
        report(OLD_REPORT, oldReportKey, NOW.minusDays(10));
        report(FRESH_REPORT, freshReportKey, NOW.minusHours(1));
    }

    @Test
    void administratorFindsSubjectInContactsCommentsFilesAndSourceRecordsWithoutJournalingTheTerms() {
        SubjectSearchResult byName = personalDataService.search(administrator, new SubjectQuery(SUBJECT_NAME, null, null, null, null), "rq-1");

        assertThat(byName.contacts()).extracting(SubjectContact::id).containsExactly(SUBJECT);
        assertThat(byName.contacts().getFirst().interactionsCount()).isEqualTo(1);
        assertThat(byName.mentions()).extracting(SubjectMention::place)
                .containsExactlyInAnyOrder(MentionPlace.COMMENT, MentionPlace.NEXT_ACTION);
        assertThat(byName.attachments()).extracting(SubjectAttachment::id).containsExactly(SUBJECT_FILE);
        assertThat(byName.sourceRecords()).extracting(SubjectSourceRecord::id).containsExactly(SITE_RECORD);

        SubjectSearchResult byPhone = personalDataService.search(
                administrator, new SubjectQuery(null, null, "+7 (000) 000-00-99", null, null), "rq-2"
        );
        assertThat(byPhone.contacts()).extracting(SubjectContact::id).containsExactly(SUBJECT);
        assertThat(byPhone.mentions()).extracting(SubjectMention::text).containsExactly("Телефон 8 (000) 000 00 99 уточнён");

        assertThat(jdbcTemplate.queryForList("SELECT details FROM audit_events WHERE action = 'SUBJECT_SEARCHED'", String.class))
                .hasSize(2)
                .allSatisfy(details -> assertThat(details).doesNotContain("Тестовый").doesNotContain("000"));
        assertThatThrownBy(() -> personalDataService.search(leader, new SubjectQuery(SUBJECT_NAME, null, null, null, null), "rq-3"))
                .isInstanceOf(AdminCrmProfileAccessDeniedException.class);
        assertThatThrownBy(() -> personalDataService.search(administrator, new SubjectQuery(" ", "", null, null, null), "rq-4"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("name"));
    }

    @Test
    void exportDescribesPurposesSourcesAndFoundDataInJsonAndPdf() throws IOException {
        PersonalDataService.SubjectExport json = personalDataService.export(
                administrator, new SubjectQuery(null, SUBJECT_EMAIL, null, null, null), SubjectExportFormat.JSON, "rq-export"
        );
        var document = objectMapper.readTree(json.content());
        assertThat(document.path("purposes")).hasSize(3);
        assertThat(document.path("data").path("contacts").get(0).path("name").asText()).isEqualTo(SUBJECT_NAME);
        assertThat(document.path("retention").get(0).asText()).contains("7 дн.");

        PersonalDataService.SubjectExport pdf = personalDataService.export(
                administrator, new SubjectQuery(SUBJECT_NAME, null, null, null, null), SubjectExportFormat.PDF, "rq-export-pdf"
        );
        assertThat(new String(pdf.content(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'SUBJECT_EXPORTED'", Long.class))
                .isEqualTo(2);
    }

    @Test
    void rectificationAndRestrictionAreVersionedIdempotentAndBlockNewLinks() {
        ContactRectification rectification = new ContactRectification(0, SUBJECT_NAME, "Методист", SUBJECT_EMAIL, "+7 000 000-00-98");
        SubjectContact rectified = personalDataService.rectify(administrator, SUBJECT, rectification, "rectify-1", "rq-5");
        SubjectContact replayed = personalDataService.rectify(administrator, SUBJECT, rectification, "rectify-1", "rq-5");

        assertThat(rectified.phone()).isEqualTo("+7 000 000-00-98");
        assertThat(rectified.version()).isEqualTo(1);
        assertThat(replayed).isEqualTo(rectified);
        assertThat(jdbcTemplate.queryForList("SELECT details FROM audit_events WHERE action = 'CONTACT_RECTIFIED'", String.class))
                .containsExactly("изменены поля: должность, телефон; было → стало: должность: пусто → «Методист»; "
                        + "телефон: «+7 000 000-00-99» → «+7 000 000-00-98»");
        assertThatThrownBy(() -> personalDataService.rectify(
                administrator, SUBJECT, new ContactRectification(0, SUBJECT_NAME, null, null, null), "rectify-stale", "rq-6"
        )).isInstanceOfSatisfying(PrivacyException.class, exception -> {
            assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
            assertThat(exception.currentVersion()).isEqualTo(1);
        });
        assertThatThrownBy(() -> personalDataService.rectify(administrator, SUBJECT, rectification, "rectify-1-changed", "rq-7"))
                .isInstanceOf(PrivacyException.class);

        SubjectContact restricted = personalDataService.restrict(
                administrator, SUBJECT, new ContactRestriction(1, true), "restrict-1", "rq-8"
        );
        assertThat(restricted.status()).isEqualTo(PersonalDataStatus.RESTRICTED);
        assertThat(contactRepository.findIdsByOrganizationId(UNIVERSITY, List.of(SUBJECT, COLLEAGUE))).containsExactly(COLLEAGUE);
        assertThat(contactRepository.findByOrganizationId(UNIVERSITY))
                .filteredOn(contact -> contact.id().equals(SUBJECT))
                .extracting(contact -> contact.personalDataStatus())
                .containsExactly(PersonalDataStatus.RESTRICTED);
        assertThatThrownBy(() -> personalDataService.restrict(
                administrator, SUBJECT, new ContactRestriction(2, true), "restrict-2", "rq-9"
        )).isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> personalDataService.restrict(
                leader, SUBJECT, new ContactRestriction(2, false), "restrict-leader", "rq-10"
        )).isInstanceOf(AdminCrmProfileAccessDeniedException.class);

        personalDataService.anonymize(
                administrator, new AnonymizationRequest(null, List.of(SUBJECT), List.of(), List.of(), List.of()), "anonymize-rectified", "rq-10a"
        );
        assertThat(jdbcTemplate.queryForList("SELECT details FROM audit_events WHERE action = 'CONTACT_RECTIFIED'", String.class))
                .containsExactly("изменены поля: должность, телефон");
    }

    @Test
    void anonymizationReplacesWholeWordsAndLeavesIdentifiersInsideStoredJson() throws IOException {
        UUID shortName = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String storedId = "00000000-0000-1234-5678-000000000000";
        event(shortName, "Жанна и Анна обсудили план, Анна перезвонит по 1234-5678");
        jdbcTemplate.update("""
                INSERT INTO command_idempotency_records (
                    id, actor_profile_id, operation, idempotency_key, request_fingerprint, result_json, created_at
                ) VALUES (?, ?, 'CREATE_INTERACTION', 'stored-command', ?, ?, ?)
                """, commandId, KAM, "0".repeat(64),
                "{\"id\":\"" + storedId + "\",\"version\":12345678,\"title\":\"Встреча с Анна, тел. 1234-5678\"}", NOW);

        personalDataService.anonymize(
                administrator,
                new AnonymizationRequest(new SubjectQuery("Анна", null, "1234-5678", null, null), List.of(), List.of(), List.of(), List.of()),
                "anonymize-short-name",
                "rq-short"
        );

        assertThat(comment(shortName)).isEqualTo("Жанна и Контакт обезличен обсудили план, Контакт обезличен перезвонит по Контакт обезличен");
        var stored = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT result_json FROM command_idempotency_records WHERE id = ?", String.class, commandId
        ));
        assertThat(stored.path("id").asText()).isEqualTo(storedId);
        assertThat(stored.path("version").asInt()).isEqualTo(12345678);
        assertThat(stored.path("title").asText()).isEqualTo("Встреча с Контакт обезличен, тел. Контакт обезличен");
        assertThat(comment(MENTION)).isEqualTo("Созвонились с UAT Субъект Тестовый, ждём программу");
    }

    @Test
    void anonymizationReplacesSubjectEverywhereDeletesChosenFilesAndKeepsHistory() throws IOException {
        AnonymizationRequest request = new AnonymizationRequest(
                new SubjectQuery(SUBJECT_NAME, null, "+7 000 000-00-99", "Testovyy", null),
                List.of(SUBJECT),
                List.of(),
                List.of(SUBJECT_FILE),
                List.of()
        );

        AnonymizationResult result = personalDataService.anonymize(administrator, request, "anonymize-1", "rq-11");

        assertThat(result.contacts()).isEqualTo(1);
        assertThat(result.attachmentsDeleted()).isEqualTo(1);
        assertThat(result.sourceRecords()).isEqualTo(1);
        assertThat(result.reportFilesDeleted()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap("SELECT name, email, phone, personal_data_status, external_key FROM contacts WHERE id = ?", SUBJECT))
                .containsEntry("NAME", "Контакт обезличен")
                .containsEntry("EMAIL", null)
                .containsEntry("PHONE", null)
                .containsEntry("PERSONAL_DATA_STATUS", "ANONYMIZED")
                .containsEntry("EXTERNAL_KEY", null);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM contacts WHERE id = ?", String.class, COLLEAGUE)).isEqualTo("Ирина Другая");
        assertThat(comment(MENTION)).isEqualTo("Созвонились с Контакт обезличен, ждём программу");
        assertThat(comment(PHONE_MENTION)).isEqualTo("Телефон Контакт обезличен уточнён");
        assertThat(comment(NEUTRAL_EVENT)).isEqualTo("Без упоминаний");
        assertThat(jdbcTemplate.queryForMap("SELECT next_action, version FROM interactions WHERE id = ?", INTERACTION))
                .containsEntry("NEXT_ACTION", "Позвонить Контакт обезличен")
                .containsEntry("VERSION", 1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachments WHERE id = ?", Long.class, SUBJECT_FILE)).isZero();
        assertThat(Files.exists(attachmentProperties.storageRoot().resolve(subjectFileKey.toString()))).isFalse();
        assertThat(Files.exists(attachmentProperties.storageRoot().resolve(programFileKey.toString()))).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT payload FROM source_records WHERE id = ?", String.class, SITE_RECORD))
                .doesNotContain("Тестовый").doesNotContain(SUBJECT_EMAIL).contains("Контакт обезличен");
        assertThat(jdbcTemplate.queryForObject("SELECT plan_json FROM catalog_import_rows", String.class)).doesNotContain("Тестовый");
        assertThat(jdbcTemplate.queryForList("SELECT error_code FROM report_jobs", String.class))
                .containsOnly("REPORT_RESULT_EXPIRED");
        assertThat(Files.exists(reportProperties.storageRoot().resolve(oldReportKey.toString()))).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT details FROM audit_events WHERE action = 'SUBJECT_ANONYMIZED'", String.class))
                .contains("контактов: 1").doesNotContain("Тестовый");

        SubjectSearchResult after = personalDataService.search(administrator, new SubjectQuery(SUBJECT_NAME, null, null, null, null), "rq-12");
        assertThat(after.contacts()).isEmpty();
        assertThat(after.mentions()).isEmpty();
        assertThat(personalDataService.anonymize(administrator, request, "anonymize-1", "rq-11")).isEqualTo(result);
        assertThatThrownBy(() -> personalDataService.anonymize(
                administrator, new AnonymizationRequest(null, List.of(COLLEAGUE), List.of(), List.of(), List.of()), "anonymize-1", "rq-13"
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception ->
                assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(() -> personalDataService.rectify(
                administrator, SUBJECT, new ContactRectification(1, "Новое имя", null, null, null), "rectify-anonymized", "rq-14"
        )).isInstanceOfSatisfying(PrivacyException.class, exception ->
                assertThat(exception.code()).isEqualTo("PERSONAL_DATA_ANONYMIZED"));
    }

    @Test
    void employeeAnonymizationNeedsBlockedProfileAndRewritesNameSnapshots() {
        jdbcTemplate.update("""
                INSERT INTO crm_profile_events (
                    id, profile_id, actor_profile_id, actor_display_name, previous_display_name, display_name,
                    previous_active, active, occurred_at
                ) VALUES (?, ?, ?, 'Администратор', 'Пётр', 'Пётр Уволенный', TRUE, TRUE, ?),
                         (?, ?, ?, 'Администратор', 'Пётр Уволенный', 'Пётр Уволенный', TRUE, FALSE, ?),
                         (?, ?, ?, 'Пётр Уволенный', 'КАМ А', 'КАМ А', FALSE, TRUE, ?)
                """,
                UUID.randomUUID(), DISMISSED, ADMIN, NOW.minusDays(20),
                UUID.randomUUID(), DISMISSED, ADMIN, NOW.minusDays(10),
                UUID.randomUUID(), KAM, DISMISSED, NOW.minusDays(30));
        jdbcTemplate.update("""
                INSERT INTO organization_assignment_events (
                    id, organization_id, previous_owner_manager_id, previous_owner_manager_display_name,
                    owner_manager_id, new_owner_manager_display_name, actor_profile_id, actor_display_name, occurred_at
                ) VALUES (?, ?, ?, 'Пётр Уволенный', NULL, NULL, ?, 'Администратор', ?)
                """, UUID.randomUUID(), UNIVERSITY, DISMISSED, ADMIN, NOW.minusDays(10));
        event(UUID.randomUUID(), "Передал дела, Пётр Уволенный");

        assertThatThrownBy(() -> personalDataService.anonymize(
                administrator, new AnonymizationRequest(null, List.of(), List.of(KAM), List.of(), List.of()), "anonymize-active", "rq-15"
        )).isInstanceOfSatisfying(PrivacyException.class, exception ->
                assertThat(exception.code()).isEqualTo("PROFILE_ACTIVE"));

        AnonymizationResult result = personalDataService.anonymize(
                administrator, new AnonymizationRequest(null, List.of(), List.of(DISMISSED), List.of(), List.of()), "anonymize-petr", "rq-16"
        );

        assertThat(result.profiles()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT display_name, login FROM crm_user_profiles WHERE id = ?", DISMISSED))
                .containsEntry("DISPLAY_NAME", "Сотрудник обезличен")
                .containsEntry("LOGIN", null);
        assertThat(jdbcTemplate.queryForList(
                "SELECT previous_display_name || ' / ' || display_name FROM crm_profile_events WHERE profile_id = ? ORDER BY occurred_at",
                String.class, DISMISSED
        )).containsExactly(
                "Сотрудник обезличен (прежнее имя) / Сотрудник обезличен",
                "Сотрудник обезличен / Сотрудник обезличен"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT actor_display_name FROM crm_profile_events WHERE actor_profile_id = ?", String.class, DISMISSED
        )).isEqualTo("Сотрудник обезличен");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT previous_owner_manager_display_name FROM organization_assignment_events", String.class
        )).isEqualTo("Сотрудник обезличен");
        assertThat(jdbcTemplate.queryForList("SELECT comment FROM interaction_events", String.class))
                .contains("Передал дела, Сотрудник обезличен");
    }

    @Test
    void learnersAreFoundOnlyByExactValuesExportedRestrictedAndAnonymizedWithJournalPerLearner() throws IOException {
        UUID stream = stream("Курс синтетики", LocalDate.of(2026, 6, 30));
        UUID learner = learner("Тестова", "Анна", "anna.subject@example.test", "+79000000077", "11223344595");
        UUID namesake = learner("Тестова", "Мария", "maria.subject@example.test", null, null);
        enrol(learner, stream);

        SubjectSearchResult bySnils = personalDataService.search(
                administrator, new SubjectQuery(null, null, null, null, "112-233-445 95"), "rq-l1"
        );
        assertThat(bySnils.learners()).extracting(SubjectLearner::id).containsExactly(learner);
        assertThat(bySnils.learners().getFirst().enrolments())
                .extracting(SubjectLearnerEnrolment::courseName, SubjectLearnerEnrolment::streamNo)
                .containsExactly(tuple("Курс синтетики", 1));
        assertThat(bySnils.contacts()).isEmpty();
        for (SubjectQuery query : List.of(
                new SubjectQuery("Анна Тестова", null, null, null, null),
                new SubjectQuery("Тестова  Анна Сергеевна", null, null, null, null),
                new SubjectQuery(null, "ANNA.SUBJECT@example.test", null, null, null),
                new SubjectQuery(null, null, "8 (900) 000-00-77", null, null)
        )) {
            assertThat(personalDataService.search(administrator, query, "rq-l2").learners())
                    .extracting(SubjectLearner::id).containsExactly(learner);
        }
        assertThat(personalDataService.search(administrator, new SubjectQuery(null, "anna.subject", null, null, null), "rq-l3")
                .learners()).isEmpty();
        assertThat(personalDataService.search(administrator, new SubjectQuery("Тестова", null, null, null, null), "rq-l3")
                .learners()).isEmpty();
        assertThat(personalDataService.search(administrator, new SubjectQuery("Мария Тестова", null, null, null, null), "rq-l3")
                .learners()).extracting(SubjectLearner::id).containsExactly(namesake);
        assertThatThrownBy(() -> personalDataService.search(administrator, new SubjectQuery(null, null, null, null, "12345"), "rq-l4"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("snils"));
        assertThat(jdbcTemplate.queryForList("""
                SELECT object_id FROM audit_events WHERE action = 'SUBJECT_SEARCHED' AND object_type = 'LEARNER'
                """, UUID.class)).hasSize(6).containsOnly(learner, namesake);

        PersonalDataService.SubjectExport json = personalDataService.export(
                administrator, new SubjectQuery(null, null, null, null, "11223344595"), SubjectExportFormat.JSON, "rq-l5"
        );
        var fields = objectMapper.readTree(json.content()).path("learnerProfiles").get(0).path("fields");
        assertThat(fields).anySatisfy(field -> {
            assertThat(field.path("name").asText()).isEqualTo("СНИЛС");
            assertThat(field.path("value").asText()).isEqualTo("11223344595");
        });
        assertThat(fields).anySatisfy(field -> assertThat(field.path("value").asText()).isEqualTo("Тестова"));
        PersonalDataService.SubjectExport pdf = personalDataService.export(
                administrator, new SubjectQuery(null, null, null, null, "11223344595"), SubjectExportFormat.PDF, "rq-l6"
        );
        assertThat(new String(pdf.content(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(jdbcTemplate.queryForList(
                "SELECT details FROM audit_events WHERE action = 'SUBJECT_EXPORTED' AND object_id = ? ORDER BY details",
                String.class, learner
        )).containsExactly("формат JSON", "формат PDF");

        SubjectLearner restricted = personalDataService.restrictLearner(
                administrator, learner, new ContactRestriction(0, true), "restrict-learner", "rq-l7"
        );
        assertThat(restricted.status()).isEqualTo(PersonalDataStatus.RESTRICTED);
        assertThat(restricted.version()).isEqualTo(1);
        assertThat(personalDataService.restrictLearner(
                administrator, learner, new ContactRestriction(0, true), "restrict-learner", "rq-l7"
        )).isEqualTo(restricted);
        assertThatThrownBy(() -> personalDataService.restrictLearner(
                administrator, learner, new ContactRestriction(0, false), "restrict-learner-stale", "rq-l8"
        )).isInstanceOfSatisfying(PrivacyException.class, exception -> {
            assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
            assertThat(exception.currentVersion()).isEqualTo(1);
        });
        assertThatThrownBy(() -> personalDataService.restrictLearner(
                leader, learner, new ContactRestriction(1, false), "restrict-learner-leader", "rq-l9"
        )).isInstanceOf(AdminCrmProfileAccessDeniedException.class);
        assertThatThrownBy(() -> personalDataService.restrictLearner(
                administrator, UUID.randomUUID(), new ContactRestriction(0, true), "restrict-learner-missing", "rq-l10"
        )).isInstanceOfSatisfying(PrivacyException.class, exception -> assertThat(exception.code()).isEqualTo("NOT_FOUND"));

        AnonymizationResult anonymized = personalDataService.anonymize(
                administrator,
                new AnonymizationRequest(null, List.of(), List.of(), List.of(), List.of(learner)),
                "anonymize-learner",
                "rq-l11"
        );
        assertThat(anonymized.learners()).isEqualTo(1);
        assertThat(anonymized.reportFilesDeleted()).isZero();
        assertThat(jdbcTemplate.queryForMap("SELECT personal_data_status, fields, snils_hmac FROM learners WHERE id = ?", learner))
                .containsEntry("PERSONAL_DATA_STATUS", "ANONYMIZED")
                .containsEntry("FIELDS", null)
                .containsEntry("SNILS_HMAC", null);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learner_enrolments WHERE learner_id = ?", Long.class, learner))
                .isEqualTo(1);
        assertThat(personalDataService.search(administrator, new SubjectQuery(null, null, null, null, "11223344595"), "rq-l12")
                .learners()).isEmpty();
        assertThatThrownBy(() -> personalDataService.restrictLearner(
                administrator, learner, new ContactRestriction(2, false), "restrict-learner-anonymized", "rq-l13"
        )).isInstanceOfSatisfying(PrivacyException.class, exception ->
                assertThat(exception.code()).isEqualTo("PERSONAL_DATA_ANONYMIZED"));
        assertThat(jdbcTemplate.queryForList("""
                SELECT action FROM audit_events WHERE object_type = 'LEARNER' AND action IN ('LEARNER_RESTRICTED', 'SUBJECT_ANONYMIZED')
                ORDER BY action
                """, String.class)).containsExactly("LEARNER_RESTRICTED", "SUBJECT_ANONYMIZED");
        assertThat(jdbcTemplate.queryForList("SELECT COALESCE(details, '') || COALESCE(object_name, '') FROM audit_events", String.class))
                .allSatisfy(text -> assertThat(text).doesNotContain("Тестова").doesNotContain("11223344595").doesNotContain("anna"));
    }

    @Test
    void fileAndReportDownloadsAreJournaledWithObjectPlaceAndRequestId() {
        jdbcTemplate.update("UPDATE report_jobs SET format = 'XLSX', row_count = 3, result_file_name = 'События.xlsx' WHERE id = ?",
                FRESH_REPORT);

        auditJournalRepository.recordAttachmentAccess(AuditAction.ATTACHMENT_DOWNLOADED, KAM, SUBJECT_FILE, "rq-file");
        auditJournalRepository.recordReportDownload(KAM, FRESH_REPORT, "rq-report");

        assertThat(jdbcTemplate.queryForList("""
                SELECT action || '|' || actor_display_name || '|' || object_name || '|' || details || '|' || request_id
                FROM audit_events ORDER BY request_id
                """, String.class)).containsExactly(
                "ATTACHMENT_DOWNLOADED|КАМ А|uat-subject-Testovyy.pdf|вуз «Университет А», карточка «Демо: внедрение»|rq-file",
                "REPORT_DOWNLOADED|КАМ А|События.xlsx|формат XLSX, строк: 3|rq-report"
        );
    }

    @Test
    void retentionDeletesOldReportFilesAndAnonymizesIdleContactsDismissedEmployeesAndExpiredLearners() throws IOException {
        jdbcTemplate.update("""
                INSERT INTO crm_profile_events (
                    id, profile_id, actor_profile_id, actor_display_name, previous_display_name, display_name,
                    previous_active, active, occurred_at
                ) VALUES (?, ?, ?, 'Администратор', 'Пётр Уволенный', 'Пётр Уволенный', TRUE, FALSE, ?),
                         (?, ?, ?, 'Администратор', 'Олег Заблокированный', 'Олег Заблокированный', TRUE, FALSE, ?)
                """,
                UUID.randomUUID(), DISMISSED, ADMIN, NOW.minusYears(2),
                UUID.randomUUID(), RECENTLY_BLOCKED, ADMIN, NOW.minusDays(1));
        jdbcTemplate.update("""
                INSERT INTO audit_events (id, category, action, actor_profile_id, actor_display_name, occurred_at)
                VALUES (?, 'DOWNLOAD', 'REPORT_DOWNLOADED', ?, 'КАМ А', ?)
                """, UUID.randomUUID(), KAM, NOW.minusYears(4));
        assertThatThrownBy(() -> retentionService.run(leader, "rq-17")).isInstanceOf(AdminCrmProfileAccessDeniedException.class);

        UUID endedStream = stream("Курс закончившийся", LocalDate.now().minusYears(3).minusDays(2));
        UUID runningStream = stream("Курс идущий", null);
        UUID expiredLearner = learner("Срокова", "Анна", "anna.srokova@example.test", null, null);
        UUID keptLearner = learner("Срокова", "Инна", "inna.srokova@example.test", null, null);
        enrol(expiredLearner, endedStream);
        enrol(keptLearner, runningStream);

        retentionService.applyScheduled();

        assertThat(jdbcTemplate.queryForObject("SELECT error_code FROM report_jobs WHERE id = ?", String.class, OLD_REPORT))
                .isEqualTo("REPORT_RESULT_EXPIRED");
        assertThat(jdbcTemplate.queryForObject("SELECT result_storage_key FROM report_jobs WHERE id = ?", UUID.class, FRESH_REPORT))
                .isEqualTo(freshReportKey);
        assertThat(Files.exists(reportProperties.storageRoot().resolve(oldReportKey.toString()))).isFalse();
        assertThat(Files.exists(reportProperties.storageRoot().resolve(freshReportKey.toString()))).isTrue();
        assertThat(status(IDLE_CONTACT)).isEqualTo("ANONYMIZED");
        assertThat(status(COLLEAGUE)).isEqualTo("ACTIVE");
        assertThat(status(SUBJECT)).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM crm_user_profiles WHERE id = ?", String.class, DISMISSED))
                .isEqualTo("Сотрудник обезличен");
        assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM crm_user_profiles WHERE id = ?", String.class, RECENTLY_BLOCKED))
                .isEqualTo("Олег Заблокированный");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'REPORT_DOWNLOADED'", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForMap("SELECT actor_display_name, details FROM audit_events WHERE action = 'RETENTION_APPLIED'"))
                .containsEntry("ACTOR_DISPLAY_NAME", "Система")
                .containsEntry("DETAILS", "удалено файлов отчётов: 1, обезличено контактов: 1, обезличено профилей: 1, "
                        + "обезличено анкет слушателей: 1, удалено записей журнала: 1");
        assertThat(jdbcTemplate.queryForMap("SELECT personal_data_status, fields FROM learners WHERE id = ?", expiredLearner))
                .containsEntry("PERSONAL_DATA_STATUS", "ANONYMIZED")
                .containsEntry("FIELDS", null);
        assertThat(jdbcTemplate.queryForObject("SELECT personal_data_status FROM learners WHERE id = ?", String.class, keptLearner))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learner_enrolments", Long.class)).isEqualTo(2);
        RetentionPolicy policy = retentionService.policy(administrator);
        assertThat(policy.learnerProfilesTerm()).isEqualTo("3 г.");
        assertThat(policy.learners()).isEqualTo(new LearnerPrivacyService.Counters(true, 1, 100_000, false, 1, 0));
        assertThat(RetentionService.term(Period.of(1, 6, 0))).isEqualTo("1 г. 6 мес.");
        assertThat(RetentionService.term(Period.ofDays(0))).isEqualTo("0 дн.");
        assertThat(retentionService.policy(administrator).lastRun()).isNotNull();
        assertThat(retentionService.policy(administrator).reportFilesDays()).isEqualTo(7);
    }

    private UUID stream(String course, LocalDate endsOn) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO enrolment_streams (id, course_key, course_name, stream_no, ends_on, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?, ?)
                """, id, "name:" + course, course, endsOn, NOW, NOW);
        return id;
    }

    private UUID learner(String lastName, String firstName, String email, String phone, String snils) throws IOException {
        UUID id = UUID.randomUUID();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("LAST_NAME", lastName);
        values.put("FIRST_NAME", firstName);
        values.put("PHONE", phone);
        values.put("EMAIL", email);
        values.put("SNILS", snils);
        Map<String, String> fields = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            if (value != null) {
                fields.put(field, learnerDataCipher.encrypt(value, "learner:" + id + ":" + field));
            }
        });
        jdbcTemplate.update("""
                INSERT INTO learners (
                    id, key_version, fields, email_hmac, phone_hmac, snils_hmac, name_hmac, last_name_hmac, missing_fields,
                    created_at, updated_at
                ) VALUES (?, 'v1', ?, ?, ?, ?, ?, ?, '', ?, ?)
                """, id, objectMapper.writeValueAsString(fields), learnerDataCipher.emailFingerprint(email),
                phone == null ? null : learnerDataCipher.phoneFingerprint(phone),
                snils == null ? null : learnerDataCipher.snilsFingerprint(snils),
                learnerDataCipher.nameFingerprint(lastName, firstName), learnerDataCipher.lastNameFingerprint(lastName), NOW, NOW);
        return id;
    }

    private void enrol(UUID learnerId, UUID streamId) {
        UUID sourceRecord = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO source_records (id, source, record_type, external_id, status, submitted_at, payload)
                VALUES (?, 'WEBSITE', 'paid_order', ?, 'APPLIED', ?, '{}')
                """, sourceRecord, "ORD-" + sourceRecord, NOW);
        jdbcTemplate.update("""
                INSERT INTO learner_enrolments (id, learner_id, stream_id, source_record_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), learnerId, streamId, sourceRecord, NOW, NOW);
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private String comment(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT comment FROM interaction_events WHERE id = ?", String.class, eventId);
    }

    private String status(UUID contactId) {
        return jdbcTemplate.queryForObject("SELECT personal_data_status FROM contacts WHERE id = ?", String.class, contactId);
    }

    private void profile(UUID id, String name, String login, String role, boolean active) {
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, login, role, team_id, active, pending_activation, updated_at)
                VALUES (?, 'issuer', ?, ?, ?, ?, ?, ?, FALSE, ?)
                """, id, id.toString(), name, login, role, "ADMIN".equals(role) ? null : TEAM, active, NOW.minusYears(3));
    }

    private void organization(UUID id, String name) {
        jdbcTemplate.update("INSERT INTO organizations (id, name, type, team_id, version, updated_at) VALUES (?, ?, 'UNIVERSITY', ?, 0, ?)",
                id, name, TEAM, NOW);
    }

    private void contact(UUID id, UUID organizationId, String name, String email, String phone, OffsetDateTime updatedAt) {
        jdbcTemplate.update("""
                INSERT INTO contacts (id, organization_id, name, position, email, phone, version, created_by, created_at, updated_at, external_key)
                VALUES (?, ?, ?, NULL, ?, ?, 0, ?, ?, ?, ?)
                """, id, organizationId, name, email, phone, KAM, updatedAt, updatedAt, "contact:" + name);
    }

    private void event(UUID id, String comment) {
        jdbcTemplate.update("""
                INSERT INTO interaction_events (id, interaction_id, comment, next_action, actor_profile_id, occurred_at)
                VALUES (?, ?, ?, NULL, ?, ?)
                """, id, INTERACTION, comment, KAM, NOW);
    }

    private void attachment(UUID id, String name, UUID storageKey) throws IOException {
        Files.writeString(attachmentProperties.storageRoot().resolve(storageKey.toString()), "content");
        jdbcTemplate.update("""
                INSERT INTO attachments (id, interaction_id, original_name, size_bytes, storage_key, status, created_at)
                VALUES (?, ?, ?, 7, ?, 'CLEAN', ?)
                """, id, INTERACTION, name, storageKey, NOW);
    }

    private void report(UUID id, UUID storageKey, OffsetDateTime finishedAt) throws IOException {
        Files.writeString(reportProperties.storageRoot().resolve(storageKey.toString()), "report");
        jdbcTemplate.update("""
                INSERT INTO report_jobs (id, status, result_storage_key, finished_at)
                VALUES (?, 'SUCCEEDED', ?, ?)
                """, id, storageKey, finishedAt);
    }

    private void createSchema() {
        for (String statement : List.of(
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (partner_organization_id UUID, partner_contact_id UUID, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY, issuer VARCHAR(512) NOT NULL, subject VARCHAR(512) NOT NULL,
                    display_name VARCHAR(200) NOT NULL, login VARCHAR(200), role VARCHAR(16) NOT NULL, team_id UUID,
                    active BOOLEAN NOT NULL, pending_activation BOOLEAN NOT NULL DEFAULT FALSE,
                    idp_enabled BOOLEAN NOT NULL DEFAULT TRUE, activation_requested_at TIMESTAMP WITH TIME ZONE,
                    anonymized_at TIMESTAMP WITH TIME ZONE, access_revision INTEGER NOT NULL DEFAULT 0,
                    version INTEGER NOT NULL DEFAULT 0, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY, name VARCHAR(300) NOT NULL, type VARCHAR(16) NOT NULL, team_id UUID NOT NULL,
                    owner_manager_id UUID, version INTEGER NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, city VARCHAR(200), website VARCHAR(300), inn VARCHAR(12)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS contacts (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, name VARCHAR(200) NOT NULL, position VARCHAR(200),
                    email VARCHAR(320), phone VARCHAR(50), version INTEGER NOT NULL, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    external_key VARCHAR(200), personal_data_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    decision_role VARCHAR(32), primary_contact BOOLEAN DEFAULT FALSE NOT NULL,
                    inactive BOOLEAN DEFAULT FALSE NOT NULL, confirmed_at TIMESTAMP WITH TIME ZONE, confirmed_by UUID
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_issues (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, kind VARCHAR(16) NOT NULL,
                    description VARCHAR(1000) NOT NULL, risk_level VARCHAR(16), responsible_profile_id UUID NOT NULL, due_on DATE,
                    status VARCHAR(16) DEFAULT 'OPEN' NOT NULL, resolution VARCHAR(1000), created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, resolved_by UUID, resolved_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interactions (next_step_partner_visible BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL, next_action VARCHAR(500),
                    version INTEGER NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_contacts (
                    interaction_id UUID NOT NULL, organization_id UUID NOT NULL, contact_id UUID NOT NULL,
                    PRIMARY KEY (interaction_id, contact_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_events (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, comment TEXT, next_action VARCHAR(500),
                    actor_profile_id UUID NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS attachments (partner_visible BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, original_name VARCHAR(255) NOT NULL,
                    size_bytes BIGINT NOT NULL, storage_key UUID NOT NULL, status VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS source_records (stream_no INTEGER, payload_hash CHAR(64), 
                    id UUID PRIMARY KEY, source VARCHAR(16) NOT NULL, record_type VARCHAR(64) NOT NULL,
                    external_id VARCHAR(200) NOT NULL, status VARCHAR(16) NOT NULL, organization_id UUID,
                    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL, payload TEXT NOT NULL
                )
                """,
                "CREATE TABLE IF NOT EXISTS catalog_import_rows (id UUID PRIMARY KEY, plan_json TEXT NOT NULL)",
                """
                CREATE TABLE IF NOT EXISTS command_idempotency_records (
                    id UUID PRIMARY KEY, actor_profile_id UUID NOT NULL, operation VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL, request_fingerprint CHAR(64) NOT NULL, result_json TEXT,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (actor_profile_id, operation, idempotency_key)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS audit_events (
                    id UUID PRIMARY KEY, category VARCHAR(32) NOT NULL, action VARCHAR(64) NOT NULL, actor_profile_id UUID,
                    actor_display_name VARCHAR(200) NOT NULL, object_type VARCHAR(32), object_id UUID,
                    object_name VARCHAR(500), details VARCHAR(2000), request_id VARCHAR(64),
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS report_jobs (
                    id UUID PRIMARY KEY, status VARCHAR(16) NOT NULL, result_storage_key UUID, error_code VARCHAR(64),
                    error_message VARCHAR(500), finished_at TIMESTAMP WITH TIME ZONE, format VARCHAR(8), row_count INTEGER,
                    result_file_name VARCHAR(255)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS crm_profile_events (previous_enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY, profile_id UUID NOT NULL, actor_profile_id UUID NOT NULL,
                    actor_display_name VARCHAR(200) NOT NULL, previous_display_name VARCHAR(200) NOT NULL,
                    display_name VARCHAR(200) NOT NULL, previous_active BOOLEAN NOT NULL, active BOOLEAN NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    CHECK (previous_display_name <> display_name OR previous_active <> active)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organization_assignment_events (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, previous_owner_manager_id UUID,
                    previous_owner_manager_display_name VARCHAR(200), owner_manager_id UUID,
                    new_owner_manager_display_name VARCHAR(200), actor_profile_id UUID NOT NULL,
                    actor_display_name VARCHAR(200) NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS enrolment_streams (
                    id UUID PRIMARY KEY, course_key VARCHAR(310) NOT NULL, course_name VARCHAR(1333) NOT NULL,
                    stream_no INTEGER NOT NULL, ends_on DATE, version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS learners (
                    id UUID PRIMARY KEY, key_version VARCHAR(16), fields TEXT, email_hmac CHAR(64), phone_hmac CHAR(64),
                    snils_hmac CHAR(64), name_hmac CHAR(64), last_name_hmac CHAR(64),
                    missing_fields VARCHAR(600) NOT NULL DEFAULT '', personal_data_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    anonymized_at TIMESTAMP WITH TIME ZONE, version INTEGER NOT NULL DEFAULT 0, created_by UUID,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS learner_enrolments (
                    id UUID PRIMARY KEY, learner_id UUID NOT NULL, stream_id UUID NOT NULL, source_record_id UUID NOT NULL,
                    lms_export_id UUID, lms_exported_at TIMESTAMP WITH TIME ZONE, lms_transferred_at TIMESTAMP WITH TIME ZONE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organization_team_events (
                    id UUID PRIMARY KEY, actor_profile_id UUID NOT NULL, actor_display_name VARCHAR(200) NOT NULL
                )
                """
        )) {
            jdbcTemplate.execute(statement);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfiguration {
        @Bean
        AttachmentProperties attachmentProperties() throws IOException {
            return new AttachmentProperties(
                    Files.createTempDirectory("personal-data-attachments"), "localhost", 3310,
                    Duration.ofSeconds(1), Duration.ofSeconds(1), DataSize.ofMegabytes(20)
            );
        }

        @Bean
        AttachmentStorage attachmentStorage(AttachmentProperties properties) {
            return new AttachmentStorage(properties);
        }

        @Bean
        ReportProperties reportProperties() throws IOException {
            return new ReportProperties(Files.createTempDirectory("personal-data-reports"), 1, 1, 100, 100);
        }

        @Bean
        ReportStorage reportStorage(ReportProperties properties) {
            return new ReportStorage(properties);
        }

        @Bean
        RetentionProperties retentionProperties() {
            return new RetentionProperties(
                    "-", Duration.ofDays(7), Duration.ofDays(1095), Duration.ofDays(365), Duration.ofDays(1095), Period.ofYears(3)
            );
        }

        @Bean
        LearnerDataCipher learnerDataCipher() {
            return new LearnerDataCipher(new EnrolmentProperties(true, "v1", Map.of("v1", randomKey()), randomKey()));
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder()
                    .findAndAddModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();
        }
    }
}
