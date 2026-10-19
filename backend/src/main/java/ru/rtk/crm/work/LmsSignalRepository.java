package ru.rtk.crm.work;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalType;

@Repository
public class LmsSignalRepository {
    // Потоки LMS для работы: снимок того же вуза и программы, только потоки обучающихся с датами, внутри окна цикла
    // карточки (как в SourceRepository) и только у активных работ. Этап занятий — самый ранний этап с ролью LMS «Идут занятия» в
    // маршруте работы; route показывает, есть ли прямой переход к нему из текущего этапа.
    private static final String SOURCES = """
            SELECT i.id AS interaction_id, i.title, i.organization_id, o.name AS organization_name, o.owner_manager_id,
                   owner_profile.display_name AS owner_manager_name, current_stage.name AS current_stage_name,
                   current_stage.stage_order AS current_stage_order, classes.id AS classes_stage_id,
                   classes.name AS classes_stage_name, classes.stage_order AS classes_stage_order,
                   route.to_stage_id AS route_to_stage_id, route.comment_required AS route_comment_required,
                   s.mapping_id, m.version AS mapping_version, s.course_name, s.group_name, m.run_starts_on, m.run_ends_on,
                   s.participants_count, s.completed_count, s.not_completed_count, s.observed_at, s.changed_at, s.teachers_count,
                   s.active_7d_count, s.active_30d_count, s.never_accessed_count, s.inactive_count,
                   s.teachers_active_7d_count, s.teachers_active_14d_count
            FROM interactions i
            JOIN organizations o ON o.id = i.organization_id
            LEFT JOIN crm_user_profiles owner_profile ON owner_profile.id = o.owner_manager_id
            JOIN interaction_stages current_stage ON current_stage.id = i.current_stage_id
            LEFT JOIN interaction_stages classes ON classes.interaction_id = i.id AND classes.lms_role = 'CLASSES'
                AND NOT EXISTS (
                    SELECT 1 FROM interaction_stages earlier
                    WHERE earlier.interaction_id = i.id AND earlier.lms_role = 'CLASSES'
                      AND earlier.stage_order < classes.stage_order
                )
            LEFT JOIN interaction_stage_transitions route ON route.interaction_id = i.id
                AND route.from_stage_id = i.current_stage_id AND route.to_stage_id = classes.id
            JOIN learning_snapshots s ON s.organization_id = i.organization_id AND s.program_id = i.program_id
            JOIN source_mappings m ON m.id = s.mapping_id AND m.run_kind = 'STUDENTS'
                AND m.run_starts_on IS NOT NULL AND m.run_ends_on IS NOT NULL
            LEFT JOIN interaction_cycles cycle_start ON cycle_start.interaction_id = i.id
            LEFT JOIN interaction_cycles cycle_next ON cycle_next.previous_interaction_id = i.id
            WHERE i.organization_id IN (SELECT id FROM organizations WHERE %s)
              AND i.work_status = 'ACTIVE'
              AND (cycle_start.starts_on IS NULL OR m.run_starts_on >= cycle_start.starts_on)
              AND (cycle_next.starts_on IS NULL OR m.run_starts_on < cycle_next.starts_on)
            """;

    private final JdbcClient jdbcClient;

    public LmsSignalRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    List<SignalSource> findSources(VisibilityScope scope, UUID interactionId) {
        String sql = SOURCES.formatted(scope.condition())
                + (interactionId == null ? "" : " AND i.id = :interactionId")
                + " ORDER BY o.name, i.title, i.id, s.course_name, s.group_name, m.run_starts_on, s.mapping_id";
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .params(scope.parameters());
        if (interactionId != null) {
            statement = statement.param("interactionId", interactionId);
        }
        return statement.query((resultSet, rowNumber) -> new SignalSource(
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getObject("owner_manager_id", UUID.class),
                        resultSet.getString("owner_manager_name"),
                        resultSet.getString("current_stage_name"),
                        resultSet.getInt("current_stage_order"),
                        resultSet.getObject("classes_stage_id", UUID.class),
                        resultSet.getString("classes_stage_name"),
                        resultSet.getObject("classes_stage_order", Integer.class),
                        resultSet.getObject("route_to_stage_id") != null,
                        resultSet.getBoolean("route_comment_required"),
                        resultSet.getObject("mapping_id", UUID.class),
                        resultSet.getInt("mapping_version"),
                        resultSet.getString("course_name"),
                        resultSet.getString("group_name"),
                        resultSet.getObject("run_starts_on", LocalDate.class),
                        resultSet.getObject("run_ends_on", LocalDate.class),
                        resultSet.getInt("participants_count"),
                        resultSet.getObject("completed_count", Integer.class),
                        resultSet.getObject("not_completed_count", Integer.class),
                        resultSet.getObject("observed_at", OffsetDateTime.class),
                        resultSet.getObject("changed_at", OffsetDateTime.class),
                        resultSet.getInt("teachers_count"),
                        resultSet.getObject("active_7d_count") == null ? null : new Activity(
                                resultSet.getInt("active_7d_count"),
                                resultSet.getInt("active_30d_count"),
                                resultSet.getInt("never_accessed_count"),
                                resultSet.getInt("inactive_count"),
                                resultSet.getInt("teachers_active_7d_count"),
                                resultSet.getInt("teachers_active_14d_count")
                        )
                ))
                .list();
    }

