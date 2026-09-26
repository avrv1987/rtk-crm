package ru.rtk.crm.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ContactRepository {
    private static final String CONTACT_SELECT = """
            SELECT id, organization_id, name, position, email, phone, version, created_by, created_at, updated_at,
                   decision_role, primary_contact, inactive, confirmed_at, confirmed_by, personal_data_status,
                   (SELECT display_name FROM crm_user_profiles confirmer WHERE confirmer.id = contacts.confirmed_by)
                       AS confirmed_by_name
            FROM contacts
            """;

    private final JdbcClient jdbcClient;

    public ContactRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Contact> findByOrganizationId(UUID organizationId) {
        return jdbcClient.sql(CONTACT_SELECT + """
                WHERE organization_id = :organizationId
                ORDER BY inactive ASC, primary_contact DESC, name ASC, id ASC
                """)
                .param("organizationId", organizationId)
                .query(this::mapContact)
                .list();
    }

    public List<Contact> findByIds(UUID organizationId, List<UUID> contactIds) {
        if (contactIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql(CONTACT_SELECT + """
                WHERE organization_id = :organizationId AND id IN (:contactIds)
                ORDER BY name ASC, id ASC
                """)
                .param("organizationId", organizationId)
                .param("contactIds", contactIds)
                .query(this::mapContact)
                .list();
    }

    public Optional<Contact> findById(UUID organizationId, UUID contactId) {
        return jdbcClient.sql(CONTACT_SELECT + "WHERE organization_id = :organizationId AND id = :contactId")
                .param("organizationId", organizationId)
                .param("contactId", contactId)
                .query(this::mapContact)
                .optional();
    }

    public Optional<Contact> findByIdForUpdate(UUID organizationId, UUID contactId) {
        return jdbcClient.sql(CONTACT_SELECT + """
                WHERE organization_id = :organizationId AND id = :contactId
                FOR UPDATE
                """)
                .param("organizationId", organizationId)
                .param("contactId", contactId)
                .query(this::mapContact)
                .optional();
    }

    public List<Contact> findOtherPrimaryForUpdate(UUID organizationId, UUID contactId) {
        return jdbcClient.sql(CONTACT_SELECT + """
                WHERE organization_id = :organizationId AND primary_contact = TRUE AND id <> :contactId
                FOR UPDATE
                """)
                .param("organizationId", organizationId)
                .param("contactId", contactId)
                .query(this::mapContact)
                .list();
    }

    public void lockOrganization(UUID organizationId) {
        jdbcClient.sql("SELECT id FROM organizations WHERE id = :organizationId FOR UPDATE")
                .param("organizationId", organizationId)
                .query(UUID.class)
                .optional();
    }

    public Set<UUID> findIdsByOrganizationId(UUID organizationId, List<UUID> contactIds) {
        if (contactIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql("""
                SELECT id
                FROM contacts
                WHERE organization_id = :organizationId AND id IN (:contactIds) AND personal_data_status = 'ACTIVE'
                """)
                .param("organizationId", organizationId)
                .param("contactIds", contactIds)
                .query(UUID.class)
                .list());
    }

    public Contact insert(
            UUID id,
            UUID organizationId,
            String name,
            String position,
            String email,
            String phone,
            UUID createdBy,
            OffsetDateTime now
    ) {
        return insert(id, organizationId, name, position, email, phone, null, false, createdBy, now);
    }

    public Contact insert(
            UUID id,
            UUID organizationId,
            String name,
            String position,
            String email,
            String phone,
            ContactRole role,
            boolean primary,
            UUID createdBy,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO contacts (
                    id, organization_id, name, position, email, phone, decision_role, primary_contact,
                    version, created_by, created_at, updated_at
                ) VALUES (
                    :id, :organizationId, :name, :position, :email, :phone, :role, :primary,
                    0, :createdBy, :createdAt, :updatedAt
                )
                """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("name", name)
                .param("position", position)
                .param("email", email)
                .param("phone", phone)
                .param("role", role == null ? null : role.name())
                .param("primary", primary)
                .param("createdBy", createdBy)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        return new Contact(
                id, organizationId, name, position, email, phone, 0, createdBy, now, now,
                role, primary, false, null, null, null, PersonalDataStatus.ACTIVE
        );
    }

    public boolean update(Contact contact, int expectedVersion, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE contacts
                SET name = :name, position = :position, email = :email, phone = :phone,
                    decision_role = :role, primary_contact = :primary, inactive = :inactive,
                    confirmed_at = :confirmedAt, confirmed_by = :confirmedBy,
                    version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND organization_id = :organizationId AND version = :expectedVersion
                """)
                .param("id", contact.id())
                .param("organizationId", contact.organizationId())
                .param("name", contact.name())
                .param("position", contact.position())
                .param("email", contact.email())
                .param("phone", contact.phone())
                .param("role", contact.role() == null ? null : contact.role().name())
                .param("primary", contact.primary())
                .param("inactive", contact.inactive())
                .param("confirmedAt", contact.confirmedAt())
                .param("confirmedBy", contact.confirmedBy())
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", now)
                .update() == 1;
    }

    public void insertEvent(
            UUID id,
            UUID contactId,
            UUID commandId,
            UUID actorProfileId,
            String changes,
            int version,
            OffsetDateTime occurredAt
    ) {
        jdbcClient.sql("""
                INSERT INTO contact_events (id, contact_id, command_id, actor_profile_id, changes, version, occurred_at)
                VALUES (:id, :contactId, :commandId, :actorProfileId, :changes, :version, :occurredAt)
                """)
                .param("id", id)
                .param("contactId", contactId)
                .param("commandId", commandId)
                .param("actorProfileId", actorProfileId)
                .param("changes", changes)
                .param("version", version)
                .param("occurredAt", occurredAt)
                .update();
    }

    public List<StoredContactEvent> findEvents(UUID contactId) {
        return jdbcClient.sql("""
                SELECT event.id, event.contact_id, event.actor_profile_id, actor.display_name AS actor_display_name,
                       event.changes, event.version, event.occurred_at
                FROM contact_events event
                JOIN crm_user_profiles actor ON actor.id = event.actor_profile_id
                WHERE event.contact_id = :contactId
                ORDER BY event.occurred_at ASC, event.version ASC, event.id ASC
                """)
                .param("contactId", contactId)
                .query((resultSet, rowNumber) -> new StoredContactEvent(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("contact_id", UUID.class),
                        resultSet.getObject("actor_profile_id", UUID.class),
                        resultSet.getString("actor_display_name"),
                        resultSet.getString("changes"),
                        resultSet.getInt("version"),
                        resultSet.getObject("occurred_at", OffsetDateTime.class)
                ))
                .list();
    }

    private Contact mapContact(ResultSet resultSet, int rowNumber) throws SQLException {
        String role = resultSet.getString("decision_role");
        return new Contact(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("position"),
                resultSet.getString("email"),
                resultSet.getString("phone"),
                resultSet.getInt("version"),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class),
                role == null ? null : ContactRole.valueOf(role),
                resultSet.getBoolean("primary_contact"),
                resultSet.getBoolean("inactive"),
                resultSet.getObject("confirmed_at", OffsetDateTime.class),
                resultSet.getObject("confirmed_by", UUID.class),
                resultSet.getString("confirmed_by_name"),
                PersonalDataStatus.valueOf(resultSet.getString("personal_data_status"))
        );
    }

    public record StoredContactEvent(
            UUID id,
            UUID contactId,
            UUID actorProfileId,
            String actorDisplayName,
            String changes,
            int version,
            OffsetDateTime occurredAt
    ) {
    }
}
