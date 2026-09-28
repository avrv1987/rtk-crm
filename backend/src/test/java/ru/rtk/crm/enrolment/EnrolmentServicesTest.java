package ru.rtk.crm.enrolment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserProfileRepository;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.attachment.AttachmentContentValidator;
import ru.rtk.crm.attachment.AttachmentProperties;
import ru.rtk.crm.attachment.AttachmentScanOutcome;
import ru.rtk.crm.attachment.AttachmentScanner;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.privacy.RetentionProperties;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:enrolment_services;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        LearnerService.class,
        LearnerPrivacyService.class,
        LearnerRepository.class,
        EnrolmentRepository.class,
        AuditJournalRepository.class,
        CommandIdempotencyRepository.class,
        UserProfileRepository.class,
        EnrolmentAccess.class,
        EnrolmentCommands.class,
        PaidOrderEnrolment.class,
        EnrolmentStreamService.class,
        EnrolmentLearnerService.class,
        QuestionnaireImportService.class,
        LmsRosterService.class,
        LearnerWorkbookReader.class,
        LmsRosterWorkbookWriter.class,
        EnrolmentServicesTest.Configuration.class
})
class EnrolmentServicesTest {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY = randomKey();
    private static final String FINGERPRINT_KEY = randomKey();
    private static final AtomicReference<AttachmentScanOutcome> SCAN = new AtomicReference<>(AttachmentScanOutcome.CLEAN);
    private static final UUID OPERATOR_ID = UUID.fromString("82000000-0000-0000-0000-000000000001");
    private static final UUID KAM_ID = UUID.fromString("82000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN_ID = UUID.fromString("82000000-0000-0000-0000-000000000003");
    private static final CrmProfile OPERATOR = new CrmProfile(OPERATOR_ID, UserRole.USER, null, 0);
    private static final CrmProfile KAM = new CrmProfile(KAM_ID, UserRole.USER, null, 0);
    private static final CrmProfile ADMIN = new CrmProfile(ADMIN_ID, UserRole.ADMIN, null, 0);
    private static final String PROMPT = "Промпт-инжиниринг";
    private static final String TESTER = "Инженер-тестировщик";
    private static final String BORIS_EMAIL = "boris.vtorov@example.test";
    private static byte[] templateBytes;

    @Autowired
    private PaidOrderEnrolment paidOrderEnrolment;

    @Autowired
    private EnrolmentStreamService streams;

    @Autowired
    private EnrolmentLearnerService learners;

    @Autowired
    private QuestionnaireImportService questionnaires;

    @Autowired
    private LmsRosterService rosters;

    @Autowired
    private LearnerService learnerService;

    @Autowired
    private LearnerRepository learnerRepository;

    @Autowired
    private EnrolmentRepository enrolmentRepository;

    @Autowired
    private LearnerPrivacyService learnerPrivacyService;

    @Autowired
    private AuditJournalRepository auditJournalRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private EnrolmentCommands commands;

    @Autowired
    private RetentionProperties retentionProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of("learner_enrolments", "learners", "enrolment_streams", "source_records", "audit_events",
                "command_idempotency_records", "source_mappings", "programs", "crm_user_profiles")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        SCAN.set(AttachmentScanOutcome.CLEAN);
        profile(OPERATOR_ID, "Оператор зачисления", "USER", true);
        profile(KAM_ID, "КАМ без флага", "USER", false);
        profile(ADMIN_ID, "Администратор", "ADMIN", false);
    }

    @Test
    void paidOrdersFindLearnersByContactsAndNameAndNeverDuplicateThem() {
        LearnerIntake first = accept(order("ORD-1-DEMO01", PROMPT, 3, "Первова", "Анна", "Демовна", "+79000000001", "anna.pervova@example.test"));
        LearnerIntake secondCourse = accept(order("ORD-1-DEMO04", TESTER, 1, "Первова", "Анна", "Демовна", "+79000000001",
                "ANNA.PERVOVA@example.test"));
        LearnerIntake repeatInStream = accept(order("ORD-1-DEMO08", PROMPT, 3, "Первова", "Анна", null, "+79000000001",
                "anna.pervova@example.test"));
        LearnerIntake brother = accept(order("ORD-1-DEMO07", PROMPT, 3, "Шестаков", "Егор", "Демович", "+79000000007", "egor.sh@example.test"));
        LearnerIntake sister = accept(order("ORD-1-DEMO09", PROMPT, 3, "Шестакова", "Ева", "Демовна", "+79000000008", "egor.sh@example.test"));
        LearnerIntake typo = accept(order("ORD-1-DEMO10", TESTER, 1, "Перова", "Анна", "Демовна", "+79000000009", "anna.p@example.test"));

        assertThat(first).isEqualTo(new LearnerIntake(1, 0, 1, 0, 0, 0, 0));
        assertThat(secondCourse).isEqualTo(new LearnerIntake(0, 1, 1, 0, 0, 0, 0));
        assertThat(repeatInStream).isEqualTo(new LearnerIntake(0, 1, 0, 1, 0, 0, 0));
        assertThat(brother.plus(sister)).isEqualTo(new LearnerIntake(2, 0, 2, 0, 0, 0, 0));
        assertThat(typo).isEqualTo(new LearnerIntake(1, 0, 1, 0, 0, 0, 0));
        assertThat(count("learners")).isEqualTo(4);
        assertThat(count("learner_enrolments")).isEqualTo(5);
        UUID pervova = learner("Первова", "Анна").id();
        assertThat(enrolmentRepository.findByLearners(List.of(pervova))).extracting(LearnerEnrolment::courseName, LearnerEnrolment::orderNumber)
                .containsExactly(tuple(TESTER, "ORD-1-DEMO04"), tuple(PROMPT, "ORD-1-DEMO01"));
        assertThat(jdbcTemplate.queryForObject("SELECT created_by FROM learners WHERE id = ?", UUID.class, pervova)).isEqualTo(OPERATOR_ID);
    }

