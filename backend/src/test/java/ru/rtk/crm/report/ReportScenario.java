package ru.rtk.crm.report;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import ru.rtk.crm.access.CrmProfile;

// Сценарий отчётов для тестов из других пакетов (сверка инструментов MCP с экраном): те же данные, что у ReportTestData.
public final class ReportScenario {
    public static final UUID TEAM_A = ReportTestData.TEAM_A;
    public static final UUID TEAM_B = ReportTestData.TEAM_B;
    public static final UUID MANAGER_A = ReportTestData.MANAGER_A;
    public static final UUID MANAGER_A2 = ReportTestData.MANAGER_A2;
    public static final UUID ORGANIZATION_A = ReportTestData.ORGANIZATION_A;
    public static final UUID ORGANIZATION_A_UNASSIGNED = ReportTestData.ORGANIZATION_A_UNASSIGNED;
    public static final UUID PROGRAM = ReportTestData.PROGRAM;
    public static final UUID DIRECTION = ReportTestData.DIRECTION;
    public static final UUID PRODUCT_Y = ReportTestData.PRODUCT_Y;
    public static final UUID INTERACTION_TWO_PRODUCTS = ReportTestData.INTERACTION_TWO_PRODUCTS;
    public static final UUID INTERACTION_WITHOUT_LINKS = ReportTestData.INTERACTION_WITHOUT_LINKS;
    public static final UUID INTERACTION_UNASSIGNED = ReportTestData.INTERACTION_UNASSIGNED;
    public static final CrmProfile MANAGER_A_PROFILE = ReportTestData.MANAGER_A_PROFILE;
    public static final CrmProfile LEADER_A_PROFILE = ReportTestData.LEADER_A_PROFILE;

    private ReportScenario() {
    }

    // Схема и данные как в ManagerWorkControlTest, включая таблицы договоров, которые читает «Контроль».
    public static void prepare(JdbcTemplate jdbc) {
        ReportTestData.createSchema(jdbc);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreements (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, number VARCHAR(100) NOT NULL, status VARCHAR(16) NOT NULL,
                    status_changed_at TIMESTAMP WITH TIME ZONE NOT NULL, valid_until DATE, sent_on DATE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_status_events (
                    id UUID PRIMARY KEY, agreement_id UUID NOT NULL, from_status VARCHAR(16), to_status VARCHAR(16) NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_interactions (
                    agreement_id UUID NOT NULL, interaction_id UUID NOT NULL, PRIMARY KEY (agreement_id, interaction_id)
                )
                """);
        jdbc.update("DELETE FROM agreement_interactions");
        jdbc.update("DELETE FROM agreement_status_events");
        jdbc.update("DELETE FROM agreements");
        ReportTestData.insertScenario(jdbc);
    }

    public static void insertDemand(JdbcTemplate jdbc) {
        ReportTestData.insertDemand(jdbc);
    }

    public static void organization(JdbcTemplate jdbc, UUID id, String name, UUID teamId, UUID ownerId) {
        ReportTestData.organization(jdbc, id, name, teamId, ownerId);
    }

    public static void assignment(JdbcTemplate jdbc, UUID organizationId, UUID previousOwnerId, String previousOwnerName, UUID ownerId,
                                  String ownerName, OffsetDateTime occurredAt) {
        ReportTestData.assignment(jdbc, organizationId, previousOwnerId, previousOwnerName, ownerId, ownerName, occurredAt);
    }
}
