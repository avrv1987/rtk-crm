package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
            long stuck
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
            long organizationsWithLearning,
            long participants,
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
            List<ReminderTraining> trainingCycles
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

    public record ReminderSettings(Boolean enabled) {
    }
}
