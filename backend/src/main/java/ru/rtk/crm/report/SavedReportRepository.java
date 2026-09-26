package ru.rtk.crm.report;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SavedReportRepository {
    private static final String COLUMNS = "id, name, definition_json, period_preset, version, created_at, updated_at";

    private final JdbcClient jdbcClient;

    public SavedReportRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<StoredReport> findOwned(UUID ownerProfileId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM saved_reports WHERE owner_profile_id = :ownerProfileId"
                        + " ORDER BY LOWER(name), id")
                .param("ownerProfileId", ownerProfileId)
                .query(this::mapReport)
                .list();
    }

    public Optional<StoredReport> findOwned(UUID id, UUID ownerProfileId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM saved_reports WHERE id = :id AND owner_profile_id = :ownerProfileId")
                .param("id", id)
                .param("ownerProfileId", ownerProfileId)
                .query(this::mapReport)
                .optional();
    }

    public long countOwned(UUID ownerProfileId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM saved_reports WHERE owner_profile_id = :ownerProfileId")
                .param("ownerProfileId", ownerProfileId)
                .query(Long.class)
                .single();
    }

    public boolean nameTaken(UUID ownerProfileId, String name, UUID exceptId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM saved_reports WHERE owner_profile_id = :ownerProfileId"
                        + " AND LOWER(name) = LOWER(:name)" + (exceptId == null ? "" : " AND id <> :exceptId"))
                .param("ownerProfileId", ownerProfileId)
                .param("name", name)
                .params(exceptId == null ? Map.of() : Map.of("exceptId", exceptId))
                .query(Long.class)
                .single() > 0;
    }

    public void insert(
            UUID id,
            UUID ownerProfileId,
            String name,
            String definitionJson,
            SavedReportPeriod period,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO saved_reports (id, owner_profile_id, name, definition_json, period_preset, version, created_at, updated_at)
                VALUES (:id, :ownerProfileId, :name, :definitionJson, :period, 0, :now, :now)
                """)
                .param("id", id)
                .param("ownerProfileId", ownerProfileId)
                .param("name", name)
                .param("definitionJson", definitionJson)
                .param("period", period == null ? null : period.name())
                .param("now", now)
                .update();
    }

    public boolean update(
            UUID id,
            UUID ownerProfileId,
            int expectedVersion,
            String name,
            String definitionJson,
            SavedReportPeriod period,
            OffsetDateTime now
    ) {
        return jdbcClient.sql("""
                UPDATE saved_reports
                SET name = :name, definition_json = :definitionJson, period_preset = :period, version = version + 1,
                    updated_at = :now
                WHERE id = :id AND owner_profile_id = :ownerProfileId AND version = :expectedVersion
                """)
                .param("id", id)
                .param("ownerProfileId", ownerProfileId)
                .param("expectedVersion", expectedVersion)
                .param("name", name)
                .param("definitionJson", definitionJson)
                .param("period", period == null ? null : period.name())
                .param("now", now)
                .update() == 1;
    }

    public boolean delete(UUID id, UUID ownerProfileId, int expectedVersion) {
        return jdbcClient.sql("""
                DELETE FROM saved_reports
                WHERE id = :id AND owner_profile_id = :ownerProfileId AND version = :expectedVersion
                """)
                .param("id", id)
                .param("ownerProfileId", ownerProfileId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    private StoredReport mapReport(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredReport(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("definition_json"),
                resultSet.getString("period_preset") == null ? null : SavedReportPeriod.valueOf(resultSet.getString("period_preset")),
                resultSet.getInt("version"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class)
        );
    }

    public record StoredReport(
            UUID id,
            String name,
            String definitionJson,
            SavedReportPeriod period,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }
}
