package ru.rtk.crm.interaction;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;

@Repository
public class WorkflowRolloutRepository {
    private final JdbcClient jdbcClient;

    public WorkflowRolloutRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Map<Integer, Long> countActiveWorksByVersion(UUID templateId, VisibilityScope scope) {
        Map<Integer, Long> counts = new HashMap<>();
        jdbcClient.sql("""
                SELECT i.workflow_version, COUNT(*) AS works
                FROM interactions i
                WHERE i.workflow_template_id = :templateId AND i.workflow_version IS NOT NULL
                  AND i.work_status <> 'COMPLETED'
                  AND i.organization_id IN (SELECT id FROM organizations WHERE %s)
                GROUP BY i.workflow_version
                """.formatted(scope.condition()))
                .params(scope.parameters())
                .param("templateId", templateId)
                .query(resultSet -> {
                    counts.put(resultSet.getInt("workflow_version"), resultSet.getLong("works"));
                });
        return counts;
    }

    public List<Candidate> findCandidates(UUID templateId, int targetVersion, VisibilityScope scope) {
        return jdbcClient.sql("""
                SELECT i.id, i.title, o.name AS organization_name, i.version, i.workflow_version,
                       i.current_stage_id, stage.name AS current_stage_name
                FROM interactions i
                JOIN organizations o ON o.id = i.organization_id
                JOIN interaction_stages stage ON stage.id = i.current_stage_id AND stage.interaction_id = i.id
                WHERE i.workflow_template_id = :templateId AND i.workflow_version IS NOT NULL
                  AND i.workflow_version <> :targetVersion AND i.work_status <> 'COMPLETED'
                  AND i.organization_id IN (SELECT id FROM organizations WHERE %s)
                ORDER BY o.name, i.title, i.id
                """.formatted(scope.condition()))
                .params(scope.parameters())
                .param("templateId", templateId)
                .param("targetVersion", targetVersion)
                .query((resultSet, rowNumber) -> new Candidate(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getString("organization_name"),
                        resultSet.getInt("version"),
                        resultSet.getInt("workflow_version"),
                        resultSet.getObject("current_stage_id", UUID.class),
                        resultSet.getString("current_stage_name")
                ))
                .list();
    }

    public Optional<RouteState> findState(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT workflow_template_id, workflow_version, work_status
                FROM interactions
                WHERE id = :interactionId
                """)
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> new RouteState(
                        resultSet.getObject("workflow_template_id", UUID.class),
                        resultSet.getObject("workflow_version", Integer.class),
                        InteractionWorkStatus.valueOf(resultSet.getString("work_status"))
                ))
                .optional();
    }

    public List<WorkflowRollout.RouteStage> findRouteStages(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT id, name, stage_order, optional, template_stage_id
                FROM interaction_stages
                WHERE interaction_id = :interactionId
                ORDER BY stage_order
                """)
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> new WorkflowRollout.RouteStage(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getInt("stage_order"),
                        resultSet.getBoolean("optional"),
                        resultSet.getObject("template_stage_id", UUID.class)
                ))
                .list();
    }

    // Условие version = :expectedVersion — оптимистичная блокировка: 409 вместо тихой перезаписи, если карточку
    // успели изменить после предпросмотра.
    public boolean moveToVersion(
            UUID interactionId,
            int expectedVersion,
            UUID currentStageId,
            int workflowVersion,
            OffsetDateTime updatedAt
    ) {
        return jdbcClient.sql("""
                UPDATE interactions
                SET current_stage_id = :currentStageId, workflow_version = :workflowVersion,
                    version = version + 1, updated_at = :updatedAt
                WHERE id = :interactionId AND version = :expectedVersion
                """)
                .param("interactionId", interactionId)
                .param("expectedVersion", expectedVersion)
                .param("currentStageId", currentStageId)
                .param("workflowVersion", workflowVersion)
                .param("updatedAt", updatedAt)
                .update() == 1;
    }

    // Все ссылки на объединяемый этап (события — этап, откуда и куда, файлы) переводятся до удаления строки этапа:
    // иначе история и вложения потеряют привязку.
    public void repointStage(UUID interactionId, UUID fromStageId, UUID toStageId) {
        for (String statement : List.of(
                "UPDATE interaction_events SET stage_id = :toStageId WHERE interaction_id = :interactionId AND stage_id = :fromStageId",
                "UPDATE interaction_events SET from_stage_id = :toStageId WHERE interaction_id = :interactionId AND from_stage_id = :fromStageId",
                "UPDATE interaction_events SET to_stage_id = :toStageId WHERE interaction_id = :interactionId AND to_stage_id = :fromStageId",
                "UPDATE attachments SET stage_id = :toStageId WHERE interaction_id = :interactionId AND stage_id = :fromStageId"
        )) {
            jdbcClient.sql(statement)
                    .param("interactionId", interactionId)
                    .param("fromStageId", fromStageId)
                    .param("toStageId", toStageId)
                    .update();
        }
    }

    record Candidate(
            UUID id,
            String title,
            String organizationName,
            int version,
            int workflowVersion,
            UUID currentStageId,
            String currentStageName
    ) {
    }

    record RouteState(UUID templateId, Integer workflowVersion, InteractionWorkStatus workStatus) {
    }
}
