package ru.rtk.crm.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ContactRepository {
    private final JdbcClient jdbcClient;

    public ContactRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Contact> findByOrganizationId(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT id, organization_id, name, position, email, phone, version, created_by, created_at, updated_at
                FROM contacts
                WHERE organization_id = :organizationId
                ORDER BY name ASC, id ASC
                """)
                .param("organizationId", organizationId)
                .query(this::mapContact)
                .list();
    }

    public Set<UUID> findIdsByOrganizationId(UUID organizationId, List<UUID> contactIds) {
        if (contactIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql("""
                SELECT id
                FROM contacts
                WHERE organization_id = :organizationId AND id IN (:contactIds)
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
        jdbcClient.sql("""
                INSERT INTO contacts (
                    id, organization_id, name, position, email, phone, version, created_by, created_at, updated_at
                ) VALUES (
                    :id, :organizationId, :name, :position, :email, :phone, 0, :createdBy, :createdAt, :updatedAt
                )
                """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("name", name)
                .param("position", position)
                .param("email", email)
                .param("phone", phone)
                .param("createdBy", createdBy)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        return new Contact(id, organizationId, name, position, email, phone, 0, createdBy, now, now);
    }

    private Contact mapContact(ResultSet resultSet, int rowNumber) throws SQLException {
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
                resultSet.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
