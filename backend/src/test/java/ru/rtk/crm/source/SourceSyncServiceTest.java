package ru.rtk.crm.source;

import static org.mockito.Mockito.doReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import ru.rtk.crm.attachment.AttachmentRepository;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.CatalogChangeEventRepository;
import ru.rtk.crm.catalog.CatalogRepository;
import ru.rtk.crm.catalog.ContactRepository;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.enrolment.EnrolmentAccess;
import ru.rtk.crm.enrolment.EnrolmentAccessDeniedException;
import ru.rtk.crm.enrolment.EnrolmentProperties;
import ru.rtk.crm.enrolment.EnrolmentRepository;
import ru.rtk.crm.enrolment.LearnerDataCipher;
import ru.rtk.crm.enrolment.LearnerIntake;
import ru.rtk.crm.enrolment.LearnerRepository;
import ru.rtk.crm.enrolment.LearnerService;
import ru.rtk.crm.enrolment.PaidOrderEnrolment;
import ru.rtk.crm.enrolment.PaidOrderParser;
import ru.rtk.crm.enrolment.PaidOrderUpload;
import ru.rtk.crm.enrolment.PaidOrderUploadService;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.Interaction;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionCreateRequest;
import ru.rtk.crm.interaction.InteractionNextStep;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionRepository;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionTransitionRequest;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.interaction.WorkflowTemplateRepository;
import ru.rtk.crm.report.ReportAgreementFilters;
import ru.rtk.crm.report.ReportColumn;
import ru.rtk.crm.report.ReportColumnView;
import ru.rtk.crm.report.ReportFilters;
import ru.rtk.crm.report.ReportKind;
import ru.rtk.crm.report.ReportProperties;
import ru.rtk.crm.report.ReportRepository;
import ru.rtk.crm.report.ReportRequest;
import ru.rtk.crm.report.ReportRow;
import ru.rtk.crm.report.ReportService;
import ru.rtk.crm.report.StatisticsGroupBy;
import ru.rtk.crm.source.CardSourcesService.InteractionSourceStatus;
import ru.rtk.crm.training.CycleStartRequest;
import ru.rtk.crm.training.TeacherTrainingCreated;
import ru.rtk.crm.training.TeacherTrainingRequest;
import ru.rtk.crm.training.TrainingRepository;
import ru.rtk.crm.training.TrainingService;
import ru.rtk.crm.module.ModuleDisabledException;
import ru.rtk.crm.module.ModuleProperties;
import ru.rtk.crm.enrolment.EnrolmentBatchTestSchema;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "app.agreements.signing-stage-rule=false",
        "spring.datasource.url=jdbc:h2:mem:sources;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        ContactRepository.class,
        CatalogRepository.class,
        InteractionRepository.class,
        WorkflowTemplateRepository.class,
        AttachmentRepository.class,
        CommandIdempotencyRepository.class,
        InteractionService.class,
        ru.rtk.crm.agreement.AgreementStages.class,
        SourceRepository.class,
        SiteRecordApplier.class,
        CrmRecordApplier.class,
        CrmExchangeService.class,
        CrmEventRepository.class,
        CrmClient.class,
        SiteApiClient.class,
        PaidOrderParser.class,
        EnrolmentAccess.class,
        PaidOrderUploadService.class,
        PaidOrderEnrolment.class,
        LearnerService.class,
        LearnerRepository.class,
        EnrolmentRepository.class,
        LearnerDataCipher.class,
        AuditJournalRepository.class,
        UserProfileRepository.class,
        MoodleSnapshotApplier.class,
        SourceSyncService.class,
        LaborMarketSync.class,
        LaborMarketSettingsService.class,
        LaborMarketSettingsRepository.class,
        LaborMarketClient.class,
        LaborMarketRepository.class,
        LaborMarketService.class,
        SourceSettingsService.class,
        SourceSettingsRepository.class,
        SourceTokenCipher.class,
        CardSourcesService.class,
        SourceMappingService.class,
        SourceReviewService.class,
        CatalogChangeEventRepository.class,
        TrainingService.class,
        TrainingRepository.class,
        ReportRepository.class,
        ReportService.class,
        ru.rtk.crm.agreement.AgreementRepository.class,
        ru.rtk.crm.agreement.AgreementApproval.class,
        ru.rtk.crm.work.LmsLearningTestBeans.class,
        SourceSyncServiceTest.SourceTestConfiguration.class
})
class SourceSyncServiceTest {
    private static final String TOKEN = "test-token";
    private static final String ENROLMENT_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String ENROLMENT_FINGERPRINT_KEY = Base64.getEncoder().encodeToString("f".repeat(32).getBytes(StandardCharsets.UTF_8));
    private static final Map<Integer, String> PAGES = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> FAILING_PAGES = new ConcurrentHashMap<>();
    private static final List<String> QUERIES = new CopyOnWriteArrayList<>();
    private static final List<String> AUTHORIZATIONS = new CopyOnWriteArrayList<>();
    private static final String MOODLE_TOKEN = "moodle-test-token";
    private static final String SETTINGS_KEY = Base64.getEncoder().encodeToString("s".repeat(32).getBytes(StandardCharsets.UTF_8));
    private static final String MOODLE_FIXTURES = "/ru/rtk/crm/source/moodle/";
    private static final long JAVA_COURSE = 2;
    private static final long DATA_COURSE = 3;
    private static final LocalDate TODAY = LocalDate.now(ReportRequest.ZONE);
    private static final LocalDate RUN_STARTS = TODAY.minusDays(30);
    private static final LocalDate RUN_ENDS = TODAY.plusDays(180);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Map<String, String>> MOODLE_REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<String> MOODLE_QUERIES = new CopyOnWriteArrayList<>();
    private static final Map<String, String> MOODLE_BODIES = new ConcurrentHashMap<>();
    private static final Map<String, String> COMPLETION_OVERRIDES = new ConcurrentHashMap<>();
    private static final AtomicInteger MOODLE_STATUS = new AtomicInteger(200);
    private static final AtomicInteger MOODLE_FAIL_NEXT = new AtomicInteger();
    private static final String LABOR_TOKEN = "labor-market-test-token";
    private static final AtomicReference<String> LABOR_BODY = new AtomicReference<>("{\"items\":[]}");
    private static final List<String> LABOR_QUERIES = new CopyOnWriteArrayList<>();
    private static final HttpServer SITE = startSite();
    private static final String CRM_TOKEN = "crm-test-token";
    private static final List<String> CRM_ORGANIZATIONS = new CopyOnWriteArrayList<>();
    private static final List<String> CRM_CONTACTS = new CopyOnWriteArrayList<>();
    private static final List<String> CRM_QUERIES = new CopyOnWriteArrayList<>();
    private static final List<JsonNode> CRM_BATCHES = new CopyOnWriteArrayList<>();
    private static final List<String> CRM_BATCH_KEYS = new CopyOnWriteArrayList<>();
    private static final Set<String> CRM_RECEIVED = ConcurrentHashMap.newKeySet();
    private static final Set<String> CRM_REJECTED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger CRM_EVENTS_STATUS = new AtomicInteger(200);
    private static final AtomicInteger CRM_LIST_STATUS = new AtomicInteger(200);
    private static final HttpServer CRM = startCrm();

    private static final UUID TEAM_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID TEAM_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID KAM_A = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID KAM_B = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID ORGANIZATION_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ORGANIZATION_B = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID DIRECTION = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID PROGRAM_JAVA = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID PROGRAM_DATA = UUID.fromString("00000000-0000-0000-0000-000000000302");

