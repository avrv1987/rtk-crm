package ru.rtk.crm.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class AgreementLifecycleMigrationTest {
    @Test
    void existingAgreementsKeepStatusFileAndWorkAndGetTheFirstHistoryEntry() throws IOException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:v41-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String table : new String[] {"programs", "products", "directions", "contacts", "crm_user_profiles"}) {
            jdbc.execute("CREATE TABLE " + table + " (id UUID PRIMARY KEY)");
        }
        jdbc.execute("CREATE TABLE interactions (id UUID PRIMARY KEY, title VARCHAR(200) NOT NULL)");
        jdbc.execute("""
                CREATE TABLE attachments (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, original_name VARCHAR(255) NOT NULL, media_type VARCHAR(160) NOT NULL,
                    size_bytes BIGINT NOT NULL, checksum CHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE agreements (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, number VARCHAR(100) NOT NULL, status VARCHAR(16) NOT NULL,
                    file_attachment_id UUID, created_by UUID NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    CONSTRAINT agreements_status_check CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED', 'TERMINATED'))
                )
                """);
        UUID author = UUID.randomUUID();
        UUID work = UUID.randomUUID();
        UUID scan = UUID.randomUUID();
        UUID signed = UUID.randomUUID();
        UUID draft = UUID.randomUUID();
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-05-01T10:00:00+03:00");
        jdbc.update("INSERT INTO crm_user_profiles (id) VALUES (?)", author);
        jdbc.update("INSERT INTO interactions (id, title) VALUES (?, 'Подписание договора')", work);
        jdbc.update("""
                INSERT INTO attachments (id, interaction_id, original_name, media_type, size_bytes, checksum, status, created_by, created_at)
                VALUES (?, ?, 'скан.pdf', 'application/pdf', 10, ?, 'CLEAN', ?, ?)
                """, scan, work, "a".repeat(64), author, updatedAt);
        jdbc.update("INSERT INTO agreements (id, organization_id, number, status, file_attachment_id, created_by, updated_at)"
                + " VALUES (?, ?, '1', 'ACTIVE', ?, ?, ?), (?, ?, '2', 'DRAFT', NULL, ?, ?)",
                signed, UUID.randomUUID(), scan, author, updatedAt, draft, UUID.randomUUID(), author, updatedAt);

        for (String statement : migration().split(";")) {
            if (!statement.isBlank() && !statement.contains("COALESCE(supplement_id, agreement_id)")) {
                jdbc.execute(statement.replace("gen_random_uuid()", "RANDOM_UUID()"));
            }
        }

        assertThat(jdbc.queryForList("SELECT agreement_id, from_status, to_status, actor_profile_id FROM agreement_status_events"
                + " ORDER BY to_status"))
                .extracting(row -> row.get("AGREEMENT_ID"), row -> row.get("FROM_STATUS"), row -> row.get("TO_STATUS"),
                        row -> row.get("ACTOR_PROFILE_ID"))
                .containsExactly(
                        tuple(signed, null, "ACTIVE", author),
                        tuple(draft, null, "DRAFT", author));
        assertThat(jdbc.queryForMap("SELECT agreement_id, version_number, attachment_id, storage_key, original_name, status, comment"
                + " FROM agreement_files"))
                .containsEntry("AGREEMENT_ID", signed)
                .containsEntry("VERSION_NUMBER", 1)
                .containsEntry("ATTACHMENT_ID", scan)
                .containsEntry("STORAGE_KEY", null)
                .containsEntry("ORIGINAL_NAME", "скан.pdf")
                .containsEntry("STATUS", "CLEAN")
                .containsEntry("COMMENT", "Документ из работы «Подписание договора»");
        assertThat(jdbc.queryForMap("SELECT agreement_id, interaction_id FROM agreement_interactions"))
                .containsEntry("AGREEMENT_ID", signed).containsEntry("INTERACTION_ID", work);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agreements WHERE status_changed_at = updated_at", Integer.class))
                .isEqualTo(2);
        jdbc.update("UPDATE agreements SET status = 'REVIEW' WHERE id = ?", draft);
        assertThatThrownBy(() -> jdbc.update("UPDATE agreements SET status = 'LOST' WHERE id = ?", draft))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.queryForList("SELECT file_attachment_id FROM agreements"))
                .isInstanceOf(org.springframework.jdbc.BadSqlGrammarException.class);
    }

    private static String migration() throws IOException {
        try (InputStream input = AgreementLifecycleMigrationTest.class.getResourceAsStream(
                "/db/migration/V41__university_agreement_lifecycle.sql")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
