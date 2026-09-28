package ru.rtk.crm.catalogimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.attachment.AttachmentContentValidator;
import ru.rtk.crm.attachment.AttachmentProperties;
import ru.rtk.crm.attachment.AttachmentRepository;
import ru.rtk.crm.attachment.AttachmentScanOutcome;
import ru.rtk.crm.attachment.AttachmentScanner;
import ru.rtk.crm.catalog.CatalogChangeEventRepository;
import ru.rtk.crm.catalog.CatalogRepository;
import ru.rtk.crm.catalog.ContactRepository;
import ru.rtk.crm.catalog.OrganizationAssignmentRepository;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionCreateRequest;
import ru.rtk.crm.interaction.InteractionRepository;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.interaction.WorkflowTemplateRepository;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:catalogimport;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        CatalogImportRepository.class,
        CatalogChangeEventRepository.class,
        CatalogImportService.class,
        CatalogImportWorkbookReader.class,
        OrganizationAssignmentRepository.class,
        OrganizationRepository.class,
        ContactRepository.class,
        CatalogRepository.class,
        InteractionRepository.class,
        WorkflowTemplateRepository.class,
        AttachmentRepository.class,
        CommandIdempotencyRepository.class,
        InteractionService.class,
        VendorContactImport.class,
        CatalogImportServiceTest.ImportTestConfiguration.class
})
class CatalogImportServiceTest {
    private static final UUID TEAM_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID TEAM_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID IVAN = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID ANNA_A = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID ANNA_B = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private static final List<String> TZ_HEADERS = List.of(
            "Название ВУЗа", "Вендор", "ПО", "Номер договора", "Подписание лицензии",
            "Срок действия лицензии (год)", "Статус по передаче", "ФИО Менеджера", "Ответственные от ВУЗа", "Комментарий"
    );
    private static final List<String> VENDOR_HEADERS = List.of("Компания", "Продукт", "ФИО", "Телефон", "Почта", "Способ связи");
    private static final List<List<String>> VENDOR_ROWS = List.of(
            List.of("ООО «Базис»", "«Базис Dynamix»", "Контакт вендора Демо 1", "+7 (900) 100-00-01", "vendor01@example.test", "Почта"),
            List.of("ООО «ТДата»", "«RT.DataLake», «RT.Warehouse»", "Контакт вендора Демо 2", "+7 (900) 100-00-02",
                    "vendor02@example.test", "Чат в ТГ"),
            List.of("ПАО «Ростелеком»", "«RT.DataVision»", "Контакт вендора Демо 3", "+7 (900) 100-00-03", "vendor03@example.test",
                    "Чат в ТГ"),
            List.of("ООО «РТК ИТ Плюс»", "«AKOLA»", "Контакт вендора Демо 4", "+7 (900) 100-00-04", "vendor04@example.test", "Чат в ТГ"),
            List.of("ООО «РТК ИТ Плюс»", "«Яга»", "Контакт вендора Демо 5", "+7 (900) 100-00-05", "vendor05@example.test",
                    "Почта, Чат в ТГ"),
            List.of("ООО «РТК ИТ»", "«Web3Gate»", "Контакт вендора Демо 6", "+7 (900) 100-00-06", "vendor06@example.test", "Чат в ТГ"),
            List.of("ООО «РТК ИТ»", "«Аврора SDK»", "Контакт вендора Демо 7", "+7 (900) 100-00-07", "vendor07@example.test", "Чат в ТГ"),
            List.of("ООО «РТК ИТ»", "«Нейрошлюз»", "Контакт вендора Демо 8", "+7 (900) 100-00-08", "vendor08@example.test", "Чат в ТГ")
    );
    private static final List<String> TZ_FIELDS = List.of(
            "organizationName", "vendorName", "productName", "contractNumber", "licenseSigned",
            "licenseExpiryYear", "transferStatus", "managerName", "contactName", "comment"
    );

    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);

    @Autowired
    private CatalogImportService service;

    @Autowired
    private InteractionService interactionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of(
                "catalog_change_events", "catalog_import_jobs", "catalog_import_rows", "catalog_imports", "organization_assignment_events",
                "vendor_contacts", "interaction_events", "command_idempotency_records", "interaction_contacts", "product_agreements",
                "interaction_stage_transitions", "interaction_stages", "interactions", "workflow_template_transitions",
                "workflow_template_stages", "workflow_templates", "contacts", "products", "vendors", "programs",
                "directions", "organizations", "crm_user_profiles", "teams"
        )) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        insertProfile(ADMIN, "Администратор импорта", "ADMIN", null);
        insertProfile(IVAN, "Иван Петров", "USER", TEAM_A);
        insertProfile(ANNA_A, "Анна Смирнова", "USER", TEAM_A);
        insertProfile(ANNA_B, "Анна Смирнова", "USER", TEAM_B);
        insertDefaultTemplate();
    }

    @Test
    void fiveRowAcceptanceExampleReportsEachRowAndRepeatsWithoutDuplicates() throws IOException {
        byte[] file = workbook(TZ_HEADERS, List.of(
                List.of("Университет Альфа", "Вендор Один", "Платформа", "Д-001", "да", "2027", "Передано",
                        "иван  петров", "Ольга Кузнецова", "Первичная поставка"),
                List.of("Университет Бета", "Вендор Один", "Платформа", "Д-002", "нет", "позже", "", "Иван Петров", "", ""),
                List.of("Университет Гамма", "Вендор Один", "Платформа", "Д-003", "", "", "", "Анна Смирнова", "", ""),
                List.of("Университет Альфа", "Вендор Один", "Платформа", "Д-001", "да", "2027", "Передано",
                        "иван  петров", "Ольга Кузнецова", "Первичная поставка"),
                List.of("Университет Альфа", "Вендор Один", "Платформа", "Д-004", "2026-03-01", "31.12.2028", "В работе",
                        "Иван Петров", "Ольга Кузнецова", "")
        ));

        CatalogImportView preview = preview(file, tzColumns(), Map.of());

        assertThat(preview.rows()).extracting(CatalogImportRowView::status).containsExactly(
                CatalogImportRowStatus.CREATE, CatalogImportRowStatus.INVALID, CatalogImportRowStatus.CONFLICT,
                CatalogImportRowStatus.CONFLICT, CatalogImportRowStatus.CREATE
        );
        assertThat(row(preview, 2).newValues())
                .containsEntry("managerName", "Иван Петров")
                .containsEntry("organizationName", "Университет Альфа")
                .containsEntry("licenseSigned", "да")
                .containsEntry("interaction", "Новое взаимодействие «Платформа» по базовому процессу");
        assertThat(row(preview, 2).newValues().values()).noneMatch(value -> value.matches(".*[0-9a-f]{8}-[0-9a-f]{4}-.*"));
        assertThat(row(preview, 3).fieldErrors()).containsOnlyKeys("licenseExpiryYear");
        assertThat(row(preview, 4).fieldErrors().get("managerName")).contains("Найдено 2 активных КАМ");
        assertThat(row(preview, 5).fieldErrors().get("contractNumber")).isEqualTo("Этот договор уже указан в строке 2");
        assertThat(row(preview, 6).newValues()).containsEntry("interaction", "Взаимодействие, создаваемое строкой 2");

        applyEligible(preview);

        assertThat(count("organizations")).isEqualTo(1);
        assertThat(count("vendors")).isEqualTo(1);
        assertThat(count("products")).isEqualTo(1);
        assertThat(count("contacts")).isEqualTo(1);
        assertThat(count("interactions")).isEqualTo(1);
        assertThat(count("interaction_stages")).isEqualTo(3);
        assertThat(count("product_agreements")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("SELECT type FROM interaction_events ORDER BY version", String.class))
                .containsExactly("CREATED", "COMMENTED");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT license_signed, license_expiry_year, transfer_status
                FROM product_agreements WHERE contract_number = 'Д-004'
                """)).containsEntry("LICENSE_SIGNED", true).containsEntry("LICENSE_EXPIRY_YEAR", 2028)
                .containsEntry("TRANSFER_STATUS", "В работе");
        assertThat(jdbcTemplate.queryForObject("SELECT created_by FROM interactions", UUID.class)).isEqualTo(ADMIN);
        assertThat(jdbcTemplate.queryForObject("SELECT owner_manager_id FROM organizations", UUID.class)).isEqualTo(IVAN);
        assertThat(journal()).containsExactlyInAnyOrder(
                "ORGANIZATION CREATE Университет Альфа: Тип: вуз; КАМ: Иван Петров; импорт каталога",
                "VENDOR CREATE Вендор Один: импорт каталога",
                "PRODUCT CREATE Платформа: Вендор: Вендор Один; импорт каталога",
                "AGREEMENT CREATE Университет Альфа — Платформа: Номер договора: Д-001; Лицензия подписана: да; "
                        + "Срок лицензии: 2027; Статус передачи: Передано; импорт каталога",
                "AGREEMENT CREATE Университет Альфа — Платформа: Номер договора: Д-004; Лицензия подписана: да; "
                        + "Срок лицензии: 2028; Статус передачи: В работе; импорт каталога"
        );

        CatalogImportView repeated = preview(file, tzColumns(), Map.of());

        assertThat(repeated.rows()).extracting(CatalogImportRowView::status).containsExactly(
                CatalogImportRowStatus.UNCHANGED, CatalogImportRowStatus.INVALID, CatalogImportRowStatus.CONFLICT,
                CatalogImportRowStatus.CONFLICT, CatalogImportRowStatus.UNCHANGED
        );

        byte[] changedComment = workbook(TZ_HEADERS, List.of(List.of(
                "Университет Альфа", "Вендор Один", "Платформа", "Д-001", "да", "2027", "Передано",
                "Иван Петров", "Ольга Кузнецова", "Поставка завершена"
        )));
        CatalogImportView commented = preview(changedComment, tzColumns(), Map.of());
        assertThat(row(commented, 2).status()).isEqualTo(CatalogImportRowStatus.CREATE);
        assertThat(row(commented, 2).oldValues()).containsEntry("comment", "");
        applyEligible(commented);
        assertThat(jdbcTemplate.queryForList("SELECT comment FROM interaction_events WHERE type = 'COMMENTED' ORDER BY version",
                String.class)).containsExactly("Первичная поставка", "Поставка завершена");
        assertThat(count("product_agreements")).isEqualTo(2);
    }

    @Test
    void importDoesNotChangeContactWithRestrictedProcessing() throws IOException {
        UUID organizationId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        insertOrganization(organizationId, null, "Университет Альфа", IVAN);
        jdbcTemplate.update("""
                INSERT INTO contacts (id, personal_data_status, organization_id, name, email, version, created_by, created_at, updated_at)
                VALUES (?, 'RESTRICTED', ?, 'Ольга Кузнецова', 'old@example.test', 2, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, contactId, organizationId, ADMIN);
        List<String> headers = new ArrayList<>(TZ_HEADERS);
        headers.add("Почта ответственного");
        Map<String, String> columns = new LinkedHashMap<>(tzColumns());
        columns.put("contactEmail", "Почта ответственного");
        byte[] changed = workbook(headers, List.of(List.of(
                "Университет Альфа", "Вендор Один", "Платформа", "Д-001", "", "", "", "Иван Петров", "Ольга Кузнецова", "",
                "new@example.test"
        )));

        CatalogImportView conflicting = preview(changed, columns, Map.of());

        assertThat(row(conflicting, 2).status()).isEqualTo(CatalogImportRowStatus.CONFLICT);
        assertThat(row(conflicting, 2).fieldErrors().get("contactName"))
                .isEqualTo("Контакт обезличен или его обработка ограничена; уточнение — через «Субъект ПДн»");

        byte[] same = workbook(TZ_HEADERS, List.of(List.of(
                "Университет Альфа", "Вендор Один", "Платформа", "Д-001", "", "", "", "Иван Петров", "Ольга Кузнецова", ""
        )));
        CatalogImportView unchanged = preview(same, tzColumns(), Map.of());
        assertThat(row(unchanged, 2).status()).isEqualTo(CatalogImportRowStatus.CREATE);
        applyEligible(unchanged);

        assertThat(count("product_agreements")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT email, external_key, version, personal_data_status FROM contacts WHERE id = ?", contactId
        )).containsEntry("email", "old@example.test")
                .containsEntry("external_key", null)
                .containsEntry("version", 2)
                .containsEntry("personal_data_status", "RESTRICTED");
    }

    @Test
    void importsTheTemplateFileIntoAnEmptyCrm() throws IOException {
        insertProfile(UUID.randomUUID(), "Иванова Анна (пример)", "USER", TEAM_A);
        insertProfile(UUID.randomUUID(), "Смирнов Олег (пример)", "USER", TEAM_A);
        byte[] template = Files.readAllBytes(Path.of("..", "frontend", "public", "catalog-import-template.xlsx"));

        CatalogImportInspectResponse inspected = service.inspect(admin, file(template));
        CatalogImportView preview = preview(template, tzColumns(), Map.of());

        assertThat(inspected.sheets()).singleElement().satisfies(sheet -> {
            assertThat(sheet.name()).isEqualTo("Каталог");
            assertThat(sheet.headers()).containsExactlyElementsOf(TZ_HEADERS);
        });
        assertThat(preview.rows()).extracting(CatalogImportRowView::status).containsOnly(CatalogImportRowStatus.CREATE);
        applyEligible(preview);
        assertThat(count("organizations")).isEqualTo(2);
        assertThat(count("vendors")).isEqualTo(2);
        assertThat(count("products")).isEqualTo(3);
        assertThat(count("interactions")).isEqualTo(3);
        assertThat(count("product_agreements")).isEqualTo(3);
        assertThat(count("contacts")).isEqualTo(2);
        assertThat(preview(template, tzColumns(), Map.of()).rows())
                .extracting(CatalogImportRowView::status)
                .containsOnly(CatalogImportRowStatus.UNCHANGED);
    }

    @Test
    void seedRecordsWithoutKeysAreUpdatedByUniqueNameInsteadOfDuplicated() throws IOException {
        UUID vendorId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO vendors (id, name, archived, version) VALUES (?, 'Вендор Один', FALSE, 0)", vendorId);
        jdbcTemplate.update("INSERT INTO products (id, vendor_id, name, archived, version) VALUES (?, ?, 'Платформа', FALSE, 0)",
                productId, vendorId);
        insertOrganization(organizationId, null, "Университет Альфа", IVAN);
        interactionService.create(
                new CrmProfile(IVAN, UserRole.USER, TEAM_A, 0),
                new InteractionCreateRequest(organizationId, "Работа из интерфейса", null, null, List.of(), null,
                        List.of(productId), null),
                "seed-interaction"
        );
        byte[] file = workbook(TZ_HEADERS, List.of(List.of(
                "Университет  альфа", "Вендор один", "Платформа", "Д-001", "да", "2027", "Передано", "Иван Петров", "", ""
        )));

        CatalogImportView preview = preview(file, tzColumns(), Map.of());

        assertThat(row(preview, 2).status()).isEqualTo(CatalogImportRowStatus.UPDATE);
        assertThat(row(preview, 2).oldValues()).containsEntry("organizationExternalKey", "").containsEntry("contractNumber", "");
        assertThat(row(preview, 2).newValues()).containsEntry("interaction", "Работа из интерфейса");
        applyEligible(preview);
        assertThat(count("organizations")).isEqualTo(1);
        assertThat(count("vendors")).isEqualTo(1);
        assertThat(count("products")).isEqualTo(1);
        assertThat(count("interactions")).isEqualTo(1);
        assertThat(count("product_agreements")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM organizations", String.class)).isEqualTo("Университет альфа");
        assertThat(jdbcTemplate.queryForObject("SELECT contract_number FROM product_agreements", String.class)).isEqualTo("Д-001");
        assertThat(jdbcTemplate.queryForList("""
                SELECT external_key FROM organizations UNION ALL SELECT external_key FROM vendors
                UNION ALL SELECT external_key FROM products UNION ALL SELECT external_key FROM product_agreements
                """, String.class)).doesNotContainNull();
        assertThat(preview(file, tzColumns(), Map.of()).rows())
                .extracting(CatalogImportRowView::status)
                .containsExactly(CatalogImportRowStatus.UNCHANGED);
    }

    @Test
    void archivedOrganizationIsAConflictAndArchivingAfterPreviewBlocksApply() throws IOException {
        UUID organizationId = UUID.randomUUID();
        insertOrganization(organizationId, "ORG-A", "Университет Альфа", IVAN);
        CatalogImportView beforeArchive = preview(agreementRow("Д-001"), tzColumns(), Map.of());
        assertThat(row(beforeArchive, 2).status()).isEqualTo(CatalogImportRowStatus.CREATE);

        jdbcTemplate.update("UPDATE organizations SET status = 'ARCHIVED' WHERE id = ?", organizationId);

        assertThatThrownBy(() -> applyEligible(beforeArchive)).isInstanceOf(InteractionConflictException.class);
        assertThat(count("interactions")).isZero();
        CatalogImportRowView archivedRow = row(preview(agreementRow("Д-001"), tzColumns(), Map.of()), 2);
        assertThat(archivedRow.status()).isEqualTo(CatalogImportRowStatus.CONFLICT);
        assertThat(archivedRow.fieldErrors().get("organizationName")).contains("в архиве");
    }

    @Test
    void explicitIdsTakePrecedenceOverKeysAndResolveAnAmbiguousManager() throws IOException {
        UUID organizationId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        insertOrganization(organizationId, "ORG-A", "Университет Альфа", IVAN);
        jdbcTemplate.update("INSERT INTO vendors (id, external_key, name, archived, version) VALUES (?, 'V-1', 'Вендор Один', FALSE, 0)",
                vendorId);
        jdbcTemplate.update("""
                INSERT INTO products (id, external_key, vendor_id, name, archived, version)
                VALUES (?, 'P-1', ?, 'Платформа', FALSE, 0)
                """, UUID.randomUUID(), vendorId);
        List<String> headers = new ArrayList<>(TZ_HEADERS);
        headers.add("Ключ вуза");
        Map<String, String> columns = tzColumns();
        columns.put("organizationExternalKey", "Ключ вуза");
        byte[] file = workbook(headers, List.of(List.of(
                "Университет Альфа имени Попова", "Вендор Один", "Платформа", "", "", "", "", "Анна Смирнова", "", "", "ORG-NEW"
        )));

        CatalogImportView withoutTargets = preview(file, columns, Map.of());
        CatalogImportView preview = preview(file, columns, Map.of(
                2, new CatalogImportRowTarget(organizationId, ANNA_A, null, null)
        ));

        assertThat(row(withoutTargets, 2).status()).isEqualTo(CatalogImportRowStatus.CONFLICT);
        assertThat(row(preview, 2).status()).isEqualTo(CatalogImportRowStatus.CREATE);
        assertThat(row(preview, 2).oldValues()).containsEntry("managerName", "Иван Петров");
        assertThat(row(preview, 2).newValues()).containsEntry("managerName", "Анна Смирнова");
        applyEligible(preview);
        assertThat(jdbcTemplate.queryForMap("SELECT name, external_key, owner_manager_id FROM organizations"))
                .containsEntry("NAME", "Университет Альфа имени Попова")
                .containsEntry("EXTERNAL_KEY", "ORG-A")
                .containsEntry("OWNER_MANAGER_ID", ANNA_A);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT previous_owner_manager_display_name, new_owner_manager_display_name, actor_display_name
                FROM organization_assignment_events
                """)).containsEntry("PREVIOUS_OWNER_MANAGER_DISPLAY_NAME", "Иван Петров")
                .containsEntry("NEW_OWNER_MANAGER_DISPLAY_NAME", "Анна Смирнова")
                .containsEntry("ACTOR_DISPLAY_NAME", "Администратор импорта");
        assertThat(jdbcTemplate.queryForList("SELECT access_revision FROM crm_user_profiles WHERE id IN (?, ?)",
                Integer.class, IVAN, ANNA_A)).containsOnly(1);
        assertThat(count("product_agreements")).isEqualTo(1);
    }

    @Test
    void newUniversityRowsShareTheManagerAndEveryProductIsLinked() throws IOException {
        byte[] file = workbook(TZ_HEADERS, List.of(
                List.of("Университет Альфа", "Вендор Один", "Платформа", "", "", "", "", "Иван Петров", "", ""),
                List.of("Университет Альфа", "Вендор Один", "Аналитика", "", "", "", "", "", "", ""),
                List.of("Университет Альфа", "Вендор Один", "Платформа", "Д-001", "да", "", "", "", "", "")
        ));

        CatalogImportView preview = preview(file, tzColumns(), Map.of());

        assertThat(preview.rows()).extracting(CatalogImportRowView::status).containsOnly(CatalogImportRowStatus.CREATE);
        assertThat(row(preview, 3).newValues()).containsEntry("managerName", "Иван Петров");
        applyEligible(preview);
        assertThat(count("organizations")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT owner_manager_id FROM organizations", UUID.class)).isEqualTo(IVAN);
        assertThat(count("products")).isEqualTo(2);
        assertThat(count("interactions")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("SELECT contract_number FROM product_agreements", String.class))
                .containsExactlyInAnyOrder(null, "Д-001");
        assertThat(preview(file, tzColumns(), Map.of()).rows())
                .extracting(CatalogImportRowView::status)
                .containsOnly(CatalogImportRowStatus.UNCHANGED);
    }

    @Test
    void threeColumnRowLinksTheProductAndAChangedNumberNeedsTheAgreementUuid() throws IOException {
        insertOrganization(UUID.randomUUID(), null, "Университет Альфа", IVAN);
        Map<String, String> threeColumns = new LinkedHashMap<>();
        for (int index = 0; index < 3; index++) {
            threeColumns.put(TZ_FIELDS.get(index), TZ_HEADERS.get(index));
        }
        byte[] linkOnly = workbook(TZ_HEADERS.subList(0, 3), List.of(List.of("Университет Альфа", "Вендор Один", "Платформа")));

        CatalogImportView linked = preview(linkOnly, threeColumns, Map.of());
        applyEligible(linked);

        assertThat(row(linked, 2).status()).isEqualTo(CatalogImportRowStatus.CREATE);
        assertThat(count("interactions")).isEqualTo(1);
        assertThat(count("product_agreements")).isEqualTo(1);

        applyEligible(preview(agreementRow("Д-1"), tzColumns(), Map.of()));
        CatalogImportView renumbered = preview(agreementRow("Д-01"), tzColumns(), Map.of());
        UUID agreementId = jdbcTemplate.queryForObject("SELECT id FROM product_agreements", UUID.class);
        CatalogImportView corrected = preview(agreementRow("Д-01"), tzColumns(), Map.of(
                2, new CatalogImportRowTarget(null, null, null, agreementId)
        ));

        assertThat(row(renumbered, 2).status()).isEqualTo(CatalogImportRowStatus.CONFLICT);
        assertThat(row(renumbered, 2).fieldErrors().get("contractNumber")).contains("№ Д-1");
        assertThat(row(corrected, 2).status()).isEqualTo(CatalogImportRowStatus.UPDATE);
        applyEligible(corrected);
        assertThat(jdbcTemplate.queryForList("SELECT contract_number FROM product_agreements", String.class))
                .containsExactly("Д-01");
        assertThat(preview(linkOnly, threeColumns, Map.of()).rows())
                .extracting(CatalogImportRowView::status)
                .containsExactly(CatalogImportRowStatus.UNCHANGED);
    }

    @Test
    void conflictingDuplicatesInsideTheFileAreRowConflictsAndOtherRowsStillApply() throws IOException {
        byte[] file = workbook(TZ_HEADERS, List.of(
                List.of("Университет Альфа", "Вендор Один", "Платформа", "", "", "", "", "Иван Петров", "", ""),
                List.of("университет  АЛЬФА", "Вендор Один", "Платформа", "", "", "", "", "Иван Петров", "", ""),
                List.of("Университет Бета", "Вендор Один", "Платформа", "", "", "", "", "Иван Петров", "", "")
        ));

        CatalogImportView preview = preview(file, tzColumns(), Map.of());

        assertThat(preview.rows()).extracting(CatalogImportRowView::status).containsExactly(
                CatalogImportRowStatus.CONFLICT, CatalogImportRowStatus.CONFLICT, CatalogImportRowStatus.CREATE
        );
        assertThat(row(preview, 4).fieldErrors()).isEmpty();
        assertThat(row(preview, 3).fieldErrors().get("organizationName")).startsWith("Противоречит строке 2");
        applyEligible(preview);
        assertThat(jdbcTemplate.queryForList("SELECT name FROM organizations", String.class))
                .containsExactly("Университет Бета");
    }

    @Test
    void directionWithoutKeyReceivesKeyAndIsRenamedByKeyLater() throws IOException {
        jdbcTemplate.update("INSERT INTO directions (id, name, archived, version) VALUES (?, 'Информационная безопасность', FALSE, 0)",
                UUID.randomUUID());
        List<String> headers = List.of("directionKey", "directionName", "programKey", "programName", "directionRef");
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("directionExternalKey", "directionKey");
        columns.put("directionName", "directionName");
        columns.put("programExternalKey", "programKey");
        columns.put("programName", "programName");
        columns.put("programDirectionRef", "directionRef");
        byte[] first = workbook(headers, List.of(List.of("dir:ib", "Информационная безопасность", "prog:soc", "Центр мониторинга", "dir:ib")));
        byte[] renamed = workbook(headers, List.of(List.of("dir:ib", "Кибербезопасность", "prog:soc", "Центр мониторинга", "dir:ib")));

        CatalogImportView created = previewDirections(first, columns);
        applyEligible(created);
        CatalogImportView rename = previewDirections(renamed, columns);
        applyEligible(rename);

        assertThat(row(created, 2).oldValues()).containsEntry("directionExternalKey", "");
        assertThat(row(rename, 2).status()).isEqualTo(CatalogImportRowStatus.UPDATE);
        assertThat(jdbcTemplate.queryForMap("SELECT name, external_key FROM directions"))
                .containsEntry("NAME", "Кибербезопасность").containsEntry("EXTERNAL_KEY", "dir:ib");
        assertThat(count("programs")).isEqualTo(1);
        assertThat(journal()).containsExactlyInAnyOrder(
                "PROGRAM CREATE Центр мониторинга: Направление: Информационная безопасность; импорт каталога",
                "DIRECTION UPDATE Кибербезопасность: Название: «Информационная безопасность» → «Кибербезопасность»; импорт каталога"
        );
        assertThat(previewDirections(renamed, columns).rows())
                .extracting(CatalogImportRowView::status)
                .containsExactly(CatalogImportRowStatus.UNCHANGED);
    }

    @Test
    void conflictListsCandidatesContactsAreSplitAndUniversityWithoutManagerAwaitsAssignment() throws IOException {
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, 'team-a'), (?, 'team-b')", TEAM_A, TEAM_B);
        byte[] file = workbook(TZ_HEADERS, List.of(
                List.of("Университет Альфа", "Вендор Один", "Платформа", "Д-001", "", "", "", "Анна Смирнова", "", ""),
                List.of("Колледж без менеджера", "Вендор Один", "Облако", "", "", "", "", "", "Смирнова Ольга", ""),
                List.of("Университет Бета", "Вендор Один", "Платформа", "Д-002", "", "", "", "Иван Петров",
                        "Сидорова Анна; Кузнецов Илья\nОрлова Мария", "")
        ));

        CatalogImportView withoutTeam = preview(file, tzColumns(), Map.of());

        assertThat(row(withoutTeam, 2).status()).isEqualTo(CatalogImportRowStatus.CONFLICT);
        assertThat(row(withoutTeam, 2).managerCandidates())
                .extracting(CatalogImportManagerCandidate::profileId, CatalogImportManagerCandidate::teamName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ANNA_A, "team-a"),
                        org.assertj.core.groups.Tuple.tuple(ANNA_B, "team-b")
                );
        assertThat(row(withoutTeam, 3).fieldErrors().get("managerName")).contains("выберите команду");
        assertThat(row(withoutTeam, 4).newValues()).containsEntry("contactName", "Сидорова Анна; Кузнецов Илья; Орлова Мария");

        CatalogImportPreviewResponse response = service.preview(admin, file(file), CatalogImportProfile.AGREEMENT, "Каталог",
                new CatalogImportMapping(tzColumns(), Map.of(), Map.of(), TEAM_B));
        CatalogImportView preview = service.get(admin, response.importId());

        assertThat(row(preview, 3).status()).isEqualTo(CatalogImportRowStatus.CREATE);
        assertThat(row(preview, 3).newValues()).containsEntry("managerName", "Требует назначения");
        applyEligible(preview);

        assertThat(jdbcTemplate.queryForMap("SELECT team_id, owner_manager_id FROM organizations WHERE name = 'Колледж без менеджера'"))
                .containsEntry("TEAM_ID", TEAM_B)
                .containsEntry("OWNER_MANAGER_ID", null);
        assertThat(jdbcTemplate.queryForList("""
                SELECT contact.name FROM contacts contact
                JOIN organizations organization ON organization.id = contact.organization_id
                WHERE organization.name = 'Университет Бета' ORDER BY contact.name
                """, String.class)).containsExactly("Кузнецов Илья", "Орлова Мария", "Сидорова Анна");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations WHERE name = 'Университет Альфа'", Long.class))
                .isZero();

        byte[] shortRegistry = workbook(TZ_HEADERS, List.of(
                List.of("Университет Бета", "Вендор Один", "Облако", "Д-009", "", "", "", "Иван Петров", "", "")
        ));
        CatalogImportView missing = preview(shortRegistry, tzColumns(), Map.of());
        UUID agreementId = jdbcTemplate.queryForObject("SELECT id FROM product_agreements WHERE contract_number = 'Д-002'", UUID.class);

        assertThat(missing.missingRecords()).singleElement().satisfies(record -> {
            assertThat(record.agreementId()).isEqualTo(agreementId);
            assertThat(record.organizationName()).isEqualTo("Университет Бета");
            assertThat(record.productName()).isEqualTo("Платформа");
        });
        assertThatThrownBy(() -> service.apply(admin, missing.id(),
                new CatalogImportApplyRequest(missing.version(), List.of(), List.of(UUID.randomUUID())), "archive-foreign"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("archiveAgreementIds"));

        service.apply(admin, missing.id(), new CatalogImportApplyRequest(missing.version(), List.of(), List.of(agreementId)),
                "archive-missing");

        assertThat(jdbcTemplate.queryForObject("SELECT archived_at FROM product_agreements WHERE id = ?", Object.class, agreementId))
                .isNotNull();
        assertThat(jdbcTemplate.queryForList("SELECT action FROM catalog_change_events WHERE entity_id = ? ORDER BY occurred_at",
                String.class, agreementId)).containsExactly("CREATE", "ARCHIVE");
        assertThat(preview(shortRegistry, tzColumns(), Map.of()).missingRecords()).isEmpty();

        CatalogImportView restored = preview(file, tzColumns(), Map.of());
        assertThat(row(restored, 4).newValues()).containsEntry("agreementArchived", "нет");
        applyEligible(restored);
        assertThat(jdbcTemplate.queryForObject("SELECT archived_at FROM product_agreements WHERE id = ?", Object.class, agreementId))
                .isNull();
    }

    @Test
    void emailAndPhoneInResponsibleCellGoToContactFieldsAndFindTheExistingContact() throws IOException {
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, 'team-a'), (?, 'team-b')", TEAM_A, TEAM_B);
        byte[] first = workbook(TZ_HEADERS, List.of(
                List.of("Университет Бета", "Вендор Один", "Платформа", "Д-002", "", "", "", "Иван Петров", "Олег Демонстрационный", "")
        ));
        applyEligible(preview(first, tzColumns(), Map.of()));
        byte[] file = workbook(TZ_HEADERS, List.of(
                List.of("Университет Бета", "Вендор Один", "Платформа", "Д-002", "", "", "", "Иван Петров",
                        "Олег Демонстрационный, oleg.demo@example.test; Мария Примерова, +7 (900) 111-22-33", ""),
                List.of("Университет Бета", "Вендор Один", "Облако", "Д-003", "", "", "", "Иван Петров",
                        "Олег Демонстрационный, oleg.demo@example", ""),
                List.of("Университет Бета", "Вендор Один", "Сервер", "Д-004", "", "", "", "Иван Петров",
                        "Анна Первая, Илья Второй, anna@example.test", ""),
                List.of("Университет Бета", "Вендор Один", "Шлюз", "Д-005", "", "", "", "Иван Петров",
                        "Пётр Одиночный, petr.demo@example.test", "")
        ));

        CatalogImportView preview = preview(file, tzColumns(), Map.of());

        assertThat(row(preview, 2).newValues())
                .containsEntry("contactName", "Олег Демонстрационный; Мария Примерова")
                .containsEntry("contactDetails", "Олег Демонстрационный: oleg.demo@example.test");
        assertThat(row(preview, 3).fieldErrors().get("contactName")).contains("указана неверно");
        assertThat(row(preview, 4).fieldErrors().get("contactName")).contains("одно ФИО");
        applyEligible(preview);

        assertThat(jdbcTemplate.queryForList("SELECT name, email, phone FROM contacts ORDER BY name"))
                .extracting(values -> values.get("NAME") + "|" + values.get("EMAIL") + "|" + values.get("PHONE"))
                .containsExactly("Мария Примерова|null|+79001112233", "Олег Демонстрационный|oleg.demo@example.test|null",
                        "Пётр Одиночный|petr.demo@example.test|null");
    }

    @Test
    void vendorFileOfOrganizerFormatCreatesVendorsProductsAndContactsAndRepeatsUnchanged() throws IOException {
        CatalogImportView preview = previewVendors(workbook(VENDOR_HEADERS, VENDOR_ROWS));

        assertThat(preview.rows()).extracting(CatalogImportRowView::status).containsOnly(CatalogImportRowStatus.CREATE);
        assertThat(row(preview, 3).newValues())
                .containsEntry("vendorName", "ООО «ТДата»")
                .containsEntry("productNames", "RT.DataLake, RT.Warehouse")
                .containsEntry("vendorContactPhone", "+79001000002")
                .containsEntry("vendorContactChannels", "Telegram");
        assertThat(row(preview, 6).newValues()).containsEntry("vendorContactChannels", "Почта, Telegram");

        applyEligible(preview);

        assertThat(count("vendors")).isEqualTo(5);
        assertThat(count("products")).isEqualTo(9);
        assertThat(count("vendor_contacts")).isEqualTo(8);
        assertThat(journal()).filteredOn(entry -> entry.startsWith("VENDOR CREATE")).hasSize(5);
        assertThat(journal()).filteredOn(entry -> entry.startsWith("PRODUCT CREATE")).hasSize(9)
                .contains("PRODUCT CREATE RT.Warehouse: Вендор: ООО «ТДата»; контакт: Контакт вендора Демо 2; импорт каталога");
        assertThat(jdbcTemplate.queryForList("SELECT name FROM vendors ORDER BY name", String.class))
                .containsExactlyInAnyOrder("ООО «Базис»", "ООО «РТК ИТ Плюс»", "ООО «РТК ИТ»", "ООО «ТДата»", "ПАО «Ростелеком»");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT c.phone, c.email, c.prefers_email, c.prefers_telegram, COUNT(p.id) AS products
                FROM vendor_contacts c JOIN products p ON p.vendor_contact_id = c.id
                WHERE c.name = 'Контакт вендора Демо 2'
                GROUP BY c.phone, c.email, c.prefers_email, c.prefers_telegram
                """))
                .containsEntry("PHONE", "+79001000002")
                .containsEntry("EMAIL", "vendor02@example.test")
                .containsEntry("PREFERS_EMAIL", false)
                .containsEntry("PREFERS_TELEGRAM", true)
                .containsEntry("PRODUCTS", 2L);
        assertThat(jdbcTemplate.queryForList("SELECT action FROM catalog_change_events WHERE entity_type = 'VENDOR_CONTACT'",
                String.class)).hasSize(8).containsOnly("CREATE");
        assertThat(jdbcTemplate.queryForList("SELECT changes FROM catalog_change_events", String.class))
                .noneMatch(changes -> changes.contains("example.test") || changes.contains("+7"));

        CatalogImportView repeated = previewVendors(workbook(VENDOR_HEADERS, VENDOR_ROWS));

        assertThat(repeated.rows()).extracting(CatalogImportRowView::status).containsOnly(CatalogImportRowStatus.UNCHANGED);
    }

    @Test
    void vendorRowsWithUnknownChannelOrProductOfAnotherCompanyAreReportedWithoutStoppingOthers() throws IOException {
        List<List<String>> rows = new ArrayList<>(VENDOR_ROWS);
        rows.set(0, List.of("ООО «Базис»", "«Базис Dynamix»", "Контакт вендора Демо 1", "+7 (900) 100-00-01",
                "vendor01@example.test", "Голубиная почта"));
        rows.add(List.of("ООО «Базис»", "«Яга»", "Контакт вендора Демо 9", "+7 (900) 100-00-09", "vendor09@example.test", "Почта"));

        CatalogImportView preview = previewVendors(workbook(VENDOR_HEADERS, rows));

        assertThat(row(preview, 2).status()).isEqualTo(CatalogImportRowStatus.INVALID);
        assertThat(row(preview, 2).fieldErrors()).containsOnlyKeys("vendorContactChannels");
        assertThat(row(preview, 10).status()).isEqualTo(CatalogImportRowStatus.CONFLICT);
        assertThat(row(preview, 10).fieldErrors().get("productNames")).contains("продукт указан у другого вендора");
        assertThat(row(preview, 3).status()).isEqualTo(CatalogImportRowStatus.CREATE);
    }

    @Test
    void agreementWithShortVendorNameFindsVendorCreatedWithLegalFormAndKeepsItsName() throws IOException {
        applyEligible(preview(workbook(TZ_HEADERS, List.of(List.of(
                "Университет Альфа", "ООО «Базис»", "Базис Dynamix", "Д-001", "", "", "", "Иван Петров", "", ""
        ))), tzColumns(), Map.of()));

        CatalogImportView preview = preview(workbook(TZ_HEADERS, List.of(List.of(
                "Университет Альфа", "Базис", "Базис Dynamix", "Д-001", "", "", "", "Иван Петров", "", ""
        ))), tzColumns(), Map.of());

        assertThat(row(preview, 2).status()).isEqualTo(CatalogImportRowStatus.UNCHANGED);
        assertThat(row(preview, 2).newValues()).containsEntry("vendorName", "ООО «Базис»");
        applyEligible(preview);
        assertThat(jdbcTemplate.queryForList("SELECT name FROM vendors", String.class)).containsExactly("ООО «Базис»");
        assertThat(count("products")).isEqualTo(1);
    }

    @Test
    void agreementWithShortVendorNameFindsVendorImportedWithContacts() throws IOException {
        applyEligible(previewVendors(workbook(VENDOR_HEADERS, VENDOR_ROWS)));
        String key = jdbcTemplate.queryForObject("SELECT external_key FROM vendors WHERE name = 'ООО «Базис»'", String.class);

        applyEligible(preview(workbook(TZ_HEADERS, List.of(List.of(
                "Университет Альфа", "Базис", "Базис Dynamix", "Д-001", "", "", "", "Иван Петров", "", ""
        ))), tzColumns(), Map.of()));

        assertThat(count("vendors")).isEqualTo(5);
        assertThat(count("products")).isEqualTo(9);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM vendors WHERE external_key = ?", String.class, key))
                .isEqualTo("ООО «Базис»");
    }

    @Test
    void savedVendorKeyIsNotRecalculatedAndSeparateSpellingsOfOneVendorAreConflict() throws IOException {
        UUID legacyId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO vendors (id, external_key, name, archived, version) VALUES (?, 'vendor:ооо «базис»', 'ООО «Базис»', FALSE, 0)
                """, legacyId);
        applyEligible(preview(workbook(TZ_HEADERS, List.of(List.of(
                "Университет Альфа", "Базис", "Базис Dynamix", "Д-001", "", "", "", "Иван Петров", "", ""
        ))), tzColumns(), Map.of()));
        assertThat(jdbcTemplate.queryForObject("SELECT external_key FROM vendors WHERE id = ?", String.class, legacyId))
                .isEqualTo("vendor:ооо «базис»");
        assertThat(count("vendors")).isEqualTo(1);

        jdbcTemplate.update("INSERT INTO vendors (id, name, archived, version) VALUES (?, 'Базис', FALSE, 0)", UUID.randomUUID());

        CatalogImportView agreement = preview(workbook(TZ_HEADERS, List.of(List.of(
                "Университет Альфа", "Базис", "Базис Dynamix", "Д-003", "", "", "", "Иван Петров", "", ""
        ))), tzColumns(), Map.of());
        CatalogImportView vendors = previewVendors(workbook(VENDOR_HEADERS, VENDOR_ROWS.subList(0, 1)));

        assertThat(row(agreement, 2).status()).isEqualTo(CatalogImportRowStatus.CONFLICT);
        assertThat(row(agreement, 2).fieldErrors()).containsKey("vendorName");
        assertThat(row(vendors, 2).status()).isEqualTo(CatalogImportRowStatus.CONFLICT);
        assertThat(row(vendors, 2).fieldErrors().get("vendorName")).contains("«ООО «Базис»»", "«Базис»");
    }

    private CatalogImportView previewVendors(byte[] file) {
        Map<String, String> columns = new LinkedHashMap<>();
        List<String> fields = List.of("vendorName", "productNames", "vendorContactName", "vendorContactPhone", "vendorContactEmail",
                "vendorContactChannels");
        for (int index = 0; index < fields.size(); index++) {
            columns.put(fields.get(index), VENDOR_HEADERS.get(index));
        }
        CatalogImportPreviewResponse response = service.preview(admin, file(file), CatalogImportProfile.VENDOR_CONTACTS, "Каталог",
                new CatalogImportMapping(columns, Map.of(), Map.of(), null));
        return service.get(admin, response.importId());
    }

    private CatalogImportView preview(byte[] file, Map<String, String> columns, Map<Integer, CatalogImportRowTarget> targets) {
        CatalogImportPreviewResponse response = service.preview(admin, file(file), CatalogImportProfile.AGREEMENT, "Каталог",
                new CatalogImportMapping(columns, targets, Map.of(), null));
        return service.get(admin, response.importId());
    }

    private CatalogImportView previewDirections(byte[] file, Map<String, String> columns) {
        CatalogImportPreviewResponse response = service.preview(admin, file(file), CatalogImportProfile.DIRECTION_PROGRAM,
                "Каталог", new CatalogImportMapping(columns, Map.of(), Map.of(), null));
        return service.get(admin, response.importId());
    }

    private void applyEligible(CatalogImportView view) {
        List<UUID> rowIds = view.rows().stream()
                .filter(row -> row.status() == CatalogImportRowStatus.CREATE || row.status() == CatalogImportRowStatus.UPDATE
                        || row.status() == CatalogImportRowStatus.UNCHANGED)
                .map(CatalogImportRowView::id)
                .toList();
        service.apply(admin, view.id(), new CatalogImportApplyRequest(view.version(), rowIds, List.of()), UUID.randomUUID().toString());
    }

    private static CatalogImportRowView row(CatalogImportView view, int rowNumber) {
        return view.rows().stream().filter(row -> row.rowNumber() == rowNumber).findFirst().orElseThrow();
    }

    private static Map<String, String> tzColumns() {
        Map<String, String> columns = new LinkedHashMap<>();
        for (int index = 0; index < TZ_FIELDS.size(); index++) {
            columns.put(TZ_FIELDS.get(index), TZ_HEADERS.get(index));
        }
        return columns;
    }

    private static byte[] agreementRow(String contractNumber) throws IOException {
        return workbook(TZ_HEADERS, List.of(List.of(
                "Университет Альфа", "Вендор Один", "Платформа", contractNumber, "", "", "", "", "", ""
        )));
    }

    private static byte[] workbook(List<String> headers, List<List<String>> rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Каталог");
            Row header = sheet.createRow(0);
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                List<String> values = rows.get(rowIndex);
                for (int index = 0; index < values.size(); index++) {
                    row.createCell(index).setCellValue(values.get(index));
                }
            }
            workbook.write(bytes);
            return bytes.toByteArray();
        }
    }

    private static MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "catalog.xlsx", "application/octet-stream", content);
    }

    private List<String> journal() {
        return jdbcTemplate.queryForList("SELECT entity_type, action, entity_name, changes FROM catalog_change_events").stream()
                .map(values -> values.get("ENTITY_TYPE") + " " + values.get("ACTION") + " " + values.get("ENTITY_NAME") + ": "
                        + values.get("CHANGES"))
                .toList();
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void insertProfile(UUID id, String displayName, String role, UUID teamId) {
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, display_name, role, team_id, active, access_revision, updated_at)
                VALUES (?, ?, ?, ?, TRUE, 0, CURRENT_TIMESTAMP)
                """, id, displayName, role, teamId);
    }

    private void insertOrganization(UUID id, String externalKey, String name, UUID ownerManagerId) {
        jdbcTemplate.update("""
                INSERT INTO organizations (id, external_key, name, type, team_id, owner_manager_id, version, updated_at)
                VALUES (?, ?, ?, 'UNIVERSITY', ?, ?, 0, CURRENT_TIMESTAMP)
                """, id, externalKey, name, TEAM_A, ownerManagerId);
    }

    private void insertDefaultTemplate() {
        UUID templateId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO workflow_templates (id, team_id, name, default_template, version, created_at, updated_at)
                VALUES (?, NULL, 'Базовый процесс', TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, templateId);
        List<UUID> stageIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<String> names = List.of("Поиск контакта", "Встреча", "Подписание");
        for (int order = 0; order < stageIds.size(); order++) {
            jdbcTemplate.update("""
                    INSERT INTO workflow_template_stages (id, template_id, stage_order, name, optional)
                    VALUES (?, ?, ?, ?, FALSE)
                    """, stageIds.get(order), templateId, order, names.get(order));
        }
        for (int order = 0; order + 1 < stageIds.size(); order++) {
            jdbcTemplate.update("""
                    INSERT INTO workflow_template_transitions (template_id, from_stage_id, to_stage_id, comment_required)
                    VALUES (?, ?, ?, FALSE)
                    """, templateId, stageIds.get(order), stageIds.get(order + 1));
        }
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
                CREATE TABLE IF NOT EXISTS teams (id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL, archived BOOLEAN DEFAULT FALSE NOT NULL, default_workflow_template_id UUID)
                """,
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (partner_organization_id UUID, partner_contact_id UUID, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, 
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
                    id UUID PRIMARY KEY, external_key VARCHAR(200) UNIQUE, name VARCHAR(200) NOT NULL UNIQUE,
                    archived BOOLEAN NOT NULL, version INTEGER NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS programs (
                    id UUID PRIMARY KEY, external_key VARCHAR(200) UNIQUE, direction_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL, archived BOOLEAN NOT NULL, version INTEGER NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP, UNIQUE (direction_id, name)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS vendors (
                    id UUID PRIMARY KEY, external_key VARCHAR(200) UNIQUE, name VARCHAR(200) NOT NULL UNIQUE,
                    archived BOOLEAN NOT NULL, version INTEGER NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS products (
                    id UUID PRIMARY KEY, vendor_contact_id UUID, external_key VARCHAR(200) UNIQUE, vendor_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL, archived BOOLEAN NOT NULL, version INTEGER NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP, UNIQUE (vendor_id, name)
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
                CREATE TABLE IF NOT EXISTS interactions (next_step_partner_visible BOOLEAN DEFAULT FALSE NOT NULL, 
                    work_status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, work_status_reason VARCHAR(1000), waiting_on VARCHAR(16), waiting_note VARCHAR(500), problem VARCHAR(1000), risk_level VARCHAR(16), risk_reason VARCHAR(1000),
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL,
                    current_stage_id UUID NOT NULL, next_action VARCHAR(500), next_action_at TIMESTAMP WITH TIME ZONE,
                    program_id UUID, last_contact_at TIMESTAMP WITH TIME ZONE, version INTEGER NOT NULL,
                    created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS product_agreements (
                    id UUID PRIMARY KEY, external_key VARCHAR(200) UNIQUE, interaction_id UUID NOT NULL,
                    product_id UUID NOT NULL, contract_number VARCHAR(200), license_signed BOOLEAN,
                    license_expiry_year INTEGER, transfer_status VARCHAR(160), scan_attachment_id UUID, version INTEGER DEFAULT 0 NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, archived_at TIMESTAMP WITH TIME ZONE
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
                CREATE TABLE IF NOT EXISTS interaction_event_contacts (
                    event_id UUID NOT NULL,
                    contact_id UUID NOT NULL,
                    change_type VARCHAR(16) NOT NULL,
                    PRIMARY KEY (event_id, contact_id)
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
                CREATE TABLE IF NOT EXISTS catalog_imports (
                    id UUID PRIMARY KEY, created_by UUID NOT NULL, profile VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL,
                    version INTEGER NOT NULL, mapping_json VARCHAR(100000) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS catalog_import_rows (
                    id UUID PRIMARY KEY, import_id UUID NOT NULL, sheet_name VARCHAR(255) NOT NULL,
                    row_number INTEGER NOT NULL, status VARCHAR(16) NOT NULL, plan_json VARCHAR(100000) NOT NULL,
                    errors_json VARCHAR(100000) NOT NULL, applied BOOLEAN NOT NULL, UNIQUE (import_id, row_number)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS catalog_import_jobs (
                    id UUID PRIMARY KEY, import_id UUID NOT NULL, created_by UUID NOT NULL, action VARCHAR(16) NOT NULL,
                    status VARCHAR(16) NOT NULL, result_json VARCHAR(10000) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, completed_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """
        ).forEach(jdbcTemplate::execute);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ImportTestConfiguration {
        @Bean
        @ConditionalOnMissingBean(ObjectMapper.class)
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        AttachmentContentValidator attachmentContentValidator() {
            return new AttachmentContentValidator(new AttachmentProperties(
                    Path.of("target", "catalog-import-test-files"), "localhost", 3310,
                    Duration.ofSeconds(1), Duration.ofSeconds(1), DataSize.ofMegabytes(5)
            ));
        }

        @Bean
        AttachmentScanner attachmentScanner() {
            return (input, sizeBytes) -> AttachmentScanOutcome.CLEAN;
        }
    }
}
