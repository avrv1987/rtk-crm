package ru.rtk.crm.catalogimport;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.VendorContactRepository.VendorContactValues;

@Repository
public class CatalogImportRepository {
    static final String DIRECTIONS = "directions";
    static final String VENDORS = "vendors";
    static final String PROGRAMS = "programs";
    static final String PRODUCTS = "products";

    private static final String ORGANIZATION_COLUMNS = """
            SELECT organization.id, organization.external_key, organization.name, organization.type,
                   organization.team_id, organization.owner_manager_id, owner.display_name AS owner_display_name,
                   organization.version, organization.status
            FROM organizations organization
            LEFT JOIN crm_user_profiles owner ON owner.id = organization.owner_manager_id
            """;
    private static final String CONTACT_COLUMNS = """
            SELECT id, external_key, organization_id, name, position, email, phone, version, personal_data_status
            FROM contacts
            """;
    private static final String AGREEMENT_COLUMNS = """
            SELECT agreement.id, agreement.external_key, agreement.interaction_id, interaction.organization_id,
                   interaction.title AS interaction_title, agreement.product_id, agreement.contract_number,
                   agreement.license_signed, agreement.license_expiry_year, agreement.transfer_status, agreement.version,
                   agreement.archived_at
            FROM product_agreements agreement
            JOIN interactions interaction ON interaction.id = agreement.interaction_id
            """;

