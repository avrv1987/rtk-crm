package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Состав активных взаимодействий на начало и конец периода (разбор §7.3): было = остались + завершены + приостановлены,
// стало = остались + новые + возобновлены. Команда — текущая команда вуза: истории принадлежности вуза команде нет.
public record PortfolioChange(OffsetDateTime calculatedAt, LocalDate from, LocalDate to, List<Row> teams, Row total) {

    public record Row(
            UUID teamId,
            String teamName,
            long atStart,
            long atEnd,
            long stayed,
            long created,
            long resumed,
            long completed,
            long paused
    ) {
    }

    // Статус взаимодействия на начало и конец периода; null — ещё не создано.
    public record State(UUID teamId, String teamName, String statusAtStart, String statusAtEnd) {
    }

    static PortfolioChange of(List<State> states, LocalDate from, LocalDate to, OffsetDateTime calculatedAt) {
        Map<UUID, Counter> teams = new LinkedHashMap<>();
        Counter total = new Counter(null, null);
        for (State state : states) {
            Counter team = teams.computeIfAbsent(state.teamId(), id -> new Counter(id, state.teamName()));
            total.add(state);
            team.add(state);
        }
        return new PortfolioChange(
                calculatedAt,
                from,
                to,
                teams.values().stream()
                        .map(Counter::row)
                        .filter(row -> row.atStart() > 0 || row.atEnd() > 0)
                        .sorted(Comparator.comparing(Row::teamName, String.CASE_INSENSITIVE_ORDER))
                        .toList(),
                total.row()
        );
    }

    private static final class Counter {
        private final UUID teamId;
        private final String teamName;
        private long stayed;
        private long created;
        private long resumed;
        private long completed;
        private long paused;

        private Counter(UUID teamId, String teamName) {
            this.teamId = teamId;
            this.teamName = teamName;
        }

        private void add(State state) {
            boolean wasActive = "ACTIVE".equals(state.statusAtStart());
            boolean isActive = "ACTIVE".equals(state.statusAtEnd());
            if (wasActive && isActive) {
                stayed++;
            } else if (isActive) {
                if (state.statusAtStart() == null) {
                    created++;
                } else {
                    resumed++;
                }
            } else if (wasActive) {
                if ("COMPLETED".equals(state.statusAtEnd())) {
                    completed++;
                } else {
                    paused++;
                }
            }
        }

        private Row row() {
            return new Row(teamId, teamName, stayed + completed + paused, stayed + created + resumed, stayed, created, resumed,
                    completed, paused);
        }
    }
}
