package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.LearningHistoryRepository;
import ru.rtk.crm.report.LearningHistoryRepository.Observation;

@Service
public class LearningTrendService {
    static final int DEFAULT_DAYS = 90;
    static final int MIN_DAYS = 7;
    static final int MAX_DAYS = 730;
    static final int TOP = 5;

    private final OrganizationRepository organizationRepository;
    private final LearningHistoryRepository historyRepository;

    public LearningTrendService(OrganizationRepository organizationRepository, LearningHistoryRepository historyRepository) {
        this.organizationRepository = organizationRepository;
        this.historyRepository = historyRepository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LearningTrend trend(CrmProfile profile, Integer requestedDays) {
        if (profile.role() != UserRole.MANAGEMENT && profile.role() != UserRole.LEADER) {
            throw new WorkAccessDeniedException();
        }
        int days = requestedDays == null ? DEFAULT_DAYS : requestedDays;
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new InteractionValidationException("days", "Укажите период от " + MIN_DAYS + " до " + MAX_DAYS + " дней");
        }
        VisibilityScope scope = organizationRepository.visibilityScope(profile).orElseThrow(WorkAccessDeniedException::new);
        LocalDate to = LocalDate.now(WorkProperties.ZONE);
        LocalDate from = to.minusDays(days);
        return trend(historyRepository.findObservations(scope, from, to, List.of(), List.of()), from, to, days);
    }

    static LearningTrend trend(List<Observation> observations, LocalDate from, LocalDate to, int days) {
        Map<UUID, Observation> atStart = LearningHistoryRepository.inForce(observations, from);
        Map<UUID, Observation> atEnd = LearningHistoryRepository.inForce(observations, to);
        Map<UUID, Observation> runs = new LinkedHashMap<>();
        observations.forEach(observation -> runs.put(observation.mappingId(), observation));
        Map<UUID, Totals> teams = new LinkedHashMap<>();
        Map<UUID, Totals> programs = new LinkedHashMap<>();
        Map<UUID, Totals> organizations = new LinkedHashMap<>();
        Totals total = new Totals(null, null);
        int withoutData = 0;
        for (Observation run : runs.values()) {
            boolean activeAtStart = run.activeOn(from);
            boolean activeAtEnd = run.activeOn(to);
            Long start = value(run, atStart.get(run.mappingId()), from);
            Long end = value(run, atEnd.get(run.mappingId()), to);
            if (start == null) {
                withoutData++;
            }
            Kind kind;
            long value;
            if (activeAtStart && activeAtEnd) {
                if (end == null) {
                    continue;
                }
                kind = start == null ? Kind.WITHOUT_BASE : Kind.COMPARABLE;
                value = end;
            } else if (activeAtEnd) {
                if (end == null) {
                    continue;
                }
                kind = Kind.STARTED;
                value = end;
            } else if (activeAtStart && start != null) {
                kind = Kind.ENDED;
                value = start;
            } else {
                continue;
            }
            long base = start == null ? 0 : start;
            if (value == 0 && base == 0) {
                continue;
            }
            for (Totals totals : List.of(
                    total,
                    teams.computeIfAbsent(run.teamId(), id -> new Totals(id, run.teamName())),
                    programs.computeIfAbsent(run.programId(), id -> new Totals(id, run.programName())),
                    organizations.computeIfAbsent(run.organizationId(), id -> new Totals(id, run.organizationName())))) {
                totals.add(kind, base, value);
            }
        }
        Comparator<LearningTrend.Item> byName = Comparator.comparing(LearningTrend.Item::name, String.CASE_INSENSITIVE_ORDER);
        Comparator<LearningTrend.Item> growing = Comparator.comparingLong(LearningTrend.Item::change).reversed().thenComparing(byName);
        List<LearningTrend.Item> organizationItems = items(organizations);
        return new LearningTrend(
                OffsetDateTime.now(),
                from,
                to,
                days,
                total.view(),
                items(teams).stream().sorted(byName).toList(),
                items(programs).stream().sorted(growing).toList(),
                organizationItems.stream().filter(item -> item.change() > 0).sorted(growing).limit(TOP).toList(),
                organizationItems.stream().filter(item -> item.change() < 0)
                        .sorted(Comparator.comparingLong(LearningTrend.Item::change).thenComparing(byName))
                        .limit(TOP).toList(),
                withoutData
        );
    }

    private static Long value(Observation run, Observation inForce, LocalDate day) {
        if (!run.activeOn(day)) {
            return 0L;
        }
        return inForce == null ? null : (long) inForce.participants();
    }

    private static List<LearningTrend.Item> items(Map<UUID, Totals> totals) {
        return totals.values().stream().map(Totals::view).toList();
    }

    private enum Kind { COMPARABLE, WITHOUT_BASE, STARTED, ENDED }

    private static final class Totals {
        private final UUID id;
        private final String name;
        private long start;
        private long end;
        private long change;
        private int withoutStart;
        private long endWithoutStart;
        private int started;
        private long endStarted;
        private int ended;
        private long startEnded;

        private Totals(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        // Поток без значения на начало не смешивается с сопоставимыми: иначе «10 → 32 (+2)» складывало бы разные
        // совокупности в одну строку. Начавшиеся и закончившиеся потоки — изменение состава, а не изменение величины.
        private void add(Kind kind, long startValue, long value) {
            switch (kind) {
                case COMPARABLE -> {
                    start += startValue;
                    end += value;
                    change += value - startValue;
                }
                case WITHOUT_BASE -> {
                    withoutStart++;
                    endWithoutStart += value;
                }
                case STARTED -> {
                    started++;
                    endStarted += value;
                }
                case ENDED -> {
                    ended++;
                    startEnded += value;
                }
            }
        }

        private LearningTrend.Item view() {
            return new LearningTrend.Item(id, name, start, end, change, withoutStart, endWithoutStart, started, endStarted,
                    ended, startEnded);
        }
    }
}
