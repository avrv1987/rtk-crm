package ru.rtk.crm.source;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SourceSettingsRepository {
    private static final String COLUMNS = """
            moodle_base_url, moodle_token_encrypted, moodle_token_changed_at, moodle_course_ids, moodle_student_roles,
            moodle_teacher_roles, website_base_url, website_token_encrypted, website_token_changed_at, sync_cron,
            updated_by, updated_at""";

    private final JdbcClient jdbcClient;

    public SourceSettingsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    Optional<StoredSourceSettings> find() {
        return jdbcClient.sql("""
                        SELECT s.moodle_base_url, s.moodle_token_encrypted, s.moodle_token_changed_at, s.moodle_course_ids,
                               s.moodle_student_roles, s.moodle_teacher_roles, s.website_base_url, s.website_token_encrypted,
                               s.website_token_changed_at, s.sync_cron, s.updated_at, p.display_name AS updated_by_name
                        FROM source_settings s
                        LEFT JOIN crm_user_profiles p ON p.id = s.updated_by
                        WHERE s.id = 1
                        """)
                .query((rs, rowNum) -> new StoredSourceSettings(
                        rs.getString("moodle_base_url"),
                        rs.getString("moodle_token_encrypted"),
                        rs.getObject("moodle_token_changed_at", OffsetDateTime.class),
                        split(rs.getString("moodle_course_ids")).stream().map(Long::valueOf).toList(),
                        split(rs.getString("moodle_student_roles")),
                        split(rs.getString("moodle_teacher_roles")),
                        rs.getString("website_base_url"),
                        rs.getString("website_token_encrypted"),
                        rs.getObject("website_token_changed_at", OffsetDateTime.class),
                        rs.getString("sync_cron"),
                        rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getString("updated_by_name")
                ))
                .optional();
    }

    @Transactional
    void save(StoredSourceSettings settings, UUID actorProfileId) {
        jdbcClient.sql("DELETE FROM source_settings WHERE id = 1").update();
        jdbcClient.sql("INSERT INTO source_settings (id, " + COLUMNS + """
                        ) VALUES (1, :moodleBaseUrl, :moodleToken, :moodleTokenChangedAt, :moodleCourseIds, :moodleStudentRoles,
                                  :moodleTeacherRoles, :websiteBaseUrl, :websiteToken, :websiteTokenChangedAt, :syncCron,
                                  :updatedBy, :updatedAt)
                        """)
                .param("moodleBaseUrl", settings.moodleBaseUrl())
                .param("moodleToken", settings.moodleTokenEncrypted())
                .param("moodleTokenChangedAt", settings.moodleTokenChangedAt())
                .param("moodleCourseIds", join(settings.moodleCourseIds()))
                .param("moodleStudentRoles", join(settings.moodleStudentRoles()))
                .param("moodleTeacherRoles", join(settings.moodleTeacherRoles()))
                .param("websiteBaseUrl", settings.websiteBaseUrl())
                .param("websiteToken", settings.websiteTokenEncrypted())
                .param("websiteTokenChangedAt", settings.websiteTokenChangedAt())
                .param("syncCron", settings.syncCron())
                .param("updatedBy", actorProfileId)
                .param("updatedAt", settings.updatedAt())
                .update();
    }

    void delete() {
        jdbcClient.sql("DELETE FROM source_settings WHERE id = 1").update();
    }

    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",")).map(String::strip).toList();
    }

    private static String join(List<?> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    record StoredSourceSettings(
            String moodleBaseUrl,
            String moodleTokenEncrypted,
            OffsetDateTime moodleTokenChangedAt,
            List<Long> moodleCourseIds,
            List<String> moodleStudentRoles,
            List<String> moodleTeacherRoles,
            String websiteBaseUrl,
            String websiteTokenEncrypted,
            OffsetDateTime websiteTokenChangedAt,
            String syncCron,
            OffsetDateTime updatedAt,
            String updatedByName
    ) {
    }
}
