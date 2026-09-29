package ru.rtk.crm.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.PlanKind;

public record SigningPlan(
        OffsetDateTime generatedAt,
        String timezone,
        int year,
        Integer quarter,
        LocalDate from,
        LocalDate to,
        List<String> notes,
        List<Row> rows,
        List<Totals> byManager,
        List<Totals> byTeam
) {
    public static final String TITLE = "План подписаний и продлений соглашений";

    public enum Source {
        PLAN,
        EXPIRY
    }

    public enum State {
        DONE("выполнено"),
        OVERDUE("просрочено"),
        UPCOMING("впереди");

        private final String title;

        State(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    public record Row(
            UUID agreementId,
            UUID organizationId,
            String organizationName,
            UUID teamId,
            String teamName,
            UUID managerId,
            String managerName,
            String agreementNumber,
            AgreementStatus agreementStatus,
            PlanKind kind,
            LocalDate plannedOn,
            Source source,
            LocalDate validUntil,
            State state
    ) {
    }

    public record Totals(
            UUID id,
            String name,
            String teamName,
            int signing,
            int renewal,
            int done,
            int overdue,
            int upcoming
    ) {
    }
}
