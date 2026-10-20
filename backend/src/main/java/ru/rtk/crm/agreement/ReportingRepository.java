package ru.rtk.crm.agreement;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.agreement.AgreementModels.ActivityStatus;
import ru.rtk.crm.agreement.ReportingModels.ActivityListEvent;
import ru.rtk.crm.agreement.ReportingModels.ActivityListItem;
import ru.rtk.crm.agreement.ReportingModels.ActivityListSummary;
import ru.rtk.crm.agreement.ReportingModels.Deadline;
import ru.rtk.crm.agreement.ReportingModels.DeadlineInput;
import ru.rtk.crm.agreement.ReportingModels.ListChannel;
import ru.rtk.crm.agreement.ReportingModels.ListReminder;
import ru.rtk.crm.agreement.ReportingModels.ListStatus;
import ru.rtk.crm.agreement.ReportingModels.PeriodKind;
import ru.rtk.crm.agreement.ReportingModels.Rate;
import ru.rtk.crm.agreement.ReportingModels.ReferenceCategory;
import ru.rtk.crm.agreement.ReportingModels.ReferenceItem;
import ru.rtk.crm.agreement.ReportingModels.Settings;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;

@Repository
public class ReportingRepository {
    // Дата, на которую берётся ставка: день проведения мероприятия (окончание, иначе начало).
    static final String VALUATION_DATE = "COALESCE(ac.actual_end, ac.planned_end, ac.actual_start, ac.planned_start)";

    private static final String LIST_COLUMNS = """
            l.id, l.organization_id, o.name AS organization_name, l.title, l.period_from, l.period_to, l.period_label, l.status,
            l.review_round, l.sent_on, l.review_due_on, l.decided_on, l.version,
            (SELECT COUNT(*) FROM agreement_activity_list_items li WHERE li.list_id = l.id) AS item_count
            """;

    private final JdbcClient jdbcClient;

    public ReportingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // ---------- справочники ----------

    public List<ReferenceItem> findReferences(boolean includeArchived) {
        return jdbcClient.sql("""
                SELECT id, category, name, base_amount, sort_order, archived, version
                FROM agreement_reference_items
                %s
                ORDER BY category, sort_order, name, id
                """.formatted(includeArchived ? "" : "WHERE archived = FALSE"))
                .query(this::mapReference)
                .list();
    }

    public Optional<ReferenceItem> findReference(UUID id) {
        return jdbcClient.sql("""
                SELECT id, category, name, base_amount, sort_order, archived, version
                FROM agreement_reference_items WHERE id = :id
                """)
                .param("id", id)
                .query(this::mapReference)
                .optional();
    }

