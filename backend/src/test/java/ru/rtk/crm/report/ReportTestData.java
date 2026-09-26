package ru.rtk.crm.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;

final class ReportTestData {
    static final UUID TEAM_A = uuid(1);
    static final UUID TEAM_B = uuid(2);
    static final UUID MANAGER_A = uuid(11);
    static final UUID MANAGER_A2 = uuid(12);
    static final UUID MANAGER_B = uuid(13);
    static final UUID LEADER_A = uuid(14);
    static final UUID ADMIN = uuid(15);
    static final UUID ORGANIZATION_A = uuid(101);
    static final UUID ORGANIZATION_A_UNASSIGNED = uuid(102);
    static final UUID ORGANIZATION_B = uuid(103);
    static final UUID DIRECTION = uuid(201);
    static final UUID PROGRAM = uuid(301);
    static final UUID PROGRAM_DATA = uuid(302);
    static final UUID PRODUCT_X = uuid(401);
    static final UUID PRODUCT_Y = uuid(402);
    static final UUID INTERACTION_TWO_PRODUCTS = uuid(501);
    static final UUID INTERACTION_WITHOUT_LINKS = uuid(502);
    static final UUID INTERACTION_UNASSIGNED = uuid(503);
    static final UUID INTERACTION_FOREIGN = uuid(504);
    static final LocalDate TODAY = LocalDate.now(ReportRequest.ZONE);
    static final LocalDate RUN_ENDS = TODAY.plusDays(180);

    static final CrmProfile MANAGER_A_PROFILE = new CrmProfile(MANAGER_A, UserRole.USER, TEAM_A, 0);
    static final CrmProfile MANAGER_B_PROFILE = new CrmProfile(MANAGER_B, UserRole.USER, TEAM_B, 0);
    static final CrmProfile LEADER_A_PROFILE = new CrmProfile(LEADER_A, UserRole.LEADER, TEAM_A, 0);
    static final CrmProfile ADMIN_PROFILE = new CrmProfile(ADMIN, UserRole.ADMIN, TEAM_A, 0);

    static final String FORMULA_COMMENT = "=HYPERLINK(\"http://example.invalid\",\"Открыть\")";

    private ReportTestData() {
    }