    private static final String VENDOR_CONTACT_COLUMNS = """
            SELECT id, external_key, vendor_id, name, phone, email, prefers_email, prefers_telegram, archived,
                   personal_data_status, version
            FROM vendor_contacts
            """;
    private static final String VENDOR_PRODUCT_COLUMNS = """
            SELECT id, external_key, name, vendor_id, vendor_contact_id, version
            FROM products
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public CatalogImportRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void insertPreview(
            UUID importId,
            UUID jobId,
            UUID createdBy,
            CatalogImportProfile profile,
            CatalogImportMapping mapping,
            List<CatalogImportStoredRow> rows,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO catalog_imports (
                    id, created_by, profile, status, version, mapping_json, created_at, updated_at
                ) VALUES (
                    :id, :createdBy, :profile, :status, 0, :mappingJson, :createdAt, :updatedAt
                )
                """)
                .param("id", importId)
                .param("createdBy", createdBy)
                .param("profile", profile.name())
                .param("status", CatalogImportStatus.PREVIEWED.name())
                .param("mappingJson", json(mapping))
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        for (CatalogImportStoredRow row : rows) {
            jdbcClient.sql("""
                    INSERT INTO catalog_import_rows (
                        id, import_id, sheet_name, row_number, status, plan_json, errors_json, applied
                    ) VALUES (
                        :id, :importId, :sheetName, :rowNumber, :status, :planJson, :errorsJson, FALSE
                    )
                    """)
                    .param("id", row.id())
                    .param("importId", importId)
                    .param("sheetName", row.sheetName())
                    .param("rowNumber", row.rowNumber())
                    .param("status", row.status().name())
                    .param("planJson", json(row.plan()))
                    .param("errorsJson", json(row.fieldErrors()))
                    .update();
        }
        insertJob(jobId, importId, createdBy, CatalogImportJobAction.PREVIEW, "Предпросмотр построен", now);
    }

    public Optional<CatalogImportStored> findById(UUID importId) {
        return findHeader(importId, false).map(this::withRows);
    }

    public Optional<CatalogImportStored> findByIdForUpdate(UUID importId) {
        return findHeader(importId, true).map(this::withRows);
    }

    public Optional<CatalogImportJobView> findJob(UUID jobId) {
        return jdbcClient.sql("""
                SELECT id, import_id, action, status, result_json, created_at, completed_at
                FROM catalog_import_jobs
                WHERE id = :id
                """)
                .param("id", jobId)
                .query((resultSet, rowNumber) -> new CatalogImportJobView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("import_id", UUID.class),
                        CatalogImportJobAction.valueOf(resultSet.getString("action")),
                        CatalogImportJobStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("result_json"),
                        resultSet.getObject("created_at", OffsetDateTime.class),
                        resultSet.getObject("completed_at", OffsetDateTime.class)
                ))
                .optional();
    }

    public boolean markApplied(UUID importId, int expectedVersion, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE catalog_imports
                SET status = :status, version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND status = :previewed AND version = :expectedVersion
                """)
                .param("id", importId)
                .param("status", CatalogImportStatus.APPLIED.name())
                .param("previewed", CatalogImportStatus.PREVIEWED.name())
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", now)
                .update() == 1;
    }

    public void markRowsApplied(UUID importId, List<UUID> rowIds) {
        if (rowIds.isEmpty()) {
            return;
        }
        jdbcClient.sql("""
                UPDATE catalog_import_rows
                SET applied = TRUE
                WHERE import_id = :importId AND id IN (:rowIds)
                """)
                .param("importId", importId)
                .param("rowIds", rowIds)
                .update();
    }

    public void insertApplyJob(UUID jobId, UUID importId, UUID createdBy, OffsetDateTime now) {
        insertJob(jobId, importId, createdBy, CatalogImportJobAction.APPLY, "Изменения применены", now);
    }

    public Optional<ImportProfile> findActiveProfile(UUID profileId) {
        if (profileId == null) {
            return Optional.empty();
        }
        return jdbcClient.sql("""
                SELECT id, display_name, role, team_id
                FROM crm_user_profiles
                WHERE id = :id AND active = TRUE
                """)
                .param("id", profileId)
                .query(this::mapProfile)
                .optional();
    }

    public List<ImportProfile> findActiveManagers() {
        return jdbcClient.sql("""
                SELECT id, display_name, role, team_id
                FROM crm_user_profiles
                WHERE active = TRUE AND role = 'USER' AND team_id IS NOT NULL
                """)
                .query(this::mapProfile)
                .list();
    }

    public List<String> findTransferStatuses() {
        return jdbcClient.sql("""
                SELECT DISTINCT transfer_status
                FROM product_agreements
                WHERE transfer_status IS NOT NULL
                """)
                .query(String.class)
                .list();
    }

    public List<CatalogImportManagerCandidate> findManagerCandidates(List<UUID> profileIds) {
        if (profileIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                SELECT profile.id, profile.display_name, team.name AS team_name,
                       (SELECT COUNT(*) FROM organizations organization
                        WHERE organization.owner_manager_id = profile.id AND organization.status <> 'ARCHIVED')
                           AS organization_count
                FROM crm_user_profiles profile
                LEFT JOIN teams team ON team.id = profile.team_id
                WHERE profile.id IN (:profileIds)
                ORDER BY team.name, profile.id
                """)
                .param("profileIds", profileIds)
                .query((resultSet, rowNumber) -> new CatalogImportManagerCandidate(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("display_name"),
                        resultSet.getString("team_name"),
                        resultSet.getLong("organization_count")
                ))
                .list();
    }

    public boolean activeTeamExists(UUID teamId) {
        return jdbcClient.sql("SELECT id FROM teams WHERE id = :teamId AND archived = FALSE FOR UPDATE")
                .param("teamId", teamId)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    public List<ImportAgreementLink> findCurrentAgreementLinks(Set<UUID> organizationIds) {
        if (organizationIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                SELECT agreement.id, organization.id AS organization_id, organization.name AS organization_name,
                       product.id AS product_id, vendor.name AS vendor_name, product.name AS product_name,
                       agreement.contract_number, interaction.title AS interaction_title
                FROM product_agreements agreement
                JOIN interactions interaction ON interaction.id = agreement.interaction_id
                JOIN organizations organization ON organization.id = interaction.organization_id
                JOIN products product ON product.id = agreement.product_id
                JOIN vendors vendor ON vendor.id = product.vendor_id
                WHERE agreement.archived_at IS NULL AND organization.id IN (:organizationIds)
                ORDER BY organization.name, product.name, agreement.id
                """)
                .param("organizationIds", organizationIds)
                .query((resultSet, rowNumber) -> new ImportAgreementLink(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getObject("product_id", UUID.class),
                        resultSet.getString("vendor_name"),
                        resultSet.getString("product_name"),
                        resultSet.getString("contract_number"),
                        resultSet.getString("interaction_title")
                ))
                .list();
    }

    public void changeAgreementArchived(UUID agreementId, boolean archived, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE product_agreements
                SET archived_at = :archivedAt, version = version + 1, updated_at = :updatedAt
                WHERE id = :id
                """)
                .param("id", agreementId)
                .param("archivedAt", archived ? now : null)
                .param("updatedAt", now)
                .update();
    }

    public List<ImportOrganization> findOrganizations() {
        return jdbcClient.sql(ORGANIZATION_COLUMNS).query(this::mapOrganization).list();
    }

    public Optional<ImportOrganization> findOrganizationById(UUID organizationId) {
        return jdbcClient.sql(ORGANIZATION_COLUMNS + " WHERE organization.id = :id")
                .param("id", organizationId)
                .query(this::mapOrganization)
                .optional();
    }

    public Optional<ImportOrganization> findOrganizationByExternalKey(String externalKey) {
        return jdbcClient.sql(ORGANIZATION_COLUMNS + " WHERE organization.external_key = :externalKey")
                .param("externalKey", externalKey)
                .query(this::mapOrganization)
                .optional();
    }

    public List<ImportCatalogEntry> findCatalogEntries(String table) {
        return jdbcClient.sql("SELECT id, external_key, name, version FROM %s".formatted(table))
                .query(this::mapCatalogEntry)
                .list();
    }

    public Optional<ImportCatalogEntry> findCatalogEntry(String table, UUID id, String externalKey) {
        String condition = id == null ? "external_key = :value" : "id = :value";
        return jdbcClient.sql("SELECT id, external_key, name, version FROM %s WHERE %s".formatted(table, condition))
                .param("value", id == null ? externalKey : id)
                .query(this::mapCatalogEntry)
                .optional();
    }

    public void insertCatalogEntry(String table, UUID id, String externalKey, String name, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO %s (id, external_key, name, archived, version, created_at, updated_at)
                VALUES (:id, :externalKey, :name, FALSE, 0, :createdAt, :updatedAt)
                """.formatted(table))
                .param("id", id)
                .param("externalKey", externalKey)
                .param("name", name)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }

    public boolean updateCatalogEntry(
            String table,
            UUID id,
            String externalKey,
            String name,
            int expectedVersion,
            OffsetDateTime now
    ) {
        return jdbcClient.sql("""
                UPDATE %s
                SET name = :name, external_key = COALESCE(external_key, :externalKey),
                    version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND version = :expectedVersion
                """.formatted(table))
                .param("id", id)
                .param("externalKey", externalKey)
                .param("name", name)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", now)
                .update() == 1;
    }

    public List<ImportChildEntry> findChildEntries(String table) {
        return jdbcClient.sql("SELECT id, external_key, name, %s AS parent_id, version FROM %s"
                        .formatted(parentColumn(table), table))
                .query(this::mapChildEntry)
                .list();
    }

    public Optional<ImportChildEntry> findChildEntry(String table, UUID id, String externalKey) {
        String condition = id == null ? "external_key = :value" : "id = :value";
        return jdbcClient.sql("SELECT id, external_key, name, %s AS parent_id, version FROM %s WHERE %s"
                        .formatted(parentColumn(table), table, condition))
                .param("value", id == null ? externalKey : id)
                .query(this::mapChildEntry)
                .optional();
    }

    public void insertChildEntry(
            String table,
            UUID id,
            String externalKey,
            UUID parentId,
            String name,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO %s (id, external_key, %s, name, archived, version, created_at, updated_at)
                VALUES (:id, :externalKey, :parentId, :name, FALSE, 0, :createdAt, :updatedAt)
                """.formatted(table, parentColumn(table)))
                .param("id", id)
                .param("externalKey", externalKey)
                .param("parentId", parentId)
                .param("name", name)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }

    public boolean updateChildEntry(
            String table,
            UUID id,
            String externalKey,
            UUID parentId,
            String name,
            int expectedVersion,
            OffsetDateTime now
    ) {
        String contactReset = PRODUCTS.equals(table)
                ? "vendor_contact_id = CASE WHEN vendor_id = :parentId THEN vendor_contact_id END, "
                : "";
        return jdbcClient.sql("""
                UPDATE %s
                SET %s%s = :parentId, name = :name, external_key = COALESCE(external_key, :externalKey),
                    version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND version = :expectedVersion
                """.formatted(table, contactReset, parentColumn(table)))
                .param("id", id)
                .param("externalKey", externalKey)
                .param("parentId", parentId)
                .param("name", name)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", now)
                .update() == 1;
    }

    public List<ImportContact> findContacts() {
        return jdbcClient.sql(CONTACT_COLUMNS).query(this::mapContact).list();
    }

    public Optional<ImportContact> findContact(UUID id, String externalKey) {
        String condition = id == null ? " WHERE external_key = :value" : " WHERE id = :value";
        return jdbcClient.sql(CONTACT_COLUMNS + condition)
                .param("value", id == null ? externalKey : id)
                .query(this::mapContact)
                .optional();
    }

    public List<ImportVendorContact> findVendorContacts() {
        return jdbcClient.sql(VENDOR_CONTACT_COLUMNS).query(this::mapVendorContact).list();
    }

    public Optional<ImportVendorContact> findVendorContact(UUID id, String externalKey) {
        String condition = id == null ? " WHERE external_key = :value" : " WHERE id = :value";
        return jdbcClient.sql(VENDOR_CONTACT_COLUMNS + condition)
                .param("value", id == null ? externalKey : id)
                .query(this::mapVendorContact)
                .optional();
    }

    public void insertVendorContact(
            UUID id,
            String externalKey,
            UUID vendorId,
            VendorContactValues values,
            UUID createdBy,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO vendor_contacts (
                    id, external_key, vendor_id, name, phone, email, prefers_email, prefers_telegram, archived, version,
                    created_by, created_at, updated_at
                ) VALUES (
                    :id, :externalKey, :vendorId, :name, :phone, :email, :prefersEmail, :prefersTelegram, FALSE, 0,
                    :createdBy, :now, :now
                )
                """)
                .param("id", id)
                .param("externalKey", externalKey)
                .param("vendorId", vendorId)
                .param("name", values.name())
                .param("phone", values.phone())
                .param("email", values.email())
                .param("prefersEmail", values.prefersEmail())
                .param("prefersTelegram", values.prefersTelegram())
                .param("createdBy", createdBy)
                .param("now", now)
                .update();
    }

    public boolean updateVendorContact(
            UUID id,
            String externalKey,
            VendorContactValues values,
            int expectedVersion,
            OffsetDateTime now
    ) {
        return jdbcClient.sql("""
                UPDATE vendor_contacts
                SET name = :name, phone = :phone, email = :email, prefers_email = :prefersEmail,
                    prefers_telegram = :prefersTelegram, archived = FALSE,
                    external_key = COALESCE(external_key, :externalKey), version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """)
                .param("id", id)
                .param("externalKey", externalKey)
                .param("name", values.name())
                .param("phone", values.phone())
                .param("email", values.email())
                .param("prefersEmail", values.prefersEmail())
                .param("prefersTelegram", values.prefersTelegram())
                .param("expectedVersion", expectedVersion)
                .param("now", now)
                .update() == 1;
    }

    public List<ImportVendorProduct> findVendorProducts() {
        return jdbcClient.sql(VENDOR_PRODUCT_COLUMNS).query(this::mapVendorProduct).list();
    }

    public Optional<ImportVendorProduct> findVendorProduct(UUID id, String externalKey) {
        String condition = id == null ? " WHERE external_key = :value" : " WHERE id = :value";
        return jdbcClient.sql(VENDOR_PRODUCT_COLUMNS + condition)
                .param("value", id == null ? externalKey : id)
                .query(this::mapVendorProduct)
                .optional();
    }

    public void insertVendorProduct(UUID id, String externalKey, UUID vendorId, String name, UUID contactId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO products (id, external_key, vendor_id, name, vendor_contact_id, archived, version, created_at, updated_at)
                VALUES (:id, :externalKey, :vendorId, :name, :contactId, FALSE, 0, :now, :now)
                """)
                .param("id", id)
                .param("externalKey", externalKey)
                .param("vendorId", vendorId)
                .param("name", name)
                .param("contactId", contactId)
                .param("now", now)
                .update();
    }

    public boolean updateVendorProduct(UUID id, String externalKey, UUID contactId, int expectedVersion, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE products
                SET vendor_contact_id = :contactId, external_key = COALESCE(external_key, :externalKey),
                    version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """)
                .param("id", id)
                .param("externalKey", externalKey)
                .param("contactId", contactId)
                .param("expectedVersion", expectedVersion)
                .param("now", now)
                .update() == 1;
    }

    public List<ImportAgreement> findAgreements() {
        return jdbcClient.sql(AGREEMENT_COLUMNS).query(this::mapAgreement).list();
    }

    public Optional<ImportAgreement> findAgreement(UUID id, String externalKey) {
        String condition = id == null ? " WHERE agreement.external_key = :value" : " WHERE agreement.id = :value";
        return jdbcClient.sql(AGREEMENT_COLUMNS + condition)
                .param("value", id == null ? externalKey : id)
                .query(this::mapAgreement)
                .optional();
    }

    public List<UUID> findInteractionIdsWithProduct(UUID organizationId, UUID productId) {
        return jdbcClient.sql("""
                SELECT DISTINCT interaction.id
                FROM interactions interaction
                JOIN product_agreements agreement ON agreement.interaction_id = interaction.id
                WHERE interaction.organization_id = :organizationId AND agreement.product_id = :productId
                """)
                .param("organizationId", organizationId)
                .param("productId", productId)
                .query(UUID.class)
                .list();
    }

    public Optional<ImportInteraction> findInteractionById(UUID interactionId) {
        return findInteraction(interactionId, "");
    }

    public Optional<ImportInteraction> findInteractionByIdForUpdate(UUID interactionId) {
        return findInteraction(interactionId, " FOR UPDATE");
    }

    public boolean hasImportEvent(String externalKey) {
        return jdbcClient.sql("SELECT COUNT(*) FROM interaction_events WHERE external_key = :externalKey")
                .param("externalKey", externalKey)
                .query(Long.class)
                .single() > 0;
    }

    public void insertOrganization(
            UUID id,
            String externalKey,
            String name,
            String type,
            UUID teamId,
            UUID ownerManagerId,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO organizations (
                    id, external_key, name, type, team_id, owner_manager_id, version, created_at, updated_at
                ) VALUES (
                    :id, :externalKey, :name, :type, :teamId, :ownerManagerId, 0, :createdAt, :updatedAt
                )
                """)
                .param("id", id)
                .param("externalKey", externalKey)
                .param("name", name)
                .param("type", type)
                .param("teamId", teamId)
                .param("ownerManagerId", ownerManagerId)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }

    public boolean updateOrganizationDetails(
            UUID id,
            String externalKey,
            String name,
            String type,
            int expectedVersion,
            OffsetDateTime now
    ) {
        return jdbcClient.sql("""
                UPDATE organizations
                SET name = :name, type = :type, external_key = COALESCE(external_key, :externalKey),
                    version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND version = :expectedVersion
                """)
                .param("id", id)
                .param("externalKey", externalKey)
                .param("name", name)
                .param("type", type)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", now)
                .update() == 1;
    }

    public void insertContact(
            UUID id,
            String externalKey,
            UUID organizationId,
            ContactValues values,
            UUID createdBy,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO contacts (
                    id, external_key, organization_id, name, position, email, phone,
                    version, created_by, created_at, updated_at
                ) VALUES (
                    :id, :externalKey, :organizationId, :name, :position, :email, :phone,
                    0, :createdBy, :createdAt, :updatedAt
                )
                """)
                .param("id", id)
                .param("externalKey", externalKey)
                .param("organizationId", organizationId)
                .param("name", values.name())
                .param("position", values.position())
                .param("email", values.email())
                .param("phone", values.phone())
                .param("createdBy", createdBy)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }

    public boolean updateContact(UUID id, String externalKey, ContactValues values, int expectedVersion, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE contacts
                SET name = :name, position = :position, email = :email, phone = :phone,
                    external_key = COALESCE(external_key, :externalKey),
                    version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND version = :expectedVersion AND personal_data_status = 'ACTIVE'
                """)
                .param("id", id)
                .param("externalKey", externalKey)
                .param("name", values.name())
                .param("position", values.position())
                .param("email", values.email())
                .param("phone", values.phone())
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", now)
                .update() == 1;
    }

    public void insertAgreement(
            UUID id,
            String externalKey,
            UUID interactionId,
            UUID productId,
            AgreementValues values,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO product_agreements (
                    id, external_key, interaction_id, product_id, contract_number, license_signed,
                    license_expiry_year, transfer_status, version, created_at, updated_at
                ) VALUES (
                    :id, :externalKey, :interactionId, :productId, :contractNumber, :licenseSigned,
                    :licenseExpiryYear, :transferStatus, 0, :createdAt, :updatedAt
                )
                """)
                .param("id", id)
                .param("externalKey", externalKey)
                .param("interactionId", interactionId)
                .param("productId", productId)
                .param("contractNumber", values.contractNumber())
                .param("licenseSigned", values.licenseSigned())
                .param("licenseExpiryYear", values.licenseExpiryYear())
                .param("transferStatus", values.transferStatus())
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }

    public boolean updateAgreement(
            UUID id,
            String externalKey,
            UUID productId,
            AgreementValues values,
            int expectedVersion,
            OffsetDateTime now
    ) {
        return jdbcClient.sql("""
                UPDATE product_agreements
                SET product_id = :productId, contract_number = :contractNumber, license_signed = :licenseSigned,
                    license_expiry_year = :licenseExpiryYear, transfer_status = :transferStatus,
                    external_key = COALESCE(external_key, :externalKey),
                    version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND version = :expectedVersion
                """)
                .param("id", id)
                .param("externalKey", externalKey)
                .param("productId", productId)
                .param("contractNumber", values.contractNumber())
                .param("licenseSigned", values.licenseSigned())
                .param("licenseExpiryYear", values.licenseExpiryYear())
                .param("transferStatus", values.transferStatus())
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", now)
                .update() == 1;
    }

    public boolean advanceInteractionVersion(UUID interactionId, int expectedVersion, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE interactions
                SET version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND version = :expectedVersion
                """)
                .param("id", interactionId)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", now)
                .update() == 1;
    }

    public void insertCommentEvent(
            UUID eventId,
            String externalKey,
            UUID commandId,
            ImportInteraction interaction,
            String comment,
            UUID actorProfileId,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO interaction_events (
                    id, external_key, interaction_id, command_id, type, stage_id, stage_name_snapshot,
                    comment, actor_profile_id, owner_manager_id_snapshot, version, occurred_at
                ) VALUES (
                    :id, :externalKey, :interactionId, :commandId, :type, :stageId, :stageName,
                    :comment, :actorProfileId, :ownerManagerId, :version, :occurredAt
                )
                """)
                .param("id", eventId)
                .param("externalKey", externalKey)
                .param("interactionId", interaction.id())
                .param("commandId", commandId)
                .param("type", "COMMENTED")
                .param("stageId", interaction.currentStageId())
                .param("stageName", interaction.currentStageName())
                .param("comment", comment)
                .param("actorProfileId", actorProfileId)
                .param("ownerManagerId", interaction.ownerManagerId())
                .param("version", interaction.version() + 1)
                .param("occurredAt", now)
                .update();
    }

    private Optional<ImportInteraction> findInteraction(UUID interactionId, String lock) {
        if (interactionId == null) {
            return Optional.empty();
        }
        return jdbcClient.sql(("""
                SELECT i.id, i.organization_id, i.title, i.current_stage_id, stage.name AS current_stage_name,
                       organization.owner_manager_id, i.version
                FROM interactions i
                JOIN interaction_stages stage ON stage.id = i.current_stage_id AND stage.interaction_id = i.id
                JOIN organizations organization ON organization.id = i.organization_id
                WHERE i.id = :id
                """ + lock))
                .param("id", interactionId)
                .query((resultSet, rowNumber) -> new ImportInteraction(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getObject("current_stage_id", UUID.class),
                        resultSet.getString("current_stage_name"),
                        resultSet.getObject("owner_manager_id", UUID.class),
                        resultSet.getInt("version")
                ))
                .optional();
    }

    private Optional<ImportHeader> findHeader(UUID importId, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        return jdbcClient.sql(("""
                SELECT id, created_by, profile, status, version, mapping_json, created_at, updated_at
                FROM catalog_imports
                WHERE id = :id
                """ + lock))
                .param("id", importId)
                .query((resultSet, rowNumber) -> new ImportHeader(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("created_by", UUID.class),
                        CatalogImportProfile.valueOf(resultSet.getString("profile")),
                        CatalogImportStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("version"),
                        read(resultSet.getString("mapping_json"), CatalogImportMapping.class),
                        resultSet.getObject("created_at", OffsetDateTime.class),
                        resultSet.getObject("updated_at", OffsetDateTime.class)
                ))
                .optional();
    }

    private CatalogImportStored withRows(ImportHeader header) {
        List<CatalogImportStoredRow> rows = jdbcClient.sql("""
                SELECT id, sheet_name, row_number, status, plan_json, errors_json, applied
                FROM catalog_import_rows
                WHERE import_id = :importId
                ORDER BY row_number ASC, id ASC
                """)
                .param("importId", header.id())
                .query((resultSet, rowNumber) -> new CatalogImportStoredRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("sheet_name"),
                        resultSet.getInt("row_number"),
                        CatalogImportRowStatus.valueOf(resultSet.getString("status")),
                        read(resultSet.getString("plan_json"), CatalogImportPlan.class),
                        readMap(resultSet.getString("errors_json")),
                        resultSet.getBoolean("applied")
                ))
                .list();
        return new CatalogImportStored(
                header.id(),
                header.createdBy(),
                header.profile(),
                header.status(),
                header.version(),
                header.mapping(),
                List.copyOf(rows),
                header.createdAt(),
                header.updatedAt()
        );
    }

    private void insertJob(
            UUID jobId,
            UUID importId,
            UUID createdBy,
            CatalogImportJobAction action,
            String result,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO catalog_import_jobs (
                    id, import_id, created_by, action, status, result_json, created_at, completed_at
                ) VALUES (
                    :id, :importId, :createdBy, :action, :status, :result, :createdAt, :completedAt
                )
                """)
                .param("id", jobId)
                .param("importId", importId)
                .param("createdBy", createdBy)
                .param("action", action.name())
                .param("status", CatalogImportJobStatus.SUCCEEDED.name())
                .param("result", result)
                .param("createdAt", now)
                .param("completedAt", now)
                .update();
    }

    private static String parentColumn(String table) {
        return PROGRAMS.equals(table) ? "direction_id" : "vendor_id";
    }

    private ImportProfile mapProfile(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ImportProfile(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("display_name"),
                UserRole.valueOf(resultSet.getString("role")),
                resultSet.getObject("team_id", UUID.class)
        );
    }

    private ImportCatalogEntry mapCatalogEntry(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ImportCatalogEntry(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("external_key"),
                resultSet.getString("name"),
                resultSet.getInt("version")
        );
    }

    private ImportChildEntry mapChildEntry(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ImportChildEntry(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("external_key"),
                resultSet.getString("name"),
                resultSet.getObject("parent_id", UUID.class),
                resultSet.getInt("version")
        );
    }

    private ImportOrganization mapOrganization(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ImportOrganization(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("external_key"),
                resultSet.getString("name"),
                resultSet.getString("type"),
                resultSet.getObject("team_id", UUID.class),
                resultSet.getObject("owner_manager_id", UUID.class),
                resultSet.getString("owner_display_name"),
                resultSet.getInt("version"),
                "ARCHIVED".equals(resultSet.getString("status"))
        );
    }

    private ImportContact mapContact(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ImportContact(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("external_key"),
                resultSet.getObject("organization_id", UUID.class),
                new ContactValues(
                        resultSet.getString("name"),
                        resultSet.getString("position"),
                        resultSet.getString("email"),
                        resultSet.getString("phone")
                ),
                resultSet.getInt("version"),
                "ACTIVE".equals(resultSet.getString("personal_data_status"))
        );
    }

    private ImportVendorContact mapVendorContact(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ImportVendorContact(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("external_key"),
                resultSet.getObject("vendor_id", UUID.class),
                new VendorContactValues(
                        resultSet.getString("name"),
                        resultSet.getString("phone"),
                        resultSet.getString("email"),
                        resultSet.getBoolean("prefers_email"),
                        resultSet.getBoolean("prefers_telegram"),
                        resultSet.getBoolean("archived")
                ),
                resultSet.getInt("version"),
                "ACTIVE".equals(resultSet.getString("personal_data_status"))
        );
    }

    private ImportVendorProduct mapVendorProduct(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ImportVendorProduct(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("external_key"),
                resultSet.getString("name"),
                resultSet.getObject("vendor_id", UUID.class),
                resultSet.getObject("vendor_contact_id", UUID.class),
                resultSet.getInt("version")
        );
    }

    private ImportAgreement mapAgreement(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ImportAgreement(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("external_key"),
                resultSet.getObject("interaction_id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getString("interaction_title"),
                resultSet.getObject("product_id", UUID.class),
                new AgreementValues(
                        resultSet.getString("contract_number"),
                        resultSet.getObject("license_signed", Boolean.class),
                        resultSet.getObject("license_expiry_year", Integer.class),
                        resultSet.getString("transfer_status")
                ),
                resultSet.getInt("version"),
                resultSet.getObject("archived_at") != null
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Catalog import JSON cannot be written", exception);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Catalog import JSON cannot be read", exception);
        }
    }

    private Map<String, String> readMap(String json) {
        try {
            return Map.copyOf(objectMapper.readValue(json, new TypeReference<Map<String, String>>() { }));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Catalog import errors cannot be read", exception);
        }
    }

    private record ImportHeader(
            UUID id,
            UUID createdBy,
            CatalogImportProfile profile,
            CatalogImportStatus status,
            int version,
            CatalogImportMapping mapping,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }
}

