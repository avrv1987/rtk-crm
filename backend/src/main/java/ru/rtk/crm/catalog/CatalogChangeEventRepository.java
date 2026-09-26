package ru.rtk.crm.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogChangeEventRepository {
    private static final int CHANGES_LIMIT = 2000;
    private static final int NAME_LIMIT = 300;

    private final JdbcClient jdbcClient;

    public CatalogChangeEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void lockNames(CatalogEntityType entityType) {
        jdbcClient.sql("SELECT entity_type FROM catalog_name_locks WHERE entity_type = :entityType FOR UPDATE")
                .param("entityType", entityType.name())
                .query(String.class)
                .single();
    }

    public void insert(
            CatalogEntityType entityType,
            UUID entityId,
            CatalogChangeAction action,
            String entityName,
            String changes,
            UUID actorProfileId,
            String requestId,
            OffsetDateTime occurredAt
    ) {
        int inserted = jdbcClient.sql("""
                INSERT INTO catalog_change_events (
                    id, entity_type, entity_id, action, entity_name, changes,
                    actor_profile_id, actor_display_name, request_id, occurred_at
                )
                SELECT :id, :entityType, :entityId, :action, :entityName, :changes,
                       profile.id, profile.display_name, :requestId, :occurredAt
                FROM crm_user_profiles profile
                WHERE profile.id = :actorProfileId
                """)
                .param("id", UUID.randomUUID())
                .param("entityType", entityType.name())
                .param("entityId", entityId)
                .param("action", action.name())
                .param("entityName", truncate(entityName, NAME_LIMIT))
                .param("changes", changes == null || changes.isBlank() ? null : truncate(changes, CHANGES_LIMIT))
                .param("actorProfileId", actorProfileId)
                .param("requestId", requestId)
                .param("occurredAt", occurredAt)
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("Catalog change actor profile is unavailable for audit");
        }
    }

    public CatalogChangeEventPage findPage(CatalogEntityType entityType, int page, int size) {
        String condition = entityType == null ? "TRUE" : "entity_type = :entityType";
        List<CatalogChangeEvent> items = jdbcClient.sql("""
                SELECT id, entity_type, entity_id, action, entity_name, changes, actor_display_name, request_id, occurred_at
                FROM catalog_change_events
                WHERE %s
                ORDER BY occurred_at DESC, id DESC
                LIMIT :size OFFSET :offset
                """.formatted(condition))
                .param("entityType", entityType == null ? "" : entityType.name())
                .param("size", size)
                .param("offset", (long) page * size)
                .query(this::mapEvent)
                .list();
        long total = jdbcClient.sql("SELECT COUNT(*) FROM catalog_change_events WHERE " + condition)
                .param("entityType", entityType == null ? "" : entityType.name())
                .query(Long.class)
                .single();
        return new CatalogChangeEventPage(items, page, size, total);
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    private CatalogChangeEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CatalogChangeEvent(
                resultSet.getObject("id", UUID.class),
                CatalogEntityType.valueOf(resultSet.getString("entity_type")),
                resultSet.getObject("entity_id", UUID.class),
                CatalogChangeAction.valueOf(resultSet.getString("action")),
                resultSet.getString("entity_name"),
                resultSet.getString("changes"),
                resultSet.getString("actor_display_name"),
                resultSet.getString("request_id"),
                resultSet.getObject("occurred_at", OffsetDateTime.class)
        );
    }
}
