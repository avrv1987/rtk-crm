package ru.rtk.crm.interaction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WorkflowTemplateRepository {
    private final JdbcClient jdbcClient;

    public WorkflowTemplateRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public WorkflowTemplatePage findPageForTeam(UUID teamId, WorkflowTemplateQuery query) {
        return findPage("team_id IS NULL OR team_id = :teamId", Map.of("teamId", teamId), query);
    }

    public WorkflowTemplatePage findTeamPageWithDefault(UUID teamId, WorkflowTemplateQuery query) {
        return findPage("team_id = :teamId OR default_template = TRUE", Map.of("teamId", teamId), query);
    }

    public WorkflowTemplatePage findGlobalPage(WorkflowTemplateQuery query) {
        return findPage("team_id IS NULL", Map.of(), query);
    }

    public Optional<WorkflowTemplate> findDefault() {
        return findDefault(false);
    }

    public Optional<WorkflowTemplate> findDefaultForUpdate() {
        return findDefault(true);
    }

    private Optional<WorkflowTemplate> findDefault(boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        return jdbcClient.sql(("""
                SELECT id, team_id, name, default_template, version
                FROM workflow_templates
                WHERE default_template = TRUE
                """ + lock))
                .query(this::mapRow)
                .optional()
                .map(this::toTemplate);
    }

    public Optional<WorkflowTemplateRow> findById(UUID templateId) {
        return findOne(templateId, false);
    }

    public Optional<WorkflowTemplateRow> findByIdForUpdate(UUID templateId) {
        return findOne(templateId, true);
    }

    public List<WorkflowTemplateStage> findStages(UUID templateId) {
        return jdbcClient.sql("""
                SELECT id, name, stage_order, optional
                FROM workflow_template_stages
                WHERE template_id = :templateId
                ORDER BY stage_order ASC
                """)
                .param("templateId", templateId)
                .query((resultSet, rowNumber) -> new WorkflowTemplateStage(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getInt("stage_order"),
                        resultSet.getBoolean("optional")
                ))
                .list();
    }

    public List<WorkflowTemplateTransition> findTransitions(UUID templateId) {
        return jdbcClient.sql("""
                SELECT from_stage_id, to_stage_id, comment_required
                FROM workflow_template_transitions
                WHERE template_id = :templateId
                ORDER BY from_stage_id, to_stage_id
                """)
                .param("templateId", templateId)
                .query((resultSet, rowNumber) -> new WorkflowTemplateTransition(
                        resultSet.getObject("from_stage_id", UUID.class),
                        resultSet.getObject("to_stage_id", UUID.class),
                        resultSet.getBoolean("comment_required")
                ))
                .list();
    }

    public void insert(
            UUID templateId,
            UUID teamId,
            String name,
            List<WorkflowTemplateStage> stages,
            List<WorkflowTemplateTransition> transitions,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO workflow_templates (
                    id, team_id, name, default_template, version, created_at, updated_at
                ) VALUES (
                    :id, :teamId, :name, FALSE, 0, :createdAt, :updatedAt
                )
                """)
                .param("id", templateId)
                .param("teamId", teamId)
                .param("name", name)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        insertStages(templateId, stages);
        insertTransitions(templateId, transitions);
    }

    public boolean update(
            UUID templateId,
            int expectedVersion,
            String name,
            OffsetDateTime updatedAt
    ) {
        return jdbcClient.sql("""
                UPDATE workflow_templates
                SET name = :name, version = version + 1, updated_at = :updatedAt
                WHERE id = :templateId AND version = :expectedVersion
                """)
                .param("templateId", templateId)
                .param("expectedVersion", expectedVersion)
                .param("name", name)
                .param("updatedAt", updatedAt)
                .update() == 1;
    }

    public boolean delete(UUID templateId, int expectedVersion) {
        return jdbcClient.sql("DELETE FROM workflow_templates WHERE id = :templateId AND version = :expectedVersion")
                .param("templateId", templateId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    public void replaceWorkflow(
            UUID templateId,
            List<WorkflowTemplateStage> stages,
            List<WorkflowTemplateTransition> transitions
    ) {
        jdbcClient.sql("DELETE FROM workflow_template_transitions WHERE template_id = :templateId")
                .param("templateId", templateId)
                .update();
        jdbcClient.sql("DELETE FROM workflow_template_stages WHERE template_id = :templateId")
                .param("templateId", templateId)
                .update();
        insertStages(templateId, stages);
        insertTransitions(templateId, transitions);
    }

    private WorkflowTemplatePage findPage(String condition, Map<String, Object> parameters, WorkflowTemplateQuery query) {
        List<WorkflowTemplate> items = jdbcClient.sql("""
                SELECT id, team_id, name, default_template, version
                FROM workflow_templates
                WHERE %s
                ORDER BY %s
                LIMIT :size OFFSET :offset
                """.formatted(condition, query.sort().orderBy()))
                .params(parameters)
                .param("size", query.size())
                .param("offset", query.offset())
                .query(this::mapRow)
                .list()
                .stream()
                .map(this::toTemplate)
                .toList();
        long total = jdbcClient.sql("SELECT COUNT(*) FROM workflow_templates WHERE " + condition)
                .params(parameters)
                .query(Long.class)
                .single();
        return new WorkflowTemplatePage(items, query.page(), query.size(), total);
    }

    private Optional<WorkflowTemplateRow> findOne(UUID templateId, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        return jdbcClient.sql(("""
                SELECT id, team_id, name, default_template, version
                FROM workflow_templates
                WHERE id = :templateId
                """ + lock))
                .param("templateId", templateId)
                .query(this::mapRow)
                .optional();
    }

    private void insertStages(UUID templateId, List<WorkflowTemplateStage> stages) {
        for (WorkflowTemplateStage stage : stages) {
            jdbcClient.sql("""
                    INSERT INTO workflow_template_stages (id, template_id, stage_order, name, optional)
                    VALUES (:id, :templateId, :stageOrder, :name, :optional)
                    """)
                    .param("id", stage.id())
                    .param("templateId", templateId)
                    .param("stageOrder", stage.order())
                    .param("name", stage.name())
                    .param("optional", stage.optional())
                    .update();
        }
    }

    private void insertTransitions(UUID templateId, List<WorkflowTemplateTransition> transitions) {
        for (WorkflowTemplateTransition transition : transitions) {
            jdbcClient.sql("""
                    INSERT INTO workflow_template_transitions (
                        template_id, from_stage_id, to_stage_id, comment_required
                    ) VALUES (
                        :templateId, :fromStageId, :toStageId, :commentRequired
                    )
                    """)
                    .param("templateId", templateId)
                    .param("fromStageId", transition.fromStageId())
                    .param("toStageId", transition.toStageId())
                    .param("commentRequired", transition.commentRequired())
                    .update();
        }
    }

    WorkflowTemplate toTemplate(WorkflowTemplateRow row) {
        return new WorkflowTemplate(
                row.id(),
                row.teamId(),
                row.name(),
                findStages(row.id()),
                findTransitions(row.id()),
                row.defaultTemplate(),
                row.version()
        );
    }

    private WorkflowTemplateRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new WorkflowTemplateRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("team_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getBoolean("default_template"),
                resultSet.getInt("version")
        );
    }

    record WorkflowTemplateRow(UUID id, UUID teamId, String name, boolean defaultTemplate, int version) {
    }
}
