package ru.rtk.crm.interaction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKind;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindRef;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindRule;

@Repository
public class InteractionKindRepository {
    private static final String KIND_SELECT = """
            SELECT k.id, k.name, k.description, k.active, k.workflow_template_id, kt.name AS template_name,
                   k.sort_order, k.version, tt.workflow_template_id AS team_template_id, ttt.name AS team_template_name,
                   k.default_kind
            FROM interaction_kinds k
            LEFT JOIN workflow_templates kt ON kt.id = k.workflow_template_id
            LEFT JOIN interaction_kind_team_templates tt ON tt.kind_id = k.id AND tt.team_id = :teamId
            LEFT JOIN workflow_templates ttt ON ttt.id = tt.workflow_template_id
            """;
    private static final String RULE_SELECT = """
            SELECT r.id, r.kind_id, k.name AS kind_name, r.organization_type, r.program_id, p.name AS program_name,
                   r.product_id, pr.name AS product_name, r.priority, r.version
            FROM interaction_kind_rules r
            JOIN interaction_kinds k ON k.id = r.kind_id
            LEFT JOIN programs p ON p.id = r.program_id
            LEFT JOIN products pr ON pr.id = r.product_id
            """;

    private final JdbcClient jdbcClient;

    public InteractionKindRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<InteractionKind> findKinds(UUID teamId, boolean activeOnly) {
        return jdbcClient.sql(KIND_SELECT + (activeOnly ? " WHERE k.active = TRUE" : "") + " ORDER BY k.sort_order, k.name, k.id")
                .param("teamId", teamId)
                .query(this::mapKind)
                .list();
    }

    public Optional<InteractionKind> findKind(UUID kindId, UUID teamId) {
        return jdbcClient.sql(KIND_SELECT + " WHERE k.id = :kindId")
                .param("kindId", kindId)
                .param("teamId", teamId)
                .query(this::mapKind)
                .optional();
    }

    public Optional<InteractionKind> findKindForUpdate(UUID kindId) {
        jdbcClient.sql("SELECT id FROM interaction_kinds WHERE id = :kindId FOR UPDATE")
                .param("kindId", kindId)
                .query(UUID.class)
                .optional();
        return findKind(kindId, null);
    }

    public Optional<InteractionKindRef> findRef(UUID kindId) {
        return jdbcClient.sql("SELECT id, name FROM interaction_kinds WHERE id = :kindId")
                .param("kindId", kindId)
                .query((resultSet, rowNumber) -> new InteractionKindRef(
                        resultSet.getObject("id", UUID.class), resultSet.getString("name")))
                .optional();
    }

    public boolean nameTaken(String name, UUID exceptId) {
        JdbcClient.StatementSpec statement = jdbcClient.sql("SELECT COUNT(*) FROM interaction_kinds WHERE LOWER(name) = LOWER(:name)"
                        + (exceptId == null ? "" : " AND id <> :exceptId"))
                .param("name", name);
        if (exceptId != null) {
            statement = statement.param("exceptId", exceptId);
        }
        return statement.query(Long.class).single() > 0;
    }

    public long countActiveExcept(UUID kindId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM interaction_kinds WHERE active = TRUE AND id <> :kindId")
                .param("kindId", kindId)
                .query(Long.class)
                .single();
    }

    public int nextSortOrder() {
        return jdbcClient.sql("SELECT COALESCE(MAX(sort_order) + 1, 0) FROM interaction_kinds")
                .query(Integer.class)
                .single();
    }