    @Test
    void repeatedOrderFillsOnlyEmptyFieldsWarnsAboutOtherContactsAndMovesEnrolmentWithLmsMarks() {
        accept(order("ORD-2-DEMO02", PROMPT, 3, "Второв", "Борис", null, "+79000000002", null));
        UUID boris = learner("Второв", "Борис").id();

        LearnerIntake filled = accept(order("ORD-2-DEMO02", PROMPT, 3, "Второв", "Борис", null, "+79000000022", BORIS_EMAIL));

        assertThat(filled).isEqualTo(new LearnerIntake(0, 1, 0, 0, 0, 0, 1));
        assertThat(learnerService.find(boris).orElseThrow().profile())
                .satisfies(profile -> {
                    assertThat(profile.phone()).isEqualTo("+79000000002");
                    assertThat(profile.email()).isEqualTo(BORIS_EMAIL);
                });
        assertThat(jdbcTemplate.queryForList("SELECT actor_profile_id FROM audit_events WHERE action = 'LEARNER_CHANGED'", UUID.class))
                .containsExactly(OPERATOR_ID);

        UUID exportId = UUID.randomUUID();
        jdbcTemplate.update("UPDATE learner_enrolments SET lms_export_id = ?, lms_exported_at = ?, lms_transferred_at = ?",
                exportId, OffsetDateTime.now(), OffsetDateTime.now());
        LearnerIntake moved = accept(order("ORD-2-DEMO02", PROMPT, 4, "Второв", "Борис", null, "+79000000002", BORIS_EMAIL));
        LearnerIntake back = accept(order("ORD-2-DEMO02", PROMPT, 3, "Второв", "Борис", null, "+79000000002", BORIS_EMAIL));

        assertThat(moved).isEqualTo(new LearnerIntake(0, 1, 0, 0, 1, 1, 0));
        assertThat(back).isEqualTo(new LearnerIntake(0, 1, 0, 0, 1, 0, 0));
        assertThat(jdbcTemplate.queryForMap("""
                SELECT s.stream_no, e.lms_export_id, e.lms_exported_at, e.lms_transferred_at
                FROM learner_enrolments e JOIN enrolment_streams s ON s.id = e.stream_id
                """)).containsEntry("STREAM_NO", 3).containsEntry("LMS_EXPORT_ID", null).containsEntry("LMS_TRANSFERRED_AT", null);

        accept(order("ORD-2-DEMO12", PROMPT, 4, "Второв", "Борис", null, "+79000000002", BORIS_EMAIL));
        LearnerIntake intoOccupied = accept(order("ORD-2-DEMO02", PROMPT, 4, "Второв", "Борис", null, "+79000000002", BORIS_EMAIL));

        assertThat(intoOccupied).isEqualTo(new LearnerIntake(0, 1, 0, 1, 1, 0, 0));
        assertThat(enrolmentRepository.findByLearners(List.of(boris))).extracting(LearnerEnrolment::streamNo, LearnerEnrolment::orderNumber)
                .containsExactly(tuple(4, "ORD-2-DEMO12"));
    }

    @Test
    void restrictedLearnerIsNotMatchedByNewOrdersAndAnonymizedLearnerKeepsItsEnrolment() {
        accept(order("ORD-3-A", PROMPT, 1, "Тестова", "Вера", null, "+79000000031", "vera@example.test"));
        Learner restricted = learner("Тестова", "Вера");
        assertThat(learnerRepository.updateStatus(restricted.id(), restricted.version(), PersonalDataStatus.RESTRICTED,
                OffsetDateTime.now())).isTrue();

        LearnerIntake another = accept(order("ORD-3-B", TESTER, 1, "Тестова", "Вера", null, "+79000000031", "vera@example.test"));
        LearnerIntake repeated = accept(order("ORD-3-A", PROMPT, 1, "Тестова", "Вера", null, "+79000000039", "vera@example.test"));

        assertThat(another.newLearners()).isEqualTo(1);
        assertThat(repeated).isEqualTo(new LearnerIntake(0, 1, 0, 0, 0, 0, 0));
        assertThat(learnerService.find(restricted.id()).orElseThrow().profile().phone()).isEqualTo("+79000000031");
    }

