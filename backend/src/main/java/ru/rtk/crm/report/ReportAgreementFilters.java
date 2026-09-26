package ru.rtk.crm.report;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.interaction.ProductAgreementRules;
import ru.rtk.crm.interaction.ProductTransferKind;

public record ReportAgreementFilters(
        List<UUID> vendorIds,
        Boolean licenseSigned,
        Integer licenseExpiresBy,
        List<ProductTransferKind> notTransferred
) {
    public static ReportAgreementFilters none() {
        return new ReportAgreementFilters(List.of(), null, null, List.of());
    }

    public ReportAgreementFilters normalized() {
        if (notTransferred != null && notTransferred.stream().anyMatch(Objects::isNull)) {
            throw new InteractionValidationException("filters.agreement.notTransferred", "Список не должен содержать пустых значений");
        }
        return new ReportAgreementFilters(
                ReportFilters.ids(vendorIds, "filters.agreement.vendorIds"),
                licenseSigned,
                ProductAgreementRules.optionalLicenseYear(licenseExpiresBy, "filters.agreement.licenseExpiresBy"),
                notTransferred == null ? List.of() : List.copyOf(new LinkedHashSet<>(notTransferred))
        );
    }

    public boolean unrestricted() {
        return vendorIds.isEmpty() && licenseSigned == null && licenseExpiresBy == null && notTransferred.isEmpty();
    }
}