    public boolean referenceNameTaken(ReferenceCategory category, String name, UUID exceptId) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM agreement_reference_items
                WHERE category = :category AND LOWER(name) = LOWER(:name) AND (CAST(:exceptId AS UUID) IS NULL OR id <> :exceptId)
                """)
                .param("category", category.name())
                .param("name", name)
                .param("exceptId", exceptId)
                .query(Long.class)
                .single() > 0;
    }

    public void insertReference(UUID id, ReferenceCategory category, String name, BigDecimal baseAmount, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO agreement_reference_items (id, category, name, base_amount, sort_order, archived, version, created_at, updated_at)
                SELECT :id, :category, :name, CAST(:baseAmount AS NUMERIC(16, 2)), COALESCE(MAX(sort_order), 0) + 10, FALSE, 0, :now, :now
                FROM agreement_reference_items WHERE category = :category
                """)
                .param("id", id)
                .param("category", category.name())
                .param("name", name)
                .param("baseAmount", baseAmount)
                .param("now", now)
                .update();
    }

    public int updateReference(UUID id, int version, String name, boolean archived, BigDecimal baseAmount, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE agreement_reference_items
                SET name = :name, archived = :archived, base_amount = :baseAmount, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :version
                """)
                .param("id", id)
                .param("version", version)
                .param("name", name)
                .param("archived", archived)
                .param("baseAmount", baseAmount)
                .param("now", now)
                .update();
    }

    // ---------- ставки ----------

    private static final String RATE_SELECT = """
            SELECT r.id, r.kind_id, k.name AS kind_name, r.unit_id, u.name AS unit_name, r.education_level_id,
                   lvl.name AS level_name, r.effective_from, r.rate, r.version
            FROM agreement_rates r
            JOIN agreement_activity_kinds k ON k.id = r.kind_id
            JOIN agreement_reference_items u ON u.id = r.unit_id
            LEFT JOIN agreement_reference_items lvl ON lvl.id = r.education_level_id
            """;

    public List<Rate> findRates() {
        return jdbcClient.sql(RATE_SELECT + " ORDER BY k.sort_order, k.name, u.name, lvl.name NULLS FIRST, r.effective_from DESC, r.id")
                .query(this::mapRate)
                .list();
    }

    public Optional<Rate> findRate(UUID id) {
        return jdbcClient.sql(RATE_SELECT + " WHERE r.id = :id").param("id", id).query(this::mapRate).optional();
    }

    public boolean rateExists(UUID kindId, UUID unitId, UUID levelId, LocalDate effectiveFrom, UUID exceptId) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM agreement_rates
                WHERE kind_id = :kindId AND unit_id = :unitId AND effective_from = :effectiveFrom
                  AND ((CAST(:levelId AS UUID) IS NULL AND education_level_id IS NULL) OR education_level_id = :levelId)
                  AND (CAST(:exceptId AS UUID) IS NULL OR id <> :exceptId)
                """)
                .param("kindId", kindId)
                .param("unitId", unitId)
                .param("levelId", levelId)
                .param("effectiveFrom", effectiveFrom)
                .param("exceptId", exceptId)
                .query(Long.class)
                .single() > 0;
    }

    public void insertRate(UUID id, UUID kindId, UUID unitId, UUID levelId, LocalDate effectiveFrom, BigDecimal rate, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO agreement_rates (id, kind_id, unit_id, education_level_id, effective_from, rate, version, created_at, updated_at)
                VALUES (:id, :kindId, :unitId, :levelId, :effectiveFrom, :rate, 0, :now, :now)
                """)
                .param("id", id)
                .param("kindId", kindId)
                .param("unitId", unitId)
                .param("levelId", levelId)
                .param("effectiveFrom", effectiveFrom)
                .param("rate", rate)
                .param("now", now)
                .update();
    }

    public int updateRate(UUID id, int version, UUID kindId, UUID unitId, UUID levelId, LocalDate effectiveFrom, BigDecimal rate,
                          OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE agreement_rates
                SET kind_id = :kindId, unit_id = :unitId, education_level_id = :levelId, effective_from = :effectiveFrom,
                    rate = :rate, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :version
                """)
                .param("id", id)
                .param("version", version)
                .param("kindId", kindId)
                .param("unitId", unitId)
                .param("levelId", levelId)
                .param("effectiveFrom", effectiveFrom)
                .param("rate", rate)
                .param("now", now)
                .update();
    }

    public int deleteRate(UUID id, int version) {
        return jdbcClient.sql("DELETE FROM agreement_rates WHERE id = :id AND version = :version")
                .param("id", id)
                .param("version", version)
                .update();
    }

    // ---------- настройки ----------

    public Settings findSettings() {
        return jdbcClient.sql("""
                SELECT period_kind, academic_year_start_month, silence_days, repeat_silence_days, reminder_days, threshold_enabled,
                       threshold_percent, threshold_base, threshold_base_label
                FROM agreement_reporting_settings WHERE id = 1
                """)
                .query((resultSet, rowNumber) -> new Settings(
                        PeriodKind.valueOf(resultSet.getString("period_kind")),
                        resultSet.getInt("academic_year_start_month"),
                        resultSet.getObject("silence_days", Integer.class),
                        resultSet.getObject("repeat_silence_days", Integer.class),
                        resultSet.getInt("reminder_days"),
                        resultSet.getBoolean("threshold_enabled"),
                        resultSet.getBigDecimal("threshold_percent"),
                        resultSet.getBigDecimal("threshold_base"),
                        resultSet.getString("threshold_base_label")
                ))
                .optional()
                .orElse(new Settings(PeriodKind.CALENDAR, 9, null, null, 14, false, null, null, null));
    }

    public Optional<String> findColumnsJson() {
        return jdbcClient.sql("SELECT report_columns FROM agreement_reporting_settings WHERE id = 1")
                .query((resultSet, rowNumber) -> Optional.ofNullable(resultSet.getString("report_columns")))
                .optional()
                .flatMap(value -> value);
    }

    public Optional<Saved> findSaved() {
        return jdbcClient.sql("""
                SELECT s.updated_at, p.display_name FROM agreement_reporting_settings s
                JOIN crm_user_profiles p ON p.id = s.updated_by WHERE s.id = 1
                """)
                .query((resultSet, rowNumber) -> new Saved(resultSet.getObject("updated_at", OffsetDateTime.class),
                        resultSet.getString("display_name")))
                .optional();
    }

    // Строка настроек создаётся миграцией; MERGE не нужен, но на пустой базе тестов строки может не быть.
    private void ensureSettingsRow() {
        Long rows = jdbcClient.sql("SELECT COUNT(*) FROM agreement_reporting_settings WHERE id = 1").query(Long.class).single();
        if (rows == 0) {
            jdbcClient.sql("""
                    INSERT INTO agreement_reporting_settings (id, period_kind, academic_year_start_month, reminder_days, threshold_enabled)
                    VALUES (1, 'CALENDAR', 9, 14, FALSE)
                    """).update();
        }
    }

    public void saveSettings(Settings settings, UUID profileId, OffsetDateTime now) {
        ensureSettingsRow();
        jdbcClient.sql("""
                UPDATE agreement_reporting_settings
                SET period_kind = :periodKind, academic_year_start_month = :startMonth, silence_days = :silenceDays,
                    repeat_silence_days = :repeatSilenceDays, reminder_days = :reminderDays, threshold_enabled = :thresholdEnabled,
                    threshold_percent = :thresholdPercent, threshold_base = :thresholdBase, threshold_base_label = :thresholdBaseLabel,
                    updated_by = :profileId, updated_at = :now
                WHERE id = 1
                """)
                .param("periodKind", settings.periodKind().name())
                .param("startMonth", settings.academicYearStartMonth())
                .param("silenceDays", settings.silenceDays())
                .param("repeatSilenceDays", settings.repeatSilenceDays())
                .param("reminderDays", settings.reminderDays())
                .param("thresholdEnabled", settings.thresholdEnabled())
                .param("thresholdPercent", settings.thresholdPercent())
                .param("thresholdBase", settings.thresholdBase())
                .param("thresholdBaseLabel", settings.thresholdBaseLabel())
                .param("profileId", profileId)
                .param("now", now)
                .update();
    }

    public void saveColumnsJson(String json, UUID profileId, OffsetDateTime now) {
        ensureSettingsRow();
        jdbcClient.sql("UPDATE agreement_reporting_settings SET report_columns = :json, updated_by = :profileId, updated_at = :now WHERE id = 1")
                .param("json", json)
                .param("profileId", profileId)
                .param("now", now)
                .update();
    }

    public List<Deadline> findDeadlines() {
        return jdbcClient.sql("SELECT id, name, due_on, deliverable, annual FROM agreement_reporting_deadlines ORDER BY sort_order, id")
                .query((resultSet, rowNumber) -> new Deadline(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getObject("due_on", LocalDate.class),
                        resultSet.getString("deliverable"),
                        resultSet.getBoolean("annual")
                ))
                .list();
    }

    public void replaceDeadlines(List<DeadlineInput> deadlines) {
        jdbcClient.sql("DELETE FROM agreement_reporting_deadlines").update();
        int order = 10;
        for (DeadlineInput deadline : deadlines) {
            jdbcClient.sql("""
                    INSERT INTO agreement_reporting_deadlines (id, name, due_on, deliverable, annual, sort_order)
                    VALUES (:id, :name, :dueOn, :deliverable, :annual, :sortOrder)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("name", deadline.name())
                    .param("dueOn", deadline.dueOn())
                    .param("deliverable", deadline.deliverable())
                    .param("annual", Boolean.TRUE.equals(deadline.annual()))
                    .param("sortOrder", order)
                    .update();
            order += 10;
        }
    }

    // ---------- перечни ----------

    // Координатор есть, если у вуза действующий доступ к кабинету: те же условия, что у PartnerCabinetRepository.findScope.
    public boolean hasCoordinator(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM crm_user_profiles p
                JOIN organizations o ON o.id = p.partner_organization_id
                JOIN contacts c ON c.id = p.partner_contact_id AND c.organization_id = o.id
                WHERE p.partner_organization_id = :organizationId AND p.role = 'PARTNER' AND p.active = TRUE
                  AND o.status = 'ACTIVE' AND c.inactive = FALSE AND c.personal_data_status = 'ACTIVE'
                """)
                .param("organizationId", organizationId)
                .query(Long.class)
                .single() > 0;
    }

    public List<ActivityListSummary> findLists(UUID organizationId, boolean withDrafts) {
        return jdbcClient.sql("SELECT " + LIST_COLUMNS + """
                FROM agreement_activity_lists l
                JOIN organizations o ON o.id = l.organization_id
                WHERE l.organization_id = :organizationId %s
                ORDER BY l.period_from DESC, l.created_at DESC, l.id
                """.formatted(withDrafts ? "" : "AND l.status <> 'DRAFT'"))
                .param("organizationId", organizationId)
                .query(this::mapList)
                .list();
    }

    public Optional<ActivityListSummary> findList(UUID listId) {
        return jdbcClient.sql("SELECT " + LIST_COLUMNS + """
                FROM agreement_activity_lists l
                JOIN organizations o ON o.id = l.organization_id
                WHERE l.id = :id
                """)
                .param("id", listId)
                .query(this::mapList)
                .optional();
    }

    public void insertList(UUID id, UUID organizationId, String title, LocalDate from, LocalDate to, String label, UUID profileId,
                           OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO agreement_activity_lists (
                    id, organization_id, title, period_from, period_to, period_label, status, review_round, version, created_by,
                    created_at, updated_at
                ) VALUES (:id, :organizationId, :title, :from, :to, :label, 'DRAFT', 0, 0, :profileId, :now, :now)
                """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("title", title)
                .param("from", from)
                .param("to", to)
                .param("label", label)
                .param("profileId", profileId)
                .param("now", now)
                .update();
    }

    public int updateListTitle(UUID id, int version, String title, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE agreement_activity_lists SET title = :title, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :version AND status IN ('DRAFT', 'RETURNED')
                """)
                .param("id", id)
                .param("version", version)
                .param("title", title)
                .param("now", now)
                .update();
    }

    public int updateListStatus(UUID id, int version, ListStatus status, int round, LocalDate sentOn, LocalDate dueOn,
                                LocalDate decidedOn, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE agreement_activity_lists
                SET status = :status, review_round = :round, sent_on = :sentOn, review_due_on = :dueOn, decided_on = :decidedOn,
                    version = version + 1, updated_at = :now
                WHERE id = :id AND version = :version
                """)
                .param("id", id)
                .param("version", version)
                .param("status", status.name())
                .param("round", round)
                .param("sentOn", sentOn)
                .param("dueOn", dueOn)
                .param("decidedOn", decidedOn)
                .param("now", now)
                .update();
    }

    // Перечни, у которых истёк срок ответа вуза: условие по статусу в UPDATE не даёт согласовать «молчанием»
    // перечень, по которому вуз успел ответить в ту же секунду.
    public List<ActivityListSummary> findSilenceExpired(LocalDate today) {
        return jdbcClient.sql("SELECT " + LIST_COLUMNS + """
                FROM agreement_activity_lists l
                JOIN organizations o ON o.id = l.organization_id
                WHERE l.status = 'REVIEW' AND l.review_due_on IS NOT NULL AND l.review_due_on < :today
                """)
                .param("today", today)
                .query(this::mapList)
                .list();
    }

    public int approveSilently(UUID id, LocalDate decidedOn, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE agreement_activity_lists
                SET status = 'APPROVED_SILENTLY', decided_on = :decidedOn, version = version + 1, updated_at = :now
                WHERE id = :id AND status = 'REVIEW'
                """)
                .param("id", id)
                .param("decidedOn", decidedOn)
                .param("now", now)
                .update();
    }

    public List<ActivityListItem> findItems(UUID listId) {
        return jdbcClient.sql("""
                SELECT activity_id, agreement_number, kind_name, title, education_level, executor, unit, planned_volume, participants,
                       cost, period_start, period_end
                FROM agreement_activity_list_items WHERE list_id = :listId ORDER BY item_order
                """)
                .param("listId", listId)
                .query((resultSet, rowNumber) -> new ActivityListItem(
                        resultSet.getObject("activity_id", UUID.class),
                        resultSet.getString("agreement_number"),
                        resultSet.getString("kind_name"),
                        resultSet.getString("title"),
                        resultSet.getString("education_level"),
                        resultSet.getString("executor"),
                        resultSet.getString("unit"),
                        resultSet.getObject("planned_volume", Integer.class),
                        resultSet.getObject("participants", Integer.class),
                        resultSet.getBigDecimal("cost"),
                        resultSet.getObject("period_start", LocalDate.class),
                        resultSet.getObject("period_end", LocalDate.class)
                ))
                .list();
    }

    // Кандидаты в перечень: мероприятия договоров вуза, пересекающиеся с периодом, кроме отменённых и отклонённых,
    // и кроме договоров «Расторгнут». Период мероприятия — тот же расчёт, что у подтверждений и отчёта.
    public List<ActivityListItem> findPlanItems(UUID organizationId, LocalDate from, LocalDate to, List<UUID> activityIds) {
        List<String> conditions = new ArrayList<>(List.of(
                "ag.organization_id = :organizationId",
                "ag.status <> 'TERMINATED'",
                "ac.status NOT IN ('CANCELLED', 'REJECTED')"
        ));
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("organizationId", organizationId);
        AgreementRepository.periodCondition(conditions, parameters, from, to);
        if (activityIds != null) {
            if (activityIds.isEmpty()) {
                return List.of();
            }
            conditions.add("ac.id IN (:activityIds)");
            parameters.put("activityIds", activityIds);
        }
        return jdbcClient.sql("""
                SELECT ac.id, ag.number, k.name AS kind_name, ac.title, lvl.name AS level_name, ex.name AS executor_name,
                       COALESCE(u.name, ac.unit) AS unit, ac.planned_volume, ac.participants, ac.cost,
                       %s AS activity_start, %s AS activity_end
                FROM agreement_activities ac
                JOIN agreements ag ON ag.id = ac.agreement_id
                JOIN agreement_activity_kinds k ON k.id = ac.kind_id
                LEFT JOIN agreement_reference_items lvl ON lvl.id = ac.education_level_id
                LEFT JOIN agreement_reference_items ex ON ex.id = ac.executor_id
                LEFT JOIN agreement_reference_items u ON u.id = ac.unit_id
                WHERE %s
                ORDER BY ag.number, k.sort_order, k.name, ac.title, ac.id
                """.formatted(AgreementRepository.ACTIVITY_START, AgreementRepository.ACTIVITY_END, String.join(" AND ", conditions)))
                .params(parameters)
                .query((resultSet, rowNumber) -> new ActivityListItem(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("number"),
                        resultSet.getString("kind_name"),
                        resultSet.getString("title"),
                        resultSet.getString("level_name"),
                        resultSet.getString("executor_name"),
                        resultSet.getString("unit"),
                        resultSet.getObject("planned_volume", Integer.class),
                        resultSet.getObject("participants", Integer.class),
                        resultSet.getBigDecimal("cost"),
                        resultSet.getObject("activity_start", LocalDate.class),
                        resultSet.getObject("activity_end", LocalDate.class)
                ))
                .list();
    }

    public void replaceItems(UUID listId, List<ActivityListItem> items) {
        jdbcClient.sql("DELETE FROM agreement_activity_list_items WHERE list_id = :listId").param("listId", listId).update();
        int order = 1;
        for (ActivityListItem item : items) {
            jdbcClient.sql("""
                    INSERT INTO agreement_activity_list_items (
                        list_id, activity_id, item_order, agreement_number, kind_name, title, education_level, executor, unit,
                        planned_volume, participants, cost, period_start, period_end
                    ) VALUES (
                        :listId, :activityId, :itemOrder, :agreementNumber, :kindName, :title, :educationLevel, :executor, :unit,
                        :plannedVolume, :participants, :cost, :periodStart, :periodEnd
                    )
                    """)
                    .param("listId", listId)
                    .param("activityId", item.activityId())
                    .param("itemOrder", order++)
                    .param("agreementNumber", item.agreementNumber())
                    .param("kindName", item.kindName())
                    .param("title", item.title())
                    .param("educationLevel", item.educationLevel())
                    .param("executor", item.executor())
                    .param("unit", item.unit())
                    .param("plannedVolume", item.plannedVolume())
                    .param("participants", item.participants())
                    .param("cost", item.cost())
                    .param("periodStart", item.periodStart())
                    .param("periodEnd", item.periodEnd())
                    .update();
        }
    }

    public void insertEvent(UUID listId, ListStatus from, ListStatus to, ListChannel channel, String comment, UUID actorId,
                            OffsetDateTime occurredAt) {
        jdbcClient.sql("""
                INSERT INTO agreement_activity_list_events (id, list_id, from_status, to_status, channel, comment, actor_profile_id, occurred_at)
                VALUES (:id, :listId, :fromStatus, :toStatus, :channel, :comment, :actorId, :occurredAt)
                """)
                .param("id", UUID.randomUUID())
                .param("listId", listId)
                .param("fromStatus", from == null ? null : from.name())
                .param("toStatus", to.name())
                .param("channel", channel.name())
                .param("comment", comment)
                .param("actorId", actorId)
                .param("occurredAt", occurredAt)
                .update();
    }

    public List<ActivityListEvent> findEvents(UUID listId) {
        return jdbcClient.sql("""
                SELECT e.id, e.from_status, e.to_status, e.channel, e.comment, p.display_name, e.occurred_at
                FROM agreement_activity_list_events e
                LEFT JOIN crm_user_profiles p ON p.id = e.actor_profile_id
                WHERE e.list_id = :listId
                ORDER BY e.occurred_at DESC, e.id
                """)
                .param("listId", listId)
                .query((resultSet, rowNumber) -> new ActivityListEvent(
                        resultSet.getObject("id", UUID.class),
                        listStatus(resultSet.getString("from_status")),
                        ListStatus.valueOf(resultSet.getString("to_status")),
                        ListChannel.valueOf(resultSet.getString("channel")),
                        resultSet.getString("comment"),
                        resultSet.getString("display_name"),
                        resultSet.getObject("occurred_at", OffsetDateTime.class)
                ))
                .list();
    }

    public List<ListReminder> findListReminders(VisibilityScope scope, int limit) {
        return jdbcClient.sql("""
                SELECT l.id, l.organization_id, o.name AS organization_name, l.title, l.status, l.review_due_on
                FROM agreement_activity_lists l
                JOIN organizations o ON o.id = l.organization_id
                WHERE l.status IN ('REVIEW', 'RETURNED') AND o.id IN (SELECT id FROM organizations WHERE %s)
                ORDER BY CASE l.status WHEN 'RETURNED' THEN 0 ELSE 1 END, l.review_due_on NULLS LAST, o.name, l.id
                LIMIT :limit
                """.formatted(scope.condition()))
                .params(scope.parameters())
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new ListReminder(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getString("title"),
                        ListStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("review_due_on", LocalDate.class)
                ))
                .list();
    }

    // ---------- отчёт ----------

    public List<ReportActivity> findReportActivities(VisibilityScope scope, LocalDate from, LocalDate to, UUID organizationId) {
        List<String> conditions = new ArrayList<>(List.of(
                "ag.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")",
                "ac.status NOT IN ('CANCELLED', 'REJECTED')"
        ));
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        if (organizationId != null) {
            conditions.add("ag.organization_id = :organizationId");
            parameters.put("organizationId", organizationId);
        }
        AgreementRepository.periodCondition(conditions, parameters, from, to);
        return jdbcClient.sql("""
                SELECT o.id AS organization_id, o.name AS organization_name, ag.number, ag.concluded_on,
                       k.id AS kind_id, k.name AS kind_name, ac.id, ac.title, ac.status,
                       ac.education_level_id, lvl.name AS level_name, ac.executor_id, ex.name AS executor_name,
                       ac.unit_id, COALESCE(u.name, ac.unit) AS unit, ac.planned_volume, ac.actual_volume, ac.participants, ac.cost,
                       %s AS activity_start, %s AS activity_end, %s AS valuation_date,
                       rp.display_name AS responsible_name, ac.document_note
                FROM agreement_activities ac
                JOIN agreements ag ON ag.id = ac.agreement_id
                JOIN organizations o ON o.id = ag.organization_id
                JOIN agreement_activity_kinds k ON k.id = ac.kind_id
                LEFT JOIN agreement_reference_items lvl ON lvl.id = ac.education_level_id
                LEFT JOIN agreement_reference_items ex ON ex.id = ac.executor_id
                LEFT JOIN agreement_reference_items u ON u.id = ac.unit_id
                LEFT JOIN crm_user_profiles rp ON rp.id = ac.responsible_profile_id
                WHERE %s
                ORDER BY o.name, ag.number, k.sort_order, k.name, ac.title, ac.id
                """.formatted(AgreementRepository.ACTIVITY_START, AgreementRepository.ACTIVITY_END, VALUATION_DATE,
                        String.join(" AND ", conditions)))
                .params(parameters)
                .query((resultSet, rowNumber) -> new ReportActivity(
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getString("number"),
                        resultSet.getObject("concluded_on", LocalDate.class),
                        resultSet.getObject("kind_id", UUID.class),
                        resultSet.getString("kind_name"),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("title"),
                        ActivityStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("education_level_id", UUID.class),
                        resultSet.getString("level_name"),
                        resultSet.getObject("executor_id", UUID.class),
                        resultSet.getString("executor_name"),
                        resultSet.getObject("unit_id", UUID.class),
                        resultSet.getString("unit"),
                        resultSet.getObject("planned_volume", Integer.class),
                        resultSet.getObject("actual_volume", Integer.class),
                        resultSet.getObject("participants", Integer.class),
                        resultSet.getBigDecimal("cost"),
                        resultSet.getObject("activity_start", LocalDate.class),
                        resultSet.getObject("activity_end", LocalDate.class),
                        resultSet.getObject("valuation_date", LocalDate.class),
                        resultSet.getString("responsible_name"),
                        resultSet.getString("document_note")
                ))
                .list();
    }

    public Map<UUID, List<String>> findCleanAttachmentNames(List<UUID> activityIds) {
        Map<UUID, List<String>> names = new HashMap<>();
        if (activityIds.isEmpty()) {
            return names;
        }
        jdbcClient.sql("""
                SELECT aa.activity_id, a.original_name
                FROM agreement_activity_attachments aa
                JOIN attachments a ON a.id = aa.attachment_id
                WHERE aa.activity_id IN (:ids) AND a.status = 'CLEAN' AND a.deleted_at IS NULL
                ORDER BY a.created_at, a.id
                """)
                .param("ids", activityIds)
                .query((ResultSet resultSet) -> {
                    names.computeIfAbsent(resultSet.getObject("activity_id", UUID.class), key -> new ArrayList<>())
                            .add(resultSet.getString("original_name"));
                });
        return names;
    }

    public Map<UUID, BigDecimal> findExecutorBases() {
        Map<UUID, BigDecimal> bases = new HashMap<>();
        jdbcClient.sql("SELECT id, base_amount FROM agreement_reference_items WHERE category = 'EXECUTOR' AND base_amount IS NOT NULL")
                .query((ResultSet resultSet) -> {
                    bases.put(resultSet.getObject("id", UUID.class), resultSet.getBigDecimal("base_amount"));
                });
        return bases;
    }

    private ReferenceItem mapReference(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ReferenceItem(
                resultSet.getObject("id", UUID.class),
                ReferenceCategory.valueOf(resultSet.getString("category")),
                resultSet.getString("name"),
                resultSet.getBigDecimal("base_amount"),
                resultSet.getInt("sort_order"),
                resultSet.getBoolean("archived"),
                resultSet.getInt("version")
        );
    }

    private Rate mapRate(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Rate(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("kind_id", UUID.class),
                resultSet.getString("kind_name"),
                resultSet.getObject("unit_id", UUID.class),
                resultSet.getString("unit_name"),
                resultSet.getObject("education_level_id", UUID.class),
                resultSet.getString("level_name"),
                resultSet.getObject("effective_from", LocalDate.class),
                resultSet.getBigDecimal("rate"),
                resultSet.getInt("version")
        );
    }

    private ActivityListSummary mapList(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ActivityListSummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getString("organization_name"),
                resultSet.getString("title"),
                resultSet.getObject("period_from", LocalDate.class),
                resultSet.getObject("period_to", LocalDate.class),
                resultSet.getString("period_label"),
                ListStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("review_round"),
                resultSet.getObject("sent_on", LocalDate.class),
                resultSet.getObject("review_due_on", LocalDate.class),
                resultSet.getObject("decided_on", LocalDate.class),
                resultSet.getInt("item_count"),
                resultSet.getInt("version")
        );
    }

    private static ListStatus listStatus(String value) {
        return value == null ? null : ListStatus.valueOf(value);
    }

    public record Saved(OffsetDateTime updatedAt, String updatedByName) {
    }

    public record ReportActivity(
            UUID organizationId,
            String organizationName,
            String agreementNumber,
            LocalDate agreementConcludedOn,
            UUID kindId,
            String kindName,
            UUID id,
            String title,
            ActivityStatus status,
            UUID educationLevelId,
            String educationLevelName,
            UUID executorId,
            String executorName,
            UUID unitId,
            String unit,
            Integer plannedVolume,
            Integer actualVolume,
            Integer participants,
            BigDecimal cost,
            LocalDate start,
            LocalDate end,
            LocalDate valuationDate,
            String responsibleName,
            String documentNote
    ) {
    }
}
