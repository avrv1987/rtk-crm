package ru.rtk.crm.enrolment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.PersonalDataStatus;

@Repository
public class EnrolmentRepository {
    private static final String STREAM_SELECT = """
            SELECT id, course_key, course_name, stream_no, ends_on, version
            FROM enrolment_streams
            """;
    private static final String ENROLMENT_SELECT = """
            SELECT e.id, e.learner_id, e.stream_id, s.course_name, s.stream_no, s.ends_on, e.source_record_id,
                   r.external_id AS order_number, e.lms_export_id, e.lms_exported_at, e.lms_transferred_at
            FROM learner_enrolments e
            JOIN enrolment_streams s ON s.id = e.stream_id
            LEFT JOIN source_records r ON r.id = e.source_record_id
            """;
    private static final String SUMMARY_SELECT = """
            SELECT s.id, s.course_name, s.stream_no, s.ends_on, s.version,
                   COALESCE(
                       (SELECT p.name FROM source_mappings m JOIN programs p ON p.id = m.program_id
                        WHERE m.source = 'WEBSITE' AND m.kind = 'PROGRAM' AND m.external_key = s.course_key),
                       (SELECT MIN(p.name) FROM learner_enrolments e
                        JOIN source_records r ON r.id = e.source_record_id
                        JOIN programs p ON p.id = r.program_id
                        WHERE e.stream_id = s.id)
                   ) AS program_name
            FROM enrolment_streams s
            """;
    private static final String STREAM_ROW_SELECT = """
            SELECT e.id, e.learner_id, e.stream_id, r.external_id AS order_number, e.lms_export_id, e.lms_exported_at,
                   e.lms_transferred_at, l.personal_data_status, l.missing_fields, l.email_hmac, l.phone_hmac, l.version
            FROM learner_enrolments e
            JOIN learners l ON l.id = e.learner_id
            LEFT JOIN source_records r ON r.id = e.source_record_id
            """;

    private final JdbcClient jdbcClient;

    EnrolmentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    EnrolmentStream findOrCreateStream(String courseKey, String courseName, int streamNo, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO enrolment_streams (id, course_key, course_name, stream_no, created_at, updated_at)
                VALUES (:id, :courseKey, :courseName, :streamNo, :now, :now)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("courseKey", courseKey)
                .param("courseName", courseName)
                .param("streamNo", streamNo)
                .param("now", now)
                .update();
        return jdbcClient.sql(STREAM_SELECT + " WHERE course_key = :courseKey AND stream_no = :streamNo")
                .param("courseKey", courseKey)
                .param("streamNo", streamNo)
                .query(this::mapStream)
                .single();
    }

    Optional<StreamSummary> findSummary(UUID streamId) {
        return jdbcClient.sql(SUMMARY_SELECT + " WHERE s.id = :streamId")
                .param("streamId", streamId)
                .query(this::mapSummary)
                .optional();
    }

    List<StreamSummary> findSummaries() {
        return jdbcClient.sql(SUMMARY_SELECT + " ORDER BY s.course_name, s.stream_no, s.id")
                .query(this::mapSummary)
                .list();
    }

