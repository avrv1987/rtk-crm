package ru.rtk.crm.work;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationDeputyRepository {
    private static final String DEPUTY_COLUMNS = """
            id, organization_id, deputy_profile_id, deputy_display_name, starts_on, ends_on, starts_at, ends_at,
            actor_profile_id, actor_display_name, created_at, ended_at, ended_by_profile_id, ended_by_display_name
            """;

    private final JdbcClient jdbcClient;

    public OrganizationDeputyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void lockOrganization(UUID organizationId) {
        jdbcClient.sql("SELECT id FROM organizations WHERE id = :organizationId FOR UPDATE")
                .param("organizationId", organizationId)
                .query(UUID.class)
                .optional();
    }

    public Optional<DeputyRow> findOpenForUpdate(UUID organizationId) {
        return jdbcClient.sql("SELECT " + DEPUTY_COLUMNS + """
                FROM organization_deputies
                WHERE organization_id = :organizationId AND ended_at IS NULL
                FOR UPDATE
                """)
                .param("organizationId", organizationId)
                .query(this::mapRow)
                .optional();
    }

    public Optional<DeputyRow> findForUpdate(UUID organizationId, UUID deputyId) {
        return jdbcClient.sql("SELECT " + DEPUTY_COLUMNS + """
                FROM organization_deputies
                WHERE id = :deputyId AND organization_id = :organizationId
                FOR UPDATE
                """)
                .param("deputyId", deputyId)
                .param("organizationId", organizationId)
                .query(this::mapRow)
                .optional();
    }

    public List<DeputyRow> findByOrganization(UUID organizationId) {
        return jdbcClient.sql("SELECT " + DEPUTY_COLUMNS + """
                FROM organization_deputies
                WHERE organization_id = :organizationId
                ORDER BY created_at DESC, id DESC
                """)
                .param("organizationId", organizationId)
                .query(this::mapRow)
                .list();
    }

    public List<DeputyRow> findExpiredForUpdate(OffsetDateTime now) {
        return jdbcClient.sql("SELECT " + DEPUTY_COLUMNS + """
                FROM organization_deputies
                WHERE ended_at IS NULL AND ends_at <= :now
                ORDER BY id
                FOR UPDATE
                """)
                .param("now", now)
                .query(this::mapRow)
                .list();
    }

    public void insert(DeputyRow row, UUID commandId) {
        jdbcClient.sql("""
                INSERT INTO organization_deputies (
                    id, organization_id, deputy_profile_id, deputy_display_name, starts_on, ends_on, starts_at, ends_at,
                    command_id, actor_profile_id, actor_display_name, created_at
                ) VALUES (
                    :id, :organizationId, :deputyProfileId, :deputyDisplayName, :startsOn, :endsOn, :startsAt, :endsAt,
                    :commandId, :actorProfileId, :actorDisplayName, :createdAt
                )
                """)
                .param("id", row.id())
                .param("organizationId", row.organizationId())
                .param("deputyProfileId", row.deputyProfileId())
                .param("deputyDisplayName", row.deputyDisplayName())
                .param("startsOn", row.startsOn())
                .param("endsOn", row.endsOn())
                .param("startsAt", row.startsAt())
                .param("endsAt", row.endsAt())
                .param("commandId", commandId)
                .param("actorProfileId", row.actorProfileId())
                .param("actorDisplayName", row.actorDisplayName())
                .param("createdAt", row.createdAt())
                .update();
    }

    public boolean end(UUID deputyId, OffsetDateTime endedAt, UUID endedByProfileId, String endedByDisplayName) {
        return jdbcClient.sql("""
                UPDATE organization_deputies
                SET ended_at = :endedAt, ended_by_profile_id = :endedByProfileId, ended_by_display_name = :endedByDisplayName
                WHERE id = :deputyId AND ended_at IS NULL
                """)
                .param("deputyId", deputyId)
                .param("endedAt", endedAt)
                .param("endedByProfileId", endedByProfileId)
                .param("endedByDisplayName", endedByDisplayName)
                .update() == 1;
    }

    private DeputyRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DeputyRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("deputy_profile_id", UUID.class),
                resultSet.getString("deputy_display_name"),
                resultSet.getObject("starts_on", LocalDate.class),
                resultSet.getObject("ends_on", LocalDate.class),
                resultSet.getObject("starts_at", OffsetDateTime.class),
                resultSet.getObject("ends_at", OffsetDateTime.class),
                resultSet.getObject("actor_profile_id", UUID.class),
                resultSet.getString("actor_display_name"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("ended_at", OffsetDateTime.class),
                resultSet.getObject("ended_by_profile_id", UUID.class),
                resultSet.getString("ended_by_display_name")
        );
    }

    public record DeputyRow(
            UUID id,
            UUID organizationId,
            UUID deputyProfileId,
            String deputyDisplayName,
            LocalDate startsOn,
            LocalDate endsOn,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID actorProfileId,
            String actorDisplayName,
            OffsetDateTime createdAt,
            OffsetDateTime endedAt,
            UUID endedByProfileId,
            String endedByDisplayName
    ) {
        public OrganizationDeputy view(OffsetDateTime now) {
            OrganizationDeputy.Status status = endedAt != null || !endsAt.isAfter(now)
                    ? OrganizationDeputy.Status.ENDED
                    : startsAt.isAfter(now) ? OrganizationDeputy.Status.SCHEDULED : OrganizationDeputy.Status.ACTIVE;
            return new OrganizationDeputy(
                    id,
                    organizationId,
                    deputyProfileId,
                    deputyDisplayName,
                    startsOn,
                    endsOn,
                    endsAt,
                    actorProfileId,
                    actorDisplayName,
                    createdAt,
                    endedAt,
                    endedByProfileId,
                    endedByDisplayName,
                    status
            );
        }
    }
}
