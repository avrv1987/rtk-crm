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
        AgreementLine agreement
) {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");


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
        return marks == null ? null : marks.problem();
    }

    public String risk() {
        if (marks == null || marks.riskLevel() == null) {
            return null;
        }
        return marks.riskLevel().label() + ": " + marks.riskReason();
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
        return duration == null ? null : duration.teamName();
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
                agreement
        );
    }

    public record Product(UUID id, String name) {
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
            BigDecimal currentMaxDays
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
