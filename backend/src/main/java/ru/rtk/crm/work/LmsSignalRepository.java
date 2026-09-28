package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalType;

@Repository
public class LmsSignalRepository {
    private static final String SOURCES = """
            SELECT i.id AS interaction_id, i.title, i.organization_id, o.name AS organization_name, o.owner_manager_id,
                   owner_profile.display_name AS owner_manager_name, current_stage.name AS current_stage_name,
                   current_stage.stage_order AS current_stage_order, classes.id AS classes_stage_id,
                   classes.name AS classes_stage_name, classes.stage_order AS classes_stage_order,
                   route.to_stage_id AS route_to_stage_id, route.comment_required AS route_comment_required,
                   s.mapping_id, m.version AS mapping_version, s.course_name, s.group_name, m.run_starts_on, m.run_ends_on,
                   s.participants_count, s.completed_count, s.observed_at, s.changed_at
            FROM interactions i
            JOIN organizations o ON o.id = i.organization_id
            LEFT JOIN crm_user_profiles owner_profile ON owner_profile.id = o.owner_manager_id
            JOIN interaction_stages current_stage ON current_stage.id = i.current_stage_id
            LEFT JOIN interaction_stages classes ON classes.interaction_id = i.id AND classes.name = :classesStageName
                AND NOT EXISTS (
                    SELECT 1 FROM interaction_stages earlier
                    WHERE earlier.interaction_id = i.id AND earlier.name = :classesStageName
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

    List<SignalSource> findSources(VisibilityScope scope, UUID interactionId, String classesStageName) {
        String sql = SOURCES.formatted(scope.condition())
                + (interactionId == null ? "" : " AND i.id = :interactionId")
                + " ORDER BY o.name, i.title, i.id, s.course_name, s.group_name, m.run_starts_on, s.mapping_id";
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .params(scope.parameters())
                .param("classesStageName", classesStageName);
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
                        resultSet.getObject("observed_at", OffsetDateTime.class),
                        resultSet.getObject("changed_at", OffsetDateTime.class)
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

    Set<String> findDismissals(VisibilityScope scope, UUID interactionId) {
        String sql = """
                SELECT d.interaction_id, d.mapping_id, d.signal_type, d.data_version
                FROM lms_signal_dismissals d
                JOIN interactions i ON i.id = d.interaction_id
                WHERE i.organization_id IN (SELECT id FROM organizations WHERE %s)
                """.formatted(scope.condition()) + (interactionId == null ? "" : " AND i.id = :interactionId");
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql).params(scope.parameters());
        if (interactionId != null) {
            statement = statement.param("interactionId", interactionId);
        }
        return new HashSet<>(statement.query((resultSet, rowNumber) -> dismissalKey(
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getObject("mapping_id", UUID.class),
                        LmsSignalType.valueOf(resultSet.getString("signal_type")),
                        resultSet.getString("data_version")
                ))
                .list());
    }

    void saveDismissal(UUID interactionId, UUID mappingId, LmsSignalType type, String dataVersion, UUID profileId,
                       OffsetDateTime now) {
        jdbcClient.sql("""
                DELETE FROM lms_signal_dismissals
                WHERE interaction_id = :interactionId AND mapping_id = :mappingId AND signal_type = :type
                """)
                .param("interactionId", interactionId)
                .param("mappingId", mappingId)
                .param("type", type.name())
                .update();
        jdbcClient.sql("""
                INSERT INTO lms_signal_dismissals (interaction_id, mapping_id, signal_type, data_version, dismissed_by, dismissed_at)
                VALUES (:interactionId, :mappingId, :type, :dataVersion, :profileId, :now)
                """)
                .param("interactionId", interactionId)
                .param("mappingId", mappingId)
                .param("type", type.name())
                .param("dataVersion", dataVersion)
                .param("profileId", profileId)
                .param("now", now)
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
            OffsetDateTime observedAt,
            OffsetDateTime changedAt
    ) {
        String dataVersion() {
            return changedAt.toInstant() + "/" + mappingVersion;
        }
    }
}
