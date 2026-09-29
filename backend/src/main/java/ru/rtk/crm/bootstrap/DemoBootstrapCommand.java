package ru.rtk.crm.bootstrap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserProfileRepository;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.attachment.AttachmentKind;
import ru.rtk.crm.attachment.AttachmentService;
import ru.rtk.crm.attachment.AttachmentUploadRequest;
import ru.rtk.crm.catalog.Contact;
import ru.rtk.crm.catalog.ContactCreateRequest;
import ru.rtk.crm.catalog.ContactService;
import ru.rtk.crm.interaction.InteractionCreateRequest;
import ru.rtk.crm.interaction.InteractionFlagsRequest;
import ru.rtk.crm.interaction.InteractionRiskLevel;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.ProductAgreementContract;
import ru.rtk.crm.interaction.ProductAgreementService;
import ru.rtk.crm.interaction.ProductAgreementUpdateRequest;
import ru.rtk.crm.interaction.ProductTransfer;
import ru.rtk.crm.interaction.ProductTransferKind;
import ru.rtk.crm.interaction.ProductTransferStatus;
import ru.rtk.crm.report.PdfLayout;

@Component
@Profile("demo-bootstrap")
@Order(1)
public class DemoBootstrapCommand implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DemoBootstrapCommand.class);
    private static final String DEMO_ORGANIZATION_NAME = "Университет А";
    private static final String DEMO_INTERACTION_TITLE = "Демо: внедрение цифрового университета";
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final List<DemoAgreement> DEMO_AGREEMENTS = List.of(
            new DemoAgreement("Университет А", "kam-a", "ДЕМО-А/1", "DRAFT", null, null, "SIGNING", 20),
            new DemoAgreement("Университет А", "kam-a", "ДЕМО-А/2", "ACTIVE", -340, 15, "RENEWAL", 10),
            new DemoAgreement("Университет А", "kam-a", "ДЕМО-А/3", "ACTIVE", -700, -3, null, null),
            new DemoAgreement("Университет Б", "kam-b", "ДЕМО-Б/1", "DRAFT", null, null, "SIGNING", -6),
            new DemoAgreement("Университет Б", "kam-b", "ДЕМО-Б/2", "ACTIVE", -30, 700, "SIGNING", -12),
            new DemoAgreement("Колледж связи (демо)", "kam-b", "ДЕМО-К/1", "ACTIVE", -200, 60, "RENEWAL", 45),
            new DemoAgreement("Школа № 1 (демо)", "kam-d", "ДЕМО-Ш/1", "DRAFT", null, null, "SIGNING", 5)
    );
    private static final List<DemoContract> DEMO_CONTRACTS = List.of(
            new DemoContract("ДЕМО-А/ЛС-1", 2026, 30, 14),
            new DemoContract("ДЕМО-А/ЛС-2", 2028, 7, null)
    );
    private static final List<DemoDocument> PARTNER_DOCUMENTS = List.of(
            new DemoDocument("plan", "Демо: план сотрудничества.pdf", AttachmentKind.OTHER, "План сотрудничества вуза и ИТ Школы РТК"),
            new DemoDocument("program", "Демо: рабочая программа.pdf", AttachmentKind.CURRICULUM, "Рабочая программа дисциплины")
    );

    private final DemoBootstrapProperties properties;
    private final JdbcClient jdbcClient;
    private final UserProfileRepository userProfileRepository;
    private final ContactService contactService;
    private final InteractionService interactionService;
    private final AttachmentService attachmentService;
    private final ProductAgreementService productAgreementService;

    public DemoBootstrapCommand(
            DemoBootstrapProperties properties,
            JdbcClient jdbcClient,
            UserProfileRepository userProfileRepository,
            ContactService contactService,
            InteractionService interactionService,
            AttachmentService attachmentService,
            ProductAgreementService productAgreementService
    ) {
        this.properties = properties;
        this.jdbcClient = jdbcClient;
        this.userProfileRepository = userProfileRepository;
        this.contactService = contactService;
        this.interactionService = interactionService;
        this.attachmentService = attachmentService;
        this.productAgreementService = productAgreementService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<DemoBootstrapProperties.Identity> identities = required(properties.identities(), "identities");
        Map<String, DemoBootstrapProperties.Identity> identitiesByKey = uniqueIdentities(identities);
        if (!properties.demoData()) {
            requireSingleAdministrator(identities);
            createProfiles(identities, Map.of());
            LOGGER.info("CRM administrator bootstrap completed without demo data");
            return;
        }
        List<DemoBootstrapProperties.Organization> organizations = required(properties.organizations(), "organizations");
        Map<String, UUID> teamIds = createTeams(identities);
        Map<String, UUID> profileIds = createProfiles(identities, teamIds);
        Map<String, UUID> organizationIds = createOrganizations(organizations, identitiesByKey, teamIds, profileIds);
        DemoCatalog catalog = createCatalogs();
        createDemoScenario(identitiesByKey, organizationIds, catalog);
        createDemoMarks(identitiesByKey, organizationIds.get(DEMO_ORGANIZATION_NAME));
        createDemoAgreements(organizationIds, profileIds);
        createPartners(identities, identitiesByKey, organizationIds);
        LOGGER.info("Demo CRM bootstrap completed");
    }

    private <T> List<T> required(List<T> values, String name) {
        if (values == null || values.isEmpty()) {
            throw new IllegalStateException("Demo bootstrap requires " + name);
        }
        return values;
    }

    private void requireSingleAdministrator(List<DemoBootstrapProperties.Identity> identities) {
        if (identities.size() != 1 || identities.getFirst().role() != UserRole.ADMIN || !blank(identities.getFirst().teamKey())
                || !properties.organizations().isEmpty() || !properties.learningMappings().isEmpty()) {
            throw new IllegalStateException("Bootstrap without demo data expects exactly one administrator without team and no demo records");
        }
    }

    private Map<String, DemoBootstrapProperties.Identity> uniqueIdentities(List<DemoBootstrapProperties.Identity> identities) {
        Map<String, DemoBootstrapProperties.Identity> values = new HashMap<>();
        for (DemoBootstrapProperties.Identity identity : identities) {
            if (blank(identity.key()) || blank(identity.issuer()) || blank(identity.subject()) || blank(identity.displayName())
                    || identity.role() == null
                    || identity.role() == UserRole.PARTNER
                            && (!blank(identity.teamKey()) || blank(identity.organization()) || blank(identity.contact()))
                    || identity.role() != UserRole.ADMIN && identity.role() != UserRole.MANAGEMENT
                            && identity.role() != UserRole.PARTNER && blank(identity.teamKey())
                    || values.put(identity.key(), identity) != null) {
                throw new IllegalStateException("Demo bootstrap identities must be complete and unique");
            }
        }
        return values;
    }

    private Map<String, UUID> createTeams(List<DemoBootstrapProperties.Identity> identities) {
        Map<String, UUID> teamIds = new HashMap<>();
        for (DemoBootstrapProperties.Identity identity : identities) {
            if (blank(identity.teamKey()) || teamIds.containsKey(identity.teamKey())) {
                continue;
            }
            UUID proposedId = stableId("team:" + identity.teamKey());
            String name = teamName(identity.teamKey());
            jdbcClient.sql("""
                    INSERT INTO teams (id, name)
                    VALUES (:id, :name)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("id", proposedId)
                    .param("name", name)
                    .update();
            if (!name.equals(identity.teamKey())) {
                jdbcClient.sql("""
                        UPDATE teams
                        SET name = :name, version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND name = :key
                          AND NOT EXISTS (SELECT 1 FROM teams other WHERE LOWER(other.name) = LOWER(:name))
                        """)
                        .param("id", proposedId)
                        .param("key", identity.teamKey())
                        .param("name", name)
                        .update();
            }
            UUID teamId = jdbcClient.sql("SELECT id FROM teams WHERE id = :id")
                    .param("id", proposedId)
                    .query(UUID.class)
                    .optional()
                    .or(() -> jdbcClient.sql("SELECT id FROM teams WHERE LOWER(name) = LOWER(:name)")
                            .param("name", name)
                            .query(UUID.class)
                            .optional())
                    .orElseThrow(() -> new IllegalStateException("Demo bootstrap team key conflicts with existing data"));
            teamIds.put(identity.teamKey(), teamId);
        }
        return teamIds;
    }

    private String teamName(String key) {
        return properties.teams().stream()
                .filter(team -> key.equals(team.key()) && !blank(team.name()))
                .map(team -> team.name().strip())
                .findFirst()
                .orElse(key);
    }

    private Map<String, UUID> createProfiles(
            List<DemoBootstrapProperties.Identity> identities,
            Map<String, UUID> teamIds
    ) {
        Map<String, UUID> profileIds = new HashMap<>();
        for (DemoBootstrapProperties.Identity identity : identities) {
            if (identity.role() == UserRole.PARTNER) {
                continue;
            }
            UUID proposedId = stableId("profile:" + identity.issuer() + "\u0000" + identity.subject());
            jdbcClient.sql("""
                    INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id)
                    VALUES (:id, :issuer, :subject, :displayName, :role, :teamId)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("id", proposedId)
                    .param("issuer", identity.issuer())
                    .param("subject", identity.subject())
                    .param("displayName", identity.displayName())
                    .param("role", identity.role().name())
                    .param("teamId", teamId(identity, teamIds))
                    .update();
            jdbcClient.sql("""
                    UPDATE crm_user_profiles
                    SET display_name = :displayName,
                        role = :role,
                        team_id = :teamId,
                        active = TRUE,
                        pending_activation = FALSE,
                        access_revision = access_revision + 1,
                        version = version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE issuer = :issuer AND subject = :subject
                      AND (role <> :role OR team_id IS DISTINCT FROM :teamId OR NOT active)
                    """)
                    .param("issuer", identity.issuer())
                    .param("subject", identity.subject())
                    .param("displayName", identity.displayName())
                    .param("role", identity.role().name())
                    .param("teamId", teamId(identity, teamIds))
                    .update();
            UUID profileId = jdbcClient.sql("""
                    SELECT id FROM crm_user_profiles
                    WHERE issuer = :issuer AND subject = :subject
                    """)
                    .param("issuer", identity.issuer())
                    .param("subject", identity.subject())
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(() -> new IllegalStateException("Demo bootstrap profile is unavailable after insert"));
            if (Boolean.TRUE.equals(identity.enrolmentOperator())) {
                jdbcClient.sql("""
                        UPDATE crm_user_profiles
                        SET enrolment_operator = TRUE
                        WHERE id = :id AND role IN ('USER', 'LEADER') AND enrolment_operator = FALSE
                        """)
                        .param("id", profileId)
                        .update();
            }
            profileIds.put(identity.key(), profileId);
        }
        return profileIds;
    }

    private UUID teamId(DemoBootstrapProperties.Identity identity, Map<String, UUID> teamIds) {
        return blank(identity.teamKey()) ? null : teamIds.get(identity.teamKey());
    }

    private Map<String, UUID> createOrganizations(
            List<DemoBootstrapProperties.Organization> organizations,
            Map<String, DemoBootstrapProperties.Identity> identitiesByKey,
            Map<String, UUID> teamIds,
            Map<String, UUID> profileIds
    ) {
        Map<String, UUID> organizationIds = new HashMap<>();
        for (DemoBootstrapProperties.Organization organization : organizations) {
            boolean hasOwner = !blank(organization.ownerKey());
            if (blank(organization.name()) || blank(organization.type()) || blank(organization.teamKey())
                    || !teamIds.containsKey(organization.teamKey())
                    || hasOwner && (!identitiesByKey.containsKey(organization.ownerKey()) || !profileIds.containsKey(organization.ownerKey()))) {
                throw new IllegalStateException("Demo bootstrap organizations must reference configured identities and teams");
            }
            UUID proposedId = stableId("organization:" + organization.name());
            jdbcClient.sql("""
                    INSERT INTO organizations (id, name, type, team_id, owner_manager_id)
                    VALUES (:id, :name, :type, :teamId, :ownerManagerId)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("id", proposedId)
                    .param("name", organization.name())
                    .param("type", organization.type())
                    .param("teamId", teamIds.get(organization.teamKey()))
                    .param("ownerManagerId", hasOwner ? profileIds.get(organization.ownerKey()) : null)
                    .update();
            UUID organizationId = jdbcClient.sql("SELECT id FROM organizations WHERE id = :id AND name = :name")
                    .param("id", proposedId)
                    .param("name", organization.name())
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(() -> new IllegalStateException("Demo bootstrap organization key conflicts with existing data"));
            organizationIds.put(organization.name(), organizationId);
        }
        return organizationIds;
    }

    private DemoCatalog createCatalogs() {
        UUID directionId = createDirection("demo-direction:digital-transformation", "Демо-направление: цифровая трансформация");
        UUID programId = createProgram(directionId, "demo-program:digital-transformation", "Демо-программа: цифровой университет");
        createProgram(directionId, "demo-program:data-analysis", "Демо-программа: анализ данных");
        UUID openEnrolmentId = createDirection("demo-direction:open-enrolment", "Демо-направление: курсы для физлиц");
        createProgram(openEnrolmentId, "demo-program:prompt-engineering", "Промпт-инжиниринг");
        createProgram(openEnrolmentId, "demo-program:software-tester", "Инженер-тестировщик");
        UUID vendorId = createVendor("Демо-вендор: РТК");
        UUID secureCommunicationsId = createProduct(
                vendorId,
                "demo-product:secure-communications",
                "Демо-продукт: защищённая связь"
        );
        UUID cloudPlatformId = createProduct(vendorId, "demo-product:cloud-platform", "Демо-продукт: облачная платформа");
        return new DemoCatalog(programId, List.of(secureCommunicationsId, cloudPlatformId));
    }

    private UUID createDirection(String key, String name) {
        jdbcClient.sql("""
                INSERT INTO directions (id, name)
                VALUES (:id, :name)
                ON CONFLICT DO NOTHING
                """)
                .param("id", stableId(key))
                .param("name", name)
                .update();
        return jdbcClient.sql("SELECT id FROM directions WHERE name = :name")
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    private UUID createProgram(UUID directionId, String key, String name) {
        jdbcClient.sql("""
                INSERT INTO programs (id, direction_id, name)
                VALUES (:id, :directionId, :name)
                ON CONFLICT DO NOTHING
                """)
                .param("id", stableId(key))
                .param("directionId", directionId)
                .param("name", name)
                .update();
        return jdbcClient.sql("SELECT id FROM programs WHERE direction_id = :directionId AND name = :name")
                .param("directionId", directionId)
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    private UUID createVendor(String name) {
        jdbcClient.sql("""
                INSERT INTO vendors (id, name)
                VALUES (:id, :name)
                ON CONFLICT DO NOTHING
                """)
                .param("id", stableId("demo-vendor:rtk"))
                .param("name", name)
                .update();
        return jdbcClient.sql("SELECT id FROM vendors WHERE name = :name")
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    private UUID createProduct(UUID vendorId, String key, String name) {
        jdbcClient.sql("""
                INSERT INTO products (id, vendor_id, name)
                VALUES (:id, :vendorId, :name)
                ON CONFLICT DO NOTHING
                """)
                .param("id", stableId(key))
                .param("vendorId", vendorId)
                .param("name", name)
                .update();
        return jdbcClient.sql("SELECT id FROM products WHERE vendor_id = :vendorId AND name = :name")
                .param("vendorId", vendorId)
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    private void createDemoScenario(
            Map<String, DemoBootstrapProperties.Identity> identitiesByKey,
            Map<String, UUID> organizationIds,
            DemoCatalog catalog
    ) {
        UUID organizationId = organizationIds.get(DEMO_ORGANIZATION_NAME);
        if (organizationId == null) {
            throw new IllegalStateException("Demo bootstrap requires " + DEMO_ORGANIZATION_NAME);
        }
        if (demoInteractionExists(organizationId)) {
            return;
        }
        DemoBootstrapProperties.Identity kamIdentity = identitiesByKey.get("kam-a");
        if (kamIdentity == null) {
            throw new IllegalStateException("Demo bootstrap requires kam-a");
        }
        CrmProfile kam = userProfileRepository.findActiveByIdentity(kamIdentity.issuer(), kamIdentity.subject())
                .orElseThrow(() -> new IllegalStateException("Demo bootstrap requires active kam-a profile"));
        Contact digitalLead = contactService.create(
                kam,
                organizationId,
                new ContactCreateRequest(
                        "Александра Демонстрационная",
                        "Руководитель цифровой трансформации",
                        "alexandra.demo@example.test",
                        "+7 000 000-00-01"
                ),
                "demo-bootstrap/v1/contact:digital-lead"
        );
        Contact projectCoordinator = contactService.create(
                kam,
                organizationId,
                new ContactCreateRequest(
                        "Игорь Демонстрационный",
                        "Координатор проекта",
                        "igor.demo@example.test",
                        "+7 000 000-00-02"
                ),
                "demo-bootstrap/v1/contact:project-coordinator"
        );
        interactionService.create(
                kam,
                new InteractionCreateRequest(
                        organizationId,
                        DEMO_INTERACTION_TITLE,
                        "Согласовать демонстрационную встречу",
                        OffsetDateTime.parse("2026-09-30T10:00:00+08:00"),
                        List.of(digitalLead.id(), projectCoordinator.id()),
                        catalog.programId(),
                        catalog.productIds(),
                        OffsetDateTime.parse("2026-09-20T10:00:00+08:00")
                ),
                "demo-bootstrap/v1/interaction:university-a"
        );
    }

    private void createDemoMarks(Map<String, DemoBootstrapProperties.Identity> identitiesByKey, UUID organizationId) {
        UUID interactionId = jdbcClient.sql("""
                SELECT id FROM interactions
                WHERE organization_id = :organizationId AND title = :title
                ORDER BY created_at, id
                LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("title", DEMO_INTERACTION_TITLE)
                .query(UUID.class)
                .optional()
                .orElse(null);
        DemoBootstrapProperties.Identity kamIdentity = identitiesByKey.get("kam-a");
        if (interactionId == null || kamIdentity == null) {
            return;
        }
        CrmProfile kam = userProfileRepository.findActiveByIdentity(kamIdentity.issuer(), kamIdentity.subject())
                .orElseThrow(() -> new IllegalStateException("Demo bootstrap requires active kam-a profile"));
        boolean unmarked = jdbcClient.sql("SELECT COUNT(*) FROM interactions WHERE id = :id AND risk_level IS NULL AND problem IS NULL")
                .param("id", interactionId)
                .query(Long.class)
                .single() > 0;
        if (unmarked) {
            int version = interactionVersion(interactionId);
            interactionService.updateFlags(
                    kam,
                    interactionId,
                    new InteractionFlagsRequest(
                            version,
                            null,
                            null,
                            "Вуз задерживает подписанный акт передачи",
                            InteractionRiskLevel.MEDIUM,
                            "Согласование лицензий идёт дольше плана"
                    ),
                    "demo-bootstrap/v1/flags:university-a:" + version
            );
        }
        List<UUID> agreementIds = jdbcClient.sql("""
                SELECT agreement.id FROM product_agreements agreement
                JOIN products product ON product.id = agreement.product_id
                WHERE agreement.interaction_id = :interactionId AND agreement.contract_number IS NULL
                ORDER BY product.name, agreement.id
                """)
                .param("interactionId", interactionId)
                .query(UUID.class)
                .list();
        LocalDate today = LocalDate.now(ZONE);
        for (int index = 0; index < agreementIds.size() && index < DEMO_CONTRACTS.size(); index++) {
            DemoContract contract = DEMO_CONTRACTS.get(index);
            int version = interactionVersion(interactionId);
            productAgreementService.update(
                    kam,
                    interactionId,
                    agreementIds.get(index),
                    new ProductAgreementUpdateRequest(
                            version,
                            new ProductAgreementContract(contract.number(), true, contract.licenseExpiryYear(), null),
                            contract.transfers(today)
                    ),
                    "demo-bootstrap/v1/agreement:" + agreementIds.get(index) + ":" + version
            );
        }
    }

    private int interactionVersion(UUID interactionId) {
        return jdbcClient.sql("SELECT version FROM interactions WHERE id = :id")
                .param("id", interactionId)
                .query(Integer.class)
                .single();
    }

    private void createDemoAgreements(Map<String, UUID> organizationIds, Map<String, UUID> profileIds) {
        LocalDate today = LocalDate.now(ZONE);
        OffsetDateTime now = OffsetDateTime.now();
        for (DemoAgreement agreement : DEMO_AGREEMENTS) {
            UUID organizationId = organizationIds.get(agreement.organization());
            UUID createdBy = profileIds.get(agreement.ownerKey());
            if (organizationId == null || createdBy == null) {
                continue;
            }
            LocalDate validUntil = agreement.validUntilDays() == null ? null : today.plusDays(agreement.validUntilDays());
            jdbcClient.sql("""
                    INSERT INTO agreements (
                        id, organization_id, number, concluded_on, valid_until, planned_kind, planned_on, planned_base_until,
                        parties, status, version, created_by, created_at, updated_at
                    ) VALUES (
                        :id, :organizationId, :number, :concludedOn, :validUntil, :plannedKind, :plannedOn, :validUntil,
                        :parties, :status, 0, :createdBy, :now, :now
                    )
                    ON CONFLICT DO NOTHING
                    """)
                    .param("id", stableId("agreement:" + agreement.organization() + "\u0000" + agreement.number()))
                    .param("organizationId", organizationId)
                    .param("number", agreement.number())
                    .param("concludedOn", agreement.concludedDays() == null ? null : today.plusDays(agreement.concludedDays()))
                    .param("validUntil", validUntil)
                    .param("plannedKind", agreement.plannedKind())
                    .param("plannedOn", agreement.plannedDays() == null ? null : today.plusDays(agreement.plannedDays()))
                    .param("parties", "Демо: РТК и " + agreement.organization())
                    .param("status", agreement.status())
                    .param("createdBy", createdBy)
                    .param("now", now)
                    .update();
        }
    }

    private void createPartners(
            List<DemoBootstrapProperties.Identity> identities,
            Map<String, DemoBootstrapProperties.Identity> identitiesByKey,
            Map<String, UUID> organizationIds
    ) {
        List<DemoBootstrapProperties.Identity> partners = identities.stream()
                .filter(identity -> identity.role() == UserRole.PARTNER)
                .toList();
        for (DemoBootstrapProperties.Identity partner : partners) {
            UUID organizationId = organizationIds.get(partner.organization());
            if (organizationId == null) {
                throw new IllegalStateException("Demo bootstrap partner must reference a configured organization");
            }
            UUID contactId = jdbcClient.sql("""
                    SELECT id FROM contacts
                    WHERE organization_id = :organizationId AND name = :name
                    ORDER BY created_at, id
                    LIMIT 1
                    """)
                    .param("organizationId", organizationId)
                    .param("name", partner.contact())
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(() -> new IllegalStateException("Demo bootstrap partner contact is unavailable"));
            jdbcClient.sql("""
                    INSERT INTO crm_user_profiles (
                        id, issuer, subject, display_name, login, role, team_id, partner_organization_id, partner_contact_id
                    ) VALUES (
                        :id, :issuer, :subject, :displayName, :login, 'PARTNER', NULL, :organizationId, :contactId
                    )
                    ON CONFLICT DO NOTHING
                    """)
                    .param("id", stableId("profile:" + partner.issuer() + "\u0000" + partner.subject()))
                    .param("issuer", partner.issuer())
                    .param("subject", partner.subject())
                    .param("displayName", partner.displayName())
                    .param("login", partner.key())
                    .param("organizationId", organizationId)
                    .param("contactId", contactId)
                    .update();
            jdbcClient.sql("""
                    UPDATE crm_user_profiles
                    SET display_name = :displayName,
                        role = 'PARTNER',
                        team_id = NULL,
                        enrolment_operator = FALSE,
                        partner_organization_id = :organizationId,
                        partner_contact_id = :contactId,
                        active = TRUE,
                        pending_activation = FALSE,
                        access_revision = access_revision + 1,
                        version = version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE issuer = :issuer AND subject = :subject
                      AND (role <> 'PARTNER' OR partner_contact_id IS DISTINCT FROM :contactId OR NOT active)
                    """)
                    .param("issuer", partner.issuer())
                    .param("subject", partner.subject())
                    .param("displayName", partner.displayName())
                    .param("organizationId", organizationId)
                    .param("contactId", contactId)
                    .update();
            createPartnerDemo(identitiesByKey, organizationId);
        }
    }

    private void createPartnerDemo(Map<String, DemoBootstrapProperties.Identity> identitiesByKey, UUID organizationId) {
        UUID interactionId = jdbcClient.sql("""
                SELECT id FROM interactions
                WHERE organization_id = :organizationId AND title = :title
                ORDER BY created_at, id
                LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("title", DEMO_INTERACTION_TITLE)
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (interactionId == null || jdbcClient.sql("""
                SELECT COUNT(*) FROM attachments WHERE interaction_id = :interactionId AND original_name = :name
                """)
                .param("interactionId", interactionId)
                .param("name", PARTNER_DOCUMENTS.getFirst().name())
                .query(Long.class)
                .single() > 0) {
            return;
        }
        DemoBootstrapProperties.Identity kamIdentity = identitiesByKey.get("kam-a");
        CrmProfile kam = userProfileRepository.findActiveByIdentity(kamIdentity.issuer(), kamIdentity.subject())
                .orElseThrow(() -> new IllegalStateException("Demo bootstrap requires active kam-a profile"));
        UUID stageId = jdbcClient.sql("SELECT current_stage_id FROM interactions WHERE id = :interactionId")
                .param("interactionId", interactionId)
                .query(UUID.class)
                .single();
        for (DemoDocument document : PARTNER_DOCUMENTS) {
            attachmentService.upload(
                    kam,
                    interactionId,
                    new AttachmentUploadRequest(stageId, document.kind(), null),
                    new DemoFile(document.name(), pdf(document.title())),
                    "demo-bootstrap/v1/partner-document:" + document.key()
            );
        }
        jdbcClient.sql("""
                UPDATE attachments SET partner_visible = TRUE
                WHERE interaction_id = :interactionId AND original_name IN (:names)
                """)
                .param("interactionId", interactionId)
                .param("names", PARTNER_DOCUMENTS.stream().map(DemoDocument::name).toList())
                .update();
        jdbcClient.sql("""
                UPDATE interactions SET next_step_partner_visible = TRUE
                WHERE id = :interactionId AND next_action IS NOT NULL
                """)
                .param("interactionId", interactionId)
                .update();
    }

    private static byte[] pdf(String title) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            PdfLayout.write(
                    title,
                    OffsetDateTime.now(),
                    List.of("Демонстрационный документ CRM ИТ Школы РТК. Данные вымышлены."),
                    output,
                    layout -> {
                    }
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return output.toByteArray();
    }

    private boolean demoInteractionExists(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM interactions
                    WHERE organization_id = :organizationId AND title = :title
                )
                """)
                .param("organizationId", organizationId)
                .param("title", DEMO_INTERACTION_TITLE)
                .query(Boolean.class)
                .single();
    }

    private UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record DemoAgreement(
            String organization,
            String ownerKey,
            String number,
            String status,
            Integer concludedDays,
            Integer validUntilDays,
            String plannedKind,
            Integer plannedDays
    ) {
    }

    private record DemoContract(String number, int licenseExpiryYear, Integer materialsDaysAgo, Integer licenseDaysAgo) {
        List<ProductTransfer> transfers(LocalDate today) {
            return List.of(
                    transfer(ProductTransferKind.MATERIALS, today, materialsDaysAgo),
                    transfer(ProductTransferKind.LICENSE, today, licenseDaysAgo),
                    transfer(ProductTransferKind.DOCUMENTATION, today, null)
            );
        }

        private static ProductTransfer transfer(ProductTransferKind kind, LocalDate today, Integer daysAgo) {
            return daysAgo == null
                    ? new ProductTransfer(kind, ProductTransferStatus.NOT_TRANSFERRED, null, null)
                    : new ProductTransfer(kind, ProductTransferStatus.TRANSFERRED, today.minusDays(daysAgo), null);
        }
    }

    private record DemoCatalog(UUID programId, List<UUID> productIds) {
    }

    private record DemoDocument(String key, String name, AttachmentKind kind, String title) {
    }

    private record DemoFile(String originalName, byte[] content) implements MultipartFile {
        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalName;
        }

        @Override
        public String getContentType() {
            return "application/pdf";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File destination) throws IOException {
            Files.write(destination.toPath(), content);
        }
    }
}
