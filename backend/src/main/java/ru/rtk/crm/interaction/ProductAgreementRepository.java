package ru.rtk.crm.interaction;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProductAgreementRepository {
    private final JdbcClient jdbcClient;

    public ProductAgreementRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void updateContract(UUID agreementId, ProductAgreementContract contract, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE product_agreements
                SET contract_number = :contractNumber, license_signed = :licenseSigned,
                    license_expiry_year = :licenseExpiryYear, scan_attachment_id = :scanAttachmentId,
                    version = version + 1, updated_at = :updatedAt
                WHERE id = :agreementId
                """)
                .param("agreementId", agreementId)
                .param("contractNumber", contract.contractNumber())
                .param("licenseSigned", contract.licenseSigned())
                .param("licenseExpiryYear", contract.licenseExpiryYear())
                .param("scanAttachmentId", contract.scanAttachmentId())
                .param("updatedAt", now)
                .update();
    }

    public void replaceTransfers(
            UUID agreementId,
            List<ProductTransfer> transfers,
            String transferStatus,
            UUID actorProfileId,
            OffsetDateTime now
    ) {
        jdbcClient.sql("DELETE FROM product_transfers WHERE agreement_id = :agreementId")
                .param("agreementId", agreementId)
                .update();
        for (ProductTransfer transfer : transfers) {
            jdbcClient.sql("""
                    INSERT INTO product_transfers (
                        agreement_id, kind, status, transferred_on, attachment_id, updated_by, updated_at
                    ) VALUES (
                        :agreementId, :kind, :status, :transferredOn, :attachmentId, :updatedBy, :updatedAt
                    )
                    """)
                    .param("agreementId", agreementId)
                    .param("kind", transfer.kind().name())
                    .param("status", transfer.status().name())
                    .param("transferredOn", transfer.transferredOn())
                    .param("attachmentId", transfer.attachmentId())
                    .param("updatedBy", actorProfileId)
                    .param("updatedAt", now)
                    .update();
        }
        jdbcClient.sql("""
                UPDATE product_agreements
                SET transfer_status = :transferStatus, version = version + 1, updated_at = :updatedAt
                WHERE id = :agreementId
                """)
                .param("agreementId", agreementId)
                .param("transferStatus", transferStatus)
                .param("updatedAt", now)
                .update();
    }
}
