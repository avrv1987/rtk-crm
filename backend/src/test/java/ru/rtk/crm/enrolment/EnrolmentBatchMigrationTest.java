package ru.rtk.crm.enrolment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

// Прежние отметки lms_export_id и lms_transferred_at переносятся в партии: история выгрузок не теряется,
// ранее отмеченные строки считаются принятыми с источником LEGACY.
class EnrolmentBatchMigrationTest {
    @Test
    void existingExportsBecomeLegacyBatchesAndTransferredRowsStayTransferred() throws IOException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:v49-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE crm_user_profiles (id UUID PRIMARY KEY)");
        jdbc.execute("CREATE TABLE enrolment_streams (id UUID PRIMARY KEY)");
        jdbc.execute("CREATE TABLE learners (id UUID PRIMARY KEY, version INTEGER NOT NULL)");
        jdbc.execute("""
                CREATE TABLE learner_enrolments (
                    id UUID PRIMARY KEY, learner_id UUID NOT NULL, stream_id UUID NOT NULL, lms_export_id UUID,
                    lms_exported_at TIMESTAMP WITH TIME ZONE, lms_transferred_at TIMESTAMP WITH TIME ZONE
                )
                """);
        UUID stream = UUID.randomUUID();
        UUID export = UUID.randomUUID();
        UUID[] learners = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        UUID transferred = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID exported = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID fresh = UUID.fromString("00000000-0000-0000-0000-000000000003");
        OffsetDateTime exportedAt = OffsetDateTime.parse("2026-10-01T10:00:00+03:00");
        OffsetDateTime transferredAt = OffsetDateTime.parse("2026-10-02T10:00:00+03:00");
        jdbc.update("INSERT INTO enrolment_streams (id) VALUES (?)", stream);
        for (int index = 0; index < learners.length; index++) {
            jdbc.update("INSERT INTO learners (id, version) VALUES (?, ?)", learners[index], index + 3);
        }
        jdbc.update("""
                INSERT INTO learner_enrolments (id, learner_id, stream_id, lms_export_id, lms_exported_at, lms_transferred_at)
                VALUES (?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, NULL), (?, ?, ?, NULL, NULL, NULL)
                """, transferred, learners[0], stream, export, exportedAt, transferredAt,
                exported, learners[1], stream, export, exportedAt,
                fresh, learners[2], stream);

        for (String statement : migration().split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement.replace("gen_random_uuid()", "RANDOM_UUID()"));
            }
        }

        assertThat(jdbc.queryForMap("SELECT stream_id, format, rows_count, legacy, sent_at FROM enrolment_batches WHERE id = ?", export))
                .containsEntry("STREAM_ID", stream)
                .containsEntry("FORMAT", "XLSX_TEMPLATE")
                .containsEntry("ROWS_COUNT", 2)
                .containsEntry("LEGACY", true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enrolment_batches", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT enrolment_id, row_no, result, result_source FROM enrolment_batch_items ORDER BY row_no"))
                .extracting(row -> row.get("ENROLMENT_ID"), row -> row.get("ROW_NO"), row -> row.get("RESULT"), row -> row.get("RESULT_SOURCE"))
                .containsExactly(tuple(transferred, 2, "ACCEPTED", "LEGACY"), tuple(exported, 3, "PENDING", null));
        assertThat(jdbc.queryForList("SELECT id, lms_synced_version FROM learner_enrolments ORDER BY id"))
                .extracting(row -> row.get("ID"), row -> row.get("LMS_SYNCED_VERSION"))
                .containsExactly(tuple(transferred, 3), tuple(exported, null), tuple(fresh, null));
    }

    private static String migration() throws IOException {
        try (InputStream input = EnrolmentBatchMigrationTest.class.getResourceAsStream("/db/migration/V49__enrolment_batches.sql")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