interface ImportKeyed {
    UUID id();

    String externalKey();

    int version();
}

record ImportProfile(UUID id, String displayName, UserRole role, UUID teamId) {
}

record ImportCatalogEntry(UUID id, String externalKey, String name, int version) implements ImportKeyed {
}

record ImportChildEntry(UUID id, String externalKey, String name, UUID parentId, int version) implements ImportKeyed {
}

record ImportOrganization(
        UUID id,
        String externalKey,
        String name,
        String type,
        UUID teamId,
        UUID ownerManagerId,
        String ownerDisplayName,
        int version,
        boolean archived
) implements ImportKeyed {
}

record ContactValues(String name, String position, String email, String phone) {
}

record ImportContact(
        UUID id,
        String externalKey,
        UUID organizationId,
        ContactValues values,
        int version,
        boolean personalDataActive
) implements ImportKeyed {
}

record AgreementValues(String contractNumber, Boolean licenseSigned, Integer licenseExpiryYear, String transferStatus) {
}

record ImportVendorContact(
        UUID id,
        String externalKey,
        UUID vendorId,
        VendorContactValues values,
        int version,
        boolean personalDataActive
) implements ImportKeyed {
}

record ImportVendorProduct(UUID id, String externalKey, String name, UUID vendorId, UUID contactId, int version)
        implements ImportKeyed {
}

record ImportAgreement(
        UUID id,
        String externalKey,
        UUID interactionId,
        UUID organizationId,
        String interactionTitle,
        UUID productId,
        AgreementValues values,
        int version,
        boolean archived
) implements ImportKeyed {
}

record ImportAgreementLink(
        UUID agreementId,
        UUID organizationId,
        String organizationName,
        UUID productId,
        String vendorName,
        String productName,
        String contractNumber,
        String interactionTitle
) {
}

record ImportInteraction(
        UUID id,
        UUID organizationId,
        String title,
        UUID currentStageId,
        String currentStageName,
        UUID ownerManagerId,
        int version
) {
}
