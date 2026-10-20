package ru.rtk.crm.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.work.WorkAccessDeniedException;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:learning_coverage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LearningCoverageServiceTest {
    private static final UUID TEAM_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID UNIVERSITY_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID UNIVERSITY_B = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID PROGRAM = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Moscow"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcClient jdbcClient;

    private final SourceRepository sourceRepository = mock(SourceRepository.class);
    private final SourceSettingsService settings = mock(SourceSettingsService.class);
    private final SourceProperties.Moodle moodle = mock(SourceProperties.Moodle.class);

    @BeforeEach
    void setUp() {
        for (String ddl : List.of(
                "CREATE TABLE IF NOT EXISTS organizations (id UUID PRIMARY KEY, name VARCHAR(300) NOT NULL, team_id UUID NOT NULL,"
                        + " status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL)",
                "CREATE TABLE IF NOT EXISTS programs (id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL)",
                "CREATE TABLE IF NOT EXISTS source_mappings (id UUID PRIMARY KEY, kind VARCHAR(16) NOT NULL,"
                        + " external_key VARCHAR(310) NOT NULL, organization_id UUID, program_id UUID, run_starts_on DATE,"
                        + " run_ends_on DATE, run_kind VARCHAR(16) DEFAULT 'STUDENTS' NOT NULL)",
                "CREATE TABLE IF NOT EXISTS learning_observations (mapping_id UUID NOT NULL,"
                        + " observed_from TIMESTAMP WITH TIME ZONE NOT NULL, confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL)",
                "CREATE TABLE IF NOT EXISTS learning_snapshots (mapping_id UUID, course_name VARCHAR(1333) NOT NULL)")) {
            jdbcTemplate.execute(ddl);
        }
        for (String table : List.of("learning_snapshots", "learning_observations", "source_mappings", "programs", "organizations")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO organizations (id, name, team_id) VALUES (?, 'Вуз А', ?), (?, 'Вуз Б', ?)",
                UNIVERSITY_A, TEAM_A, UNIVERSITY_B, TEAM_B);
        jdbcTemplate.update("INSERT INTO programs (id, name) VALUES (?, 'Java')", PROGRAM);
        when(settings.moodle()).thenReturn(moodle);
        when(moodle.configured()).thenReturn(true);
        when(sourceRepository.countProblems(SourceCode.MOODLE)).thenReturn(4L);
    }

    // §5.4: знаменатель — идущие сопоставленные потоки, а не только те, по которым данные пришли.
    @Test
    void expectedRunsAreMappedRunningStudentRunsAndEachGetsFreshnessState() {
        run(UNIVERSITY_A, "fresh", TODAY.minusDays(10), TODAY.plusDays(30), OffsetDateTime.now().minusHours(1));
        run(UNIVERSITY_A, "stale", TODAY.minusDays(10), TODAY.plusDays(30), OffsetDateTime.now().minusDays(3));
        run(UNIVERSITY_B, "silent", TODAY.minusDays(10), TODAY.plusDays(30), null);
        run(UNIVERSITY_A, "ended", TODAY.minusDays(60), TODAY.minusDays(1), OffsetDateTime.now().minusDays(5));
        run(UNIVERSITY_A, "planned", TODAY.plusDays(5), TODAY.plusDays(60), null);

        LearningCoverage all = service().coverage(new CrmProfile(UUID.randomUUID(), UserRole.ADMIN, null, 0));
        LearningCoverage team = service().coverage(new CrmProfile(UUID.randomUUID(), UserRole.LEADER, TEAM_A, 0));

        assertThat(all).extracting(LearningCoverage::expected, LearningCoverage::fresh, LearningCoverage::stale,
                        LearningCoverage::withoutObservation, LearningCoverage::unappliedRecords)
                .containsExactly(3, 1, 1, 1, 4L);
        assertThat(all.runs()).extracting(LearningCoverage.Run::courseName, LearningCoverage.Run::state)
                .containsExactly(tuple("Курс stale", LearningCoverage.State.STALE),
                        tuple("silent", LearningCoverage.State.NO_DATA));
        assertThat(team).extracting(LearningCoverage::expected, LearningCoverage::fresh, LearningCoverage::stale,
                        LearningCoverage::withoutObservation, LearningCoverage::unappliedRecords)
                .containsExactly(2, 1, 1, 0, null);
    }

    @Test
    void withoutConfiguredMoodleFreshnessIsNotJudgedAndOnlyStaffRolesRead() {
        when(moodle.configured()).thenReturn(false);
        run(UNIVERSITY_A, "old", TODAY.minusDays(10), TODAY.plusDays(30), OffsetDateTime.now().minusDays(30));

        LearningCoverage coverage = service().coverage(new CrmProfile(UUID.randomUUID(), UserRole.MANAGEMENT, null, 0));

        assertThat(coverage.sourceConfigured()).isFalse();
        assertThat(coverage.fresh()).isEqualTo(1);
        assertThat(coverage.stale()).isZero();
        for (UserRole role : List.of(UserRole.USER, UserRole.PARTNER)) {
            assertThatThrownBy(() -> service().coverage(new CrmProfile(UUID.randomUUID(), role, TEAM_A, 0)))
                    .isInstanceOf(WorkAccessDeniedException.class);
        }
    }

    private LearningCoverageService service() {
        SourceProperties properties = new SourceProperties(1, 1, null, Duration.ofHours(26), null, null, null, null);
        return new LearningCoverageService(jdbcClient, new OrganizationRepository(jdbcClient), sourceRepository, properties, settings,
                new ru.rtk.crm.settings.TestSystemSettings());
    }

    private void run(UUID organizationId, String key, LocalDate startsOn, LocalDate endsOn, OffsetDateTime observedAt) {
        UUID mappingId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO source_mappings (id, kind, external_key, organization_id, program_id, run_starts_on, run_ends_on)
                VALUES (?, 'COURSE', ?, ?, ?, ?, ?)
                """, mappingId, key, organizationId, PROGRAM, startsOn, endsOn);
        if (observedAt != null) {
            jdbcTemplate.update("INSERT INTO learning_observations (mapping_id, observed_from, confirmed_at) VALUES (?, ?, ?)",
                    mappingId, observedAt.minusDays(1), observedAt);
            jdbcTemplate.update("INSERT INTO learning_snapshots (mapping_id, course_name) VALUES (?, ?)", mappingId, "Курс " + key);
        }
    }
}
