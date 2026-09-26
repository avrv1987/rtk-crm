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
        return jdbcClient.sql("SELECT id, name, version FROM teams ORDER BY name ASC, id ASC")
                .query(Team.class)
                .list();
    }

    public Optional<Team> findById(UUID teamId) {
        return jdbcClient.sql("SELECT id, name, version FROM teams WHERE id = :teamId")
                .param("teamId", teamId)
                .query(Team.class)
                .optional();
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
