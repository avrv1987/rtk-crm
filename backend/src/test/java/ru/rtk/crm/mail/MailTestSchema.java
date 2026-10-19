package ru.rtk.crm.mail;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

// Схема тестов модуля: таблицы CRM, которые читает модуль, и настоящая миграция V55 — она проверяется здесь же.
final class MailTestSchema {
    private static final List<String> TABLES = List.of(
            "agent_change_plans", "mail_outbox", "mail_preferences", "mail_invitations", "audit_events", "crm_profile_events", "command_idempotency_records",
            "organization_assignment_events", "agreement_status_events", "agreements", "source_records", "interaction_issues",
            "interactions", "sync_runs", "crm_exchange_events", "organizations", "crm_user_profiles", "teams"
    );

    private MailTestSchema() {
    }

    static void reset(JdbcTemplate jdbcTemplate) {
        new ResourceDatabasePopulator(new ClassPathResource("ru/rtk/crm/mail/schema.sql")).execute(jdbcTemplate.getDataSource());
        Integer migrated = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'mail_settings'", Integer.class);
        if (migrated == null || migrated == 0) {
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V55__mail.sql")).execute(jdbcTemplate.getDataSource());
        }
        for (String table : TABLES) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("""
                UPDATE mail_settings SET mode = 'STUB', host = NULL, port = NULL, security = 'STARTTLS', username = NULL,
                    password_encrypted = NULL, from_address = 'crm@localhost', from_name = 'CRM ИТ Школы РТК', checked_at = NULL,
                    check_ok = NULL, check_message = NULL, events_collected_until = NULL
                """);
    }

    static void profile(JdbcTemplate jdbcTemplate, UUID id, String name, String role, UUID team) {
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, login, role, team_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, MailHttpTest.ISSUER, "kc-" + id, name, "login-" + id.toString().substring(34), role, team);
    }

    // Почта учётки в Keycloak для теста: стабильная строка из идентификатора учётки.
    static String email(String subject) {
        return subject.equals("kc-petrova") ? "petrova@example.test" : "a" + subject.substring(subject.length() - 4) + "@example.test";
    }

    static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
