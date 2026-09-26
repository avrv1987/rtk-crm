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

    public CatalogPage findActivePrograms(CatalogQuery query) {
        return findActive("programs", query);
    }

    public CatalogPage findActiveProducts(CatalogQuery query) {
        return findActive("products", query);
    }

    public CatalogPage findActiveDirections(CatalogQuery query) {
        return findActive("directions", query);
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

    private CatalogPage findActive(String table, CatalogQuery query) {
        List<CatalogLookup> items = jdbcClient.sql("""
                SELECT id, name, version
                FROM %s
                WHERE archived = FALSE
                ORDER BY name ASC, id ASC
                LIMIT :size OFFSET :offset
                """.formatted(table))
                .param("size", query.size())
                .param("offset", query.offset())
                .query(this::mapLookup)
                .list();
        long total = jdbcClient.sql("SELECT COUNT(*) FROM " + table + " WHERE archived = FALSE")
                .query(Long.class)
                .single();
        return new CatalogPage(items, query.page(), query.size(), total);
    }

    private CatalogLookup mapLookup(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CatalogLookup(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getInt("version")
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
