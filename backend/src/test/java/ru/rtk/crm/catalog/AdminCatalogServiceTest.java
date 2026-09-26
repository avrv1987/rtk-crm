package ru.rtk.crm.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import ru.rtk.crm.access.AdminCrmProfileAccessDeniedException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:admin_catalogs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        AdminCatalogRepository.class,
        AdminCatalogService.class,
        VendorContactRepository.class,
        VendorContactService.class,
        CatalogRepository.class,
        CatalogChangeEventRepository.class,
        CommandIdempotencyRepository.class,
        IdempotentCommandRunner.class,
        AdminCatalogServiceTest.JsonConfiguration.class
})
class AdminCatalogServiceTest {
    private static final UUID ADMIN = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID LEADER = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID TEAM = UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final UUID DIRECTION = UUID.fromString("40000000-0000-0000-0000-000000000010");
    private static final UUID PROGRAM = UUID.fromString("40000000-0000-0000-0000-000000000011");
    private static final UUID VENDOR = UUID.fromString("40000000-0000-0000-0000-000000000020");
    private static final String REQUEST_ID = "admin-catalog-request";

    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);
    private final CrmProfile leader = new CrmProfile(LEADER, UserRole.LEADER, TEAM, 0);

    @Autowired
    private AdminCatalogService service;

    @Autowired
    private VendorContactService vendorContactService;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private CatalogChangeEventRepository catalogChangeEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of(
                "catalog_change_events", "command_idempotency_records", "vendor_contacts", "products", "vendors", "programs", "directions",
                "crm_user_profiles"
        )) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO crm_user_profiles (id, display_name) VALUES (?, 'Администратор'), (?, 'Руководитель')",
                ADMIN, LEADER);
        jdbcTemplate.update("INSERT INTO directions (id, name, archived, version) VALUES (?, 'Кибербезопасность', FALSE, 0)", DIRECTION);
        jdbcTemplate.update("INSERT INTO programs (id, direction_id, name, archived, version) VALUES (?, ?, 'UAT-программа (архив)', FALSE, 0)",
                PROGRAM, DIRECTION);
        jdbcTemplate.update("INSERT INTO vendors (id, name, archived, version) VALUES (?, 'Демо-вендор: РТК', FALSE, 0)", VENDOR);
    }

    @Test
    void administratorAddsVendorAndProductThatBecomeAvailableAndDuplicatesAreRejected() {
        AdminCatalogEntry vendor = service.create(admin, CatalogKind.VENDORS, new CatalogEntryRequest(" UAT  Вендор ", null, null, null),
                "vendor-create", REQUEST_ID);
        AdminCatalogEntry replayed = service.create(admin, CatalogKind.VENDORS, new CatalogEntryRequest(" UAT  Вендор ", null, null, null),
                "vendor-create", REQUEST_ID);
        AdminCatalogEntry product = service.create(admin, CatalogKind.PRODUCTS, new CatalogEntryRequest("UAT Продукт", vendor.id(), null, null),
                "product-create", REQUEST_ID);

        assertThat(replayed).isEqualTo(vendor);
        assertThat(vendor.name()).isEqualTo("UAT Вендор");
        assertThat(product.parentName()).isEqualTo("UAT Вендор");
        assertThat(catalogRepository.findProducts(CatalogQuery.from(0, 25), CatalogEntryState.ACTIVE).items())
                .extracting(CatalogLookup::name).contains("UAT Продукт");
        assertThatThrownBy(() -> service.create(admin, CatalogKind.VENDORS, new CatalogEntryRequest("uat вендор", null, null, null),
                "vendor-duplicate", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.getMessage()).contains("«UAT Вендор» уже есть"));
        assertThatThrownBy(() -> service.create(admin, CatalogKind.PRODUCTS, new CatalogEntryRequest("UAT Продукт", null, null, null),
                "product-without-vendor", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("parentId"));
        assertThatThrownBy(() -> service.create(leader, CatalogKind.VENDORS, new CatalogEntryRequest("Чужой", null, null, null),
                "leader-create", REQUEST_ID))
                .isInstanceOf(AdminCrmProfileAccessDeniedException.class);
        assertThat(catalogChangeEventRepository.findPage(null, 0, 10).items())
                .extracting(CatalogChangeEvent::entityType)
                .containsExactlyInAnyOrder(CatalogEntityType.VENDOR, CatalogEntityType.PRODUCT);
    }

    @Test
    void vendorNameRuleRejectsSecondSpellingButAllowsRenamingTheSameVendor() {
        AdminCatalogEntry vendor = service.create(admin, CatalogKind.VENDORS, new CatalogEntryRequest("ООО «Базис»", null, null, null),
                "basis", REQUEST_ID);

        assertThatThrownBy(() -> service.create(admin, CatalogKind.VENDORS, new CatalogEntryRequest("Базис", null, null, null),
                "basis-short", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.getMessage()).contains("«ООО «Базис»» уже есть"));
        AdminCatalogEntry renamed = service.update(admin, CatalogKind.VENDORS, vendor.id(),
                new CatalogEntryRequest("АО «Базис»", null, null, 0), "basis-rename", REQUEST_ID);

        assertThat(renamed.name()).isEqualTo("АО «Базис»");
    }

    @Test
    void vendorContactsAreLinkedToProductsOfTheirVendorAndJournaledWithoutContactValues() {
        AdminCatalogEntry vendor = service.create(admin, CatalogKind.VENDORS, new CatalogEntryRequest("ООО «Базис»", null, null, null),
                "basis", REQUEST_ID);
        AdminCatalogEntry product = service.create(admin, CatalogKind.PRODUCTS,
                new CatalogEntryRequest("Базис Dynamix", vendor.id(), null, null), "dynamix", REQUEST_ID);
        AdminCatalogEntry otherProduct = service.create(admin, CatalogKind.PRODUCTS,
                new CatalogEntryRequest("Чужой продукт", VENDOR, null, null), "other", REQUEST_ID);
        VendorContactRequest request = new VendorContactRequest(" Контакт  вендора Демо ", "8 (900) 100-00-01", "demo@example.test",
                true, false, null, List.of(product.id()), null);

        VendorContact contact = vendorContactService.create(admin, vendor.id(), request, "contact", REQUEST_ID);

        assertThat(vendorContactService.create(admin, vendor.id(), request, "contact", REQUEST_ID)).isEqualTo(contact);
        assertThat(contact.name()).isEqualTo("Контакт вендора Демо");
        assertThat(contact.phone()).isEqualTo("+79001000001");
        assertThat(contact.products()).extracting(CatalogReference::id).containsExactly(product.id());
        assertThatThrownBy(() -> vendorContactService.create(admin, vendor.id(), new VendorContactRequest("Второй", null,
                "DEMO@example.test", null, null, null, null, null), "duplicate", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("email"));
        assertThatThrownBy(() -> vendorContactService.create(admin, vendor.id(), new VendorContactRequest("Третий", null, null,
                null, null, null, List.of(otherProduct.id()), null), "foreign-product", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("productIds"));
        assertThatThrownBy(() -> vendorContactService.create(admin, vendor.id(), new VendorContactRequest("Четвёртый", "12345",
                null, null, null, null, null, null), "bad-phone", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
                    assertThat(exception.field()).isEqualTo("phone");
                    assertThat(exception.getMessage()).doesNotContain("12345");
                });
        assertThatThrownBy(() -> vendorContactService.create(leader, vendor.id(), request, "leader", REQUEST_ID))
                .isInstanceOf(AdminCrmProfileAccessDeniedException.class);

        VendorContact changed = vendorContactService.update(admin, vendor.id(), contact.id(), new VendorContactRequest(null,
                "+7 (900) 100-00-11", null, null, true, null, null, 0), "phone", REQUEST_ID);
        assertThatThrownBy(() -> vendorContactService.update(admin, vendor.id(), contact.id(), new VendorContactRequest(null,
                "+7 900 100-00-12", null, null, null, null, null, 0), "stale", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionConflictException.class, exception ->
                        assertThat(exception.currentVersion()).isEqualTo(1));
        VendorContact archived = vendorContactService.update(admin, vendor.id(), contact.id(), new VendorContactRequest(null, null,
                null, null, null, true, null, changed.version()), "archive", REQUEST_ID);

        assertThat(changed.phone()).isEqualTo("+79001000011");
        assertThat(changed.prefersTelegram()).isTrue();
        assertThat(archived.archived()).isTrue();
        assertThat(archived.products()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT vendor_contact_id FROM products WHERE id = ?", UUID.class, product.id())).isNull();
        assertThat(catalogChangeEventRepository.findPage(CatalogEntityType.VENDOR_CONTACT, 0, 10).items())
                .extracting(CatalogChangeEvent::action)
                .containsExactlyInAnyOrder(CatalogChangeAction.CREATE, CatalogChangeAction.UPDATE, CatalogChangeAction.UPDATE,
                        CatalogChangeAction.ARCHIVE);
        assertThat(catalogChangeEventRepository.findPage(CatalogEntityType.VENDOR_CONTACT, 0, 10).items())
                .extracting(CatalogChangeEvent::changes)
                .noneMatch(changes -> changes != null && (changes.contains("example.test") || changes.contains("900")));
    }

    @Test
    void archivedProgramDisappearsFromChoiceAndRenameIsJournaledWithVersionCheck() {
        AdminCatalogEntry renamed = service.update(admin, CatalogKind.PROGRAMS, PROGRAM,
                new CatalogEntryRequest("UAT-программа (испр.)", null, null, 0), "program-rename", REQUEST_ID);
        assertThatThrownBy(() -> service.update(admin, CatalogKind.PROGRAMS, PROGRAM,
                new CatalogEntryRequest("Другое имя", null, null, 0), "program-stale", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionConflictException.class, exception ->
                        assertThat(exception.currentVersion()).isEqualTo(1));
        assertThatThrownBy(() -> service.update(admin, CatalogKind.DIRECTIONS, DIRECTION,
                new CatalogEntryRequest(null, null, true, 0), "direction-archive", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.getMessage()).contains("действующие программы (1)"));

        AdminCatalogEntry archived = service.update(admin, CatalogKind.PROGRAMS, PROGRAM,
                new CatalogEntryRequest(null, null, true, renamed.version()), "program-archive", REQUEST_ID);

        assertThat(archived.archived()).isTrue();
        assertThat(catalogRepository.findPrograms(CatalogQuery.from(0, 25), CatalogEntryState.ACTIVE).items()).isEmpty();
        assertThat(catalogRepository.findPrograms(CatalogQuery.from(0, 25), CatalogEntryState.ALL).items())
                .extracting(CatalogLookup::name, CatalogLookup::archived)
                .containsExactly(tuple("UAT-программа (испр.)", true));
        assertThat(catalogRepository.findProgramById(PROGRAM)).isPresent();
        assertThat(service.list(admin, CatalogKind.PROGRAMS, "испр", "ARCHIVED", 0, 25).items())
                .extracting(AdminCatalogEntry::name).containsExactly("UAT-программа (испр.)");
        assertThat(catalogChangeEventRepository.findPage(CatalogEntityType.PROGRAM, 0, 10).items())
                .extracting(CatalogChangeEvent::action, CatalogChangeEvent::changes)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(CatalogChangeAction.ARCHIVE, null),
                        org.assertj.core.groups.Tuple.tuple(
                                CatalogChangeAction.UPDATE, "Название: «UAT-программа (архив)» → «UAT-программа (испр.)»"
                        )
                );
        assertThatThrownBy(() -> service.update(admin, CatalogKind.PROGRAMS, UUID.randomUUID(),
                new CatalogEntryRequest("Нет", null, null, 0), "program-missing", REQUEST_ID))
                .isInstanceOf(CatalogEntryNotFoundException.class);
    }

    private void createSchema() {
        List.of(
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, id UUID PRIMARY KEY, display_name VARCHAR(200) NOT NULL)
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
                CREATE TABLE IF NOT EXISTS command_idempotency_records (
                    id UUID PRIMARY KEY, actor_profile_id UUID NOT NULL, operation VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL, request_fingerprint CHAR(64) NOT NULL,
                    result_json VARCHAR(10000), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (actor_profile_id, operation, idempotency_key)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS catalog_change_events (
                    id UUID PRIMARY KEY, entity_type VARCHAR(16) NOT NULL, entity_id UUID NOT NULL,
                    action VARCHAR(16) NOT NULL, entity_name VARCHAR(300) NOT NULL, changes VARCHAR(2000),
                    actor_profile_id UUID NOT NULL, actor_display_name VARCHAR(200) NOT NULL,
                    request_id VARCHAR(64) NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                "CREATE TABLE IF NOT EXISTS catalog_name_locks (entity_type VARCHAR(16) PRIMARY KEY)",
                "MERGE INTO catalog_name_locks KEY (entity_type) VALUES ('ORGANIZATION'), ('DIRECTION'), ('PROGRAM'), ('VENDOR'), ('PRODUCT')"
        ).forEach(jdbcTemplate::execute);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfiguration {
        @Bean
        @ConditionalOnMissingBean(ObjectMapper.class)
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
