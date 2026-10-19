package ru.rtk.crm.enrolment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EnrolmentBatchRepository {
    private static final String BATCH_SELECT = """
            SELECT b.id, b.stream_id, b.format, b.file_name, b.checksum, b.legacy, b.created_at, author.display_name AS created_by_name,
                   b.sent_at, b.rows_count,
                   (SELECT COUNT(*) FROM enrolment_batch_items i WHERE i.batch_id = b.id AND i.result = 'ACCEPTED') AS accepted,
                   (SELECT COUNT(*) FROM enrolment_batch_items i WHERE i.batch_id = b.id AND i.result = 'FAILED') AS failed
            FROM enrolment_batches b
            LEFT JOIN crm_user_profiles author ON author.id = b.created_by
            """;

    private final JdbcClient jdbcClient;

    EnrolmentBatchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    void insertBatch(UUID id, UUID streamId, RosterFormat format, RosterMode mode, String fileName, String checksum, int rows,
                     UUID createdBy, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO enrolment_batches (id, stream_id, format, mode, file_name, checksum, rows_count, legacy, created_by, created_at)
                VALUES (:id, :streamId, :format, :mode, :fileName, :checksum, :rows, FALSE, :createdBy, :now)
                """)
                .param("id", id)
                .param("streamId", streamId)
                .param("format", format.name())
                .param("mode", mode.name())
                .param("fileName", fileName)
                .param("checksum", checksum)
                .param("rows", rows)
                .param("createdBy", createdBy)
                .param("now", now)
                .update();
    }

    void insertItem(UUID batchId, int rowNo, UUID enrolmentId, UUID learnerId, int learnerVersion) {
        jdbcClient.sql("""
                INSERT INTO enrolment_batch_items (id, batch_id, row_no, enrolment_id, learner_id, learner_version, result)
                VALUES (:id, :batchId, :rowNo, :enrolmentId, :learnerId, :learnerVersion, 'PENDING')
                """)
                .param("id", UUID.randomUUID())
                .param("batchId", batchId)
                .param("rowNo", rowNo)
                .param("enrolmentId", enrolmentId)
                .param("learnerId", learnerId)
                .param("learnerVersion", learnerVersion)
                .update();
    }

    Optional<BatchRow> find(UUID batchId) {
        return jdbcClient.sql(BATCH_SELECT + " WHERE b.id = :id")
                .param("id", batchId)
                .query(this::mapBatch)
                .optional();
    }

    // Блокировка партии упорядочивает запись результатов: два менеджера учебного процесса, отмечающие одну партию, не теряют
    // обновления друг друга, а принятая строка не перезаписывается.
    Optional<UUID> lock(UUID batchId) {
        return jdbcClient.sql("SELECT id FROM enrolment_batches WHERE id = :id FOR UPDATE")
                .param("id", batchId)
                .query(UUID.class)
                .optional();
    }

    List<BatchRow> findByStream(UUID streamId) {
        return jdbcClient.sql(BATCH_SELECT + " WHERE b.stream_id = :streamId ORDER BY b.created_at DESC, b.id")
                .param("streamId", streamId)
                .query(this::mapBatch)
                .list();
    }

    List<ItemRow> findItems(UUID batchId) {
        return jdbcClient.sql("""
                SELECT i.id, i.row_no, i.enrolment_id, i.learner_id, i.learner_version, i.result, i.error, i.result_source, i.result_at,
                       r.external_id AS order_number, l.version AS current_version
                FROM enrolment_batch_items i
                LEFT JOIN learner_enrolments e ON e.id = i.enrolment_id
                LEFT JOIN source_records r ON r.id = e.source_record_id
                LEFT JOIN learners l ON l.id = i.learner_id
                WHERE i.batch_id = :batchId
                ORDER BY i.row_no
                """)
                .param("batchId", batchId)
                .query((resultSet, rowNumber) -> new ItemRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getInt("row_no"),
                        resultSet.getObject("enrolment_id", UUID.class),
                        resultSet.getObject("learner_id", UUID.class),
                        (Integer) resultSet.getObject("learner_version"),
                        BatchItemResult.valueOf(resultSet.getString("result")),
                        resultSet.getString("error"),
                        resultSet.getString("result_source"),
                        resultSet.getObject("result_at", OffsetDateTime.class),
                        resultSet.getString("order_number"),
                        (Integer) resultSet.getObject("current_version")
                ))
                .list();
    }

    int countPending(UUID batchId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM enrolment_batch_items WHERE batch_id = :batchId AND result = 'PENDING'")
                .param("batchId", batchId)
                .query(Integer.class)
                .single();
    }

    boolean markSent(UUID batchId, UUID actorId, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE enrolment_batches SET sent_at = :now, sent_by = :actorId WHERE id = :id AND sent_at IS NULL
                """)
                .param("id", batchId)
                .param("actorId", actorId)
                .param("now", now)
                .update() == 1;
    }

    // Принятая строка окончательна: повторная отметка или протокол её не меняют.
    boolean recordResult(UUID itemId, BatchItemResult result, String error, String source, UUID actorId, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE enrolment_batch_items
                SET result = :result, error = :error, result_source = :source, result_at = :now, result_by = :actorId
                WHERE id = :id AND result <> 'ACCEPTED'
                """)
                .param("id", itemId)
                .param("result", result.name())
                .param("error", error)
                .param("source", source)
                .param("actorId", actorId)
                .param("now", now)
                .update() == 1;
    }

    // Курс Moodle потока: сопоставления Moodle «курс» и «группа» программы потока у служебной организации «Открытый
    // набор». Программа потока — как на экране потока: сопоставление сайта WEBSITE/PROGRAM по ключу курса, а без него —
    // программа оплат потока. Оплаты применяются и по совпадению названия курса с программой, явного сопоставления
    // сайта тогда нет; если программ у оплат несколько, курсов тоже несколько и передача остановится, а не угадает.
    List<String[]> findMoodleMappings(UUID streamId) {
        return jdbcClient.sql("""
                SELECT m.kind, m.external_key
                FROM source_mappings m
                JOIN organizations o ON o.id = m.organization_id AND o.type = 'OPEN_ENROLLMENT'
                WHERE m.source = 'MOODLE' AND m.kind IN ('COURSE', 'GROUP') AND m.run_kind = 'STUDENTS'
                  AND m.program_id IN (
                      SELECT website.program_id
                      FROM enrolment_streams s
                      JOIN source_mappings website ON website.source = 'WEBSITE' AND website.kind = 'PROGRAM'
                        AND website.external_key = s.course_key
                      WHERE s.id = :streamId
                      UNION
                      SELECT r.program_id
                      FROM learner_enrolments e
                      JOIN source_records r ON r.id = e.source_record_id
                      WHERE e.stream_id = :streamId AND r.program_id IS NOT NULL
                        AND NOT EXISTS (
                            SELECT 1
                            FROM enrolment_streams s
                            JOIN source_mappings website ON website.source = 'WEBSITE' AND website.kind = 'PROGRAM'
                              AND website.external_key = s.course_key
                            WHERE s.id = :streamId))
                ORDER BY m.kind, m.external_key
                """)
                .param("streamId", streamId)
                .query((resultSet, rowNumber) -> new String[] {resultSet.getString("kind"), resultSet.getString("external_key")})
                .list();
    }

    Optional<UUID> findPreviousStream(UUID enrolmentId, UUID currentStreamId) {
        return jdbcClient.sql("""
                SELECT b.stream_id
                FROM enrolment_batch_items i
                JOIN enrolment_batches b ON b.id = i.batch_id
                WHERE i.enrolment_id = :enrolmentId AND b.stream_id <> :streamId
                ORDER BY b.created_at DESC, b.id
                LIMIT 1
                """)
                .param("enrolmentId", enrolmentId)
                .param("streamId", currentStreamId)
                .query(UUID.class)
                .optional();
    }

    private BatchRow mapBatch(ResultSet resultSet, int rowNumber) throws SQLException {
        int rows = resultSet.getInt("rows_count");
        int accepted = resultSet.getInt("accepted");
        int failed = resultSet.getInt("failed");
        return new BatchRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("stream_id", UUID.class),
                RosterFormat.valueOf(resultSet.getString("format")),
                resultSet.getString("file_name"),
                resultSet.getString("checksum"),
                resultSet.getBoolean("legacy"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getString("created_by_name"),
                resultSet.getObject("sent_at", OffsetDateTime.class),
                rows,
                accepted,
                failed,
                rows - accepted - failed
        );
    }

    record BatchRow(
            UUID id,
            UUID streamId,
            RosterFormat format,
            String fileName,
            String checksum,
            boolean legacy,
            OffsetDateTime createdAt,
            String createdByName,
            OffsetDateTime sentAt,
            int rows,
            int accepted,
            int failed,
            int pending
    ) {
    }

    record ItemRow(
            UUID id,
            int rowNo,
            UUID enrolmentId,
            UUID learnerId,
            Integer learnerVersion,
            BatchItemResult result,
            String error,
            String resultSource,
            OffsetDateTime resultAt,
            String orderNumber,
            Integer currentVersion
    ) {
    }
}
