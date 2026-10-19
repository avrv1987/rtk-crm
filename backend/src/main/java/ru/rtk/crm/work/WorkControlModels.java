package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.Approval;
import ru.rtk.crm.agreement.AgreementModels.RegistryRow;
import ru.rtk.crm.interaction.InteractionWorkStatus;
import ru.rtk.crm.report.ManagerWorkDue;
import ru.rtk.crm.report.ManagerWorkOrganization;
import ru.rtk.crm.report.ReportRepository.StageRoute;

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
            List<ManagerWorkOrganization> idleOrganizations,
            List<RegistryRow> agreements,
            List<StageRoute> routes
    ) {
        public Control(OffsetDateTime calculatedAt, LocalDate from, LocalDate to, boolean byTeams, List<TeamBlock> teams, Totals total,
                       List<ManagerWorkOrganization> idleOrganizations) {
            this(calculatedAt, from, to, byTeams, teams, total, idleOrganizations, List.of(), List.of());
        }

        public Control(OffsetDateTime calculatedAt, LocalDate from, LocalDate to, boolean byTeams, List<TeamBlock> teams, Totals total,
                       List<ManagerWorkOrganization> idleOrganizations, List<RegistryRow> agreements) {
            this(calculatedAt, from, to, byTeams, teams, total, idleOrganizations, agreements, List.of());
        }
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
            LmsSignalModels.WorkLearning learning,
            Approval agreementApproval,
            boolean stageUnderApproval,
            UUID programId,
            List<UUID> productIds,
            UUID kindId,
            UUID routeId,
            String stageAtEnd,
            boolean missingDocuments
    ) {
        public WorkItem(UUID interactionId, UUID organizationId, String organizationName, String title, String direction, String program,
                        List<String> products, String stage, int stagePosition, int stageTotal, Long daysOnStage, String stageAtStart,
                        String nextAction, OffsetDateTime nextActionAt, ManagerWorkDue due, InteractionWorkStatus status, int problems,
                        int risks, String issues, long transitions, boolean created, boolean completed, AgreementStatus agreementStatus,
                        String agreementNumber, LocalDate agreementValidUntil, LmsSignalModels.WorkLearning learning,
                        Approval agreementApproval, boolean stageUnderApproval) {
            this(interactionId, organizationId, organizationName, title, direction, program, products, stage, stagePosition, stageTotal,
                    daysOnStage, stageAtStart, nextAction, nextActionAt, due, status, problems, risks, issues, transitions, created,
                    completed, agreementStatus, agreementNumber, agreementValidUntil, learning, agreementApproval, stageUnderApproval,
                    null, List.of(), null, null, stage, false);
        }
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
