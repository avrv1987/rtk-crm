package ru.rtk.crm.report;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import ru.rtk.crm.interaction.InteractionMarks;

public record ReportRow(
        UUID interactionId,
        String interactionTitle,
        UUID organizationId,
        String organizationName,
        UUID directionId,
        String directionName,
        UUID programId,
        String programName,
        List<Product> products,
        String stageName,
        UUID managerId,
        String managerName,
        OffsetDateTime createdAt,
        OffsetDateTime lastEventAt,
        Long daysOnStage,
        String nextAction,
        OffsetDateTime nextActionAt,
        OffsetDateTime eventAt,
        String eventType,
        String fromStageName,
        String comment,
        String authorName,
        Long applications,
        Long participants,
        Long parallelRuns,
        InteractionMarks marks,
        List<Agreement> agreements,
        StageDuration duration,
        Long completed,
        AgreementLine agreement,
        Long paidOrders,
        Long paidStreams,
        Long completedBase,
        ManagerWork managerWork,
        MarketDemand market,
        Long runsWithoutData,
        Demand demand
) {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public ReportRow(
            UUID interactionId,
            String interactionTitle,
            UUID organizationId,
            String organizationName,
            UUID directionId,
            String directionName,
            UUID programId,
            String programName,
            List<Product> products,
            String stageName,
            UUID managerId,
            String managerName,
            OffsetDateTime createdAt,
            OffsetDateTime lastEventAt,
            Long daysOnStage,
            String nextAction,
            OffsetDateTime nextActionAt,
            OffsetDateTime eventAt,
            String eventType,
            String fromStageName,
            String comment,
            String authorName,
            Long applications,
            Long participants,
            Long parallelRuns,
            InteractionMarks marks,
            List<Agreement> agreements,
            StageDuration duration,
            Long completed,
            AgreementLine agreement,
            Long paidOrders,
            Long paidStreams,
            Long completedBase,
            ManagerWork managerWork
    ) {
        this(interactionId, interactionTitle, organizationId, organizationName, directionId, directionName, programId, programName,
                products, stageName, managerId, managerName, createdAt, lastEventAt, daysOnStage, nextAction, nextActionAt, eventAt,
                eventType, fromStageName, comment, authorName, applications, participants, parallelRuns, marks, agreements,
                duration, completed, agreement, paidOrders, paidStreams, completedBase, managerWork, null, null, null);
    }

    public ReportRow(
            UUID interactionId,
            String interactionTitle,
            UUID organizationId,
            String organizationName,
            UUID directionId,
            String directionName,
            UUID programId,
            String programName,
            List<Product> products,
            String stageName,
            UUID managerId,
            String managerName,
            OffsetDateTime createdAt,
            OffsetDateTime lastEventAt,
            Long daysOnStage,
            String nextAction,
            OffsetDateTime nextActionAt,
            OffsetDateTime eventAt,
            String eventType,
            String fromStageName,
            String comment,
            String authorName,
            Long applications,
            Long participants,
            Long parallelRuns,
            InteractionMarks marks,
            List<Agreement> agreements,
            StageDuration duration,
            Long completed,
            AgreementLine agreement,
            Long paidOrders,
            Long paidStreams,
            Long completedBase
    ) {
        this(interactionId, interactionTitle, organizationId, organizationName, directionId, directionName, programId, programName,
                products, stageName, managerId, managerName, createdAt, lastEventAt, daysOnStage, nextAction, nextActionAt, eventAt,
                eventType, fromStageName, comment, authorName, applications, participants, parallelRuns, marks, agreements,
                duration, completed, agreement, paidOrders, paidStreams, completedBase, null, null, null, null);
    }


    public static Long percentOf(Long part, Long base) {
        return part == null || base == null || base <= 0 ? null : (part * 200 + base) / (base * 2);
    }

    public Long completionPercent() {
        return percentOf(completed, completedBase);
    }

    // То же основание, что в «Динамике обучения»; потоки без наблюдения называются числом. Пусто — у программы не было
    // потоков в периоде.
    public String completionBasis() {
        boolean withoutData = runsWithoutData != null && runsWithoutData > 0;
        if (participants == null && !withoutData) {
            return null;
        }
        String basis = LearningDynamicsService.completionBasis(completed, completedBase, participants);
        return withoutData && participants != null ? basis + "; потоков без данных: " + runsWithoutData : basis;
    }

    public Object marketVacancies() {
        return market == null ? null : market.applicable() ? market.vacancies() : MarketDemand.NOT_APPLICABLE;
    }

    public Object marketTrend() {
        return market == null ? null : market.applicable() ? market.trendPercent() : MarketDemand.NOT_APPLICABLE;
    }

    ReportRow withMarket(MarketDemand marketDemand) {
        return new ReportRow(
                interactionId, interactionTitle, organizationId, organizationName, directionId, directionName, programId,
                programName, products, stageName, managerId, managerName, createdAt, lastEventAt, daysOnStage, nextAction,
                nextActionAt, eventAt, eventType, fromStageName, comment, authorName, applications, participants, parallelRuns,
                marks, agreements, duration, completed, agreement, paidOrders, paidStreams, completedBase, managerWork, marketDemand,
                runsWithoutData, demand
        );
    }

    ReportRow withDemand(Demand value) {
        return new ReportRow(
                interactionId, interactionTitle, organizationId, organizationName, directionId, directionName, programId,
                programName, products, stageName, managerId, managerName, createdAt, lastEventAt, daysOnStage, nextAction,
                nextActionAt, eventAt, eventType, fromStageName, comment, authorName, applications, participants, parallelRuns,
                marks, agreements, duration, completed, agreement, paidOrders, paidStreams, completedBase, managerWork, market,
                runsWithoutData, value
        );
    }

    public Integer demandPlace() {
        return demand == null || demand.result() == null ? null : demand.result().place();
    }

    public BigDecimal demandIndex() {
        return demand == null || demand.result() == null ? null : demand.result().index();
    }

    public String demandContributions() {
        return demand == null || demand.result() == null ? null : demand.result().contributions();
    }

    public String demandCoverage() {
        return demand == null || demand.result() == null ? null : demand.result().coverage();
    }

    public String demandTrend() {
        return demand == null ? null : demand.trend();
    }

    public Long enrolledGrowth() {
        return demand == null || demand.result() == null ? null : demand.result().growth();
    }

    public Long activityPercent() {
        return demand == null ? null : percentOf(demand.active7d(), demand.activityBase());
    }

    public Long universityDemand() {
        return demand == null ? null : demand.interactions();
    }

    public String productNames() {
        return products.isEmpty() ? null : products.stream().map(Product::name).collect(Collectors.joining(", "));
    }

    public String workStatus() {
        return marks == null ? null : marks.status().label();
    }

    public String waiting() {
        if (marks == null || marks.waitingOn() == null) {
            return null;
        }
        return marks.waitingNote() == null ? marks.waitingOn().label() : marks.waitingOn().label() + ": " + marks.waitingNote();
    }

    public String problem() {
        return marks == null ? null : marks.problems();
    }

    public String risk() {
        return marks == null ? null : marks.risks();
    }

    public String vendorNames() {
        return agreements.isEmpty()
                ? null
                : agreements.stream().map(Agreement::vendorName).distinct().collect(Collectors.joining(", "));
    }

    public String contractNumbers() {
        return perAgreement(Agreement::contractNumber);
    }

    public String licenseSigned() {
        return perAgreement(agreement -> agreement.licenseSigned() == null
                ? null
                : agreement.licenseSigned() ? "Подписана" : "Не подписана");
    }

    public String licenseExpiryYears() {
        return perAgreement(agreement -> agreement.licenseExpiryYear() == null ? null : agreement.licenseExpiryYear().toString());
    }

    public String transferStatuses() {
        return perAgreement(Agreement::transferStatus);
    }

    public String materialsTransferredOn() {
        return perAgreement(agreement -> agreement.materialsTransferredOn() == null
                ? null
                : DATE.format(agreement.materialsTransferredOn()));
    }

    private String perAgreement(Function<Agreement, String> value) {
        if (agreements.stream().map(value).allMatch(Objects::isNull)) {
            return null;
        }
        if (agreements.size() == 1) {
            return value.apply(agreements.getFirst());
        }
        return agreements.stream()
                .map(agreement -> agreement.productName() + ": "
                        + Objects.requireNonNullElse(value.apply(agreement), ReportColumn.UNSPECIFIED))
                .collect(Collectors.joining("; "));
    }

    public String teamName() {
        if (managerWork != null) {
            return managerWork.teamName();
        }
        return duration == null ? null : duration.teamName();
    }

    public String stageAtStart() {
        return managerWork == null ? null : managerWork.stageAtStart();
    }

    public String stageAtEnd() {
        return managerWork == null ? null : managerWork.stageAtEnd();
    }

    public String stagePosition() {
        return managerWork == null ? null : managerWork.position();
    }

    public Long transitions() {
        return managerWork == null ? null : managerWork.transitions();
    }

    public String periodComments() {
        return managerWork == null ? null : managerWork.comments();
    }

    public String dueState() {
        return managerWork == null ? null : managerWork.dueState();
    }

    public Long overdueDays() {
        return managerWork == null ? null : managerWork.overdueDays();
    }

    public String lmsLearning() {
        return managerWork == null ? null : managerWork.learning();
    }

    public String lmsCheck() {
        return managerWork == null ? null : managerWork.lmsCheck();
    }

    public Long completedCount() {
        return duration == null ? null : duration.completed();
    }

    public BigDecimal averageDays() {
        return duration == null ? null : duration.averageDays();
    }

    public BigDecimal maxDays() {
        return duration == null ? null : duration.maxDays();
    }

    public BigDecimal medianDays() {
        return duration == null ? null : duration.medianDays();
    }

    public BigDecimal p90Days() {
        return duration == null ? null : duration.p90Days();
    }

    public Long currentCount() {
        return duration == null ? null : duration.current();
    }

    public BigDecimal currentMaxDays() {
        return duration == null ? null : duration.currentMaxDays();
    }

    ReportRow withProducts(List<Product> linkedProducts, List<Agreement> linkedAgreements) {
        return new ReportRow(
                interactionId,
                interactionTitle,
                organizationId,
                organizationName,
                directionId,
                directionName,
                programId,
                programName,
                List.copyOf(linkedProducts),
                stageName,
                managerId,
                managerName,
                createdAt,
                lastEventAt,
                daysOnStage,
                nextAction,
                nextActionAt,
                eventAt,
                eventType,
                fromStageName,
                comment,
                authorName,
                applications,
                participants,
                parallelRuns,
                marks,
                List.copyOf(linkedAgreements),
                duration,
                completed,
                agreement,
                paidOrders,
                paidStreams,
                completedBase,
                managerWork,
                market,
                runsWithoutData,
                demand
        );
    }

    public record Product(UUID id, String name) {
    }

    // Факты индекса востребованности из SQL (активность только по свежим наблюдениям, взаимодействия программы) и
    // результат расчёта М17 с трендом к прошлому периоду.
    public record Demand(Long active7d, Long activityBase, Long staleRuns, Long interactions, DemandIndex.Result result,
                         String trend) {
        Demand with(DemandIndex.Result value, String trendText) {
            return new Demand(active7d, activityBase, staleRuns, interactions, value, trendText);
        }

        Demand withInteractions(Long value) {
            return new Demand(active7d, activityBase, staleRuns, value, result, trend);
        }
    }

    public record ManagerWork(
            String teamName,
            String stageAtStart,
            String stageAtEnd,
            String position,
            Long transitions,
            String comments,
            String dueState,
            Long overdueDays,
            String learning,
            String lmsCheck
    ) {
    }

    public record Agreement(
            String productName,
            String vendorName,
            String contractNumber,
            Boolean licenseSigned,
            Integer licenseExpiryYear,
            String transferStatus,
            LocalDate materialsTransferredOn
    ) {
    }

    public record StageDuration(
            String teamName,
            long completed,
            BigDecimal averageDays,
            BigDecimal maxDays,
            long current,
            BigDecimal currentMaxDays,
            BigDecimal medianDays,
            BigDecimal p90Days
    ) {
    }

    public record AgreementLine(
            UUID agreementId,
            UUID activityId,
            String agreement,
            String agreementStatus,
            String agreementTerm,
            String activityKind,
            String activity,
            Long plannedVolume,
            Long actualVolume,
            String unit,
            String plannedDates,
            String actualDates,
            String activityStatus,
            String works,
            String confirmations,
            String confirmationLinks
    ) {
    }
}
