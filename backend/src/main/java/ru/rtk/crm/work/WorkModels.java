package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ru.rtk.crm.people.PeopleModels.FrdoReminder;

public final class WorkModels {
    private WorkModels() {
    }

    public record TeamIndicators(
            OffsetDateTime calculatedAt,
            int stuckDays,
            long unassignedOrganizations,
            List<ManagerIndicators> managers
    ) {
    }

    public record ManagerIndicators(
            UUID managerId,
            String managerName,
            long organizations,
            long interactions,
            long overdue,
            long withoutNextStep,
            long stuck,
            long atRisk
    ) {
    }

    public record TeamsSummary(OffsetDateTime calculatedAt, int stuckDays, List<TeamSummary> teams, TeamSummary total) {
    }

    public record TeamSummary(
            UUID teamId,
            String teamName,
            long organizations,
            long unassignedOrganizations,
            long interactions,
            long overdue,
            long withoutNextStep,
            long stuck,
            long atRisk,
            long organizationsWithLearning,
            long participants,
            long learningNow,
            long teachers
    ) {
    }

    public record ReminderDigest(
            boolean enabled,
            OffsetDateTime generatedAt,
            int upcomingDays,
            long overdueTotal,
            long dueTodayTotal,
            long upcomingTotal,
            List<ReminderStep> overdue,
            List<ReminderStep> upcoming,
            List<ReminderLicense> expiringLicenses,
            List<ReminderTraining> trainingCycles,
            int licenseExpiresBy,
            List<ReminderQualification> qualificationDue,
            int agreementNoticeDays,
            List<ReminderAgreement> expiringAgreements,
            ReminderFeed feed,
            List<ReminderUnassigned> unassignedAfterBlock,
            List<FrdoReminder> frdoDue,
            ru.rtk.crm.agreement.ReportingModels.ReportingReminders agreementReporting
    ) {
        public ReminderDigest(
                boolean enabled,
                OffsetDateTime generatedAt,
                int upcomingDays,
                long overdueTotal,
                long dueTodayTotal,
                long upcomingTotal,
                List<ReminderStep> overdue,
                List<ReminderStep> upcoming,
                List<ReminderLicense> expiringLicenses,
                List<ReminderTraining> trainingCycles,
                int licenseExpiresBy,
                List<ReminderQualification> qualificationDue,
                int agreementNoticeDays,
                List<ReminderAgreement> expiringAgreements,
                ReminderFeed feed,
                List<ReminderUnassigned> unassignedAfterBlock,
                List<FrdoReminder> frdoDue
        ) {
            this(enabled, generatedAt, upcomingDays, overdueTotal, dueTodayTotal, upcomingTotal, overdue, upcoming,
                    expiringLicenses, trainingCycles, licenseExpiresBy, qualificationDue, agreementNoticeDays,
                    expiringAgreements, feed, unassignedAfterBlock, frdoDue, ru.rtk.crm.agreement.ReportingModels.ReportingReminders.empty());
        }

        public ReminderDigest(
                boolean enabled,
                OffsetDateTime generatedAt,
                int upcomingDays,
                long overdueTotal,
                long dueTodayTotal,
                long upcomingTotal,
                List<ReminderStep> overdue,
                List<ReminderStep> upcoming,
                List<ReminderLicense> expiringLicenses,
                List<ReminderTraining> trainingCycles,
                int licenseExpiresBy,
                List<ReminderQualification> qualificationDue,
                int agreementNoticeDays,
                List<ReminderAgreement> expiringAgreements,
                ReminderFeed feed,
                List<ReminderUnassigned> unassignedAfterBlock
        ) {
            this(enabled, generatedAt, upcomingDays, overdueTotal, dueTodayTotal, upcomingTotal, overdue, upcoming,
                    expiringLicenses, trainingCycles, licenseExpiresBy, qualificationDue, agreementNoticeDays,
                    expiringAgreements, feed, unassignedAfterBlock, List.of(), ru.rtk.crm.agreement.ReportingModels.ReportingReminders.empty());
        }

        public ReminderDigest(
                boolean enabled,
                OffsetDateTime generatedAt,
                int upcomingDays,
                long overdueTotal,
                long dueTodayTotal,
                long upcomingTotal,
                List<ReminderStep> overdue,
                List<ReminderStep> upcoming,
                List<ReminderLicense> expiringLicenses,
                List<ReminderTraining> trainingCycles,
                int licenseExpiresBy,
                List<ReminderQualification> qualificationDue,
                int agreementNoticeDays,
                List<ReminderAgreement> expiringAgreements,
                ReminderFeed feed
        ) {
            this(enabled, generatedAt, upcomingDays, overdueTotal, dueTodayTotal, upcomingTotal, overdue, upcoming,
                    expiringLicenses, trainingCycles, licenseExpiresBy, qualificationDue, agreementNoticeDays,
                    expiringAgreements, feed, List.of(), List.of(), ru.rtk.crm.agreement.ReportingModels.ReportingReminders.empty());
        }
    }

    // Вуз остался без КАМ, потому что КАМ заблокировали и вузы не передали: сигнал руководителю команды.
    public record ReminderUnassigned(
            UUID organizationId,
            String organizationName,
            String previousOwnerName,
            OffsetDateTime unassignedAt
    ) {
    }

    public record ReminderFeed(
            long overdue,
            long today,
            long thisWeek,
            long later,
            long noNextStep,
            long resumeDue,
            long resumeNoDate
    ) {
    }

    public record ReminderAgreement(
            UUID agreementId,
            UUID organizationId,
            String organizationName,
            String number,
            LocalDate validUntil,
            String plannedKind,
            LocalDate plannedOn,
            String ownerManagerName
    ) {
    }

    public record ReminderStep(
            UUID interactionId,
            UUID organizationId,
            String title,
            String organizationName,
            String nextAction,
            OffsetDateTime nextActionAt,
            String ownerManagerName
    ) {
    }

    public record ReminderLicense(
            UUID interactionId,
            UUID organizationId,
            String title,
            String organizationName,
            String productName,
            String vendorName,
            String contractNumber,
            int licenseExpiryYear
    ) {
    }

    public record ReminderTraining(
            UUID interactionId,
            UUID organizationId,
            String title,
            String organizationName,
            String stageName,
            OffsetDateTime trainedAt,
            LocalDate nextCycleOn
    ) {
    }

    public record ReminderQualification(
            UUID interactionId,
            UUID organizationId,
            String title,
            String organizationName,
            String stageName,
            LocalDate trainedOn,
            LocalDate dueOn
    ) {
    }

    public record ReminderSettings(Boolean enabled) {
    }
}
