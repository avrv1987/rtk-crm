package ru.rtk.crm.catalogimport;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Запросы профилей переноса данных: история взаимодействий, договоры с вузом, настройка шага по умолчанию.
@Repository
class MigrationImportRepository {
    private final JdbcClient jdbcClient;

    MigrationImportRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    List<InteractionRef> findInteractions() {
        return jdbcClient.sql("""
                SELECT i.id, i.organization_id, i.title, i.external_key, i.current_stage_id, stage.name AS current_stage_name
                FROM interactions i
                JOIN interaction_stages stage ON stage.id = i.current_stage_id AND stage.interaction_id = i.id
                """)
                .query((resultSet, rowNumber) -> new InteractionRef(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getString("external_key"),
                        resultSet.getObject("current_stage_id", UUID.class),
                        resultSet.getString("current_stage_name")
                ))
                .list();
    }

    List<StageRef> findStages() {
        return jdbcClient.sql("SELECT id, interaction_id, name FROM interaction_stages")
                .query((resultSet, rowNumber) -> new StageRef(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getString("name")
                ))
                .list();
    }

    List<StageRef> findStages(UUID interactionId) {
        return jdbcClient.sql("SELECT id, interaction_id, name FROM interaction_stages WHERE interaction_id = :interactionId")
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> new StageRef(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getString("name")
                ))
                .list();
    }

    // Маршрут новой карточки выбирают правила видов при применении, поэтому в предпросмотре этап новой карточки
    // сверяется с этапами всех шаблонов; точная проверка — при применении.
    List<String> findTemplateStageNames() {
        return jdbcClient.sql("SELECT DISTINCT name FROM workflow_template_stages")
                .query(String.class)
                .list();
    }

    Map<String, String> findProfileNamesByLogin() {
        Map<String, String> names = new HashMap<>();
        jdbcClient.sql("SELECT login, display_name FROM crm_user_profiles WHERE login IS NOT NULL")
                .query((ResultSet resultSet) -> {
                    names.putIfAbsent(CatalogImportIndex.normalized(resultSet.getString("login")), resultSet.getString("display_name"));
                });
        return names;
    }

    void setInteractionExternalKey(UUID interactionId, String externalKey) {
        jdbcClient.sql("UPDATE interactions SET external_key = :externalKey WHERE id = :id")
                .param("id", interactionId)
                .param("externalKey", externalKey)
                .update();
    }

    void insertImportedEvent(ImportedEvent event) {
        jdbcClient.sql("""
                INSERT INTO interaction_events (
                    id, external_key, interaction_id, command_id, type, stage_id, stage_name_snapshot,
                    to_stage_id, to_stage_name_snapshot, comment, plan_changed, next_action, next_action_at,
                    contact_channel, actor_profile_id, owner_manager_id_snapshot, version, occurred_at,
                    import_source, import_author
                ) VALUES (
                    :id, :externalKey, :interactionId, :commandId, :type, :stageId, :stageName,
                    :toStageId, :toStageName, :comment, :planChanged, :nextAction, :nextActionAt,
                    :contactChannel, :actorProfileId, :ownerManagerId, :version, :occurredAt,
                    :importSource, :importAuthor
                )
                """)
                .param("id", UUID.randomUUID())
                .param("externalKey", event.externalKey())
                .param("interactionId", event.interactionId())
                .param("commandId", event.commandId())
                .param("type", event.type())
                .param("stageId", event.stageId())
                .param("stageName", event.stageName())
                .param("toStageId", event.transition() ? event.stageId() : null)
                .param("toStageName", event.transition() ? event.stageName() : null)
                .param("comment", event.comment())
                .param("planChanged", event.nextAction() != null)
                .param("nextAction", event.nextAction())
                .param("nextActionAt", event.nextActionAt())
                .param("contactChannel", event.contactChannel())
                .param("actorProfileId", event.actorProfileId())
                .param("ownerManagerId", event.ownerManagerId())
                .param("version", event.version())
                .param("occurredAt", event.occurredAt())
                .param("importSource", event.importSource())
                .param("importAuthor", event.importAuthor())
                .update();
    }

    // Дата последнего контакта двигается только вперёд, как у ручной отметки «был контакт».
    void advanceLastContact(UUID interactionId, OffsetDateTime contactedAt) {
        jdbcClient.sql("""
                UPDATE interactions
                SET last_contact_at = :contactedAt
                WHERE id = :interactionId AND (last_contact_at IS NULL OR last_contact_at < :contactedAt)
                """)
                .param("interactionId", interactionId)
                .param("contactedAt", contactedAt)
                .update();
    }

    // Самый поздний импортированный переход каждой карточки этой загрузки — кандидат на текущий этап.
    List<LastTransition> findLatestImportedTransitions(UUID commandId) {
        return jdbcClient.sql("""
                SELECT e.interaction_id, e.to_stage_id, e.occurred_at, i.current_stage_id, i.version
                FROM interaction_events e
                JOIN interactions i ON i.id = e.interaction_id
                WHERE e.command_id = :commandId AND e.type = 'TRANSITIONED' AND e.import_source IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM interaction_events later
                      WHERE later.interaction_id = e.interaction_id AND later.type = 'TRANSITIONED'
                        AND (later.occurred_at > e.occurred_at OR (later.occurred_at = e.occurred_at AND later.version > e.version))
                  )
                """)
                .param("commandId", commandId)
                .query((resultSet, rowNumber) -> new LastTransition(
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getObject("to_stage_id", UUID.class),
                        resultSet.getObject("current_stage_id", UUID.class),
                        resultSet.getInt("version")
                ))
                .list();
    }

    boolean moveToStage(UUID interactionId, UUID stageId, int expectedVersion, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE interactions
                SET current_stage_id = :stageId, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """)
                .param("id", interactionId)
                .param("stageId", stageId)
                .param("expectedVersion", expectedVersion)
                .param("now", now)
                .update() == 1;
    }

    List<UniversityAgreementRef> findUniversityAgreements() {
        return jdbcClient.sql("""
                SELECT id, organization_id, number, status, concluded_on, valid_until, sent_on, parties
                FROM agreements
                """)
                .query(this::mapAgreement)
                .list();
    }

    Optional<UniversityAgreementRef> findUniversityAgreement(UUID organizationId, String number) {
        return jdbcClient.sql("""
                SELECT id, organization_id, number, status, concluded_on, valid_until, sent_on, parties
                FROM agreements
                WHERE organization_id = :organizationId AND LOWER(number) = LOWER(:number)
                """)
                .param("organizationId", organizationId)
                .param("number", number)
                .query(this::mapAgreement)
                .optional();
    }

    List<ActivityRef> findActivities() {
        return jdbcClient.sql("SELECT agreement_id, title FROM agreement_activities")
                .query((resultSet, rowNumber) -> new ActivityRef(
                        resultSet.getObject("agreement_id", UUID.class),
                        resultSet.getString("title")
                ))
                .list();
    }

    boolean hasActivity(UUID agreementId, String title) {
        return jdbcClient.sql("SELECT COUNT(*) FROM agreement_activities WHERE agreement_id = :agreementId AND LOWER(title) = LOWER(:title)")
                .param("agreementId", agreementId)
                .param("title", title)
                .query(Long.class)
                .single() > 0;
    }

    List<NamedRef> findNamed(String table) {
        return jdbcClient.sql("SELECT id, name FROM " + table)
                .query((resultSet, rowNumber) -> new NamedRef(resultSet.getObject("id", UUID.class), resultSet.getString("name")))
                .list();
    }

    Optional<ImportSettings> findSettings() {
        return jdbcClient.sql("SELECT default_step_text, default_step_days FROM import_settings WHERE id = 1")
                .query((resultSet, rowNumber) -> new ImportSettings(
                        resultSet.getString("default_step_text"), resultSet.getInt("default_step_days")
                ))
                .optional();
    }

    void saveSettings(ImportSettings settings, UUID actorProfileId, OffsetDateTime now) {
        int updated = jdbcClient.sql("""
                UPDATE import_settings
                SET default_step_text = :text, default_step_days = :days, updated_by = :actor, updated_at = :now
                WHERE id = 1
                """)
                .param("text", settings.defaultStepText())
                .param("days", settings.defaultStepDays())
                .param("actor", actorProfileId)
                .param("now", now)
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO import_settings (id, default_step_text, default_step_days, updated_by, updated_at)
                    VALUES (1, :text, :days, :actor, :now)
                    """)
                    .param("text", settings.defaultStepText())
                    .param("days", settings.defaultStepDays())
                    .param("actor", actorProfileId)
                    .param("now", now)
                    .update();
        }
    }

    private UniversityAgreementRef mapAgreement(ResultSet resultSet, int rowNumber) throws SQLException {
        return new UniversityAgreementRef(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getString("number"),
                resultSet.getString("status"),
                resultSet.getObject("concluded_on", LocalDate.class),
                resultSet.getObject("valid_until", LocalDate.class),
                resultSet.getObject("sent_on", LocalDate.class),
                resultSet.getString("parties")
        );
    }

    record InteractionRef(UUID id, UUID organizationId, String title, String externalKey, UUID currentStageId, String currentStageName) {
    }

    record StageRef(UUID id, UUID interactionId, String name) {
    }

    record ImportedEvent(
            String externalKey,
            UUID interactionId,
            UUID commandId,
            String type,
            UUID stageId,
            String stageName,
            boolean transition,
            String comment,
            String nextAction,
            OffsetDateTime nextActionAt,
            String contactChannel,
            UUID actorProfileId,
            UUID ownerManagerId,
            int version,
            OffsetDateTime occurredAt,
            String importSource,
            String importAuthor
    ) {
    }

    record LastTransition(UUID interactionId, UUID toStageId, UUID currentStageId, int version) {
    }

    record UniversityAgreementRef(
            UUID id,
            UUID organizationId,
            String number,
            String status,
            LocalDate concludedOn,
            LocalDate validUntil,
            LocalDate sentOn,
            String parties
    ) {
    }

    record ActivityRef(UUID agreementId, String title) {
    }

    record NamedRef(UUID id, String name) {
    }

    record ImportSettings(String defaultStepText, int defaultStepDays) {
        static final ImportSettings DEFAULT = new ImportSettings("Связаться с вузом", 7);
    }
}
