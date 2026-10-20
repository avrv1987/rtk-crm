package ru.rtk.crm.catalogimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.attachment.AttachmentScanOutcome;
import ru.rtk.crm.attachment.AttachmentScanner;
import ru.rtk.crm.interaction.InteractionEvent;
import ru.rtk.crm.interaction.InteractionRepository;
import ru.rtk.crm.robustness.H2Schema;
import ru.rtk.crm.work.MissingStepService;
import ru.rtk.crm.work.WorkAccessDeniedException;

// Перенос данных при вводе в эксплуатацию: история взаимодействий с датами, договоры с вузами, шаг по умолчанию
// и массовое «Задать шаг». Схема — настоящие миграции на H2 (вместе с V69).
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.session.jdbc.initialize-schema=never",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-migration-import-test",
        "app.reports.storage-root=${java.io.tmpdir}/rtk-crm-migration-import-reports",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.agreements.signing-stage-rule=false"
})
@ActiveProfiles("oidc")
class MigrationImportTest {
    private static final String DATABASE = "jdbc:h2:mem:migration-import;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000058a1");
    private static final UUID TEAM_B = UUID.fromString("00000000-0000-0000-0000-0000000058a2");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000058b1");
    private static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-0000000058b2");
    private static final UUID KAM = UUID.fromString("00000000-0000-0000-0000-0000000058b3");
    private static final UUID KAM_B = UUID.fromString("00000000-0000-0000-0000-0000000058b4");
    private static final List<String> HISTORY_HEADERS = List.of(
            "Вуз", "Взаимодействие", "Дата события", "Тип события", "Этап", "Текст", "Автор", "Канал", "Срок шага"
    );
    private static final Map<String, String> HISTORY_COLUMNS = Map.of(
            "organizationName", "Вуз", "interactionTitle", "Взаимодействие", "eventDate", "Дата события",
            "eventType", "Тип события", "stageName", "Этап", "eventText", "Текст", "eventAuthor", "Автор",
            "contactChannel", "Канал", "stepDueDate", "Срок шага"
    );
    private static final List<String> AGREEMENT_HEADERS = List.of(
            "Вуз", "Номер договора", "Статус", "Дата подписания", "Действует до", "Продукты", "Вид мероприятия", "Мероприятие",
            "Плановый объём", "Статус мероприятия"
    );
    private static final Map<String, String> AGREEMENT_COLUMNS = Map.of(
            "organizationName", "Вуз", "agreementNumber", "Номер договора", "agreementStatus", "Статус",
            "concludedOn", "Дата подписания", "validUntil", "Действует до", "productNames", "Продукты",
            "activityKind", "Вид мероприятия", "activityTitle", "Мероприятие", "activityPlannedVolume", "Плановый объём",
            "activityStatus", "Статус мероприятия"
    );

    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);
    private final CrmProfile leader = new CrmProfile(LEADER, UserRole.LEADER, TEAM, 0);
    private final CrmProfile kam = new CrmProfile(KAM, UserRole.USER, TEAM, 0);

    @Autowired
    private CatalogImportService importService;

    @Autowired
    private ImportSettingsService settingsService;

    @Autowired
    private MissingStepService missingStepService;

    @Autowired
    private InteractionRepository interactionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AttachmentScanner attachmentScanner;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws IOException {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL(DATABASE);
        dataSource.setUser("sa");
        JdbcTemplate schema = new JdbcTemplate(dataSource);
        Integer tables = schema.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ORGANIZATIONS'", Integer.class);
        if (tables == null || tables == 0) {
            H2Schema.apply(schema);
        }
        registry.add("spring.datasource.url", () -> DATABASE);
    }

    @BeforeEach
    void setUp() {
        when(attachmentScanner.scan(any(), anyLong())).thenReturn(AttachmentScanOutcome.CLEAN);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM teams WHERE id = ?", Integer.class, TEAM) > 0) {
            return;
        }
        jdbc.update("INSERT INTO teams (id, name) VALUES (?, 'Команда переноса'), (?, 'Другая команда переноса')", TEAM, TEAM_B);
        profile(ADMIN, "Администратор переноса", "ADMIN", null, "admin.import");
        profile(LEADER, "Руководитель переноса", "LEADER", TEAM, "leader.import");
        profile(KAM, "Иванова КАМ", "USER", TEAM, "kam.ivanova");
        profile(KAM_B, "КАМ другой команды", "USER", TEAM_B, "kam.other");
        UUID vendor = UUID.randomUUID();
        jdbc.update("INSERT INTO vendors (id, name) VALUES (?, 'Вендор переноса')", vendor);
        jdbc.update("INSERT INTO products (id, vendor_id, name) VALUES (?, ?, 'Учебная платформа переноса')", UUID.randomUUID(), vendor);
    }

    @Test
    void historyIsImportedWithDatesMarkedAsImportedAndRepeatsWithoutDuplicates() throws IOException {
        organization("Университет истории", TEAM, KAM);
        String title = "Работа с вузом";
        String future = LocalDate.now(MOSCOW).plusDays(3).toString();
        byte[] file = workbook("История", HISTORY_HEADERS, List.of(
                List.of("Университет истории", title, "10.09.2025", "комментарий", "", "Первое письмо\nвторая строка", "kam.ivanova", "", ""),
                List.of("Университет истории", title, "2025-09-15", "переход этапа", "Уточнение актуальности", "", "импорт", "", ""),
                List.of("Университет истории", title, "01.10.2025 11:30", "звонок", "", "Созвон с проректором", "", "", ""),
                List.of("Университет истории", title, "2025-10-20", "переход этапа", "Встреча", "", "", "", ""),
                List.of("Университет истории", title, "2025-11-05", "шаг", "", "Отправить проект договора", "", "", "12.11.2025"),
                List.of("Нет такого вуза", title, "2025-11-05", "комментарий", "", "Текст", "", "", ""),
                List.of("Университет истории", title, "31.02.2025", "комментарий", "", "Текст", "", "", ""),
                List.of("Университет истории", title, future, "комментарий", "", "Текст из будущего", "", "", ""),
                List.of("Университет истории", title, "10.09.2025", "комментарий", "", "Первое письмо\nвторая строка", "kam.ivanova", "", ""),
                List.of("Университет истории", title, "2025-09-11", "комментарий", "", "Текст", "nobody", "", ""),
                List.of("Университет истории", title, "2025-09-12", "контакт", "", "Без канала", "", "", "")
        ));

        CatalogImportView preview = preview(file, "history.xlsx", "История", CatalogImportProfile.INTERACTION_HISTORY, HISTORY_COLUMNS, true);

        assertThat(preview.rows()).extracting(CatalogImportRowView::status).containsExactly(
                CatalogImportRowStatus.CREATE, CatalogImportRowStatus.CREATE, CatalogImportRowStatus.CREATE,
                CatalogImportRowStatus.CREATE, CatalogImportRowStatus.CREATE, CatalogImportRowStatus.INVALID,
                CatalogImportRowStatus.INVALID, CatalogImportRowStatus.INVALID, CatalogImportRowStatus.CONFLICT,
                CatalogImportRowStatus.INVALID, CatalogImportRowStatus.INVALID
        );
        assertThat(row(preview, 7).fieldErrors()).containsKey("organizationName");
        assertThat(row(preview, 8).fieldErrors()).containsKey("eventDate");
        assertThat(row(preview, 9).fieldErrors().get("eventDate")).contains("позже сегодняшней");
        assertThat(row(preview, 10).fieldErrors().get("eventText")).isEqualTo("Повторяет событие строки 2");
        assertThat(row(preview, 11).fieldErrors()).containsKey("eventAuthor");
        assertThat(row(preview, 12).fieldErrors()).containsKey("contactChannel");
        assertThat(row(preview, 2).newValues().get("interaction")).isEqualTo("Новое взаимодействие «Работа с вузом» по базовому процессу");
        assertThat(row(preview, 2).newValues().get("historyEvent")).contains("10.09.2025 00:00").contains("автор: Иванова КАМ");

        apply(preview);

        UUID interactionId = jdbc.queryForObject("""
                SELECT i.id FROM interactions i JOIN organizations o ON o.id = i.organization_id
                WHERE o.name = 'Университет истории'""", UUID.class);
        assertThat(jdbc.queryForObject("SELECT title FROM interactions WHERE id = ?", String.class, interactionId)).isEqualTo(title);
        // Новая карточка импорта получает шаг по умолчанию, а этап — по самому позднему переходу файла.
        assertThat(jdbc.queryForObject("SELECT next_action FROM interactions WHERE id = ?", String.class, interactionId))
                .isEqualTo("Связаться с вузом");
        assertThat(jdbc.queryForObject("""
                SELECT s.name FROM interactions i JOIN interaction_stages s ON s.id = i.current_stage_id WHERE i.id = ?""",
                String.class, interactionId)).isEqualTo("Встреча");
        assertThat(jdbc.queryForObject("SELECT last_contact_at FROM interactions WHERE id = ?", OffsetDateTime.class, interactionId)
                .toInstant()).isEqualTo(OffsetDateTime.of(2025, 10, 1, 11, 30, 0, 0, java.time.ZoneOffset.ofHours(3)).toInstant());
        List<InteractionEvent> imported = interactionRepository.findEvents(interactionId).stream()
                .filter(event -> event.importSource() != null)
                .toList();
        assertThat(imported).hasSize(5);
        assertThat(imported).allSatisfy(event -> {
            assertThat(event.actorProfileId()).isEqualTo(ADMIN);
            assertThat(event.importSource()).startsWith("Импорт истории, файл «history.xlsx», строка ");
        });
        InteractionEvent comment = imported.stream().filter(event -> "Первое письмо\nвторая строка".equals(event.comment())).findFirst().orElseThrow();
        assertThat(comment.importAuthor()).isEqualTo("Иванова КАМ");
        assertThat(comment.occurredAt().toInstant())
                .isEqualTo(LocalDate.of(2025, 9, 10).atStartOfDay(MOSCOW).toInstant());
        assertThat(imported).filteredOn(event -> event.nextStep() != null)
                .singleElement()
                .satisfies(event -> assertThat(event.nextStep().nextAction()).isEqualTo("Отправить проект договора"));
        assertThat(jdbc.queryForObject("""
                SELECT contact_channel FROM interaction_events WHERE interaction_id = ? AND comment = 'Созвон с проректором'""",
                String.class, interactionId)).isEqualTo("CALL");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events WHERE action = 'CATALOG_IMPORT_APPLIED' AND object_name = 'Импорт истории взаимодействий'""",
                Integer.class)).isPositive();

        CatalogImportView repeated = preview(file, "history.xlsx", "История", CatalogImportProfile.INTERACTION_HISTORY, HISTORY_COLUMNS, true);
        assertThat(repeated.rows().subList(0, 5)).extracting(CatalogImportRowView::status)
                .containsOnly(CatalogImportRowStatus.UNCHANGED);
        int events = jdbc.queryForObject("SELECT COUNT(*) FROM interaction_events WHERE interaction_id = ?", Integer.class, interactionId);
        apply(repeated);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_events WHERE interaction_id = ?", Integer.class, interactionId))
                .isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interactions WHERE title = ?", Integer.class, title)).isEqualTo(1);
    }

    @Test
    void csvInWindows1251KeepsMultilineTextAndUsesTheOnlyInteraction() throws IOException {
        organization("Университет CSV", TEAM, KAM);
        String csv = "Вуз;Дата события;Тип события;Текст\r\n"
                + "Университет CSV;12.03.2025;комментарий;\"Строка 1\r\nСтрока 2; с точкой с запятой\"\r\n"
                + "Университет CSV;13.03.2025;встреча;\"Обсудили \"\"лабораторию\"\"\"\r\n";
        byte[] file = csv.getBytes(Charset.forName("windows-1251"));

        CatalogImportInspectResponse inspected = importService.inspect(admin, multipart(file, "flow.csv"));
        assertThat(inspected.sheets()).singleElement().satisfies(sheet -> {
            assertThat(sheet.name()).isEqualTo("CSV");
            assertThat(sheet.headers()).containsExactly("Вуз", "Дата события", "Тип события", "Текст");
        });
        CatalogImportView preview = preview(file, "flow.csv", "CSV", CatalogImportProfile.INTERACTION_HISTORY, Map.of(
                "organizationName", "Вуз", "eventDate", "Дата события", "eventType", "Тип события", "eventText", "Текст"
        ), false);
        assertThat(preview.rows()).extracting(CatalogImportRowView::status)
                .containsExactly(CatalogImportRowStatus.CREATE, CatalogImportRowStatus.CREATE);
        apply(preview);

        assertThat(jdbc.queryForList("""
                SELECT e.comment FROM interaction_events e JOIN interactions i ON i.id = e.interaction_id
                JOIN organizations o ON o.id = i.organization_id
                WHERE o.name = 'Университет CSV' AND e.import_source IS NOT NULL ORDER BY e.occurred_at""", String.class))
                .containsExactly("Строка 1\r\nСтрока 2; с точкой с запятой", "Обсудили \"лабораторию\"");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM interactions i JOIN organizations o ON o.id = i.organization_id WHERE o.name = 'Университет CSV'""",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void agreementsAreImportedWithTheManualDateRulesAndRepeatWithoutDuplicates() throws IOException {
        organization("Университет договоров", TEAM, KAM);
        String org = "Университет договоров";
        String tomorrow = LocalDate.now(MOSCOW).plusDays(1).toString();
        byte[] file = workbook("Договоры", AGREEMENT_HEADERS, List.of(
                List.of(org, "ШВ-1", "Действует", "01.09.2025", "31.08.2027", "Учебная платформа переноса", "Иное", "Курс для преподавателей", "10", "Запланировано"),
                List.of(org, "ШВ-1", "Действует", "01.09.2025", "31.08.2027", "Учебная платформа переноса", "Иное", "Летняя практика", "", ""),
                List.of(org, "ШВ-2", "Действует", "", "", "", "", "", "", ""),
                List.of(org, "ШВ-3", "Действует", tomorrow, "", "", "", "", "", ""),
                List.of(org, "ШВ-4", "Завершён", "01.01.2024", "31.12.2023", "", "", "", "", ""),
                List.of(org, "ШВ-5", "Завершён", "01.01.2023", "31.12.2024", "", "", "", "", ""),
                List.of(org, "ШВ-6", "Проект", "", "", "Нет такого продукта", "", "", "", ""),
                List.of(org, "ШВ-7", "Непонятный", "", "", "", "", "", "", ""),
                List.of(org, "ШВ-8", "Проект", "", "", "", "Нет такого вида", "Мероприятие", "", "")
        ));

        CatalogImportView preview = preview(file, "agreements.xlsx", "Договоры", CatalogImportProfile.UNIVERSITY_AGREEMENT, AGREEMENT_COLUMNS, false);

        assertThat(preview.rows()).extracting(CatalogImportRowView::status).containsExactly(
                CatalogImportRowStatus.CREATE, CatalogImportRowStatus.CREATE, CatalogImportRowStatus.INVALID,
                CatalogImportRowStatus.INVALID, CatalogImportRowStatus.INVALID, CatalogImportRowStatus.CREATE,
                CatalogImportRowStatus.INVALID, CatalogImportRowStatus.INVALID, CatalogImportRowStatus.INVALID
        );
        assertThat(row(preview, 4).fieldErrors().get("concludedOn")).isEqualTo("Укажите дату подписания");
        assertThat(row(preview, 5).fieldErrors().get("concludedOn")).isEqualTo("Дата подписания не может быть позже сегодняшней");
        assertThat(row(preview, 6).fieldErrors().get("validUntil")).isEqualTo("Срок действия раньше даты подписания");
        assertThat(row(preview, 8).fieldErrors()).containsKey("productNames");
        assertThat(row(preview, 9).fieldErrors()).containsKey("agreementStatus");
        assertThat(row(preview, 10).fieldErrors()).containsKey("activityKind");

        apply(preview);

        UUID organizationId = jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", UUID.class, org);
        assertThat(jdbc.queryForList("SELECT number || ':' || status FROM agreements WHERE organization_id = ? ORDER BY number",
                String.class, organizationId)).containsExactly("ШВ-1:ACTIVE", "ШВ-5:COMPLETED");
        UUID agreementId = jdbc.queryForObject("SELECT id FROM agreements WHERE organization_id = ? AND number = 'ШВ-1'",
                UUID.class, organizationId);
        assertThat(jdbc.queryForObject("SELECT concluded_on FROM agreements WHERE id = ?", LocalDate.class, agreementId))
                .isEqualTo(LocalDate.of(2025, 9, 1));
        assertThat(jdbc.queryForList("SELECT title FROM agreement_activities WHERE agreement_id = ? ORDER BY title", String.class, agreementId))
                .containsExactly("Курс для преподавателей", "Летняя практика");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agreement_products WHERE agreement_id = ?", Integer.class, agreementId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT comment FROM agreement_status_events WHERE agreement_id = ?", String.class, agreementId))
                .startsWith("Импорт договоров, файл «agreements.xlsx», строка ");

        CatalogImportView repeated = preview(file, "agreements.xlsx", "Договоры", CatalogImportProfile.UNIVERSITY_AGREEMENT, AGREEMENT_COLUMNS, false);
        assertThat(List.of(row(repeated, 2).status(), row(repeated, 3).status(), row(repeated, 7).status()))
                .containsOnly(CatalogImportRowStatus.UNCHANGED);
        apply(repeated);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agreements WHERE organization_id = ?", Integer.class, organizationId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agreement_activities WHERE agreement_id = ?", Integer.class, agreementId))
                .isEqualTo(2);

        byte[] changed = workbook("Договоры", AGREEMENT_HEADERS, List.of(
                List.of(org, "шв-1", "Проект", "", "", "", "", "", "", "")
        ));
        CatalogImportView conflict = preview(changed, "agreements.xlsx", "Договоры", CatalogImportProfile.UNIVERSITY_AGREEMENT, AGREEMENT_COLUMNS, false);
        assertThat(row(conflict, 2).status()).isEqualTo(CatalogImportRowStatus.CONFLICT);
        assertThat(row(conflict, 2).fieldErrors().get("agreementNumber")).contains("уже есть в CRM").contains("«Действует» → «Проект»");
    }

    @Test
    void everyRowOfAnAgreementWithContradictoryRowsIsAConflictAndNothingIsCreated() throws IOException {
        organization("Университет трёх строк", TEAM, KAM);
        String org = "Университет трёх строк";
        byte[] file = workbook("Договоры", AGREEMENT_HEADERS, List.of(
                List.of(org, "ТС-1", "Действует", "01.09.2025", "", "", "Иное", "Первое мероприятие", "", ""),
                List.of(org, "ТС-1", "Действует", "01.09.2025", "", "", "Иное", "Второе мероприятие", "", ""),
                List.of(org, "ТС-1", "Действует", "01.09.2025", "", "", "Иное", "Третье мероприятие", "", ""),
                List.of(org, "ТС-1", "Завершён", "01.09.2025", "", "", "", "", "", ""),
                List.of(org, "ТС-2", "Действует", "01.09.2025", "", "", "Иное", "Своё мероприятие", "", "")
        ));

        CatalogImportView preview = preview(file, "three.xlsx", "Договоры", CatalogImportProfile.UNIVERSITY_AGREEMENT, AGREEMENT_COLUMNS, false);

        assertThat(preview.rows()).extracting(CatalogImportRowView::status).containsExactly(
                CatalogImportRowStatus.CONFLICT, CatalogImportRowStatus.CONFLICT, CatalogImportRowStatus.CONFLICT,
                CatalogImportRowStatus.CONFLICT, CatalogImportRowStatus.CREATE
        );
        assertThat(row(preview, 3).fieldErrors().get("agreementNumber")).isEqualTo("Противоречит строке 5: другие данные договора");
        assertThat(row(preview, 4).fieldErrors().get("agreementNumber")).isEqualTo("Противоречит строке 5: другие данные договора");
        assertThat(row(preview, 5).fieldErrors().get("agreementNumber")).isEqualTo("Противоречит строке 2: другие данные договора");

        apply(preview);

        UUID organizationId = jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", UUID.class, org);
        assertThat(jdbc.queryForList("SELECT number FROM agreements WHERE organization_id = ?", String.class, organizationId))
                .containsExactly("ТС-2");
    }

    @Test
    void onlyAdministratorImportsAndChangesTheDefaultStep() throws IOException {
        byte[] file = workbook("История", HISTORY_HEADERS, List.of(
                List.of("Университет истории", "", "2025-09-10", "комментарий", "", "Текст", "", "", "")
        ));
        assertThatThrownBy(() -> importService.preview(leader, multipart(file, "history.xlsx"), CatalogImportProfile.INTERACTION_HISTORY,
                "История", mapping(HISTORY_COLUMNS, false))).isInstanceOf(CatalogImportAccessDeniedException.class);
        assertThatThrownBy(() -> importService.inspect(kam, multipart(file, "history.xlsx")))
                .isInstanceOf(CatalogImportAccessDeniedException.class);
        assertThatThrownBy(() -> settingsService.view(leader)).isInstanceOf(CatalogImportAccessDeniedException.class);
        assertThatThrownBy(() -> settingsService.update(kam, new ImportSettingsService.ImportSettingsView("Шаг", 3), "rq"))
                .isInstanceOf(CatalogImportAccessDeniedException.class);
        assertThatThrownBy(() -> missingStepService.assign(kam, new MissingStepService.MissingStepRequest(List.of(UUID.randomUUID()), null, null),
                UUID.randomUUID().toString(), "rq")).isInstanceOf(WorkAccessDeniedException.class);
        assertThatThrownBy(() -> settingsService.update(admin, new ImportSettingsService.ImportSettingsView("Шаг", 0), "rq"))
                .isInstanceOf(ru.rtk.crm.interaction.InteractionValidationException.class);
    }

    @Test
    void leaderAssignsTheDefaultStepOnlyToOwnCardsWithoutStepAndTheActionIsJournaled() throws IOException {
        organization("Университет шагов", TEAM, KAM);
        organization("Университет чужой команды", TEAM_B, KAM_B);
        ImportSettingsService.ImportSettingsView before = settingsService.view(admin);
        settingsService.update(admin, new ImportSettingsService.ImportSettingsView("Позвонить в вуз", 3), "rq-settings");
        try {
            byte[] file = workbook("История", HISTORY_HEADERS, List.of(
                    List.of("Университет шагов", "Карточка X", "2025-09-10", "комментарий", "", "Текст X", "", "", ""),
                    List.of("Университет шагов", "Карточка Y", "2025-09-10", "комментарий", "", "Текст Y", "", "", ""),
                    List.of("Университет шагов", "Карточка Z", "2025-09-10", "комментарий", "", "Текст Z", "", "", ""),
                    List.of("Университет чужой команды", "Карточка W", "2025-09-10", "комментарий", "", "Текст W", "", "", "")
            ));
            apply(preview(file, "steps.xlsx", "История", CatalogImportProfile.INTERACTION_HISTORY, HISTORY_COLUMNS, false));
        } finally {
            settingsService.update(admin, before, "rq-settings-back");
        }
        UUID x = interaction("Карточка X");
        UUID y = interaction("Карточка Y");
        UUID z = interaction("Карточка Z");
        UUID w = interaction("Карточка W");
        assertThat(jdbc.queryForObject("SELECT next_action FROM interactions WHERE id = ?", String.class, z)).isEqualTo("Позвонить в вуз");
        jdbc.update("UPDATE interactions SET next_action = NULL, next_action_at = NULL WHERE id IN (?, ?, ?)", x, y, w);
        String key = UUID.randomUUID().toString();
        MissingStepService.MissingStepRequest request = new MissingStepService.MissingStepRequest(List.of(x, y, z, w), null, null);

        MissingStepService.MissingStepResult result = missingStepService.assign(leader, request, key, "rq");

        assertThat(result.assigned()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT next_action FROM interactions WHERE id = ?", String.class, x)).isEqualTo("Связаться с вузом");
        assertThat(jdbc.queryForObject("SELECT next_action FROM interactions WHERE id = ?", String.class, w)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM interaction_events
                WHERE interaction_id = ? AND type = 'PLAN_UPDATED' AND comment LIKE 'Шаг задан руководителем%'""", Integer.class, x))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'MISSING_STEPS_ASSIGNED'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'IMPORT_SETTINGS_CHANGED'", Integer.class))
                .isGreaterThanOrEqualTo(2);

        MissingStepService.MissingStepResult replay = missingStepService.assign(leader, request, key, "rq");
        assertThat(List.of(replay.assigned(), replay.skipped())).containsExactly(2, 2);
        assertThat(replay.nextActionAt().toInstant()).isEqualTo(result.nextActionAt().toInstant());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'MISSING_STEPS_ASSIGNED'", Integer.class))
                .isEqualTo(1);
    }

    private CatalogImportView preview(byte[] file, String name, String sheet, CatalogImportProfile profile, Map<String, String> columns,
                                      boolean stageFromHistory) {
        CatalogImportPreviewResponse response = importService.preview(admin, multipart(file, name), profile, sheet,
                mapping(columns, stageFromHistory));
        return importService.get(admin, response.importId());
    }

    private void apply(CatalogImportView preview) {
        List<UUID> rows = preview.rows().stream()
                .filter(row -> !row.applied() && (row.status() == CatalogImportRowStatus.CREATE || row.status() == CatalogImportRowStatus.UPDATE
                        || row.status() == CatalogImportRowStatus.UNCHANGED))
                .map(CatalogImportRowView::id)
                .toList();
        importService.apply(admin, preview.id(), new CatalogImportApplyRequest(preview.version(), rows, List.of()), UUID.randomUUID().toString());
    }

    private static CatalogImportMapping mapping(Map<String, String> columns, boolean stageFromHistory) {
        return new CatalogImportMapping(columns, Map.of(), Map.of(), null, stageFromHistory ? Boolean.TRUE : null);
    }

    private static CatalogImportRowView row(CatalogImportView view, int rowNumber) {
        return view.rows().stream().filter(row -> row.rowNumber() == rowNumber).findFirst().orElseThrow();
    }

    private static MockMultipartFile multipart(byte[] content, String name) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    private static byte[] workbook(String sheetName, List<String> headers, List<List<String>> rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName);
            List<List<String>> all = new ArrayList<>();
            all.add(headers);
            all.addAll(rows);
            for (int index = 0; index < all.size(); index++) {
                Row row = sheet.createRow(index);
                for (int column = 0; column < all.get(index).size(); column++) {
                    row.createCell(column).setCellValue(all.get(index).get(column));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void organization(String name, UUID team, UUID owner) {
        jdbc.update("INSERT INTO organizations (id, name, type, team_id, owner_manager_id) VALUES (?, ?, 'UNIVERSITY', ?, ?)",
                UUID.randomUUID(), name, team, owner);
    }

    private UUID interaction(String title) {
        return jdbc.queryForObject("SELECT id FROM interactions WHERE title = ?", UUID.class, title);
    }

    private void profile(UUID id, String name, String role, UUID team, String login) {
        jdbc.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, login)
                VALUES (?, 'http://crm.test/idp/realms/rtk-crm', ?, ?, ?, ?, ?)""", id, id.toString(), name, role, team, login);
    }
}