    static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS crm_user_profiles (
                    id UUID PRIMARY KEY,
                    display_name VARCHAR(200) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    team_id UUID,
                    active BOOLEAN NOT NULL,
                    access_revision INTEGER NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY,
                    name VARCHAR(300) NOT NULL,
                    type VARCHAR(16) NOT NULL,
                    team_id UUID NOT NULL,
                    owner_manager_id UUID,
                    version INTEGER NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS directions (
                    id UUID PRIMARY KEY,
                    name VARCHAR(200) NOT NULL,
                    archived BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS programs (
                    id UUID PRIMARY KEY,
                    direction_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    archived BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS products (
                    id UUID PRIMARY KEY,
                    name VARCHAR(200) NOT NULL,
                    archived BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interactions (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    title VARCHAR(200) NOT NULL,
                    current_stage_id UUID NOT NULL,
                    next_action VARCHAR(500),
                    next_action_at TIMESTAMP WITH TIME ZONE,
                    program_id UUID,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interaction_stages (
                    id UUID PRIMARY KEY,
                    interaction_id UUID NOT NULL,
                    stage_order INTEGER NOT NULL,
                    name VARCHAR(200) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS product_agreements (
                    id UUID PRIMARY KEY,
                    interaction_id UUID NOT NULL,
                    product_id UUID NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interaction_events (
                    id UUID PRIMARY KEY,
                    interaction_id UUID NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    stage_name_snapshot VARCHAR(200) NOT NULL,
                    from_stage_name_snapshot VARCHAR(200),
                    comment TEXT,
                    actor_profile_id UUID NOT NULL,
                    owner_manager_id_snapshot UUID,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS report_jobs (
                    id UUID PRIMARY KEY,
                    owner_profile_id UUID NOT NULL,
                    owner_role VARCHAR(16) NOT NULL,
                    owner_team_id UUID,
                    owner_access_revision INTEGER NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    request_fingerprint CHAR(64) NOT NULL,
                    request_json TEXT NOT NULL,
                    kind VARCHAR(16) NOT NULL,
                    format VARCHAR(8) NOT NULL,
                    group_by VARCHAR(16),
                    status VARCHAR(16) NOT NULL,
                    progress INTEGER NOT NULL DEFAULT 0,
                    row_count INTEGER,
                    error_code VARCHAR(64),
                    error_message VARCHAR(500),
                    result_storage_key UUID UNIQUE,
                    result_file_name VARCHAR(255),
                    result_size_bytes BIGINT,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    started_at TIMESTAMP WITH TIME ZONE,
                    finished_at TIMESTAMP WITH TIME ZONE,
                    CONSTRAINT report_jobs_owner_idempotency_key UNIQUE (owner_profile_id, idempotency_key),
                    CONSTRAINT report_jobs_chart_format CHECK (
                        (group_by IS NULL AND format <> 'PNG') OR (group_by IS NOT NULL AND format IN ('PNG', 'PDF'))
                    )
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS source_records (
                    id UUID PRIMARY KEY,
                    source VARCHAR(16) NOT NULL,
                    record_type VARCHAR(64) NOT NULL,
                    external_id VARCHAR(200) NOT NULL,
                    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    organization_id UUID,
                    program_id UUID,
                    applications_count INTEGER NOT NULL DEFAULT 1
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS learning_snapshots (
                    source_record_id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    program_id UUID NOT NULL,
                    course_id BIGINT NOT NULL,
                    group_id BIGINT,
                    course_name VARCHAR(1333) NOT NULL,
                    group_name VARCHAR(300),
                    participants_count INTEGER NOT NULL,
                    teachers_count INTEGER NOT NULL,
                    completed_count INTEGER,
                    not_completed_count INTEGER,
                    unknown_count INTEGER NOT NULL,
                    groups_count INTEGER NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    sync_run_id UUID
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS source_mappings (
                    id UUID PRIMARY KEY,
                    source VARCHAR(16) NOT NULL,
                    kind VARCHAR(16) NOT NULL,
                    external_key VARCHAR(310) NOT NULL,
                    organization_id UUID,
                    program_id UUID,
                    run_starts_on DATE,
                    run_ends_on DATE
                )
                """);
        for (String table : new String[]{
                "learning_snapshots", "source_mappings", "source_records", "report_jobs", "interaction_events", "product_agreements", "interaction_stages", "interactions",
                "products", "programs", "directions", "organizations", "crm_user_profiles"
        }) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    static void insertScenario(JdbcTemplate jdbc) {
        profile(jdbc, MANAGER_A, "Анна Кузнецова", "USER", TEAM_A);
        profile(jdbc, MANAGER_A2, "Борис Смирнов", "USER", TEAM_A);
        profile(jdbc, MANAGER_B, "Вера Орлова", "USER", TEAM_B);
        profile(jdbc, LEADER_A, "Галина Лебедева", "LEADER", TEAM_A);
        profile(jdbc, ADMIN, "Дмитрий Администратор", "ADMIN", TEAM_A);

        organization(jdbc, ORGANIZATION_A, "Университет «Альфа»", TEAM_A, MANAGER_A);
        organization(jdbc, ORGANIZATION_A_UNASSIGNED, "Институт без КАМ", TEAM_A, null);
        organization(jdbc, ORGANIZATION_B, "Университет «Бета»", TEAM_B, MANAGER_B);

        jdbc.update("INSERT INTO directions (id, name) VALUES (?, ?)", DIRECTION, "Программирование");
        jdbc.update("INSERT INTO programs (id, direction_id, name) VALUES (?, ?, ?)", PROGRAM, DIRECTION, "Java-разработчик");
        jdbc.update("INSERT INTO products (id, name) VALUES (?, ?)", PRODUCT_X, "Продукт Икс");
        jdbc.update("INSERT INTO products (id, name) VALUES (?, ?)", PRODUCT_Y, "Продукт Игрек");

        interaction(jdbc, INTERACTION_TWO_PRODUCTS, ORGANIZATION_A, "Договор на курс Java", PROGRAM, 2,
                at("2026-08-10T10:00:00+03:00"));
        productAgreement(jdbc, INTERACTION_TWO_PRODUCTS, PRODUCT_X);
        productAgreement(jdbc, INTERACTION_TWO_PRODUCTS, PRODUCT_Y);
        event(jdbc, INTERACTION_TWO_PRODUCTS, "CREATED", "Поиск контакта", null, null, MANAGER_A2, at("2026-08-10T10:00:00+03:00"));
        event(jdbc, INTERACTION_TWO_PRODUCTS, "COMMENTED", "Поиск контакта", null, "Перед периодом", MANAGER_A,
                at("2026-08-31T23:59:00+03:00"));
        event(jdbc, INTERACTION_TWO_PRODUCTS, "TRANSITIONED", "Встреча", "Поиск контакта", "Назначена встреча", MANAGER_A,
                at("2026-09-05T12:00:00+03:00"));
        event(jdbc, INTERACTION_TWO_PRODUCTS, "COMMENTED", "Встреча", null, FORMULA_COMMENT, MANAGER_A,
                at("2026-09-10T12:00:00+03:00"));
        event(jdbc, INTERACTION_TWO_PRODUCTS, "COMMENTED", "Встреча", null, "После периода", MANAGER_A,
                at("2026-10-01T00:00:00+03:00"));
        jdbc.update("UPDATE interactions SET next_action = ?, next_action_at = ? WHERE id = ?",
                "Отправить договор", at("2026-10-05T10:00:00+03:00"), INTERACTION_TWO_PRODUCTS);

        interaction(jdbc, INTERACTION_WITHOUT_LINKS, ORGANIZATION_A, "Первое обращение", null, 0,
                at("2026-09-01T00:00:00+03:00"));
        event(jdbc, INTERACTION_WITHOUT_LINKS, "CREATED", "Поиск контакта", null, null, MANAGER_A,
                at("2026-09-01T00:00:00+03:00"));

        interaction(jdbc, INTERACTION_UNASSIGNED, ORGANIZATION_A_UNASSIGNED, "Запрос на обучение", PROGRAM, 0,
                at("2026-09-15T09:00:00+03:00"));
        productAgreement(jdbc, INTERACTION_UNASSIGNED, PRODUCT_X);
        event(jdbc, INTERACTION_UNASSIGNED, "CREATED", "Поиск контакта", null, null, LEADER_A, at("2026-09-15T09:00:00+03:00"));

        interaction(jdbc, INTERACTION_FOREIGN, ORGANIZATION_B, "Чужое взаимодействие", PROGRAM, 0,
                at("2026-09-03T09:00:00+03:00"));
        productAgreement(jdbc, INTERACTION_FOREIGN, PRODUCT_X);
        event(jdbc, INTERACTION_FOREIGN, "CREATED", "Поиск контакта", null, null, MANAGER_B, at("2026-09-03T09:00:00+03:00"));
    }

    static void insertDemand(JdbcTemplate jdbc) {
        demand(jdbc, "la-a1", "learning_application", "APPLIED", ORGANIZATION_A, PROGRAM, 3, "2026-09-05T10:00:00+03:00");
        demand(jdbc, "la-a2", "learning_application", "APPLIED", ORGANIZATION_A, null, 4, "2026-09-06T10:00:00+03:00");
        demand(jdbc, "la-u1", "learning_application", "APPLIED", ORGANIZATION_A_UNASSIGNED, PROGRAM, 2,
                "2026-08-20T10:00:00+03:00");
        demand(jdbc, "la-b1", "learning_application", "APPLIED", ORGANIZATION_B, PROGRAM, 7, "2026-09-07T10:00:00+03:00");
        demand(jdbc, "la-x1", "learning_application", "NEEDS_MAPPING", null, PROGRAM, 4, "2026-09-08T10:00:00+03:00");
        demand(jdbc, "la-a3", "learning_application", "SKIPPED", ORGANIZATION_A, PROGRAM, 10, "2026-09-09T10:00:00+03:00");
        demand(jdbc, "pr-a1", "partnership_request", "APPLIED", ORGANIZATION_A, PROGRAM, 1, "2026-09-10T10:00:00+03:00");
        jdbc.update("INSERT INTO programs (id, direction_id, name) VALUES (?, ?, ?)", PROGRAM_DATA, DIRECTION, "Анализ данных");
        learning(jdbc, 2, null, ORGANIZATION_A, PROGRAM, 6, TODAY.minusDays(30), RUN_ENDS);
        learning(jdbc, 5, 51L, ORGANIZATION_A, PROGRAM, 2, TODAY.minusDays(30), RUN_ENDS);
        learning(jdbc, 6, null, ORGANIZATION_A, PROGRAM, 5, null, null);
        learning(jdbc, 3, null, ORGANIZATION_B, PROGRAM, 4, TODAY.minusDays(30), RUN_ENDS);
        learning(jdbc, 4, null, ORGANIZATION_A_UNASSIGNED, PROGRAM_DATA, 3, TODAY.plusDays(10), TODAY.plusDays(200));
    }

    private static void learning(JdbcTemplate jdbc, long courseId, Long groupId, UUID organizationId, UUID programId,
                                 int participants, LocalDate runStartsOn, LocalDate runEndsOn) {
        UUID recordId = UUID.randomUUID();
        String externalId = groupId == null ? Long.toString(courseId) : courseId + ":" + groupId;
        jdbc.update("""
                INSERT INTO source_records (
                    id, source, record_type, external_id, submitted_at, status, organization_id, program_id
                ) VALUES (?, 'MOODLE', ?, ?, CURRENT_TIMESTAMP, 'APPLIED', ?, ?)
                """, recordId, groupId == null ? "moodle_course" : "moodle_group", externalId, organizationId, programId);
        jdbc.update("""
                INSERT INTO source_mappings (
                    id, source, kind, external_key, organization_id, program_id, run_starts_on, run_ends_on
                ) VALUES (?, 'MOODLE', ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), groupId == null ? "COURSE" : "GROUP", externalId, organizationId, programId, runStartsOn,
                runEndsOn);
        jdbc.update("""
                INSERT INTO learning_snapshots (
                    source_record_id, organization_id, program_id, course_id, group_id, course_name, participants_count,
                    teachers_count, completed_count, not_completed_count, unknown_count, groups_count, observed_at, changed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, NULL, NULL, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, recordId, organizationId, programId, courseId, groupId, "Курс " + courseId, participants, participants);
    }

    private static void demand(
            JdbcTemplate jdbc,
            String externalId,
            String type,
            String status,
            UUID organizationId,
            UUID programId,
            int applications,
            String submittedAt
    ) {
        jdbc.update("""
                INSERT INTO source_records (
                    id, source, record_type, external_id, submitted_at, status, organization_id, program_id, applications_count
                ) VALUES (?, 'WEBSITE', ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), type, externalId, at(submittedAt), status, organizationId, programId, applications);
    }

    static OffsetDateTime at(String value) {
        return OffsetDateTime.parse(value);
    }

    private static void profile(JdbcTemplate jdbc, UUID id, String name, String role, UUID teamId) {
        jdbc.update("""
                INSERT INTO crm_user_profiles (id, display_name, role, team_id, active, access_revision)
                VALUES (?, ?, ?, ?, TRUE, 0)
                """, id, name, role, teamId);
    }

    private static void organization(JdbcTemplate jdbc, UUID id, String name, UUID teamId, UUID ownerId) {
        jdbc.update("""
                INSERT INTO organizations (id, name, type, team_id, owner_manager_id)
                VALUES (?, ?, 'UNIVERSITY', ?, ?)
                """, id, name, teamId, ownerId);
    }

    private static void interaction(
            JdbcTemplate jdbc,
            UUID id,
            UUID organizationId,
            String title,
            UUID programId,
            int currentStageOrder,
            OffsetDateTime createdAt
    ) {
        String[] stages = {"Поиск контакта", "Уточнение актуальности", "Встреча"};
        UUID current = null;
        for (int order = 0; order < stages.length; order++) {
            UUID stageId = UUID.randomUUID();
            jdbc.update("INSERT INTO interaction_stages (id, interaction_id, stage_order, name) VALUES (?, ?, ?, ?)",
                    stageId, id, order, stages[order]);
            if (order == currentStageOrder) {
                current = stageId;
            }
        }
        jdbc.update("""
                INSERT INTO interactions (id, organization_id, title, current_stage_id, program_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, organizationId, title, current, programId, createdAt);
    }

    private static void productAgreement(JdbcTemplate jdbc, UUID interactionId, UUID productId) {
        jdbc.update("INSERT INTO product_agreements (id, interaction_id, product_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), interactionId, productId);
    }

    static void event(
            JdbcTemplate jdbc,
            UUID interactionId,
            String type,
            String stageName,
            String fromStageName,
            String comment,
            UUID actorId,
            OffsetDateTime occurredAt
    ) {
        UUID owner = jdbc.queryForObject("""
                SELECT o.owner_manager_id FROM organizations o JOIN interactions i ON i.organization_id = o.id WHERE i.id = ?
                """, UUID.class, interactionId);
        UUID snapshot = type.equals("CREATED") && interactionId.equals(INTERACTION_TWO_PRODUCTS) ? MANAGER_A2 : owner;
        jdbc.update("""
                INSERT INTO interaction_events (
                    id, interaction_id, type, stage_name_snapshot, from_stage_name_snapshot, comment,
                    actor_profile_id, owner_manager_id_snapshot, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), interactionId, type, stageName, fromStageName, comment, actorId, snapshot, occurredAt);
    }

    private static UUID uuid(int value) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
    }
}
