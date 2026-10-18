package ru.rtk.crm.work;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;

@Repository
public class WorkNewsRepository {
    // Новое из источников — события, записанные командой APPLY_SOURCE_RECORD: заявки сайта и наблюдения LMS. По
    // этому же признаку они исключаются из «последнего действия сотрудника» (М14).
    private static final String SOURCE_EVENTS = """
            FROM interaction_events e
            JOIN command_idempotency_records source_command
              ON source_command.id = e.command_id AND source_command.operation = 'APPLY_SOURCE_RECORD'
            JOIN interactions i ON i.id = e.interaction_id
            JOIN organizations o ON o.id = i.organization_id
            LEFT JOIN interaction_event_views viewed ON viewed.event_id = e.id AND viewed.profile_id = :profileId
            WHERE e.type = 'COMMENTED' AND e.occurred_at >= :since
              AND i.organization_id IN (%s)
            """;

    private final JdbcClient jdbcClient;

    public WorkNewsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<WorkNewsService.NewsItem> find(VisibilityScope scope, UUID profileId, OffsetDateTime since, int limit) {
        return jdbcClient.sql("""
                SELECT e.id, e.interaction_id, i.organization_id, o.name AS organization_name, i.title, e.comment, e.occurred_at,
                       CASE WHEN viewed.event_id IS NULL THEN FALSE ELSE TRUE END AS viewed
                """ + SOURCE_EVENTS.formatted(scope.currentOrganizations()) + """
                ORDER BY CASE WHEN viewed.event_id IS NULL THEN 0 ELSE 1 END, e.occurred_at DESC, e.id
                LIMIT :limit
                """)
                .params(scope.parameters())
                .param("profileId", profileId)
                .param("since", since)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> WorkNewsService.NewsItem.of(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getString("title"),
                        resultSet.getString("comment"),
                        resultSet.getObject("occurred_at", OffsetDateTime.class),
                        resultSet.getBoolean("viewed")
                ))
                .list();
    }

    public long countUnviewed(VisibilityScope scope, UUID profileId, OffsetDateTime since) {
        return jdbcClient.sql("SELECT COUNT(*) " + SOURCE_EVENTS.formatted(scope.currentOrganizations()) + " AND viewed.event_id IS NULL")
                .params(scope.parameters())
                .param("profileId", profileId)
                .param("since", since)
                .query(Long.class)
                .single();
    }

    public boolean isVisibleSourceEvent(VisibilityScope scope, UUID profileId, UUID eventId) {
        return jdbcClient.sql("SELECT COUNT(*) " + SOURCE_EVENTS.formatted(scope.currentOrganizations()) + " AND e.id = :eventId")
                .params(scope.parameters())
                .param("profileId", profileId)
                .param("since", OffsetDateTime.parse("2000-01-01T00:00:00Z"))
                .param("eventId", eventId)
                .query(Long.class)
                .single() > 0;
    }

    public boolean markViewed(UUID profileId, UUID eventId, OffsetDateTime viewedAt) {
        try {
            return jdbcClient.sql("""
                    INSERT INTO interaction_event_views (profile_id, event_id, viewed_at)
                    SELECT :profileId, :eventId, :viewedAt
                    WHERE NOT EXISTS (
                        SELECT 1 FROM interaction_event_views WHERE profile_id = :profileId AND event_id = :eventId
                    )
                    """)
                    .param("profileId", profileId)
                    .param("eventId", eventId)
                    .param("viewedAt", viewedAt)
                    .update() == 1;
        } catch (DuplicateKeyException exception) {
            // Две вкладки отметили одно событие одновременно: отметка уже есть, повтор ничего не меняет.
            return false;
        }
    }
}
