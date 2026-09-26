package ru.rtk.crm.access;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminTeamRepository {
    private final JdbcClient jdbcClient;

    public AdminTeamRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Team> findAll() {
        return jdbcClient.sql("SELECT id, name, version, archived FROM teams ORDER BY name ASC, id ASC")
                .query(Team.class)
                .list();
    }

    public Optional<Team> findById(UUID teamId) {
        return jdbcClient.sql("SELECT id, name, version, archived FROM teams WHERE id = :teamId")
                .param("teamId", teamId)
                .query(Team.class)
                .optional();
    }

    public Optional<Team> findByIdForUpdate(UUID teamId) {
        return jdbcClient.sql("SELECT id, name, version, archived FROM teams WHERE id = :teamId FOR UPDATE")
                .param("teamId", teamId)
                .query(Team.class)
                .optional();
    }

    public List<TeamMember> findActiveMembers() {
        return jdbcClient.sql("""
                SELECT team_id, display_name, role
                FROM crm_user_profiles
                WHERE team_id IS NOT NULL AND active = TRUE AND role IN ('USER', 'LEADER')
                ORDER BY display_name ASC, id ASC
                """)
                .query((resultSet, rowNumber) -> new TeamMember(
                        resultSet.getObject("team_id", UUID.class),
                        resultSet.getString("display_name"),
                        UserRole.valueOf(resultSet.getString("role"))
                ))
                .list();
    }

    public List<TeamCount> countCurrentOrganizations() {
        return jdbcClient.sql("""
                SELECT team_id, COUNT(*) AS total
                FROM organizations
                WHERE status <> 'ARCHIVED'
                GROUP BY team_id
                """)
                .query((resultSet, rowNumber) -> new TeamCount(
                        resultSet.getObject("team_id", UUID.class),
                        resultSet.getLong("total")
                ))
                .list();
    }

    public long countCurrentOrganizations(UUID teamId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM organizations WHERE team_id = :teamId AND status <> 'ARCHIVED'")
                .param("teamId", teamId)
                .query(Long.class)
                .single();
    }

    public List<TeamCount> countProfiles() {
        return jdbcClient.sql("""
                SELECT team_id, COUNT(*) AS total
                FROM crm_user_profiles
                WHERE team_id IS NOT NULL
                GROUP BY team_id
                """)
                .query((resultSet, rowNumber) -> new TeamCount(
                        resultSet.getObject("team_id", UUID.class),
                        resultSet.getLong("total")
                ))
                .list();
    }

    public long countProfiles(UUID teamId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM crm_user_profiles WHERE team_id = :teamId")
                .param("teamId", teamId)
                .query(Long.class)
                .single();
    }

    public boolean updateArchived(UUID teamId, int expectedVersion, boolean archived, OffsetDateTime updatedAt) {
        return jdbcClient.sql("""
                UPDATE teams
                SET archived = :archived, version = version + 1, updated_at = :updatedAt
                WHERE id = :teamId AND version = :expectedVersion
                """)
                .param("teamId", teamId)
                .param("expectedVersion", expectedVersion)
                .param("archived", archived)
                .param("updatedAt", updatedAt)
                .update() == 1;
    }

    public record TeamMember(UUID teamId, String displayName, UserRole role) {
    }

    public record TeamCount(UUID teamId, long total) {
    }

    public boolean nameTaken(String name, UUID exceptTeamId) {
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM teams
                WHERE LOWER(name) = LOWER(:name) AND (CAST(:exceptTeamId AS UUID) IS NULL OR id <> :exceptTeamId)
                """)
                .param("name", name)
                .param("exceptTeamId", exceptTeamId)
                .query(Long.class)
                .single() > 0;
    }

    public void insert(UUID teamId, String name, OffsetDateTime createdAt) {
        jdbcClient.sql("""
                INSERT INTO teams (id, name, version, created_at, updated_at)
                VALUES (:id, :name, 0, :createdAt, :createdAt)
                """)
                .param("id", teamId)
                .param("name", name)
                .param("createdAt", createdAt)
                .update();
    }

    public boolean rename(UUID teamId, int expectedVersion, String name, OffsetDateTime updatedAt) {
        return jdbcClient.sql("""
                UPDATE teams
                SET name = :name, version = version + 1, updated_at = :updatedAt
                WHERE id = :teamId AND version = :expectedVersion
                """)
                .param("teamId", teamId)
                .param("expectedVersion", expectedVersion)
                .param("name", name)
                .param("updatedAt", updatedAt)
                .update() == 1;
    }
}