    public void insertKind(UUID id, String name, String description, boolean active, UUID templateId, int sortOrder,
                           OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO interaction_kinds (id, name, description, active, workflow_template_id, sort_order, version,
                                               created_at, updated_at)
                VALUES (:id, :name, :description, :active, :templateId, :sortOrder, 0, :now, :now)
                """)
                .param("id", id)
                .param("name", name)
                .param("description", description)
                .param("active", active)
                .param("templateId", templateId)
                .param("sortOrder", sortOrder)
                .param("now", now)
                .update();
    }

    public boolean updateKind(UUID id, int expectedVersion, String name, String description, boolean active, UUID templateId,
                              int sortOrder, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE interaction_kinds
                SET name = :name, description = :description, active = :active, workflow_template_id = :templateId,
                    sort_order = :sortOrder, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("name", name)
                .param("description", description)
                .param("active", active)
                .param("templateId", templateId)
                .param("sortOrder", sortOrder)
                .param("now", now)
                .update() == 1;
    }

    // Вид по умолчанию один: назначение нового снимает отметку с прежнего в той же транзакции.
    public void makeDefault(UUID kindId) {
        jdbcClient.sql("UPDATE interaction_kinds SET default_kind = (id = :kindId)")
                .param("kindId", kindId)
                .update();
    }

    public void replaceTeamTemplate(UUID kindId, UUID teamId, UUID templateId, OffsetDateTime now) {
        jdbcClient.sql("DELETE FROM interaction_kind_team_templates WHERE kind_id = :kindId AND team_id = :teamId")
                .param("kindId", kindId)
                .param("teamId", teamId)
                .update();
        if (templateId != null) {
            jdbcClient.sql("""
                    INSERT INTO interaction_kind_team_templates (kind_id, team_id, workflow_template_id, updated_at)
                    VALUES (:kindId, :teamId, :templateId, :now)
                    """)
                    .param("kindId", kindId)
                    .param("teamId", teamId)
                    .param("templateId", templateId)
                    .param("now", now)
                    .update();
        }
    }

    public List<InteractionKindRule> findRules() {
        return jdbcClient.sql(RULE_SELECT + " ORDER BY r.priority, k.sort_order, k.name, r.id")
                .query(this::mapRule)
                .list();
    }

    public Optional<InteractionKindRule> findRule(UUID ruleId) {
        return jdbcClient.sql(RULE_SELECT + " WHERE r.id = :ruleId")
                .param("ruleId", ruleId)
                .query(this::mapRule)
                .optional();
    }

    public void insertRule(UUID id, UUID kindId, OrganizationType organizationType, UUID programId, UUID productId, int priority,
                           OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO interaction_kind_rules (id, kind_id, organization_type, program_id, product_id, priority, version,
                                                    created_at, updated_at)
                VALUES (:id, :kindId, :organizationType, :programId, :productId, :priority, 0, :now, :now)
                """)
                .param("id", id)
                .param("kindId", kindId)
                .param("organizationType", organizationType == null ? null : organizationType.name())
                .param("programId", programId)
                .param("productId", productId)
                .param("priority", priority)
                .param("now", now)
                .update();
    }

    public boolean updateRule(UUID id, int expectedVersion, UUID kindId, OrganizationType organizationType, UUID programId,
                              UUID productId, int priority, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE interaction_kind_rules
                SET kind_id = :kindId, organization_type = :organizationType, program_id = :programId,
                    product_id = :productId, priority = :priority, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("kindId", kindId)
                .param("organizationType", organizationType == null ? null : organizationType.name())
                .param("programId", programId)
                .param("productId", productId)
                .param("priority", priority)
                .param("now", now)
                .update() == 1;
    }

    public boolean deleteRule(UUID id, int expectedVersion) {
        return jdbcClient.sql("DELETE FROM interaction_kind_rules WHERE id = :id AND version = :expectedVersion")
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    public boolean activeKindExists(UUID kindId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM interaction_kinds WHERE id = :kindId AND active = TRUE")
                .param("kindId", kindId)
                .query(Long.class)
                .single() > 0;
    }

    // Подходящее правило: каждое заполненное условие должно совпасть, пустое не ограничивает. Побеждает меньший номер
    // приоритета, при равенстве — правило с большим числом условий (более точное), затем порядок вида в справочнике.
    public Optional<UUID> findKindByRule(UUID organizationId, UUID programId, List<UUID> productIds) {
        String program = programId == null ? "r.program_id IS NULL" : "(r.program_id IS NULL OR r.program_id = :programId)";
        String product = productIds.isEmpty() ? "r.product_id IS NULL" : "(r.product_id IS NULL OR r.product_id IN (:productIds))";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                SELECT r.kind_id
                FROM interaction_kind_rules r
                JOIN interaction_kinds k ON k.id = r.kind_id AND k.active = TRUE
                JOIN organizations o ON o.id = :organizationId
                WHERE (r.organization_type IS NULL OR r.organization_type = o.type) AND %s AND %s
                ORDER BY r.priority,
                         CASE WHEN r.organization_type IS NULL THEN 0 ELSE 1 END
                             + CASE WHEN r.program_id IS NULL THEN 0 ELSE 1 END
                             + CASE WHEN r.product_id IS NULL THEN 0 ELSE 1 END DESC,
                         k.sort_order, k.name, r.id
                LIMIT 1
                """.formatted(program, product))
                .param("organizationId", organizationId);
        if (programId != null) {
            statement = statement.param("programId", programId);
        }
        if (!productIds.isEmpty()) {
            statement = statement.param("productIds", productIds);
        }
        return statement.query(UUID.class).optional();
    }

    public Optional<UUID> findFirstActiveKind() {
        return jdbcClient.sql("""
                SELECT id FROM interaction_kinds WHERE active = TRUE
                ORDER BY CASE WHEN default_kind THEN 0 ELSE 1 END, sort_order, name, id
                LIMIT 1
                """)
                .query(UUID.class)
                .optional();
    }

    public Optional<UUID> findTeamTemplateId(UUID kindId, UUID organizationId) {
        return jdbcClient.sql("""
                SELECT tt.workflow_template_id
                FROM interaction_kind_team_templates tt
                JOIN organizations o ON o.team_id = tt.team_id
                WHERE tt.kind_id = :kindId AND o.id = :organizationId
                """)
                .param("kindId", kindId)
                .param("organizationId", organizationId)
                .query(UUID.class)
                .optional();
    }

    public Optional<UUID> findKindTemplateId(UUID kindId) {
        return jdbcClient.sql("SELECT workflow_template_id FROM interaction_kinds WHERE id = :kindId AND workflow_template_id IS NOT NULL")
                .param("kindId", kindId)
                .query(UUID.class)
                .optional();
    }

    private InteractionKind mapKind(ResultSet resultSet, int rowNumber) throws SQLException {
        return new InteractionKind(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("description"),
                resultSet.getBoolean("active"),
                resultSet.getObject("workflow_template_id", UUID.class),
                resultSet.getString("template_name"),
                resultSet.getInt("sort_order"),
                resultSet.getInt("version"),
                resultSet.getObject("team_template_id", UUID.class),
                resultSet.getString("team_template_name"),
                resultSet.getBoolean("default_kind")
        );
    }

    private InteractionKindRule mapRule(ResultSet resultSet, int rowNumber) throws SQLException {
        String type = resultSet.getString("organization_type");
        return new InteractionKindRule(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("kind_id", UUID.class),
                resultSet.getString("kind_name"),
                type == null ? null : OrganizationType.valueOf(type),
                resultSet.getObject("program_id", UUID.class),
                resultSet.getString("program_name"),
                resultSet.getObject("product_id", UUID.class),
                resultSet.getString("product_name"),
                resultSet.getInt("priority"),
                resultSet.getInt("version")
        );
    }
}
