package ru.rtk.crm.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.report.LearningHistoryRepository.Observation;

class LearningTrendServiceTest {
    private static final UUID TEAM_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID JAVA = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID DATA = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final LocalDate FROM = LocalDate.parse("2026-06-01");
    private static final LocalDate TO = LocalDate.parse("2026-09-01");

    @Test
    void trendComparesParticipantsInForceAtBothEndsAndRanksUniversities() {
        UUID growingRun = UUID.randomUUID();
        UUID fallingRun = UUID.randomUUID();
        UUID newRun = UUID.randomUUID();
        UUID endedRun = UUID.randomUUID();
        UUID unknownRun = UUID.randomUUID();
        List<Observation> observations = List.of(
                observation(growingRun, "Вуз А", TEAM_A, "Команда А", JAVA, "Java", "2026-02-01", "2027-01-01", "2026-05-15", 10),
                observation(growingRun, "Вуз А", TEAM_A, "Команда А", JAVA, "Java", "2026-02-01", "2027-01-01", "2026-08-01", 14),
                observation(fallingRun, "Вуз Б", TEAM_B, "Команда Б", DATA, "Данные", "2026-02-01", "2027-01-01", "2026-05-20", 9),
                observation(fallingRun, "Вуз Б", TEAM_B, "Команда Б", DATA, "Данные", "2026-02-01", "2027-01-01", "2026-07-01", 3),
                observation(newRun, "Вуз В", TEAM_A, "Команда А", JAVA, "Java", "2026-07-01", "2027-01-01", "2026-07-02", 5),
                observation(endedRun, "Вуз Г", TEAM_B, "Команда Б", DATA, "Данные", "2026-02-01", "2026-08-01", "2026-05-01", 4),
                observation(unknownRun, "Вуз Д", TEAM_A, "Команда А", JAVA, "Java", "2026-02-01", "2027-01-01", "2026-07-15", 50)
        );

        LearningTrend trend = LearningTrendService.trend(observations, FROM, TO, 92);

        assertThat(trend.total()).extracting(LearningTrend.Item::start, LearningTrend.Item::end, LearningTrend.Item::change)
                .containsExactly(23L, 72L, -1L);
        assertThat(trend.runsWithoutData()).isEqualTo(1);
        assertThat(trend.teams()).extracting(LearningTrend.Item::name, LearningTrend.Item::start, LearningTrend.Item::end)
                .containsExactly(tuple("Команда А", 10L, 69L), tuple("Команда Б", 13L, 3L));
        assertThat(trend.programs()).extracting(LearningTrend.Item::name, LearningTrend.Item::change)
                .containsExactly(tuple("Java", 9L), tuple("Данные", -10L));
        assertThat(trend.growing()).extracting(LearningTrend.Item::name, LearningTrend.Item::change)
                .containsExactly(tuple("Вуз В", 5L), tuple("Вуз А", 4L));
        assertThat(trend.falling()).extracting(LearningTrend.Item::name, LearningTrend.Item::change)
                .containsExactly(tuple("Вуз Б", -6L), tuple("Вуз Г", -4L));
    }

    @Test
    void runWithoutObservationBeforeStartCountsAtEndButNotInChange() {
        UUID watched = UUID.randomUUID();
        UUID silent = UUID.randomUUID();
        List<Observation> observations = List.of(
                observation(watched, "Вуз А", TEAM_A, "Команда А", JAVA, "Java", "2026-02-01", "2027-01-01", "2026-05-15", 10),
                observation(watched, "Вуз А", TEAM_A, "Команда А", JAVA, "Java", "2026-02-01", "2027-01-01", "2026-08-01", 12),
                observation(silent, "Вуз Б", TEAM_B, "Команда Б", DATA, "Данные", "2026-02-01", "2027-01-01", "2026-07-15", 3)
        );

        LearningTrend trend = LearningTrendService.trend(observations, FROM, TO, 92);

        assertThat(trend.total()).extracting(LearningTrend.Item::start, LearningTrend.Item::end, LearningTrend.Item::change)
                .containsExactly(10L, 15L, 2L);
        assertThat(trend.runsWithoutData()).isEqualTo(1);
        assertThat(trend.teams()).extracting(LearningTrend.Item::name, LearningTrend.Item::end, LearningTrend.Item::runsWithoutData)
                .containsExactly(tuple("Команда А", 12L, 0), tuple("Команда Б", 3L, 1));
    }

