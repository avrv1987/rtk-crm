package ru.rtk.crm.report;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
        String nextAction,
        OffsetDateTime nextActionAt,
        OffsetDateTime eventAt,
        String eventType,
        String fromStageName,
        String comment,
        String authorName,
        Long applications,
        Long participants,
        Long parallelRuns
) {
    public String productNames() {
        return products.isEmpty() ? null : products.stream().map(Product::name).collect(Collectors.joining(", "));
    }

    ReportRow withProducts(List<Product> linkedProducts) {
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
                nextAction,
                nextActionAt,
                eventAt,
                eventType,
                fromStageName,
                comment,
                authorName,
                applications,
                participants,
                parallelRuns
        );
    }

    public record Product(UUID id, String name) {
    }
}
