package ru.rtk.crm.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
    private final JdbcClient jdbcClient;

    public CatalogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public CatalogPage findPrograms(CatalogQuery query, CatalogEntryState state) {
        return findPage("programs", "LEFT JOIN directions parent ON parent.id = entry.direction_id", "parent.name", query, state);
    }

    public CatalogPage findProducts(CatalogQuery query, CatalogEntryState state) {
        return findPage("products", "LEFT JOIN vendors parent ON parent.id = entry.vendor_id", "parent.name", query, state);
    }

    public CatalogPage findDirections(CatalogQuery query, CatalogEntryState state) {
        return findPage("directions", "", "CAST(NULL AS VARCHAR(255))", query, state);
    }

    public Optional<CatalogReference> findActiveProgramById(UUID programId) {
        if (programId == null) {
            return Optional.empty();
        }
        return jdbcClient.sql("""
                SELECT id, name, archived, version
                FROM programs
                WHERE id = :id AND archived = FALSE
                """)
                .param("id", programId)
                .query(this::mapReference)
                .optional();
    }

    public Optional<CatalogReference> findProgramById(UUID programId) {
        if (programId == null) {
            return Optional.empty();
        }
        return jdbcClient.sql("""
                SELECT id, name, archived, version
                FROM programs
                WHERE id = :id
                """)
                .param("id", programId)
                .query(this::mapReference)
                .optional();
    }

    public Set<UUID> findActiveProductIds(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql("""
                SELECT id
                FROM products
                WHERE archived = FALSE AND id IN (:productIds)
                """)
                .param("productIds", productIds)
                .query(UUID.class)
                .list());
    }

    // Родитель (направление программы, вендор продукта) нужен формам для группировки длинных списков.
    private CatalogPage findPage(String table, String parentJoin, String groupName, CatalogQuery query, CatalogEntryState state) {
        List<CatalogLookup> items = jdbcClient.sql("""
                SELECT entry.id, entry.name, entry.archived, entry.version, %s AS group_name
                FROM %s entry
                %s
                WHERE %s
                ORDER BY entry.archived ASC, entry.name ASC, entry.id ASC
                LIMIT :size OFFSET :offset
                """.formatted(groupName, table, parentJoin, state.condition()))
                .param("size", query.size())
                .param("offset", query.offset())
                .query(this::mapLookup)
                .list();
        long total = jdbcClient.sql("SELECT COUNT(*) FROM " + table + " entry WHERE " + state.condition())
                .query(Long.class)
                .single();
        return new CatalogPage(items, query.page(), query.size(), total);
    }

    private CatalogLookup mapLookup(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CatalogLookup(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getBoolean("archived"),
                resultSet.getInt("version"),
                resultSet.getString("group_name")
        );
    }

    private CatalogReference mapReference(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CatalogReference(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getBoolean("archived"),
                resultSet.getInt("version")
        );
    }
}
