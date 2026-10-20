package ru.rtk.crm.interaction;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;

// История карточки дополняется событиями, которые уже записаны в своих таблицах: загрузка документа, не привязанного
// к переходу или комментарию; создание и смена статуса договора этой работы; запуск нового цикла. Отдельной записи в
// interaction_events им не заводим: она дублировала бы историю договора и циклов, а загрузка поднимала бы версию
// карточки и давала 409 параллельному окну. Документ, привязанный к событию, показывается в самом событии — здесь
// его нет, поэтому одно действие не встречается в истории дважды.
@Repository
public class InteractionRelatedEvents {
    private final JdbcClient jdbcClient;

    public InteractionRelatedEvents(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<InteractionEvent> merge(UUID interactionId, List<InteractionEvent> events) {
        if (events.isEmpty()) {
            return events;
        }
        List<InteractionEvent> related = new ArrayList<>();
        related.addAll(documents(interactionId, events));
        related.addAll(agreements(interactionId, events));
        related.addAll(cycles(interactionId, events));
        if (related.isEmpty()) {
            return events;
        }
        related.sort(Comparator.comparing(InteractionEvent::occurredAt));
        // Порядок своих событий (по версии) сохраняется: карта пути берёт последний вход на этап по этому порядку.
        // Соседние события встают перед первым своим событием, которое случилось позже них.
        List<InteractionEvent> merged = new ArrayList<>(events.size() + related.size());
        int next = 0;
        for (InteractionEvent event : events) {
            while (next < related.size() && related.get(next).occurredAt().isBefore(event.occurredAt())) {
                merged.add(related.get(next++));
            }
            merged.add(event);
        }
        merged.addAll(related.subList(next, related.size()));
        return merged;
    }

    private List<InteractionEvent> documents(UUID interactionId, List<InteractionEvent> events) {
        return jdbcClient.sql("""
                SELECT a.id, a.stage_id, s.name AS stage_name, a.original_name, a.created_by, p.display_name, a.created_at,
                       CASE WHEN a.deleted_at IS NULL THEN 0 ELSE 1 END AS deleted
                FROM attachments a
                JOIN interaction_stages s ON s.id = a.stage_id
                JOIN crm_user_profiles p ON p.id = a.created_by
                WHERE a.interaction_id = :interactionId AND a.event_id IS NULL
                """)
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> {
                    UUID id = resultSet.getObject("id", UUID.class);
                    String name = resultSet.getString("original_name");
                    OffsetDateTime at = resultSet.getObject("created_at", OffsetDateTime.class);
                    boolean deleted = resultSet.getInt("deleted") == 1;
                    return event(id, InteractionEventType.DOCUMENT_ADDED,
                            resultSet.getObject("stage_id", UUID.class), resultSet.getString("stage_name"),
                            "Документ «" + name + "»" + (deleted ? " (позже удалён)" : ""),
                            resultSet.getObject("created_by", UUID.class), resultSet.getString("display_name"), at,
                            versionAt(events, at),
                            deleted ? null : new InteractionEventLink(InteractionEventLink.Kind.ATTACHMENT, id, null, name));
                })
                .list();
    }

