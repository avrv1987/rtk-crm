package ru.rtk.crm.partner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.OrganizationStatus;
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.partner.PartnerModels.PartnerAccess;

@Repository
public class PartnerAccessRepository {
    private static final String ACCESS_COLUMNS = """
            SELECT p.id AS profile_id, c.id AS contact_id, c.name AS contact_name, p.login, p.active, p.idp_enabled, p.updated_at
            FROM crm_user_profiles p
            JOIN contacts c ON c.id = p.partner_contact_id
            """;

    private final JdbcClient jdbcClient;

    public PartnerAccessRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<ManagedOrganization> findOrganization(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT id, name, type, status, team_id, owner_manager_id
                FROM organizations
                WHERE id = :organizationId
                """)
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> new ManagedOrganization(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        OrganizationType.valueOf(resultSet.getString("type")),
                        OrganizationStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("team_id", UUID.class),
                        resultSet.getObject("owner_manager_id", UUID.class)
                ))
                .optional();
    }

    public Optional<PartnerContact> lockContact(UUID organizationId, UUID contactId) {
        return jdbcClient.sql("""
                SELECT id, name, email, inactive, personal_data_status
                FROM contacts
                WHERE id = :contactId AND organization_id = :organizationId
                FOR UPDATE
                """)
                .param("organizationId", organizationId)
                .param("contactId", contactId)
                .query((resultSet, rowNumber) -> new PartnerContact(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getString("email"),
                        resultSet.getBoolean("inactive"),
                        PersonalDataStatus.valueOf(resultSet.getString("personal_data_status"))
                ))
                .optional();
    }

    public Optional<PartnerProfile> lockProfileByContact(UUID contactId) {
        return jdbcClient.sql("""
                SELECT id, subject, display_name, active
                FROM crm_user_profiles
                WHERE partner_contact_id = :contactId AND role = 'PARTNER'
                FOR UPDATE
                """)
                .param("contactId", contactId)
                .query(this::mapProfile)
                .optional();
    }

    public Optional<PartnerProfile> lockProfile(UUID profileId) {
        return jdbcClient.sql("""
                SELECT id, subject, display_name, active
                FROM crm_user_profiles
                WHERE id = :profileId AND role = 'PARTNER'
                FOR UPDATE
                """)
                .param("profileId", profileId)
                .query(this::mapProfile)
                .optional();
    }

    public List<UUID> findActiveProfileIds(UUID organizationId, UUID contactId) {
        JdbcClient.StatementSpec query = jdbcClient.sql("""
                SELECT id
                FROM crm_user_profiles
                WHERE role = 'PARTNER' AND active = TRUE AND partner_organization_id = :organizationId
                """ + (contactId == null ? "" : " AND partner_contact_id = :contactId") + " ORDER BY id")
                .param("organizationId", organizationId);
        if (contactId != null) {
            query.param("contactId", contactId);
        }
        return query.query(UUID.class).list();
    }

    public List<PartnerAccess> findByOrganization(UUID organizationId) {
        return jdbcClient.sql(ACCESS_COLUMNS + """
                WHERE p.role = 'PARTNER' AND p.partner_organization_id = :organizationId
                ORDER BY c.name, p.id
                """)
                .param("organizationId", organizationId)
                .query(this::mapAccess)
                .list();
    }

    public PartnerAccess findAccess(UUID profileId) {
        return jdbcClient.sql(ACCESS_COLUMNS + "WHERE p.id = :profileId")
                .param("profileId", profileId)
                .query(this::mapAccess)
                .single();
    }

    public Optional<String> findIssuer(UUID profileId) {
        return jdbcClient.sql("SELECT issuer FROM crm_user_profiles WHERE id = :profileId")
                .param("profileId", profileId)
                .query(String.class)
                .optional();
    }

    public void insert(
            UUID profileId,
            String issuer,
            String subject,
            String displayName,
            String login,
            UUID organizationId,
            UUID contactId,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO crm_user_profiles (
                    id, issuer, subject, display_name, login, role, team_id, active, pending_activation, idp_enabled,
                    partner_organization_id, partner_contact_id, updated_at
                ) VALUES (
                    :id, :issuer, :subject, :displayName, :login, 'PARTNER', NULL, TRUE, FALSE, TRUE,
                    :organizationId, :contactId, :now
                )
                """)
                .param("id", profileId)
                .param("issuer", issuer)
                .param("subject", subject)
                .param("displayName", displayName)
                .param("login", login)
                .param("organizationId", organizationId)
                .param("contactId", contactId)
                .param("now", now)
                .update();
    }

    public void changeActive(UUID profileId, boolean active, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE crm_user_profiles
                SET active = :active,
                    access_revision = access_revision + 1,
                    version = version + 1,
                    updated_at = :now
                WHERE id = :profileId AND role = 'PARTNER'
                """)
                .param("profileId", profileId)
                .param("active", active)
                .param("now", now)
                .update();
    }

    public void markIdpEnabled(UUID profileId, boolean enabled) {
        jdbcClient.sql("UPDATE crm_user_profiles SET idp_enabled = :enabled WHERE id = :profileId")
                .param("profileId", profileId)
                .param("enabled", enabled)
                .update();
    }

    private PartnerProfile mapProfile(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PartnerProfile(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("subject"),
                resultSet.getString("display_name"),
                resultSet.getBoolean("active")
        );
    }

    private PartnerAccess mapAccess(ResultSet resultSet, int rowNumber) throws SQLException {
        boolean active = resultSet.getBoolean("active");
        return new PartnerAccess(
                resultSet.getObject("profile_id", UUID.class),
                resultSet.getObject("contact_id", UUID.class),
                resultSet.getString("contact_name"),
                resultSet.getString("login"),
                active,
                active != resultSet.getBoolean("idp_enabled"),
                resultSet.getObject("updated_at", OffsetDateTime.class),
                null
        );
    }

    public record ManagedOrganization(
            UUID id,
            String name,
            OrganizationType type,
            OrganizationStatus status,
            UUID teamId,
            UUID ownerManagerId
    ) {
    }

    public record PartnerContact(UUID id, String name, String email, boolean inactive, PersonalDataStatus personalDataStatus) {
    }

    public record PartnerProfile(UUID id, String subject, String displayName, boolean active) {
    }
}
