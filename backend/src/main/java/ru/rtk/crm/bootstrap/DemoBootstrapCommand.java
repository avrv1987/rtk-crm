package ru.rtk.crm.bootstrap;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserProfileRepository;
import ru.rtk.crm.catalog.Contact;
import ru.rtk.crm.catalog.ContactCreateRequest;
import ru.rtk.crm.catalog.ContactService;
import ru.rtk.crm.interaction.InteractionCreateRequest;
import ru.rtk.crm.interaction.InteractionService;

@Component
@Profile("demo-bootstrap")
@Order(1)
public class DemoBootstrapCommand implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DemoBootstrapCommand.class);
    private static final String DEMO_ORGANIZATION_NAME = "Университет А";
    private static final String DEMO_INTERACTION_TITLE = "Демо: внедрение цифрового университета";

    private final DemoBootstrapProperties properties;
    private final JdbcClient jdbcClient;
    private final UserProfileRepository userProfileRepository;
    private final ContactService contactService;
    private final InteractionService interactionService;

    public DemoBootstrapCommand(
            DemoBootstrapProperties properties,
            JdbcClient jdbcClient,
            UserProfileRepository userProfileRepository,
            ContactService contactService,
            InteractionService interactionService
    ) {
        this.properties = properties;
        this.jdbcClient = jdbcClient;
        this.userProfileRepository = userProfileRepository;
        this.contactService = contactService;
        this.interactionService = interactionService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<DemoBootstrapProperties.Identity> identities = required(properties.identities(), "identities");
        List<DemoBootstrapProperties.Organization> organizations = required(properties.organizations(), "organizations");
        Map<String, DemoBootstrapProperties.Identity> identitiesByKey = uniqueIdentities(identities);
        Map<String, UUID> teamIds = createTeams(identities);
        Map<String, UUID> profileIds = createProfiles(identities, teamIds);
        Map<String, UUID> organizationIds = createOrganizations(organizations, identitiesByKey, teamIds, profileIds);
        DemoCatalog catalog = createCatalogs();
        createDemoScenario(identitiesByKey, organizationIds, catalog);
        LOGGER.info("Demo CRM bootstrap completed");
    }

    private <T> List<T> required(List<T> values, String name) {
        if (values == null || values.isEmpty()) {
            throw new IllegalStateException("Demo bootstrap requires " + name);
        }
        return values;
    }

    private Map<String, DemoBootstrapProperties.Identity> uniqueIdentities(List<DemoBootstrapProperties.Identity> identities) {
        Map<String, DemoBootstrapProperties.Identity> values = new HashMap<>();
        for (DemoBootstrapProperties.Identity identity : identities) {
            if (blank(identity.key()) || blank(identity.issuer()) || blank(identity.subject()) || blank(identity.displayName())
                    || identity.role() == null || blank(identity.teamKey()) || values.put(identity.key(), identity) != null) {
                throw new IllegalStateException("Demo bootstrap identities must be complete and unique");
            }
        }
        return values;
    }

    private Map<String, UUID> createTeams(List<DemoBootstrapProperties.Identity> identities) {
        Map<String, UUID> teamIds = new HashMap<>();
        for (DemoBootstrapProperties.Identity identity : identities) {
            UUID proposedId = stableId("team:" + identity.teamKey());
            jdbcClient.sql("""
                    INSERT INTO teams (id, name)
                    VALUES (:id, :name)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("id", proposedId)
                    .param("name", identity.teamKey())
                    .update();
            UUID teamId = jdbcClient.sql("SELECT id FROM teams WHERE id = :id")
                    .param("id", proposedId)
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(() -> new IllegalStateException("Demo bootstrap team key conflicts with existing data"));
            teamIds.put(identity.teamKey(), teamId);
        }
        return teamIds;
    }

    private Map<String, UUID> createProfiles(
            List<DemoBootstrapProperties.Identity> identities,
            Map<String, UUID> teamIds
    ) {
        Map<String, UUID> profileIds = new HashMap<>();
        for (DemoBootstrapProperties.Identity identity : identities) {
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
                    .param("teamId", teamIds.get(identity.teamKey()))
                    .update();
            userProfileRepository.activatePending(
                    identity.issuer(),
                    identity.subject(),
                    identity.displayName(),
                    identity.role(),
                    teamIds.get(identity.teamKey())
            );
            UUID profileId = jdbcClient.sql("""
                    SELECT id FROM crm_user_profiles
                    WHERE issuer = :issuer AND subject = :subject
                    """)
                    .param("issuer", identity.issuer())
                    .param("subject", identity.subject())
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(() -> new IllegalStateException("Demo bootstrap profile is unavailable after insert"));
            profileIds.put(identity.key(), profileId);
        }
        return profileIds;
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
        UUID directionId = createDirection("Демо-направление: цифровая трансформация");
        UUID programId = createProgram(directionId, "demo-program:digital-transformation", "Демо-программа: цифровой университет");
        createProgram(directionId, "demo-program:data-analysis", "Демо-программа: анализ данных");
        UUID vendorId = createVendor("Демо-вендор: РТК");
        UUID secureCommunicationsId = createProduct(
                vendorId,
                "demo-product:secure-communications",
                "Демо-продукт: защищённая связь"
        );
        UUID cloudPlatformId = createProduct(vendorId, "demo-product:cloud-platform", "Демо-продукт: облачная платформа");
        return new DemoCatalog(programId, List.of(secureCommunicationsId, cloudPlatformId));
    }

    private UUID createDirection(String name) {
        jdbcClient.sql("""
                INSERT INTO directions (id, name)
                VALUES (:id, :name)
                ON CONFLICT (name) DO NOTHING
                """)
                .param("id", stableId("demo-direction:digital-transformation"))
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
                ON CONFLICT (direction_id, name) DO NOTHING
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
                ON CONFLICT (name) DO NOTHING
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
                ON CONFLICT (vendor_id, name) DO NOTHING
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

    private record DemoCatalog(UUID programId, List<UUID> productIds) {
    }
}