    @Test
    void streamsShowCountsProgramRetentionAndEndDateChangesWithVersionAndJournal() {
        UUID program = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO programs (id, name) VALUES (?, ?)", program, PROMPT);
        jdbcTemplate.update("""
                INSERT INTO source_mappings (id, source, kind, external_key, program_id) VALUES (?, 'WEBSITE', 'PROGRAM', ?, ?)
                """, UUID.randomUUID(), courseKey(PROMPT), program);
        seedPromptStream();
        accept(order("ORD-9-T", TESTER, 1, "Тестов", "Олег", null, "+79000000041", "oleg@example.test"));

        EnrolmentStreamsView view = streams.streams(OPERATOR);

        assertThat(view.streams()).extracting(EnrolmentStreamView::courseName, EnrolmentStreamView::streamNo,
                        EnrolmentStreamView::programName, EnrolmentStreamView::paid, EnrolmentStreamView::pending)
                .containsExactly(tuple(TESTER, 1, null, 1, 1), tuple(PROMPT, 3, PROMPT, 5, 5));
        assertThat(view.counters()).isEqualTo(new EnrolmentCountersView(6, 100_000, false, 2));
        EnrolmentStreamView prompt = view.streams().get(1);

        EnrolmentStreamView dated = streams.update(OPERATOR, prompt.id().toString(),
                new EnrolmentStreamUpdate(prompt.version(), LocalDate.of(2023, 9, 1)), "end-date", "rq-end");

        assertThat(dated.endsOn()).isEqualTo(LocalDate.of(2023, 9, 1));
        assertThat(dated.keepUntil()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(dated.version()).isEqualTo(1);
        assertThat(streams.update(OPERATOR, prompt.id().toString(),
                new EnrolmentStreamUpdate(prompt.version(), LocalDate.of(2023, 9, 1)), "end-date", "rq-end")).isEqualTo(dated);
        assertThatThrownBy(() -> streams.update(OPERATOR, prompt.id().toString(), new EnrolmentStreamUpdate(0, null), "stale", "rq"))
                .isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
                    assertThat(exception.currentVersion()).isEqualTo(1);
                });
        assertThat(journal("STREAM_END_DATE_CHANGED")).containsExactly(
                "STREAM|" + PROMPT + ", поток 3|дата окончания: не указана → 01.09.2023|Оператор зачисления");
        assertThatThrownBy(() -> streams.learners(OPERATOR, UUID.randomUUID().toString(), "rq"))
                .isInstanceOf(EnrolmentNotFoundException.class)
                .hasMessage("Поток не найден");
        assertThatThrownBy(() -> streams.learners(OPERATOR, "not-an-id", "rq"))
                .isInstanceOf(InteractionValidationException.class);
    }

    @Test
    void streamLearnersAreMaskedSortedAndSummarizedForTheRosterDialog() {
        seedPromptStream();
        Learner daria = learner("Пятакова", "Дарья");
        learnerRepository.updateStatus(daria.id(), daria.version(), PersonalDataStatus.RESTRICTED, OffsetDateTime.now());
        accept(order("ORD-9-NOEMAIL", PROMPT, 3, "Восьмова", "Лада", null, "+79000000044", null));
        UUID streamId = streamId(PROMPT, 3);

        EnrolmentStreamLearners view = streams.learners(OPERATOR, streamId.toString(), "rq-list");

        assertThat(view.learners()).extracting(StreamLearner::lastName)
                .containsExactly("Восьмова", "Второв", "Первова", "Пятакова", "Шестаков", "Шестакова");
        assertThat(view.learners().get(1)).satisfies(boris -> {
            assertThat(boris.phone()).isEqualTo("+7 9** ***-**-02");
            assertThat(boris.email()).isEqualTo("B***@EXAMPLE.TEST");
            assertThat(boris.orderNumber()).isEqualTo("ORD-D-02");
            assertThat(boris.requiredFields()).isEqualTo(27);
            assertThat(boris.filledFields()).isEqualTo(4);
            assertThat(boris.complete()).isFalse();
            assertThat(boris.lmsStatus()).isEqualTo(LmsStatus.PENDING);
        });
        assertThat(view.learners()).filteredOn(StreamLearner::duplicateEmail).extracting(StreamLearner::lastName)
                .containsExactly("Шестаков", "Шестакова");
        assertThat(view.learners().getFirst().missingForLms()).containsExactly(LearnerField.EMAIL);
        assertThat(view.learners()).filteredOn(learner -> learner.status() == PersonalDataStatus.RESTRICTED).hasSize(1);
        assertThat(view.roster().pending()).isEqualTo(new RosterScope(5, 1, 2, 1));
        assertThat(view.exports()).isEmpty();
        assertThat(journal("LEARNER_LIST_VIEWED")).containsExactly("STREAM|" + PROMPT + ", поток 3|строк: 6|Оператор зачисления");
    }

    @Test
    void operatorSearchesByExactValuesAndEditsProfileWithRulesVersionAndIdempotency() {
        seedPromptStream();
        Learner boris = learner("Второв", "Борис");

        assertThat(learners.search(OPERATOR, new LearnerSearch(LearnerSearchKind.EMAIL, " BORIS.VTOROV@example.test "), "rq-s1"))
                .extracting(LearnerSummary::id).containsExactly(boris.id());
        assertThat(learners.search(OPERATOR, new LearnerSearch(LearnerSearchKind.PHONE, "8 900 000-00-02"), "rq-s2")).hasSize(1);
        assertThat(learners.search(OPERATOR, new LearnerSearch(LearnerSearchKind.LAST_NAME, "шестакова"), "rq-s3"))
                .extracting(LearnerSummary::firstName).containsExactly("Ева");
        assertThat(learners.search(OPERATOR, new LearnerSearch(LearnerSearchKind.EMAIL, "boris@example.test"), "rq-s4")).isEmpty();
        assertThatThrownBy(() -> learners.search(OPERATOR, new LearnerSearch(LearnerSearchKind.SNILS, "12-34"), "rq"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("value"));
        assertThat(journal("LEARNER_SEARCHED")).contains("LEARNER|Поиск слушателя|вид: EMAIL; найдено: 1|Оператор зачисления");

        LearnerCardView card = learners.card(OPERATOR, boris.id().toString(), "rq-card");
        assertThat(card.values()).containsEntry(LearnerField.PHONE, "+7 9** ***-**-02").doesNotContainKey(LearnerField.SNILS);

        assertThatThrownBy(() -> update(boris, Map.of("BIRTH_DATE", TODAY().minusYears(17).toString()), "minor"))
                .isInstanceOfSatisfying(LearnerValidationException.class, exception -> assertThat(exception.fieldErrors())
                        .containsEntry("BIRTH_DATE", "Слушатель младше 18 лет: обработка данных несовершеннолетних не предусмотрена"));
        assertThatThrownBy(() -> update(boris, Map.of("PASSPORT_SERIES", "4509", "LAST_NAME", "", "NICKNAME", "x"), "invalid"))
                .isInstanceOfSatisfying(LearnerValidationException.class, exception -> assertThat(exception.fieldErrors())
                        .containsEntry("NICKNAME", "Такого поля в анкете нет")
                        .doesNotContainKey("PASSPORT_SERIES"));
        assertThatThrownBy(() -> update(boris, Map.of("PASSPORT_SERIES", "4509", "LAST_NAME", ""), "partial"))
                .isInstanceOfSatisfying(LearnerValidationException.class, exception -> assertThat(exception.fieldErrors())
                        .containsEntry("LAST_NAME", "Не заполнено обязательное поле «Фамилия»")
                        .containsEntry("PASSPORT_NUMBER", "Паспорт заполняется целиком: заполните поле «Номер паспорта»")
                        .containsKeys("PASSPORT_ISSUED_BY", "PASSPORT_ISSUE_DATE", "PASSPORT_DIVISION_CODE"));
        assertThatThrownBy(() -> update(boris, Map.of("SNILS", "112-233-445 96"), "checksum"))
                .isInstanceOfSatisfying(LearnerValidationException.class, exception -> assertThat(exception.fieldErrors())
                        .containsExactly(Map.entry("SNILS", "Контрольное число СНИЛС не совпадает с номером")));

        LearnerCardView saved = update(boris, Map.of(
                "SNILS", "112-233-445 95", "PASSPORT_SERIES", "45 09", "PASSPORT_NUMBER", "739251",
                "PASSPORT_ISSUED_BY", "ГУ МВД Демо", "PASSPORT_ISSUE_DATE", "2015-03-15", "PASSPORT_DIVISION_CODE", "123456",
                "GENDER", "м", "BIRTH_DATE", "1995-02-10", "EDUCATION", "Высшее образование - бакалавриат"), "documents");

        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.values()).containsEntry(LearnerField.SNILS, "***-***-*** 95").containsEntry(LearnerField.PASSPORT_NUMBER, "****51");
        assertThat(learnerService.find(boris.id()).orElseThrow().profile()).satisfies(profile -> {
            assertThat(profile.passportSeries()).isEqualTo("4509");
            assertThat(profile.passportDivisionCode()).isEqualTo("123-456");
            assertThat(profile.gender()).isEqualTo(Gender.MALE);
            assertThat(profile.education()).isEqualTo(Education.HIGHER_BACHELOR);
        });
        long events = count("audit_events");
        assertThat(update(boris, Map.of("SNILS", "112-233-445 95", "PASSPORT_SERIES", "45 09", "PASSPORT_NUMBER", "739251",
                "PASSPORT_ISSUED_BY", "ГУ МВД Демо", "PASSPORT_ISSUE_DATE", "2015-03-15", "PASSPORT_DIVISION_CODE", "123456",
                "GENDER", "м", "BIRTH_DATE", "1995-02-10", "EDUCATION", "Высшее образование - бакалавриат"), "documents").version())
                .isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(events);
        assertThatThrownBy(() -> update(boris, Map.of("APARTMENT", "2"), "stale"))
                .isInstanceOfSatisfying(InteractionConflictException.class, exception ->
                        assertThat(exception.code()).isEqualTo("VERSION_CONFLICT"));
        Learner daria = learner("Пятакова", "Дарья");
        assertThatThrownBy(() -> learners.update(OPERATOR, daria.id().toString(),
                new LearnerUpdate(daria.version(), Map.of("SNILS", "11223344595")), "taken", "rq"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("SNILS"));
        assertThat(learners.history(OPERATOR, boris.id().toString()))
                .extracting(LearnerHistoryEntry::action, LearnerHistoryEntry::details)
                .containsExactly(
                        tuple("LEARNER_CHANGED", "поля: SNILS, PASSPORT_SERIES, PASSPORT_NUMBER, PASSPORT_ISSUED_BY, PASSPORT_ISSUE_DATE, "
                                + "PASSPORT_DIVISION_CODE, GENDER, BIRTH_DATE, EDUCATION"),
                        tuple("LEARNER_VIEWED", null));
        assertThat(jdbcTemplate.queryForList("SELECT request_fingerprint FROM command_idempotency_records", String.class))
                .allSatisfy(fingerprint -> assertThat(fingerprint).matches("[0-9a-f]{64}"));
        assertThat(jdbcTemplate.queryForList("SELECT result_json FROM command_idempotency_records", String.class))
                .allSatisfy(result -> assertThat(result).doesNotContain("Второв", "112", "4509"));
    }

    @Test
    void duplicateFoundBySnilsGivesEnrolmentsAndEmptyFieldsToTheOwnerAndDisappears() {
        seedPromptStream();
        accept(order("ORD-5-P2", TESTER, 1, "Первова", "Анна", "Демовна", "+79000000001", "anna.pervova@example.test"));
        Learner owner = learner("Первова", "Анна");
        update(owner, Map.of("SNILS", "112-233-445 95"), "owner-snils");
        accept(order("ORD-5-TYPO", "Управление ИТ-проектами", 2, "Перова", "Анна", "Демовна", "+79000000009", "anna.p@example.test"));
        accept(order("ORD-5-TYPO2", TESTER, 1, "Перова", "Анна", "Демовна", "+79000000009", "anna.p@example.test"));
        Learner duplicate = learner("Перова", "Анна");
        update(duplicate, Map.of("APARTMENT", "12"), "duplicate-apartment");
        Learner source = learner("Перова", "Анна");

        assertThatThrownBy(() -> learners.move(OPERATOR, source.id().toString(), new LearnerMove("123", source.version()), "bad", "rq"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field()).isEqualTo("snils"));
        assertThatThrownBy(() -> learners.move(OPERATOR, source.id().toString(), new LearnerMove("123-456-789 64", source.version()),
                "nobody", "rq"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.getMessage()).isEqualTo("Слушателя с таким СНИЛС нет"));

        LearnerMoveResult result = learners.move(OPERATOR, source.id().toString(),
                new LearnerMove("112 233 445 95", source.version()), "move", "rq-move");

        assertThat(result).isEqualTo(new LearnerMoveResult(owner.id(), 1, 1, List.of(LearnerField.APARTMENT)));
        assertThat(learners.move(OPERATOR, source.id().toString(), new LearnerMove("112 233 445 95", source.version()), "move", "rq"))
                .isEqualTo(result);
        assertThat(learnerService.find(source.id())).isEmpty();
        assertThat(enrolmentRepository.findByLearners(List.of(owner.id()))).extracting(LearnerEnrolment::orderNumber)
                .containsExactly("ORD-5-P2", "ORD-D-01", "ORD-5-TYPO");
        assertThat(learnerService.find(owner.id()).orElseThrow().profile())
                .satisfies(profile -> {
                    assertThat(profile.apartment()).isEqualTo("12");
                    assertThat(profile.phone()).isEqualTo("+79000000001");
                });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_records WHERE external_id = 'ORD-5-TYPO2'", Long.class))
                .isEqualTo(1);
        assertThat(journal("LEARNER_ENROLMENTS_MOVED")).containsExactly("LEARNER|" + LearnerService.objectName(owner.id())
                + "|из анкеты " + source.id() + ": зачислений перенесено 1, удалено повторных 1; поля: APARTMENT|Оператор зачисления");
        assertThat(learners.history(OPERATOR, owner.id().toString())).extracting(LearnerHistoryEntry::action)
                .contains("LEARNER_ENROLMENTS_MOVED", "LEARNER_CHANGED");
    }

    @Test
    void filledTemplateIsPreviewedByCellAppliedOnlyForUpdateRowsAndRefusedAfterConcurrentChange() throws IOException {
        seedPromptStream();
        Learner pervova = learner("Первова", "Анна");
        update(pervova, Map.of("EDUCATION", Education.HIGHER_BACHELOR.title()), "pervova-education");
        String stream = streamId(PROMPT, 3).toString();
        QuestionnaireImport preview = questionnaires.preview(OPERATOR, stream, template(), "rq-preview");

        assertThat(preview.applied()).isFalse();
        assertThat(preview.rows()).extracting(QuestionnaireRow::rowNumber, QuestionnaireRow::status)
                .containsExactly(
                        tuple(2, QuestionnaireStatus.UPDATE),
                        tuple(3, QuestionnaireStatus.ERROR),
                        tuple(4, QuestionnaireStatus.UNCHANGED),
                        tuple(5, QuestionnaireStatus.ERROR),
                        tuple(6, QuestionnaireStatus.CONFLICT),
                        tuple(7, QuestionnaireStatus.UPDATE));
        assertThat(List.of(preview.updated(), preview.unchanged(), preview.errors(), preview.conflicts())).containsExactly(2, 1, 2, 1);
        QuestionnaireRow boris = preview.rows().getFirst();
        assertThat(boris.changedFields()).contains(LearnerField.SNILS, LearnerField.PASSPORT_SERIES, LearnerField.GENDER)
                .doesNotContain(LearnerField.LAST_NAME);
        assertThat(boris.issues()).containsExactly(new QuestionnaireIssue("G", "Серия паспорта", LearnerField.PASSPORT_SERIES,
                "Восстановлены ведущие нули: Excel сохранил значение числом", true));
        assertThat(preview.rows().get(1).issues()).containsExactly(new QuestionnaireIssue("F", "СНИЛС", LearnerField.SNILS,
                "Контрольное число СНИЛС не совпадает с номером", false));
        assertThat(preview.rows().get(3).issues()).extracting(QuestionnaireIssue::message)
                .containsExactly("Слушателя нет в потоке: сначала загрузите оплату");
        assertThat(preview.rows().get(4).issues()).extracting(QuestionnaireIssue::message)
                .containsExactly("Конфликт: ФИО не совпадает с анкетой");
        assertThat(preview.rows().get(5).learnerId()).isEqualTo(learner("Шестакова", "Ева").id());
        assertThat(journal("LEARNER_TEMPLATE_PREVIEWED")).containsExactly("STREAM|" + PROMPT
                + ", поток 3|строк: 6; обновить: 2, без изменений: 1, ошибок: 2, конфликтов: 1|Оператор зачисления");

        QuestionnaireImport applied = questionnaires.apply(OPERATOR, stream, template(), preview.fingerprint(), "apply", "rq-apply");

        assertThat(applied.applied()).isTrue();
        assertThat(applied.updated()).isEqualTo(2);
        assertThat(learner("Второв", "Борис").profile()).satisfies(profile -> {
            assertThat(profile.snils()).isEqualTo("12345678964");
            assertThat(profile.passportSeries()).isEqualTo("0412");
            assertThat(profile.gender()).isEqualTo(Gender.MALE);
        });
        assertThat(learner("Шестакова", "Ева").profile().education()).isEqualTo(Education.SECONDARY_VOCATIONAL);
        assertThat(learner("Шестаков", "Егор").profile().education()).isNull();
        assertThat(learner("Пятакова", "Дарья").profile().snils()).isNull();
        assertThat(questionnaires.apply(OPERATOR, stream, template(), preview.fingerprint(), "apply", "rq").rows())
                .allSatisfy(row -> assertThat(row.lastName()).isNull());
        assertThat(journal("LEARNER_TEMPLATE_IMPORTED")).containsExactly("STREAM|" + PROMPT
                + ", поток 3|строк: 6; применено: 2, без изменений: 1, пропущено с ошибками и конфликтами: 3|Оператор зачисления");

        QuestionnaireImport again = questionnaires.preview(OPERATOR, stream, template(), "rq-again");
        Learner daria = learner("Пятакова", "Дарья");
        update(daria, Map.of("APARTMENT", "7"), "daria-apartment");

        assertThat(again.rows()).extracting(QuestionnaireRow::status).containsExactly(QuestionnaireStatus.UNCHANGED,
                QuestionnaireStatus.ERROR, QuestionnaireStatus.UNCHANGED, QuestionnaireStatus.ERROR, QuestionnaireStatus.CONFLICT,
                QuestionnaireStatus.UNCHANGED);
        assertThatThrownBy(() -> questionnaires.apply(OPERATOR, stream, template(), again.fingerprint(), "stale", "rq"))
                .isInstanceOfSatisfying(InteractionConflictException.class, exception ->
                        assertThat(exception.code()).isEqualTo("VERSION_CONFLICT"));
        SCAN.set(AttachmentScanOutcome.REJECTED);
        assertThatThrownBy(() -> questionnaires.preview(OPERATOR, stream, template(), "rq"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.getMessage()).isEqualTo("Антивирусная проверка отклонила файл"));
    }

    @Test
    void juryDemoTemplateGivesTwoUpdatesThreeErrorsAndOneConflictOnAFreshStand() throws IOException {
        accept(order("ORD-20260901000001-DEMO01", PROMPT, 3, "Первова", "Анна", "Демовна", "+79000000001", "anna.pervova@example.test"));
        accept(order("ORD-20260901000002-DEMO02", PROMPT, 3, "Второв", "Борис", null, "+79000000002", "BORIS.VTOROV@EXAMPLE.TEST"));
        accept(order("ORD-20260901000006-DEMO06", PROMPT, 3, "Пятакова", "Дарья", "Демовна", "+79000000006", "daria.p@example.test"));
        byte[] demo = Files.readAllBytes(Path.of("..", "frontend", "public", "learners-filled-demo.xlsx"));

        QuestionnaireImport preview = questionnaires.preview(OPERATOR, streamId(PROMPT, 3).toString(),
                new MockMultipartFile("file", "learners-filled-demo.xlsx", null, demo), "rq-demo");

        assertThat(preview.ignoredHeaders()).isEmpty();
        assertThat(preview.rows()).extracting(QuestionnaireRow::rowNumber, QuestionnaireRow::status)
                .containsExactly(
                        tuple(2, QuestionnaireStatus.UPDATE),
                        tuple(3, QuestionnaireStatus.ERROR),
                        tuple(4, QuestionnaireStatus.UPDATE),
                        tuple(5, QuestionnaireStatus.ERROR),
                        tuple(6, QuestionnaireStatus.CONFLICT),
                        tuple(7, QuestionnaireStatus.ERROR));
        assertThat(preview.rows().getFirst().changedFields()).hasSizeGreaterThanOrEqualTo(24);
        assertThat(preview.rows().get(1).issues()).extracting(QuestionnaireIssue::column, QuestionnaireIssue::message)
                .containsExactly(tuple("F", "Контрольное число СНИЛС не совпадает с номером"));
        assertThat(preview.rows().get(2).changedFields()).containsExactly(LearnerField.EDUCATION);

        questionnaires.apply(OPERATOR, streamId(PROMPT, 3).toString(),
                new MockMultipartFile("file", "learners-filled-demo.xlsx", null, demo), preview.fingerprint(), "demo", "rq-apply");

        assertThat(learner("Второв", "Борис").missingFields()).containsExactlyInAnyOrder(
                LearnerField.MIDDLE_NAME, LearnerField.APARTMENT, LearnerField.MIDDLE_NAME_DATIVE);
        assertThat(EnrolmentStreamService.completeness(PersonalDataStatus.ACTIVE, learner("Второв", "Борис").missingFields()).complete())
                .isTrue();
    }

    @Test
    void templateRowsOfOneLearnerAndOtherStreamsAreErrors() throws IOException {
        seedPromptStream();
        accept(order("ORD-6-T", TESTER, 1, "Первова", "Анна", "Демовна", "+79000000001", "anna.pervova@example.test"));
        MockMultipartFile file = workbook(List.of(
                row("Второв", "Борис", null, "boris.vtorov@example.test", null, null),
                row("Второв", "Борис", "7 900 000-00-02", null, null, null),
                row("Первова", "Анна", null, "anna.pervova@example.test", null, "Среднее профессиональное образование")
        ));

        QuestionnaireImport promptPreview = questionnaires.preview(OPERATOR, streamId(PROMPT, 3).toString(), file, "rq-1");
        QuestionnaireImport testerPreview = questionnaires.preview(OPERATOR, streamId(TESTER, 1).toString(), file, "rq-2");

        assertThat(promptPreview.rows().get(1).issues()).extracting(QuestionnaireIssue::message)
                .containsExactly("Строка относится к тому же слушателю, что строка 2");
        assertThat(testerPreview.rows()).extracting(QuestionnaireRow::status)
                .containsExactly(QuestionnaireStatus.ERROR, QuestionnaireStatus.ERROR, QuestionnaireStatus.UPDATE);
    }

    @Test
    void rosterFileHasOneRowPerEnrolmentMarksOnlyItsExportAndSkipsRestrictedProfiles() throws IOException {
        seedPromptStream();
        Learner daria = learner("Пятакова", "Дарья");
        learnerRepository.updateStatus(daria.id(), daria.version(), PersonalDataStatus.RESTRICTED, OffsetDateTime.now());
        accept(order("ORD-7-NOEMAIL", PROMPT, 3, "Восьмова", "Лада", null, "+79000000044", null));
        String stream = streamId(PROMPT, 3).toString();

        LmsRoster excluded = rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.EXCLUDE), "rq-x");

        assertThat(excluded.fileName()).isEqualTo("LMS_" + PROMPT + "_поток3_" + LocalDate.now(EnrolmentLearnerService.ZONE) + ".xlsx");
        assertThat(lastNames(excluded)).containsExactly("Второв", "Первова", "Шестаков", "Шестакова");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learner_enrolments WHERE lms_export_id = ?", Long.class,
                excluded.exportId())).isEqualTo(4);
        assertThat(journal("LMS_ROSTER_EXPORTED")).containsExactly("STREAM|" + PROMPT + ", поток 3|строк: 4; выгрузка: "
                + excluded.exportId() + "; режим: только не переданные; анкеты без обязательных полей: исключены|Оператор зачисления");

        RosterExportMarked marked = rosters.markTransferred(OPERATOR, excluded.exportId().toString(), "mark-1", "rq-m1");
        RosterExportMarked again = rosters.markTransferred(OPERATOR, excluded.exportId().toString(), "mark-2", "rq-m2");

        assertThat(marked).isEqualTo(new RosterExportMarked(excluded.exportId(), streamId(PROMPT, 3), 4, 4));
        assertThat(again.marked()).isZero();
        assertThat(rosters.markTransferred(OPERATOR, excluded.exportId().toString(), "mark-1", "rq")).isEqualTo(marked);
        assertThat(journal("LMS_ROSTER_MARKED")).hasSize(1);
        assertThatThrownBy(() -> rosters.markTransferred(OPERATOR, UUID.randomUUID().toString(), "mark-3", "rq"))
                .isInstanceOf(EnrolmentNotFoundException.class);

        LmsRoster withEmptyCells = rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE),
                "rq-y");
        LmsRoster all = rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.ALL, IncompleteProfiles.INCLUDE), "rq-z");

        assertThat(lastNames(withEmptyCells)).containsExactly("Восьмова");
        assertThat(lastNames(all)).containsExactly("Восьмова", "Второв", "Первова", "Шестаков", "Шестакова");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learner_enrolments WHERE lms_export_id = ?", Long.class,
                excluded.exportId())).isEqualTo(4);
        rosters.markTransferred(OPERATOR, all.exportId().toString(), "mark-all", "rq");
        assertThatThrownBy(() -> rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE),
                "rq"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.getMessage())
                        .isEqualTo("Анкеты этих слушателей не выгружаются: обработка ограничена или анкета обезличена"));
        EnrolmentStreamLearners view = streams.learners(OPERATOR, stream, "rq");
        assertThat(view.exports()).extracting(RosterExport::rows, RosterExport::transferred)
                .containsExactlyInAnyOrder(tuple(4, 4), tuple(1, 1));
        assertThat(view.stream().transferred()).isEqualTo(5);
        assertThat(view.stream().pending()).isEqualTo(1);
    }

    @Test
    void onlyOperatorWithEnabledModuleUsesTheSectionAndNoPlainValueReachesTheDatabase() throws IOException {
        seedPromptStream();
        Learner boris = learner("Второв", "Борис");
        String stream = streamId(PROMPT, 3).toString();
        List<Runnable> calls = List.of(
                () -> streams.streams(KAM),
                () -> streams.learners(KAM, stream, "rq"),
                () -> streams.update(KAM, stream, new EnrolmentStreamUpdate(0, null), "k", "rq"),
                () -> learners.search(KAM, new LearnerSearch(LearnerSearchKind.LAST_NAME, "Второв"), "rq"),
                () -> learners.card(KAM, boris.id().toString(), "rq"),
                () -> learners.reveal(KAM, boris.id().toString(), new LearnerReveal(Set.of(LearnerFieldGroup.DOCUMENTS)), "rq"),
                () -> learners.update(KAM, boris.id().toString(), new LearnerUpdate(0, Map.of("APARTMENT", "1")), "k", "rq"),
                () -> learners.move(KAM, boris.id().toString(), new LearnerMove("11223344595", 0), "k", "rq"),
                () -> learners.history(KAM, boris.id().toString()),
                () -> rosters.export(KAM, stream, new LmsRosterRequest(RosterMode.ALL, IncompleteProfiles.INCLUDE), "rq"),
                () -> rosters.markTransferred(KAM, UUID.randomUUID().toString(), "k", "rq")
        );
        for (Runnable call : calls) {
            assertThatThrownBy(call::run).isInstanceOf(EnrolmentAccessDeniedException.class);
        }
        assertThatThrownBy(() -> learners.card(ADMIN, boris.id().toString(), "rq")).isInstanceOf(EnrolmentAccessDeniedException.class);
        assertThatThrownBy(() -> questionnaires.preview(ADMIN, stream, template(), "rq")).isInstanceOf(EnrolmentAccessDeniedException.class);
        EnrolmentAccess moduleOff = new EnrolmentAccess(new EnrolmentProperties(false, null, null, null), userProfileRepository);
        EnrolmentStreamService offStreams = new EnrolmentStreamService(moduleOff, enrolmentRepository, learnerService,
                learnerPrivacyService, retentionProperties, auditJournalRepository, commands);
        assertThatThrownBy(() -> offStreams.streams(OPERATOR)).isInstanceOf(EnrolmentDisabledException.class);
        assertThatThrownBy(() -> offStreams.learners(OPERATOR, "not-an-id", "rq")).isInstanceOf(EnrolmentDisabledException.class);
        assertThat(journal("LEARNER_VIEWED")).isEmpty();

        learners.reveal(OPERATOR, boris.id().toString(), new LearnerReveal(Set.of(LearnerFieldGroup.MAIN)), "rq-reveal");
        update(boris, Map.of("SNILS", "123-456-789 64"), "snils");
        questionnaires.preview(OPERATOR, stream, template(), "rq-preview");
        rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.ALL, IncompleteProfiles.INCLUDE), "rq-export");

        String database = databaseDump();
        for (String value : List.of("Второв", "Борис", "Первова", "Шестакова", "boris.vtorov", "egor.sh", "9000000002", "12345678964",
                "123-456-789 64")) {
            assertThat(database).doesNotContain(value);
        }
    }

    private void seedPromptStream() {
        accept(order("ORD-D-01", PROMPT, 3, "Первова", "Анна", "Демовна", "+79000000001", "anna.pervova@example.test"));
        accept(order("ORD-D-02", PROMPT, 3, "Второв", "Борис", null, "+79000000002", "BORIS.VTOROV@EXAMPLE.TEST"));
        accept(order("ORD-D-06", PROMPT, 3, "Пятакова", "Дарья", "Демовна", "+79000000006", "daria.p@example.test"));
        accept(order("ORD-D-07", PROMPT, 3, "Шестаков", "Егор", "Демович", "+79000000007", "egor.sh@example.test"));
        accept(order("ORD-D-08", PROMPT, 3, "Шестакова", "Ева", "Демовна", "+79000000008", "egor.sh@example.test"));
    }

    private MockMultipartFile template() throws IOException {
        if (templateBytes == null) {
            templateBytes = workbook(templateRows()).getBytes();
        }
        return new MockMultipartFile("file", "learners-filled.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", templateBytes);
    }

    private static List<Map<LearnerField, Object>> templateRows() {
        return List.of(
                row("Второв", "Борис", "7 (900) 000-00-02", BORIS_EMAIL, "123-456-789 64", null, 412d, "739251", "ГУ МВД Демо",
                        "20.08.2010", "770-002", "м", "12.07.1990"),
                row("Пятакова", "Дарья", null, "daria.p@example.test", "987-654-321 84", null),
                row("Первова", "Анна", null, "anna.pervova@example.test", null, "Высшее образование - бакалавриат"),
                row("Неизвестный", "Иван", "7 900 000-00-99", "unknown@example.test", null, null),
                row("Пятакова", "Дарина", null, "daria.p@example.test", null, null),
                row("Шестакова", "Ева", null, "egor.sh@example.test", null, "Среднее профессиональное образование")
        );
    }

    private static Map<LearnerField, Object> row(String lastName, String firstName, String phone, String email, String snils,
                                                 String education, Object... documents) {
        Map<LearnerField, Object> values = new java.util.EnumMap<>(LearnerField.class);
        values.put(LearnerField.LAST_NAME, lastName);
        values.put(LearnerField.FIRST_NAME, firstName);
        put(values, LearnerField.PHONE, phone);
        put(values, LearnerField.EMAIL, email);
        put(values, LearnerField.SNILS, snils);
        put(values, LearnerField.EDUCATION, education);
        List<LearnerField> fields = List.of(LearnerField.PASSPORT_SERIES, LearnerField.PASSPORT_NUMBER, LearnerField.PASSPORT_ISSUED_BY,
                LearnerField.PASSPORT_ISSUE_DATE, LearnerField.PASSPORT_DIVISION_CODE, LearnerField.GENDER, LearnerField.BIRTH_DATE);
        for (int index = 0; index < documents.length; index++) {
            values.put(fields.get(index), documents[index]);
        }
        return values;
    }

    private static void put(Map<LearnerField, Object> values, LearnerField field, Object value) {
        if (value != null) {
            values.put(field, value);
        }
    }

    private static MockMultipartFile workbook(List<Map<LearnerField, Object>> rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Лист1");
            Row header = sheet.createRow(0);
            for (LearnerField field : LearnerField.values()) {
                header.createCell(field.ordinal()).setCellValue(field == LearnerField.MIDDLE_NAME ? "Отчество (при наличии)" : field.header());
            }
            for (int index = 0; index < rows.size(); index++) {
                Row row = sheet.createRow(index + 1);
                rows.get(index).forEach((field, value) -> {
                    if (value instanceof Double number) {
                        row.createCell(field.ordinal()).setCellValue(number);
                    } else {
                        row.createCell(field.ordinal()).setCellValue((String) value);
                    }
                });
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return new MockMultipartFile("file", "learners-filled.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private static List<String> lastNames(LmsRoster roster) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(roster.content()))) {
            XSSFSheet sheet = workbook.getSheet("Лист1");
            return IntStream.rangeClosed(1, sheet.getLastRowNum())
                    .mapToObj(index -> sheet.getRow(index).getCell(LearnerField.LAST_NAME.ordinal()).getStringCellValue())
                    .toList();
        }
    }

    private LearnerCardView update(Learner learner, Map<String, String> fields, String key) {
        return learners.update(OPERATOR, learner.id().toString(), new LearnerUpdate(learner.version(), fields), key, "rq-" + key);
    }

    private LearnerIntake accept(PaidOrder order) {
        List<UUID> existing = jdbcTemplate.queryForList("SELECT id FROM source_records WHERE external_id = ?", UUID.class,
                order.orderNumber());
        UUID recordId = existing.isEmpty() ? UUID.randomUUID() : existing.getFirst();
        if (existing.isEmpty()) {
            jdbcTemplate.update("INSERT INTO source_records (id, external_id) VALUES (?, ?)", recordId, order.orderNumber());
        }
        return paidOrderEnrolment.accept(order, courseKey(order.course()), recordId, OPERATOR_ID);
    }

    private static PaidOrder order(String number, String course, int stream, String lastName, String firstName, String middleName,
                                   String phone, String email) {
        return new PaidOrder(number, course, stream, lastName, firstName, middleName, phone, email, "v");
    }

    private static String courseKey(String course) {
        return "name:" + course.toLowerCase(Locale.ROOT);
    }

    private Learner learner(String lastName, String firstName) {
        return learnerService.findByLastName(lastName).stream()
                .filter(learner -> firstName.equals(learner.profile().firstName()))
                .findFirst()
                .orElseThrow();
    }

    private UUID streamId(String course, int number) {
        return jdbcTemplate.queryForObject("SELECT id FROM enrolment_streams WHERE course_key = ? AND stream_no = ?", UUID.class,
                courseKey(course), number);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private List<String> journal(String action) {
        return jdbcTemplate.queryForList("""
                SELECT object_type || '|' || object_name || '|' || COALESCE(details, '') || '|' || actor_display_name
                FROM audit_events WHERE action = ? ORDER BY occurred_at, id
                """, String.class, action);
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

    private static LocalDate TODAY() {
        return LocalDate.now(EnrolmentLearnerService.ZONE);
    }

    private void profile(UUID id, String name, String role, boolean operator) {
        jdbcTemplate.update("INSERT INTO crm_user_profiles (id, display_name, role, active, enrolment_operator) VALUES (?, ?, ?, TRUE, ?)",
                id, name, role, operator);
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private void createSchema() {
        List<String> statements = new ArrayList<>(List.of(
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (partner_organization_id UUID, partner_contact_id UUID, 
                    id UUID PRIMARY KEY, display_name VARCHAR(200) NOT NULL, role VARCHAR(16) NOT NULL,
                    active BOOLEAN NOT NULL, enrolment_operator BOOLEAN NOT NULL DEFAULT FALSE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS source_records (
                    id UUID PRIMARY KEY, external_id VARCHAR(200), program_id UUID, status VARCHAR(16)
                )
                """,
                "CREATE TABLE IF NOT EXISTS programs (id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL)",
                """
                CREATE TABLE IF NOT EXISTS source_mappings (
                    id UUID PRIMARY KEY, source VARCHAR(16) NOT NULL, kind VARCHAR(16) NOT NULL, external_key VARCHAR(310) NOT NULL,
                    program_id UUID
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
                CREATE TABLE IF NOT EXISTS command_idempotency_records (
                    id UUID PRIMARY KEY, actor_profile_id UUID NOT NULL, operation VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL, request_fingerprint CHAR(64) NOT NULL, result_json TEXT,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    CONSTRAINT enrolment_command_key UNIQUE (actor_profile_id, operation, idempotency_key)
                )
                """
        ));
        statements.add(LearnerStorageTest.LEARNER_SCHEMA_STREAMS);
        statements.add(LearnerStorageTest.LEARNER_SCHEMA_LEARNERS);
        statements.add("CREATE UNIQUE INDEX IF NOT EXISTS learners_snils_key ON learners (snils_hmac)");
        statements.add(LearnerStorageTest.LEARNER_SCHEMA_ENROLMENTS);
        statements.forEach(jdbcTemplate::execute);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        EnrolmentProperties enrolmentProperties() {
            return new EnrolmentProperties(true, "v1", Map.of("v1", KEY), FINGERPRINT_KEY);
        }

        @Bean
        LearnerDataCipher learnerDataCipher(EnrolmentProperties properties) {
            return new LearnerDataCipher(properties);
        }

        @Bean
        RetentionProperties retentionProperties() {
            return new RetentionProperties("-", Duration.ofDays(7), Duration.ofDays(1095), Duration.ofDays(365), Duration.ofDays(1095),
                    Period.ofYears(3));
        }

        @Bean
        AttachmentContentValidator attachmentContentValidator() {
            return new AttachmentContentValidator(new AttachmentProperties(
                    Path.of("target", "enrolment-services-test-files"), "localhost", 3310,
                    Duration.ofSeconds(1), Duration.ofSeconds(1), DataSize.ofMegabytes(20)
            ));
        }

        @Bean
        AttachmentScanner attachmentScanner() {
            return (input, sizeBytes) -> SCAN.get();
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
