package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.ReportingModels.ReportingReminders;
import ru.rtk.crm.agreement.ReportingSettingsService;
import ru.rtk.crm.catalog.OrganizationAssignmentCandidate;
import ru.rtk.crm.catalog.OrganizationAssignmentRepository;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.people.PeopleService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.settings.SystemSetting;
import ru.rtk.crm.settings.SystemSettings;
import ru.rtk.crm.work.WorkModels.ManagerIndicators;
import ru.rtk.crm.work.WorkModels.ReminderDigest;
import ru.rtk.crm.work.WorkModels.TeamIndicators;
import ru.rtk.crm.work.WorkModels.TeamSummary;
import ru.rtk.crm.work.WorkModels.TeamsSummary;
import ru.rtk.crm.work.WorkRepository.WorkCounts;

@Service
public class WorkService {
    private static final int MAX_STUCK_DAYS = 3650;
    static final int MAX_PORTFOLIO_DAYS = 730;
    // Сигнал о вузах, оставшихся без КАМ после его блокировки, гаснет при назначении КАМ или через 30 дней:
    // дальше такие вузы видны в плитке «Вузов без КАМ».
    private static final int UNASSIGNED_SIGNAL_DAYS = 30;

    private final OrganizationRepository organizationRepository;
    private final OrganizationAssignmentRepository organizationAssignmentRepository;
    private final WorkRepository workRepository;
    private final WorkProperties properties;
    private final PeopleService peopleService;
    // Напоминания отчётности по соглашениям; провайдер, а не прямая зависимость, чтобы сводка работы не тянула весь
    // модуль договоров туда, где его нет (узкие тестовые контексты).
    private final ObjectProvider<ReportingSettingsService> reportingSettingsService;
    private final SystemSettings settings;

    public WorkService(
            OrganizationRepository organizationRepository,
            OrganizationAssignmentRepository organizationAssignmentRepository,
            WorkRepository workRepository,
            WorkProperties properties,
            PeopleService peopleService,
            ObjectProvider<ReportingSettingsService> reportingSettingsService,
            SystemSettings settings
    ) {
        this.peopleService = peopleService;
        this.organizationRepository = organizationRepository;
        this.organizationAssignmentRepository = organizationAssignmentRepository;
        this.workRepository = workRepository;
        this.properties = properties;
        this.reportingSettingsService = reportingSettingsService;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public TeamIndicators teamIndicators(CrmProfile profile, Integer requestedStuckDays) {
        if (profile.role() != UserRole.LEADER || profile.teamId() == null) {
            throw new WorkAccessDeniedException();
        }
        int stuckDays = stuckDays(requestedStuckDays);
        VisibilityScope scope = scope(profile);
        OffsetDateTime now = OffsetDateTime.now();
        Map<UUID, WorkCounts> work = workRepository.countWorkByManager(scope, now, stuckDays);
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
        Map<UUID, WorkCounts> work = workRepository.countWorkByTeam(scope, now, stuckDays);
        List<TeamSummary> teams = workRepository.countOrganizationsByTeam(scope, LocalDate.now(WorkProperties.ZONE)).stream()
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
                            counts.atRisk(),
                            team.organizationsWithLearning(),
                            team.participants(),
                            team.learningNow(),
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
                teams.stream().mapToLong(TeamSummary::atRisk).sum(),
                teams.stream().mapToLong(TeamSummary::organizationsWithLearning).sum(),
                teams.stream().mapToLong(TeamSummary::participants).sum(),
                teams.stream().mapToLong(TeamSummary::learningNow).sum(),
                teams.stream().mapToLong(TeamSummary::teachers).sum()
        );
        return new TeamsSummary(now, stuckDays, teams, total);
    }

    @Transactional(readOnly = true)
    public PortfolioChange portfolioChange(CrmProfile profile, LocalDate from, LocalDate to) {
        if (profile.role() != UserRole.MANAGEMENT && profile.role() != UserRole.LEADER) {
            throw new WorkAccessDeniedException();
        }
        if (from == null || to == null) {
            throw new InteractionValidationException(from == null ? "from" : "to", "Укажите начало и конец периода");
        }
        if (from.isAfter(to) || from.plusDays(MAX_PORTFOLIO_DAYS).isBefore(to)) {
            throw new InteractionValidationException("from", "Укажите период не длиннее " + MAX_PORTFOLIO_DAYS + " дней, начало не позже конца");
        }
        VisibilityScope scope = scope(profile);
        return PortfolioChange.of(
                workRepository.findPortfolioStates(scope, from.atStartOfDay(WorkProperties.ZONE).toOffsetDateTime(),
                        to.plusDays(1).atStartOfDay(WorkProperties.ZONE).toOffsetDateTime()),
                from,
                to,
                OffsetDateTime.now()
        );
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
                        trainedBefore,
                        properties.trainingCycleYears(),
                        limit
                ),
                properties.licenseExpiresBy(now),
                workRepository.findQualificationDue(
                        scope,
                        today.minusMonths(properties.qualificationAfterMonths()),
                        properties.qualificationAfterMonths(),
                        limit
                ),
                properties.agreementNoticeDays(),
                workRepository.findExpiringAgreements(scope, today.plusDays(properties.agreementNoticeDays()), limit),
                workRepository.countFeed(scope, now),
                profile.role() == UserRole.LEADER
                        ? workRepository.findUnassignedAfterBlock(scope, now.minusDays(UNASSIGNED_SIGNAL_DAYS), limit)
                        : List.of(),
                peopleService.frdoReminders(scope, today, limit),
                reportingReminders(profile)
        );
    }

    // Только итоги групп ленты — одним запросом. Мини-приложение MAX зовёт его на каждое переключение группы, а полный
    // reminders() — это ещё десяток запросов (лицензии, циклы, договоры, ФРДО), которые ему не нужны.
    @Transactional(readOnly = true)
    public WorkModels.ReminderFeed feed(CrmProfile profile) {
        requireOwnWork(profile);
        return workRepository.countFeed(scope(profile), OffsetDateTime.now());
    }

    private ReportingReminders reportingReminders(CrmProfile profile) {
        ReportingSettingsService service = reportingSettingsService.getIfAvailable();
        return service == null ? ReportingReminders.empty() : service.reminders(profile);
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
                counts.stuck(),
                counts.atRisk()
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
            return settings.value(SystemSetting.STUCK_DAYS, properties.stuckDays());
        }
        if (requested < 1 || requested > MAX_STUCK_DAYS) {
            throw new InteractionValidationException("stuckDays", "Укажите целое число дней от 1 до " + MAX_STUCK_DAYS);
        }
        return requested;
    }
}
