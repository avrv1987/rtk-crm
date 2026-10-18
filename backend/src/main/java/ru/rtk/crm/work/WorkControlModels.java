package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.interaction.InteractionWorkStatus;
import ru.rtk.crm.report.ManagerWorkDue;
import ru.rtk.crm.report.ManagerWorkOrganization;

public final class WorkControlModels {
    private WorkControlModels() {
    }

    public record Control(
            OffsetDateTime calculatedAt,
            LocalDate from,
            LocalDate to,
            boolean byTeams,
            List<TeamBlock> teams,
            Totals total,
            List<ManagerWorkOrganization> idleOrganizations
    ) {
    }

    public record TeamBlock(UUID teamId, String teamName, Totals totals, List<ManagerBlock> managers) {
    }

    public record ManagerBlock(UUID managerId, String managerName, Totals totals, List<WorkItem> works) {
    }

    public record WorkItem(
            UUID interactionId,
            UUID organizationId,
            String organizationName,
            String title,
            String direction,
            String program,
            List<String> products,
            String stage,
            int stagePosition,
            int stageTotal,
            Long daysOnStage,
            String stageAtStart,
            String nextAction,
            OffsetDateTime nextActionAt,
            ManagerWorkDue due,
            InteractionWorkStatus status,
            int problems,
            int risks,
            String issues,
            long transitions,
            boolean created,
            boolean completed,
            AgreementStatus agreementStatus,
            String agreementNumber,
            LocalDate agreementValidUntil,
            LmsSignalModels.WorkLearning learning
    ) {
    }

    public record Totals(
            long organizations,
            long works,
            long overdue,
            long thisWeek,
            long noNextStep,
            long inOrder,
            long withIssues,
            long moved,
            long created,
            long completed,
            long idle
    ) {
    }
}