    @Test
    void runStartedInsidePeriodIsZeroAtStartAndEndedRunIsZeroAtEnd() {
        UUID started = UUID.randomUUID();
        UUID ended = UUID.randomUUID();
        UUID beforeStart = UUID.randomUUID();
        List<Observation> observations = List.of(
                observation(started, "Вуз А", TEAM_A, "Команда А", JAVA, "Java", "2026-07-01", "2027-01-01", "2026-07-20", 6),
                observation(ended, "Вуз Б", TEAM_B, "Команда Б", DATA, "Данные", "2026-01-01", "2026-08-01", "2026-04-10", 8),
                observation(beforeStart, "Вуз В", TEAM_A, "Команда А", JAVA, "Java", "2026-10-01", "2027-01-01", "2026-08-20", 99)
        );

        LearningTrend trend = LearningTrendService.trend(observations, FROM, TO, 92);

        assertThat(trend.total()).extracting(LearningTrend.Item::start, LearningTrend.Item::end, LearningTrend.Item::change)
                .containsExactly(8L, 6L, -2L);
        assertThat(trend.runsWithoutData()).isEqualTo(0);
    }

    @Test
    void sameRuleAppliesToEveryPeriodLength() {
        UUID run = UUID.randomUUID();
        List<Observation> observations = List.of(
                observation(run, "Вуз А", TEAM_A, "Команда А", JAVA, "Java", "2026-04-01", "2027-09-01", "2026-06-10", 13)
        );

        LearningTrend longPeriod = LearningTrendService.trend(observations, LocalDate.parse("2025-09-01"), TO, 365);
        LearningTrend midPeriod = LearningTrendService.trend(observations, LocalDate.parse("2026-04-02"), TO, 152);
        LearningTrend shortPeriod = LearningTrendService.trend(observations, LocalDate.parse("2026-08-02"), TO, 30);

        assertThat(longPeriod.total()).extracting(LearningTrend.Item::start, LearningTrend.Item::end, LearningTrend.Item::change)
                .containsExactly(0L, 13L, 13L);
        assertThat(longPeriod.runsWithoutData()).isEqualTo(0);
        assertThat(midPeriod.total()).extracting(LearningTrend.Item::start, LearningTrend.Item::end, LearningTrend.Item::change)
                .containsExactly(0L, 13L, 0L);
        assertThat(midPeriod.runsWithoutData()).isEqualTo(1);
        assertThat(shortPeriod.total()).extracting(LearningTrend.Item::start, LearningTrend.Item::end, LearningTrend.Item::change)
                .containsExactly(13L, 13L, 0L);
        assertThat(shortPeriod.runsWithoutData()).isEqualTo(0);
    }

    @Test
    void onlyManagementReadsTrendAndPeriodIsBounded() {
        LearningTrendService service = new LearningTrendService(null, null);
        CrmProfile management = new CrmProfile(UUID.randomUUID(), UserRole.MANAGEMENT, null, 0);
        for (UserRole role : List.of(UserRole.USER, UserRole.LEADER, UserRole.ADMIN)) {
            assertThatThrownBy(() -> service.trend(new CrmProfile(UUID.randomUUID(), role, TEAM_A, 0), 90))
                    .isInstanceOf(WorkAccessDeniedException.class);
        }
        assertThatThrownBy(() -> service.trend(management, 6)).hasMessageContaining("от 7 до 730");
        assertThatThrownBy(() -> service.trend(management, 731)).hasMessageContaining("от 7 до 730");
    }

    private static Observation observation(UUID run, String organization, UUID teamId, String team, UUID programId,
                                           String program, String startsOn, String endsOn, String observedOn, int participants) {
        return new Observation(run, LocalDate.parse(startsOn), LocalDate.parse(endsOn),
                UUID.nameUUIDFromBytes(organization.getBytes()), organization, teamId, team, programId, program,
                OffsetDateTime.parse(observedOn + "T12:00:00+03:00"), participants, null);
    }
}
