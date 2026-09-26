package ru.rtk.crm.training;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TrainingRepository {
    private static final String TRAINING_SELECT = """
            SELECT t.id, t.interaction_id, t.event_id, t.trained_on, t.course_name, t.enrolled_count, t.completed_count,
                   t.attachment_id, a.original_name AS attachment_name, t.next_cycle_on, p.display_name, t.created_at
            FROM teacher_trainings t
            LEFT JOIN attachments a ON a.id = t.attachment_id
            LEFT JOIN crm_user_profiles p ON p.id = t.created_by
            """;

    private final JdbcClient jdbcClient;

    public TrainingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    List<TeacherTraining> findTrainings(UUID interactionId) {
        return jdbcClient.sql(TRAINING_SELECT + "WHERE t.interaction_id = :interactionId ORDER BY t.trained_on DESC, t.created_at DESC")
                .param("interactionId", interactionId)
                .query(this::mapTraining)
                .list();
    }

    Optional<TeacherTraining> findTrainingByEvent(UUID eventId) {
        return jdbcClient.sql(TRAINING_SELECT + "WHERE t.event_id = :eventId")
                .param("eventId", eventId)
                .query(this::mapTraining)
                .optional();
    }

    void insertTraining(UUID id, UUID interactionId, UUID eventId, TeacherTrainingRequest request, UUID actorProfileId,
                        OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO teacher_trainings (
                    id, interaction_id, event_id, trained_on, course_name, enrolled_count, completed_count, attachment_id,
                    next_cycle_on, created_by, created_at
                ) VALUES (
                    :id, :interactionId, :eventId, :trainedOn, :courseName, :enrolledCount, :completedCount, :attachmentId,
                    :nextCycleOn, :actorProfileId, :now
                )
                """)
                .param("id", id)
                .param("interactionId", interactionId)
                .param("eventId", eventId)
                .param("trainedOn", request.trainedOn())
                .param("courseName", request.courseName().strip())
                .param("enrolledCount", request.enrolledCount())
                .param("completedCount", request.completedCount())
                .param("attachmentId", request.attachmentId())
                .param("nextCycleOn", request.nextCycleOn())
                .param("actorProfileId", actorProfileId)
                .param("now", now)
                .update();
    }

    Optional<LocalDate> findCycleStart(UUID interactionId) {
        return jdbcClient.sql("SELECT starts_on FROM interaction_cycles WHERE interaction_id = :interactionId")
                .param("interactionId", interactionId)
                .query(LocalDate.class)
                .optional();
    }

    Optional<CycleLink> findPrevious(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT i.id, i.title, previous_cycle.starts_on, i.created_at
                FROM interaction_cycles c
                JOIN interactions i ON i.id = c.previous_interaction_id
                LEFT JOIN interaction_cycles previous_cycle ON previous_cycle.interaction_id = i.id
                WHERE c.interaction_id = :interactionId
                """)
                .param("interactionId", interactionId)
                .query(this::mapLink)
                .optional();
    }

    Optional<CycleLink> findNext(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT i.id, i.title, c.starts_on, i.created_at
                FROM interaction_cycles c
                JOIN interactions i ON i.id = c.interaction_id
                WHERE c.previous_interaction_id = :interactionId
                """)
                .param("interactionId", interactionId)
                .query(this::mapLink)
                .optional();
    }

    void lockInteraction(UUID interactionId) {
        jdbcClient.sql("SELECT id FROM interactions WHERE id = :interactionId FOR UPDATE")
                .param("interactionId", interactionId)
                .query(UUID.class)
                .optional();
    }

    void insertCycle(UUID interactionId, UUID previousInteractionId, LocalDate startsOn, UUID actorProfileId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO interaction_cycles (interaction_id, previous_interaction_id, starts_on, created_by, created_at)
                VALUES (:interactionId, :previousInteractionId, :startsOn, :actorProfileId, :now)
                """)
                .param("interactionId", interactionId)
                .param("previousInteractionId", previousInteractionId)
                .param("startsOn", startsOn)
                .param("actorProfileId", actorProfileId)
                .param("now", now)
                .update();
    }

    private CycleLink mapLink(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CycleLink(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("title"),
                resultSet.getObject("starts_on", LocalDate.class),
                resultSet.getObject("created_at", OffsetDateTime.class)
        );
    }

    private TeacherTraining mapTraining(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TeacherTraining(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("interaction_id", UUID.class),
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("trained_on", LocalDate.class),
                resultSet.getString("course_name"),
                resultSet.getInt("enrolled_count"),
                resultSet.getObject("completed_count", Integer.class),
                resultSet.getObject("attachment_id", UUID.class),
                resultSet.getString("attachment_name"),
                resultSet.getObject("next_cycle_on", LocalDate.class),
                resultSet.getString("display_name"),
                resultSet.getObject("created_at", OffsetDateTime.class)
        );
    }
}
