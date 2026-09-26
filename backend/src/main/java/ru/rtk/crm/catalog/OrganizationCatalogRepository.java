package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationCatalogRepository {
    private final JdbcClient jdbcClient;

    public OrganizationCatalogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<NameRow> findNames() {
        return jdbcClient.sql("""
                SELECT org.id, org.name, org.type, org.status, org.team_id, team.name AS team_name, org.owner_manager_id
                FROM organizations org
                JOIN teams team ON team.id = org.team_id
                """)
                .query((resultSet, rowNumber) -> new NameRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        OrganizationType.valueOf(resultSet.getString("type")),
                        OrganizationStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("team_id", UUID.class),
                        resultSet.getString("team_name"),
                        resultSet.getObject("owner_manager_id", UUID.class)
                ))
                .list();
    }

    public void insert(
            UUID id,
            OrganizationDetails details,
            UUID teamId,
            UUID ownerManagerId,
            OrganizationStatus status,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO organizations (
                    id, name, type, team_id, owner_manager_id, status, city, website, inn, version, created_at, updated_at
                ) VALUES (
                    :id, :name, :type, :teamId, :ownerManagerId, :status, :city, :website, :inn, 0, :now, :now
                )
                """)
                .param("id", id)
                .param("name", details.name())
                .param("type", details.type().name())
                .param("teamId", teamId)
                .param("ownerManagerId", ownerManagerId)
                .param("status", status.name())
                .param("city", details.city())
                .param("website", details.website())
                .param("inn", details.inn())
                .param("now", now)
                .update();
    }

    public boolean updateDetails(UUID id, int expectedVersion, OrganizationDetails details, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE organizations
                SET name = :name, type = :type, city = :city, website = :website, inn = :inn,
                    version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("name", details.name())
                .param("type", details.type().name())
                .param("city", details.city())
                .param("website", details.website())
                .param("inn", details.inn())
                .param("now", now)
                .update() == 1;
    }

    public boolean updateStatus(UUID id, int expectedVersion, OrganizationStatus status, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE organizations
                SET status = :status, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("status", status.name())
                .param("now", now)
                .update() == 1;
    }

    public record NameRow(
            UUID id,
            String name,
            OrganizationType type,
            OrganizationStatus status,
            UUID teamId,
            String teamName,
            UUID ownerManagerId
    ) {
    }
}