    boolean interactionVisible(UUID interactionId, VisibilityScope scope) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM interactions
                WHERE id = :interactionId AND organization_id IN (SELECT id FROM organizations WHERE %s)
                """.formatted(scope.condition()))
                .param("interactionId", interactionId)
                .params(scope.parameters())
                .query(Long.class)
                .single() > 0;
    }

    /**
     * Ключ скрытия → записан ли сигнал как риск.
     */
    Map<String, Boolean> findDismissals(VisibilityScope scope, UUID interactionId) {
        String sql = """
                SELECT d.interaction_id, d.mapping_id, d.signal_type, d.data_version, d.recorded_as_risk
                FROM lms_signal_dismissals d
                JOIN interactions i ON i.id = d.interaction_id
                WHERE i.organization_id IN (SELECT id FROM organizations WHERE %s)
                """.formatted(scope.condition()) + (interactionId == null ? "" : " AND i.id = :interactionId");
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql).params(scope.parameters());
        if (interactionId != null) {
            statement = statement.param("interactionId", interactionId);
        }
        Map<String, Boolean> dismissals = new HashMap<>();
        statement.query((RowCallbackHandler) resultSet -> dismissals.put(dismissalKey(
                resultSet.getObject("interaction_id", UUID.class),
                resultSet.getObject("mapping_id", UUID.class),
                LmsSignalType.valueOf(resultSet.getString("signal_type")),
                resultSet.getString("data_version")
        ), resultSet.getBoolean("recorded_as_risk")));
        return dismissals;
    }

    // Одно скрытие на работу, поток и вид сигнала: прежнее заменяется, поэтому после смены версии данных сигнал
    // скрывается заново, а повторное нажатие не плодит строки.
    void saveDismissal(UUID interactionId, UUID mappingId, LmsSignalType type, String dataVersion, UUID profileId,
                       OffsetDateTime now, boolean recordedAsRisk) {
        jdbcClient.sql("""
                DELETE FROM lms_signal_dismissals
                WHERE interaction_id = :interactionId AND mapping_id = :mappingId AND signal_type = :type
                """)
                .param("interactionId", interactionId)
                .param("mappingId", mappingId)
                .param("type", type.name())
                .update();
        jdbcClient.sql("""
                INSERT INTO lms_signal_dismissals (
                    interaction_id, mapping_id, signal_type, data_version, dismissed_by, dismissed_at, recorded_as_risk
                ) VALUES (:interactionId, :mappingId, :type, :dataVersion, :profileId, :now, :recordedAsRisk)
                """)
                .param("interactionId", interactionId)
                .param("mappingId", mappingId)
                .param("type", type.name())
                .param("dataVersion", dataVersion)
                .param("profileId", profileId)
                .param("now", now)
                .param("recordedAsRisk", recordedAsRisk)
                .update();
    }

    static String dismissalKey(UUID interactionId, UUID mappingId, LmsSignalType type, String dataVersion) {
        return interactionId + "|" + mappingId + "|" + type + "|" + dataVersion;
    }

    record SignalSource(
            UUID interactionId,
            String interactionTitle,
            UUID organizationId,
            String organizationName,
            UUID ownerManagerId,
            String ownerManagerName,
            String currentStageName,
            int currentStageOrder,
            UUID classesStageId,
            String classesStageName,
            Integer classesStageOrder,
            boolean classesReachable,
            boolean classesCommentRequired,
            UUID mappingId,
            int mappingVersion,
            String courseName,
            String groupName,
            LocalDate runStartsOn,
            LocalDate runEndsOn,
            int participants,
            Integer completed,
            Integer notCompleted,
            OffsetDateTime observedAt,
            OffsetDateTime changedAt,
            int teachers,
            Activity activity
    ) {
        /**
         * Версия данных, при которой скрытие сигнала действует. Для сигналов по числам — changed_at снимка, он не
         * меняется от активности. Активность меняется каждый день, поэтому её сигнал скрывается до конца недели
         * наблюдения (по Москве), а не до следующего запуска.
         */
        String dataVersion(LmsSignalType type) {
            if (type.activity()) {
                LocalDate week = observedAt.atZoneSameInstant(WorkProperties.ZONE).toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                return "week:" + week + "/" + mappingVersion;
            }
            return changedAt.toInstant() + "/" + mappingVersion;
        }
    }

    record Activity(int active7d, int active30d, int neverAccessed, int inactive, int teachersActive7d, int teachersActive14d) {
    }
}
