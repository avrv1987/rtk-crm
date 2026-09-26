package ru.rtk.crm.report;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;

@Repository
public class ReportJobRepository {
    private static final String COLUMNS = """
            id, owner_profile_id, owner_role, owner_team_id, owner_access_revision, request_fingerprint, request_json,
            kind, format, group_by, status, progress, row_count, error_code, error_message, result_storage_key, result_file_name,
            result_size_bytes, created_at, started_at, finished_at
            """;

    private final JdbcClient jdbcClient;

    public ReportJobRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(
            UUID jobId,
            CrmProfile owner,
            String idempotencyKey,
            String requestFingerprint,
            String requestJson,
            ReportRequest request,
            OffsetDateTime createdAt
    ) {
        jdbcClient.sql("""
                INSERT INTO report_jobs (
                    id, owner_profile_id, owner_role, owner_team_id, owner_access_revision, idempotency_key,
                    request_fingerprint, request_json, kind, format, group_by, status, progress, created_at
                ) VALUES (
                    :id, :ownerProfileId, :ownerRole, :ownerTeamId, :ownerAccessRevision, :idempotencyKey,
                    :requestFingerprint, :requestJson, :kind, :format, :groupBy, 'PENDING', 0, :createdAt
                )
                """)
                .param("id", jobId)
                .param("ownerProfileId", owner.id())
                .param("ownerRole", owner.role().name())
                .param("ownerTeamId", owner.teamId())
                .param("ownerAccessRevision", owner.accessRevision())
                .param("idempotencyKey", idempotencyKey)
                .param("requestFingerprint", requestFingerprint)
                .param("requestJson", requestJson)
                .param("kind", request.kind().name())
                .param("format", request.format().name())
                .param("groupBy", request.groupBy() == null ? null : request.groupBy().name())
                .param("createdAt", createdAt)
                .update();
    }

    public Optional<StoredJob> findById(UUID jobId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM report_jobs WHERE id = :jobId")
                .param("jobId", jobId)
                .query(this::mapJob)
                .optional();
    }

    public Optional<StoredJob> findOwned(UUID jobId, UUID ownerProfileId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM report_jobs WHERE id = :jobId AND owner_profile_id = :ownerProfileId")
                .param("jobId", jobId)
                .param("ownerProfileId", ownerProfileId)
                .query(this::mapJob)
                .optional();
    }

    public Optional<StoredJob> findByIdempotencyKey(UUID ownerProfileId, String idempotencyKey) {
        return jdbcClient.sql("""
                SELECT %s
                FROM report_jobs
                WHERE owner_profile_id = :ownerProfileId AND idempotency_key = :idempotencyKey
                """.formatted(COLUMNS))
                .param("ownerProfileId", ownerProfileId)
                .param("idempotencyKey", idempotencyKey)
                .query(this::mapJob)
                .optional();
    }

    public List<StoredJob> findRecentOwned(UUID ownerProfileId, int limit) {
        return jdbcClient.sql("""
                SELECT %s
                FROM report_jobs
                WHERE owner_profile_id = :ownerProfileId
                ORDER BY created_at DESC, id
                LIMIT :limit
                """.formatted(COLUMNS))
                .param("ownerProfileId", ownerProfileId)
                .param("limit", limit)
                .query(this::mapJob)
                .list();
    }

    public Optional<CrmProfile> findActiveProfile(UUID profileId) {
        return jdbcClient.sql("""
                SELECT id, role, team_id, access_revision
                FROM crm_user_profiles
                WHERE id = :profileId AND active = TRUE
                """)
                .param("profileId", profileId)
                .query(CrmProfile.class)
                .optional();
    }

    public boolean claim(UUID jobId, OffsetDateTime startedAt) {
        return jdbcClient.sql("""
                UPDATE report_jobs
                SET status = 'RUNNING', progress = 10, started_at = :startedAt
                WHERE id = :jobId AND status = 'PENDING'
                """)
                .param("jobId", jobId)
                .param("startedAt", startedAt)
                .update() == 1;
    }

    public void markRowsLoaded(UUID jobId, int rowCount) {
        jdbcClient.sql("""
                UPDATE report_jobs
                SET progress = 50, row_count = :rowCount
                WHERE id = :jobId AND status = 'RUNNING'
                """)
                .param("jobId", jobId)
                .param("rowCount", rowCount)
                .update();
    }

