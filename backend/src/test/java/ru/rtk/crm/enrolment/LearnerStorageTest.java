package ru.rtk.crm.enrolment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
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
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:learner_storage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        LearnerService.class,
        LearnerPrivacyService.class,
        LearnerRepository.class,
        EnrolmentRepository.class,
        AuditJournalRepository.class,
        LearnerStorageTest.CipherConfiguration.class
})
class LearnerStorageTest {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String FIRST_KEY = randomKey();
    private static final String SECOND_KEY = randomKey();
    private static final String FINGERPRINT_KEY = randomKey();
    private static final String NEW_FINGERPRINT_KEY = randomKey();
    private static final UUID OPERATOR = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.now();
    private static final LocalDate TODAY = LearnerTestData.TODAY;
    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {
    };

    @Autowired
    private LearnerService learnerService;

    @Autowired
    private LearnerPrivacyService learnerPrivacyService;

    @Autowired
    private LearnerRepository learnerRepository;

    @Autowired
    private EnrolmentRepository enrolmentRepository;

    @Autowired
    private AuditJournalRepository auditJournalRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of("learner_enrolments", "learners", "enrolment_streams", "source_records", "audit_events",
                "crm_user_profiles")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO crm_user_profiles (id, display_name) VALUES (?, 'Оператор зачисления')", OPERATOR);
    }

    @Test
    void everyFieldIsEncryptedSeparatelyAndNoPlainValueReachesTheDatabase() throws Exception {
        LearnerProfile profile = LearnerTestData.fullProfile().normalized();
        Learner learner = learnerService.create(LearnerTestData.fullProfile(), OPERATOR);
        enrol(learner.id(), stream("Курс синтетики", 2, null));
        learnerService.card(OPERATOR, learner.id(), "rq-card");
        learnerService.reveal(OPERATOR, learner.id(), EnumSet.allOf(LearnerFieldGroup.class), "rq-reveal");
        Learner changed = learnerService.update(OPERATOR, learner.id(), 0, withPhone(profile, "+79000000009"), "rq-change");

        assertThat(learner.profile()).isEqualTo(profile);
        assertThat(learner.missingFields()).isEmpty();
        assertThat(changed.version()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM learners WHERE id = ?", learner.id());
        Map<String, String> fields = objectMapper.readValue((String) row.get("FIELDS"), FIELDS);
        assertThat(fields).hasSize(LearnerField.values().length);
        assertThat(fields.values()).allSatisfy(value -> assertThat(value).matches("v1:[A-Za-z0-9+/]{16}:[A-Za-z0-9+/=]+"));
        assertThat(row).containsEntry("KEY_VERSION", "v1").containsEntry("MISSING_FIELDS", "");
        for (String column : List.of("EMAIL_HMAC", "PHONE_HMAC", "SNILS_HMAC", "NAME_HMAC", "LAST_NAME_HMAC")) {
            assertThat((String) row.get(column)).matches("[0-9a-f]{64}");
        }

        String database = databaseDump();
        List<String> plainValues = new ArrayList<>(List.of("112-233-445 95", "79000000001", "9000000001", "79000000009"));
        for (LearnerField field : LearnerField.values()) {
            plainValues.add(profile.stored(field));
            plainValues.add(profile.display(field));
            if (profile.value(field) instanceof LocalDate date) {
                plainValues.add(DateTimeFormatter.ofPattern("dd.MM.yyyy").format(date));
            }
        }
        assertThat(plainValues.stream().filter(value -> value.length() >= 10 || value.length() >= 6 && !value.matches("\\d+")).distinct())
                .hasSizeGreaterThan(25)
                .allSatisfy(value -> assertThat(database).doesNotContain(value));
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events ORDER BY action", String.class))
                .containsExactly("LEARNER_CHANGED", "LEARNER_FIELDS_REVEALED", "LEARNER_VIEWED");
    }

    @Test
    void additionalDataBindsCiphertextToLearnerAndField() throws Exception {
        Learner first = learnerService.create(LearnerTestData.fullProfile(), OPERATOR);
        Learner second = learnerService.create(LearnerTestData.requiredOnly("Примерова", "Ольга", "+79000000002", "olga@example.test"), OPERATOR);
        Map<String, String> firstFields = storedFields(first.id());
        Map<String, String> secondFields = storedFields(second.id());

        Map<String, String> movedField = new LinkedHashMap<>(firstFields);
        movedField.put(LearnerField.PASSPORT_NUMBER.name(), firstFields.get(LearnerField.SNILS.name()));
        storeFields(first.id(), movedField);
        Map<String, String> movedLearner = new LinkedHashMap<>(secondFields);
        movedLearner.put(LearnerField.LAST_NAME.name(), firstFields.get(LearnerField.LAST_NAME.name()));
        storeFields(second.id(), movedLearner);

        for (UUID id : List.of(first.id(), second.id())) {
            assertThatThrownBy(() -> learnerService.find(id))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Зашифрованное значение повреждено или не соответствует ключу");
        }
        storeFields(first.id(), firstFields);
        assertThat(learnerService.find(first.id()).orElseThrow().profile()).isEqualTo(LearnerTestData.fullProfile().normalized());
    }

    @Test
    void rotationJobReencryptsOldRecordsInBatchesAndRecomputesFingerprints() throws Exception {
        Learner full = learnerService.create(LearnerTestData.fullProfile(), OPERATOR);
        Learner broken = learnerService.create(LearnerTestData.requiredOnly("Сбоева", "Вера", "+79000000003", "vera@example.test"), OPERATOR);
        for (int index = 0; index < LearnerRekeyJob.BATCH; index++) {
            learnerService.create(LearnerTestData.requiredOnly("Пакетова", "Инна", null, "batch" + index + "@example.test"), OPERATOR);
        }
        Map<String, String> brokenFields = storedFields(broken.id());
        brokenFields.put(LearnerField.EMAIL.name(), brokenFields.get(LearnerField.FIRST_NAME.name()));
        storeFields(broken.id(), brokenFields);
        LearnerDataCipher rotated = cipher("v2", Map.of("v1", FIRST_KEY, "v2", SECOND_KEY), NEW_FINGERPRINT_KEY);
        LearnerService rotatedService = new LearnerService(learnerRepository, enrolmentRepository, rotated, auditJournalRepository);
        assertThat(learnerRepository.countWithOtherKey("v2")).isEqualTo(LearnerRekeyJob.BATCH + 2L);

        new LearnerRekeyJob(learnerRepository, rotatedService, rotated).reencryptWithActiveKey();

        assertThat(learnerRepository.countWithOtherKey("v2")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT key_version FROM learners WHERE id = ?", String.class, broken.id())).isEqualTo("v1");
        assertThat(storedFields(full.id()).values()).allSatisfy(value -> assertThat(value).startsWith("v2:"));
        assertThat(jdbcTemplate.queryForMap("SELECT key_version, version, name_hmac FROM learners WHERE id = ?", full.id()))
                .containsEntry("KEY_VERSION", "v2")
                .containsEntry("VERSION", 0)
                .containsEntry("NAME_HMAC", rotated.nameFingerprint("Тестова", "Анна"));
        LearnerDataCipher withoutOldKey = cipher("v2", Map.of("v2", SECOND_KEY), NEW_FINGERPRINT_KEY);
        LearnerService newKeyOnly = new LearnerService(learnerRepository, enrolmentRepository, withoutOldKey, auditJournalRepository);
        assertThat(newKeyOnly.find(full.id()).orElseThrow().profile()).isEqualTo(LearnerTestData.fullProfile().normalized());
        assertThat(newKeyOnly.findBySnils(LearnerTestData.VALID_SNILS)).map(Learner::id).contains(full.id());
        assertThat(newKeyOnly.findByContacts("BATCH7@example.test", null)).hasSize(1);
    }

    @Test
    void retentionAnonymizesLearnersWhoseEveryStreamEndedLongerAgoThanTheTerm() {
        UUID expired = stream("Курс А", 1, TODAY.minusYears(3).minusDays(1));
        UUID boundary = stream("Курс А", 2, TODAY.minusYears(3));
        UUID recent = stream("Курс Б", 1, TODAY.minusYears(1));
        UUID open = stream("Курс Б", 2, null);
        UUID onlyExpired = learner("Первая", expired);
        UUID alsoRecent = learner("Вторая", expired, recent);
        UUID openStream = learner("Третья", open);
        UUID onBoundary = learner("Четвёртая", boundary);
        UUID restricted = learner("Пятая", expired);
        assertThat(learnerRepository.updateStatus(restricted, 0, PersonalDataStatus.RESTRICTED, NOW)).isTrue();

        assertThat(learnerPrivacyService.anonymizeExpired(Period.ofYears(3), TODAY)).isEqualTo(2);

        for (UUID id : List.of(onlyExpired, restricted)) {
            assertThat(jdbcTemplate.queryForMap("""
                    SELECT fields, key_version, email_hmac, phone_hmac, snils_hmac, name_hmac, last_name_hmac, personal_data_status
                    FROM learners WHERE id = ?
                    """, id))
                    .containsEntry("PERSONAL_DATA_STATUS", "ANONYMIZED")
                    .allSatisfy((column, value) -> assertThat(column.equals("PERSONAL_DATA_STATUS") || value == null).isTrue());
        }
        assertThat(jdbcTemplate.queryForList("SELECT id FROM learners WHERE personal_data_status <> 'ANONYMIZED'", UUID.class))
                .containsExactlyInAnyOrder(alsoRecent, openStream, onBoundary);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learner_enrolments", Long.class)).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM enrolment_streams", Long.class)).isEqualTo(4);
        assertThat(learnerPrivacyService.anonymizeExpired(Period.ofYears(3), TODAY)).isZero();
        assertThat(learnerPrivacyService.counters())
                .isEqualTo(new LearnerPrivacyService.Counters(true, 3, 100_000, false, 1, 0));
        Learner anonymized = learnerService.find(onlyExpired).orElseThrow();
        assertThat(anonymized.profile()).isNull();
        assertThat(learnerService.card(OPERATOR, onlyExpired, "rq-anonymized").maskedValues()).isEmpty();
        assertThatThrownBy(() -> learnerService.reveal(OPERATOR, onlyExpired, Set.of(LearnerFieldGroup.MAIN), "rq-reveal"))
                .isInstanceOfSatisfying(InteractionConflictException.class, exception ->
                        assertThat(exception.code()).isEqualTo("PERSONAL_DATA_ANONYMIZED"));
    }

    @Test
    void cardMasksHiddenGroupsAndRevealJournalsGroupsWithoutValues() {
        Learner learner = learnerService.create(LearnerTestData.fullProfile(), OPERATOR);
        enrol(learner.id(), stream("Курс синтетики", 3, TODAY));

        LearnerCard card = learnerService.card(OPERATOR, learner.id(), "rq-card");
        Map<LearnerField, String> revealed = learnerService.reveal(
                OPERATOR, learner.id(), EnumSet.of(LearnerFieldGroup.DOCUMENTS, LearnerFieldGroup.PERSONAL), "rq-reveal"
        );

        assertThat(card.maskedValues())
                .hasSize(LearnerField.values().length)
                .containsEntry(LearnerField.LAST_NAME, "Тестова")
                .containsEntry(LearnerField.PHONE, "+7 9** ***-**-01")
                .containsEntry(LearnerField.EMAIL, "a***@example.test")
                .containsEntry(LearnerField.SNILS, "***-***-*** 95")
                .containsEntry(LearnerField.PASSPORT_NUMBER, "****78")
                .containsEntry(LearnerField.PASSPORT_SERIES, "***")
                .containsEntry(LearnerField.BIRTH_DATE, "***")
                .containsEntry(LearnerField.STREET, "***")
                .containsEntry(LearnerField.FIRST_NAME_DATIVE, "Анне")
                .containsEntry(LearnerField.DIPLOMA_NUMBER, "***");
        assertThat(card.enrolments()).extracting(LearnerEnrolment::courseName, LearnerEnrolment::streamNo)
                .containsExactly(tuple("Курс синтетики", 3));
        assertThat(revealed)
                .containsEntry(LearnerField.SNILS, "11223344595")
                .containsEntry(LearnerField.PASSPORT_SERIES, "0123")
                .containsEntry(LearnerField.BIRTH_DATE, "2000-02-29")
                .containsEntry(LearnerField.GENDER, "Ж")
                .doesNotContainKeys(LearnerField.LAST_NAME, LearnerField.REGION);
        assertThat(jdbcTemplate.queryForList("""
                SELECT action || '|' || category || '|' || object_type || '|' || object_name || '|' || actor_display_name || '|'
                       || COALESCE(details, '') || '|' || request_id
                FROM audit_events ORDER BY request_id
                """, String.class)).containsExactly(
                "LEARNER_VIEWED|LEARNER|LEARNER|Слушатель " + learner.id().toString().substring(0, 8) + "|Оператор зачисления||rq-card",
                "LEARNER_FIELDS_REVEALED|LEARNER|LEARNER|Слушатель " + learner.id().toString().substring(0, 8)
                        + "|Оператор зачисления|группы: DOCUMENTS, PERSONAL|rq-reveal"
        );
        assertThatThrownBy(() -> learnerService.reveal(OPERATOR, learner.id(), Set.of(), "rq-empty"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("groups"));
        assertThatThrownBy(() -> learnerService.card(OPERATOR, UUID.randomUUID(), "rq-missing"))
                .isInstanceOf(LearnerNotFoundException.class);
    }

    @Test
    void updateNeedsCurrentVersionAndFreeSnilsAndJournalsChangedFieldCodes() {
        Learner first = learnerService.create(LearnerTestData.fullProfile(), OPERATOR);
        Learner second = learnerService.create(LearnerTestData.requiredOnly("Примерова", "Ольга", "+79000000002", "olga@example.test"), OPERATOR);
        LearnerProfile withFirstSnils = withSnils(second.profile(), "112 233 445 95");

        assertThatThrownBy(() -> learnerService.update(OPERATOR, second.id(), 0, withFirstSnils, "rq-snils"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("SNILS"));
        assertThatThrownBy(() -> learnerService.create(withFirstSnils, OPERATOR))
                .isInstanceOf(InteractionValidationException.class);
        Learner updated = learnerService.update(OPERATOR, second.id(), 0, withPhone(second.profile(), "8 (900) 000-00-08"), "rq-phone");
        assertThat(updated.profile().phone()).isEqualTo("+79000000008");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(learnerService.update(OPERATOR, second.id(), 1, updated.profile(), "rq-same").version()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT details FROM audit_events WHERE action = 'LEARNER_CHANGED'", String.class))
                .containsExactly("поля: PHONE");
        assertThatThrownBy(() -> learnerService.update(OPERATOR, second.id(), 0, second.profile(), "rq-stale"))
                .isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
                    assertThat(exception.currentVersion()).isEqualTo(1);
                });
        assertThat(learnerService.findByContacts(null, "+7 900 000-00-08")).extracting(Learner::id).containsExactly(second.id());
        assertThat(learnerService.findByLastName("примерова")).extracting(Learner::id).containsExactly(second.id());
        assertThat(learnerService.findBySnils("11223344595")).map(Learner::id).contains(first.id());

        assertThat(learnerRepository.updateStatus(second.id(), 1, PersonalDataStatus.RESTRICTED, NOW)).isTrue();
        assertThatThrownBy(() -> learnerService.update(OPERATOR, second.id(), 2, updated.profile(), "rq-restricted"))
                .isInstanceOfSatisfying(InteractionConflictException.class, exception ->
                        assertThat(exception.code()).isEqualTo("PERSONAL_DATA_RESTRICTED"));
        assertThatThrownBy(() -> learnerService.reveal(OPERATOR, second.id(), Set.of(LearnerFieldGroup.MAIN), "rq-restricted"))
                .isInstanceOf(InteractionConflictException.class);
    }

    @Test
    void privacyFacadeFindsByExactFingerprintsDisclosesReadableValuesAndStaysSilentWhenModuleIsOff() {
        Learner full = learnerService.create(LearnerTestData.fullProfile(), OPERATOR);
        Learner partial = learnerService.create(LearnerTestData.requiredOnly("Примерова", "Ольга", "+79000000002", "olga@example.test"), OPERATOR);
        enrol(full.id(), stream("Курс синтетики", 2, LocalDate.of(2026, 6, 30)));

        List<LearnerPrivacyService.SubjectLearner> found = learnerPrivacyService.find("Анна  Тестова", "OLGA@example.test", null, null);
        List<LearnerPrivacyService.LearnerDisclosure> disclosed = learnerPrivacyService.disclose(List.of(full.id()));

        assertThat(found)
                .extracting(LearnerPrivacyService.SubjectLearner::id, LearnerPrivacyService.SubjectLearner::filledFields)
                .containsExactlyInAnyOrder(tuple(full.id(), 30), tuple(partial.id(), 4));
        assertThat(found).filteredOn(learner -> learner.id().equals(full.id())).singleElement()
                .satisfies(learner -> assertThat(learner.enrolments()).containsExactly(
                        new LearnerPrivacyService.SubjectLearnerEnrolment("Курс синтетики", 2, LocalDate.of(2026, 6, 30))
                ));
        assertThat(learnerPrivacyService.find("Тестова", null, "+7 900 000-00-00", null)).isEmpty();
        assertThat(disclosed.getFirst().fields())
                .extracting(LearnerPrivacyService.DisclosedField::name, LearnerPrivacyService.DisclosedField::value)
                .contains(
                        tuple("Отчество", "Сергеевна"),
                        tuple("Дата рождения", "29.02.2000"),
                        tuple("Пол", "Ж"),
                        tuple("Образование", Education.HIGHER_BACHELOR.title())
                );
        assertThat(disclosed.getFirst().toString()).doesNotContain("Тестова");
        LearnerPrivacyService moduleOff = new LearnerPrivacyService(
                learnerService, learnerRepository, enrolmentRepository,
                new LearnerDataCipher(new EnrolmentProperties(false, "", Map.of(), ""))
        );
        assertThat(moduleOff.find("Анна Тестова", null, null, "11223344595")).isEmpty();
        assertThat(moduleOff.disclose(List.of(full.id()))).isEmpty();
        assertThatThrownBy(() -> moduleOff.find(null, null, null, "12-34"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("snils"));
        assertThat(moduleOff.counters()).isEqualTo(new LearnerPrivacyService.Counters(false, 2, 100_000, false, 0, 0));
    }

    private UUID stream(String course, int number, LocalDate endsOn) {
        UUID id = enrolmentRepository.findOrCreateStream("name:" + course.toLowerCase(Locale.ROOT), course, number, NOW).id();
        jdbcTemplate.update("UPDATE enrolment_streams SET ends_on = ? WHERE id = ?", endsOn, id);
        return id;
    }

    private UUID learner(String firstName, UUID... streams) {
        UUID id = learnerService.create(
                LearnerTestData.requiredOnly("Срокова", firstName, null, firstName.toLowerCase(Locale.ROOT) + "@example.test"), OPERATOR
        ).id();
        for (UUID stream : streams) {
            enrol(id, stream);
        }
        return id;
    }

    private void enrol(UUID learnerId, UUID streamId) {
        UUID sourceRecord = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO source_records (id) VALUES (?)", sourceRecord);
        enrolmentRepository.createEnrolment(learnerId, streamId, sourceRecord, NOW);
    }

    private Map<String, String> storedFields(UUID learnerId) throws Exception {
        return objectMapper.readValue(
                jdbcTemplate.queryForObject("SELECT fields FROM learners WHERE id = ?", String.class, learnerId), FIELDS
        );
    }

    private void storeFields(UUID learnerId, Map<String, String> fields) throws Exception {
        jdbcTemplate.update("UPDATE learners SET fields = ? WHERE id = ?", objectMapper.writeValueAsString(fields), learnerId);
    }

    private String databaseDump() {
        StringBuilder dump = new StringBuilder();
        for (String table : jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'", String.class
        )) {
            jdbcTemplate.queryForList("SELECT * FROM " + table).forEach(row -> dump.append(row.values()).append('\n'));
        }
        return dump.toString();
    }

    private static LearnerProfile withPhone(LearnerProfile profile, String phone) {
        return copy(profile, LearnerField.PHONE, phone);
    }

    private static LearnerProfile withSnils(LearnerProfile profile, String snils) {
        return copy(profile, LearnerField.SNILS, snils);
    }

    private static LearnerProfile copy(LearnerProfile profile, LearnerField changedField, String value) {
        Map<LearnerField, String> values = new EnumMap<>(LearnerField.class);
        for (LearnerField field : LearnerField.values()) {
            String stored = field == changedField ? value : profile.stored(field);
            if (stored != null) {
                values.put(field, stored);
            }
        }
        return LearnerProfile.fromStored(values);
    }

    private static LearnerDataCipher cipher(String active, Map<String, String> keys, String fingerprintKey) {
        return new LearnerDataCipher(new EnrolmentProperties(true, active, keys, fingerprintKey));
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private void createSchema() {
        for (String statement : List.of(
                "CREATE TABLE IF NOT EXISTS crm_user_profiles (partner_organization_id UUID, partner_contact_id UUID, id UUID PRIMARY KEY, display_name VARCHAR(200) NOT NULL)",
                "CREATE TABLE IF NOT EXISTS source_records (id UUID PRIMARY KEY, external_id VARCHAR(200))",
                """
                CREATE TABLE IF NOT EXISTS audit_events (
                    id UUID PRIMARY KEY, category VARCHAR(32) NOT NULL, action VARCHAR(64) NOT NULL, actor_profile_id UUID,
                    actor_display_name VARCHAR(200) NOT NULL, object_type VARCHAR(32), object_id UUID,
                    object_name VARCHAR(500), details VARCHAR(2000), request_id VARCHAR(64),
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                LEARNER_SCHEMA_STREAMS,
                LEARNER_SCHEMA_LEARNERS,
                "CREATE UNIQUE INDEX IF NOT EXISTS learners_snils_key ON learners (snils_hmac)",
                LEARNER_SCHEMA_ENROLMENTS
        )) {
            jdbcTemplate.execute(statement);
        }
    }

    static final String LEARNER_SCHEMA_STREAMS = """
            CREATE TABLE IF NOT EXISTS enrolment_streams (
                id UUID PRIMARY KEY, course_key VARCHAR(310) NOT NULL, course_name VARCHAR(1333) NOT NULL,
                stream_no INTEGER NOT NULL CHECK (stream_no > 0), ends_on DATE, version INTEGER NOT NULL DEFAULT 0,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                CONSTRAINT enrolment_streams_key UNIQUE (course_key, stream_no)
            )
            """;

    static final String LEARNER_SCHEMA_LEARNERS = """
            CREATE TABLE IF NOT EXISTS learners (
                id UUID PRIMARY KEY, key_version VARCHAR(16), fields TEXT, email_hmac CHAR(64), phone_hmac CHAR(64),
                snils_hmac CHAR(64), name_hmac CHAR(64), last_name_hmac CHAR(64),
                missing_fields VARCHAR(600) NOT NULL DEFAULT '', personal_data_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                anonymized_at TIMESTAMP WITH TIME ZONE, version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
                created_by UUID REFERENCES crm_user_profiles(id),
                created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                CONSTRAINT learners_profile_state CHECK (
                    (personal_data_status = 'ANONYMIZED' AND anonymized_at IS NOT NULL
                        AND fields IS NULL AND key_version IS NULL
                        AND email_hmac IS NULL AND phone_hmac IS NULL AND snils_hmac IS NULL
                        AND name_hmac IS NULL AND last_name_hmac IS NULL)
                    OR (personal_data_status <> 'ANONYMIZED' AND anonymized_at IS NULL
                        AND fields IS NOT NULL AND key_version IS NOT NULL)
                )
            )
            """;

    static final String LEARNER_SCHEMA_ENROLMENTS = """
            CREATE TABLE IF NOT EXISTS learner_enrolments (
                id UUID PRIMARY KEY, learner_id UUID NOT NULL REFERENCES learners(id),
                stream_id UUID NOT NULL REFERENCES enrolment_streams(id),
                source_record_id UUID NOT NULL UNIQUE REFERENCES source_records(id),
                lms_export_id UUID, lms_exported_at TIMESTAMP WITH TIME ZONE, lms_transferred_at TIMESTAMP WITH TIME ZONE,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                CONSTRAINT learner_enrolments_learner_stream UNIQUE (learner_id, stream_id)
            )
            """;

    @TestConfiguration(proxyBeanMethods = false)
    static class CipherConfiguration {
        @Bean
        LearnerDataCipher learnerDataCipher() {
            return cipher("v1", Map.of("v1", FIRST_KEY), FINGERPRINT_KEY);
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