    private static final String PARTNERSHIP_A = """
            {"externalId":"pr-1","type":"partnership_request","updatedAt":"2026-09-20T10:00:00+03:00",
             "createdAt":"2026-09-20T09:00:00+03:00","status":"new","organization":{"name":"Университет А"},
             "program":{"name":"Java-разработчик"},
             "contact":{"name":"Ольга Иванова","email":"olga@example.test","position":"Проректор"},
             "message":"Хотим подключить курс"}
            """;
    private static final String PARTNERSHIP_B = """
            {"externalId":"pr-2","type":"partnership_request","updatedAt":"2026-09-21T10:00:00+03:00","status":"new",
             "organization":{"externalId":"site-org-b","name":"Университет Б на сайте"},"program":{"name":"Java-разработчик"},
             "contact":{"name":"Пётр Сидоров","email":"petr@example.test"},"message":"Нужна встреча"}
            """;
    private static final String APPLICATION_A = """
            {"externalId":"la-1","type":"learning_application","updatedAt":"2026-09-22T10:00:00+03:00","status":"new",
             "organization":{"name":"Университет А"},"program":{"name":"Java-разработчик"},"applicationsCount":3,
             "contact":{"name":"Студент Студентов","email":"student@example.test"}}
            """;
    private static final String APPLICATION_UNKNOWN = """
            {"externalId":"la-2","type":"learning_application","updatedAt":"2026-09-22T11:00:00+03:00","status":"new",
             "organization":{"externalId":"site-org-x","name":"Университет Икс"},"program":{"name":"Java-разработчик"}}
            """;
    private static final String PARTNERSHIP_UNKNOWN = """
            {"externalId":"pr-3","type":"partnership_request","updatedAt":"2026-09-22T12:00:00+03:00","status":"new",
             "organization":{"externalId":"site-org-x","name":"Университет Икс"},"message":"Интересует партнёрство"}
            """;
    private static final String APPLICATION_WITHDRAWN = """
            {"externalId":"la-3","type":"learning_application","updatedAt":"2026-09-23T10:00:00+03:00","status":"withdrawn",
             "organization":{"name":"Университет Б"},"program":{"name":"Java-разработчик"}}
            """;
    private static final String PAID_ORDERS = """
            [null,
             {"Номер заявки": "ORD-20260901000001-DEMO01", "Курс": "Промпт-инжиниринг", "Фамилия": "Первова", "Имя": "Анна",
              "Отчество": "Демовна", "Телефон": "7 (900) 000-00-01", "Email": "anna.pervova@example.test", "Номер потока": 3},
             {"Номер заявки": "ORD-20260901000002-DEMO02", "Курс": "Промпт-инжиниринг", "Фамилия": "Второв", "Имя": "Борис",
              "Телефон": "7 (900) 000-00-02", "Email": "BORIS.VTOROV@EXAMPLE.TEST", "Номер потока": 3},
             {"Номер заявки": "ORD-202609010000003-DEMO03", "Курс": "Инженер-тестировщик", "Фамилия": "Третьякова",
              "Имя": "Вера", "Отчество": "Тестовна", "Телефон": "7 (900) 000-00-03", "Email": "vera.t@example.test",
              "Номер потока": 1},
             {"Номер заявки": "ORD-20261701000004-DEMO04", "Курс": "Инженер-тестировщик", "Фамилия": "Первова", "Имя": "Анна",
              "Отчество": "Демовна", "Телефон": "7 (900) 000-00-01", "Email": "anna.pervova@example.test", "Номер потока": 1},
             {"Номер заявки": "ORD-20260901000005-DEMO05",
              "Курс": "Управление ИТ-проектами на базе программного продукта ПАО «Ростелеком»", "Фамилия": "Четвертов",
              "Имя": "Глеб", "Отчество": "Демович", "Телефон": "7 (900) 000-00-05", "Email": "gleb.ch@example.test",
              "Номер потока": 2},
             {"Номер заявки": "ORD-20260901000006-DEMO06", "Курс": "Промпт-инжиниринг", "Фамилия": "Пятакова", "Имя": "Дарья",
              "Отчество": "Демовна", "Телефон": "7 (900) 000-00-06", "Email": "daria.p@example.test", "Номер потока": 3}]
            """;
    private static final String LONG_COURSE = "Управление ИТ-проектами на базе программного продукта ПАО «Ростелеком»";
    private static final String WITHOUT_EXTERNAL_ID = """
            {"type":"learning_application","updatedAt":"2026-09-23T10:00:00+03:00"}
            """;

    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);
    private final CrmProfile kamA = new CrmProfile(KAM_A, UserRole.USER, TEAM_A, 0);
    private final CrmProfile kamB = new CrmProfile(KAM_B, UserRole.USER, TEAM_B, 0);

    @Autowired
    private SourceSyncService service;

    @Autowired
    private LaborMarketSync laborMarketSync;

    @Autowired
    private LaborMarketService laborMarketService;

    @Autowired
    private LaborMarketSettingsService laborMarketSettings;

    @Autowired
    private SourceRepository repository;

    @Autowired
    private InteractionService interactionService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private SiteRecordApplier siteRecordApplier;

    @Autowired
    private SiteApiClient siteApiClient;

    @Autowired
    private CrmRecordApplier crmRecordApplier;

    @Autowired
    private CrmExchangeService crmExchangeService;

    @Autowired
    private MoodleSnapshotApplier moodleSnapshotApplier;

    @Autowired
    private SourceSyncExecutor sourceSyncExecutor;

    @Autowired
    private SourceSettingsService settingsService;

    @Autowired
    private SourceSettingsRepository sourceSettingsRepository;

    @Autowired
    private AuditJournalRepository auditJournalRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CardSourcesService card;

    @Autowired
    private SourceMappingService mappingService;

    @Autowired
    private SourceReviewService reviewService;

    @Autowired
    private TrainingService trainingService;

    @Autowired
    private PaidOrderUploadService paidOrderUploadService;

    @Autowired
    private PaidOrderParser paidOrderParser;

    @Autowired
    private EnrolmentAccess enrolmentAccess;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private CommandIdempotencyRepository commandIdempotencyRepository;

    @MockitoSpyBean
    private PaidOrderEnrolment paidOrderEnrolment;

    private UUID existingInteractionA;

    @AfterAll
    static void stopSite() {
        SITE.stop(0);
        CRM.stop(0);
    }

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of(
                "source_settings", "labor_market_settings", "labor_market_mappings", "labor_market_snapshots", "catalog_change_events", "learner_enrolments", "learners", "enrolment_streams", "audit_events",
                "teacher_trainings", "attachments", "interaction_cycles", "learning_observations", "learning_snapshots", "source_mappings", "source_records", "sync_runs", "sources",
                "interaction_events",
                "command_idempotency_records", "interaction_contacts", "product_agreements", "interaction_stage_transitions",
                "interaction_stages", "interactions", "workflow_template_transitions", "workflow_template_stages",
                "workflow_templates", "contacts", "products", "vendors", "programs", "directions", "organizations", "crm_user_profiles"
        )) {
            jdbcTemplate.update("DELETE FROM crm_exchange_events");
            jdbcTemplate.update("DELETE FROM agreement_status_events");
            jdbcTemplate.update("DELETE FROM agreements");
            jdbcTemplate.update("DELETE FROM organization_assignment_events");
            jdbcTemplate.update("DELETE FROM " + table);
        }
        PAGES.clear();
        FAILING_PAGES.clear();
        QUERIES.clear();
        AUTHORIZATIONS.clear();
        MOODLE_REQUESTS.clear();
        MOODLE_QUERIES.clear();
        MOODLE_BODIES.clear();
        COMPLETION_OVERRIDES.clear();
        MOODLE_STATUS.set(200);
        MOODLE_FAIL_NEXT.set(0);
        LABOR_BODY.set("{\"items\":[]}");
        LABOR_QUERIES.clear();
        CRM_ORGANIZATIONS.clear();
        CRM_CONTACTS.clear();
        CRM_QUERIES.clear();
        CRM_BATCHES.clear();
        CRM_BATCH_KEYS.clear();
        CRM_RECEIVED.clear();
        CRM_REJECTED.clear();
        CRM_EVENTS_STATUS.set(200);
        CRM_LIST_STATUS.set(200);
        jdbcTemplate.update("INSERT INTO sources (code) VALUES ('WEBSITE'), ('MOODLE'), ('LABOR_MARKET'), ('CUSTOMER_CRM')");
        insertProfile(ADMIN, "Администратор", "ADMIN", null);
        insertProfile(KAM_A, "Анна Смирнова", "USER", TEAM_A);
        insertProfile(KAM_B, "Борис Орлов", "USER", TEAM_B);
        insertOrganization(ORGANIZATION_A, null, "Университет А", TEAM_A, KAM_A);
        insertOrganization(ORGANIZATION_B, "site-org-b", "Университет Б", TEAM_B, KAM_B);
        jdbcTemplate.update("INSERT INTO directions (id, name, archived, version) VALUES (?, 'Программирование', FALSE, 0)",
                DIRECTION);
        jdbcTemplate.update("""
                INSERT INTO programs (id, direction_id, name, archived, version) VALUES (?, ?, 'Java-разработчик', FALSE, 0)
                """, PROGRAM_JAVA, DIRECTION);
        jdbcTemplate.update("""
                INSERT INTO programs (id, direction_id, name, archived, version) VALUES (?, ?, 'Анализ данных', FALSE, 0)
                """, PROGRAM_DATA, DIRECTION);
        insertDefaultTemplate();
        existingInteractionA = interactionService.create(
                kamA,
                new InteractionCreateRequest(ORGANIZATION_A, "Курс Java", null, null, List.of(), PROGRAM_JAVA, List.of(),
                        null, null),
                "existing-a"
        ).id();
        PAGES.put(1, page(2, PARTNERSHIP_A, PARTNERSHIP_B, APPLICATION_A, APPLICATION_UNKNOWN, PARTNERSHIP_UNKNOWN));
        PAGES.put(2, page(null, PARTNERSHIP_A, APPLICATION_WITHDRAWN, WITHOUT_EXTERNAL_ID));
    }

    @Test
    void laborMarketSyncStoresMonthlySlicesMapsDirectionsByNameAndFeedsDemandReport() {
        sync();
        YearMonth month = YearMonth.now(ReportRequest.ZONE);
        assertThat(marketColumns()).containsExactly(tuple("Java-разработчик", "не применяется", "не применяется"));
        jdbcTemplate.update("UPDATE directions SET labor_market = TRUE WHERE id = ?", DIRECTION);
        LABOR_BODY.set(laborBody(
                laborItem("programming", " Программирование ", month.minusMonths(2), 100),
                laborItem("programming", "Программирование", month.minusMonths(1), 120),
                laborItem("programming", "Программирование", month, 150),
                laborItem("design", "Дизайн", month, 40)));

        SyncRunView first = syncLaborMarket();

        assertThat(first.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(counters(first)).containsExactly(4, 4, 0, 0, 0, 0);
        assertThat(LABOR_QUERIES.getFirst()).isEqualTo("from=" + month.minusMonths(23) + "&to=" + month);
        assertThat(jdbcTemplate.queryForList("SELECT external_code FROM labor_market_snapshots ORDER BY external_code, period_month",
                String.class)).containsExactly("design", "programming", "programming", "programming");
        assertThat(jdbcTemplate.queryForMap("SELECT external_code, origin FROM labor_market_mappings WHERE direction_id = ?", DIRECTION))
                .containsEntry("external_code", "programming").containsEntry("origin", "AUTO");
        assertThat(marketColumns()).containsExactly(tuple("Java-разработчик", 150L, 50L));

        SyncRunView repeat = syncLaborMarket();

        assertThat(counters(repeat)).containsExactly(4, 0, 0, 4, 0, 0);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM labor_market_snapshots", Integer.class)).isEqualTo(4);

        LABOR_BODY.set(laborBody(laborItem("programming", "Программирование", month, 180)));

        assertThat(counters(syncLaborMarket())).containsExactly(1, 0, 1, 0, 0, 0);
        assertThat(marketColumns()).containsExactly(tuple("Java-разработчик", 180L, 80L));
    }

    @Test
    void laborMarketDirectionWithoutNameMatchNeedsMappingUntilAdministratorChoosesCode() {
        sync();
        YearMonth month = YearMonth.now(ReportRequest.ZONE);
        jdbcTemplate.update("UPDATE directions SET labor_market = TRUE WHERE id = ?", DIRECTION);
        LABOR_BODY.set(laborBody(laborItem("dev", "Разработка ПО", month.minusMonths(1), 10), laborItem("dev", "Разработка ПО", month, 12)));

        SyncRunView run = syncLaborMarket();

        assertThat(counters(run)).containsExactly(2, 2, 0, 0, 1, 0);
        assertThat(run.errorMessage()).contains("без сопоставления: 1");
        assertThat(marketColumns()).containsExactly(tuple("Java-разработчик", null, null));

        LaborMarketDirection mapped = laborMarketService.update(admin, DIRECTION, new LaborMarketMappingRequest("dev", null), "request-1");

        assertThat(mapped.origin()).isEqualTo("MANUAL");
        assertThat(mapped.latestVacancies()).isEqualTo(12);
        assertThat(marketColumns()).containsExactly(tuple("Java-разработчик", 12L, 20L));
        assertThat(counters(syncLaborMarket())).containsExactly(2, 0, 0, 2, 0, 0);
        assertThatThrownBy(() -> laborMarketService.update(admin, DIRECTION, new LaborMarketMappingRequest("dev", 5), "request-2"))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("VERSION_CONFLICT"));
        assertThatThrownBy(() -> laborMarketService.update(admin, DIRECTION, new LaborMarketMappingRequest("other", 0), "request-3"))
                .isInstanceOf(InteractionValidationException.class);
    }

    @Test
    void demoPreparationFlagsDirectionsKeepsManualCodeAndFirstSyncMapsTheRestByName() {
        YearMonth month = YearMonth.now(ReportRequest.ZONE);
        UUID transformation = UUID.randomUUID();
        UUID security = UUID.randomUUID();
        UUID cloud = UUID.randomUUID();
        UUID unflagged = UUID.randomUUID();
        for (Object[] direction : List.of(new Object[] {transformation, "Демо-направление: цифровая трансформация"},
                new Object[] {security, "Кибербезопасность"}, new Object[] {cloud, "Облачные технологии и DevOps"},
                new Object[] {unflagged, "Тестирование"})) {
            jdbcTemplate.update("INSERT INTO directions (id, name, archived, version) VALUES (?, ?, FALSE, 0)", direction);
        }
        LABOR_BODY.set(laborBody(
                laborItem("digital-transformation", "Цифровая трансформация", month, 500),
                laborItem("cybersecurity", "Кибербезопасность", month, 300),
                laborItem("testing", "Тестирование", month, 100)));
        List<String> names = List.of("Демо-направление: цифровая трансформация", "Кибербезопасность", "Облачные технологии и DevOps");
        Map<String, String> codes = Map.of("Демо-направление: цифровая трансформация", "digital-transformation");

        laborMarketService.prepareDemo(ADMIN, names, codes);
        laborMarketService.prepareDemo(ADMIN, names, Map.of("Демо-направление: цифровая трансформация", "testing"));
        SyncRunView run = syncLaborMarket();

        assertThat(counters(run)).containsExactly(3, 3, 0, 0, 1, 0);
        assertThat(jdbcTemplate.queryForList("SELECT name FROM directions WHERE labor_market = TRUE ORDER BY name", String.class))
                .containsExactly("Демо-направление: цифровая трансформация", "Кибербезопасность", "Облачные технологии и DevOps");
        assertThat(jdbcTemplate.queryForList("SELECT direction_id, external_code, origin FROM labor_market_mappings ORDER BY external_code")
                .stream().map(row -> tuple(row.get("DIRECTION_ID"), row.get("EXTERNAL_CODE"), row.get("ORIGIN"))).toList())
                .containsExactlyInAnyOrder(tuple(transformation, "digital-transformation", "MANUAL"),
                        tuple(security, "cybersecurity", "AUTO"));
        assertThat(laborMarketService.mappings(admin).directions())
                .extracting(LaborMarketDirection::directionName, LaborMarketDirection::externalCode,
                        LaborMarketDirection::latestVacancies)
                .containsExactly(tuple("Демо-направление: цифровая трансформация", "digital-transformation", 500),
                        tuple("Кибербезопасность", "cybersecurity", 300),
                        tuple("Облачные технологии и DevOps", null, null));
    }

    @Test
    void laborMarketRejectsBrokenItemsAndKeepsStoredDataWhenSourceFails() {
        YearMonth month = YearMonth.now(ReportRequest.ZONE);
        LABOR_BODY.set(laborBody(
                laborItem("programming", "Программирование", month, 150),
                laborItem("programming", "Программирование", month, 151),
                laborItem("bad code", "Дизайн", month, 40),
                laborItem("negative", "Минус", month, -1),
                "{\"directionCode\":\"nomonth\",\"directionName\":\"Без месяца\",\"month\":\"2026-13\",\"openVacancies\":1,"
                        + "\"dataSource\":\"Тестовый источник\",\"asOf\":\"2026-01-01\"}"));

        SyncRunView run = syncLaborMarket();

        assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(counters(run)).containsExactly(5, 1, 0, 0, 0, 4);
        assertThat(run.errorMessage()).contains("с ошибками");
        assertThat(jdbcTemplate.queryForObject("SELECT open_vacancies FROM labor_market_snapshots", Integer.class)).isEqualTo(150);

        LABOR_BODY.set(null);
        SyncRunView failed = syncLaborMarket();

        assertThat(failed.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(jdbcTemplate.queryForObject("SELECT open_vacancies FROM labor_market_snapshots", Integer.class)).isEqualTo(150);

        LABOR_BODY.set("{\"rows\":[]}");
        SyncRunView invalid = syncLaborMarket();

        assertThat(invalid.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(invalid.errorCode()).isEqualTo("SOURCE_INVALID_RESPONSE");
    }

    @Test
    void laborMarketSourceIsAdministratorOnlyAndNeedsAnAddress() {
        assertThat(service.sources(admin)).extracting(SourceView::source, SourceView::configured, SourceView::proposedContract)
                .contains(tuple(SourceCode.LABOR_MARKET, true, true));
        assertThatThrownBy(() -> service.start(kamA, SourceCode.LABOR_MARKET))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("FORBIDDEN"));
        assertThatThrownBy(() -> service.runs(kamA, SourceCode.LABOR_MARKET)).isInstanceOf(SourceException.class);
        assertThatThrownBy(() -> laborMarketService.mappings(kamA)).isInstanceOf(SourceException.class);

        laborMarketSettings.update(admin, new LaborMarketSettingsRequest(null, null), "request-1");

        assertThatThrownBy(() -> service.start(admin, SourceCode.LABOR_MARKET))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("SOURCE_NOT_CONFIGURED"));
        assertThat(service.sources(admin)).extracting(SourceView::source, SourceView::configured)
                .contains(tuple(SourceCode.LABOR_MARKET, false));
    }

    @Test
    void siteRequestAnswerIsDueInTwoWorkingDaysSkippingTheWeekend() {
        assertThat(SiteRecordApplier.leadResponseDue(OffsetDateTime.parse("2026-10-14T15:30:00+03:00")))
                .isEqualTo(OffsetDateTime.parse("2026-10-16T15:30:00+03:00"));
        assertThat(SiteRecordApplier.leadResponseDue(OffsetDateTime.parse("2026-10-16T18:00:00+03:00")))
                .isEqualTo(OffsetDateTime.parse("2026-10-20T18:00:00+03:00"));
        assertThat(SiteRecordApplier.leadResponseDue(OffsetDateTime.parse("2026-10-17T21:00:00Z")))
                .isEqualTo(OffsetDateTime.parse("2026-10-20T00:00:00+03:00"));
    }

    @Test
    void syncAddsToExistingOrNewInteractionAndRepeatDoesNotDuplicate() {
        SyncRunView first = sync();

        assertThat(first.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(List.of(first.fetchedCount(), first.createdCount(), first.updatedCount(), first.skippedCount(),
                first.needsMappingCount(), first.failedCount())).containsExactly(8, 3, 0, 2, 2, 1);
        assertThat(first.errorMessage()).contains("без externalId");
        assertThat(AUTHORIZATIONS).containsOnly("Bearer " + TOKEN);
        assertThat(QUERIES.getFirst()).isEqualTo("page=1");

        assertThat(interactionIds(ORGANIZATION_A)).containsExactly(existingInteractionA);
        assertThat(events(existingInteractionA)).containsExactly("CREATED", "COMMENTED");
        assertThat(lastComment(existingInteractionA)).startsWith("Заявка с сайта: Хотим подключить курс")
                .contains("Внешний ID: pr-1").contains("Ольга Иванова");
        assertThat(linkedContactNames(existingInteractionA)).containsExactly("Ольга Иванова");

        List<UUID> interactionsB = interactionIds(ORGANIZATION_B);
        assertThat(interactionsB).hasSize(1);
        assertThat(jdbcTemplate.queryForMap("SELECT title, program_id FROM interactions WHERE id = ?", interactionsB.getFirst()))
                .containsEntry("title", "Заявка с сайта: Java-разработчик")
                .containsEntry("program_id", PROGRAM_JAVA);
        assertThat(jdbcTemplate.queryForObject("SELECT next_action FROM interactions WHERE id = ?", String.class,
                interactionsB.getFirst())).isEqualTo("Ответить на заявку");
        assertThat(jdbcTemplate.queryForObject("SELECT next_action_at FROM interactions WHERE id = ?", OffsetDateTime.class,
                interactionsB.getFirst())).isAfter(OffsetDateTime.now().plusDays(1));
        assertThat(jdbcTemplate.queryForObject("SELECT next_action FROM interactions WHERE id = ?", String.class,
                existingInteractionA)).isNull();
        assertThat(events(interactionsB.getFirst())).containsExactly("CREATED", "COMMENTED");
        assertThat(linkedContactNames(interactionsB.getFirst())).containsExactly("Пётр Сидоров");

        assertThat(jdbcTemplate.queryForObject("SELECT payload FROM source_records WHERE external_id = 'la-1'", String.class))
                .doesNotContain("student@example.test");
        assertThat(jdbcTemplate.queryForList("SELECT name FROM contacts WHERE organization_id = ?", String.class, ORGANIZATION_A))
                .containsExactly("Ольга Иванова");
        assertThat(recordStatus("la-3")).isEqualTo("SKIPPED");
        assertThat(watermark()).isEqualTo(OffsetDateTime.parse("2026-09-23T10:00:00+03:00"));
        assertThat(demandApplications(kamA)).isEqualTo(3);
        assertThat(demandApplications(kamB)).isZero();

        SyncRunView repeat = sync();

        assertThat(List.of(repeat.createdCount(), repeat.updatedCount(), repeat.skippedCount(), repeat.needsMappingCount(),
                repeat.failedCount())).containsExactly(0, 0, 5, 2, 1);
        assertThat(QUERIES.getLast()).contains("updated_since=2026-09-23T10%3A00%2B03%3A00");
        assertThat(events(existingInteractionA)).containsExactly("CREATED", "COMMENTED");
        assertThat(events(interactionsB.getFirst())).containsExactly("CREATED", "COMMENTED");
        assertThat(interactionIds(ORGANIZATION_B)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_records", Integer.class)).isEqualTo(6);
        assertThat(demandApplications(kamA)).isEqualTo(3);
    }

    @Test
    void partnershipRequestFromRestrictedContactIsAppliedWithoutLinkingOrCopyingTheContact() {
        UUID restricted = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO contacts (id, personal_data_status, organization_id, name, email, version, created_by, created_at, updated_at)
                VALUES (?, 'RESTRICTED', ?, 'Ольга Иванова', 'OLGA@example.test', 1, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, restricted, ORGANIZATION_A, KAM_A);

        sync();

        assertThat(events(existingInteractionA)).containsExactly("CREATED", "COMMENTED");
        assertThat(linkedContactNames(existingInteractionA)).isEmpty();
        assertThat(lastComment(existingInteractionA))
                .contains("Внешний ID: pr-1")
                .contains("Контакт: обработка персональных данных ограничена, данные не перенесены")
                .doesNotContain("Ольга Иванова")
                .doesNotContain("olga@example.test");
        assertThat(jdbcTemplate.queryForList("SELECT id FROM contacts WHERE organization_id = ?", UUID.class, ORGANIZATION_A))
                .containsExactly(restricted);
        assertThat(jdbcTemplate.queryForMap("SELECT status, error FROM source_records WHERE external_id = 'pr-1'"))
                .containsEntry("status", "APPLIED")
                .containsEntry("error", "Контакт заявки не привязан: обработка его персональных данных ограничена");
        assertThat(linkedContactNames(interactionIds(ORGANIZATION_B).getFirst())).containsExactly("Пётр Сидоров");
    }

    @Test
    void recordNeedingMappingIsAppliedAfterAdministratorChoosesOrganization() {
        sync();
        List<SourceRecordView> problems = service.problemRecords(admin);
        assertThat(problems).extracting(SourceRecordView::externalId).containsExactlyInAnyOrder("la-2", "pr-3");
        assertThat(problems).allSatisfy(record -> {
            assertThat(record.status()).isEqualTo(SourceRecordStatus.NEEDS_MAPPING);
            assertThat(record.organizationName()).isEqualTo("Университет Икс");
            assertThat(record.error()).contains("не сопоставлен");
        });
        UUID applicationId = problems.stream().filter(record -> record.externalId().equals("la-2")).findFirst().orElseThrow().id();
        String appliedAt = "SELECT applied_at FROM source_records WHERE id = ?";
        assertThat(jdbcTemplate.queryForObject(appliedAt, OffsetDateTime.class, applicationId)).isNull();

        SourceRecordApplyResult result = service.apply(admin, applicationId, new SourceRecordApplyRequest(ORGANIZATION_A, null, null, null));

        assertThat(result.record().status()).isEqualTo(SourceRecordStatus.APPLIED);
        // По времени применения уходит «Заявка с сайта»: оно ставится при сопоставлении и не сдвигается повторной синхронизацией.
        OffsetDateTime applied = jdbcTemplate.queryForObject(appliedAt, OffsetDateTime.class, applicationId);
        assertThat(applied).isNotNull();
        assertThat(result.record().organizationId()).isEqualTo(ORGANIZATION_A);
        assertThat(result.reappliedCount()).isEqualTo(1);
        assertThat(recordStatus("pr-3")).isEqualTo("APPLIED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT external_key FROM source_mappings WHERE kind = 'ORGANIZATION'", String.class)).isEqualTo("id:site-org-x");
        assertThat(interactionIds(ORGANIZATION_A)).hasSize(2).contains(existingInteractionA);
        assertThat(service.problemRecords(admin)).isEmpty();
        assertThat(demandApplications(kamA)).isEqualTo(4);

        assertThat(sync().needsMappingCount()).isZero();
        assertThat(interactionIds(ORGANIZATION_A)).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(appliedAt, OffsetDateTime.class, applicationId)).isEqualTo(applied);
    }

    @Test
    void recordsStayPinnedToTheRunThatFirstSentThemToReviewWhileHistoryKeepsCountingThem() {
        SyncRunView first = sync();
        SyncRunView repeat = sync();

        assertThat(List.of(repeat.createdCount(), repeat.updatedCount(), repeat.needsMappingCount())).containsExactly(0, 0, 2);
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT sync_run_id FROM source_records WHERE status = 'NEEDS_MAPPING'",
                UUID.class)).containsExactly(first.id());

        CRM_ORGANIZATIONS.add(crmOrganization("c-4", "Школа № 7", "SCHOOL", null, "2026-10-04T10:00:00+03:00"));
        SyncRunView crm = syncCrm();
        syncCrm();

        assertThat(crmStatus("crm_organization", "c-4")).isEqualTo("NEEDS_MAPPING");
        assertThat(jdbcTemplate.queryForObject("SELECT sync_run_id FROM source_records WHERE id = ?", UUID.class,
                crmRecord("crm_organization", "c-4"))).isEqualTo(crm.id());
    }

    @Test
    void newerVersionReplacesRecordInsteadOfAddingToDemand() {
        sync();
        PAGES.put(1, page(null,
                PARTNERSHIP_A.replace("2026-09-20T10:00:00+03:00", "2026-09-25T10:00:00+03:00")
                        .replace("Хотим подключить курс", "Готовы подписать договор"),
                APPLICATION_A.replace("2026-09-22T10:00:00+03:00", "2026-09-25T10:00:00+03:00")
                        .replace("\"applicationsCount\":3", "\"applicationsCount\":5")));

        SyncRunView update = sync();

        assertThat(update.updatedCount()).isEqualTo(2);
        assertThat(demandApplications(kamA)).isEqualTo(5);
        assertThat(interactionIds(ORGANIZATION_A)).containsExactly(existingInteractionA);
        assertThat(events(existingInteractionA)).containsExactly("CREATED", "COMMENTED", "COMMENTED");
        assertThat(lastComment(existingInteractionA)).startsWith("Заявка с сайта обновлена: Готовы подписать договор");

        PAGES.put(1, page(null, APPLICATION_A.replace("2026-09-22T10:00:00+03:00", "2026-09-26T10:00:00+03:00")
                .replace("\"status\":\"new\"", "\"status\":\"withdrawn\"")));
        sync();

        assertThat(recordStatus("la-1")).isEqualTo("SKIPPED");
        assertThat(demandApplications(kamA)).isZero();
    }

    @Test
    void sourceFailureKeepsPreviousRecordsAndWatermark() {
        sync();
        int records = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_records", Integer.class);
        PAGES.put(1, page(2, PARTNERSHIP_A, APPLICATION_A.replace("la-1", "la-9")));
        FAILING_PAGES.put(2, 500);

        SyncRunView failed = sync();

        assertThat(failed.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(failed.errorMessage()).contains("HTTP 500");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_records", Integer.class)).isEqualTo(records);
        assertThat(watermark()).isEqualTo(OffsetDateTime.parse("2026-09-23T10:00:00+03:00"));
        assertThat(demandApplications(kamA)).isEqualTo(3);
        SourceView website = service.sources(admin).getFirst();
        assertThat(website.lastRun().status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(website.lastSuccessAt()).isNotNull();
        assertThat(website.problemCount()).isEqualTo(2);

        FAILING_PAGES.put(1, 401);
        assertThat(sync().errorCode()).isEqualTo("SOURCE_UNAUTHORIZED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_records", Integer.class)).isEqualTo(records);
    }

    @Test
    void onlyAdministratorManagesSourcesAndOneRunIsActivePerSource() {
        List<SourceView> sources = service.sources(admin);
        assertThat(sources).extracting(SourceView::source, SourceView::adapterAvailable, SourceView::configured,
                SourceView::proposedContract).containsExactly(
                tuple(SourceCode.WEBSITE, true, true, true),
                tuple(SourceCode.MOODLE, true, true, false),
                tuple(SourceCode.LABOR_MARKET, true, true, true),
                tuple(SourceCode.CUSTOMER_CRM, true, true, true));
        assertThat(new SourceProperties.Moodle("http://moodle.test", MOODLE_TOKEN, List.of(), List.of("student"), List.of(),
                Duration.ofSeconds(1), Duration.ofSeconds(1), DataSize.ofMegabytes(1)).configured()).isFalse();
        assertThat(sources.getFirst().lastRun()).isNull();
        assertThat(sources.getFirst().lastSuccessAt()).isNull();
        assertThat(sources.getFirst().updatedSince()).isNull();

        assertThatThrownBy(() -> service.sources(kamA))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("FORBIDDEN"));
        assertThatThrownBy(() -> service.start(kamA, SourceCode.WEBSITE)).isInstanceOf(SourceException.class);
        assertThatThrownBy(() -> service.start(kamA, SourceCode.MOODLE))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("FORBIDDEN"));

        jdbcTemplate.update("INSERT INTO sync_runs (id, source, status, started_by, created_at) VALUES (?, 'WEBSITE', 'RUNNING', ?, ?)",
                UUID.randomUUID(), ADMIN, OffsetDateTime.now());
        assertThatThrownBy(() -> service.start(admin, SourceCode.WEBSITE))
                .isInstanceOfSatisfying(SourceException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SYNC_ALREADY_RUNNING"));
        assertThat(QUERIES).isEmpty();
    }

    @Test
    void demoFixtureOfInfraFollowsProposedContract() throws IOException {
        UUID demoProgram = UUID.randomUUID();
        UUID vendor = UUID.randomUUID();
        UUID secureCommunications = UUID.randomUUID();
        UUID cloudPlatform = UUID.randomUUID();
        UUID organizationC = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO programs (id, direction_id, name, archived, version) VALUES (?, ?, ?, FALSE, 0)",
                demoProgram, DIRECTION, "Демо-программа: цифровой университет");
        jdbcTemplate.update("INSERT INTO vendors (id, name) VALUES (?, ?)", vendor, "Демо-вендор: РТК");
        jdbcTemplate.update("INSERT INTO products (id, vendor_id, name, archived, version) VALUES (?, ?, ?, FALSE, 0)",
                secureCommunications, vendor, "Демо-продукт: защищённая связь");
        jdbcTemplate.update("INSERT INTO products (id, vendor_id, name, archived, version) VALUES (?, ?, ?, FALSE, 0)",
                cloudPlatform, vendor, "Демо-продукт: облачная платформа");
        insertOrganization(organizationC, null, "Университет C — требует назначения", TEAM_A, null);
        UUID college = UUID.randomUUID();
        insertOrganization(college, null, "Колледж связи (демо)", TEAM_B, KAM_B);
        jdbcTemplate.update("UPDATE organizations SET type = 'COLLEGE' WHERE id = ?", college);
        UUID demoInteraction = interactionService.create(
                kamA,
                new InteractionCreateRequest(ORGANIZATION_A, "Демо: внедрение цифрового университета", null, null, List.of(),
                        demoProgram, List.of(secureCommunications, cloudPlatform), null, null),
                "demo-a"
        ).id();
        Path fixture = Path.of("..", "infra", "site-fixture", "data");
        PAGES.put(1, Files.readString(fixture.resolve("records-page-1.json")));
        PAGES.put(2, Files.readString(fixture.resolve("records-page-2.json")));

        SyncRunView run = sync();

        assertThat(List.of(run.fetchedCount(), run.createdCount(), run.updatedCount(), run.skippedCount(),
                run.needsMappingCount(), run.failedCount())).containsExactly(17, 11, 0, 2, 4, 0);
        assertThat(events(demoInteraction)).containsExactly("CREATED", "COMMENTED", "COMMENTED");
        assertThat(interactionIds(ORGANIZATION_B)).hasSize(1);
        assertThat(interactionIds(organizationC)).hasSize(1);
        assertThat(service.problemRecords(admin)).extracting(SourceRecordView::externalId)
                .containsExactlyInAnyOrder("pr-demo-004", "la-demo-104", "la-demo-107", "pr-demo-006");
        assertThat(demandApplications(kamA)).isEqualTo(19);
        assertThat(demandApplications(kamB)).isEqualTo(15);
        assertThat(interactionIds(college)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contacts WHERE email = 'applicant.demo@example.test'"
                + " OR name = 'Абитуриент Демонстрационный'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT payload FROM source_records WHERE external_id = 'la-demo-110'", String.class))
                .contains("site-college-1").doesNotContain("applicant.demo", "Абитуриент", "00-21");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void savedSettingsOverrideEnvironmentWithoutRestartAndTokenStaysOutOfLogs(CapturedOutput output) {
        String baseUrl = "http://127.0.0.1:" + SITE.getAddress().getPort() + "/";
        String savedToken = "saved-moodle-token-" + UUID.randomUUID();
        settingsService.update(admin, new SourceSettingsRequest(baseUrl, savedToken, List.of(JAVA_COURSE), List.of("student"),
                List.of("editingteacher", "teacher"), baseUrl, null, null), "settings-request");

        SyncRunView saved = syncMoodle();

        assertThat(saved.status()).as(saved.errorMessage()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(MOODLE_REQUESTS).extracting(form -> form.get("wstoken")).containsOnly(savedToken);
        assertThat(MOODLE_REQUESTS).extracting(form -> form.get("courseid")).filteredOn(value -> value != null)
                .containsOnly(Long.toString(JAVA_COURSE));
        assertThat(service.sources(admin)).extracting(SourceView::schedule).containsOnly((String) null);

        MOODLE_STATUS.set(401);
        SyncRunView failed = syncMoodle();
        assertThat(failed.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(failed.errorMessage()).doesNotContain(savedToken);
        assertThat(output.getAll()).doesNotContain(savedToken);
        assertThat(jdbcTemplate.queryForList("SELECT details FROM audit_events", String.class))
                .singleElement().asString().contains("токен Moodle заменён").doesNotContain(savedToken);

        settingsService.reset(admin, "reset-request");
        MOODLE_STATUS.set(200);
        MOODLE_REQUESTS.clear();
        SyncRunView restored = syncMoodle();

        assertThat(restored.status()).as(restored.errorMessage()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(MOODLE_REQUESTS).extracting(form -> form.get("wstoken")).containsOnly(MOODLE_TOKEN);
        assertThat(service.sources(admin)).extracting(SourceView::schedule).containsOnly("0 0 * * * *");
    }

    @Test
    void moodleSnapshotGoesToWorkflowAfterMappingAndOnlyChangedSnapshotWritesEvent() throws IOException {
        SyncRunView first = syncMoodle();

        assertThat(first.status()).as(first.errorMessage()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(counters(first)).containsExactly(5, 0, 0, 0, 5, 0);
        assertThat(MOODLE_QUERIES).containsOnly("null");
        assertThat(MOODLE_REQUESTS).extracting(form -> form.get("wstoken")).containsOnly(MOODLE_TOKEN);
        assertThat(MOODLE_REQUESTS).extracting(form -> form.get("wsfunction"))
                .filteredOn("core_completion_get_course_completion_status"::equals).hasSize(6);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learning_snapshots", Integer.class)).isZero();
        for (String payload : jdbcTemplate.queryForList("SELECT payload FROM source_records WHERE source = 'MOODLE'", String.class)) {
            assertThat(fieldNames(JSON.readTree(payload))).containsExactlyInAnyOrder("courseId", "groupId", "courseShortname",
                    "courseName", "groupName", "participants", "teachers", "completed", "notCompleted", "unknown",
                    "groupsCount");
        }
        assertThat(service.problemRecords(admin)).extracting(SourceRecordView::source, SourceRecordView::status)
                .containsOnly(tuple(SourceCode.MOODLE, SourceRecordStatus.NEEDS_MAPPING)).hasSize(5);

        UUID javaRecord = moodleRecordId("moodle_course", Long.toString(JAVA_COURSE));
        SourceRecordApplyResult mapped = service.apply(admin, javaRecord, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));

        assertThat(mapped.record().status()).isEqualTo(SourceRecordStatus.APPLIED);
        assertThat(mapped.record().interactionId()).isEqualTo(existingInteractionA);
        assertThat(events(existingInteractionA)).containsExactly("CREATED", "COMMENTED");
        assertThat(lastComment(existingInteractionA)).startsWith("Данные LMS: курс «Демо: Java-разработчик», обучающихся 6,"
                + " завершили 3, не завершили 3, статус неизвестен 0, групп 2, преподавателей 1. Наблюдение ");
        assertThat(moodleStatuses("moodle_group", JAVA_COURSE + ":%")).containsOnly("SKIPPED");

        service.apply(admin, moodleRecordId("moodle_course", Long.toString(DATA_COURSE)),
                moodleRun(ORGANIZATION_B, PROGRAM_DATA));

        List<UUID> interactionsB = interactionIds(ORGANIZATION_B);
        assertThat(interactionsB).hasSize(1);
        assertThat(jdbcTemplate.queryForMap("SELECT title, program_id FROM interactions WHERE id = ?", interactionsB.getFirst()))
                .containsEntry("title", "Обучение в LMS: Демо: анализ данных")
                .containsEntry("program_id", PROGRAM_DATA);
        assertThat(events(interactionsB.getFirst())).containsExactly("CREATED", "COMMENTED");
        assertThat(lastComment(interactionsB.getFirst())).contains("обучающихся 4, завершили: нет данных"
                + " (завершение курса в Moodle не отслеживается), групп 1, преподавателей 1");
        assertThat(service.problemRecords(admin)).isEmpty();
        OffsetDateTime changedAt = snapshotValue(javaRecord, "changed_at", OffsetDateTime.class);

        SyncRunView repeat = syncMoodle();

        assertThat(counters(repeat)).containsExactly(5, 0, 0, 5, 0, 0);
        assertThat(events(existingInteractionA)).containsExactly("CREATED", "COMMENTED");
        assertThat(events(interactionsB.getFirst())).containsExactly("CREATED", "COMMENTED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learning_snapshots", Integer.class)).isEqualTo(2);
        assertThat(snapshotValue(javaRecord, "changed_at", OffsetDateTime.class)).isEqualTo(changedAt);
        assertThat(snapshotValue(javaRecord, "observed_at", OffsetDateTime.class)).isAfter(changedAt);

        COMPLETION_OVERRIDES.put(completionUser(false), completionResponse(true));
        SyncRunView changed = syncMoodle();

        assertThat(counters(changed)).containsExactly(5, 0, 1, 4, 0, 0);
        assertThat(events(existingInteractionA)).containsExactly("CREATED", "COMMENTED", "COMMENTED");
        assertThat(lastComment(existingInteractionA)).contains("обучающихся 6, завершили 4, не завершили 2");
        assertThat(events(interactionsB.getFirst())).containsExactly("CREATED", "COMMENTED");
    }

    @Test
    void completionDisabledStaysUnknownInSnapshotReportAndInteractionCard() {
        syncMoodle();
        service.apply(admin, moodleRecordId("moodle_course", Long.toString(JAVA_COURSE)),
                moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        UUID dataRecord = moodleRecordId("moodle_course", Long.toString(DATA_COURSE));
        service.apply(admin, dataRecord, moodleRun(ORGANIZATION_A, PROGRAM_DATA));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT participants_count, completed_count, not_completed_count, unknown_count, groups_count
                FROM learning_snapshots WHERE source_record_id = ?
                """, dataRecord)).containsEntry("participants_count", 4)
                .containsEntry("completed_count", null)
                .containsEntry("not_completed_count", null)
                .containsEntry("unknown_count", 4)
                .containsEntry("groups_count", 1);

        List<ReportRow> demand = reportService.document(kamA, new ReportRequest(ReportKind.DEMAND, null, null, null,
                ReportFilters.none(), null, null, null, null)).rows();
        assertThat(demand).extracting(ReportRow::programName, ReportRow::applications, ReportRow::participants,
                ReportRow::parallelRuns).containsExactlyInAnyOrder(
                tuple("Java-разработчик", null, 6L, 1L),
                tuple("Анализ данных", null, 4L, 1L));
        assertThat(reportService.document(kamB, new ReportRequest(ReportKind.DEMAND, null, null, null,
                ReportFilters.none(), null, null, null, null)).rows()).isEmpty();

        assertThat(card.snapshots(kamA, existingInteractionA)).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.courseName()).isEqualTo("Демо: Java-разработчик");
            assertThat(List.of(snapshot.participants(), snapshot.completed(), snapshot.notCompleted(), snapshot.unknown(),
                    snapshot.groupsCount(), snapshot.teachers())).containsExactly(6, 3, 3, 0, 2, 1);
            assertThat(List.of(snapshot.runStartsOn(), snapshot.runEndsOn())).containsExactly(RUN_STARTS, RUN_ENDS);
        });
        UUID dataInteraction = jdbcTemplate.queryForObject(
                "SELECT id FROM interactions WHERE organization_id = ? AND program_id = ?", UUID.class, ORGANIZATION_A, PROGRAM_DATA);
        assertThat(card.snapshots(kamA, dataInteraction)).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.completed()).isNull();
            assertThat(snapshot.notCompleted()).isNull();
            assertThat(snapshot.unknown()).isEqualTo(4);
        });
        assertThatThrownBy(() -> card.snapshots(kamB, existingInteractionA)).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> card.snapshots(admin, existingInteractionA)).isInstanceOf(InteractionNotFoundException.class);
    }

    @Test
    void moodleFailureKeepsLastFullPublication() throws IOException {
        syncMoodle();
        UUID javaRecord = moodleRecordId("moodle_course", Long.toString(JAVA_COURSE));
        service.apply(admin, javaRecord, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        Map<String, Object> published = jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", javaRecord);
        COMPLETION_OVERRIDES.put(completionUser(false), completionResponse(true));
        MOODLE_BODIES.put("core_group_get_course_groups:" + DATA_COURSE, fixture("error-invalidtoken.json"));

        SyncRunView unauthorized = syncMoodle();

        assertThat(unauthorized.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(unauthorized.errorCode()).isEqualTo("SOURCE_UNAUTHORIZED");
        assertThat(unauthorized.errorMessage()).contains("invalidtoken").doesNotContain(MOODLE_TOKEN);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", javaRecord))
                .isEqualTo(published);
        assertThat(events(existingInteractionA)).containsExactly("CREATED", "COMMENTED");

        MOODLE_BODIES.clear();
        MOODLE_BODIES.put("core_enrol_get_enrolled_users:" + JAVA_COURSE, "[]");
        SyncRunView withoutGroupAccess = syncMoodle();

        assertThat(withoutGroupAccess.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(withoutGroupAccess.failedCount()).isEqualTo(1);
        assertThat(withoutGroupAccess.errorMessage()).startsWith("Частично: не обновлены курсы " + JAVA_COURSE)
                .contains("moodle/site:accessallgroups");
        assertThat(recordStatusById(javaRecord)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", javaRecord))
                .isEqualTo(published);

        MOODLE_BODIES.clear();
        MOODLE_STATUS.set(500);
        SyncRunView unavailable = syncMoodle();

        assertThat(unavailable.errorCode()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", javaRecord))
                .isEqualTo(published);
        SourceView moodle = sourceView(SourceCode.MOODLE);
        assertThat(moodle.lastRun().status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(moodle.lastSuccessAt()).isNotNull();

        MOODLE_STATUS.set(200);
        assertThat(syncMoodle().updatedCount()).isEqualTo(1);
        assertThat(snapshotValue(javaRecord, "completed_count", Integer.class)).isEqualTo(4);
    }

    @Test
    void oneFailedCourseDoesNotStopTheRunAndKeepsItsPreviousData() throws IOException {
        syncMoodle();
        UUID javaRecord = moodleRecordId("moodle_course", Long.toString(JAVA_COURSE));
        UUID dataRecord = moodleRecordId("moodle_course", Long.toString(DATA_COURSE));
        service.apply(admin, javaRecord, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        service.apply(admin, dataRecord, moodleRun(ORGANIZATION_B, PROGRAM_DATA));
        Map<String, Object> dataPublished = jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?",
                dataRecord);
        COMPLETION_OVERRIDES.put(completionUser(false), completionResponse(true));
        MOODLE_BODIES.put("core_group_get_course_groups:" + DATA_COURSE, fixture("error-function-not-in-service.json"));

        SyncRunView partial = syncMoodle();

        assertThat(partial.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(counters(partial)).containsExactly(4, 0, 1, 2, 0, 1);
        assertThat(partial.errorMessage()).startsWith("Частично: не обновлены курсы " + DATA_COURSE + ";").contains("accessexception");
        assertThat(snapshotValue(javaRecord, "completed_count", Integer.class)).isEqualTo(4);
        assertThat(recordStatusById(dataRecord)).isEqualTo("FAILED");
        assertThat(moodleStatuses("moodle_group", DATA_COURSE + ":%")).containsOnly("FAILED");
        assertThat(jdbcTemplate.queryForMap("SELECT error, organization_id, program_id FROM source_records WHERE id = ?", dataRecord))
                .containsEntry("ORGANIZATION_ID", ORGANIZATION_B).containsEntry("PROGRAM_ID", PROGRAM_DATA)
                .satisfies(row -> assertThat((String) row.get("ERROR")).startsWith("Курс не обновлён:"));
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", dataRecord))
                .isEqualTo(dataPublished);
        assertThat(sourceView(SourceCode.MOODLE).lastSuccessAt()).isNotNull();

        MOODLE_BODIES.clear();
        SyncRunView recovered = syncMoodle();

        assertThat(recovered.errorMessage()).isNull();
        assertThat(recovered.failedCount()).isZero();
        assertThat(recordStatusById(dataRecord)).isEqualTo("APPLIED");

        MOODLE_BODIES.put("core_group_get_course_groups:" + DATA_COURSE, fixture("error-function-not-in-service.json"));
        MOODLE_BODIES.put("core_group_get_course_groups:" + JAVA_COURSE, fixture("error-function-not-in-service.json"));
        SyncRunView allFailed = syncMoodle();

        assertThat(allFailed.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(allFailed.errorCode()).isEqualTo("SOURCE_UNAUTHORIZED");
        assertThat(allFailed.errorMessage()).startsWith("Данные не получены ни по одному курсу");
    }

    @Test
    void courseFailingInEveryPartialRunIsShownStaleByItsSnapshotTime() throws IOException {
        syncMoodle();
        UUID javaRecord = moodleRecordId("moodle_course", Long.toString(JAVA_COURSE));
        UUID dataRecord = moodleRecordId("moodle_course", Long.toString(DATA_COURSE));
        service.apply(admin, javaRecord, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        service.apply(admin, dataRecord, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        jdbcTemplate.update("UPDATE learning_snapshots SET observed_at = ?", OffsetDateTime.now().minusHours(30));
        MOODLE_BODIES.put("core_group_get_course_groups:" + DATA_COURSE, fixture("error-function-not-in-service.json"));

        SyncRunView partial = syncMoodle();

        assertThat(partial.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(partial.failedCount()).isEqualTo(1);
        assertThat(sourceView(SourceCode.MOODLE).stale()).as("the run itself succeeded").isFalse();
        assertThat(card.snapshots(kamA, existingInteractionA))
                .extracting(CardSourcesService.LearningSnapshotView::courseId, CardSourcesService.LearningSnapshotView::stale)
                .containsExactlyInAnyOrder(tuple(JAVA_COURSE, false), tuple(DATA_COURSE, true));
        assertThat(card.status(kamA, existingInteractionA).lms().stale()).isTrue();

        jdbcTemplate.update("UPDATE source_mappings SET run_ends_on = ? WHERE id = (SELECT mapping_id FROM learning_snapshots"
                + " WHERE source_record_id = ?)", TODAY, dataRecord);

        assertThat(card.status(kamA, existingInteractionA).lms().stale())
                .as("closed run keeps its last observation and is not stale")
                .isFalse();
    }

    @Test
    void hiddenOrDeletedCourseIsListedAsFailedWhileOtherCoursesAreRead() throws IOException {
        JsonNode courses = JSON.readTree(fixture("courses.json"));
        ((com.fasterxml.jackson.databind.node.ArrayNode) courses.path("courses")).remove(1);
        MOODLE_BODIES.put("core_course_get_courses_by_field:null", courses.toString());

        SyncRunView run = syncMoodle();

        assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(run.failedCount()).isEqualTo(1);
        assertThat(run.needsMappingCount()).isEqualTo(3);
        assertThat(run.errorMessage()).startsWith("Частично: не обновлены курсы " + DATA_COURSE + ";")
                .contains("moodle/course:viewhiddencourses");
    }

    @Test
    void temporaryFailuresAreRepeatedTwiceThenStopTheRun() {
        MOODLE_FAIL_NEXT.set(2);

        SyncRunView recovered = syncMoodle();

        assertThat(recovered.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(recovered.failedCount()).isZero();
        assertThat(MOODLE_REQUESTS).extracting(form -> form.get("wsfunction")).startsWith("core_course_get_courses_by_field",
                "core_course_get_courses_by_field", "core_course_get_courses_by_field");

        MOODLE_REQUESTS.clear();
        MOODLE_FAIL_NEXT.set(3);
        SyncRunView unavailable = syncMoodle();

        assertThat(unavailable.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(unavailable.errorCode()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(MOODLE_REQUESTS).hasSize(3);
    }

    @Test
    void emptyCourseWithGroupsFailsOnlyInSeparateGroupsMode() throws IOException {
        MOODLE_BODIES.put("core_enrol_get_enrolled_users:" + JAVA_COURSE, "[]");
        JsonNode courses = JSON.readTree(fixture("courses.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) courses.path("courses").get(0)).put("groupmode", 0);
        MOODLE_BODIES.put("core_course_get_courses_by_field:null", courses.toString());

        SyncRunView run = syncMoodle();

        assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(run.errorMessage()).isNull();
        assertThat(run.failedCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload FROM source_records WHERE source = 'MOODLE' AND record_type = 'moodle_course' AND external_id = ?
                """, String.class, Long.toString(JAVA_COURSE))).contains("\"participants\":0");
    }

    @Test
    void teacherCourseMarkIsSuggestedAndUsedWhenRunKindIsNotChosen() throws IOException {
        JsonNode courses = JSON.readTree(fixture("courses.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) courses.path("courses").get(0)).put("fullname", "Курс ППС 2026");
        MOODLE_BODIES.put("core_course_get_courses_by_field:null", courses.toString());
        syncMoodle();

        assertThat(service.problemRecords(admin)).filteredOn(record -> record.recordType().equals("moodle_course"))
                .extracting(SourceRecordView::externalId, SourceRecordView::suggestedRunKind)
                .containsExactlyInAnyOrder(tuple(Long.toString(JAVA_COURSE), RunKind.TEACHERS), tuple(Long.toString(DATA_COURSE), null));

        service.apply(admin, moodleRecordId("moodle_course", Long.toString(JAVA_COURSE)), moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        service.apply(admin, moodleRecordId("moodle_course", Long.toString(DATA_COURSE)), moodleRun(ORGANIZATION_B, PROGRAM_DATA));

        assertThat(jdbcTemplate.queryForList("SELECT run_kind FROM source_mappings WHERE external_key = ?", String.class,
                Long.toString(JAVA_COURSE))).containsExactly("TEACHERS");
        assertThat(jdbcTemplate.queryForList("SELECT run_kind FROM source_mappings WHERE external_key = ?", String.class,
                Long.toString(DATA_COURSE))).containsExactly("STUDENTS");
        assertThat(lastComment(existingInteractionA)).startsWith("Данные LMS (обучение преподавателей): курс «Курс ППС 2026»");
    }

    @Test
    void dailyActivityIsStoredWithoutNewEventChangedAtOrHistoryRow() throws IOException {
        MOODLE_BODIES.put("core_enrol_get_enrolled_users:" + JAVA_COURSE,
                withLastAccess(Map.of(4L, 16, 5L, 1, 6L, 2, 7L, 10, 8L, 20, 9L, 40)));
        syncMoodle();
        UUID javaRecord = moodleRecordId("moodle_course", Long.toString(JAVA_COURSE));
        service.apply(admin, javaRecord, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_7d_count, active_30d_count, never_accessed_count, inactive_count, teachers_active_7d_count,
                       teachers_active_14d_count
                FROM learning_snapshots WHERE source_record_id = ?
                """, javaRecord).values()).containsExactly(2, 4, 1, 2, 0, 0);
        assertThat(events(existingInteractionA)).containsExactly("CREATED", "COMMENTED");
        assertThat(lastComment(existingInteractionA)).doesNotContain("заходили");
        OffsetDateTime changedAt = snapshotValue(javaRecord, "changed_at", OffsetDateTime.class);

        MOODLE_BODIES.put("core_enrol_get_enrolled_users:" + JAVA_COURSE,
                withLastAccess(Map.of(4L, 1, 5L, 1, 6L, 1, 7L, 1, 8L, 1, 9L, 1, 10L, 1)));
        SyncRunView next = syncMoodle();

        assertThat(next.updatedCount()).as("only activity changed: the snapshot counts are the same").isZero();
        assertThat(snapshotValue(javaRecord, "active_7d_count", Integer.class)).isEqualTo(6);
        assertThat(snapshotValue(javaRecord, "teachers_active_7d_count", Integer.class)).isEqualTo(1);
        assertThat(snapshotValue(javaRecord, "changed_at", OffsetDateTime.class)).isEqualTo(changedAt);
        assertThat(events(existingInteractionA)).containsExactly("CREATED", "COMMENTED");
        assertThat(jdbcTemplate.queryForList("""
                SELECT o.active_7d_count FROM learning_observations o
                JOIN learning_snapshots s ON s.mapping_id = o.mapping_id WHERE s.source_record_id = ?
                """, Integer.class, javaRecord)).containsExactly(6);
    }

    @Test
    void studentInSeveralGroupsOfOneFlowIsCountedOnce() throws IOException {
        long firstGroup = groupId(JAVA_COURSE, "crm-demo-java-1");
        long secondGroup = groupId(JAVA_COURSE, "crm-demo-java-2");
        JsonNode users = JSON.readTree(fixture("users-" + JAVA_COURSE + ".json"));
        for (JsonNode user : users) {
            if (user.path("id").asLong() == 5) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) user.path("groups"))
                        .addObject().put("id", secondGroup).put("name", "Поток А2");
            }
        }
        MOODLE_BODIES.put("core_enrol_get_enrolled_users:" + JAVA_COURSE, users.toString());
        syncMoodle();
        UUID groupA1 = moodleRecordId("moodle_group", JAVA_COURSE + ":" + firstGroup);
        UUID groupA2 = moodleRecordId("moodle_group", JAVA_COURSE + ":" + secondGroup);

        service.apply(admin, groupA1, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        service.apply(admin, groupA2, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        SyncRunView sameFlow = syncMoodle();

        assertThat(sameFlow.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(snapshotValue(groupA1, "participants_count", Integer.class)).isEqualTo(3);
        assertThat(snapshotValue(groupA2, "participants_count", Integer.class)).isEqualTo(3);
        assertThat(demandParticipants(kamA)).containsExactly(tuple("Java-разработчик", 6L));
    }

    @Test
    void sharedStudentDoesNotDoubleTheFlowButEachFlowKeepsItsOwnCount() throws IOException {
        long firstGroup = groupId(JAVA_COURSE, "crm-demo-java-1");
        long secondGroup = groupId(JAVA_COURSE, "crm-demo-java-2");
        JsonNode users = JSON.readTree(fixture("users-" + JAVA_COURSE + ".json"));
        for (JsonNode user : users) {
            if (user.path("id").asLong() == 8) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) user.path("groups"))
                        .addObject().put("id", firstGroup).put("name", "Поток А1");
            }
        }
        MOODLE_BODIES.put("core_enrol_get_enrolled_users:" + JAVA_COURSE, users.toString());
        syncMoodle();
        UUID groupA1 = moodleRecordId("moodle_group", JAVA_COURSE + ":" + firstGroup);
        UUID groupA2 = moodleRecordId("moodle_group", JAVA_COURSE + ":" + secondGroup);

        service.apply(admin, groupA1, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        service.apply(admin, groupA2, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        syncMoodle();

        assertThat(snapshotValue(groupA1, "participants_count", Integer.class)).isEqualTo(4);
        assertThat(snapshotValue(groupA2, "participants_count", Integer.class)).isEqualTo(2);
        assertThat(lastComment(existingInteractionA)).contains("ещё 1 уже учтены в другой группе этого потока");
        assertThat(demandParticipants(kamA)).containsExactly(tuple("Java-разработчик", 6L));

        service.apply(admin, groupA2, moodleRun(ORGANIZATION_B, PROGRAM_JAVA));
        syncMoodle();

        assertThat(snapshotValue(groupA1, "participants_count", Integer.class)).isEqualTo(4);
        assertThat(snapshotValue(groupA2, "participants_count", Integer.class)).isEqualTo(3);
    }

    @Test
    void completedStudentWithEndedEnrolmentStaysCountedWhenMoodleReportsHisStatus() throws IOException {
        MOODLE_BODIES.put("core_enrol_get_enrolled_users:" + JAVA_COURSE + ":all", withEndedStudents(98, 99));
        COMPLETION_OVERRIDES.put("98", completionResponse(true));
        COMPLETION_OVERRIDES.put("99", completionResponse(false));

        syncMoodle();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload FROM source_records WHERE source = 'MOODLE' AND record_type = 'moodle_course' AND external_id = ?
                """, String.class, Long.toString(JAVA_COURSE)))
                .contains("\"participants\":7", "\"completed\":4", "\"notCompleted\":3", "\"unknown\":0");
        assertThat(MOODLE_REQUESTS).filteredOn(form -> "core_completion_get_course_completion_status".equals(form.get("wsfunction")))
                .extracting(form -> form.get("userid")).contains("98", "99");
        assertThat(MOODLE_REQUESTS).filteredOn(form -> Long.toString(DATA_COURSE).equals(form.get("courseid")))
                .extracting(form -> form.get("options[0][value]")).doesNotContain("0");
    }

    @Test
    void endedEnrolmentWithoutReadableStatusIsProbedOnceAndLeftOut() throws IOException {
        MOODLE_BODIES.put("core_enrol_get_enrolled_users:" + JAVA_COURSE + ":all", withEndedStudents(98, 99));
        String notTracked = "{\"exception\":\"moodle_exception\",\"errorcode\":\"nottracked\",\"message\":\"Not tracked\"}";
        COMPLETION_OVERRIDES.put("98", notTracked);
        COMPLETION_OVERRIDES.put("99", notTracked);

        SyncRunView run = syncMoodle();

        assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(run.failedCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload FROM source_records WHERE source = 'MOODLE' AND record_type = 'moodle_course' AND external_id = ?
                """, String.class, Long.toString(JAVA_COURSE))).contains("\"participants\":6", "\"completed\":3");
        assertThat(MOODLE_REQUESTS).filteredOn(form -> "core_completion_get_course_completion_status".equals(form.get("wsfunction")))
                .extracting(form -> form.get("userid")).filteredOn(user -> user.equals("98") || user.equals("99")).hasSize(1);
    }

    private static String withLastAccess(Map<Long, Integer> daysAgo) throws IOException {
        long now = System.currentTimeMillis() / 1000;
        JsonNode users = JSON.readTree(fixture("users-" + JAVA_COURSE + ".json"));
        for (JsonNode user : users) {
            Integer days = daysAgo.get(user.path("id").asLong());
            ((com.fasterxml.jackson.databind.node.ObjectNode) user).put("lastcourseaccess", days == null ? 0 : now - days * 86_400L);
        }
        return users.toString();
    }

    private static String withEndedStudents(long... ids) throws IOException {
        com.fasterxml.jackson.databind.node.ArrayNode users = (com.fasterxml.jackson.databind.node.ArrayNode) JSON.readTree(
                fixture("users-" + JAVA_COURSE + ".json"));
        for (long id : ids) {
            com.fasterxml.jackson.databind.node.ObjectNode user = users.addObject().put("id", id);
            user.putArray("roles").addObject().put("roleid", 5).put("name", "").put("shortname", "student").put("sortorder", 0);
            user.putArray("groups").addObject().put("id", groupId(JAVA_COURSE, "crm-demo-java-1")).put("name", "Поток А1");
        }
        return users.toString();
    }

    @Test
    void mixedCourseIsMappedByGroupsAndNeverTogetherWithWholeCourse() throws IOException {
        syncMoodle();
        UUID javaRecord = moodleRecordId("moodle_course", Long.toString(JAVA_COURSE));
        UUID firstGroup = moodleRecordId("moodle_group", JAVA_COURSE + ":" + groupId(JAVA_COURSE, "crm-demo-java-1"));

        SourceRecordApplyResult mapped = service.apply(admin, firstGroup, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));

        assertThat(mapped.record().status()).isEqualTo(SourceRecordStatus.APPLIED);
        assertThat(recordStatusById(javaRecord)).isEqualTo("SKIPPED");
        assertThat(moodleStatuses("moodle_group", JAVA_COURSE + ":%")).containsExactlyInAnyOrder("APPLIED", "NEEDS_MAPPING");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT participants_count, teachers_count, completed_count, not_completed_count
                FROM learning_snapshots WHERE source_record_id = ?
                """, firstGroup)).containsEntry("participants_count", 3)
                .containsEntry("teachers_count", 0)
                .containsEntry("completed_count", 2)
                .containsEntry("not_completed_count", 1);
        assertThat(lastComment(existingInteractionA)).startsWith("Данные LMS: курс «Демо: Java-разработчик», группа «Поток А1»,"
                + " обучающихся 3, завершили 2, не завершили 1, статус неизвестен 0, преподавателей 0.");
        assertThatThrownBy(() -> service.apply(admin, javaRecord, moodleRun(ORGANIZATION_B, PROGRAM_JAVA)))
                .isInstanceOf(InteractionValidationException.class);

        UUID dataRecord = moodleRecordId("moodle_course", Long.toString(DATA_COURSE));
        service.apply(admin, dataRecord, moodleRun(ORGANIZATION_B, PROGRAM_DATA));
        UUID dataGroup = moodleRecordId("moodle_group", DATA_COURSE + ":" + groupId(DATA_COURSE, "crm-demo-data-1"));
        assertThat(recordStatusById(dataGroup)).isEqualTo("SKIPPED");
        assertThatThrownBy(() -> service.apply(admin, dataGroup, moodleRun(ORGANIZATION_B, PROGRAM_DATA)))
                .isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> service.apply(admin, moodleRecordId("moodle_group",
                JAVA_COURSE + ":" + groupId(JAVA_COURSE, "crm-demo-java-2")), moodleRun(ORGANIZATION_B, null)))
                .isInstanceOf(InteractionValidationException.class);
        assertThat(syncMoodle().needsMappingCount()).isEqualTo(1);
    }

    @Test
    void cardRefreshReadsOnlyCoursesMappedToVisibleInteraction() throws IOException {
        syncMoodle();
        UUID groupA = moodleRecordId("moodle_group", JAVA_COURSE + ":" + groupId(JAVA_COURSE, "crm-demo-java-1"));
        UUID groupB = moodleRecordId("moodle_group", JAVA_COURSE + ":" + groupId(JAVA_COURSE, "crm-demo-java-2"));
        service.apply(admin, groupA, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        service.apply(admin, groupB, moodleRun(ORGANIZATION_B, PROGRAM_JAVA));
        UUID interactionB = interactionIds(ORGANIZATION_B).getFirst();
        Map<String, Object> publishedB = jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", groupB);
        OffsetDateTime observedA = snapshotValue(groupA, "observed_at", OffsetDateTime.class);
        List<String> eventsA = events(existingInteractionA);
        MOODLE_REQUESTS.clear();

        CardSourcesService.LearningSnapshotsRefresh refreshed = card.refreshLearning(kamA, existingInteractionA);

        assertThat(refreshed.run().status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(counters(refreshed.run())).containsExactly(1, 0, 0, 1, 0, 0);
        assertThat(refreshed.snapshots()).singleElement().extracting(CardSourcesService.LearningSnapshotView::groupName)
                .isEqualTo("Поток А1");
        assertThat(MOODLE_REQUESTS).extracting(form -> form.getOrDefault("courseid", form.get("value")))
                .containsOnly(Long.toString(JAVA_COURSE));
        assertThat(MOODLE_REQUESTS).extracting(form -> form.get("wstoken")).containsOnly(MOODLE_TOKEN);
        assertThat(snapshotValue(groupA, "observed_at", OffsetDateTime.class)).isAfter(observedA);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", groupB))
                .isEqualTo(publishedB);
        assertThat(events(existingInteractionA)).isEqualTo(eventsA);
        assertThat(jdbcTemplate.queryForObject("SELECT started_by FROM sync_runs WHERE id = ?", UUID.class,
                refreshed.run().id())).isEqualTo(KAM_A);

        assertThatThrownBy(() -> card.refreshLearning(kamB, existingInteractionA)).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> card.refreshLearning(admin, existingInteractionA)).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> card.refreshLearning(kamA, interactionB)).isInstanceOf(InteractionNotFoundException.class);
        UUID dataInteraction = interactionService.create(kamA, new InteractionCreateRequest(ORGANIZATION_A, "Анализ данных", null,
                null, List.of(), PROGRAM_DATA, List.of(), null, null), "data-a").id();
        assertThatThrownBy(() -> card.refreshLearning(kamA, dataInteraction)).isInstanceOf(SourceException.class)
                .extracting(exception -> ((SourceException) exception).code()).isEqualTo("LMS_NOT_MAPPED");

        COMPLETION_OVERRIDES.put(completionUser(false), completionResponse(true));
        MOODLE_STATUS.set(500);
        Map<String, Object> publishedA = jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", groupA);

        assertThatThrownBy(() -> card.refreshLearning(kamA, existingInteractionA)).isInstanceOf(SourceException.class)
                .extracting(exception -> ((SourceException) exception).code()).isEqualTo("LMS_SYNC_FAILED");
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", groupA))
                .isEqualTo(publishedA);
    }

    @Test
    void scheduleRunsConfiguredSourcesAsSystemWithoutAFirstManualRunAndDemoFirstSyncRunsOnce() {
        service.startScheduled();

        assertThat(jdbcTemplate.queryForList("SELECT started_by FROM sync_runs WHERE source = 'MOODLE'", UUID.class))
                .containsExactly(ADMIN);
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT run_trigger FROM sync_runs", String.class)).containsExactly("SCHEDULE");
        assertThat(service.runs(admin, SourceCode.MOODLE)).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
            assertThat(run.startedByName()).isEqualTo("Система");
        });
        assertThat(jdbcTemplate.queryForList("SELECT source FROM sync_runs ORDER BY source", String.class))
                .containsExactly("CUSTOMER_CRM", "LABOR_MARKET", "MOODLE", "WEBSITE");
        assertThat(service.synchronizeIfNeverSucceeded(ADMIN, SourceCode.MOODLE)).isEmpty();
        int runs = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sync_runs", Integer.class);

        jdbcTemplate.update("UPDATE crm_user_profiles SET active = FALSE WHERE role = 'ADMIN'");
        service.startScheduled();

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sync_runs", Integer.class)).isEqualTo(runs);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedMoodleRecordIsAppliedAgainWithSavedMapping() {
        syncMoodle();
        UUID javaRecord = moodleRecordId("moodle_course", Long.toString(JAVA_COURSE));
        UUID dataRecord = moodleRecordId("moodle_course", Long.toString(DATA_COURSE));
        assertThatThrownBy(() -> service.apply(admin, javaRecord, new SourceRecordApplyRequest(null, null, null, null)))
                .isInstanceOf(InteractionValidationException.class);
        jdbcTemplate.update("UPDATE workflow_templates SET default_template = FALSE");

        SourceRecordApplyResult failed = service.apply(admin, dataRecord, moodleRun(ORGANIZATION_B, PROGRAM_DATA));

        assertThat(failed.record().status()).isEqualTo(SourceRecordStatus.FAILED);
        assertThat(failed.record().error()).contains("шаблона процесса по умолчанию");
        assertThat(interactionIds(ORGANIZATION_B)).isEmpty();
        jdbcTemplate.update("UPDATE workflow_templates SET default_template = TRUE");

        SourceRecordApplyResult retried = service.apply(admin, dataRecord, new SourceRecordApplyRequest(null, null, null, null));

        assertThat(retried.record().status()).isEqualTo(SourceRecordStatus.APPLIED);
        assertThat(interactionIds(ORGANIZATION_B)).hasSize(1);
        assertThat(snapshotValue(dataRecord, "participants_count", Integer.class)).isEqualTo(4);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MOODLE_TEST_URL", matches = ".+")
    void liveMoodleMatchesDemoDataAndKeepsPublicationOnRepeatAndFailure() {
        List<Long> courses = Arrays.stream(System.getenv("MOODLE_TEST_COURSE_IDS").split(","))
                .map(String::strip).map(Long::valueOf).toList();
        SourceSyncService live = liveService(System.getenv("MOODLE_TEST_TOKEN"), courses);

        SyncRunView first = repository.findRun(live.start(admin, SourceCode.MOODLE).runId()).orElseThrow().run();

        assertThat(first.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(counters(first)).containsExactly(5, 0, 0, 0, 5, 0);
        UUID javaRecord = moodleRecordId("moodle_course", courses.get(0).toString());
        UUID dataRecord = moodleRecordId("moodle_course", courses.get(1).toString());
        live.apply(admin, javaRecord, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        live.apply(admin, dataRecord, moodleRun(ORGANIZATION_B, PROGRAM_DATA));
        String counts = """
                SELECT participants_count, teachers_count, completed_count, not_completed_count, unknown_count, groups_count
                FROM learning_snapshots WHERE source_record_id = ?
                """;
        Map<String, Object> java = jdbcTemplate.queryForMap(counts, javaRecord);
        Map<String, Object> data = jdbcTemplate.queryForMap(counts, dataRecord);
        assertThat(java.values()).containsExactly(6, 1, 3, 3, 0, 2);
        assertThat(data.values()).containsExactly(4, 1, null, null, 4, 1);
        assertThat(events(existingInteractionA)).containsExactly("CREATED", "COMMENTED");
        assertThat(lastComment(existingInteractionA)).startsWith("Данные LMS: курс «Демо: Java-разработчик», обучающихся 6,"
                + " завершили 3, не завершили 3, статус неизвестен 0, групп 2, преподавателей 1.");

        SyncRunView repeat = repository.findRun(live.start(admin, SourceCode.MOODLE).runId()).orElseThrow().run();

        assertThat(counters(repeat)).containsExactly(5, 0, 0, 5, 0, 0);
        assertThat(jdbcTemplate.queryForMap(counts, javaRecord)).isEqualTo(java);
        assertThat(jdbcTemplate.queryForMap(counts, dataRecord)).isEqualTo(data);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learning_snapshots", Integer.class)).isEqualTo(2);
        assertThat(events(existingInteractionA)).containsExactly("CREATED", "COMMENTED");
        assertThat(events(interactionIds(ORGANIZATION_B).getFirst())).containsExactly("CREATED", "COMMENTED");

        Map<String, Object> published = jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", javaRecord);
        SyncRunView rejected = repository.findRun(liveService("invalid-" + UUID.randomUUID(), courses)
                .start(admin, SourceCode.MOODLE).runId()).orElseThrow().run();

        assertThat(rejected.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(rejected.errorCode()).isEqualTo("SOURCE_UNAUTHORIZED");
        assertThat(rejected.errorMessage()).contains("invalidtoken");
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", javaRecord))
                .isEqualTo(published);
        assertThat(events(existingInteractionA)).containsExactly("CREATED", "COMMENTED");
    }

    @Test
    void moodleRunNeedsDatesAndCountsAsParallelOnlyInsideItsInterval() {
        syncMoodle();
        UUID javaRecord = moodleRecordId("moodle_course", Long.toString(JAVA_COURSE));
        UUID dataRecord = moodleRecordId("moodle_course", Long.toString(DATA_COURSE));

        assertThatThrownBy(() -> service.apply(admin, javaRecord,
                new SourceRecordApplyRequest(ORGANIZATION_A, PROGRAM_JAVA, null, RUN_ENDS)))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("runStartsOn"));
        assertThatThrownBy(() -> service.apply(admin, javaRecord,
                new SourceRecordApplyRequest(ORGANIZATION_A, PROGRAM_JAVA, RUN_STARTS, null)))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("runEndsOn"));
        assertThatThrownBy(() -> service.apply(admin, javaRecord,
                new SourceRecordApplyRequest(ORGANIZATION_A, PROGRAM_JAVA, RUN_STARTS, RUN_STARTS)))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("runEndsOn"));
        assertThat(recordStatusById(javaRecord)).isEqualTo("NEEDS_MAPPING");

        service.apply(admin, javaRecord, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        service.apply(admin, dataRecord,
                new SourceRecordApplyRequest(ORGANIZATION_A, PROGRAM_DATA, TODAY.plusDays(10), TODAY.plusDays(200)));

        assertThat(parallelRuns(null)).containsExactlyInAnyOrder(tuple("Java-разработчик", 1L), tuple("Анализ данных", 0L));
        assertThat(parallelRuns(RUN_STARTS.minusDays(1))).isEmpty();
        assertThat(parallelRuns(RUN_STARTS)).containsExactly(tuple("Java-разработчик", 1L));
        assertThat(parallelRuns(RUN_ENDS.minusDays(1)))
                .containsExactlyInAnyOrder(tuple("Java-разработчик", 1L), tuple("Анализ данных", 0L));
        assertThat(parallelRuns(RUN_ENDS)).containsExactlyInAnyOrder(tuple("Java-разработчик", 1L), tuple("Анализ данных", 0L));
        assertThat(reportService.document(kamA, new ReportRequest(ReportKind.DEMAND, null, RUN_ENDS, null,
                ReportFilters.none(), null, null, null, null)).notes())
                .anyMatch(note -> note.contains("по наблюдению на " + TODAY.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))));

        repository.saveMapping(SourceCode.MOODLE, "COURSE", Long.toString(JAVA_COURSE), ORGANIZATION_A, PROGRAM_JAVA, null,
                ADMIN, OffsetDateTime.now());

        assertThat(parallelRuns(null)).containsExactly(tuple("Анализ данных", 0L));
        assertThat(syncMoodle().needsMappingCount()).isEqualTo(1);
        assertThat(service.problemRecords(admin)).singleElement().satisfies(record -> {
            assertThat(record.id()).isEqualTo(javaRecord);
            assertThat(record.error()).contains("даты потока");
        });

        service.apply(admin, javaRecord, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));

        assertThat(recordStatusById(javaRecord)).isEqualTo("APPLIED");
        assertThat(parallelRuns(null)).containsExactlyInAnyOrder(tuple("Java-разработчик", 1L), tuple("Анализ данных", 0L));
    }

    @Test
    void closedRunKeepsLastObservationAndIsNotRecountedFromReusedCourse() throws IOException {
        syncMoodle();
        UUID javaRecord = moodleRecordId("moodle_course", Long.toString(JAVA_COURSE));
        UUID dataRecord = moodleRecordId("moodle_course", Long.toString(DATA_COURSE));
        service.apply(admin, javaRecord, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        Map<String, Object> published = jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", javaRecord);
        List<String> events = events(existingInteractionA);
        jdbcTemplate.update("UPDATE source_mappings SET run_starts_on = ?, run_ends_on = ? WHERE kind = 'COURSE' AND external_key = ?",
                TODAY.minusDays(90), TODAY, Long.toString(JAVA_COURSE));
        COMPLETION_OVERRIDES.put(completionUser(false), completionResponse(true));

        SyncRunView afterEnd = syncMoodle();

        assertThat(afterEnd.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(afterEnd.createdCount() + afterEnd.updatedCount()).isZero();
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", javaRecord))
                .isEqualTo(published);
        assertThat(recordStatusById(javaRecord)).isEqualTo("APPLIED");
        assertThat(events(existingInteractionA)).isEqualTo(events);

        SourceRecordApplyResult past = service.apply(admin, dataRecord,
                new SourceRecordApplyRequest(ORGANIZATION_B, PROGRAM_DATA, TODAY.minusDays(90), TODAY.minusDays(10)));

        assertThat(past.record().status()).isEqualTo(SourceRecordStatus.SKIPPED);
        assertThat(past.record().error()).contains("до первого наблюдения");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learning_snapshots WHERE source_record_id = ?",
                Integer.class, dataRecord)).isZero();
        assertThat(interactionIds(ORGANIZATION_B)).isEmpty();
    }

    @Test
    void learningHistoryAddsRowOnlyOnChangeAndDemandTakesObservationInForceOnDate() throws IOException {
        syncMoodle();
        UUID javaRecord = moodleRecordId("moodle_course", Long.toString(JAVA_COURSE));
        service.apply(admin, javaRecord, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        UUID run = jdbcTemplate.queryForObject("SELECT id FROM source_mappings WHERE kind = 'COURSE' AND external_key = ?",
                UUID.class, Long.toString(JAVA_COURSE));
        Map<String, Object> first = jdbcTemplate.queryForMap("SELECT * FROM learning_observations WHERE mapping_id = ?", run);
        assertThat(first).containsEntry("PARTICIPANTS_COUNT", 6).containsEntry("COMPLETED_COUNT", 3).containsEntry("DEMO", false);

        syncMoodle();
        syncMoodle();

        assertThat(history(run)).containsExactly(tuple(6, 3));
        Map<String, Object> confirmed = jdbcTemplate.queryForMap("SELECT * FROM learning_observations WHERE mapping_id = ?", run);
        assertThat(confirmed.get("OBSERVED_FROM")).isEqualTo(first.get("OBSERVED_FROM"));
        assertThat(((OffsetDateTime) confirmed.get("CONFIRMED_AT"))).isAfter((OffsetDateTime) first.get("CONFIRMED_AT"));

        COMPLETION_OVERRIDES.put(completionUser(false), completionResponse(true));
        syncMoodle();
        syncMoodle();

        assertThat(history(run)).containsExactly(tuple(6, 3), tuple(6, 4));

        jdbcTemplate.update("""
                UPDATE learning_observations SET observed_from = ?, confirmed_at = ? WHERE mapping_id = ? AND completed_count = 3
                """, OffsetDateTime.now().minusDays(20), OffsetDateTime.now().minusDays(6), run);
        jdbcTemplate.update("""
                UPDATE learning_observations SET observed_from = ? WHERE mapping_id = ? AND completed_count = 4
                """, OffsetDateTime.now().minusDays(5), run);

        assertThat(demandOn(TODAY.minusDays(10)))
                .containsExactly(tuple("Java-разработчик", 6L, 3L, 1L));
        assertThat(demandOn(TODAY)).containsExactly(tuple("Java-разработчик", 6L, 4L, 1L));
        assertThat(demandOn(TODAY.minusDays(25))).containsExactly(tuple("Java-разработчик", null, null, 1L));
        assertThat(reportService.document(kamA, new ReportRequest(ReportKind.DEMAND, null, TODAY.minusDays(10), null,
                ReportFilters.none(), null, null, null, null)).columnTitles())
                .containsEntry(ReportColumn.PARTICIPANTS, "Обучающиеся в потоках периода (Moodle, участия — не уникальные люди, на "
                        + TODAY.minusDays(10).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + ")");

        mappingService.deleteSnapshot(admin, run);

        assertThat(history(run)).isEmpty();
        assertThat(jdbcTemplate.queryForList(
                "SELECT details FROM audit_events WHERE action = 'SOURCE_MAPPING_CHANGED' AND object_id = ?", String.class, run))
                .containsExactly("LMS Moodle: снимок обучения удалён");
    }

    private List<Tuple> history(UUID run) {
        return jdbcTemplate.query("SELECT participants_count, completed_count FROM learning_observations WHERE mapping_id = ?"
                        + " ORDER BY observed_from", (resultSet, rowNumber) -> tuple(resultSet.getInt(1), resultSet.getObject(2)), run);
    }

    private List<Tuple> demandOn(LocalDate day) {
        return reportService.document(kamA, new ReportRequest(ReportKind.DEMAND, null, day, null, ReportFilters.none(), null,
                        null, null, null)).rows().stream()
                .filter(row -> row.programName() != null)
                .map(row -> tuple(row.programName(), row.participants(), row.completed(), row.parallelRuns()))
                .toList();
    }

    @Test
    void teacherRunIsCountedSeparatelyFromStudentsInCardAndDemand() {
        syncMoodle();
        service.apply(admin, moodleRecordId("moodle_course", Long.toString(JAVA_COURSE)), moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        service.apply(admin, moodleRecordId("moodle_course", Long.toString(DATA_COURSE)),
                new SourceRecordApplyRequest(ORGANIZATION_A, PROGRAM_JAVA, RUN_STARTS, RUN_ENDS, RunKind.TEACHERS));

        assertThat(card.snapshots(kamA, existingInteractionA))
                .extracting(CardSourcesService.LearningSnapshotView::runKind, CardSourcesService.LearningSnapshotView::participants)
                .containsExactly(tuple(RunKind.STUDENTS, 6), tuple(RunKind.TEACHERS, 4));
        assertThat(lastComment(existingInteractionA))
                .startsWith("Данные LMS (обучение преподавателей): курс «Демо: анализ данных», записано 4");
        assertThat(reportService.document(kamA, new ReportRequest(ReportKind.DEMAND, null, null, null, ReportFilters.none(),
                null, null, null, null)).rows())
                .extracting(ReportRow::programName, ReportRow::participants, ReportRow::completed, ReportRow::parallelRuns)
                .containsExactly(tuple("Java-разработчик", 6L, 3L, 1L));
    }

    @Test
    void directlySavedMappingDefaultsToStudentsAndCanBeSeededAsTeacherRun() {
        SourceRepository.RunDates run = new SourceRepository.RunDates(RUN_STARTS, RUN_ENDS);
        repository.saveMapping(SourceCode.MOODLE, "COURSE", Long.toString(JAVA_COURSE), ORGANIZATION_A, PROGRAM_JAVA, run,
                ADMIN, OffsetDateTime.now());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT run_kind FROM source_mappings WHERE kind = 'COURSE' AND external_key = ?", String.class,
                Long.toString(JAVA_COURSE))).isEqualTo("STUDENTS");

        repository.saveMapping(SourceCode.MOODLE, "COURSE", Long.toString(DATA_COURSE), ORGANIZATION_A, PROGRAM_JAVA, run,
                RunKind.TEACHERS, ADMIN, OffsetDateTime.now());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT run_kind FROM source_mappings WHERE kind = 'COURSE' AND external_key = ?", String.class,
                Long.toString(DATA_COURSE))).isEqualTo("TEACHERS");
    }

    @Test
    void newCycleGetsItsOwnRunOnTheSameCourseAndThePreviousRunStaysFrozen() {
        syncMoodle();
        UUID javaRecord = moodleRecordId("moodle_course", Long.toString(JAVA_COURSE));
        service.apply(admin, javaRecord, moodleRun(ORGANIZATION_A, PROGRAM_JAVA));
        UUID firstRun = jdbcTemplate.queryForObject("SELECT id FROM source_mappings WHERE kind = 'COURSE' AND external_key = ?",
                UUID.class, Long.toString(JAVA_COURSE));
        jdbcTemplate.update("UPDATE source_mappings SET run_starts_on = ?, run_ends_on = ? WHERE id = ?",
                TODAY.minusDays(120), TODAY.minusDays(5), firstRun);
        jdbcTemplate.update("UPDATE learning_snapshots SET observed_at = ? WHERE mapping_id = ?",
                OffsetDateTime.now().minusDays(10), firstRun);
        jdbcTemplate.update("UPDATE learning_observations SET observed_from = ?, confirmed_at = ? WHERE mapping_id = ?",
                OffsetDateTime.now().minusDays(30), OffsetDateTime.now().minusDays(10), firstRun);
        Map<String, Object> frozen = snapshotOfRun(firstRun);
        List<String> previousEvents = events(existingInteractionA);
        CycleStartRequest cycle = new CycleStartRequest("Курс Java: повторный цикл", null, TODAY.minusDays(5), null, null);

        Interaction next = trainingService.startCycle(kamA, existingInteractionA, cycle, "cycle-1");

        assertThat(List.of(next.organizationId(), next.programId())).containsExactly(ORGANIZATION_A, PROGRAM_JAVA);
        assertThat(trainingService.startCycle(kamA, existingInteractionA, cycle, "cycle-1").id()).isEqualTo(next.id());
        assertThatThrownBy(() -> trainingService.startCycle(kamA, existingInteractionA, cycle, "cycle-2"))
                .isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> trainingService.startCycle(kamB, existingInteractionA, cycle, "cycle-3"))
                .isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> trainingService.startCycle(kamA, next.id(),
                new CycleStartRequest("Слишком ранний цикл", null, TODAY.minusDays(6), null, null), "cycle-4"))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("startsOn"));
        assertThat(trainingService.cycle(kamA, next.id()).previous().interactionId()).isEqualTo(existingInteractionA);
        assertThat(trainingService.cycle(kamA, existingInteractionA).next().interactionId()).isEqualTo(next.id());
        assertThat(card.snapshots(kamA, next.id())).isEmpty();
        assertThat(card.status(kamA, next.id()).learning().state()).isEqualTo("OTHER_CYCLE");
        assertThatThrownBy(() -> mappingService.addRun(admin, firstRun, new SourceMappingRequest(null, ORGANIZATION_A,
                PROGRAM_JAVA, TODAY.minusDays(30), TODAY.plusDays(300), RunKind.STUDENTS)))
                .isInstanceOf(InteractionValidationException.class)
                .hasMessageContaining("пересекается");

        SourceMappingView secondRun = mappingService.addRun(admin, firstRun, new SourceMappingRequest(null, ORGANIZATION_A,
                PROGRAM_JAVA, TODAY.minusDays(5), TODAY.plusDays(300), RunKind.STUDENTS));

        assertThat(snapshotOfRun(firstRun)).isEqualTo(frozen);
        assertThat(card.snapshots(kamA, existingInteractionA)).singleElement()
                .extracting(CardSourcesService.LearningSnapshotView::mappingId).isEqualTo(firstRun);
        assertThat(card.snapshots(kamA, next.id())).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.mappingId()).isEqualTo(secondRun.id());
            assertThat(snapshot.participants()).isEqualTo(6);
        });
        assertThat(events(existingInteractionA)).isEqualTo(previousEvents);
        assertThat(lastComment(next.id())).startsWith("Данные LMS: курс «Демо: Java-разработчик», обучающихся 6");

        syncMoodle();

        assertThat(snapshotOfRun(firstRun)).isEqualTo(frozen);
        assertThat(events(next.id())).containsExactly("CREATED", "COMMENTED");
        assertThat(reportService.document(kamA, new ReportRequest(ReportKind.DEMAND, TODAY.minusDays(100),
                TODAY.minusDays(20), null, ReportFilters.none(), null, null, null, null)).rows())
                .extracting(ReportRow::programName, ReportRow::participants, ReportRow::parallelRuns)
                .containsExactly(tuple("Java-разработчик", 6L, 1L));

        jdbcTemplate.update("DELETE FROM learning_snapshots WHERE mapping_id = ?", secondRun.id());
        jdbcTemplate.update("UPDATE source_mappings SET run_starts_on = ? WHERE id = ?", TODAY.plusDays(10), secondRun.id());
        List<String> nextEvents = events(next.id());

        assertThat(syncMoodle().createdCount()).isZero();

        assertThat(recordStatusById(javaRecord)).isEqualTo("SKIPPED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learning_snapshots WHERE mapping_id = ?", Integer.class,
                secondRun.id())).isZero();
        assertThat(events(next.id())).isEqualTo(nextEvents);
        assertThat(snapshotOfRun(firstRun)).isEqualTo(frozen);
        assertThat(card.status(kamA, next.id()).learning()).satisfies(learning -> {
            assertThat(learning.state()).isEqualTo("RUN_ENDED");
            assertThat(learning.message()).contains("между потоками");
        });
    }

    @Test
    void administratorEditsMovesAndRemovesSavedMappingsWithSnapshotsAndSeesRunHistory() throws IOException {
        syncMoodle();
        UUID dataGroup = moodleRecordId("moodle_group", DATA_COURSE + ":" + groupId(DATA_COURSE, "crm-demo-data-1"));
        service.apply(admin, dataGroup, moodleRun(ORGANIZATION_B, PROGRAM_DATA));
        UUID interactionB = interactionIds(ORGANIZATION_B).getFirst();
        SourceMappingView saved = mappingService.mappings(admin).stream()
                .filter(view -> view.kind().equals("GROUP")).findFirst().orElseThrow();

        assertThat(saved.label()).startsWith("Группа «").contains("Демо: анализ данных");
        assertThat(List.of(saved.organizationName(), saved.programName(), saved.updatedByName()))
                .containsExactly("Университет Б", "Анализ данных", "Администратор");
        assertThat(saved.participants()).isEqualTo(4);
        assertThat(saved.runKind()).isEqualTo(RunKind.STUDENTS);
        assertThatThrownBy(() -> mappingService.mappings(kamA)).isInstanceOf(SourceException.class);
        assertThatThrownBy(() -> mappingService.update(admin, saved.id(), new SourceMappingRequest(saved.version() + 1,
                ORGANIZATION_A, PROGRAM_DATA, RUN_STARTS, RUN_ENDS, RunKind.STUDENTS)))
                .isInstanceOfSatisfying(SourceException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
                    assertThat(exception.currentVersion()).isEqualTo(saved.version());
                });

        SourceMappingView moved = mappingService.update(admin, saved.id(), new SourceMappingRequest(saved.version(),
                ORGANIZATION_A, PROGRAM_DATA, RUN_STARTS, RUN_ENDS.plusDays(30), RunKind.STUDENTS));

        assertThat(moved.organizationName()).isEqualTo("Университет А");
        assertThat(moved.version()).isEqualTo(saved.version() + 1);
        assertThat(moved.runEndsOn()).isEqualTo(RUN_ENDS.plusDays(30));
        assertThat(card.snapshots(kamB, interactionB)).isEmpty();
        UUID dataInteractionA = jdbcTemplate.queryForObject(
                "SELECT id FROM interactions WHERE organization_id = ? AND program_id = ?", UUID.class, ORGANIZATION_A, PROGRAM_DATA);
        assertThat(card.snapshots(kamA, dataInteractionA)).singleElement()
                .extracting(CardSourcesService.LearningSnapshotView::participants).isEqualTo(4);
        assertThat(demandParticipants(kamB)).isEmpty();
        assertThat(demandParticipants(kamA)).containsExactly(tuple("Анализ данных", 4L));

        mappingService.deleteSnapshot(admin, moved.id());

        assertThat(card.snapshots(kamA, dataInteractionA)).isEmpty();
        assertThat(card.status(kamA, dataInteractionA).learning().state()).isEqualTo("NOT_SYNCED");
        assertThatThrownBy(() -> mappingService.deleteSnapshot(admin, moved.id())).isInstanceOf(InteractionValidationException.class);
        assertThat(syncMoodle().createdCount()).isEqualTo(1);
        assertThatThrownBy(() -> mappingService.remove(admin, moved.id(), moved.version() - 1))
                .isInstanceOfSatisfying(SourceException.class,
                        exception -> assertThat(exception.code()).isEqualTo("VERSION_CONFLICT"));

        mappingService.remove(admin, moved.id(), moved.version());

        assertThat(recordStatusById(dataGroup)).isEqualTo("NEEDS_MAPPING");
        assertThat(recordStatusById(moodleRecordId("moodle_course", Long.toString(DATA_COURSE)))).isEqualTo("NEEDS_MAPPING");
        assertThat(service.problemRecords(admin)).extracting(SourceRecordView::id).contains(dataGroup);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learning_snapshots WHERE source_record_id = ?",
                Integer.class, dataGroup)).isZero();
        assertThat(mappingService.mappings(admin)).noneMatch(view -> view.id().equals(moved.id()));
        assertThat(service.runs(admin, SourceCode.MOODLE)).hasSize(2).allSatisfy(run -> {
            assertThat(run.trigger()).isEqualTo(SyncTrigger.MANUAL);
            assertThat(run.startedByName()).isEqualTo("Администратор");
        });
        assertThatThrownBy(() -> service.runs(kamA, SourceCode.MOODLE)).isInstanceOf(SourceException.class);
    }

    @Test
    void removedSiteMappingReturnsRecordsToReviewAndRemappingDoesNotDuplicateEvents() {
        sync();
        UUID application = problemId("la-2");
        service.apply(admin, application, new SourceRecordApplyRequest(ORGANIZATION_A, null, null, null));
        UUID partnershipInteraction = jdbcTemplate.queryForObject(
                "SELECT interaction_id FROM source_records WHERE external_id = 'pr-3'", UUID.class);
        List<String> eventsBefore = events(partnershipInteraction);
        assertThat(demandApplications(kamA)).isEqualTo(4);
        SourceMappingView organizationMapping = mappingService.mappings(admin).stream()
                .filter(view -> view.kind().equals("ORGANIZATION")).findFirst().orElseThrow();
        assertThat(organizationMapping.label()).isEqualTo("Вуз на сайте, внешний ID site-org-x");

        mappingService.remove(admin, organizationMapping.id(), organizationMapping.version());

        assertThat(service.problemRecords(admin)).extracting(SourceRecordView::externalId).containsExactlyInAnyOrder("la-2", "pr-3");
        assertThat(demandApplications(kamA)).isEqualTo(3);

        service.apply(admin, application, new SourceRecordApplyRequest(ORGANIZATION_A, null, null, null));

        assertThat(recordStatus("pr-3")).isEqualTo("APPLIED");
        assertThat(events(partnershipInteraction)).isEqualTo(eventsBefore);
        assertThat(demandApplications(kamA)).isEqualTo(4);
    }

    @Test
    void operatorUploadsPaidOrdersIntoOpenEnrolmentWithoutPersonalDataAndRepeatsWithoutDuplicates() {
        UUID openEnrolment = insertOpenEnrolment();
        UUID prompt = insertProgram("Промпт-инжиниринг");
        UUID tester = insertProgram("Инженер-тестировщик");
        UUID operator = UUID.fromString("00000000-0000-0000-0000-000000000031");
        insertProfile(operator, "Оператор зачисления Демо", "USER", TEAM_A);
        CrmProfile operatorProfile = new CrmProfile(operator, UserRole.USER, TEAM_A, 0);
        assertThatThrownBy(() -> paidOrderUploadService.upload(operatorProfile, paidOrdersFile(PAID_ORDERS), "upload-1", "rq"))
                .isInstanceOf(EnrolmentAccessDeniedException.class);
        assertThatThrownBy(() -> paidOrderUploadService.upload(admin, paidOrdersFile(PAID_ORDERS), "upload-1", "rq"))
                .isInstanceOf(EnrolmentAccessDeniedException.class);
        jdbcTemplate.update("UPDATE crm_user_profiles SET enrolment_operator = TRUE WHERE id = ?", operator);
        EnrolmentAccess moduleOff = new EnrolmentAccess(new ModuleProperties(new ModuleProperties.Toggle(false), null), userProfileRepository);
        assertThat(moduleOff.isOperator(operator)).isFalse();
        assertThat(enrolmentAccess.isOperator(operator)).isTrue();
        assertThatThrownBy(() -> new PaidOrderUploadService(moduleOff, paidOrderParser, service, commandIdempotencyRepository,
                paidOrderEnrolment, objectMapper).upload(operatorProfile, paidOrdersFile(PAID_ORDERS), "upload-off", "rq"))
                .isInstanceOf(ModuleDisabledException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_records WHERE record_type = 'paid_order'", Integer.class))
                .isZero();

        assertThat(paidOrderUploadService.last(operatorProfile)).isEmpty();
        PaidOrderUpload first = paidOrderUploadService.upload(operatorProfile, paidOrdersFile(PAID_ORDERS), "upload-1", "rq-upload-1");

        assertThat(paidOrderUploadService.upload(operatorProfile, paidOrdersFile(PAID_ORDERS), "upload-1", "rq-upload-1")).isEqualTo(first);
        assertThat(List.of(first.received(), first.emptyElements(), first.created(), first.updated(), first.skipped(),
                first.needsMapping(), first.failed())).containsExactly(6, 1, 5, 0, 0, 1, 0);
        assertThat(first.unmappedCourses()).containsExactly(LONG_COURSE);
        assertThat(first.streams())
                .extracting(PaidOrderUpload.Stream::course, PaidOrderUpload.Stream::streamNo, PaidOrderUpload.Stream::orders)
                .containsExactly(tuple("Инженер-тестировщик", 1, 2), tuple("Промпт-инжиниринг", 3, 3), tuple(LONG_COURSE, 2, 1));
        assertThat(first.issues()).extracting(issue -> issue.position()).containsExactly(1);
        assertThat(jdbcTemplate.queryForList("SELECT payload FROM source_records WHERE record_type = 'paid_order'", String.class))
                .hasSize(6)
                .noneMatch(payload -> payload.contains("Первова") || payload.contains("Анна") || payload.contains("example.test")
                        || payload.contains("EXAMPLE.TEST") || payload.contains("+7900") || payload.contains("(900)"));
        assertThat(jdbcTemplate.queryForMap("""
                SELECT organization_id, program_id, stream_no, status FROM source_records
                WHERE external_id = 'ORD-20261701000004-DEMO04'
                """))
                .containsEntry("ORGANIZATION_ID", openEnrolment)
                .containsEntry("PROGRAM_ID", tester)
                .containsEntry("STREAM_NO", 1)
                .containsEntry("STATUS", "APPLIED");
        assertThat(jdbcTemplate.queryForObject("SELECT program_id FROM source_records WHERE external_id = 'ORD-20260901000002-DEMO02'",
                UUID.class)).isEqualTo(prompt);
        SyncRunView run = service.runs(admin, SourceCode.WEBSITE).getFirst();
        assertThat(run.trigger()).isEqualTo(SyncTrigger.UPLOAD);
        assertThat(run.startedByName()).isEqualTo("Оператор зачисления Демо");
        assertThat(run.errorMessage()).contains("пустых элементов пропущено: 1");
        assertThat(service.sources(admin)).filteredOn(source -> source.source() == SourceCode.WEBSITE).singleElement()
                .satisfies(source -> {
                    assertThat(source.lastSuccessAt()).isNull();
                    assertThat(source.lastRun()).isNull();
                });
        assertThat(watermark()).isNull();

        UUID course = problemId("ORD-20260901000005-DEMO05");
        assertThat(service.problemRecords(admin)).filteredOn(record -> record.id().equals(course)).singleElement()
                .satisfies(record -> {
                    assertThat(record.recordType()).isEqualTo("paid_order");
                    assertThat(record.organizationName()).isEqualTo("Открытый набор (физлица)");
                    assertThat(record.programName()).isEqualTo(LONG_COURSE);
                    assertThat(record.streamNo()).isEqualTo(2);
                    assertThat(record.error()).contains("не сопоставлен с программой");
                });
        assertThat(reviewService.pending(leader())).extracting(PendingSourceRecordView::recordType).doesNotContain("paid_order");
        assertThatThrownBy(() -> service.apply(admin, course, new SourceRecordApplyRequest(ORGANIZATION_A, null, null, null)))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("organizationId"));
        service.apply(admin, course, new SourceRecordApplyRequest(null, PROGRAM_DATA, null, null));
        assertThat(recordStatus("ORD-20260901000005-DEMO05")).isEqualTo("APPLIED");
        assertThat(demandApplications(kamA)).isZero();

        PaidOrderUpload repeat = paidOrderUploadService.upload(operatorProfile, paidOrdersFile(PAID_ORDERS), "upload-2", "rq-upload-2");

        assertThat(List.of(repeat.created(), repeat.updated(), repeat.skipped(), repeat.needsMapping(), repeat.failed()))
                .containsExactly(0, 0, 6, 0, 0);
        OffsetDateTime received = jdbcTemplate.queryForObject(
                "SELECT submitted_at FROM source_records WHERE external_id = 'ORD-20260901000002-DEMO02'", OffsetDateTime.class);
        PaidOrderUpload moved = paidOrderUploadService.upload(operatorProfile, paidOrdersFile("""
                [{"Номер заявки": "ORD-20260901000002-DEMO02", "Курс": "Промпт-инжиниринг", "Фамилия": "Второв", "Имя": "Борис",
                  "Телефон": "7 (900) 000-00-02", "Email": "boris@example.test", "Номер потока": 4}]
                """), "upload-3", "rq-upload-3");
        assertThat(moved.updated()).isEqualTo(1);
        assertThat(paidOrderUploadService.last(operatorProfile)).hasValueSatisfying(last -> assertThat(last.protocol()).isEqualTo(moved));
        assertThatThrownBy(() -> paidOrderUploadService.last(admin)).isInstanceOf(EnrolmentAccessDeniedException.class);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT stream_no, submitted_at FROM source_records WHERE external_id = 'ORD-20260901000002-DEMO02'
                """)).containsEntry("STREAM_NO", 4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT submitted_at FROM source_records WHERE external_id = 'ORD-20260901000002-DEMO02'", OffsetDateTime.class))
                .isEqualTo(received);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_records WHERE record_type = 'paid_order'", Integer.class))
                .isEqualTo(6);
    }

    @Test
    void paidOrdersFormSeparateDemandColumnsForEveryKamWithoutPeriod() {
        insertOpenEnrolment();
        insertProgram("Промпт-инжиниринг");
        insertProgram("Инженер-тестировщик");
        service.uploadPaidOrders(KAM_A, paidOrderParser.parse(new ByteArrayInputStream(PAID_ORDERS.getBytes(StandardCharsets.UTF_8))));
        ReportRequest all = new ReportRequest(ReportKind.DEMAND, null, null, null, ReportFilters.none(), null, null, null, null);
        ReportRequest oldPeriod = new ReportRequest(ReportKind.DEMAND, TODAY.minusYears(2), TODAY.minusYears(1), null,
                ReportFilters.none(), null, null, null, ReportColumn.PAID_ORDERS);
        ReportFilters universityOnly = new ReportFilters(List.of(ORGANIZATION_A), List.of(), false, List.of(), false, List.of(),
                false, List.of(), false, List.of(), null, List.of(), List.of(), ReportAgreementFilters.none(), List.of(), null);

        assertThat(reportService.document(kamB, all).rows())
                .extracting(ReportRow::programName, ReportRow::applications, ReportRow::paidOrders, ReportRow::paidStreams)
                .containsExactlyInAnyOrder(tuple("Промпт-инжиниринг", null, 3L, 1L), tuple("Инженер-тестировщик", null, 2L, 1L));
        assertThat(reportService.document(kamA, oldPeriod).rows())
                .extracting(ReportRow::programName, ReportRow::paidOrders)
                .containsExactly(tuple("Промпт-инжиниринг", 3L), tuple("Инженер-тестировщик", 2L));
        assertThat(reportService.document(kamA, new ReportRequest(ReportKind.DEMAND, null, null, null, universityOnly, null, null,
                null, null)).rows()).isEmpty();
        assertThat(reportService.preview(kamA, all, 0, 50).columns()).extracting(ReportColumnView::title)
                .contains("Оплаченные заявки (сайт, без ограничения периода)", "Потоки с оплатами (сайт, без ограничения периода)");
        assertThat(demandApplications(kamA)).isZero();
    }

    @Test
    void uploadedOrdersEnterLearnersInTheRecordTransactionEvenBeforeTheCourseIsMappedAndRepeatOnlyCompletes() {
        insertOpenEnrolment();
        insertProgram("Промпт-инжиниринг");
        insertProgram("Инженер-тестировщик");
        UUID operator = UUID.fromString("00000000-0000-0000-0000-000000000031");
        insertProfile(operator, "Оператор зачисления Демо", "USER", TEAM_A);
        jdbcTemplate.update("UPDATE crm_user_profiles SET enrolment_operator = TRUE WHERE id = ?", operator);
        CrmProfile operatorProfile = new CrmProfile(operator, UserRole.USER, TEAM_A, 0);

        PaidOrderUpload first = paidOrderUploadService.upload(operatorProfile, paidOrdersFile(PAID_ORDERS), "learners-1", "rq-learners-1");

        assertThat(first.learners()).isEqualTo(new LearnerIntake(5, 1, 6, 0, 0, 0, 0));
        assertThat(first.needsMapping()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM learner_enrolments e JOIN source_records r ON r.id = e.source_record_id
                WHERE r.status = 'NEEDS_MAPPING'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT course_name || ' / ' || stream_no FROM enrolment_streams ORDER BY course_name",
                String.class)).containsExactly("Инженер-тестировщик / 1", "Промпт-инжиниринг / 3", LONG_COURSE + " / 2");
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT created_by FROM learners", UUID.class)).containsExactly(operator);
        assertThat(jdbcTemplate.queryForList("""
                SELECT CAST(actor_profile_id AS VARCHAR(36)) || '|' || object_type || '|' || request_id || '|' || details
                FROM audit_events WHERE action = 'PAID_ORDERS_UPLOADED'
                """, String.class)).containsExactly(operator + "|SYNC_RUN|rq-learners-1|получено: 6, пустых элементов: 1; "
                + "новых слушателей: 5, найдено: 1, зачислений: 6, повторных заявок в потоке: 0, перенесено в другой поток: 0 "
                + "(после выгрузки в LMS: 0), контакты отличаются от анкеты: 0");
        StringBuilder dump = new StringBuilder();
        for (String table : List.of("learners", "learner_enrolments", "source_records", "audit_events", "sync_runs",
                "command_idempotency_records")) {
            jdbcTemplate.queryForList("SELECT * FROM " + table).forEach(row -> dump.append(row.values()));
        }
        assertThat(dump.toString()).doesNotContain("Первова", "Анна", "anna.pervova", "BORIS", "(900)", "9000000001");

        service.apply(admin, problemId("ORD-20260901000005-DEMO05"), new SourceRecordApplyRequest(null, PROGRAM_DATA, null, null));
        PaidOrderUpload repeat = paidOrderUploadService.upload(operatorProfile, paidOrdersFile(PAID_ORDERS), "learners-2", "rq-learners-2");

        assertThat(repeat.skipped()).isEqualTo(6);
        assertThat(repeat.learners()).isEqualTo(new LearnerIntake(0, 6, 0, 0, 0, 0, 0));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learners", Integer.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learner_enrolments", Integer.class)).isEqualTo(6);
    }

    @Test
    void siteArrayWithPaidOrdersWritesNothingWhileTheEnrolmentModuleIsOff() {
        insertOpenEnrolment();
        PAGES.put(1, PAID_ORDERS);
        PAGES.remove(2);
        doReturn(false).when(paidOrderEnrolment).enabled();

        SyncRunView run = sync();

        assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(counters(run)).containsExactly(0, 0, 0, 0, 0, 0);
        assertThat(run.errorMessage()).contains("модуль «Слушатели» выключен");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_records", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learners", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learner_enrolments", Integer.class)).isZero();
    }

    @Test
    void siteArrayResponseIsParsedAsPaidOrdersWithoutMovingTheWatermark() {
        insertOpenEnrolment();
        PAGES.put(1, PAID_ORDERS);
        PAGES.remove(2);

        SyncRunView run = sync();

        assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(counters(run)).containsExactly(6, 0, 0, 0, 6, 0);
        assertThat(run.errorMessage()).contains("пустых элементов пропущено: 1");
        assertThat(watermark()).isNull();
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT record_type FROM source_records", String.class))
                .containsExactly("paid_order");
        assertThat(jdbcTemplate.queryForList("SELECT payload FROM source_records", String.class))
                .noneMatch(payload -> payload.contains("Четвертов") || payload.contains("example.test"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learner_enrolments", Integer.class)).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learners WHERE created_by IS NULL", Integer.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT actor_profile_id, actor_display_name, object_id, details FROM audit_events WHERE action = 'LEARNERS_SYNCED'
                """))
                .containsEntry("ACTOR_PROFILE_ID", null)
                .containsEntry("ACTOR_DISPLAY_NAME", "Система")
                .containsEntry("OBJECT_ID", run.id())
                .hasEntrySatisfying("DETAILS", details -> assertThat((String) details)
                        .startsWith("запуск: " + run.id() + "; новых слушателей: 5, найдено: 1, зачислений: 6"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'PAID_ORDERS_UPLOADED'", Integer.class))
                .isZero();
    }

    @Test
    void leaderMapsUnknownUniversityOfPendingApplicationToOwnTeamOnly() {
        CrmProfile leaderA = leader();
        UUID organizationC = UUID.randomUUID();
        insertOrganization(organizationC, null, "Университет C", TEAM_A, null);
        sync();
        UUID application = problemId("la-2");

        assertThat(reviewService.pending(leaderA))
                .extracting(PendingSourceRecordView::externalId, PendingSourceRecordView::canResolve)
                .containsExactlyInAnyOrder(tuple("la-2", true), tuple("pr-3", true));
        assertThat(reviewService.pending(leaderA)).allSatisfy(record -> {
            assertThat(record.organizationName()).isEqualTo("Университет Икс");
            assertThat(record.error()).contains("не сопоставлен");
        });
        assertThat(reviewService.pending(kamA)).isEmpty();
        assertThat(reviewService.pending(admin)).isEmpty();
        assertThatThrownBy(() -> reviewService.resolve(kamA, application, new SourceRecordResolveRequest(ORGANIZATION_A)))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> reviewService.resolve(leaderA, application, new SourceRecordResolveRequest(ORGANIZATION_B)))
                .isInstanceOf(OrganizationNotFoundException.class);

        SourceRecordApplyResult resolved = reviewService.resolve(leaderA, application, new SourceRecordResolveRequest(organizationC));

        assertThat(resolved.record().status()).isEqualTo(SourceRecordStatus.APPLIED);
        assertThat(resolved.reappliedCount()).isEqualTo(1);
        assertThat(interactionIds(organizationC)).hasSize(1);
        assertThat(reviewService.pending(leaderA)).isEmpty();
        assertThat(reviewService.resolve(leaderA, application, new SourceRecordResolveRequest(organizationC)).record().status())
                .isEqualTo(SourceRecordStatus.APPLIED);
        assertThat(interactionIds(organizationC)).hasSize(1);

        PAGES.put(1, page(null, APPLICATION_A.replace("la-1", "la-9").replace("Java-разработчик", "Неизвестная программа")));
        sync();
        UUID unknownProgram = problemId("la-9");

        assertThat(reviewService.pending(kamA))
                .extracting(PendingSourceRecordView::externalId, PendingSourceRecordView::canResolve,
                        PendingSourceRecordView::crmOrganizationName)
                .containsExactly(tuple("la-9", false, "Университет А"));
        assertThat(reviewService.pending(kamB)).isEmpty();
        assertThatThrownBy(() -> reviewService.resolve(kamA, unknownProgram, new SourceRecordResolveRequest(ORGANIZATION_A)))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void leaderCreatesOrganizationFromPendingApplicationInOwnTeam() {
        CrmProfile leaderA = leader();
        sync();
        UUID application = problemId("la-2");
        assertThatThrownBy(() -> reviewService.createOrganization(kamA, application,
                new SourceOrganizationCreateRequest(null, "UNIVERSITY", null), "source-request"))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> reviewService.createOrganization(leaderA, application,
                new SourceOrganizationCreateRequest("Университет А", "UNIVERSITY", null), "source-request"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field()).isEqualTo("name"));
        assertThatThrownBy(() -> reviewService.createOrganization(admin, application,
                new SourceOrganizationCreateRequest(null, "UNIVERSITY", null), "source-request"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field()).isEqualTo("teamId"));
        assertThatThrownBy(() -> reviewService.createOrganization(leaderA, application,
                new SourceOrganizationCreateRequest(null, "FACTORY", null), "source-request"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field()).isEqualTo("type"));

        SourceOrganizationCreated created = reviewService.createOrganization(leaderA, application,
                new SourceOrganizationCreateRequest(null, "SCHOOL", null), "source-request");

        assertThat(created.organizationName()).isEqualTo("Университет Икс");
        assertThat(created.result().record().status()).isEqualTo(SourceRecordStatus.APPLIED);
        assertThat(created.result().reappliedCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT type, team_id, owner_manager_id FROM organizations WHERE id = ?",
                created.organizationId()))
                .containsEntry("type", "SCHOOL").containsEntry("team_id", TEAM_A).containsEntry("owner_manager_id", null);
        assertThat(interactionIds(created.organizationId())).hasSize(1);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT entity_type, action, entity_name, changes, request_id FROM catalog_change_events WHERE entity_id = ?",
                created.organizationId()))
                .containsEntry("entity_type", "ORGANIZATION").containsEntry("action", "CREATE")
                .containsEntry("entity_name", "Университет Икс").containsEntry("changes", "Тип: Школа; из заявки сайта")
                .containsEntry("request_id", "source-request");
        assertThat(reviewService.pending(leaderA)).isEmpty();
        assertThatThrownBy(() -> reviewService.createOrganization(leaderA, application,
                new SourceOrganizationCreateRequest("Другое название", "UNIVERSITY", null), "source-request"))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("CONFLICT"));
    }

    @Test
    void leaderOfAnotherTeamCannotTakeOverFailedApplicationOfMappedUniversity() {
        CrmProfile leaderA = leader();
        UUID leaderBId = UUID.fromString("00000000-0000-0000-0000-000000000022");
        insertProfile(leaderBId, "Виктор Ковалёв", "LEADER", TEAM_B);
        CrmProfile leaderB = new CrmProfile(leaderBId, UserRole.LEADER, TEAM_B, 0);
        sync();
        UUID application = problemId("la-2");
        service.apply(admin, application, new SourceRecordApplyRequest(ORGANIZATION_A, null, null, null));
        SourceMappingView mapping = mappingService.mappings(admin).stream()
                .filter(view -> view.kind().equals("ORGANIZATION")).findFirst().orElseThrow();

        siteRecordApplier.markFailed(application, "Запись не применена: проверка");

        assertThat(jdbcTemplate.queryForObject("SELECT organization_id FROM source_records WHERE id = ?", UUID.class, application))
                .isEqualTo(ORGANIZATION_A);
        assertThat(reviewService.pending(kamA))
                .extracting(PendingSourceRecordView::externalId, PendingSourceRecordView::canResolve)
                .containsExactly(tuple("la-2", false));
        assertThat(reviewService.pending(leaderB)).isEmpty();

        jdbcTemplate.update("UPDATE source_records SET organization_id = NULL WHERE id = ?", application);

        assertThat(reviewService.pending(leaderB)).isEmpty();
        assertThat(reviewService.pending(leaderA))
                .extracting(PendingSourceRecordView::externalId, PendingSourceRecordView::canResolve,
                        PendingSourceRecordView::crmOrganizationName)
                .containsExactly(tuple("la-2", false, "Университет А"));
        assertThatThrownBy(() -> reviewService.resolve(leaderB, application, new SourceRecordResolveRequest(ORGANIZATION_B)))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> reviewService.createOrganization(leaderB, application,
                new SourceOrganizationCreateRequest("Университет Икс Б", "UNIVERSITY", null), "source-request"))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> reviewService.resolve(leaderA, application, new SourceRecordResolveRequest(ORGANIZATION_A)))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("CONFLICT"));
        assertThatThrownBy(() -> reviewService.createOrganization(admin, application,
                new SourceOrganizationCreateRequest("Университет Икс Б", "UNIVERSITY", TEAM_B), "source-request"))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("CONFLICT"));
        assertThat(mappingService.mappings(admin)).filteredOn(view -> view.kind().equals("ORGANIZATION")).singleElement()
                .satisfies(view -> {
                    assertThat(view.organizationId()).isEqualTo(ORGANIZATION_A);
                    assertThat(view.version()).isEqualTo(mapping.version());
                });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations WHERE name = 'Университет Икс Б'",
                Integer.class)).isZero();

        service.apply(admin, application, new SourceRecordApplyRequest(ORGANIZATION_A, null, null, null));

        assertThat(recordStatusById(application)).isEqualTo("APPLIED");
        assertThatThrownBy(() -> mappingService.remove(admin, mapping.id(), mapping.version()))
                .isInstanceOfSatisfying(SourceException.class,
                        exception -> assertThat(exception.code()).isEqualTo("VERSION_CONFLICT"));
    }

    @Test
    void cardRunsDoNotHideFailedOrStaleFullSyncFromAdministratorNorCancelFirstSync() {
        repository.insertMapping(SourceCode.MOODLE, "COURSE", Long.toString(JAVA_COURSE), ORGANIZATION_A, PROGRAM_JAVA,
                new SourceRepository.RunDates(RUN_STARTS, RUN_ENDS), RunKind.STUDENTS, ADMIN, OffsetDateTime.now());
        assertThat(card.refreshLearning(kamA, existingInteractionA).run().status()).isEqualTo(SyncRunStatus.SUCCEEDED);

        SourceView beforeFirstSync = sourceView(SourceCode.MOODLE);

        assertThat(beforeFirstSync.lastRun()).isNull();
        assertThat(beforeFirstSync.lastSuccessAt()).isNull();
        assertThat(beforeFirstSync.stale()).isTrue();
        assertThat(service.synchronizeIfNeverSucceeded(ADMIN, SourceCode.MOODLE)).get()
                .extracting(SyncRunView::status).isEqualTo(SyncRunStatus.SUCCEEDED);

        MOODLE_STATUS.set(500);
        assertThat(syncMoodle().status()).isEqualTo(SyncRunStatus.FAILED);
        MOODLE_STATUS.set(200);
        jdbcTemplate.update("UPDATE sync_runs SET finished_at = ? WHERE status = 'SUCCEEDED'", OffsetDateTime.now().minusHours(30));
        assertThat(card.refreshLearning(kamA, existingInteractionA).run().status()).isEqualTo(SyncRunStatus.SUCCEEDED);

        SourceView moodle = sourceView(SourceCode.MOODLE);

        assertThat(moodle.lastRun().status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(moodle.lastRun().trigger()).isEqualTo(SyncTrigger.MANUAL);
        assertThat(moodle.lastSuccessAt()).isBefore(OffsetDateTime.now().minusHours(29));
        assertThat(moodle.stale()).isTrue();
        assertThat(card.status(kamA, existingInteractionA).lms().stale()).isFalse();
        assertThat(service.runs(admin, SourceCode.MOODLE)).extracting(SyncRunView::trigger)
                .containsExactlyInAnyOrder(SyncTrigger.CARD, SyncTrigger.BOOTSTRAP, SyncTrigger.MANUAL, SyncTrigger.CARD);
    }

    @Test
    void cardShowsSourceStateExactReasonAndRefreshesBothSourcesWithinItsOrganization() {
        InteractionSourceStatus initial = card.status(kamA, existingInteractionA);
        assertThat(initial.lms().configured()).isTrue();
        assertThat(initial.lms().lastSuccessAt()).isNull();
        assertThat(initial.lms().stale()).isTrue();
        assertThat(initial.learning().state()).isEqualTo("NOT_MAPPED");
        UUID withoutProgram = interactionService.create(kamA,
                new InteractionCreateRequest(ORGANIZATION_A, "Без программы", null, null, List.of()), "no-program").id();
        assertThat(card.status(kamA, withoutProgram).learning().state()).isEqualTo("NO_PROGRAM");
        assertThatThrownBy(() -> card.status(kamB, existingInteractionA)).isInstanceOf(InteractionNotFoundException.class);

        syncMoodle();
        UUID dataInteraction = interactionService.create(kamA, new InteractionCreateRequest(ORGANIZATION_A, "Анализ данных",
                null, null, List.of(), PROGRAM_DATA, List.of(), null, null), "data-a").id();
        repository.insertMapping(SourceCode.MOODLE, "COURSE", "999", ORGANIZATION_A, PROGRAM_DATA,
                new SourceRepository.RunDates(RUN_STARTS, RUN_ENDS), RunKind.STUDENTS, ADMIN, OffsetDateTime.now());
        assertThat(card.status(kamA, dataInteraction).learning().state()).isEqualTo("NOT_SYNCED");
        service.apply(admin, moodleRecordId("moodle_course", Long.toString(JAVA_COURSE)), moodleRun(ORGANIZATION_A, PROGRAM_JAVA));

        InteractionSourceStatus mapped = card.status(kamA, existingInteractionA);
        assertThat(mapped.learning().state()).isEqualTo("AVAILABLE");
        assertThat(mapped.lms().lastSuccessAt()).isNotNull();
        assertThat(mapped.lms().stale()).isFalse();
        assertThat(mapped.lms().dataObservedAt()).isNotNull();
        assertThat(mapped.site().lastSuccessAt()).isNull();

        CardSourcesService.SourcesRefresh refreshed = card.refresh(kamA, existingInteractionA);

        assertThat(refreshed.lms().status()).isEqualTo("UNCHANGED");
        assertThat(refreshed.site().status()).isEqualTo("UPDATED");
        assertThat(refreshed.site().changedCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("SELECT external_id FROM source_records WHERE source = 'WEBSITE'", String.class))
                .containsExactlyInAnyOrder("pr-1", "la-1");
        assertThat(watermark()).isNull();
        assertThat(refreshed.status().site().lastSuccessAt()).isNotNull();
        assertThat(demandApplications(kamA)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForMap("SELECT run_trigger, organization_id, started_by FROM sync_runs WHERE source = 'WEBSITE'"))
                .containsEntry("run_trigger", "CARD").containsEntry("organization_id", ORGANIZATION_A).containsEntry("started_by", KAM_A);
        assertThat(lastComment(existingInteractionA)).startsWith("Заявка с сайта: Хотим подключить курс");
        assertThatThrownBy(() -> card.refresh(kamB, existingInteractionA)).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> card.refresh(admin, existingInteractionA)).isInstanceOf(InteractionNotFoundException.class);

        MOODLE_STATUS.set(500);
        FAILING_PAGES.put(1, 500);
        CardSourcesService.SourcesRefresh failed = card.refresh(kamA, existingInteractionA);

        assertThat(failed.lms().status()).isEqualTo("FAILED");
        assertThat(failed.lms().message()).contains("прежние значения сохранены");
        assertThat(failed.site().status()).isEqualTo("FAILED");
        assertThat(failed.status().lms().errorCode()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(failed.status().learning().state()).isEqualTo("AVAILABLE");
        assertThat(failed.snapshots()).hasSize(1);
        List<SourceView> sources = service.sources(admin);
        assertThat(sources).extracting(SourceView::schedule).containsOnly("0 0 * * * *");
        assertThat(sources.getFirst().nextRunAt()).isAfter(OffsetDateTime.now());
        assertThat(sourceView(SourceCode.MOODLE).nextRunAt()).isAfter(OffsetDateTime.now());

        jdbcTemplate.update("UPDATE sync_runs SET finished_at = ? WHERE status = 'SUCCEEDED'", OffsetDateTime.now().minusHours(30));

        assertThat(card.status(kamA, existingInteractionA).lms().stale()).isTrue();
        assertThat(sourceView(SourceCode.MOODLE).stale()).isTrue();
    }

    @Test
    void teacherTrainingIsRecordedWithDocumentAndNextCycleReminder() {
        Interaction interaction = interactionService.get(kamA, existingInteractionA);
        UUID attachmentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO attachments (id, interaction_id, stage_id, original_name, media_type, size_bytes, storage_key,
                                         checksum, status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, 'udostoverenie.pdf', 'application/pdf', 10, ?, ?, 'CLEAN', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, attachmentId, existingInteractionA, interaction.currentStageId(), UUID.randomUUID(), "0".repeat(64), KAM_A);
        TeacherTrainingRequest request = new TeacherTrainingRequest(interaction.version(), interaction.currentStageId(),
                TODAY.minusDays(3), "Работа с продуктом", 5, 4, attachmentId, TODAY.plusYears(3), true);

        TeacherTrainingCreated created = trainingService.createTraining(kamA, existingInteractionA, request, "tt-1");

        assertThat(List.of(created.training().courseName(), created.training().attachmentName()))
                .containsExactly("Работа с продуктом", "udostoverenie.pdf");
        assertThat(List.of(created.training().enrolledCount(), created.training().completedCount())).containsExactly(5, 4);
        assertThat(created.interaction().nextAction()).isEqualTo("Следующий цикл повышения квалификации преподавателей");
        assertThat(created.interaction().nextActionAt().atZoneSameInstant(ReportRequest.ZONE).toLocalDate())
                .isEqualTo(TODAY.plusYears(3));
        assertThat(lastComment(existingInteractionA)).startsWith("Обучение преподавателей: курс «Работа с продуктом», дата ")
                .contains("записано 5, завершили 4. Документ о повышении квалификации приложен. Следующий цикл");
        assertThat(jdbcTemplate.queryForObject("SELECT event_id FROM attachments WHERE id = ?", UUID.class, attachmentId))
                .isEqualTo(created.training().eventId());
        assertThat(trainingService.createTraining(kamA, existingInteractionA, request, "tt-1").training().id())
                .isEqualTo(created.training().id());
        assertThat(trainingService.trainings(kamA, existingInteractionA)).hasSize(1);
        assertThatThrownBy(() -> trainingService.createTraining(kamA, existingInteractionA, request, "tt-2"))
                .isInstanceOfSatisfying(InteractionConflictException.class,
                        exception -> assertThat(exception.code()).isEqualTo("VERSION_CONFLICT"));
        assertThatThrownBy(() -> trainingService.createTraining(kamA, existingInteractionA, new TeacherTrainingRequest(
                created.interaction().version(), interaction.currentStageId(), TODAY, "Курс", 3, 4, null, null, false), "tt-3"))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("completedCount"));
        assertThatThrownBy(() -> trainingService.createTraining(kamA, existingInteractionA, new TeacherTrainingRequest(
                created.interaction().version(), interaction.currentStageId(), TODAY.plusDays(1), "Курс", 3, 1, null, null,
                false), "tt-4"))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("trainedOn"));
        assertThatThrownBy(() -> trainingService.createTraining(kamB, existingInteractionA, request, "tt-5"))
                .isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> trainingService.trainings(kamB, existingInteractionA)).isInstanceOf(InteractionNotFoundException.class);
    }

    private UUID insertOpenEnrolment() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO organizations (id, external_key, name, type, team_id, owner_manager_id, version, updated_at)
                VALUES (?, 'open-enrolment', 'Открытый набор (физлица)', 'OPEN_ENROLLMENT', ?, NULL, 0, CURRENT_TIMESTAMP)
                """, id, UUID.randomUUID());
        return id;
    }

    private UUID insertProgram(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO programs (id, direction_id, name, archived, version) VALUES (?, ?, ?, FALSE, 0)",
                id, DIRECTION, name);
        return id;
    }

    private static MockMultipartFile paidOrdersFile(String json) {
        return new MockMultipartFile("file", "orders.json", "application/json",
                json.getBytes(StandardCharsets.UTF_8));
    }

    private CrmProfile leader() {
        UUID leaderId = UUID.fromString("00000000-0000-0000-0000-000000000021");
        insertProfile(leaderId, "Галина Лебедева", "LEADER", TEAM_A);
        return new CrmProfile(leaderId, UserRole.LEADER, TEAM_A, 0);
    }

    private UUID problemId(String externalId) {
        return service.problemRecords(admin).stream().filter(record -> record.externalId().equals(externalId))
                .findFirst().orElseThrow().id();
    }

    private Map<String, Object> snapshotOfRun(UUID mappingId) {
        return jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE mapping_id = ?", mappingId);
    }

    private List<Tuple> demandParticipants(CrmProfile profile) {
        return reportService.document(profile, new ReportRequest(ReportKind.DEMAND, null, null, null, ReportFilters.none(), null,
                        null, null, null)).rows().stream()
                .filter(row -> row.participants() != null)
                .map(row -> tuple(row.programName(), row.participants()))
                .toList();
    }

    private List<Tuple> parallelRuns(LocalDate asOf) {
        return reportService.document(kamA, new ReportRequest(ReportKind.DEMAND, null, asOf, null, ReportFilters.none(), null,
                        null, null, null)).rows().stream()
                .map(row -> tuple(row.programName(), row.parallelRuns()))
                .toList();
    }

    private static SourceRecordApplyRequest moodleRun(UUID organizationId, UUID programId) {
        return new SourceRecordApplyRequest(organizationId, programId, RUN_STARTS, RUN_ENDS);
    }

    private SourceSyncService liveService(String token, List<Long> courses) {
        SourceProperties properties = new SourceProperties(1, 1, "-", Duration.ofHours(26), null, null, new SourceProperties.Moodle(
                System.getenv("MOODLE_TEST_URL"), token, courses, List.of("student"), List.of("editingteacher", "teacher"),
                Duration.ofSeconds(5), Duration.ofSeconds(20), DataSize.ofMegabytes(5)), null);
        MoodleClient moodleClient = new MoodleClient(properties, objectMapper, 14);
        SourceSettingsService settings = new SourceSettingsService(properties, sourceSettingsRepository,
                new SourceTokenCipher(properties), auditJournalRepository, moodleClient, siteApiClient,
                new CrmClient(properties, objectMapper), event -> { }, null);
        return new SourceSyncService(repository, siteRecordApplier, crmRecordApplier, crmExchangeService, paidOrderEnrolment,
                siteApiClient, moodleSnapshotApplier, moodleClient, laborMarketSync, sourceSyncExecutor, properties, settings,
                objectMapper);
    }

    private SyncRunView sync() {
        UUID runId = service.start(admin, SourceCode.WEBSITE).runId();
        return repository.findRun(runId).orElseThrow().run();
    }

    private SourceView sourceView(SourceCode code) {
        return service.sources(admin).stream().filter(source -> source.source() == code).findFirst().orElseThrow();
    }

    private SyncRunView syncLaborMarket() {
        UUID runId = service.start(admin, SourceCode.LABOR_MARKET).runId();
        return repository.findRun(runId).orElseThrow().run();
    }

    private static String laborItem(String code, String name, YearMonth month, int vacancies) {
        return "{\"directionCode\":\"" + code + "\",\"directionName\":\"" + name + "\",\"month\":\"" + month
                + "\",\"openVacancies\":" + vacancies + ",\"medianSalary\":150000,\"dataSource\":\"Тестовый источник\","
                + "\"asOf\":\"" + month.atEndOfMonth() + "\"}";
    }

    private static String laborBody(String... items) {
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private List<Tuple> marketColumns() {
        return reportService.document(kamA, new ReportRequest(ReportKind.DEMAND, null, null, null, ReportFilters.none(), null, null,
                        null, null)).rows().stream()
                .map(row -> tuple(row.programName(), row.marketVacancies(), row.marketTrend()))
                .toList();
    }

    private SyncRunView syncCrm() {
        UUID runId = service.start(admin, SourceCode.CUSTOMER_CRM).runId();
        return repository.findRun(runId).orElseThrow().run();
    }

    private static String crmOrganization(String id, String name, String type, String inn, String updatedAt) {
        ObjectNode node = JSON.createObjectNode().put("externalId", id).put("name", name).put("type", type)
                .put("region", "Москва").put("updatedAt", updatedAt);
        if (inn != null) {
            node.put("inn", inn);
        }
        return node.toString();
    }

    private static String crmContact(String id, String organizationId, String fullName, String email, String phone, String updatedAt) {
        ObjectNode node = JSON.createObjectNode().put("externalId", id).put("organizationExternalId", organizationId)
                .put("fullName", fullName).put("position", "Проректор").put("role", "SIGNATORY").put("updatedAt", updatedAt);
        if (email != null) {
            node.put("workEmail", email);
        }
        if (phone != null) {
            node.put("workPhone", phone);
        }
        return node.toString();
    }

    private void insertAgreementStatusEvent(UUID agreementId, String fromStatus, String toStatus, OffsetDateTime occurredAt) {
        jdbcTemplate.update("""
                INSERT INTO agreement_status_events (id, agreement_id, from_status, to_status, actor_profile_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), agreementId, fromStatus, toStatus, KAM_A, occurredAt);
    }

    private void insertContact(UUID organizationId, String name, String email, String status, String position) {
        jdbcTemplate.update("""
                INSERT INTO contacts (id, personal_data_status, organization_id, name, position, email, version, created_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), status, organizationId, name, position, email, KAM_A);
    }

    private UUID crmRecord(String type, String externalId) {
        return jdbcTemplate.queryForObject("SELECT id FROM source_records WHERE source = 'CUSTOMER_CRM' AND record_type = ? AND external_id = ?",
                UUID.class, type, externalId);
    }

    private String crmStatus(String type, String externalId) {
        return jdbcTemplate.queryForObject("SELECT status FROM source_records WHERE source = 'CUSTOMER_CRM' AND record_type = ? AND external_id = ?",
                String.class, type, externalId);
    }

    private static List<String> crmEventIds() {
        return CRM_BATCHES.stream()
                .flatMap(batch -> StreamSupport.stream(batch.path("events").spliterator(), false))
                .map(event -> event.path("eventId").asText())
                .toList();
    }

    private static HttpServer startCrm() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(CrmClient.ORGANIZATIONS_PATH, exchange -> respondCrmList(exchange, CRM_ORGANIZATIONS));
            server.createContext(CrmClient.CONTACTS_PATH, exchange -> respondCrmList(exchange, CRM_CONTACTS));
            server.createContext(CrmClient.EVENTS_PATH, SourceSyncServiceTest::receiveCrmEvents);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void respondCrmList(HttpExchange exchange, List<String> items) throws IOException {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        CRM_QUERIES.add(exchange.getRequestURI().getPath() + "?" + rawQuery);
        if (!("Bearer " + CRM_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            sendJson(exchange, 401, "{}");
            return;
        }
        if (CRM_LIST_STATUS.get() != 200) {
            sendJson(exchange, CRM_LIST_STATUS.get(), "{}");
            return;
        }
        Map<String, String> query = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            query.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        int page = Integer.parseInt(query.get("page"));
        OffsetDateTime since = query.containsKey("updated_since") ? OffsetDateTime.parse(query.get("updated_since")) : null;
        List<String> matching = items.stream().filter(item -> {
            try {
                return since == null || !OffsetDateTime.parse(JSON.readTree(item).path("updatedAt").asText()).isBefore(since);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }).toList();
        int from = Math.min((page - 1) * 2, matching.size());
        int to = Math.min(page * 2, matching.size());
        sendJson(exchange, 200, page(to < matching.size() ? page + 1 : null, matching.subList(from, to).toArray(String[]::new)));
    }

    private static void receiveCrmEvents(HttpExchange exchange) throws IOException {
        byte[] request = exchange.getRequestBody().readAllBytes();
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{}");
            return;
        }
        if (!("Bearer " + CRM_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            sendJson(exchange, 401, "{}");
            return;
        }
        if (CRM_EVENTS_STATUS.get() != 200) {
            sendJson(exchange, CRM_EVENTS_STATUS.get(), "{}");
            return;
        }
        JsonNode body = JSON.readTree(request);
        CRM_BATCHES.add(body);
        CRM_BATCH_KEYS.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        ObjectNode answer = JSON.createObjectNode();
        int accepted = 0;
        int duplicates = 0;
        for (JsonNode event : body.path("events")) {
            String id = event.path("eventId").asText();
            if (CRM_REJECTED.contains(id)) {
                answer.withArray("rejected").addObject().put("eventId", id).put("reason", "неизвестная организация");
            } else if (CRM_RECEIVED.add(id)) {
                accepted++;
            } else {
                duplicates++;
            }
        }
        sendJson(exchange, 200, answer.put("accepted", accepted).put("duplicates", duplicates).toString());
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private SyncRunView syncMoodle() {
        UUID runId = service.start(admin, SourceCode.MOODLE).runId();
        return repository.findRun(runId).orElseThrow().run();
    }

    private static List<Integer> counters(SyncRunView run) {
        return List.of(run.fetchedCount(), run.createdCount(), run.updatedCount(), run.skippedCount(),
                run.needsMappingCount(), run.failedCount());
    }

    private UUID moodleRecordId(String type, String externalId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM source_records WHERE source = 'MOODLE' AND record_type = ? AND external_id = ?",
                UUID.class, type, externalId);
    }

    private List<String> moodleStatuses(String type, String externalIdPattern) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM source_records WHERE source = 'MOODLE' AND record_type = ? AND external_id LIKE ?",
                String.class, type, externalIdPattern);
    }

    private String recordStatusById(UUID id) {
        return jdbcTemplate.queryForObject("SELECT status FROM source_records WHERE id = ?", String.class, id);
    }

    private <T> T snapshotValue(UUID recordId, String column, Class<T> type) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM learning_snapshots WHERE source_record_id = ?", type, recordId);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String completionUser(boolean completed) throws IOException {
        JsonNode statuses = JSON.readTree(fixture("completion-" + JAVA_COURSE + ".json"));
        return StreamSupport.stream(((Iterable<String>) statuses::fieldNames).spliterator(), false)
                .filter(user -> statuses.path(user).path("completionstatus").path("completed").asBoolean() == completed)
                .findFirst()
                .orElseThrow();
    }

    private static String completionResponse(boolean completed) throws IOException {
        return JSON.readTree(fixture("completion-" + JAVA_COURSE + ".json")).path(completionUser(completed)).toString();
    }

    private static long groupId(long courseId, String idnumber) throws IOException {
        for (JsonNode group : JSON.readTree(fixture("groups-" + courseId + ".json"))) {
            if (idnumber.equals(group.path("idnumber").asText())) {
                return group.path("id").asLong();
            }
        }
        throw new IllegalStateException("Group " + idnumber + " is absent in the Moodle fixture");
    }

    private static String fixture(String name) {
        try (InputStream input = SourceSyncServiceTest.class.getResourceAsStream(MOODLE_FIXTURES + name)) {
            if (input == null) {
                throw new IllegalStateException("Moodle fixture " + name + " is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void respondMoodle(HttpExchange exchange) throws IOException {
        Map<String, String> form = new HashMap<>();
        for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            int separator = pair.indexOf('=');
            form.put(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        MOODLE_REQUESTS.add(form);
        MOODLE_QUERIES.add(String.valueOf(exchange.getRequestURI().getRawQuery()));
        int status = MOODLE_FAIL_NEXT.getAndUpdate(left -> Math.max(0, left - 1)) > 0 ? 503 : MOODLE_STATUS.get();
        byte[] bytes = (status == 200 ? moodleBody(form) : "{}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String moodleBody(Map<String, String> form) throws IOException {
        String function = form.get("wsfunction");
        String course = form.get("courseid");
        boolean withEndedEnrolments = "core_enrol_get_enrolled_users".equals(function) && "0".equals(form.get("options[0][value]"));
        String override = MOODLE_BODIES.get(function + ":" + course + (withEndedEnrolments ? ":all" : ""));
        if (override == null && withEndedEnrolments) {
            override = MOODLE_BODIES.get(function + ":" + course);
        }
        if (override != null) {
            return override;
        }
        return switch (function) {
            case "core_course_get_courses_by_field" -> fixture("courses.json");
            case "core_group_get_course_groups" -> fixture("groups-" + course + ".json");
            case "core_enrol_get_enrolled_users" -> fixture("users-" + course + ".json");
            case "core_completion_get_course_completion_status" -> COMPLETION_OVERRIDES.getOrDefault(form.get("userid"),
                    JSON.readTree(fixture("completion-" + course + ".json")).path(form.get("userid")).toString());
            default -> fixture("error-function-not-in-service.json");
        };
    }

    private long demandApplications(CrmProfile profile) {
        return reportService.statistics(profile, new ReportRequest(ReportKind.DEMAND, null, null, null,
                ReportFilters.none(), null, null, StatisticsGroupBy.PROGRAM, null)
                .statisticsRequest()).total();
    }

    private List<UUID> interactionIds(UUID organizationId) {
        return jdbcTemplate.queryForList("SELECT id FROM interactions WHERE organization_id = ? ORDER BY created_at, id",
                UUID.class, organizationId);
    }

    private List<String> events(UUID interactionId) {
        return jdbcTemplate.queryForList("SELECT type FROM interaction_events WHERE interaction_id = ? ORDER BY version, occurred_at",
                String.class, interactionId);
    }

    private String lastComment(UUID interactionId) {
        return jdbcTemplate.queryForObject("""
                SELECT comment FROM interaction_events WHERE interaction_id = ? AND type = 'COMMENTED'
                ORDER BY version DESC LIMIT 1
                """, String.class, interactionId);
    }

    private List<String> linkedContactNames(UUID interactionId) {
        return jdbcTemplate.queryForList("""
                SELECT c.name FROM interaction_contacts ic JOIN contacts c ON c.id = ic.contact_id
                WHERE ic.interaction_id = ? ORDER BY c.name
                """, String.class, interactionId);
    }

    private String recordStatus(String externalId) {
        return jdbcTemplate.queryForObject("SELECT status FROM source_records WHERE external_id = ?", String.class, externalId);
    }

    private OffsetDateTime watermark() {
        return jdbcTemplate.queryForObject("SELECT updated_since FROM sources WHERE code = 'WEBSITE'", OffsetDateTime.class);
    }

    private static String page(Integer nextPage, String... items) {
        return "{\"items\":[" + String.join(",", items) + "],\"nextPage\":" + nextPage + "}";
    }

    private static HttpServer startSite() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(SiteApiClient.RECORDS_PATH, SourceSyncServiceTest::respond);
            server.createContext(MoodleClient.REST_PATH, SourceSyncServiceTest::respondMoodle);
            server.createContext(LaborMarketClient.DEMAND_PATH, SourceSyncServiceTest::respondLaborMarket);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void respondLaborMarket(HttpExchange exchange) throws IOException {
        LABOR_QUERIES.add(exchange.getRequestURI().getRawQuery());
        boolean authorized = ("Bearer " + LABOR_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"));
        String body = LABOR_BODY.get();
        int status = !authorized ? 401 : body == null ? 503 : 200;
        byte[] bytes = (status == 200 ? body : "{}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        QUERIES.add(query);
        AUTHORIZATIONS.add(exchange.getRequestHeaders().getFirst("Authorization"));
        int page = Integer.parseInt(query.replaceAll(".*page=(\\d+).*", "$1"));
        Integer failure = FAILING_PAGES.get(page);
        String body = PAGES.get(page);
        int status = failure != null ? failure : body == null ? 404 : 200;
        byte[] bytes = status == 200 ? body.getBytes(StandardCharsets.UTF_8) : "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void insertProfile(UUID id, String name, String role, UUID teamId) {
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, display_name, role, team_id, active, access_revision, updated_at)
                VALUES (?, ?, ?, ?, TRUE, 0, CURRENT_TIMESTAMP)
                """, id, name, role, teamId);
    }

    private void insertOrganization(UUID id, String externalKey, String name, UUID teamId, UUID ownerManagerId) {
        jdbcTemplate.update("""
                INSERT INTO organizations (id, external_key, name, type, team_id, owner_manager_id, version, updated_at)
                VALUES (?, ?, ?, 'UNIVERSITY', ?, ?, 0, CURRENT_TIMESTAMP)
                """, id, externalKey, name, teamId, ownerManagerId);
    }

    private void insertDefaultTemplate() {
        UUID templateId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO workflow_templates (id, team_id, name, default_template, version, created_at, updated_at)
                VALUES (?, NULL, 'Базовый процесс', TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, templateId);
        List<UUID> stageIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<String> names = List.of("Поиск контакта", "Встреча");
        for (int order = 0; order < stageIds.size(); order++) {
            jdbcTemplate.update("""
                    INSERT INTO workflow_template_stages (id, template_id, stage_order, name, optional)
                    VALUES (?, ?, ?, ?, FALSE)
                    """, stageIds.get(order), templateId, order, names.get(order));
        }
        jdbcTemplate.update("""
                INSERT INTO workflow_template_transitions (template_id, from_stage_id, to_stage_id, comment_required)
                VALUES (?, ?, ?, FALSE)
                """, templateId, stageIds.get(0), stageIds.get(1));
    }

    private void createSchema() {
        List.of(
                """
                CREATE TABLE IF NOT EXISTS catalog_change_events (
                    id UUID PRIMARY KEY, entity_type VARCHAR(16) NOT NULL, entity_id UUID NOT NULL,
                    action VARCHAR(16) NOT NULL, entity_name VARCHAR(300) NOT NULL, changes VARCHAR(2000),
                    actor_profile_id UUID NOT NULL, actor_display_name VARCHAR(200) NOT NULL,
                    request_id VARCHAR(64) NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL UNIQUE, version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, archived BOOLEAN DEFAULT FALSE NOT NULL, default_workflow_template_id UUID
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (role_synced_at TIMESTAMP WITH TIME ZONE, last_login_at TIMESTAMP WITH TIME ZONE, role_reconcile_required BOOLEAN DEFAULT FALSE NOT NULL, partner_organization_id UUID, partner_contact_id UUID, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY, login VARCHAR(200), idp_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    activation_requested_at TIMESTAMP WITH TIME ZONE, anonymized_at TIMESTAMP WITH TIME ZONE, display_name VARCHAR(200) NOT NULL, role VARCHAR(16) NOT NULL, team_id UUID,
                    active BOOLEAN NOT NULL, access_revision INTEGER NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY, external_key VARCHAR(200) UNIQUE, name VARCHAR(300) NOT NULL UNIQUE,
                    type VARCHAR(16) NOT NULL, team_id UUID NOT NULL, owner_manager_id UUID, version INTEGER NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, city VARCHAR(200), website VARCHAR(300), inn VARCHAR(12)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organization_deputies (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, deputy_profile_id UUID NOT NULL,
                    deputy_display_name VARCHAR(200) NOT NULL, starts_on DATE NOT NULL, ends_on DATE NOT NULL,
                    starts_at TIMESTAMP WITH TIME ZONE NOT NULL, ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    command_id UUID NOT NULL, actor_profile_id UUID NOT NULL, actor_display_name VARCHAR(200) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, ended_at TIMESTAMP WITH TIME ZONE,
                    ended_by_profile_id UUID, ended_by_display_name VARCHAR(200)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS contacts (
                    decision_role VARCHAR(32), primary_contact BOOLEAN DEFAULT FALSE NOT NULL, inactive BOOLEAN DEFAULT FALSE NOT NULL, confirmed_at TIMESTAMP WITH TIME ZONE, confirmed_by UUID,
                    id UUID PRIMARY KEY, personal_data_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', external_key VARCHAR(200) UNIQUE, organization_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL, position VARCHAR(200), email VARCHAR(320), phone VARCHAR(50),
                    version INTEGER NOT NULL, created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS directions (
                    id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL UNIQUE, archived BOOLEAN NOT NULL, version INTEGER NOT NULL,
                    labor_market BOOLEAN NOT NULL DEFAULT FALSE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS labor_market_settings (
                    id SMALLINT PRIMARY KEY, base_url VARCHAR(500), token_encrypted VARCHAR(2000),
                    token_changed_at TIMESTAMP WITH TIME ZONE, updated_by UUID NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS labor_market_snapshots (
                    external_code VARCHAR(100) NOT NULL, period_month DATE NOT NULL, direction_name VARCHAR(300) NOT NULL,
                    open_vacancies INTEGER NOT NULL, median_salary INTEGER, data_source VARCHAR(200) NOT NULL, as_of DATE NOT NULL,
                    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL, sync_run_id UUID, PRIMARY KEY (external_code, period_month)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS labor_market_mappings (
                    direction_id UUID PRIMARY KEY, external_code VARCHAR(100) NOT NULL, origin VARCHAR(8) NOT NULL,
                    version INTEGER NOT NULL DEFAULT 0, updated_by UUID, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS programs (
                    id UUID PRIMARY KEY, direction_id UUID NOT NULL, name VARCHAR(200) NOT NULL, archived BOOLEAN NOT NULL,
                    version INTEGER NOT NULL, UNIQUE (direction_id, name)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS vendors (
                    id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL, archived BOOLEAN NOT NULL DEFAULT FALSE,
                    version INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS products (
                    id UUID PRIMARY KEY, vendor_contact_id UUID, vendor_id UUID NOT NULL, name VARCHAR(200) NOT NULL, archived BOOLEAN NOT NULL,
                    version INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS vendor_contacts (
                    id UUID PRIMARY KEY, vendor_id UUID NOT NULL, name VARCHAR(200) NOT NULL, phone VARCHAR(16),
                    email VARCHAR(320), prefers_email BOOLEAN DEFAULT FALSE NOT NULL,
                    prefers_telegram BOOLEAN DEFAULT FALSE NOT NULL, archived BOOLEAN DEFAULT FALSE NOT NULL,
                    personal_data_status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, external_key VARCHAR(200) UNIQUE,
                    version INTEGER DEFAULT 0 NOT NULL, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_issues (last_comment VARCHAR(1000), last_comment_by UUID, last_comment_at TIMESTAMP WITH TIME ZONE,
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, kind VARCHAR(16) NOT NULL,
                    description VARCHAR(1000) NOT NULL, risk_level VARCHAR(16), responsible_profile_id UUID NOT NULL, due_on DATE,
                    status VARCHAR(16) DEFAULT 'OPEN' NOT NULL, resolution VARCHAR(1000), created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, resolved_by UUID, resolved_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interactions (workflow_template_id UUID, workflow_version INTEGER, next_step_partner_visible BOOLEAN DEFAULT FALSE NOT NULL, 
                    work_status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, work_status_reason VARCHAR(1000), waiting_on VARCHAR(16), waiting_note VARCHAR(500),
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL,
                    current_stage_id UUID NOT NULL, next_action VARCHAR(500), next_action_at TIMESTAMP WITH TIME ZONE,
                    program_id UUID, last_contact_at TIMESTAMP WITH TIME ZONE, version INTEGER NOT NULL,
                    created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS product_agreements (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, product_id UUID NOT NULL,
                    contract_number VARCHAR(200), license_signed BOOLEAN, license_expiry_year INTEGER,
                    transfer_status VARCHAR(160), scan_attachment_id UUID, version INTEGER DEFAULT 0 NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, archived_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_stages (template_stage_id UUID, 
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, stage_order INTEGER NOT NULL,
                    name VARCHAR(200) NOT NULL, optional BOOLEAN NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_stage_transitions (
                    interaction_id UUID NOT NULL, from_stage_id UUID NOT NULL, to_stage_id UUID NOT NULL,
                    comment_required BOOLEAN NOT NULL, PRIMARY KEY (interaction_id, from_stage_id, to_stage_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_contacts (
                    interaction_id UUID NOT NULL, organization_id UUID NOT NULL, contact_id UUID NOT NULL,
                    PRIMARY KEY (interaction_id, contact_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS workflow_template_versions (
                    template_id UUID NOT NULL, version_number INTEGER NOT NULL, name VARCHAR(200) NOT NULL,
                    content TEXT NOT NULL, change_comment VARCHAR(1000), created_by UUID,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (template_id, version_number)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS workflow_templates (
                    id UUID PRIMARY KEY, team_id UUID, name VARCHAR(200) NOT NULL, default_template BOOLEAN NOT NULL,
                    version INTEGER NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS workflow_template_stages (
                    id UUID PRIMARY KEY, template_id UUID NOT NULL, stage_order INTEGER NOT NULL,
                    name VARCHAR(200) NOT NULL, optional BOOLEAN NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS workflow_template_transitions (
                    template_id UUID NOT NULL, from_stage_id UUID NOT NULL, to_stage_id UUID NOT NULL,
                    comment_required BOOLEAN NOT NULL, PRIMARY KEY (template_id, from_stage_id, to_stage_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS command_idempotency_records (
                    id UUID PRIMARY KEY, actor_profile_id UUID NOT NULL, operation VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL, request_fingerprint CHAR(64) NOT NULL,
                    result_json VARCHAR(10000), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (actor_profile_id, operation, idempotency_key)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_events (contact_channel VARCHAR(16),
                    id UUID PRIMARY KEY, external_key VARCHAR(200) UNIQUE, interaction_id UUID NOT NULL,
                    command_id UUID NOT NULL, type VARCHAR(32) NOT NULL, stage_id UUID NOT NULL,
                    stage_name_snapshot VARCHAR(200) NOT NULL, from_stage_id UUID, from_stage_name_snapshot VARCHAR(200),
                    to_stage_id UUID, to_stage_name_snapshot VARCHAR(200), comment VARCHAR(4000),
                    plan_changed BOOLEAN NOT NULL DEFAULT FALSE, next_action VARCHAR(500), next_action_at TIMESTAMP WITH TIME ZONE,
                    actor_profile_id UUID NOT NULL, owner_manager_id_snapshot UUID, version INTEGER NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_event_contacts (
                    event_id UUID NOT NULL,
                    contact_id UUID NOT NULL,
                    change_type VARCHAR(16) NOT NULL,
                    PRIMARY KEY (event_id, contact_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organization_assignment_events (
                    reason VARCHAR(32), handover_note VARCHAR(2000),
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, command_id UUID NOT NULL,
                    previous_owner_manager_id UUID, previous_owner_manager_display_name VARCHAR(200),
                    owner_manager_id UUID, new_owner_manager_display_name VARCHAR(200), actor_profile_id UUID NOT NULL,
                    actor_display_name VARCHAR(200) NOT NULL, request_id VARCHAR(64) NOT NULL, version INTEGER NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_stage_completions (
                    interaction_id UUID NOT NULL, stage_id UUID NOT NULL, completed_on DATE NOT NULL,
                    comment VARCHAR(4000), event_id UUID NOT NULL, PRIMARY KEY (interaction_id, stage_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS attachments (partner_visible BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, stage_id UUID NOT NULL, event_id UUID,
                    original_name VARCHAR(255) NOT NULL, media_type VARCHAR(160) NOT NULL, size_bytes BIGINT NOT NULL,
                    storage_key UUID NOT NULL, checksum CHAR(64) NOT NULL, status VARCHAR(32) NOT NULL,
                    kind VARCHAR(32) DEFAULT 'OTHER' NOT NULL, revision INTEGER DEFAULT 1 NOT NULL, replaces_id UUID,
                    version INTEGER DEFAULT 0 NOT NULL, deleted_at TIMESTAMP WITH TIME ZONE, deleted_by UUID,
                    created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS product_transfers (
                    agreement_id UUID NOT NULL, kind VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL,
                    transferred_on DATE, attachment_id UUID, updated_by UUID NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (agreement_id, kind)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS agreements (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, number VARCHAR(100) NOT NULL, concluded_on DATE,
                    valid_until DATE, parties VARCHAR(2000), status VARCHAR(16) NOT NULL, file_attachment_id UUID,
                    version INTEGER NOT NULL DEFAULT 0, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS agreement_status_events (
                    id UUID PRIMARY KEY, agreement_id UUID NOT NULL, from_status VARCHAR(16), to_status VARCHAR(16) NOT NULL,
                    comment VARCHAR(2000), actor_profile_id UUID, interaction_id UUID,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS crm_exchange_events (
                    id UUID PRIMARY KEY, event_key VARCHAR(200) NOT NULL UNIQUE, kind VARCHAR(24) NOT NULL,
                    organization_id UUID NOT NULL, interaction_id UUID, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    payload VARCHAR(100000) NOT NULL, status VARCHAR(8) NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL, last_error VARCHAR(500), batch_id UUID,
                    sent_at TIMESTAMP WITH TIME ZONE, created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS sources (
                    code VARCHAR(16) PRIMARY KEY, updated_since TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS sync_runs (
                    id UUID PRIMARY KEY, source VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL, started_by UUID NOT NULL,
                    updated_since TIMESTAMP WITH TIME ZONE, fetched_count INTEGER NOT NULL DEFAULT 0,
                    created_count INTEGER NOT NULL DEFAULT 0, updated_count INTEGER NOT NULL DEFAULT 0,
                    skipped_count INTEGER NOT NULL DEFAULT 0, needs_mapping_count INTEGER NOT NULL DEFAULT 0,
                    failed_count INTEGER NOT NULL DEFAULT 0, error_code VARCHAR(64), error_message VARCHAR(500),
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    started_at TIMESTAMP WITH TIME ZONE, finished_at TIMESTAMP WITH TIME ZONE,
                    run_trigger VARCHAR(16) NOT NULL DEFAULT 'MANUAL', organization_id UUID
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS source_records (stream_no INTEGER, payload_hash CHAR(64), applied_at TIMESTAMP WITH TIME ZONE, contact_id UUID, 
                    id UUID PRIMARY KEY, source VARCHAR(16) NOT NULL, record_type VARCHAR(64) NOT NULL,
                    external_id VARCHAR(200) NOT NULL, external_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL, external_status VARCHAR(64), payload VARCHAR(100000) NOT NULL,
                    status VARCHAR(16) NOT NULL, error VARCHAR(500), organization_id UUID, program_id UUID,
                    applications_count INTEGER NOT NULL DEFAULT 1, interaction_id UUID, sync_run_id UUID,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (source, record_type, external_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS source_mappings (
                    id UUID PRIMARY KEY, source VARCHAR(16) NOT NULL, kind VARCHAR(16) NOT NULL,
                    external_key VARCHAR(310) NOT NULL, organization_id UUID, program_id UUID, run_starts_on DATE,
                    run_ends_on DATE, run_kind VARCHAR(16) NOT NULL DEFAULT 'STUDENTS', version INTEGER NOT NULL DEFAULT 0,
                    created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS learning_snapshots (
                    mapping_id UUID PRIMARY KEY, source_record_id UUID NOT NULL, interaction_id UUID,
                    organization_id UUID NOT NULL, program_id UUID NOT NULL,
                    course_id BIGINT NOT NULL, group_id BIGINT, course_name VARCHAR(1333) NOT NULL, group_name VARCHAR(300),
                    participants_count INTEGER NOT NULL, teachers_count INTEGER NOT NULL, completed_count INTEGER,
                    not_completed_count INTEGER, unknown_count INTEGER NOT NULL, groups_count INTEGER NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    changed_at TIMESTAMP WITH TIME ZONE NOT NULL, sync_run_id UUID,
                    active_7d_count INTEGER, active_30d_count INTEGER, never_accessed_count INTEGER,
                    inactive_count INTEGER, teachers_active_7d_count INTEGER, teachers_active_14d_count INTEGER
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS learning_observations (
                    mapping_id UUID NOT NULL, observed_from TIMESTAMP WITH TIME ZONE NOT NULL,
                    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL, participants_count INTEGER NOT NULL,
                    teachers_count INTEGER NOT NULL, completed_count INTEGER, not_completed_count INTEGER,
                    unknown_count INTEGER NOT NULL, groups_count INTEGER NOT NULL, demo BOOLEAN NOT NULL DEFAULT FALSE,
                    PRIMARY KEY (mapping_id, observed_from),
                    active_7d_count INTEGER, active_30d_count INTEGER, never_accessed_count INTEGER,
                    inactive_count INTEGER, teachers_active_7d_count INTEGER, teachers_active_14d_count INTEGER
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS teacher_trainings (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, event_id UUID NOT NULL UNIQUE, trained_on DATE NOT NULL,
                    course_name VARCHAR(300) NOT NULL, enrolled_count INTEGER NOT NULL, completed_count INTEGER,
                    attachment_id UUID, next_cycle_on DATE, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS source_settings (
                    id SMALLINT PRIMARY KEY, moodle_base_url VARCHAR(500), moodle_token_encrypted VARCHAR(2000),
                    moodle_token_changed_at TIMESTAMP WITH TIME ZONE, moodle_course_ids VARCHAR(2000) NOT NULL,
                    moodle_student_roles VARCHAR(500) NOT NULL, moodle_teacher_roles VARCHAR(500) NOT NULL,
                    website_base_url VARCHAR(500), website_token_encrypted VARCHAR(2000),
                    website_token_changed_at TIMESTAMP WITH TIME ZONE, sync_cron VARCHAR(100), crm_base_url VARCHAR(500),
                    crm_token_encrypted VARCHAR(2000), crm_token_changed_at TIMESTAMP WITH TIME ZONE,
                    updated_by UUID NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
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
                CREATE TABLE IF NOT EXISTS enrolment_streams (
                    id UUID PRIMARY KEY, course_key VARCHAR(310) NOT NULL, course_name VARCHAR(1333) NOT NULL,
                    stream_no INTEGER NOT NULL, ends_on DATE, version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (course_key, stream_no)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS learners (
                    id UUID PRIMARY KEY, key_version VARCHAR(16), fields TEXT, email_hmac CHAR(64), phone_hmac CHAR(64),
                    snils_hmac CHAR(64) UNIQUE, name_hmac CHAR(64), last_name_hmac CHAR(64),
                    missing_fields VARCHAR(600) NOT NULL DEFAULT '', personal_data_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    anonymized_at TIMESTAMP WITH TIME ZONE, version INTEGER NOT NULL DEFAULT 0, created_by UUID,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS learner_enrolments (lms_synced_version INTEGER, lms_reconcile_reason VARCHAR(500),
                    id UUID PRIMARY KEY, learner_id UUID NOT NULL, stream_id UUID NOT NULL, source_record_id UUID NOT NULL UNIQUE,
                    lms_export_id UUID, lms_exported_at TIMESTAMP WITH TIME ZONE, lms_transferred_at TIMESTAMP WITH TIME ZONE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (learner_id, stream_id)
                )
                """,
                EnrolmentBatchTestSchema.BATCHES,
                EnrolmentBatchTestSchema.ITEMS,
                """
                CREATE TABLE IF NOT EXISTS interaction_cycles (
                    interaction_id UUID PRIMARY KEY, previous_interaction_id UUID NOT NULL UNIQUE, starts_on DATE NOT NULL,
                    created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """
        ).forEach(jdbcTemplate::execute);
    }

    @Test
    void customerCrmOrganizationsAndContactsMatchByIdThenInnThenNameAndRepeatDoesNotDuplicate() {
        UUID dup1 = UUID.randomUUID();
        UUID dup2 = UUID.randomUUID();
        insertOrganization(dup1, null, "Колледж Д1", TEAM_A, KAM_A);
        insertOrganization(dup2, null, "Колледж Д2", TEAM_A, KAM_A);
        jdbcTemplate.update("UPDATE organizations SET inn = '7700000001' WHERE id = ?", ORGANIZATION_A);
        jdbcTemplate.update("UPDATE organizations SET inn = '7700000003' WHERE id IN (?, ?)", dup1, dup2);
        CRM_ORGANIZATIONS.addAll(List.of(
                crmOrganization("c-1", "Университет А (CRM)", "UNIVERSITY", "7700000001", "2026-10-01T10:00:00+03:00"),
                crmOrganization("c-2", "  университет   Б ", "UNIVERSITY", null, "2026-10-02T10:00:00+03:00"),
                crmOrganization("c-3", "Колледж Д", "COLLEGE", "7700000003", "2026-10-03T10:00:00+03:00"),
                crmOrganization("c-4", "Школа № 7", "SCHOOL", null, "2026-10-04T10:00:00+03:00"),
                crmOrganization("c-5", "Плохая организация", "UNIVERSITY", "123", "2026-10-05T10:00:00+03:00")));
        CRM_CONTACTS.addAll(List.of(
                crmContact("k-1", "c-1", "Ольга Иванова", "olga@example.test", null, "2026-10-06T10:00:00+03:00"),
                crmContact("k-2", "c-3", "Игорь Колледжев", "igor@example.test", null, "2026-10-07T10:00:00+03:00"),
                crmContact("k-4", "c-unknown", "Нина Неизвестная", null, null, "2026-10-08T10:00:00+03:00"),
                crmContact("k-3", "c-1", "Пётр Сидоров", null, null, "2026-10-09T10:00:00+03:00")));

        SyncRunView first = syncCrm();

        assertThat(first.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(List.of(first.fetchedCount(), first.createdCount(), first.updatedCount(), first.skippedCount(),
                first.needsMappingCount(), first.failedCount())).containsExactly(9, 4, 0, 0, 4, 1);
        assertThat(CRM_QUERIES).filteredOn(query -> query.startsWith(CrmClient.ORGANIZATIONS_PATH))
                .extracting(query -> query.replaceAll(".*page=(\\d+).*", "$1")).containsExactly("1", "2", "3");
        assertThat(jdbcTemplate.queryForMap("SELECT city, inn FROM organizations WHERE id = ?", ORGANIZATION_A))
                .containsEntry("city", "Москва").containsEntry("inn", "7700000001");
        assertThat(jdbcTemplate.queryForObject("SELECT city FROM organizations WHERE id = ?", String.class, ORGANIZATION_B))
                .isEqualTo("Москва");
        assertThat(jdbcTemplate.queryForList("""
                SELECT external_key FROM source_mappings WHERE source = 'CUSTOMER_CRM' AND kind = 'ORGANIZATION' ORDER BY external_key
                """, String.class)).containsExactly("id:c-1", "id:c-2");
        assertThat(jdbcTemplate.queryForList("SELECT name FROM contacts WHERE organization_id = ? ORDER BY name", String.class,
                ORGANIZATION_A)).containsExactly("Ольга Иванова", "Пётр Сидоров");
        assertThat(jdbcTemplate.queryForObject("SELECT decision_role FROM contacts WHERE name = 'Ольга Иванова'", String.class))
                .isEqualTo("SIGNATORY");
        assertThat(crmStatus("crm_organization", "c-3")).isEqualTo("NEEDS_MAPPING");
        assertThat(jdbcTemplate.queryForObject("SELECT error FROM source_records WHERE external_id = 'c-3'", String.class))
                .contains("Найдено несколько организаций CRM по ИНН", "Колледж Д1", "Колледж Д2");
        assertThat(jdbcTemplate.queryForObject("SELECT error FROM source_records WHERE external_id = 'c-4'", String.class))
                .contains("нет в CRM");
        assertThat(jdbcTemplate.queryForObject("SELECT error FROM source_records WHERE external_id = 'c-5'", String.class))
                .contains("ИНН должен состоять из 10 или 12 цифр");
        assertThat(crmStatus("crm_contact", "k-2")).isEqualTo("NEEDS_MAPPING");
        assertThat(crmStatus("crm_contact", "k-4")).isEqualTo("NEEDS_MAPPING");
        assertThat(jdbcTemplate.queryForObject("SELECT updated_since FROM sources WHERE code = 'CUSTOMER_CRM'", OffsetDateTime.class))
                .isEqualTo(OffsetDateTime.parse("2026-10-09T10:00:00+03:00"));

        SyncRunView repeat = syncCrm();

        assertThat(List.of(repeat.fetchedCount(), repeat.createdCount(), repeat.updatedCount(), repeat.skippedCount(),
                repeat.needsMappingCount(), repeat.failedCount())).containsExactly(1, 0, 0, 1, 0, 0);
        assertThat(CRM_QUERIES.getLast()).contains("updated_since=2026-10-09T10%3A00%2B03%3A00");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contacts WHERE organization_id = ?", Integer.class,
                ORGANIZATION_A)).isEqualTo(2);

        CRM_ORGANIZATIONS.set(0, crmOrganization("c-1", "Университет А — новое название", "UNIVERSITY", null, "2026-10-10T10:00:00+03:00"));
        CRM_CONTACTS.set(0, crmContact("k-1", "c-1", "Ольга Иванова", "olga@example.test", "+7 900 000-00-01",
                "2026-10-10T11:00:00+03:00"));

        SyncRunView changed = syncCrm();

        assertThat(List.of(changed.fetchedCount(), changed.createdCount(), changed.updatedCount(), changed.skippedCount()))
                .containsExactly(3, 0, 2, 1);
        assertThat(jdbcTemplate.queryForObject("SELECT phone FROM contacts WHERE name = 'Ольга Иванова'", String.class))
                .isEqualTo("+7 900 000-00-01");
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM organizations WHERE id = ?", String.class, ORGANIZATION_A))
                .isEqualTo("Университет А");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_mappings WHERE source = 'CUSTOMER_CRM'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contacts WHERE organization_id = ?", Integer.class,
                ORGANIZATION_A)).isEqualTo(2);
    }

    @Test
    void ambiguousCrmRecordsAreResolvedByAdministratorThroughTheSameApplyFlow() {
        UUID dup1 = UUID.randomUUID();
        UUID dup2 = UUID.randomUUID();
        insertOrganization(dup1, null, "Колледж Д1", TEAM_A, KAM_A);
        insertOrganization(dup2, null, "Колледж Д2", TEAM_A, KAM_A);
        jdbcTemplate.update("UPDATE organizations SET inn = '7700000003' WHERE id IN (?, ?)", dup1, dup2);
        jdbcTemplate.update("UPDATE organizations SET inn = '7700000001' WHERE id = ?", ORGANIZATION_A);
        insertContact(ORGANIZATION_A, "Ольга Иванова", "dup@example.test", "ACTIVE", null);
        insertContact(ORGANIZATION_A, "Ольга И.", "DUP@example.test", "ACTIVE", null);
        CRM_ORGANIZATIONS.addAll(List.of(
                crmOrganization("c-1", "Университет А", "UNIVERSITY", "7700000001", "2026-10-01T10:00:00+03:00"),
                crmOrganization("c-3", "Колледж Д", "COLLEGE", "7700000003", "2026-10-03T10:00:00+03:00"),
                crmOrganization("c-4", "Школа № 7", "SCHOOL", null, "2026-10-04T10:00:00+03:00")));
        CRM_CONTACTS.addAll(List.of(
                crmContact("k-2", "c-3", "Игорь Колледжев", "igor@example.test", null, "2026-10-07T10:00:00+03:00"),
                crmContact("k-5", "c-1", "Ольга Иванова", "dup@example.test", null, "2026-10-08T10:00:00+03:00")));
        syncCrm();
        UUID ambiguousOrganization = crmRecord("crm_organization", "c-3");
        UUID waitingContact = crmRecord("crm_contact", "k-2");
        UUID ambiguousContact = crmRecord("crm_contact", "k-5");

        assertThat(crmStatus("crm_contact", "k-5")).isEqualTo("NEEDS_MAPPING");
        List<SourceRecordView> problems = service.problemRecords(admin);
        assertThat(problems).extracting(SourceRecordView::externalId).containsExactlyInAnyOrder("c-3", "c-4", "k-2", "k-5");
        SourceRecordView organizationView = problems.stream().filter(record -> record.id().equals(ambiguousOrganization)).findFirst().orElseThrow();
        assertThat(organizationView.candidates()).extracting(SourceMappingOption::name).containsExactlyInAnyOrder("Колледж Д1", "Колледж Д2");
        SourceRecordView contactView = problems.stream().filter(record -> record.id().equals(ambiguousContact)).findFirst().orElseThrow();
        assertThat(contactView.contactName()).isEqualTo("Ольга Иванова");
        assertThat(contactView.organizationId()).isEqualTo(ORGANIZATION_A);
        assertThat(contactView.candidates()).hasSize(2);

        SourceRecordApplyResult organization = service.apply(admin, ambiguousOrganization,
                new SourceRecordApplyRequest(dup1, null, null, null));

        assertThat(organization.record().status()).isEqualTo(SourceRecordStatus.APPLIED);
        assertThat(organization.record().organizationId()).isEqualTo(dup1);
        assertThat(organization.reappliedCount()).isEqualTo(1);
        assertThat(crmStatus("crm_contact", "k-2")).isEqualTo("APPLIED");
        assertThat(jdbcTemplate.queryForList("SELECT name FROM contacts WHERE organization_id = ?", String.class, dup1))
                .containsExactly("Игорь Колледжев");
        assertThat(waitingContact).isNotNull();

        UUID chosen = contactView.candidates().getFirst().id();
        SourceRecordApplyResult contact = service.apply(admin, ambiguousContact,
                new SourceRecordApplyRequest(null, null, null, null, null, chosen));

        assertThat(contact.record().status()).isEqualTo(SourceRecordStatus.APPLIED);
        assertThat(jdbcTemplate.queryForObject("SELECT contact_id FROM source_records WHERE id = ?", UUID.class, ambiguousContact))
                .isEqualTo(chosen);
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM contacts WHERE id = ?", String.class, chosen))
                .isEqualTo("Проректор");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contacts WHERE organization_id = ?", Integer.class,
                ORGANIZATION_A)).isEqualTo(2);
        assertThatThrownBy(() -> service.apply(admin, ambiguousContact,
                new SourceRecordApplyRequest(null, null, null, null, null, UUID.randomUUID())))
                .isInstanceOf(InteractionValidationException.class);

        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, 'Команда А')", TEAM_A);
        SourceOrganizationCreated created = reviewService.createOrganization(admin, crmRecord("crm_organization", "c-4"),
                new SourceOrganizationCreateRequest(null, null, TEAM_A), "request-crm");

        assertThat(created.organizationName()).isEqualTo("Школа № 7");
        assertThat(created.result().record().status()).isEqualTo(SourceRecordStatus.APPLIED);
        assertThat(jdbcTemplate.queryForObject("SELECT type FROM organizations WHERE id = ?", String.class, created.organizationId()))
                .isEqualTo("SCHOOL");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT external_key FROM source_mappings WHERE source = 'CUSTOMER_CRM' AND organization_id = ?
                """, String.class, created.organizationId())).isEqualTo("id:c-4");
        assertThat(service.problemRecords(admin)).isEmpty();
    }

    @Test
    void crmRecordsNeverChangeRestrictedOrAnonymizedContactsAndStayAwayFromLeaderQueue() {
        insertContact(ORGANIZATION_A, "Ольга Иванова", "olga@example.test", "RESTRICTED", "Ограничен");
        CRM_ORGANIZATIONS.add(crmOrganization("c-1", "Университет А", "UNIVERSITY", null, "2026-10-01T10:00:00+03:00"));
        CRM_CONTACTS.addAll(List.of(
                crmContact("k-1", "c-1", "Ольга Иванова", "olga@example.test", "+7 900 000-00-01", "2026-10-06T10:00:00+03:00"),
                crmContact("k-3", "c-1", "Пётр Сидоров", null, null, "2026-10-07T10:00:00+03:00")));

        syncCrm();

        assertThat(crmStatus("crm_contact", "k-1")).isEqualTo("SKIPPED");
        assertThat(jdbcTemplate.queryForMap("SELECT position, phone FROM contacts WHERE name = 'Ольга Иванова'"))
                .containsEntry("position", "Ограничен").containsEntry("phone", null);
        assertThat(jdbcTemplate.queryForObject("SELECT error FROM source_records WHERE external_id = 'k-1'", String.class))
                .contains("ограничена");
        jdbcTemplate.update("UPDATE contacts SET personal_data_status = 'ANONYMIZED', name = 'Обезличено', email = NULL WHERE name = 'Пётр Сидоров'");
        CRM_CONTACTS.set(1, crmContact("k-3", "c-1", "Пётр Сидоров", null, "+7 900 000-00-03", "2026-10-11T10:00:00+03:00"));

        SyncRunView changed = syncCrm();

        assertThat(changed.skippedCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contacts WHERE organization_id = ?", Integer.class,
                ORGANIZATION_A)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT phone FROM contacts WHERE name = 'Обезличено'", String.class)).isNull();
        CRM_ORGANIZATIONS.add(crmOrganization("c-9", "Школа № 9", "SCHOOL", null, "2026-10-12T10:00:00+03:00"));
        syncCrm();
        CrmProfile leader = new CrmProfile(UUID.randomUUID(), UserRole.LEADER, TEAM_A, 0);
        assertThat(reviewService.pending(leader)).isEmpty();
        assertThatThrownBy(() -> service.problemRecords(leader)).isInstanceOfSatisfying(SourceException.class,
                exception -> assertThat(exception.code()).isEqualTo("FORBIDDEN"));
        assertThatThrownBy(() -> reviewService.createOrganization(leader, crmRecord("crm_organization", "c-9"),
                new SourceOrganizationCreateRequest(null, null, TEAM_A), "request")).isInstanceOf(SourceException.class);
    }

    @Test
    void crmPreviewShowsOutcomesWithoutChangingAnything() {
        UUID dup1 = UUID.randomUUID();
        UUID dup2 = UUID.randomUUID();
        insertOrganization(dup1, null, "Колледж Д1", TEAM_A, KAM_A);
        insertOrganization(dup2, null, "Колледж Д2", TEAM_A, KAM_A);
        jdbcTemplate.update("UPDATE organizations SET inn = '7700000003' WHERE id IN (?, ?)", dup1, dup2);
        CRM_ORGANIZATIONS.addAll(List.of(
                crmOrganization("c-1", "Университет А", "UNIVERSITY", null, "2026-10-01T10:00:00+03:00"),
                crmOrganization("c-3", "Колледж Д", "COLLEGE", "7700000003", "2026-10-03T10:00:00+03:00"),
                crmOrganization("c-4", "Школа № 7", "SCHOOL", null, "2026-10-04T10:00:00+03:00"),
                crmOrganization("c-5", "Плохая", "UNIVERSITY", "123", "2026-10-05T10:00:00+03:00")));
        CRM_CONTACTS.addAll(List.of(
                crmContact("k-1", "c-1", "Ольга Иванова", null, null, "2026-10-06T10:00:00+03:00"),
                crmContact("k-2", "c-3", "Игорь Колледжев", null, null, "2026-10-07T10:00:00+03:00")));

        CrmPreview preview = crmExchangeService.preview(admin);

        assertThat(preview.organizations()).isEqualTo(4);
        assertThat(preview.contacts()).isEqualTo(2);
        assertThat(preview.counts()).extracting(CrmPreviewCount::outcome, CrmPreviewCount::count).containsExactlyInAnyOrder(
                tuple(CrmPreviewOutcome.FOUND, 1), tuple(CrmPreviewOutcome.AMBIGUOUS, 1), tuple(CrmPreviewOutcome.NOT_FOUND, 1),
                tuple(CrmPreviewOutcome.INVALID, 1), tuple(CrmPreviewOutcome.CREATE, 1),
                tuple(CrmPreviewOutcome.ORGANIZATION_UNRESOLVED, 1));
        assertThat(preview.items().getFirst().outcome()).isEqualTo(CrmPreviewOutcome.INVALID);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_records WHERE source = 'CUSTOMER_CRM'", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_mappings WHERE source = 'CUSTOMER_CRM'", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contacts WHERE name = 'Ольга Иванова'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT updated_since FROM sources WHERE code = 'CUSTOMER_CRM'", OffsetDateTime.class))
                .isNull();
        CrmProfile leader = new CrmProfile(UUID.randomUUID(), UserRole.LEADER, TEAM_A, 0);
        assertThatThrownBy(() -> crmExchangeService.preview(leader)).isInstanceOfSatisfying(SourceException.class,
                exception -> assertThat(exception.code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void crmEventsAreCollectedOnceSentInBatchesWithIdempotencyKeyAndWithoutPersonalData() {
        repository.insertMapping(SourceCode.CUSTOMER_CRM, "ORGANIZATION", "id:crm-a", ORGANIZATION_A, null, null, null, ADMIN,
                OffsetDateTime.now());
        jdbcTemplate.update("UPDATE organizations SET inn = '7700000001' WHERE id = ?", ORGANIZATION_A);
        insertContact(ORGANIZATION_A, "Ольга Иванова", "olga@example.test", "ACTIVE", null);
        UUID stage = jdbcTemplate.queryForObject("SELECT id FROM interaction_stages WHERE interaction_id = ? AND name = 'Встреча'",
                UUID.class, existingInteractionA);
        interactionService.transition(kamA, existingInteractionA, new InteractionTransitionRequest(
                interactionService.get(kamA, existingInteractionA).version(), stage, null, List.of(),
                new InteractionNextStep("Согласовать договор", OffsetDateTime.parse("2026-11-01T12:00:00+03:00"))),
                "crm-transition");
        UUID agreementId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO agreements (id, organization_id, number, concluded_on, status, created_by)
                VALUES (?, ?, 'ДЕМО-А/1', DATE '2026-09-01', 'ACTIVE', ?)
                """, agreementId, ORGANIZATION_A, KAM_A);
        insertAgreementStatusEvent(agreementId, null, "ACTIVE", OffsetDateTime.now());
        jdbcTemplate.update("""
                INSERT INTO organization_assignment_events (id, organization_id, command_id, previous_owner_manager_id,
                    previous_owner_manager_display_name, owner_manager_id, new_owner_manager_display_name, actor_profile_id,
                    actor_display_name, request_id, version, occurred_at)
                VALUES (?, ?, ?, ?, 'Борис Орлов', ?, 'Анна Смирнова', ?, 'Администратор', 'rq', 1, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), ORGANIZATION_A, UUID.randomUUID(), KAM_B, KAM_A, ADMIN);

        SyncRunView first = syncCrm();

        assertThat(first.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(first.errorMessage()).contains("добавлено в очередь 5", "отправлено 5");
        assertThat(CRM_BATCHES).hasSize(1);
        assertThat(CRM_BATCH_KEYS.getFirst()).matches("[0-9a-f-]{36}");
        assertThat(CRM_BATCHES.getFirst().path("batchId").asText()).isEqualTo(CRM_BATCH_KEYS.getFirst());
        JsonNode events = CRM_BATCHES.getFirst().path("events");
        assertThat(events).hasSize(5);
        assertThat(StreamSupport.stream(events.spliterator(), false).map(event -> event.path("kind").asText()))
                .containsExactlyInAnyOrder("STAGE_CHANGED", "STAGE_CHANGED", "NEXT_STEP_CHANGED", "MANAGER_CHANGED", "AGREEMENT_STATUS");
        JsonNode stageEvent = StreamSupport.stream(events.spliterator(), false)
                .filter(event -> event.path("stage").path("from").asText().equals("Поиск контакта")).findFirst().orElseThrow();
        assertThat(stageEvent.path("stage").path("to").asText()).isEqualTo("Встреча");
        assertThat(stageEvent.path("organization").path("externalId").asText()).isEqualTo("crm-a");
        assertThat(stageEvent.path("organization").path("inn").asText()).isEqualTo("7700000001");
        assertThat(stageEvent.path("work").path("title").asText()).isEqualTo("Курс Java");
        assertThat(stageEvent.path("work").path("program").asText()).isEqualTo("Java-разработчик");
        assertThat(stageEvent.path("manager").path("name").asText()).isEqualTo("Анна Смирнова");
        assertThat(stageEvent.path("occurredAt").asText()).endsWith("+03:00");
        JsonNode planEvent = StreamSupport.stream(events.spliterator(), false)
                .filter(event -> event.path("kind").asText().equals("NEXT_STEP_CHANGED")).findFirst().orElseThrow();
        assertThat(planEvent.path("nextStep").path("text").asText()).isEqualTo("Согласовать договор");
        assertThat(planEvent.path("nextStep").path("dueAt").asText()).isEqualTo("2026-11-01T12:00:00+03:00");
        JsonNode agreementEvent = StreamSupport.stream(events.spliterator(), false)
                .filter(event -> event.path("kind").asText().equals("AGREEMENT_STATUS")).findFirst().orElseThrow();
        assertThat(agreementEvent.path("agreement").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(agreementEvent.path("agreement").path("number").asText()).isEqualTo("ДЕМО-А/1");
        assertThat(CRM_BATCHES.getFirst().toString()).doesNotContain("@", "olga", "Ольга", "email", "phone", "contact");
        CrmEventList list = crmExchangeService.events(admin, null);
        assertThat(list.counts()).isEqualTo(new CrmEventCounts(0, 5, 0));
        assertThat(list.items()).extracting(CrmEventView::status).containsOnly(CrmEventStatus.SENT);
        assertThat(list.items()).extracting(CrmEventView::description).anyMatch(text -> text.equals("Этап: «Поиск контакта» → «Встреча»"))
                .anyMatch(text -> text.startsWith("Следующий шаг: Согласовать договор, срок 01.11.2026 12:00 (МСК)"))
                .anyMatch(text -> text.equals("Ответственный: Борис Орлов → Анна Смирнова"))
                .anyMatch(text -> text.equals("Договор № ДЕМО-А/1: «Действует»"));

        syncCrm();

        assertThat(CRM_BATCHES).hasSize(1);
        assertThat(crmExchangeService.events(admin, CrmEventStatus.SENT).counts().sent()).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_exchange_events", Integer.class)).isEqualTo(5);
    }

    @Test
    void crmEventsCarryEveryAgreementStatusTransitionIncludingReturns() {
        UUID agreementId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO agreements (id, organization_id, number, concluded_on, status, created_by)
                VALUES (?, ?, 'ДЕМО-КА/1', DATE '2026-10-01', 'TERMINATED', ?)
                """, agreementId, ORGANIZATION_A, KAM_A);
        List<String> statuses = Arrays.asList(null, "DRAFT", "REVIEW", "CORRECTION", "REVIEW", "SIGNING", "ACTIVE",
                "COMPLETED", "ACTIVE", "TERMINATED");
        OffsetDateTime start = OffsetDateTime.now().minusHours(2);
        for (int index = 1; index < statuses.size(); index++) {
            insertAgreementStatusEvent(agreementId, statuses.get(index - 1), statuses.get(index), start.plusMinutes(index));
        }

        SyncRunView run = syncCrm();

        assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        List<JsonNode> sent = StreamSupport.stream(CRM_BATCHES.getFirst().path("events").spliterator(), false)
                .filter(event -> event.path("kind").asText().equals("AGREEMENT_STATUS")).toList();
        assertThat(sent).extracting(event -> event.path("agreement").path("status").asText())
                .containsExactly("DRAFT", "REVIEW", "CORRECTION", "REVIEW", "SIGNING", "ACTIVE", "COMPLETED", "ACTIVE", "TERMINATED");
        assertThat(sent.getFirst().path("agreement").path("previousStatus").isNull()).isTrue();
        assertThat(sent.get(3).path("agreement").path("previousStatus").asText()).isEqualTo("CORRECTION");
        assertThat(sent).extracting(event -> event.path("eventId").asText()).doesNotHaveDuplicates();
        assertThat(crmExchangeService.events(admin, null).items()).extracting(CrmEventView::description)
                .contains("Договор № ДЕМО-КА/1: «Проект»",
                        "Договор № ДЕМО-КА/1: «На согласовании» → «На корректировке»",
                        "Договор № ДЕМО-КА/1: «На корректировке» → «На согласовании»",
                        "Договор № ДЕМО-КА/1: «На подписании» → «Действует»",
                        "Договор № ДЕМО-КА/1: «Завершён» → «Действует»",
                        "Договор № ДЕМО-КА/1: «Действует» → «Расторгнут»");

        syncCrm();

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_exchange_events WHERE kind = 'AGREEMENT_STATUS'",
                Integer.class)).isEqualTo(9);
    }

    @Test
    void failedCrmDeliveryIsRetriedWithPauseThenStopsAsErrorAndAdministratorRequeuesIt() {
        CRM_EVENTS_STATUS.set(500);

        SyncRunView first = syncCrm();

        assertThat(first.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(CRM_BATCHES).isEmpty();
        assertThat(jdbcTemplate.queryForMap("SELECT status, attempts, last_error FROM crm_exchange_events"))
                .containsEntry("status", "QUEUED").containsEntry("attempts", 1)
                .containsEntry("last_error", "CRM заказчика ответила HTTP 500");
        assertThat(jdbcTemplate.queryForObject("SELECT next_attempt_at FROM crm_exchange_events", OffsetDateTime.class))
                .isAfter(OffsetDateTime.now().plusSeconds(30));
        CRM_EVENTS_STATUS.set(200);

        syncCrm();

        assertThat(CRM_BATCHES).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT attempts FROM crm_exchange_events", Integer.class)).isEqualTo(1);
        CrmEventView waiting = crmExchangeService.events(admin, CrmEventStatus.QUEUED).items().getFirst();
        assertThat(waiting.nextAttemptAt()).isNotNull();
        assertThat(waiting.lastError()).contains("HTTP 500");

        CRM_EVENTS_STATUS.set(500);
        for (int attempt = 2; attempt <= 5; attempt++) {
            jdbcTemplate.update("UPDATE crm_exchange_events SET next_attempt_at = ?", OffsetDateTime.now().minusMinutes(1));
            syncCrm();
        }

        assertThat(jdbcTemplate.queryForMap("SELECT status, attempts FROM crm_exchange_events"))
                .containsEntry("status", "ERROR").containsEntry("attempts", 5);
        assertThat(crmExchangeService.events(admin, null).counts()).isEqualTo(new CrmEventCounts(0, 0, 1));
        CRM_EVENTS_STATUS.set(200);
        jdbcTemplate.update("UPDATE crm_exchange_events SET next_attempt_at = ?", OffsetDateTime.now().minusMinutes(1));
        syncCrm();
        assertThat(CRM_BATCHES).isEmpty();

        CrmProfile leader = new CrmProfile(UUID.randomUUID(), UserRole.LEADER, TEAM_A, 0);
        assertThatThrownBy(() -> crmExchangeService.requeue(leader, "request"))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("FORBIDDEN"));
        assertThatThrownBy(() -> crmExchangeService.events(kamA, null))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("FORBIDDEN"));
        assertThat(crmExchangeService.requeue(admin, "request-requeue").requeued()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events", String.class)).contains("CRM_EVENTS_REQUEUED");

        SyncRunView resent = syncCrm();

        assertThat(resent.errorMessage()).contains("отправлено 1");
        assertThat(jdbcTemplate.queryForMap("SELECT status, attempts FROM crm_exchange_events"))
                .containsEntry("status", "SENT").containsEntry("attempts", 1);
        assertThat(CRM_BATCHES).hasSize(1);
    }

    @Test
    void rejectedCrmEventStopsAsErrorWithReasonAndInboundFailureDoesNotBlockDelivery() {
        CRM_LIST_STATUS.set(500);
        CRM_REJECTED.add("stage:" + jdbcTemplate.queryForObject("SELECT id FROM interaction_events", UUID.class));

        SyncRunView failed = syncCrm();

        assertThat(failed.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(CRM_BATCHES).hasSize(1);
        assertThat(jdbcTemplate.queryForMap("SELECT status, last_error FROM crm_exchange_events"))
                .containsEntry("status", "ERROR")
                .containsEntry("last_error", "CRM заказчика не приняла событие: неизвестная организация");
        assertThat(crmExchangeService.events(admin, CrmEventStatus.ERROR).items()).singleElement()
                .satisfies(event -> assertThat(event.lastError()).contains("неизвестная организация"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SourceTestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder()
                    .findAndAddModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();
        }

        @Bean
        SourceProperties sourceProperties() {
            String baseUrl = "http://127.0.0.1:" + SITE.getAddress().getPort() + "/";
            return new SourceProperties(
                    1,
                    1,
                    "0 0 * * * *",
                    Duration.ofHours(26),
                    SETTINGS_KEY,
                    new SourceProperties.Website(baseUrl, TOKEN, Duration.ofSeconds(2), Duration.ofSeconds(5), 10,
                            DataSize.ofMegabytes(1)),
                    new SourceProperties.Moodle(baseUrl, MOODLE_TOKEN, List.of(JAVA_COURSE, DATA_COURSE), List.of("student"),
                            List.of("editingteacher", "teacher"), Duration.ofSeconds(2), Duration.ofSeconds(5),
                            DataSize.ofMegabytes(1)),
                    new SourceProperties.Crm("http://127.0.0.1:" + CRM.getAddress().getPort() + "/", CRM_TOKEN, Duration.ofSeconds(2),
                            Duration.ofSeconds(5), 10, DataSize.ofMegabytes(1))
            );
        }

        @Bean
        MoodleClient moodleClient(SourceProperties properties, ObjectMapper objectMapper) {
            return new MoodleClient(properties, objectMapper, Duration.ZERO);
        }

        @Bean
        LaborMarketProperties laborMarketProperties() {
            return new LaborMarketProperties("http://127.0.0.1:" + SITE.getAddress().getPort() + "/", LABOR_TOKEN,
                    Duration.ofSeconds(2), Duration.ofSeconds(5), DataSize.ofMegabytes(1), 100, 24);
        }

        @Bean
        ModuleProperties moduleProperties() {
            return new ModuleProperties(new ModuleProperties.Toggle(true), null);
        }

        @Bean
        EnrolmentProperties enrolmentProperties() {
            return new EnrolmentProperties("v1", Map.of("v1", ENROLMENT_KEY), ENROLMENT_FINGERPRINT_KEY);
        }

        @Bean
        ReportProperties reportProperties() throws IOException {
            Path root = Files.createTempDirectory("source-sync-test");
            return new ReportProperties(root, 1, 1, 1_000, 1_000);
        }

        @Bean
        SourceSyncExecutor sourceSyncExecutor(SourceProperties properties) {
            return new SourceSyncExecutor(properties) {
                @Override
                public void execute(Runnable task) {
                    task.run();
                }
            };
        }
    }
}