    boolean updateEndDate(UUID streamId, int expectedVersion, LocalDate endsOn, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE enrolment_streams
                SET ends_on = :endsOn, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """)
                .param("id", streamId)
                .param("expectedVersion", expectedVersion)
                .param("endsOn", endsOn)
                .param("now", now)
                .update() == 1;
    }

    UUID createEnrolment(UUID learnerId, UUID streamId, UUID sourceRecordId, OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO learner_enrolments (id, learner_id, stream_id, source_record_id, created_at, updated_at)
                VALUES (:id, :learnerId, :streamId, :sourceRecordId, :now, :now)
                """)
                .param("id", id)
                .param("learnerId", learnerId)
                .param("streamId", streamId)
                .param("sourceRecordId", sourceRecordId)
                .param("now", now)
                .update();
        return id;
    }

    Optional<LearnerEnrolment> findBySourceRecord(UUID sourceRecordId) {
        return jdbcClient.sql(ENROLMENT_SELECT + " WHERE e.source_record_id = :sourceRecordId")
                .param("sourceRecordId", sourceRecordId)
                .query(this::mapEnrolment)
                .optional();
    }

    boolean enrolled(UUID learnerId, UUID streamId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM learner_enrolments WHERE learner_id = :learnerId AND stream_id = :streamId")
                .param("learnerId", learnerId)
                .param("streamId", streamId)
                .query(Long.class)
                .single() > 0;
    }

    void moveToStream(UUID enrolmentId, UUID streamId, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE learner_enrolments
                SET stream_id = :streamId, lms_export_id = NULL, lms_exported_at = NULL, lms_transferred_at = NULL, updated_at = :now
                WHERE id = :id
                """)
                .param("id", enrolmentId)
                .param("streamId", streamId)
                .param("now", now)
                .update();
    }

    void reassign(UUID enrolmentId, UUID learnerId, OffsetDateTime now) {
        jdbcClient.sql("UPDATE learner_enrolments SET learner_id = :learnerId, updated_at = :now WHERE id = :id")
                .param("id", enrolmentId)
                .param("learnerId", learnerId)
                .param("now", now)
                .update();
    }

    void delete(UUID enrolmentId) {
        jdbcClient.sql("DELETE FROM learner_enrolments WHERE id = :id")
                .param("id", enrolmentId)
                .update();
    }

    List<LearnerEnrolment> findByLearners(Collection<UUID> learnerIds) {
        if (learnerIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql(ENROLMENT_SELECT + " WHERE e.learner_id IN (:learnerIds) ORDER BY s.course_name, s.stream_no, e.id")
                .param("learnerIds", learnerIds)
                .query(this::mapEnrolment)
                .list();
    }

    List<StreamEnrolmentRow> findStreamRows(UUID streamId) {
        return jdbcClient.sql(STREAM_ROW_SELECT + " WHERE e.stream_id = :streamId ORDER BY e.id")
                .param("streamId", streamId)
                .query(this::mapStreamRow)
                .list();
    }

    List<StreamEnrolmentRow> findAllStreamRows() {
        return jdbcClient.sql(STREAM_ROW_SELECT + " ORDER BY e.id")
                .query(this::mapStreamRow)
                .list();
    }

    void markExported(Collection<UUID> enrolmentIds, UUID exportId, OffsetDateTime now) {
        if (enrolmentIds.isEmpty()) {
            return;
        }
        jdbcClient.sql("""
                UPDATE learner_enrolments
                SET lms_export_id = :exportId, lms_exported_at = :now, updated_at = :now
                WHERE id IN (:ids) AND lms_transferred_at IS NULL
                """)
                .param("ids", enrolmentIds)
                .param("exportId", exportId)
                .param("now", now)
                .update();
    }

    int markTransferred(UUID exportId, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE learner_enrolments
                SET lms_transferred_at = :now, updated_at = :now
                WHERE lms_export_id = :exportId AND lms_transferred_at IS NULL
                """)
                .param("exportId", exportId)
                .param("now", now)
                .update();
    }

    Optional<UUID> findExportStream(UUID exportId) {
        return jdbcClient.sql("SELECT stream_id FROM learner_enrolments WHERE lms_export_id = :exportId ORDER BY id LIMIT 1")
                .param("exportId", exportId)
                .query(UUID.class)
                .optional();
    }

    int countExport(UUID exportId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM learner_enrolments WHERE lms_export_id = :exportId")
                .param("exportId", exportId)
                .query(Integer.class)
                .single();
    }

    Map<UUID, LocalDate> findStreamEndDates() {
        return jdbcClient.sql(STREAM_SELECT + " WHERE ends_on IS NOT NULL")
                .query(this::mapStream)
                .list()
                .stream()
                .collect(Collectors.toMap(EnrolmentStream::id, EnrolmentStream::endsOn));
    }

    long countStreamsWithoutEndDate() {
        return jdbcClient.sql("SELECT COUNT(*) FROM enrolment_streams WHERE ends_on IS NULL")
                .query(Long.class)
                .single();
    }

    private EnrolmentStream mapStream(ResultSet resultSet, int rowNumber) throws SQLException {
        return new EnrolmentStream(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("course_key"),
                resultSet.getString("course_name"),
                resultSet.getInt("stream_no"),
                resultSet.getObject("ends_on", LocalDate.class),
                resultSet.getInt("version")
        );
    }

    private StreamSummary mapSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StreamSummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("course_name"),
                resultSet.getInt("stream_no"),
                resultSet.getString("program_name"),
                resultSet.getObject("ends_on", LocalDate.class),
                resultSet.getInt("version")
        );
    }

    private LearnerEnrolment mapEnrolment(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LearnerEnrolment(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("learner_id", UUID.class),
                resultSet.getObject("stream_id", UUID.class),
                resultSet.getString("course_name"),
                resultSet.getInt("stream_no"),
                resultSet.getObject("ends_on", LocalDate.class),
                resultSet.getObject("source_record_id", UUID.class),
                resultSet.getString("order_number"),
                resultSet.getObject("lms_export_id", UUID.class),
                resultSet.getObject("lms_exported_at", OffsetDateTime.class),
                resultSet.getObject("lms_transferred_at", OffsetDateTime.class)
        );
    }

    private StreamEnrolmentRow mapStreamRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StreamEnrolmentRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("learner_id", UUID.class),
                resultSet.getObject("stream_id", UUID.class),
                resultSet.getString("order_number"),
                resultSet.getObject("lms_export_id", UUID.class),
                resultSet.getObject("lms_exported_at", OffsetDateTime.class),
                resultSet.getObject("lms_transferred_at", OffsetDateTime.class),
                PersonalDataStatus.valueOf(resultSet.getString("personal_data_status")),
                LearnerRepository.missingFields(resultSet.getString("missing_fields")),
                resultSet.getString("email_hmac"),
                resultSet.getString("phone_hmac"),
                resultSet.getInt("version")
        );
    }
}
