package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationAssignmentCandidate;
import ru.rtk.crm.catalog.OrganizationAssignmentRepository;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.work.WorkModels.ManagerIndicators;
import ru.rtk.crm.work.WorkModels.ReminderDigest;
import ru.rtk.crm.work.WorkModels.TeamIndicators;
import ru.rtk.crm.work.WorkModels.TeamSummary;
import ru.rtk.crm.work.WorkModels.TeamsSummary;
import ru.rtk.crm.work.WorkRepository.WorkCounts;

@Service
public class WorkService {
    private static final int MAX_STUCK_DAYS = 3650;

    private final OrganizationRepository organizationRepository;
    private final OrganizationAssignmentRepository organizationAssignmentRepository;
    private final WorkRepository workRepository;
    private final WorkProperties properties;

    public WorkService(
            OrganizationRepository organizationRepository,
            OrganizationAssignmentRepository organizationAssignmentRepository,
            WorkRepository workRepository,
            WorkProperties properties
    ) {
        this.organizationRepository = organizationRepository;
        this.organizationAssignmentRepository = organizationAssignmentRepository;
        this.workRepository = workRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public TeamIndicators teamIndicators(CrmProfile profile, Integer requestedStuckDays) {
        if (profile.role() != UserRole.LEADER || profile.teamId() == null) {
            throw new WorkAccessDeniedException();
        }
        int stuckDays = stuckDays(requestedStuckDays);
        VisibilityScope scope = scope(profile);
        OffsetDateTime now = OffsetDateTime.now();
        Map<UUID, WorkCounts> work = workRepository.countWorkByManager(scope, now, now.minusDays(stuckDays));
        Map<UUID, Long> organizations = workRepository.countOrganizationsByManager(scope);
        List<ManagerIndicators> managers = new ArrayList<>();
        for (OrganizationAssignmentCandidate manager : organizationAssignmentRepository.findCandidates(profile.teamId(), profile.id())) {
            if (manager.id().equals(profile.id()) && !organizations.containsKey(profile.id())) {
                continue;
            }
            managers.add(indicators(manager.id(), manager.displayName(), organizations, work));
        }
        managers.add(indicators(null, null, organizations, work));
        return new TeamIndicators(now, stuckDays, organizations.getOrDefault(null, 0L), managers);
    }

    @Transactional(readOnly = true)
    public TeamsSummary teamsSummary(CrmProfile profile, Integer requestedStuckDays) {
        if (profile.role() != UserRole.MANAGEMENT) {
            throw new WorkAccessDeniedException();
        }
        int stuckDays = stuckDays(requestedStuckDays);
        VisibilityScope scope = scope(profile);
        OffsetDateTime now = OffsetDateTime.now();
        Map<UUID, WorkCounts> work = workRepository.countWorkByTeam(scope, now, now.minusDays(stuckDays));
        List<TeamSummary> teams = workRepository.countOrganizationsByTeam(scope).stream()
                .map(team -> {
                    WorkCounts counts = work.getOrDefault(team.teamId(), WorkCounts.EMPTY);
                    return new TeamSummary(
                            team.teamId(),
                            team.teamName(),
                            team.organizations(),
                            team.unassignedOrganizations(),
                            counts.interactions(),
                            counts.overdue(),
                            counts.withoutNextStep(),
                            counts.stuck(),
                            team.organizationsWithLearning(),
                            team.participants(),
                            team.teachers()
                    );
                })
                .toList();
        TeamSummary total = new TeamSummary(
                null,
                null,
                teams.stream().mapToLong(TeamSummary::organizations).sum(),
                teams.stream().mapToLong(TeamSummary::unassignedOrganizations).sum(),
                teams.stream().mapToLong(TeamSummary::interactions).sum(),
                teams.stream().mapToLong(TeamSummary::overdue).sum(),
                teams.stream().mapToLong(TeamSummary::withoutNextStep).sum(),
                teams.stream().mapToLong(TeamSummary::stuck).sum(),
                teams.stream().mapToLong(TeamSummary::organizationsWithLearning).sum(),
                teams.stream().mapToLong(TeamSummary::participants).sum(),
                teams.stream().mapToLong(TeamSummary::teachers).sum()
        );
        return new TeamsSummary(now, stuckDays, teams, total);
    }

    @Transactional(readOnly = true)
    public ReminderDigest reminders(CrmProfile profile) {
        requireOwnWork(profile);
        VisibilityScope scope = scope(profile);
        OffsetDateTime now = OffsetDateTime.now();
        LocalDate today = now.atZoneSameInstant(WorkProperties.ZONE).toLocalDate();
        OffsetDateTime tomorrow = today.plusDays(1).atStartOfDay(WorkProperties.ZONE).toOffsetDateTime();
        OffsetDateTime upcomingUntil = now.plusDays(properties.upcomingDays());
        OffsetDateTime trainedBefore = today.plusDays(properties.trainingNoticeDays())
                .minusYears(properties.trainingCycleYears())
                .plusDays(1)
                .atStartOfDay(WorkProperties.ZONE)
                .toOffsetDateTime();
        int limit = properties.reminderListLimit();
        return new ReminderDigest(
                workRepository.findRemindersEnabled(profile.id()).orElse(true),
                now,
                properties.upcomingDays(),
                workRepository.countSteps(scope, null, now),
                workRepository.countSteps(scope, now, tomorrow),
                workRepository.countSteps(scope, now, upcomingUntil),
                workRepository.findSteps(scope, null, now, limit),
                workRepository.findSteps(scope, now, upcomingUntil, limit),
                workRepository.findExpiringLicenses(scope, properties.licenseExpiresBy(now), limit),
                workRepository.findTrainingCycles(
                        scope,
                        properties.trainingStageNames(),
                        trainedBefore,
                        properties.trainingCycleYears(),
                        limit
                ),
                properties.licenseExpiresBy(now)
        );
    }

    @Transactional
    public WorkModels.ReminderSettings saveReminderSettings(CrmProfile profile, WorkModels.ReminderSettings request) {
        requireOwnWork(profile);
        if (request == null || request.enabled() == null) {
            throw new InteractionValidationException("enabled", "Укажите, включены ли напоминания");
        }
        workRepository.saveRemindersEnabled(profile.id(), request.enabled(), OffsetDateTime.now());
        return new WorkModels.ReminderSettings(request.enabled());
    }

    private ManagerIndicators indicators(
            UUID managerId,
            String managerName,
            Map<UUID, Long> organizations,
            Map<UUID, WorkCounts> work
    ) {
        WorkCounts counts = work.getOrDefault(managerId, WorkCounts.EMPTY);
        return new ManagerIndicators(
                managerId,
                managerName,
                organizations.getOrDefault(managerId, 0L),
                counts.interactions(),
                counts.overdue(),
                counts.withoutNextStep(),
                counts.stuck()
        );
    }

    private void requireOwnWork(CrmProfile profile) {
        if (profile.role() != UserRole.USER && profile.role() != UserRole.LEADER) {
            throw new WorkAccessDeniedException();
        }
    }

    private VisibilityScope scope(CrmProfile profile) {
        return organizationRepository.visibilityScope(profile).orElseThrow(WorkAccessDeniedException::new);
    }

    private int stuckDays(Integer requested) {
        if (requested == null) {
            return properties.stuckDays();
        }
        if (requested < 1 || requested > MAX_STUCK_DAYS) {
            throw new InteractionValidationException("stuckDays", "Укажите целое число дней от 1 до " + MAX_STUCK_DAYS);
        }
        return requested;
    }
}
