package ru.rtk.crm.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PortfolioChangeTest {
    private static final UUID TEAM_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // Разбор §7.3: было = остались + завершены + приостановлены, стало = остались + новые + возобновлены.
    @Test
    void balanceSplitsChangeIntoStayedNewResumedCompletedAndPaused() {
        List<PortfolioChange.State> states = List.of(
                state(TEAM_A, "ACTIVE", "ACTIVE"),
                state(TEAM_A, "ACTIVE", "ACTIVE"),
                state(TEAM_A, null, "ACTIVE"),
                state(TEAM_A, "PAUSED", "ACTIVE"),
                state(TEAM_A, "ACTIVE", "COMPLETED"),
                state(TEAM_B, "ACTIVE", "PAUSED"),
                state(TEAM_B, "COMPLETED", "COMPLETED"),
                state(TEAM_B, null, "PAUSED")
        );

        PortfolioChange change = PortfolioChange.of(states, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"),
                OffsetDateTime.now());

        assertThat(change.total()).extracting(PortfolioChange.Row::atStart, PortfolioChange.Row::atEnd, PortfolioChange.Row::stayed,
                        PortfolioChange.Row::created, PortfolioChange.Row::resumed, PortfolioChange.Row::completed,
                        PortfolioChange.Row::paused)
                .containsExactly(4L, 4L, 2L, 1L, 1L, 1L, 1L);
        assertThat(change.teams()).extracting(PortfolioChange.Row::teamName, PortfolioChange.Row::atStart, PortfolioChange.Row::atEnd)
                .containsExactly(tuple("Команда А", 3L, 4L), tuple("Команда Б", 1L, 0L));
    }

    @Test
    void teamWithoutActiveWorkAtBothEndsIsNotListed() {
        PortfolioChange change = PortfolioChange.of(List.of(state(TEAM_B, "COMPLETED", "COMPLETED")),
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), OffsetDateTime.now());

        assertThat(change.teams()).isEmpty();
        assertThat(change.total().atStart()).isZero();
    }

    private static PortfolioChange.State state(UUID teamId, String atStart, String atEnd) {
        return new PortfolioChange.State(teamId, teamId.equals(TEAM_A) ? "Команда А" : "Команда Б", atStart, atEnd);
    }
}
