package ru.rtk.crm.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class AgreementPlanMigrationTest {
    @Test
    void existingAgreementsHaveNoPlanAndKindWithDateAreStoredOnlyTogether() throws IOException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:v36-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE agreements (id UUID PRIMARY KEY, valid_until DATE, status VARCHAR(16) NOT NULL)");
        UUID existing = UUID.randomUUID();
        jdbc.update("INSERT INTO agreements (id, valid_until, status) VALUES (?, ?, 'ACTIVE')", existing, LocalDate.parse("2026-12-31"));

        for (String statement : migration().split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }

        assertThat(jdbc.queryForMap("SELECT planned_kind, planned_on, planned_base_until FROM agreements"))
                .containsEntry("PLANNED_KIND", null).containsEntry("PLANNED_ON", null).containsEntry("PLANNED_BASE_UNTIL", null);
        jdbc.update("UPDATE agreements SET planned_kind = 'RENEWAL', planned_on = ? WHERE id = ?", LocalDate.parse("2026-11-01"), existing);
        assertThatThrownBy(() -> jdbc.update("UPDATE agreements SET planned_kind = 'SIGNING', planned_on = NULL WHERE id = ?", existing))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE agreements SET planned_kind = NULL WHERE id = ?", existing))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE agreements SET planned_kind = 'OTHER' WHERE id = ?", existing))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String migration() throws IOException {
        try (InputStream input = AgreementPlanMigrationTest.class.getResourceAsStream("/db/migration/V36__agreement_signing_plan.sql")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
