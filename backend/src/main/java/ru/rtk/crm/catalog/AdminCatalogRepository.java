package ru.rtk.crm.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminCatalogRepository {
    private final JdbcClient jdbcClient;

    public AdminCatalogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public AdminCatalogPage findPage(CatalogKind kind, String search, CatalogEntryState state, int page, int size) {
        String where = " WHERE " + state.condition()
                + (search == null ? "" : " AND LOWER(entry.name) LIKE :search " + SearchPattern.LIKE_ESCAPE);
        String pattern = search == null ? "" : SearchPattern.contains(search);
        List<AdminCatalogEntry> items = jdbcClient.sql(select(kind) + where + """

                ORDER BY entry.name ASC, entry.id ASC
                LIMIT :size OFFSET :offset
                """)
                .param("search", pattern)
                .param("size", size)
                .param("offset", (long) page * size)
                .query(this::mapEntry)
                .list();
        long total = jdbcClient.sql("SELECT COUNT(*) FROM " + kind.table() + " entry" + where)
                .param("search", pattern)
                .query(Long.class)
                .single();
        return new AdminCatalogPage(items, page, size, total);
    }

    public List<AdminCatalogEntry> findAll(CatalogKind kind) {
        return jdbcClient.sql(select(kind)).query(this::mapEntry).list();
    }

    public Optional<AdminCatalogEntry> findById(CatalogKind kind, UUID id) {
        return jdbcClient.sql(select(kind) + " WHERE entry.id = :id")
                .param("id", id)
                .query(this::mapEntry)
                .optional();
    }

    public Optional<AdminCatalogEntry> findByIdForUpdate(CatalogKind kind, UUID id) {
        return jdbcClient.sql("SELECT id FROM " + kind.table() + " WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(UUID.class)
                .optional()
                .flatMap(lockedId -> findById(kind, lockedId));
    }

    public Optional<AdminCatalogEntry> findParent(CatalogKind kind, UUID parentId) {
        return jdbcClient.sql("SELECT id, name, archived, version FROM " + kind.parentTable() + " WHERE id = :id FOR UPDATE")
                .param("id", parentId)
                .query((resultSet, rowNumber) -> new AdminCatalogEntry(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        null,
                        null,
                        resultSet.getBoolean("archived"),
                        resultSet.getInt("version")
                ))
                .optional();
    }

    public long countActiveChildren(CatalogKind childKind, UUID parentId) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM %s WHERE %s = :parentId AND archived = FALSE
                """.formatted(childKind.table(), childKind.parentColumn()))
                .param("parentId", parentId)
                .query(Long.class)
                .single();
    }

    public void insert(CatalogKind kind, UUID id, String name, UUID parentId, OffsetDateTime now) {
        String parentColumn = kind.hasParent() ? ", " + kind.parentColumn() : "";
        String parentValue = kind.hasParent() ? ", :parentId" : "";
        jdbcClient.sql("""
                INSERT INTO %s (id, name, archived, version, created_at, updated_at%s)
                VALUES (:id, :name, FALSE, 0, :now, :now%s)
                """.formatted(kind.table(), parentColumn, parentValue))
                .param("id", id)
                .param("name", name)
                .param("parentId", parentId)
                .param("now", now)
                .update();
    }

    public boolean update(CatalogKind kind, UUID id, int expectedVersion, String name, boolean archived, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE %s
                SET name = :name, archived = :archived, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """.formatted(kind.table()))
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("name", name)
                .param("archived", archived)
                .param("now", now)
                .update() == 1;
    }

    private static String select(CatalogKind kind) {
        if (!kind.hasParent()) {
            return """
                    SELECT entry.id, entry.name, NULL AS parent_id, NULL AS parent_name, entry.archived, entry.version
                    FROM %s entry
                    """.formatted(kind.table());
        }
        return """
                SELECT entry.id, entry.name, parent.id AS parent_id, parent.name AS parent_name, entry.archived, entry.version
                FROM %s entry
                JOIN %s parent ON parent.id = entry.%s
                """.formatted(kind.table(), kind.parentTable(), kind.parentColumn());
    }

    private AdminCatalogEntry mapEntry(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AdminCatalogEntry(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getObject("parent_id", UUID.class),
                resultSet.getString("parent_name"),
                resultSet.getBoolean("archived"),
                resultSet.getInt("version")
        );
    }
}
