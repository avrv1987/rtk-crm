package ru.rtk.crm.interaction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WorkflowTemplateRepository {
    private static final String VERSION_SELECT = """
            SELECT v.version_number, v.name, v.content, v.change_comment, v.created_at,
                   author.display_name AS author_name
            FROM workflow_template_versions v
            LEFT JOIN crm_user_profiles author ON author.id = v.created_by
            """;

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

    public Optional<WorkflowTemplate> findDefaultForOrganizationForUpdate(UUID organizationId) {
        return findDefaultForOrganization(organizationId, true);
    }

    public Optional<WorkflowTemplate> findDefaultForOrganization(UUID organizationId, boolean forUpdate) {
        return jdbcClient.sql("""
                SELECT team.default_workflow_template_id
                FROM organizations organization
                JOIN teams team ON team.id = organization.team_id
                WHERE organization.id = :organizationId AND team.default_workflow_template_id IS NOT NULL
                """)
                .param("organizationId", organizationId)
                .query(UUID.class)
                .optional()
                .flatMap(id -> findOne(id, forUpdate))
                .map(this::toTemplate)
                .or(() -> findDefault(forUpdate));
    }

    public Optional<UUID> findTeamDefaultId(UUID teamId) {
        return jdbcClient.sql("""
                SELECT default_workflow_template_id
                FROM teams
                WHERE id = :teamId AND default_workflow_template_id IS NOT NULL
                """)
                .param("teamId", teamId)
                .query(UUID.class)
                .optional();
    }

    public void updateTeamDefault(UUID teamId, UUID templateId) {
        jdbcClient.sql("UPDATE teams SET default_workflow_template_id = :templateId WHERE id = :teamId")
                .param("teamId", teamId)
                .param("templateId", templateId)
                .update();
    }

    public void replaceGlobalDefault(UUID previousTemplateId, UUID templateId, OffsetDateTime updatedAt) {
        jdbcClient.sql("""
                UPDATE workflow_templates
                SET default_template = FALSE, version = version + 1, updated_at = :updatedAt
                WHERE id = :previousTemplateId
                """)
                .param("previousTemplateId", previousTemplateId)
                .param("updatedAt", updatedAt)
                .update();
        jdbcClient.sql("""
                UPDATE workflow_templates
                SET default_template = TRUE, version = version + 1, updated_at = :updatedAt
                WHERE id = :templateId
                """)
                .param("templateId", templateId)
                .param("updatedAt", updatedAt)
                .update();
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
        Map<UUID, List<String>> documents = new HashMap<>();
        jdbcClient.sql("""
                SELECT d.stage_id, d.kind_code
                FROM workflow_template_stage_documents d
                JOIN workflow_template_stages s ON s.id = d.stage_id
                WHERE s.template_id = :templateId
                ORDER BY d.kind_code
                """)
                .param("templateId", templateId)
                .query((ResultSet resultSet) -> {
                    documents.computeIfAbsent(resultSet.getObject("stage_id", UUID.class), ignored -> new ArrayList<>())
                            .add(resultSet.getString("kind_code"));
                });
        return jdbcClient.sql("""
                SELECT id, name, stage_order, optional, requires_agreement, lms_role, norm_days
                FROM workflow_template_stages
                WHERE template_id = :templateId
                ORDER BY stage_order ASC
                """)
                .param("templateId", templateId)
                .query((resultSet, rowNumber) -> {
                    UUID id = resultSet.getObject("id", UUID.class);
                    String role = resultSet.getString("lms_role");
                    return new WorkflowTemplateStage(
                            id,
                            resultSet.getString("name"),
                            resultSet.getInt("stage_order"),
                            resultSet.getBoolean("optional"),
                            resultSet.getBoolean("requires_agreement"),
                            role == null ? null : StageLmsRole.valueOf(role),
                            documents.get(id),
                            resultSet.getObject("norm_days", Integer.class)
                    );
                })
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

    /** Незавершённые (идущие и приостановленные) взаимодействия, которые идут по шаблону. */
    public long countOpenInteractions(UUID templateId) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM interactions WHERE workflow_template_id = :templateId AND work_status <> 'COMPLETED'
                """)
                .param("templateId", templateId)
                .query(Long.class)
                .single();
    }

    /** Названия видов взаимодействия, у которых шаблон выбран общим или командным маршрутом. */
    public List<String> findKindNamesUsing(UUID templateId) {
        return jdbcClient.sql("""
                SELECT DISTINCT k.name FROM interaction_kinds k
                WHERE k.workflow_template_id = :templateId
                   OR EXISTS (SELECT 1 FROM interaction_kind_team_templates t WHERE t.kind_id = k.id AND t.workflow_template_id = :templateId)
                ORDER BY k.name
                """)
                .param("templateId", templateId)
                .query(String.class)
                .list();
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
        jdbcClient.sql("""
                DELETE FROM workflow_template_stage_documents
                WHERE stage_id IN (SELECT id FROM workflow_template_stages WHERE template_id = :templateId)
                """)
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
        return new WorkflowTemplatePage(items, query.page(), query.size(), total, null);
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
                    INSERT INTO workflow_template_stages (
                        id, template_id, stage_order, name, optional, requires_agreement, lms_role, norm_days
                    ) VALUES (:id, :templateId, :stageOrder, :name, :optional, :requiresAgreement, :lmsRole, :normDays)
                    """)
                    .param("id", stage.id())
                    .param("templateId", templateId)
                    .param("stageOrder", stage.order())
                    .param("name", stage.name())
                    .param("optional", stage.optional())
                    .param("requiresAgreement", stage.requiresAgreement())
                    .param("lmsRole", stage.lmsRole() == null ? null : stage.lmsRole().name())
                    .param("normDays", stage.normDays(), java.sql.Types.INTEGER)
                    .update();
            for (String kind : stage.documentKinds()) {
                jdbcClient.sql("INSERT INTO workflow_template_stage_documents (stage_id, kind_code) VALUES (:stageId, :kind)")
                        .param("stageId", stage.id())
                        .param("kind", kind)
                        .update();
            }
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
                row.version(),
                findLatestVersionNumber(row.id())
        );
    }

    public int findLatestVersionNumber(UUID templateId) {
        return jdbcClient.sql("""
                SELECT COALESCE(MAX(version_number), 0)
                FROM workflow_template_versions
                WHERE template_id = :templateId
                """)
                .param("templateId", templateId)
                .query(Integer.class)
                .single();
    }

    public void insertVersion(
            UUID templateId,
            int number,
            String name,
            String content,
            String comment,
            UUID createdBy,
            OffsetDateTime createdAt
    ) {
        jdbcClient.sql("""
                INSERT INTO workflow_template_versions (
                    template_id, version_number, name, content, change_comment, created_by, created_at
                ) VALUES (
                    :templateId, :number, :name, :content, :comment, :createdBy, :createdAt
                )
                """)
                .param("templateId", templateId)
                .param("number", number)
                .param("name", name)
                .param("content", content)
                .param("comment", comment)
                .param("createdBy", createdBy)
                .param("createdAt", createdAt)
                .update();
    }

    public List<VersionRow> findVersions(UUID templateId) {
        return jdbcClient.sql(VERSION_SELECT + "WHERE v.template_id = :templateId ORDER BY v.version_number DESC")
                .param("templateId", templateId)
                .query(this::mapVersion)
                .list();
    }

    public Optional<VersionRow> findVersion(UUID templateId, int number) {
        return jdbcClient.sql(VERSION_SELECT + "WHERE v.template_id = :templateId AND v.version_number = :number")
                .param("templateId", templateId)
                .param("number", number)
                .query(this::mapVersion)
                .optional();
    }

    private VersionRow mapVersion(ResultSet resultSet, int rowNumber) throws SQLException {
        return new VersionRow(
                resultSet.getInt("version_number"),
                resultSet.getString("name"),
                resultSet.getString("content"),
                resultSet.getString("change_comment"),
                resultSet.getString("author_name"),
                resultSet.getObject("created_at", OffsetDateTime.class)
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

    record VersionRow(
            int number,
            String name,
            String content,
            String comment,
            String authorName,
            OffsetDateTime createdAt
    ) {
    }
}
