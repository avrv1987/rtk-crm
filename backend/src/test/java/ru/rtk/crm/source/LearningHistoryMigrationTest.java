package ru.rtk.crm.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class LearningHistoryMigrationTest {
    private static final OffsetDateTime CHANGED = OffsetDateTime.parse("2026-09-01T10:00:00+03:00");
    private static final OffsetDateTime OBSERVED = OffsetDateTime.parse("2026-09-20T10:00:00+03:00");

    @Test
    void existingSnapshotsBecomeFirstHistoryRowsAndRepeatedObservationIsRejected() throws IOException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:v30-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE source_mappings (id UUID PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE learning_snapshots (
                    mapping_id UUID PRIMARY KEY, participants_count INTEGER NOT NULL, teachers_count INTEGER NOT NULL,
                    completed_count INTEGER, not_completed_count INTEGER, unknown_count INTEGER NOT NULL,
                    groups_count INTEGER NOT NULL, observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    changed_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        UUID tracked = UUID.randomUUID();
        UUID untracked = UUID.randomUUID();
        jdbc.update("INSERT INTO source_mappings (id) VALUES (?), (?)", tracked, untracked);
        jdbc.update("INSERT INTO learning_snapshots VALUES (?, 6, 1, 3, 3, 0, 2, ?, ?)", tracked, OBSERVED, CHANGED);
        jdbc.update("INSERT INTO learning_snapshots VALUES (?, 4, 1, NULL, NULL, 4, 1, ?, ?)", untracked, CHANGED.minusDays(1),
                CHANGED);

        for (String statement : migration().split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }

        assertThat(jdbc.query("""
                SELECT mapping_id, participants_count, completed_count, observed_from, confirmed_at, demo
                FROM learning_observations ORDER BY participants_count DESC
                """, (resultSet, rowNumber) -> tuple(
                resultSet.getObject(1, UUID.class),
                resultSet.getInt(2),
                resultSet.getObject(3),
                resultSet.getObject(4, OffsetDateTime.class).toInstant(),
                resultSet.getObject(5, OffsetDateTime.class).toInstant(),
                resultSet.getBoolean(6)
        ))).containsExactly(
                tuple(tracked, 6, 3, CHANGED.toInstant(), OBSERVED.toInstant(), false),
                tuple(untracked, 4, null, CHANGED.toInstant(), CHANGED.toInstant(), false)
        );
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO learning_observations (
                    mapping_id, observed_from, confirmed_at, participants_count, teachers_count, unknown_count, groups_count
                ) VALUES (?, ?, ?, 1, 1, 1, 0)
                """, tracked, CHANGED, OBSERVED)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO learning_observations (
                    mapping_id, observed_from, confirmed_at, participants_count, teachers_count, completed_count, unknown_count,
                    groups_count
                ) VALUES (?, ?, ?, 1, 1, 1, 0, 0)
                """, tracked, OBSERVED, OBSERVED)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM source_mappings WHERE id = ?", untracked);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_observations", Integer.class)).isEqualTo(1);
    }

    private static String migration() throws IOException {
        try (InputStream input = LearningHistoryMigrationTest.class.getResourceAsStream(
                "/db/migration/V30__learning_observations.sql")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