    public boolean succeed(UUID jobId, UUID storageKey, String fileName, long sizeBytes, OffsetDateTime finishedAt) {
        return jdbcClient.sql("""
                UPDATE report_jobs
                SET status = 'SUCCEEDED', progress = 100, result_storage_key = :storageKey,
                    result_file_name = :fileName, result_size_bytes = :sizeBytes, finished_at = :finishedAt
                WHERE id = :jobId AND status = 'RUNNING'
                """)
                .param("jobId", jobId)
                .param("storageKey", storageKey)
                .param("fileName", fileName)
                .param("sizeBytes", sizeBytes)
                .param("finishedAt", finishedAt)
                .update() == 1;
    }

    public void fail(UUID jobId, String errorCode, String errorMessage, OffsetDateTime finishedAt) {
        jdbcClient.sql("""
                UPDATE report_jobs
                SET status = 'FAILED', error_code = :errorCode, error_message = :errorMessage, finished_at = :finishedAt
                WHERE id = :jobId AND status IN ('PENDING', 'RUNNING')
                """)
                .param("jobId", jobId)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .param("finishedAt", finishedAt)
                .update();
    }

    public int failUnfinishedCreatedBefore(OffsetDateTime createdBefore, String errorCode, String errorMessage) {
        return jdbcClient.sql("""
                UPDATE report_jobs
                SET status = 'FAILED', error_code = :errorCode, error_message = :errorMessage, finished_at = :finishedAt
                WHERE status IN ('PENDING', 'RUNNING') AND created_at < :createdBefore
                """)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .param("finishedAt", OffsetDateTime.now())
                .param("createdBefore", createdBefore)
                .update();
    }

    public void delete(UUID jobId) {
        jdbcClient.sql("DELETE FROM report_jobs WHERE id = :jobId AND status = 'PENDING'")
                .param("jobId", jobId)
                .update();
    }

    private StoredJob mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredJob(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("owner_profile_id", UUID.class),
                UserRole.valueOf(resultSet.getString("owner_role")),
                resultSet.getObject("owner_team_id", UUID.class),
                resultSet.getInt("owner_access_revision"),
                resultSet.getString("request_fingerprint"),
                resultSet.getString("request_json"),
                ReportKind.valueOf(resultSet.getString("kind")),
                ReportFormat.valueOf(resultSet.getString("format")),
                resultSet.getString("group_by") == null ? null : StatisticsGroupBy.valueOf(resultSet.getString("group_by")),
                ReportJobStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("progress"),
                resultSet.getObject("row_count", Integer.class),
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                resultSet.getObject("result_storage_key", UUID.class),
                resultSet.getString("result_file_name"),
                resultSet.getObject("result_size_bytes", Long.class),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("started_at", OffsetDateTime.class),
                resultSet.getObject("finished_at", OffsetDateTime.class)
        );
    }

    public record StoredJob(
            UUID id,
            UUID ownerProfileId,
            UserRole ownerRole,
            UUID ownerTeamId,
            int ownerAccessRevision,
            String requestFingerprint,
            String requestJson,
            ReportKind kind,
            ReportFormat format,
            StatisticsGroupBy groupBy,
            ReportJobStatus status,
            int progress,
            Integer rowCount,
            String errorCode,
            String errorMessage,
            UUID resultStorageKey,
            String resultFileName,
            Long resultSizeBytes,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt
    ) {
        public boolean grantedTo(CrmProfile profile) {
            return profile.id().equals(ownerProfileId)
                    && profile.role() == ownerRole
                    && Objects.equals(profile.teamId(), ownerTeamId)
                    && profile.accessRevision() == ownerAccessRevision;
        }

        public ReportJob view() {
            return new ReportJob(
                    id,
                    kind,
                    format,
                    groupBy,
                    status,
                    progress,
                    status == ReportJobStatus.SUCCEEDED && resultStorageKey != null,
                    rowCount,
                    resultFileName,
                    errorCode == null ? null : new ReportJob.Error(errorCode, errorMessage),
                    createdAt,
                    startedAt,
                    finishedAt
            );
        }
    }
}