    // Событие договора попадает в карточку, если переход отмечен этой работой, либо не отмечен никакой и договор связан
    // с ней. Переход, отмеченный другой работой того же договора, показывается только там.
    private List<InteractionEvent> agreements(UUID interactionId, List<InteractionEvent> events) {
        return jdbcClient.sql("""
                SELECT se.id, se.agreement_id, ag.number, ag.organization_id, se.from_status, se.to_status, se.comment,
                       se.actor_profile_id, p.display_name, se.occurred_at
                FROM agreement_status_events se
                JOIN agreements ag ON ag.id = se.agreement_id
                JOIN crm_user_profiles p ON p.id = se.actor_profile_id
                WHERE se.interaction_id = :interactionId
                   OR (se.interaction_id IS NULL AND EXISTS (
                       SELECT 1 FROM agreement_interactions link
                       WHERE link.agreement_id = se.agreement_id AND link.interaction_id = :interactionId))
                """)
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> {
                    String number = resultSet.getString("number");
                    String from = resultSet.getString("from_status");
                    String to = AgreementStatus.valueOf(resultSet.getString("to_status")).title();
                    String comment = resultSet.getString("comment");
                    String text = from == null
                            ? "Договор № " + number + " создан в статусе «" + to + "»"
                            : "Договор № " + number + ": «" + AgreementStatus.valueOf(from).title() + "» → «" + to + "»";
                    if (comment != null && !comment.isBlank()) {
                        text += ". " + comment.strip();
                    }
                    OffsetDateTime at = resultSet.getObject("occurred_at", OffsetDateTime.class);
                    InteractionEvent stageAt = stageAt(events, at);
                    return event(resultSet.getObject("id", UUID.class), InteractionEventType.AGREEMENT_STATUS_CHANGED,
                            stageId(stageAt), stageName(stageAt), text,
                            resultSet.getObject("actor_profile_id", UUID.class), resultSet.getString("display_name"), at,
                            stageAt.version(),
                            new InteractionEventLink(InteractionEventLink.Kind.AGREEMENT, resultSet.getObject("agreement_id", UUID.class),
                                    resultSet.getObject("organization_id", UUID.class), "№ " + number));
                })
                .list();
    }

    // Новый цикл виден в обеих карточках: в прежней — «начат новый цикл» со ссылкой на новую, в новой — ссылка на прежнюю.
    private List<InteractionEvent> cycles(UUID interactionId, List<InteractionEvent> events) {
        return jdbcClient.sql("""
                SELECT c.interaction_id, c.previous_interaction_id, c.created_by, p.display_name, c.created_at,
                       other.id AS other_id, other.title AS other_title, other.organization_id
                FROM interaction_cycles c
                JOIN crm_user_profiles p ON p.id = c.created_by
                JOIN interactions other ON other.id = CASE WHEN c.interaction_id = :interactionId
                    THEN c.previous_interaction_id ELSE c.interaction_id END
                WHERE c.interaction_id = :interactionId OR c.previous_interaction_id = :interactionId
                """)
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> {
                    boolean isNew = interactionId.equals(resultSet.getObject("interaction_id", UUID.class));
                    String title = resultSet.getString("other_title");
                    OffsetDateTime at = resultSet.getObject("created_at", OffsetDateTime.class);
                    InteractionEvent stageAt = stageAt(events, at);
                    UUID otherId = resultSet.getObject("other_id", UUID.class);
                    return event(otherId, InteractionEventType.CYCLE_STARTED, stageId(stageAt), stageName(stageAt),
                            isNew ? "Новый цикл после взаимодействия «" + title + "»" : "Начат новый цикл «" + title + "»",
                            resultSet.getObject("created_by", UUID.class), resultSet.getString("display_name"), at,
                            stageAt.version(),
                            new InteractionEventLink(InteractionEventLink.Kind.INTERACTION, otherId,
                                    resultSet.getObject("organization_id", UUID.class), title));
                })
                .list();
    }

    // Этап на момент события — по последнему своему событию не позже него; до первого — этап создания карточки.
    private static InteractionEvent stageAt(List<InteractionEvent> events, OffsetDateTime at) {
        InteractionEvent found = events.getFirst();
        for (InteractionEvent event : events) {
            if (!event.occurredAt().isAfter(at)) {
                found = event;
            }
        }
        return found;
    }

    private static int versionAt(List<InteractionEvent> events, OffsetDateTime at) {
        return stageAt(events, at).version();
    }

    private static UUID stageId(InteractionEvent event) {
        return event.toStageId() != null ? event.toStageId() : event.stageId();
    }

    private static String stageName(InteractionEvent event) {
        return event.toStageId() != null ? event.toStageNameSnapshot() : event.stageNameSnapshot();
    }

    private static InteractionEvent event(UUID id, InteractionEventType type, UUID stageId, String stageName, String comment,
                                          UUID actorId, String actorName, OffsetDateTime at, int version, InteractionEventLink link) {
        return new InteractionEvent(id, type, id, stageId, stageName, null, null, null, null, comment, null, actorId, actorName,
                null, version, at, false, link);
    }
}
