package ru.rtk.crm.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class VendorContactRepository {
    private static final String CONTACT_COLUMNS = """
            SELECT id, vendor_id, name, phone, email, prefers_email, prefers_telegram, archived, version
            FROM vendor_contacts
            """;

    private final JdbcClient jdbcClient;

    public VendorContactRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<AdminCatalogEntry> findVendorForUpdate(UUID vendorId) {
        return findVendor(vendorId, " FOR UPDATE");
    }

    public Optional<AdminCatalogEntry> findVendor(UUID vendorId) {
        return findVendor(vendorId, "");
    }

    private Optional<AdminCatalogEntry> findVendor(UUID vendorId, String lock) {
        return jdbcClient.sql("SELECT id, name, archived, version FROM vendors WHERE id = :id" + lock)
                .param("id", vendorId)
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

    public List<VendorContact> findByVendor(UUID vendorId) {
        Map<UUID, List<CatalogReference>> products = new HashMap<>();
        jdbcClient.sql("""
                SELECT id, name, archived, version, vendor_contact_id
                FROM products
                WHERE vendor_id = :vendorId AND vendor_contact_id IS NOT NULL
                ORDER BY name, id
                """)
                .param("vendorId", vendorId)
                .query((ResultSet resultSet) -> {
                    products.computeIfAbsent(resultSet.getObject("vendor_contact_id", UUID.class), id -> new ArrayList<>())
                            .add(product(resultSet));
                });
        return jdbcClient.sql(CONTACT_COLUMNS + " WHERE vendor_id = :vendorId ORDER BY archived, name, id")
                .param("vendorId", vendorId)
                .query((resultSet, rowNumber) -> contact(resultSet, products))
                .list();
    }

    public Optional<VendorContact> findById(UUID vendorId, UUID id) {
        return findByVendor(vendorId).stream().filter(contact -> contact.id().equals(id)).findFirst();
    }

    public Optional<VendorContact> findByIdForUpdate(UUID vendorId, UUID id) {
        return jdbcClient.sql("SELECT id FROM vendor_contacts WHERE id = :id AND vendor_id = :vendorId FOR UPDATE")
                .param("id", id)
                .param("vendorId", vendorId)
                .query(UUID.class)
                .optional()
                .flatMap(lockedId -> findById(vendorId, lockedId));
    }

    public List<CatalogReference> findProducts(UUID vendorId) {
        return jdbcClient.sql("SELECT id, name, archived, version FROM products WHERE vendor_id = :vendorId ORDER BY archived, name, id")
                .param("vendorId", vendorId)
                .query((resultSet, rowNumber) -> product(resultSet))
                .list();
    }

    public List<CatalogReference> findProducts(UUID vendorId, List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                SELECT id, name, archived, version
                FROM products
                WHERE vendor_id = :vendorId AND id IN (:productIds)
                ORDER BY name, id
                """)
                .param("vendorId", vendorId)
                .param("productIds", productIds)
                .query((resultSet, rowNumber) -> product(resultSet))
                .list();
    }

    public boolean emailTaken(UUID vendorId, String email, UUID exceptId) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM vendor_contacts
                WHERE vendor_id = :vendorId AND LOWER(email) = LOWER(:email) AND id <> :exceptId
                """)
                .param("vendorId", vendorId)
                .param("email", email)
                .param("exceptId", exceptId == null ? new UUID(0, 0) : exceptId)
                .query(Long.class)
                .single() > 0;
    }

    public void insert(UUID id, UUID vendorId, VendorContactValues values, String externalKey, UUID actorProfileId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO vendor_contacts (
                    id, vendor_id, name, phone, email, prefers_email, prefers_telegram, archived, external_key, version,
                    created_by, created_at, updated_at
                ) VALUES (
                    :id, :vendorId, :name, :phone, :email, :prefersEmail, :prefersTelegram, :archived, :externalKey, 0,
                    :createdBy, :now, :now
                )
                """)
                .param("id", id)
                .param("vendorId", vendorId)
                .params(parameters(values))
                .param("externalKey", externalKey)
                .param("createdBy", actorProfileId)
                .param("now", now)
                .update();
    }

    public boolean update(UUID id, int expectedVersion, VendorContactValues values, String externalKey, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE vendor_contacts
                SET name = :name, phone = :phone, email = :email, prefers_email = :prefersEmail,
                    prefers_telegram = :prefersTelegram, archived = :archived,
                    external_key = COALESCE(external_key, :externalKey), version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .params(parameters(values))
                .param("externalKey", externalKey)
                .param("now", now)
                .update() == 1;
    }

    public void linkProducts(UUID vendorId, UUID contactId, List<UUID> productIds, OffsetDateTime now) {
        JdbcClient.StatementSpec detach = jdbcClient.sql("""
                UPDATE products
                SET vendor_contact_id = NULL, version = version + 1, updated_at = :now
                WHERE vendor_contact_id = :contactId%s
                """.formatted(productIds.isEmpty() ? "" : " AND id NOT IN (:productIds)"))
                .param("contactId", contactId)
                .param("now", now);
        if (productIds.isEmpty()) {
            detach.update();
            return;
        }
        detach.param("productIds", productIds).update();
        jdbcClient.sql("""
                UPDATE products
                SET vendor_contact_id = :contactId, version = version + 1, updated_at = :now
                WHERE vendor_id = :vendorId AND id IN (:productIds)
                  AND (vendor_contact_id IS NULL OR vendor_contact_id <> :contactId)
                """)
                .param("vendorId", vendorId)
                .param("contactId", contactId)
                .param("productIds", productIds)
                .param("now", now)
                .update();
    }

    private static Map<String, Object> parameters(VendorContactValues values) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("name", values.name());
        parameters.put("phone", values.phone());
        parameters.put("email", values.email());
        parameters.put("prefersEmail", values.prefersEmail());
        parameters.put("prefersTelegram", values.prefersTelegram());
        parameters.put("archived", values.archived());
        return parameters;
    }

    private static CatalogReference product(ResultSet resultSet) throws SQLException {
        return new CatalogReference(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getBoolean("archived"),
                resultSet.getInt("version")
        );
    }

    private static VendorContact contact(ResultSet resultSet, Map<UUID, List<CatalogReference>> products) throws SQLException {
        UUID id = resultSet.getObject("id", UUID.class);
        return new VendorContact(
                id,
                resultSet.getObject("vendor_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("phone"),
                resultSet.getString("email"),
                resultSet.getBoolean("prefers_email"),
                resultSet.getBoolean("prefers_telegram"),
                resultSet.getBoolean("archived"),
                resultSet.getInt("version"),
                List.copyOf(products.getOrDefault(id, List.of()))
        );
    }

    public record VendorContactValues(
            String name,
            String phone,
            String email,
            boolean prefersEmail,
            boolean prefersTelegram,
            boolean archived
    ) {
        public static VendorContactValues of(VendorContact contact) {
            return new VendorContactValues(contact.name(), contact.phone(), contact.email(), contact.prefersEmail(),
                    contact.prefersTelegram(), contact.archived());
        }
    }
}
