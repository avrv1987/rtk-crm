package ru.rtk.crm.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.attachment.AttachmentRepository;
import ru.rtk.crm.catalog.CatalogRepository;
import ru.rtk.crm.catalog.ContactRepository;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.InteractionCreateRequest;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionRepository;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.interaction.WorkflowTemplateRepository;
import ru.rtk.crm.report.ReportFilters;
import ru.rtk.crm.report.ReportKind;
import ru.rtk.crm.report.ReportProperties;
import ru.rtk.crm.report.ReportRepository;
import ru.rtk.crm.report.ReportRequest;
import ru.rtk.crm.report.ReportRow;
import ru.rtk.crm.report.ReportService;
import ru.rtk.crm.report.StatisticsGroupBy;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
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
        SourceRepository.class,
        SiteRecordApplier.class,
        SiteApiClient.class,
        MoodleSnapshotApplier.class,
        MoodleClient.class,
        SourceSyncService.class,
        ReportRepository.class,
        ReportService.class,
        SourceSyncServiceTest.SourceTestConfiguration.class
})
class SourceSyncServiceTest {
    private static final String TOKEN = "test-token";
    private static final Map<Integer, String> PAGES = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> FAILING_PAGES = new ConcurrentHashMap<>();
    private static final List<String> QUERIES = new CopyOnWriteArrayList<>();
    private static final List<String> AUTHORIZATIONS = new CopyOnWriteArrayList<>();
    private static final String MOODLE_TOKEN = "moodle-test-token";
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
    private static final HttpServer SITE = startSite();

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
    private static final String WITHOUT_EXTERNAL_ID = """
            {"type":"learning_application","updatedAt":"2026-09-23T10:00:00+03:00"}
            """;

    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);
    private final CrmProfile kamA = new CrmProfile(KAM_A, UserRole.USER, TEAM_A, 0);
    private final CrmProfile kamB = new CrmProfile(KAM_B, UserRole.USER, TEAM_B, 0);

    @Autowired
    private SourceSyncService service;

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
    private MoodleSnapshotApplier moodleSnapshotApplier;

    @Autowired
    private SourceSyncExecutor sourceSyncExecutor;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID existingInteractionA;

    @AfterAll
    static void stopSite() {
        SITE.stop(0);
    }

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of(
                "learning_snapshots", "source_mappings", "source_records", "sync_runs", "sources", "interaction_events",
                "command_idempotency_records", "interaction_contacts", "product_agreements", "interaction_stage_transitions",
                "interaction_stages", "interactions", "workflow_template_transitions", "workflow_template_stages",
                "workflow_templates", "contacts", "products", "programs", "directions", "organizations", "crm_user_profiles"
        )) {
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
        jdbcTemplate.update("INSERT INTO sources (code) VALUES ('WEBSITE'), ('MOODLE')");
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

        SourceRecordApplyResult result = service.apply(admin, applicationId, new SourceRecordApplyRequest(ORGANIZATION_A, null, null, null));

        assertThat(result.record().status()).isEqualTo(SourceRecordStatus.APPLIED);
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
                tuple(SourceCode.MOODLE, true, true, false));
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
        jdbcTemplate.update("INSERT INTO products (id, vendor_id, name, archived, version) VALUES (?, ?, ?, FALSE, 0)",
                secureCommunications, vendor, "Демо-продукт: защищённая связь");
        jdbcTemplate.update("INSERT INTO products (id, vendor_id, name, archived, version) VALUES (?, ?, ?, FALSE, 0)",
                cloudPlatform, vendor, "Демо-продукт: облачная платформа");
        insertOrganization(organizationC, null, "Университет C — требует назначения", TEAM_A, null);
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
                run.needsMappingCount(), run.failedCount())).containsExactly(16, 10, 0, 2, 4, 0);
        assertThat(events(demoInteraction)).containsExactly("CREATED", "COMMENTED", "COMMENTED");
        assertThat(interactionIds(ORGANIZATION_B)).hasSize(1);
        assertThat(interactionIds(organizationC)).hasSize(1);
        assertThat(service.problemRecords(admin)).extracting(SourceRecordView::externalId)
                .containsExactlyInAnyOrder("pr-demo-004", "la-demo-104", "la-demo-107", "pr-demo-006");
        assertThat(demandApplications(kamA)).isEqualTo(19);
        assertThat(demandApplications(kamB)).isEqualTo(14);
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

        LearningSnapshotsApiController card = new LearningSnapshotsApiController(null, organizationRepository, repository, service);
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

        assertThat(withoutGroupAccess.errorCode()).isEqualTo("SOURCE_UNAUTHORIZED");
        assertThat(withoutGroupAccess.errorMessage()).contains("moodle/site:accessallgroups");
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", javaRecord))
                .isEqualTo(published);

        MOODLE_BODIES.clear();
        MOODLE_STATUS.set(500);
        SyncRunView unavailable = syncMoodle();

        assertThat(unavailable.errorCode()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", javaRecord))
                .isEqualTo(published);
        SourceView moodle = service.sources(admin).getLast();
        assertThat(moodle.lastRun().status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(moodle.lastSuccessAt()).isNotNull();

        MOODLE_STATUS.set(200);
        assertThat(syncMoodle().updatedCount()).isEqualTo(1);
        assertThat(snapshotValue(javaRecord, "completed_count", Integer.class)).isEqualTo(4);
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
        LearningSnapshotsApiController card = new LearningSnapshotsApiController(null, organizationRepository, repository, service);
        MOODLE_REQUESTS.clear();

        LearningSnapshotsApiController.LearningSnapshotsRefresh refreshed = card.refresh(kamA, existingInteractionA);

        assertThat(refreshed.run().status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(counters(refreshed.run())).containsExactly(1, 0, 0, 1, 0, 0);
        assertThat(refreshed.snapshots()).singleElement().extracting(LearningSnapshotsApiController.LearningSnapshotView::groupName)
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

        assertThatThrownBy(() -> card.refresh(kamB, existingInteractionA)).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> card.refresh(admin, existingInteractionA)).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> card.refresh(kamA, interactionB)).isInstanceOf(InteractionNotFoundException.class);
        UUID dataInteraction = interactionService.create(kamA, new InteractionCreateRequest(ORGANIZATION_A, "Анализ данных", null,
                null, List.of(), PROGRAM_DATA, List.of(), null, null), "data-a").id();
        assertThatThrownBy(() -> card.refresh(kamA, dataInteraction)).isInstanceOf(SourceException.class)
                .extracting(exception -> ((SourceException) exception).code()).isEqualTo("LMS_NOT_MAPPED");

        COMPLETION_OVERRIDES.put(completionUser(false), completionResponse(true));
        MOODLE_STATUS.set(500);
        Map<String, Object> publishedA = jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", groupA);

        assertThatThrownBy(() -> card.refresh(kamA, existingInteractionA)).isInstanceOf(SourceException.class)
                .extracting(exception -> ((SourceException) exception).code()).isEqualTo("LMS_SYNC_FAILED");
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM learning_snapshots WHERE source_record_id = ?", groupA))
                .isEqualTo(publishedA);
    }

    @Test
    void scheduleStartsSourcesOnBehalfOfLastActiveAdministratorAndDemoFirstSyncRunsOnce() {
        service.startScheduled();

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sync_runs", Integer.class)).isZero();

        assertThat(service.synchronizeIfNeverSucceeded(ADMIN, SourceCode.MOODLE)).get()
                .extracting(SyncRunView::status).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(service.synchronizeIfNeverSucceeded(ADMIN, SourceCode.MOODLE)).isEmpty();

        service.startScheduled();

        assertThat(jdbcTemplate.queryForList("SELECT started_by FROM sync_runs WHERE source = 'MOODLE'", UUID.class))
                .containsExactly(ADMIN, ADMIN);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sync_runs WHERE source = 'WEBSITE'", Integer.class)).isZero();

        jdbcTemplate.update("UPDATE crm_user_profiles SET active = FALSE WHERE id = ?", ADMIN);
        service.startScheduled();

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sync_runs", Integer.class)).isEqualTo(2);
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
        assertThat(parallelRuns(RUN_STARTS.minusDays(1)))
                .containsExactlyInAnyOrder(tuple("Java-разработчик", 0L), tuple("Анализ данных", 0L));
        assertThat(parallelRuns(RUN_STARTS)).containsExactlyInAnyOrder(tuple("Java-разработчик", 1L), tuple("Анализ данных", 0L));
        assertThat(parallelRuns(RUN_ENDS.minusDays(1)))
                .containsExactlyInAnyOrder(tuple("Java-разработчик", 1L), tuple("Анализ данных", 1L));
        assertThat(parallelRuns(RUN_ENDS)).containsExactlyInAnyOrder(tuple("Java-разработчик", 0L), tuple("Анализ данных", 1L));
        assertThat(reportService.document(kamA, new ReportRequest(ReportKind.DEMAND, null, RUN_ENDS, null,
                ReportFilters.none(), null, null, null, null)).notes())
                .anyMatch(note -> note.contains("содержит " + RUN_ENDS.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))));

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
        SourceProperties properties = new SourceProperties(1, 1, null, new SourceProperties.Moodle(
                System.getenv("MOODLE_TEST_URL"), token, courses, List.of("student"), List.of("editingteacher", "teacher"),
                Duration.ofSeconds(5), Duration.ofSeconds(20), DataSize.ofMegabytes(5)));
        return new SourceSyncService(repository, siteRecordApplier, siteApiClient, moodleSnapshotApplier,
                new MoodleClient(properties, objectMapper), sourceSyncExecutor, objectMapper);
    }

    private SyncRunView sync() {
        UUID runId = service.start(admin, SourceCode.WEBSITE).runId();
        return repository.findRun(runId).orElseThrow().run();
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
        int status = MOODLE_STATUS.get();
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
        String override = MOODLE_BODIES.get(function + ":" + course);
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
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
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
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL UNIQUE, version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (
                    id UUID PRIMARY KEY, display_name VARCHAR(200) NOT NULL, role VARCHAR(16) NOT NULL, team_id UUID,
                    active BOOLEAN NOT NULL, access_revision INTEGER NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY, external_key VARCHAR(200) UNIQUE, name VARCHAR(300) NOT NULL UNIQUE,
                    type VARCHAR(16) NOT NULL, team_id UUID NOT NULL, owner_manager_id UUID, version INTEGER NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS contacts (
                    id UUID PRIMARY KEY, external_key VARCHAR(200) UNIQUE, organization_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL, position VARCHAR(200), email VARCHAR(320), phone VARCHAR(50),
                    version INTEGER NOT NULL, created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS directions (
                    id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL UNIQUE, archived BOOLEAN NOT NULL, version INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS programs (
                    id UUID PRIMARY KEY, direction_id UUID NOT NULL, name VARCHAR(200) NOT NULL, archived BOOLEAN NOT NULL,
                    version INTEGER NOT NULL, UNIQUE (direction_id, name)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS products (
                    id UUID PRIMARY KEY, vendor_id UUID NOT NULL, name VARCHAR(200) NOT NULL, archived BOOLEAN NOT NULL,
                    version INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interactions (
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
                    transfer_status VARCHAR(160), version INTEGER DEFAULT 0 NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_stages (
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
                CREATE TABLE IF NOT EXISTS interaction_events (
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
                CREATE TABLE IF NOT EXISTS attachments (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, stage_id UUID NOT NULL, event_id UUID,
                    original_name VARCHAR(255) NOT NULL, media_type VARCHAR(160) NOT NULL, size_bytes BIGINT NOT NULL,
                    storage_key UUID NOT NULL, checksum CHAR(64) NOT NULL, status VARCHAR(32) NOT NULL,
                    created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
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
                    started_at TIMESTAMP WITH TIME ZONE, finished_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS source_records (
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
                    run_ends_on DATE, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (source, kind, external_key)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS learning_snapshots (
                    source_record_id UUID PRIMARY KEY, organization_id UUID NOT NULL, program_id UUID NOT NULL,
                    course_id BIGINT NOT NULL, group_id BIGINT, course_name VARCHAR(1333) NOT NULL, group_name VARCHAR(300),
                    participants_count INTEGER NOT NULL, teachers_count INTEGER NOT NULL, completed_count INTEGER,
                    not_completed_count INTEGER, unknown_count INTEGER NOT NULL, groups_count INTEGER NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    changed_at TIMESTAMP WITH TIME ZONE NOT NULL, sync_run_id UUID
                )
                """
        ).forEach(jdbcTemplate::execute);
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
                    new SourceProperties.Website(baseUrl, TOKEN, Duration.ofSeconds(2), Duration.ofSeconds(5), 10,
                            DataSize.ofMegabytes(1)),
                    new SourceProperties.Moodle(baseUrl, MOODLE_TOKEN, List.of(JAVA_COURSE, DATA_COURSE), List.of("student"),
                            List.of("editingteacher", "teacher"), Duration.ofSeconds(2), Duration.ofSeconds(5),
                            DataSize.ofMegabytes(1))
            );
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
