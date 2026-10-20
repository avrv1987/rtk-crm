package ru.rtk.crm.enrolment;

import static java.nio.charset.StandardCharsets.UTF_8;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.rtk.crm.source.MoodleWriteTarget;
import ru.rtk.crm.source.SourceSettingsService;
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
import ru.rtk.crm.module.ModuleDisabledException;
import ru.rtk.crm.module.ModuleProperties;
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
@Import({ru.rtk.crm.settings.TestSystemSettings.class, 
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
        EnrolmentBatchRepository.class,
        EnrolmentBatchService.class,
        MoodleTransferService.class,
        MoodleWriteClient.class,
        LearnerWorkbookReader.class,
        LmsRosterWorkbookWriter.class,
        LmsLogin.class,
        EnrolmentServicesTest.Configuration.class
})
@ExtendWith(OutputCaptureExtension.class)
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
    private ru.rtk.crm.settings.SystemSettings systemSettings;

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
    private EnrolmentBatchService batches;

    @Autowired
    private EnrolmentBatchRepository batchRepository;

    @Autowired
    private MoodleTransferService moodle;

    @MockitoBean
    private SourceSettingsService sourceSettings;

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
        for (String table : List.of("enrolment_batch_items", "enrolment_batches", "learner_enrolments", "learners", "enrolment_streams", "source_records", "audit_events",
                "command_idempotency_records", "source_mappings", "organizations", "programs", "crm_user_profiles")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        SCAN.set(AttachmentScanOutcome.CLEAN);
        profile(OPERATOR_ID, "Менеджер учебного процесса", "USER", true);
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

        LocalDate today = LocalDate.now(EnrolmentLearnerService.ZONE);
        LocalDate past = today.minusYears(3).minusDays(1);
        String stream = prompt.id().toString();
        assertThatThrownBy(() -> streams.update(OPERATOR, stream, new EnrolmentStreamUpdate(0, LocalDate.of(1900, 1, 1), true),
                "typo-year", "rq"))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("endsOn"));
        assertThatThrownBy(() -> streams.update(OPERATOR, stream, new EnrolmentStreamUpdate(0, today.plusYears(5).plusDays(1), null),
                "far-future", "rq"))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("endsOn"));
        assertThatThrownBy(() -> streams.update(OPERATOR, stream, new EnrolmentStreamUpdate(0, past, null), "unconfirmed", "rq"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
                    assertThat(exception.field()).isEqualTo("confirmPastDate");
                    assertThat(exception.getMessage()).contains("обезличит анкеты");
                });
        assertThat(streams.update(OPERATOR, stream, new EnrolmentStreamUpdate(0, today.plusYears(5), null), "future", "rq").endsOn())
                .isEqualTo(today.plusYears(5));

        EnrolmentStreamView dated = streams.update(OPERATOR, stream, new EnrolmentStreamUpdate(1, past, true), "end-date", "rq-end");

        assertThat(dated.endsOn()).isEqualTo(past);
        assertThat(dated.keepUntil()).isEqualTo(past.plusYears(3));
        assertThat(dated.version()).isEqualTo(2);
        assertThat(streams.update(OPERATOR, stream, new EnrolmentStreamUpdate(1, past, true), "end-date", "rq-end")).isEqualTo(dated);
        assertThatThrownBy(() -> streams.update(OPERATOR, prompt.id().toString(), new EnrolmentStreamUpdate(0, null, null), "stale", "rq"))
                .isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
                    assertThat(exception.currentVersion()).isEqualTo(2);
                });
        java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
        assertThat(journal("STREAM_END_DATE_CHANGED")).containsExactlyInAnyOrder(
                "STREAM|" + PROMPT + ", поток 3|дата окончания: не указана → " + format.format(today.plusYears(5)) + "|Менеджер учебного процесса",
                "STREAM|" + PROMPT + ", поток 3|дата окончания: " + format.format(today.plusYears(5)) + " → " + format.format(past)
                        + "|Менеджер учебного процесса");
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
        assertThat(journal("LEARNER_LIST_VIEWED")).containsExactly("STREAM|" + PROMPT + ", поток 3|строк: 6|Менеджер учебного процесса");
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
        assertThat(journal("LEARNER_SEARCHED")).contains("LEARNER|Поиск слушателя|вид: EMAIL; найдено: 1|Менеджер учебного процесса");

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

        assertThatThrownBy(() -> learners.move(OPERATOR, source.id().toString(), new LearnerMove("123", source.version(), null), "bad", "rq"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field()).isEqualTo("snils"));
        assertThatThrownBy(() -> learners.move(OPERATOR, source.id().toString(), new LearnerMove("123-456-789 64", source.version(), null),
                "nobody", "rq"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.getMessage()).isEqualTo("Слушателя с таким СНИЛС нет"));

        assertThatThrownBy(() -> learners.move(OPERATOR, source.id().toString(),
                new LearnerMove("112 233 445 95", source.version(), null), "move-unconfirmed", "rq"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
                    assertThat(exception.field()).isEqualTo("confirmNameMismatch");
                    assertThat(exception.getMessage()).startsWith("ФИО в анкетах различаются");
                });
        assertThat(learnerService.find(source.id())).isPresent();

        LearnerMoveResult result = learners.move(OPERATOR, source.id().toString(),
                new LearnerMove("112 233 445 95", source.version(), true), "move", "rq-move");

        assertThat(result).isEqualTo(new LearnerMoveResult(owner.id(), 1, 1, List.of(LearnerField.APARTMENT)));
        assertThat(learners.move(OPERATOR, source.id().toString(), new LearnerMove("112 233 445 95", source.version(), true), "move", "rq"))
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
                + "|из анкеты " + source.id() + ": зачислений перенесено 1, удалено повторных 1; поля: APARTMENT|Менеджер учебного процесса");
        assertThat(learners.history(OPERATOR, owner.id().toString())).extracting(LearnerHistoryEntry::action)
                .contains("LEARNER_ENROLMENTS_MOVED", "LEARNER_CHANGED");
    }

    @Test
    void nameCheckBeforeMoveIgnoresCaseYoSpacesAndAMissingMiddleName() {
        assertThat(EnrolmentLearnerService.sameName(name("Ёлкина", "Анна", null), name(" елкина ", "АННА", "Петровна"))).isTrue();
        assertThat(EnrolmentLearnerService.sameName(name("Ёлкина", "Анна", "Ивановна"), name("Елкина", "Анна", "Петровна"))).isFalse();
        assertThat(EnrolmentLearnerService.sameName(name("Иванова", "Анна", null), name("Петрова", "Анна", null))).isFalse();
    }

    private static LearnerProfile name(String lastName, String firstName, String middleName) {
        Map<LearnerField, String> values = new java.util.EnumMap<>(LearnerField.class);
        values.put(LearnerField.LAST_NAME, lastName);
        values.put(LearnerField.FIRST_NAME, firstName);
        if (middleName != null) {
            values.put(LearnerField.MIDDLE_NAME, middleName);
        }
        return LearnerProfile.fromStored(values);
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
                + ", поток 3|строк: 6; обновить: 2, без изменений: 1, ошибок: 2, конфликтов: 1|Менеджер учебного процесса");

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
        // Н-4 (2.5): повтор «Применить» того же предпросмотра под новым ключом — «уже применено», а не конфликт.
        QuestionnaireImport repeated = questionnaires.apply(OPERATOR, stream, template(), preview.fingerprint(), "apply-again", "rq");
        assertThat(repeated.alreadyApplied()).isTrue();
        assertThat(repeated.updated()).isEqualTo(2);
        assertThat(journal("LEARNER_TEMPLATE_IMPORTED")).containsExactly("STREAM|" + PROMPT
                + ", поток 3|строк: 6; применено: 2, без изменений: 1, пропущено с ошибками и конфликтами: 3|Менеджер учебного процесса");

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

        LmsRoster excluded = rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.EXCLUDE, null), "rq-x");

        assertThat(excluded.fileName()).isEqualTo("LMS_" + PROMPT + "_поток3_" + LocalDate.now(EnrolmentLearnerService.ZONE) + ".xlsx");
        assertThat(lastNames(excluded)).containsExactly("Второв", "Первова", "Шестаков", "Шестакова");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learner_enrolments WHERE lms_export_id = ?", Long.class,
                excluded.exportId())).isEqualTo(4);
        assertThat(journal("LMS_ROSTER_EXPORTED")).containsExactly("STREAM|" + PROMPT + ", поток 3|строк: 4; выгрузка: "
                + excluded.exportId() + "; режим: только не переданные; анкеты без обязательных полей: исключены; формат: XLSX по шаблону; SHA-256: "
                + excluded.checksum() + "|Менеджер учебного процесса");

        RosterExportMarked marked = rosters.markTransferred(OPERATOR, excluded.exportId().toString(), "mark-1", "rq-m1");
        RosterExportMarked again = rosters.markTransferred(OPERATOR, excluded.exportId().toString(), "mark-2", "rq-m2");

        assertThat(marked).isEqualTo(new RosterExportMarked(excluded.exportId(), streamId(PROMPT, 3), 4, 4));
        assertThat(again.marked()).isZero();
        assertThat(rosters.markTransferred(OPERATOR, excluded.exportId().toString(), "mark-1", "rq")).isEqualTo(marked);
        assertThat(journal("LMS_ROSTER_MARKED")).hasSize(1);
        assertThatThrownBy(() -> rosters.markTransferred(OPERATOR, UUID.randomUUID().toString(), "mark-3", "rq"))
                .isInstanceOf(EnrolmentNotFoundException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learner_enrolments WHERE lms_transferred_at IS NOT NULL",
                Long.class)).isZero();
        acceptAll(excluded.exportId(), "accept-excluded");

        LmsRoster withEmptyCells = rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null),
                "rq-y");
        LmsRoster all = rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.ALL, IncompleteProfiles.INCLUDE, null), "rq-z");

        assertThat(lastNames(withEmptyCells)).containsExactly("Восьмова");
        assertThat(lastNames(all)).containsExactly("Восьмова", "Второв", "Первова", "Шестаков", "Шестакова");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learner_enrolments WHERE lms_export_id = ?", Long.class,
                excluded.exportId())).isEqualTo(4);
        rosters.markTransferred(OPERATOR, all.exportId().toString(), "mark-all", "rq");
        acceptAll(all.exportId(), "accept-all");
        assertThatThrownBy(() -> rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null),
                "rq"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.getMessage())
                        .isEqualTo("Анкеты этих слушателей не выгружаются: обработка ограничена или анкета обезличена"));
        EnrolmentStreamLearners view = streams.learners(OPERATOR, stream, "rq");
        assertThat(view.exports()).extracting(RosterExport::rows, RosterExport::transferred)
                .containsExactlyInAnyOrder(tuple(4, 4), tuple(1, 0), tuple(5, 5));
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
                () -> streams.update(KAM, stream, new EnrolmentStreamUpdate(0, null, null), "k", "rq"),
                () -> learners.search(KAM, new LearnerSearch(LearnerSearchKind.LAST_NAME, "Второв"), "rq"),
                () -> learners.card(KAM, boris.id().toString(), "rq"),
                () -> learners.reveal(KAM, boris.id().toString(), new LearnerReveal(Set.of(LearnerFieldGroup.DOCUMENTS)), "rq"),
                () -> learners.update(KAM, boris.id().toString(), new LearnerUpdate(0, Map.of("APARTMENT", "1")), "k", "rq"),
                () -> learners.move(KAM, boris.id().toString(), new LearnerMove("11223344595", 0, null), "k", "rq"),
                () -> learners.history(KAM, boris.id().toString()),
                () -> rosters.export(KAM, stream, new LmsRosterRequest(RosterMode.ALL, IncompleteProfiles.INCLUDE, null), "rq"),
                () -> rosters.markTransferred(KAM, UUID.randomUUID().toString(), "k", "rq"),
                () -> batches.batch(KAM, UUID.randomUUID().toString(), "rq"),
                () -> batches.queue(KAM, "rq"),
                () -> batches.record(KAM, UUID.randomUUID().toString(), new BatchResultUpdate(List.of()), "k", "rq"),
                () -> batches.reconcile(KAM, UUID.randomUUID().toString(), new LmsReconcile(EnrolmentQueueKind.MOVED_AFTER_TRANSFER, null),
                        "k", "rq")
        );
        for (Runnable call : calls) {
            assertThatThrownBy(call::run).isInstanceOf(EnrolmentAccessDeniedException.class);
        }
        assertThatThrownBy(() -> learners.card(ADMIN, boris.id().toString(), "rq")).isInstanceOf(EnrolmentAccessDeniedException.class);
        assertThatThrownBy(() -> questionnaires.preview(ADMIN, stream, template(), "rq")).isInstanceOf(EnrolmentAccessDeniedException.class);
        EnrolmentAccess moduleOff = new EnrolmentAccess(new ModuleProperties(new ModuleProperties.Toggle(false), null), userProfileRepository);
        EnrolmentStreamService offStreams = new EnrolmentStreamService(moduleOff, enrolmentRepository, learnerService,
                learnerPrivacyService, retentionProperties, auditJournalRepository, commands, batchRepository, systemSettings);
        assertThatThrownBy(() -> offStreams.streams(OPERATOR)).isInstanceOf(ModuleDisabledException.class);
        assertThatThrownBy(() -> offStreams.learners(OPERATOR, "not-an-id", "rq")).isInstanceOf(ModuleDisabledException.class);
        assertThat(journal("LEARNER_VIEWED")).isEmpty();

        learners.reveal(OPERATOR, boris.id().toString(), new LearnerReveal(Set.of(LearnerFieldGroup.MAIN)), "rq-reveal");
        update(boris, Map.of("SNILS", "123-456-789 64"), "snils");
        questionnaires.preview(OPERATOR, stream, template(), "rq-preview");
        rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.ALL, IncompleteProfiles.INCLUDE, null), "rq-export");

        String database = databaseDump();
        for (String value : List.of("Второв", "Борис", "Первова", "Шестакова", "boris.vtorov", "egor.sh", "9000000002", "12345678964",
                "123-456-789 64")) {
            assertThat(database).doesNotContain(value);
        }
    }

    // E-04 и ER-01/ER-02: повторная партия до подтверждения не стирает прежнюю; отметка партии не принимает строки.
    @Test
    void repeatedBatchBeforeConfirmationKeepsTheFirstBatchReproducible() {
        seedPromptStream();
        String stream = streamId(PROMPT, 3).toString();
        LmsRoster first = rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq-a");
        LmsRoster second = rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq-b");

        EnrolmentBatchView batchA = batches.batch(OPERATOR, first.exportId().toString(), "rq");
        EnrolmentBatchView batchB = batches.batch(OPERATOR, second.exportId().toString(), "rq");
        assertThat(batchA.rows()).isEqualTo(5);
        assertThat(batchB.rows()).isEqualTo(5);
        assertThat(batchA.items()).extracting(EnrolmentBatchItemView::enrolmentId)
                .containsExactlyElementsOf(batchB.items().stream().map(EnrolmentBatchItemView::enrolmentId).toList());
        assertThat(batchA.items()).extracting(EnrolmentBatchItemView::rowNo).containsExactly(2, 3, 4, 5, 6);
        assertThat(batchA.checksum()).hasSize(64);

        assertThat(rosters.markTransferred(OPERATOR, first.exportId().toString(), "mark-a", "rq").marked()).isEqualTo(5);
        EnrolmentBatchItemView firstRow = batchA.items().getFirst();
        batches.record(OPERATOR, first.exportId().toString(), new BatchResultUpdate(List.of(
                new BatchResultItem(firstRow.id(), BatchItemResult.ACCEPTED, null))), "accept-a", "rq");

        EnrolmentBatchView afterA = batches.batch(OPERATOR, first.exportId().toString(), "rq");
        assertThat(afterA.sentAt()).isNotNull();
        assertThat(afterA.accepted()).isEqualTo(1);
        assertThat(afterA.pending()).isEqualTo(4);
        EnrolmentBatchView afterB = batches.batch(OPERATOR, second.exportId().toString(), "rq");
        assertThat(afterB.pending()).isEqualTo(5);
        assertThat(afterB.sentAt()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learner_enrolments WHERE lms_transferred_at IS NOT NULL", Long.class))
                .isEqualTo(1);
    }

    // E-03, E-11, E-12: из 100 строк приняты 95, ошибки видны по строкам; файл и отметка «передано» — не приём.
    @Test
    void protocolOfLmsAcceptsNinetyFiveRowsAndKeepsFiveErrorsForRepeat() {
        for (int index = 0; index < 100; index++) {
            accept(order("ORD-100-" + index, TESTER, 1, "Слушатель" + (char) ('А' + index % 30), "Номер" + index, null,
                    String.format("+7900100%04d", index), "learner" + index + "@example.test"));
        }
        String stream = streamId(TESTER, 1).toString();
        LmsRoster roster = rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq");

        assertThat(group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.NOT_SENT).total()).isEqualTo(100);
        assertThat(streams.learners(OPERATOR, stream, "rq").learners()).extracting(StreamLearner::lmsStatus).containsOnly(LmsStatus.EXPORTED);
        rosters.markTransferred(OPERATOR, roster.exportId().toString(), "mark", "rq");
        EnrolmentQueue sent = batches.queue(OPERATOR, "rq");
        assertThat(group(sent, EnrolmentQueueKind.AWAITING_RESULT).total()).isEqualTo(100);
        assertThat(group(sent, EnrolmentQueueKind.AWAITING_RESULT).items()).hasSize(EnrolmentBatchService.QUEUE_ITEMS);
        // Очередь листается: вторая страница группы продолжает первую, отбор по потоку и группе сохраняет счётчики.
        EnrolmentQueue second = batches.queue(OPERATOR, "rq", streamId(TESTER, 1), EnrolmentQueueKind.AWAITING_RESULT, 50, 50);
        assertThat(group(second, EnrolmentQueueKind.AWAITING_RESULT).items()).hasSize(50)
                .doesNotContainAnyElementsOf(group(sent, EnrolmentQueueKind.AWAITING_RESULT).items());
        assertThat(group(second, EnrolmentQueueKind.AWAITING_RESULT).total()).isEqualTo(100);
        assertThat(second.streams()).singleElement().satisfies(item -> assertThat(item.total()).isEqualTo(100));
        assertThat(group(batches.queue(OPERATOR, "rq", UUID.randomUUID(), null, 0, 50), EnrolmentQueueKind.AWAITING_RESULT).total()).isZero();
        assertThatThrownBy(() -> batches.queue(OPERATOR, "rq", null, null, 0, 0)).isInstanceOf(InteractionValidationException.class);
        assertThat(streams.learners(OPERATOR, stream, "rq").stream().transferred()).isZero();

        StringBuilder protocol = new StringBuilder("строка;результат;ошибка\n");
        for (int row = 2; row <= 101; row++) {
            protocol.append(row).append(row <= 96 ? ";принята;\n" : ";ошибка;\"Пользователь с такой почтой уже есть\"\n");
        }
        protocol.append("999;принята;\n");
        MockMultipartFile file = new MockMultipartFile("file", "protocol.csv", "text/csv", protocol.toString().getBytes(UTF_8));
        BatchResultsApplied applied = batches.importProtocol(OPERATOR, roster.exportId().toString(), file, "protocol-1", "rq");

        assertThat(applied.changed()).isEqualTo(100);
        assertThat(applied.batch().accepted()).isEqualTo(95);
        assertThat(applied.batch().failed()).isEqualTo(5);
        assertThat(applied.batch().pending()).isZero();
        assertThat(applied.skipped()).containsExactly("строка 999: такой строки в партии нет");
        BatchResultsApplied replayed = batches.importProtocol(OPERATOR, roster.exportId().toString(), file, "protocol-1", "rq");
        assertThat(replayed.changed()).isEqualTo(100);
        assertThat(replayed.batch().accepted()).isEqualTo(95);
        assertThat(batches.importProtocol(OPERATOR, roster.exportId().toString(), file, "protocol-2", "rq").changed()).isZero();

        EnrolmentQueue queue = batches.queue(OPERATOR, "rq");
        assertThat(group(queue, EnrolmentQueueKind.LMS_ERROR).total()).isEqualTo(5);
        assertThat(group(queue, EnrolmentQueueKind.LMS_ERROR).items().getFirst().detail()).isEqualTo("Пользователь с такой почтой уже есть");
        assertThat(group(queue, EnrolmentQueueKind.AWAITING_RESULT).total()).isZero();
        assertThat(streams.learners(OPERATOR, stream, "rq").stream().transferred()).isEqualTo(95);
        LmsRoster repeat = rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq");
        assertThat(repeat.rows()).isEqualTo(5);
        assertThat(journal("LMS_RESULTS_RECORDED")).hasSize(2);
        assertThat(journal("LMS_RESULTS_RECORDED").getFirst()).contains("принято: 95, ошибок: 5, ожидает: 0").doesNotContain("Слушатель");
    }

    // E-02, E-05, E-06, E-07: очередь исключений с причиной и сверка после передачи.
    @Test
    void queueShowsMissingAmbiguousMovedAndChangedAfterTransferUntilReconciled() {
        seedPromptStream();
        accept(order("ORD-7-NOEMAIL", PROMPT, 3, "Восьмова", "Лада", null, "+79000000044", null));
        Learner daria = learner("Пятакова", "Дарья");
        learnerRepository.updateStatus(daria.id(), daria.version(), PersonalDataStatus.RESTRICTED, OffsetDateTime.now());
        String stream = streamId(PROMPT, 3).toString();

        EnrolmentQueue before = batches.queue(OPERATOR, "rq-q");
        assertThat(names(before, EnrolmentQueueKind.MISSING_DATA)).containsExactly("Восьмова");
        assertThat(names(before, EnrolmentQueueKind.AMBIGUOUS_MATCH)).containsExactlyInAnyOrder("Шестаков", "Шестакова");
        assertThat(names(before, EnrolmentQueueKind.READY)).containsExactlyInAnyOrder("Первова", "Второв");
        assertThat(before.groups().stream().flatMap(group -> group.items().stream()).map(EnrolmentQueueItem::lastName))
                .doesNotContain("Пятакова");
        assertThat(count("learners")).isEqualTo(6);
        assertThat(journal("LEARNER_LIST_VIEWED")).anyMatch(entry -> entry.startsWith("ENROLMENT_QUEUE|"));

        LmsRoster roster = rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.EXCLUDE, null), "rq");
        assertThat(lastNamesOf(roster)).doesNotContain("Пятакова", "Восьмова");
        rosters.markTransferred(OPERATOR, roster.exportId().toString(), "mark", "rq");
        acceptAll(roster.exportId(), "accept");

        Learner pervova = learner("Первова", "Анна");
        update(pervova, Map.of("APARTMENT", "12"), "pervova-flat");
        EnrolmentQueueItem changed = group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.CHANGED_AFTER_TRANSFER).items().getFirst();
        assertThat(changed.lastName()).isEqualTo("Первова");
        assertThat(changed.detail()).contains("в LMS ушла версия");
        assertThatThrownBy(() -> batches.reconcile(OPERATOR, changed.enrolmentId().toString(),
                new LmsReconcile(EnrolmentQueueKind.CHANGED_AFTER_TRANSFER, changed.learnerVersion() - 1), "rec-old", "rq"))
                .isInstanceOf(InteractionConflictException.class);
        batches.reconcile(OPERATOR, changed.enrolmentId().toString(),
                new LmsReconcile(EnrolmentQueueKind.CHANGED_AFTER_TRANSFER, changed.learnerVersion()), "rec-changed", "rq");
        assertThat(group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.CHANGED_AFTER_TRANSFER).total()).isZero();

        accept(order("ORD-D-02", PROMPT, 4, "Второв", "Борис", null, "+79000000002", "BORIS.VTOROV@EXAMPLE.TEST"));
        EnrolmentQueueItem moved = group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.MOVED_AFTER_TRANSFER).items().getFirst();
        assertThat(moved.lastName()).isEqualTo("Второв");
        assertThat(moved.streamTitle()).isEqualTo(PROMPT + ", поток 4");
        assertThat(moved.detail()).contains("поток 3").contains("LMS");
        assertThat(batches.batch(OPERATOR, roster.exportId().toString(), "rq").items())
                .extracting(EnrolmentBatchItemView::lastName).contains("Второв");
        batches.reconcile(OPERATOR, moved.enrolmentId().toString(), new LmsReconcile(EnrolmentQueueKind.MOVED_AFTER_TRANSFER, null),
                "rec-moved", "rq");
        assertThat(group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.MOVED_AFTER_TRANSFER).total()).isZero();
        assertThat(journal("LMS_RECONCILED")).hasSize(2);
    }

    // E-08, E-10: второй менеджер учебного процесса не перезаписывает принятую строку; снятый флаг закрывает партии и очередь.
    @Test
    void secondOperatorCannotOverwriteAcceptedRowAndRevokedOperatorLosesAccess() {
        UUID secondId = UUID.fromString("82000000-0000-0000-0000-000000000004");
        CrmProfile second = new CrmProfile(secondId, UserRole.LEADER, null, 0);
        profile(secondId, "Второй оператор", "LEADER", true);
        seedPromptStream();
        String stream = streamId(PROMPT, 3).toString();
        LmsRoster roster = rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq");
        EnrolmentBatchItemView row = batches.batch(OPERATOR, roster.exportId().toString(), "rq").items().getFirst();

        batches.record(OPERATOR, roster.exportId().toString(), new BatchResultUpdate(List.of(
                new BatchResultItem(row.id(), BatchItemResult.ACCEPTED, null))), "first", "rq");
        BatchResultsApplied late = batches.record(second, roster.exportId().toString(), new BatchResultUpdate(List.of(
                new BatchResultItem(row.id(), BatchItemResult.FAILED, "Ошибка"))), "second", "rq");

        assertThat(late.changed()).isZero();
        assertThat(late.skipped()).containsExactly("строка " + row.rowNo() + ": уже принята LMS, не изменена");
        assertThat(late.batch().accepted()).isEqualTo(1);
        assertThatThrownBy(() -> batches.record(OPERATOR, roster.exportId().toString(), new BatchResultUpdate(List.of(
                new BatchResultItem(UUID.randomUUID(), BatchItemResult.ACCEPTED, null))), "foreign", "rq"))
                .isInstanceOf(InteractionValidationException.class);

        jdbcTemplate.update("UPDATE crm_user_profiles SET enrolment_operator = FALSE WHERE id = ?", secondId);
        assertThatThrownBy(() -> batches.batch(second, roster.exportId().toString(), "rq")).isInstanceOf(EnrolmentAccessDeniedException.class);
        assertThatThrownBy(() -> batches.queue(second, "rq")).isInstanceOf(EnrolmentAccessDeniedException.class);
        assertThatThrownBy(() -> rosters.export(second, stream, new LmsRosterRequest(RosterMode.ALL, IncompleteProfiles.INCLUDE, null), "rq"))
                .isInstanceOf(EnrolmentAccessDeniedException.class);
    }

    @Test
    void moodleCsvCarriesOnlyAccountFieldsAndChecksumOfTheFile() throws Exception {
        seedPromptStream();
        update(learner("Второв", "Борис"), Map.of("SNILS", "123-456-789 64"), "snils-csv");
        String stream = streamId(PROMPT, 3).toString();
        LmsRoster csv = rosters.export(OPERATOR, stream,
                new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, RosterFormat.MOODLE_CSV), "rq");

        String text = new String(csv.content(), UTF_8);
        assertThat(csv.fileName()).endsWith(".csv");
        assertThat(text.lines().toList().getFirst()).isEqualTo("username,firstname,lastname,middlename,email,phone1");
        assertThat(text).contains("boris.vtorov@example.test,Борис,Второв,,boris.vtorov@example.test,+79000000002");
        assertThat(text).doesNotContain("12345678964", "123-456-789");
        assertThat(csv.checksum()).isEqualTo(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(csv.content())));
        assertThat(batches.batch(OPERATOR, csv.exportId().toString(), "rq").format()).isEqualTo(RosterFormat.MOODLE_CSV);
        assertThat(MoodleCsvWriter.cell("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(MoodleCsvWriter.cell("Иванов, Иван")).isEqualTo("\"Иванов, Иван\"");
    }

    private void acceptAll(UUID batchId, String key) {
        List<BatchResultItem> items = batchRepository.findItems(batchId).stream()
                .map(item -> new BatchResultItem(item.id(), BatchItemResult.ACCEPTED, null))
                .toList();
        batches.record(OPERATOR, batchId.toString(), new BatchResultUpdate(items), key, "rq");
    }

    private static EnrolmentQueueGroup group(EnrolmentQueue queue, EnrolmentQueueKind kind) {
        return queue.groups().stream().filter(group -> group.kind() == kind).findFirst().orElseThrow();
    }

    private static List<String> names(EnrolmentQueue queue, EnrolmentQueueKind kind) {
        return group(queue, kind).items().stream().map(EnrolmentQueueItem::lastName).toList();
    }

    private static List<String> lastNamesOf(LmsRoster roster) {
        try {
            return lastNames(roster);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    // Этап передачи через API Moodle: E-03 по ответам Moodle, повтор только ошибок без второго пользователя, E-11 и
    // перенос E-06; в Moodle уходят только поля учётной записи, токен не попадает в журнал и вывод.
    @Test
    void moodleApiAcceptsRowsByResponsesRepeatsOnlyErrorsAndMovesBetweenCourses(CapturedOutput output) throws Exception {
        MoodleStub stub = MoodleStub.start();
        try {
            org.mockito.Mockito.when(sourceSettings.moodleWrite()).thenReturn(java.util.Optional.of(new MoodleWriteTarget(stub.url(),
                    "write-secret-token-0001", 5, Duration.ofSeconds(2), Duration.ofSeconds(5), DataSize.ofMegabytes(1))));
            UUID openEnrollment = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO organizations (id, type) VALUES (?, 'OPEN_ENROLLMENT')", openEnrollment);
            mapCourse(PROMPT, openEnrollment, "42", "42:7");
            mapCourse(TESTER, openEnrollment, "43");
            accept(order("ORD-M-1", PROMPT, 3, "Первова", "Анна", null, "+79000000101", "anna.m@example.test"));
            accept(order("ORD-M-2", PROMPT, 3, "Второв", "Борис", null, "+79000000102", "boris.m@example.test"));
            accept(order("ORD-M-3", PROMPT, 3, "Шестаков", "Егор", null, "+79000000103", "egor.m@example.test"));
            update(learner("Второв", "Борис"), Map.of("SNILS", "123-456-789 64"), "snils-moodle");
            stub.existing("egor.m@example.test", 900L, "Егор", "Шестаков");
            stub.rejectCreate("boris.m@example.test");
            String stream = streamId(PROMPT, 3).toString();
            LmsRoster roster = rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq");
            assertThat(batches.batch(OPERATOR, roster.exportId().toString(), "rq").moodleTransferAvailable()).isTrue();

            MoodleTransferResult first = moodle.transfer(OPERATOR, roster.exportId().toString(), "moodle-1", "rq");

            assertThat(first.sent()).isEqualTo(3);
            assertThat(first.accepted()).isEqualTo(2);
            assertThat(first.failed()).isEqualTo(1);
            assertThat(first.batch().items()).filteredOn(item -> item.result() == BatchItemResult.FAILED)
                    .singleElement().satisfies(item -> assertThat(item.error()).contains("уже есть", "(invalidparameter)")
                            .doesNotContain("Username already exists"));
            assertThat(stub.calls("core_user_create_users")).isEqualTo(2);
            assertThat(stub.calls("enrol_manual_enrol_users")).isEqualTo(2);
            assertThat(stub.calls("core_group_add_group_members")).isEqualTo(2);
            assertThat(stub.calls("core_enrol_get_enrolled_users")).isEqualTo(1);
            assertThat(stub.bodies()).noneMatch(body -> body.contains("12345678964") || body.contains("snils") || body.contains("passport"));
            assertThat(stub.bodies()).allMatch(body -> body.contains("wstoken=write-secret-token-0001"));

            stub.allowCreate("boris.m@example.test");
            MoodleTransferResult repeat = moodle.transfer(OPERATOR, roster.exportId().toString(), "moodle-2", "rq");
            assertThat(repeat.sent()).isEqualTo(1);
            assertThat(repeat.batch().accepted()).isEqualTo(3);
            assertThat(stub.calls("core_user_create_users")).isEqualTo(3);
            assertThat(moodle.transfer(OPERATOR, roster.exportId().toString(), "moodle-2", "rq").sent()).isEqualTo(1);

            accept(order("ORD-M-1", TESTER, 1, "Первова", "Анна", null, "+79000000101", "anna.m@example.test"));
            EnrolmentQueueItem moved = group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.MOVED_AFTER_TRANSFER).items().getFirst();
            MoodleMoveResult move = moodle.move(OPERATOR, moved.enrolmentId().toString(), "move-1", "rq");
            assertThat(move.courseFrom()).isEqualTo(42L);
            assertThat(move.courseTo()).isEqualTo(43L);
            assertThat(stub.calls("enrol_manual_unenrol_users")).isEqualTo(1);
            assertThat(group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.MOVED_AFTER_TRANSFER).total()).isZero();

            LmsRoster another = rosters.export(OPERATOR, streamId(TESTER, 1).toString(),
                    new LmsRosterRequest(RosterMode.ALL, IncompleteProfiles.INCLUDE, null), "rq");
            stub.stop();
            MoodleTransferResult offline = moodle.transfer(OPERATOR, another.exportId().toString(), "moodle-3", "rq");
            assertThat(offline.stoppedReason()).contains("Moodle недоступен");
            assertThat(offline.batch().pending()).isEqualTo(1);
            assertThat(offline.batch().accepted()).isZero();

            assertThat(journal("LMS_API_TRANSFER")).hasSize(3).allMatch(entry -> !entry.contains("@example.test") && !entry.contains("Борис"));
            assertThat(databaseDump()).doesNotContain("write-secret-token-0001");
            assertThat(output.getAll()).doesNotContain("write-secret-token-0001");

            org.mockito.Mockito.when(sourceSettings.moodleWrite()).thenReturn(java.util.Optional.empty());
            assertThat(batches.batch(OPERATOR, roster.exportId().toString(), "rq").moodleTransferAvailable()).isFalse();
            assertThatThrownBy(() -> moodle.transfer(OPERATOR, roster.exportId().toString(), "moodle-off", "rq"))
                    .isInstanceOf(InteractionValidationException.class);
            assertThatThrownBy(() -> moodle.transfer(KAM, roster.exportId().toString(), "moodle-kam", "rq"))
                    .isInstanceOf(EnrolmentAccessDeniedException.class);
        } finally {
            stub.stop();
        }
    }

    // Н-1 (2.5): одна почта у двух слушателей партии — обе строки в ошибку, в Moodle не уходят. Учётная запись Moodle
    // на другое имя не переименовывается и не зачисляется; совпадение имени без учёта регистра и «ё» — та же запись.
    @Test
    void moodleApiRejectsSharedEmailAndDoesNotTakeOverAccountWithOtherName() throws Exception {
        MoodleStub stub = MoodleStub.start();
        try {
            org.mockito.Mockito.when(sourceSettings.moodleWrite()).thenReturn(java.util.Optional.of(new MoodleWriteTarget(stub.url(),
                    "write-secret-token-0002", 5, Duration.ofSeconds(2), Duration.ofSeconds(5), DataSize.ofMegabytes(1))));
            UUID openEnrollment = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO organizations (id, type) VALUES (?, 'OPEN_ENROLLMENT')", openEnrollment);
            mapCourse(PROMPT, openEnrollment, "42");
            accept(order("ORD-S-1", PROMPT, 4, "Ребёнков", "Первый", null, "+79000000301", "parent.s@example.test"));
            accept(order("ORD-S-2", PROMPT, 4, "Ребёнкова", "Вторая", null, "+79000000302", "Parent.S@example.test"));
            accept(order("ORD-S-3", PROMPT, 4, "Чужов", "Пётр", null, "+79000000303", "other.s@example.test"));
            accept(order("ORD-S-4", PROMPT, 4, "Свой", "Семён", null, "+79000000304", "own.s@example.test"));
            stub.existing("other.s@example.test", 901L, "Иван", "Другой");
            stub.existing("own.s@example.test", 902L, "СЕМЕН", "Свой");
            LmsRoster roster = rosters.export(OPERATOR, streamId(PROMPT, 4).toString(),
                    new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq");

            MoodleTransferResult result = moodle.transfer(OPERATOR, roster.exportId().toString(), "moodle-shared", "rq");

            assertThat(result.sent()).isEqualTo(4);
            assertThat(result.accepted()).isEqualTo(1);
            assertThat(result.batch().items()).filteredOn(item -> item.result() == BatchItemResult.FAILED)
                    .extracting(EnrolmentBatchItemView::error)
                    .satisfiesExactlyInAnyOrder(
                            error -> assertThat(error).isEqualTo(MoodleTransferService.SHARED_EMAIL),
                            error -> assertThat(error).isEqualTo(MoodleTransferService.SHARED_EMAIL),
                            error -> assertThat(error).contains("на другое имя", "не переименовывает"));
            assertThat(stub.calls("core_user_create_users")).isZero();
            assertThat(stub.calls("core_user_update_users")).isZero();
            assertThat(stub.calls("enrol_manual_enrol_users")).isEqualTo(1);
            assertThat(stub.bodies()).noneMatch(body -> body.contains("parent.s"));
        } finally {
            stub.stop();
        }
    }

    // Н-А1 (2.5): настоящий Moodle с токеном записи отдаёт только fullname. Существующий пользователь с тем же именем
    // (в любом порядке слов, без учёта регистра и «ё») зачисляется без создания второго, чужое имя по-прежнему
    // отклоняется; повтор и перенос проходят. Слушатель без фамилии в Moodle не уходит (п. 14).
    @Test
    void moodleApiMatchesExistingUserByFullnameWhenMoodleReturnsNoNameParts() throws Exception {
        MoodleStub stub = MoodleStub.start();
        try {
            org.mockito.Mockito.when(sourceSettings.moodleWrite()).thenReturn(java.util.Optional.of(new MoodleWriteTarget(stub.url(),
                    "write-secret-token-0003", 5, Duration.ofSeconds(2), Duration.ofSeconds(5), DataSize.ofMegabytes(1))));
            stub.userFields(MoodleStub.UserFields.FULLNAME_ONLY);
            UUID openEnrollment = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO organizations (id, type) VALUES (?, 'OPEN_ENROLLMENT')", openEnrollment);
            mapCourse(PROMPT, openEnrollment, "42");
            mapCourse(TESTER, openEnrollment, "43");
            accept(order("ORD-F-1", PROMPT, 5, "Свой", "Семён", null, "+79000000401", "own.f@example.test"));
            accept(order("ORD-F-2", PROMPT, 5, "Ковалёва", "Мария", "Ивановна", "+79000000402", "maria.f@example.test"));
            accept(order("ORD-F-3", PROMPT, 5, "Чужов", "Пётр", null, "+79000000403", "other.f@example.test"));
            accept(order("ORD-F-4", PROMPT, 5, "Новикова", "Нина", null, "+79000000404", "new.f@example.test"));
            accept(order("ORD-F-5", PROMPT, 5, null, "Безфамильный", null, "+79000000405", "nolast.f@example.test"));
            stub.existing("own.f@example.test", 910L, "СЕМЕН", "Свой");
            // Сайт показывает «Фамилия Имя Отчество»: порядок слов другой, отчество есть.
            stub.existing("maria.f@example.test", 911L, "Ковалева Мария", "Ивановна");
            stub.existing("other.f@example.test", 912L, "Иван", "Другой");
            String stream = streamId(PROMPT, 5).toString();
            LmsRoster roster = rosters.export(OPERATOR, stream, new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq");

            MoodleTransferResult first = moodle.transfer(OPERATOR, roster.exportId().toString(), "moodle-fn-1", "rq");

            assertThat(first.sent()).isEqualTo(5);
            assertThat(first.accepted()).isEqualTo(3);
            assertThat(first.batch().items()).filteredOn(item -> item.result() == BatchItemResult.FAILED)
                    .extracting(EnrolmentBatchItemView::error)
                    .satisfiesExactlyInAnyOrder(
                            error -> assertThat(error).contains("на другое имя"),
                            error -> assertThat(error).isEqualTo(MoodleTransferService.NO_LAST_NAME));
            assertThat(first.batch().items()).filteredOn(item -> item.result() == BatchItemResult.ACCEPTED)
                    .allMatch(item -> item.error() == null);
            assertThat(stub.calls("core_user_create_users")).isEqualTo(1);
            assertThat(stub.calls("core_user_update_users")).isZero();
            assertThat(stub.bodies()).noneMatch(body -> body.contains("nolast.f") && body.contains("core_user_create_users"));

            MoodleTransferResult repeat = moodle.transfer(OPERATOR, roster.exportId().toString(), "moodle-fn-2", "rq");
            assertThat(repeat.sent()).isEqualTo(2);
            assertThat(repeat.batch().accepted()).isEqualTo(3);
            assertThat(stub.calls("core_user_create_users")).isEqualTo(1);

            accept(order("ORD-F-1", TESTER, 2, "Свой", "Семён", null, "+79000000401", "own.f@example.test"));
            EnrolmentQueueItem moved = group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.MOVED_AFTER_TRANSFER).items().getFirst();
            MoodleMoveResult move = moodle.move(OPERATOR, moved.enrolmentId().toString(), "move-fn-1", "rq");
            assertThat(move.courseTo()).isEqualTo(43L);

            // Токен, которому имена не видны совсем: совпадение по почте с пометкой в протоколе, без переименования.
            stub.userFields(MoodleStub.UserFields.NONE);
            accept(order("ORD-F-6", PROMPT, 6, "Тихонов", "Тимур", null, "+79000000406", "quiet.f@example.test"));
            stub.existing("quiet.f@example.test", 913L, "Кто", "Угодно");
            LmsRoster silent = rosters.export(OPERATOR, streamId(PROMPT, 6).toString(),
                    new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq");
            MoodleTransferResult noNames = moodle.transfer(OPERATOR, silent.exportId().toString(), "moodle-fn-3", "rq");
            assertThat(noNames.accepted()).isEqualTo(1);
            assertThat(noNames.batch().items()).singleElement()
                    .satisfies(item -> assertThat(item.error()).isEqualTo(MoodleTransferService.NAME_NOT_CHECKED));
            assertThat(stub.calls("core_user_create_users")).isEqualTo(1);
            assertThat(stub.calls("core_user_update_users")).isZero();
        } finally {
            stub.stop();
        }
    }

    // D06, Н01: перенос между курсами — сначала новый курс и группа со сверкой, потом снятие со старого. Сбой после
    // каждого шага (в том числе с потерянным ответом) оставляет слушателя хотя бы на одном курсе и описание состояния в
    // очереди; повтор с тем же ключом доводит перенос до конца.
    @Test
    void moodleMoveBetweenCoursesEnrolsNewFirstAndRetryFinishesAfterFailureAtEachStep() throws Exception {
        MoodleStub stub = MoodleStub.start();
        try {
            moodleTarget(stub);
            UUID openEnrollment = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO organizations (id, type) VALUES (?, 'OPEN_ENROLLMENT')", openEnrollment);
            mapCourse(PROMPT, openEnrollment, "42", "42:7");
            mapCourse(TESTER, openEnrollment, "43", "43:8");
            accept(order("ORD-T-1", PROMPT, 3, "Первова", "Анна", null, "+79000000501", "anna.t@example.test"));
            LmsRoster roster = rosters.export(OPERATOR, streamId(PROMPT, 3).toString(),
                    new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq");
            assertThat(moodle.transfer(OPERATOR, roster.exportId().toString(), "moodle-t", "rq").accepted()).isEqualTo(1);
            long user = stub.userId("anna.t@example.test");
            accept(order("ORD-T-1", TESTER, 1, "Первова", "Анна", null, "+79000000501", "anna.t@example.test"));
            UUID enrolment = group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.MOVED_AFTER_TRANSFER).items().getFirst().enrolmentId();

            stub.failOnce("enrol_manual_enrol_users", true);
            assertThatThrownBy(() -> moodle.move(OPERATOR, enrolment.toString(), "move-t", "rq"))
                    .isInstanceOf(InteractionValidationException.class);
            assertThat(stub.enrolled(42, user)).isTrue();
            assertThat(stub.calls("enrol_manual_unenrol_users")).isZero();
            assertThat(movedDetail()).contains(MoodleTransferService.NEW_NOT_CONFIRMED);

            for (String step : List.of("core_group_add_group_members", "core_enrol_get_enrolled_users", "core_group_get_group_members")) {
                stub.failOnce(step, false);
                assertThatThrownBy(() -> moodle.move(OPERATOR, enrolment.toString(), "move-t", "rq"))
                        .isInstanceOf(InteractionValidationException.class);
                assertThat(stub.enrolled(42, user)).as(step).isTrue();
                assertThat(stub.calls("enrol_manual_unenrol_users")).as(step).isZero();
            }

            stub.failOnce("enrol_manual_unenrol_users", false);
            assertThatThrownBy(() -> moodle.move(OPERATOR, enrolment.toString(), "move-t", "rq"))
                    .isInstanceOf(InteractionValidationException.class);
            assertThat(stub.enrolled(42, user)).isTrue();
            assertThat(stub.enrolled(43, user)).isTrue();
            assertThat(movedDetail()).contains("на двух курсах").containsOnlyOnce("не завершён");

            // Moodle снял, но ответ потерян: повтор видит, что на старом курсе слушателя нет, и не снимает повторно.
            stub.failOnce("enrol_manual_unenrol_users", true);
            assertThatThrownBy(() -> moodle.move(OPERATOR, enrolment.toString(), "move-t", "rq"))
                    .isInstanceOf(InteractionValidationException.class);
            assertThat(stub.enrolled(43, user)).isTrue();
            int unenrolCalls = stub.calls("enrol_manual_unenrol_users");

            MoodleMoveResult move = moodle.move(OPERATOR, enrolment.toString(), "move-t", "rq");

            assertThat(move.courseTo()).isEqualTo(43L);
            assertThat(stub.enrolled(43, user)).isTrue();
            assertThat(stub.enrolled(42, user)).isFalse();
            assertThat(stub.inGroup(8, user)).isTrue();
            assertThat(stub.calls("enrol_manual_unenrol_users")).isEqualTo(unenrolCalls);
            assertThat(group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.MOVED_AFTER_TRANSFER).total()).isZero();
            assertThat(moodle.move(OPERATOR, enrolment.toString(), "move-t", "rq")).isEqualTo(move);
            assertThat(journal("LMS_API_MOVE")).singleElement().satisfies(entry -> assertThat(entry).contains("42 → 43"));
        } finally {
            stub.stop();
        }
    }

    // D07, Н02: перенос между группами одного курса — слушатель добавляется в новую группу и убирается только из группы
    // прежнего потока; со курса не снимается. Сбой снятия из группы — состояние в очереди, повтор завершает.
    @Test
    void moodleMoveBetweenGroupsOfOneCourseRemovesOnlyThePreviousGroup() throws Exception {
        MoodleStub stub = MoodleStub.start();
        try {
            moodleTarget(stub);
            UUID openEnrollment = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO organizations (id, type) VALUES (?, 'OPEN_ENROLLMENT')", openEnrollment);
            mapCourse(PROMPT, openEnrollment, "42", "42:7");
            mapCourse(TESTER, openEnrollment, "42:8");
            accept(order("ORD-G-1", PROMPT, 3, "Первова", "Анна", null, "+79000000601", "anna.g@example.test"));
            LmsRoster roster = rosters.export(OPERATOR, streamId(PROMPT, 3).toString(),
                    new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq");
            moodle.transfer(OPERATOR, roster.exportId().toString(), "moodle-g", "rq");
            long user = stub.userId("anna.g@example.test");
            assertThat(stub.inGroup(7, user)).isTrue();
            accept(order("ORD-G-1", TESTER, 1, "Первова", "Анна", null, "+79000000601", "anna.g@example.test"));
            UUID enrolment = group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.MOVED_AFTER_TRANSFER).items().getFirst().enrolmentId();

            stub.failOnce("core_group_delete_group_members", false);
            assertThatThrownBy(() -> moodle.move(OPERATOR, enrolment.toString(), "move-g-1", "rq"))
                    .isInstanceOf(InteractionValidationException.class);
            assertThat(stub.inGroup(7, user)).isTrue();
            assertThat(stub.inGroup(8, user)).isTrue();
            assertThat(movedDetail()).contains(MoodleTransferService.TWO_GROUPS);

            MoodleMoveResult move = moodle.move(OPERATOR, enrolment.toString(), "move-g-2", "rq");

            assertThat(move.courseFrom()).isEqualTo(42L);
            assertThat(move.courseTo()).isEqualTo(42L);
            assertThat(stub.inGroup(8, user)).isTrue();
            assertThat(stub.inGroup(7, user)).isFalse();
            assertThat(stub.enrolled(42, user)).isTrue();
            assertThat(stub.calls("enrol_manual_unenrol_users")).isZero();
            assertThat(stub.bodies()).filteredOn(body -> body.contains("core_group_delete_group_members"))
                    .allMatch(body -> body.contains("members%5B0%5D%5Bgroupid%5D=7"));
            assertThat(group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.MOVED_AFTER_TRANSFER).total()).isZero();
            assertThat(journal("LMS_API_MOVE")).singleElement().satisfies(entry -> assertThat(entry).contains("группа 7 → 8"));
        } finally {
            stub.stop();
        }
    }

    // D05, Н04: зачисление прошло, но ответ потерян — строка в ошибку, повтор принимает её без второго пользователя.
    // Сбой сверки оставляет строки «ожидает», отметка «передача идёт» снимается; вторая передача той же партии, пока
    // идёт первая, отклоняется, а отметка, оставшаяся после сбоя процесса, через срок не мешает.
    @Test
    void moodleTransferKeepsPartialStateOutsideTransactionAndRepeatsSafely() throws Exception {
        MoodleStub stub = MoodleStub.start();
        try {
            moodleTarget(stub);
            UUID openEnrollment = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO organizations (id, type) VALUES (?, 'OPEN_ENROLLMENT')", openEnrollment);
            mapCourse(PROMPT, openEnrollment, "42");
            accept(order("ORD-P-1", PROMPT, 3, "Первова", "Анна", null, "+79000000701", "anna.p@example.test"));
            accept(order("ORD-P-2", PROMPT, 3, "Второв", "Борис", null, "+79000000702", "boris.p@example.test"));
            LmsRoster roster = rosters.export(OPERATOR, streamId(PROMPT, 3).toString(),
                    new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq");
            String batch = roster.exportId().toString();

            jdbcTemplate.update("UPDATE enrolment_batches SET moodle_transfer_started_at = ? WHERE id = ?", OffsetDateTime.now(),
                    roster.exportId());
            assertThatThrownBy(() -> moodle.transfer(OPERATOR, batch, "moodle-p-0", "rq")).isInstanceOf(InteractionConflictException.class);
            assertThat(stub.bodies()).isEmpty();
            jdbcTemplate.update("UPDATE enrolment_batches SET moodle_transfer_started_at = ? WHERE id = ?", OffsetDateTime.now()
                    .minus(MoodleTransferService.TRANSFER_CLAIM_TTL).minusMinutes(1), roster.exportId());

            stub.failOnce("core_enrol_get_enrolled_users", false);
            MoodleTransferResult unverified = moodle.transfer(OPERATOR, batch, "moodle-p-0", "rq");
            assertThat(unverified.stoppedReason()).isNotNull();
            assertThat(unverified.batch().pending()).isEqualTo(2);
            assertThat(claim(roster.exportId())).isNull();

            stub.failOnce("enrol_manual_enrol_users", true);
            MoodleTransferResult partial = moodle.transfer(OPERATOR, batch, "moodle-p-1", "rq");
            assertThat(partial.accepted()).isEqualTo(1);
            assertThat(partial.failed()).isEqualTo(1);

            MoodleTransferResult repeat = moodle.transfer(OPERATOR, batch, "moodle-p-2", "rq");

            assertThat(repeat.sent()).isEqualTo(1);
            assertThat(repeat.batch().accepted()).isEqualTo(2);
            assertThat(stub.calls("core_user_create_users")).isEqualTo(2);
            assertThat(claim(roster.exportId())).isNull();
        } finally {
            stub.stop();
        }
    }

    // D08, Н34: анкету поправили между партией и передачей — в Moodle уходит новое имя, и переданной записывается именно
    // эта версия; ложной строки «изменена после передачи» нет, а следующая правка её даёт.
    @Test
    void moodleTransferRecordsTheLearnerVersionThatWasActuallySent() throws Exception {
        MoodleStub stub = MoodleStub.start();
        try {
            moodleTarget(stub);
            UUID openEnrollment = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO organizations (id, type) VALUES (?, 'OPEN_ENROLLMENT')", openEnrollment);
            mapCourse(PROMPT, openEnrollment, "42");
            accept(order("ORD-V-1", PROMPT, 3, "Первова", "Анна", null, "+79000000801", "anna.v@example.test"));
            LmsRoster roster = rosters.export(OPERATOR, streamId(PROMPT, 3).toString(),
                    new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq");
            update(learner("Первова", "Анна"), Map.of("LAST_NAME", "Новикова"), "rename-v");
            Learner renamed = learner("Новикова", "Анна");

            assertThat(moodle.transfer(OPERATOR, roster.exportId().toString(), "moodle-v", "rq").accepted()).isEqualTo(1);

            assertThat(stub.bodies()).anyMatch(body -> body.contains("core_user_create_users")
                    && body.contains(java.net.URLEncoder.encode("Новикова", java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(jdbcTemplate.queryForObject("SELECT lms_synced_version FROM learner_enrolments WHERE learner_id = ?",
                    Integer.class, renamed.id())).isEqualTo(renamed.version());
            assertThat(group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.CHANGED_AFTER_TRANSFER).total()).isZero();

            update(renamed, Map.of("SNILS", "123-456-789 64"), "snils-v");
            assertThat(group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.CHANGED_AFTER_TRANSFER).items()).singleElement()
                    .satisfies(item -> assertThat(item.detail()).contains("ушла версия " + renamed.version()));
        } finally {
            stub.stop();
        }
    }

    // Решения владельца 21.10 и Н4 проверки 2.5.3. Курс потока: по программе, явное уточнение задают менеджер учебного
    // процесса или администратор, передача и перенос берут его. Строки прежнего учёта (LEGACY) сверка с Moodle
    // подтверждает без записи в Moodle, не найденные остаются с пометкой. Перенесённый слушатель не попадает в «Готово к
    // передаче» и не уходит партией нового потока, пока перенос не выполнен.
    @Test
    void explicitStreamCourseLegacyCheckAndPendingMoveInMoodleTransfer() throws Exception {
        MoodleStub stub = MoodleStub.start();
        try {
            moodleTarget(stub);
            UUID openEnrollment = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO organizations (id, type) VALUES (?, 'OPEN_ENROLLMENT')", openEnrollment);
            mapCourse(PROMPT, openEnrollment, "42", "42:7");
            mapCourse(TESTER, openEnrollment, "43", "43:8");
            accept(order("ORD-L-1", PROMPT, 3, "Первова", "Анна", null, "+79000000601", "anna.l@example.test"));
            accept(order("ORD-L-2", PROMPT, 3, "Второв", "Борис", null, "+79000000602", "boris.l@example.test"));
            UUID prompt = streamId(PROMPT, 3);
            assertThat(moodle.course(prompt)).isEqualTo(new MoodleTransferService.Course(42L, 7L));

            int version = streams.view(prompt).version();
            EnrolmentStreamView refined = streams.updateMoodleCourse(OPERATOR, prompt.toString(),
                    new StreamMoodleCourseUpdate(version, 44L, 9L), "mc-1", "rq");
            assertThat(refined.moodleCourseId()).isEqualTo(44L);
            assertThat(refined.moodleGroupId()).isEqualTo(9L);
            assertThat(moodle.course(prompt)).isEqualTo(new MoodleTransferService.Course(44L, 9L));
            assertThatThrownBy(() -> streams.updateMoodleCourse(KAM, prompt.toString(),
                    new StreamMoodleCourseUpdate(refined.version(), 45L, null), "mc-kam", "rq")).isInstanceOf(EnrolmentAccessDeniedException.class);
            assertThatThrownBy(() -> streams.updateMoodleCourse(OPERATOR, prompt.toString(),
                    new StreamMoodleCourseUpdate(refined.version(), null, 9L), "mc-bad", "rq"))
                    .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field()).isEqualTo("groupId"));
            assertThatThrownBy(() -> streams.updateMoodleCourse(OPERATOR, prompt.toString(),
                    new StreamMoodleCourseUpdate(version, 45L, null), "mc-old", "rq")).isInstanceOf(InteractionConflictException.class);
            EnrolmentStreamView byProgram = streams.updateMoodleCourse(ADMIN, prompt.toString(),
                    new StreamMoodleCourseUpdate(refined.version(), null, null), "mc-admin", "rq");
            assertThat(moodle.course(prompt)).isEqualTo(new MoodleTransferService.Course(42L, 7L));
            streams.updateMoodleCourse(OPERATOR, prompt.toString(), new StreamMoodleCourseUpdate(byProgram.version(), 44L, 9L), "mc-2", "rq");
            assertThat(journal("STREAM_MOODLE_COURSE_CHANGED")).hasSize(3)
                    .anySatisfy(entry -> assertThat(entry).contains("по программе → 44, группа 9"));

            LmsRoster roster = rosters.export(OPERATOR, prompt.toString(),
                    new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq");
            assertThat(moodle.transfer(OPERATOR, roster.exportId().toString(), "lt-1", "rq").accepted()).isEqualTo(2);
            long anna = stub.userId("anna.l@example.test");
            assertThat(stub.enrolled(44, anna)).isTrue();
            assertThat(stub.inGroup(9, anna)).isTrue();
            assertThat(stub.enrolled(42, anna)).isFalse();

            jdbcTemplate.update("UPDATE enrolment_batch_items SET result_source = 'LEGACY' WHERE batch_id = ?", roster.exportId());
            update(learner("Второв", "Борис"), Map.of("EMAIL", "boris.other@example.test"), "boris-email");
            int creates = stub.calls("core_user_create_users");
            int enrols = stub.calls("enrol_manual_enrol_users");
            assertThat(batches.batch(OPERATOR, roster.exportId().toString(), "rq").items())
                    .extracting(EnrolmentBatchItemView::resultSource).containsOnly("LEGACY");
            MoodleTransferResult verified = moodle.transfer(OPERATOR, roster.exportId().toString(), "lt-2", "rq");
            assertThat(verified.sent()).isZero();
            assertThat(verified.confirmed()).isEqualTo(1);
            assertThat(stub.calls("core_user_create_users")).isEqualTo(creates);
            assertThat(stub.calls("enrol_manual_enrol_users")).isEqualTo(enrols);
            assertThat(verified.batch().items()).filteredOn(item -> "Первова".equals(item.lastName())).singleElement()
                    .satisfies(item -> assertThat(item.resultSource()).isEqualTo("API"));
            assertThat(verified.batch().items()).filteredOn(item -> "Второв".equals(item.lastName())).singleElement()
                    .satisfies(item -> assertThat(item.resultSource()).isEqualTo("LEGACY"));
            assertThat(journal("LMS_API_TRANSFER")).last().satisfies(entry -> assertThat(entry).contains("сверено строк прежнего учёта: 1"));

            accept(order("ORD-L-1", TESTER, 1, "Первова", "Анна", null, "+79000000601", "anna.l@example.test"));
            EnrolmentQueue queue = batches.queue(OPERATOR, "rq");
            assertThat(names(queue, EnrolmentQueueKind.MOVED_AFTER_TRANSFER)).contains("Первова");
            assertThat(names(queue, EnrolmentQueueKind.READY)).doesNotContain("Первова");
            LmsRoster tester = rosters.export(OPERATOR, streamId(TESTER, 1).toString(),
                    new LmsRosterRequest(RosterMode.PENDING, IncompleteProfiles.INCLUDE, null), "rq");
            MoodleTransferResult blocked = moodle.transfer(OPERATOR, tester.exportId().toString(), "lt-3", "rq");
            assertThat(blocked.accepted()).isZero();
            assertThat(blocked.batch().items()).singleElement().satisfies(item -> {
                assertThat(item.result()).isEqualTo(BatchItemResult.FAILED);
                assertThat(item.error()).isEqualTo(MoodleTransferService.PENDING_MOVE);
            });
            assertThat(stub.enrolled(43, anna)).isFalse();

            UUID enrolment = group(queue, EnrolmentQueueKind.MOVED_AFTER_TRANSFER).items().getFirst().enrolmentId();
            MoodleMoveResult move = moodle.move(OPERATOR, enrolment.toString(), "lm-1", "rq");
            assertThat(move.courseFrom()).isEqualTo(44L);
            assertThat(move.courseTo()).isEqualTo(43L);
            assertThat(stub.enrolled(43, anna)).isTrue();
            assertThat(stub.enrolled(44, anna)).isFalse();
        } finally {
            stub.stop();
        }
    }

    private void moodleTarget(MoodleStub stub) {
        org.mockito.Mockito.when(sourceSettings.moodleWrite()).thenReturn(java.util.Optional.of(new MoodleWriteTarget(stub.url(),
                "write-secret-token-0004", 5, Duration.ofSeconds(2), Duration.ofSeconds(5), DataSize.ofMegabytes(1))));
    }

    private String movedDetail() {
        return group(batches.queue(OPERATOR, "rq"), EnrolmentQueueKind.MOVED_AFTER_TRANSFER).items().getFirst().detail();
    }

    private OffsetDateTime claim(UUID batchId) {
        return jdbcTemplate.queryForObject("SELECT moodle_transfer_started_at FROM enrolment_batches WHERE id = ?", OffsetDateTime.class,
                batchId);
    }

    // Курс сайта совпал с программой по названию: явного сопоставления сайта нет, программа потока — из оплат, как на
    // экране потока. Явное сопоставление сайта главнее программы оплат.
    @Test
    void moodleCourseOfStreamFollowsPaymentProgramWhenSiteCourseMatchedProgramByName() {
        UUID openEnrollment = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, type) VALUES (?, 'OPEN_ENROLLMENT')", openEnrollment);
        UUID byName = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO programs (id, name) VALUES (?, ?)", byName, PROMPT);
        jdbcTemplate.update("""
                INSERT INTO source_mappings (id, source, kind, external_key, program_id, organization_id) VALUES (?, 'MOODLE', 'COURSE', '44', ?, ?)
                """, UUID.randomUUID(), byName, openEnrollment);
        accept(order("ORD-N-1", PROMPT, 2, "Названова", "Нина", null, "+79000000201", "nina.n@example.test"));
        jdbcTemplate.update("UPDATE source_records SET program_id = ? WHERE external_id = 'ORD-N-1'", byName);
        UUID stream = streamId(PROMPT, 2);

        assertThat(moodle.course(stream)).isEqualTo(new MoodleTransferService.Course(44L, null));

        mapCourse(PROMPT, openEnrollment, "45");
        assertThat(moodle.course(stream)).isEqualTo(new MoodleTransferService.Course(45L, null));
    }

    private void mapCourse(String course, UUID organization, String... moodleKeys) {
        UUID program = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO programs (id, name) VALUES (?, ?)", program, "Программа " + course);
        jdbcTemplate.update("INSERT INTO source_mappings (id, source, kind, external_key, program_id) VALUES (?, 'WEBSITE', 'PROGRAM', ?, ?)",
                UUID.randomUUID(), courseKey(course), program);
        for (String key : moodleKeys) {
            jdbcTemplate.update("""
                    INSERT INTO source_mappings (id, source, kind, external_key, program_id, organization_id) VALUES (?, 'MOODLE', ?, ?, ?, ?)
                    """, UUID.randomUUID(), key.contains(":") ? "GROUP" : "COURSE", key, program, organization);
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
                CREATE TABLE IF NOT EXISTS crm_user_profiles (role_synced_at TIMESTAMP WITH TIME ZONE, last_login_at TIMESTAMP WITH TIME ZONE, role_reconcile_required BOOLEAN DEFAULT FALSE NOT NULL, partner_organization_id UUID, partner_contact_id UUID, 
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
                    program_id UUID, organization_id UUID, run_kind VARCHAR(16) DEFAULT 'STUDENTS' NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organizations (id UUID PRIMARY KEY, type VARCHAR(16) NOT NULL)
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
        statements.add(EnrolmentBatchTestSchema.BATCHES);
        statements.add(EnrolmentBatchTestSchema.ITEMS);
        statements.forEach(jdbcTemplate::execute);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        EnrolmentProperties enrolmentProperties() {
            return new EnrolmentProperties("v1", Map.of("v1", KEY), FINGERPRINT_KEY);
        }

        @Bean
        ModuleProperties moduleProperties() {
            return new ModuleProperties(new ModuleProperties.Toggle(true), null);
        }

        @Bean
        LearnerDataCipher learnerDataCipher(EnrolmentProperties properties, ModuleProperties modules) {
            return new LearnerDataCipher(properties, modules);
        }

        @Bean
        RetentionProperties retentionProperties() {
            return new RetentionProperties("-", Duration.ofDays(7), Duration.ofDays(1095), Duration.ofDays(365), Duration.ofDays(1095),
                    Duration.ofDays(30), Period.ofYears(3));
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
