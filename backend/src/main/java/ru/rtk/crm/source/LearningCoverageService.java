package ru.rtk.crm.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.work.WorkAccessDeniedException;

@Service
public class LearningCoverageService {
    static final int RUNS_LIMIT = 50;
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    private final JdbcClient jdbcClient;
    private final OrganizationRepository organizationRepository;
    private final SourceRepository sourceRepository;
    private final SourceProperties properties;
    private final SourceSettingsService settings;

    public LearningCoverageService(
            JdbcClient jdbcClient,
            OrganizationRepository organizationRepository,
            SourceRepository sourceRepository,
            SourceProperties properties,
            SourceSettingsService settings
    ) {
        this.jdbcClient = jdbcClient;
        this.organizationRepository = organizationRepository;
        this.sourceRepository = sourceRepository;
        this.properties = properties;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public LearningCoverage coverage(CrmProfile profile) {
        boolean admin = profile.role() == UserRole.ADMIN;
        if (!admin && profile.role() != UserRole.LEADER && profile.role() != UserRole.MANAGEMENT) {
            throw new WorkAccessDeniedException();
        }
        // Администратор видит все вузы: его вопрос — какая часть аналитики неполна во всей системе.
        VisibilityScope scope = admin
                ? new VisibilityScope("1 = 1", Map.of())
                : organizationRepository.visibilityScope(profile).orElseThrow(WorkAccessDeniedException::new);
        OffsetDateTime now = OffsetDateTime.now();
        boolean configured = settings.moodle().configured();
        OffsetDateTime staleBefore = now.minus(properties.staleAfter());
        List<LearningCoverage.Run> runs = findRuns(scope, now.atZoneSameInstant(ZONE).toLocalDate(), staleBefore,
                configured);
        int fresh = 0;
        int stale = 0;
        int withoutObservation = 0;
        List<LearningCoverage.Run> toCheck = new ArrayList<>();
        for (LearningCoverage.Run run : runs) {
            switch (run.state()) {
                case FRESH -> fresh++;
                case STALE -> stale++;
                case NO_DATA -> withoutObservation++;
            }
            if (run.state() != LearningCoverage.State.FRESH && toCheck.size() < RUNS_LIMIT) {
                toCheck.add(run);
            }
        }
        return new LearningCoverage(
                now,
                configured,
                properties.staleAfter().toHours(),
                runs.size(),
                fresh,
                stale,
                withoutObservation,
                admin ? sourceRepository.countProblems(SourceCode.MOODLE) : null,
                toCheck
        );
    }

    private List<LearningCoverage.Run> findRuns(VisibilityScope scope, LocalDate today, OffsetDateTime staleBefore, boolean configured) {
        return jdbcClient.sql("""
                SELECT sm.id AS mapping_id, o.name AS organization_name, p.name AS program_name, sm.external_key,
                       sm.run_starts_on, sm.run_ends_on,
                       (SELECT MAX(lo.confirmed_at) FROM learning_observations lo WHERE lo.mapping_id = sm.id) AS last_observed_at,
                       (SELECT MAX(ls.course_name) FROM learning_snapshots ls WHERE ls.mapping_id = sm.id) AS course_name
                FROM source_mappings sm
                JOIN organizations o ON o.id = sm.organization_id
                JOIN programs p ON p.id = sm.program_id
                WHERE sm.kind IN ('COURSE', 'GROUP') AND sm.run_kind = 'STUDENTS' AND sm.run_starts_on IS NOT NULL
                  AND sm.run_starts_on <= :today AND sm.run_ends_on > :today
                  AND sm.organization_id IN (%s)
                ORDER BY o.name, p.name, sm.run_starts_on, sm.id
                """.formatted(scope.currentOrganizations()))
                .params(scope.parameters())
                .param("today", today)
                .query((resultSet, rowNumber) -> {
                    OffsetDateTime lastObservedAt = resultSet.getObject("last_observed_at", OffsetDateTime.class);
                    String courseName = resultSet.getString("course_name");
                    return new LearningCoverage.Run(
                            resultSet.getObject("mapping_id", UUID.class),
                            resultSet.getString("organization_name"),
                            resultSet.getString("program_name"),
                            courseName == null ? resultSet.getString("external_key") : courseName,
                            resultSet.getObject("run_starts_on", LocalDate.class),
                            resultSet.getObject("run_ends_on", LocalDate.class),
                            lastObservedAt,
                            LearningCoverage.state(lastObservedAt, staleBefore, configured)
                    );
                })
                .list();
    }
}
