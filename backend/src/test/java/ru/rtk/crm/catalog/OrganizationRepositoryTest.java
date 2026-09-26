package ru.rtk.crm.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;

@JdbcTest(properties = "spring.flyway.enabled=false")
@Import(OrganizationRepository.class)
class OrganizationRepositoryTest {
    private final UUID teamA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID teamB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID managerA = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private final UUID managerB = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private final UUID organizationA = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private final UUID organizationUnassigned = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private final UUID organizationB = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private final UUID organizationWithCrossTeamOwner = UUID.fromString("00000000-0000-0000-0000-000000000104");

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY,
                    name VARCHAR(160) NOT NULL, archived BOOLEAN DEFAULT FALSE NOT NULL, default_workflow_template_id UUID
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_user_profiles (
                    id UUID PRIMARY KEY,
                    login VARCHAR(200),
                    idp_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    activation_requested_at TIMESTAMP WITH TIME ZONE,
                    anonymized_at TIMESTAMP WITH TIME ZONE,
                    display_name VARCHAR(200) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    team_id UUID,
                    active BOOLEAN NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY,
                    name VARCHAR(300) NOT NULL,
                    type VARCHAR(16) NOT NULL,
                    team_id UUID NOT NULL,
                    owner_manager_id UUID,
                    version INTEGER NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, city VARCHAR(200), website VARCHAR(300), inn VARCHAR(12)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS contacts (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    confirmed_at TIMESTAMP WITH TIME ZONE,
                    confirmed_by UUID
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organization_assignment_events (
                    reason VARCHAR(32), handover_note VARCHAR(2000),
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, command_id UUID NOT NULL,
                    previous_owner_manager_id UUID, previous_owner_manager_display_name VARCHAR(200),
                    owner_manager_id UUID, new_owner_manager_display_name VARCHAR(200), actor_profile_id UUID NOT NULL,
                    actor_display_name VARCHAR(200) NOT NULL, request_id VARCHAR(64) NOT NULL, version INTEGER NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organization_deputies (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, deputy_profile_id UUID NOT NULL,
                    deputy_display_name VARCHAR(200) NOT NULL, starts_on DATE NOT NULL, ends_on DATE NOT NULL,
                    starts_at TIMESTAMP WITH TIME ZONE NOT NULL, ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    command_id UUID NOT NULL, actor_profile_id UUID NOT NULL, actor_display_name VARCHAR(200) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, ended_at TIMESTAMP WITH TIME ZONE,
                    ended_by_profile_id UUID, ended_by_display_name VARCHAR(200)
                )
                """);
        jdbcTemplate.update("DELETE FROM organizations");
        jdbcTemplate.update("DELETE FROM crm_user_profiles");
        jdbcTemplate.update("DELETE FROM teams");
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, ?), (?, ?)", teamA, "Команда А", teamB, "Команда Б");
        jdbcTemplate.update(
                "INSERT INTO crm_user_profiles (id, display_name, role, team_id, active) VALUES (?, ?, 'USER', ?, TRUE), (?, ?, 'USER', ?, TRUE)",
                managerA,
                "Анна Менеджер",
                teamA,
                managerB,
                "Борис Менеджер",
                teamB
        );
        insert(organizationA, "Университет А", teamA, managerA, 3);
        insert(organizationUnassigned, "Университет без КАМ", teamA, null, 2);
        insert(organizationB, "Университет Б", teamB, managerB, 1);
        insert(organizationWithCrossTeamOwner, "Университет с неверным КАМ", teamA, managerB, 4);
    }

    @Test
    void userCanReadOnlyOwnOrganizationsAndCannotResolveForeignId() {
        CrmProfile profile = new CrmProfile(managerA, UserRole.USER, teamA, 0);

        OrganizationPage page = organizationRepository.findVisible(profile, query());

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(Organization::id).containsExactly(organizationA);
        assertThat(organizationRepository.findVisibleById(profile, organizationB)).isEmpty();
        assertThat(organizationRepository.findVisibleById(profile, organizationWithCrossTeamOwner)).isEmpty();
    }

    @Test
    void leaderCanReadItsTeamIncludingUnassignedOrganizationsOnly() {
        CrmProfile profile = new CrmProfile(UUID.randomUUID(), UserRole.LEADER, teamA, 0);

        OrganizationPage page = organizationRepository.findVisible(profile, query());

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(Organization::id)
                .containsExactlyInAnyOrder(organizationA, organizationUnassigned, organizationWithCrossTeamOwner);
        assertThat(organizationRepository.findVisibleById(profile, organizationB)).isEmpty();
    }

    @Test
    void userCannotReadAnOrganizationWithAStaleCrossTeamOwnership() {
        CrmProfile profile = new CrmProfile(managerB, UserRole.USER, teamB, 0);

        OrganizationPage page = organizationRepository.findVisible(profile, query());

        assertThat(page.items()).extracting(Organization::id).containsExactly(organizationB);
        assertThat(organizationRepository.findVisibleById(profile, organizationWithCrossTeamOwner)).isEmpty();
    }

    @Test
    void administratorHasNoImplicitBusinessReadScope() {
        CrmProfile profile = new CrmProfile(UUID.randomUUID(), UserRole.ADMIN, teamA, 0);

        OrganizationPage page = organizationRepository.findVisible(profile, query());

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(organizationRepository.findVisibleById(profile, organizationA)).isEmpty();
    }

    @Test
    void leaderSearchesByNameFiltersRequiringAssignmentAndSeesNames() {
        CrmProfile leader = new CrmProfile(UUID.randomUUID(), UserRole.LEADER, teamA, 0);

        OrganizationPage requiringAssignment = organizationRepository.findVisible(
                leader,
                OrganizationQuery.from(0, 25, "name,asc", null, true)
        );
        assertThat(requiringAssignment.total()).isEqualTo(2);
        assertThat(requiringAssignment.items()).extracting(Organization::id)
                .containsExactly(organizationUnassigned, organizationWithCrossTeamOwner);
        assertThat(organizationRepository.findVisible(leader, OrganizationQuery.from(0, 25, "name,asc", "  БЕЗ кам ", false))
                .items()).extracting(Organization::id).containsExactly(organizationUnassigned);
        assertThat(organizationRepository.findVisible(leader, OrganizationQuery.from(0, 25, "name,asc", "%", false))
                .total()).isZero();

        Organization assigned = organizationRepository.findVisibleById(leader, organizationA).orElseThrow();
        assertThat(assigned.ownerManagerName()).isEqualTo("Анна Менеджер");
        assertThat(assigned.teamName()).isEqualTo("Команда А");
        assertThat(assigned.requiresAssignment()).isFalse();

        jdbcTemplate.update("UPDATE crm_user_profiles SET active = FALSE WHERE id = ?", managerA);
        Organization withInactiveOwner = organizationRepository.findVisibleById(leader, organizationA).orElseThrow();
        assertThat(withInactiveOwner.ownerManagerName()).isEqualTo("Анна Менеджер");
        assertThat(withInactiveOwner.requiresAssignment()).isTrue();
        assertThat(organizationRepository.findVisible(leader, OrganizationQuery.from(0, 25, "name,asc", null, true)).total())
                .isEqualTo(3);
    }

    @Test
    void rejectsUnsupportedPaginationSortAndSearch() {
        assertThatThrownBy(() -> OrganizationQuery.from(-1, 25, "name,asc", null, false))
                .isInstanceOf(InvalidOrganizationQueryException.class);
        assertThatThrownBy(() -> OrganizationQuery.from(0, 101, "name,asc", null, false))
                .isInstanceOf(InvalidOrganizationQueryException.class);
        assertThatThrownBy(() -> OrganizationQuery.from(0, 25, "id,asc", null, false))
                .isInstanceOf(InvalidOrganizationQueryException.class);
        assertThatThrownBy(() -> OrganizationQuery.from(0, 25, "name,asc", "я".repeat(201), false))
                .isInstanceOf(InvalidOrganizationQueryException.class);
    }

    private OrganizationQuery query() {
        return OrganizationQuery.from(0, 25, "name,asc", null, false);
    }

    private void insert(UUID id, String name, UUID teamId, UUID ownerManagerId, int version) {
        jdbcTemplate.update(
                """
                        INSERT INTO organizations (id, name, type, team_id, owner_manager_id, version, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                name,
                OrganizationType.UNIVERSITY.name(),
                teamId,
                ownerManagerId,
                version,
                OffsetDateTime.parse("2026-09-22T12:00:00+00:00").plusMinutes(version)
        );
    }
}
