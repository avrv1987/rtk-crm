package ru.rtk.crm.interaction;

import java.util.List;

public final class ProductAgreementRules {
    public static final int MIN_LICENSE_YEAR = 2000;
    public static final int MAX_LICENSE_YEAR = 2100;
    public static final String TRANSFERRED = "Передано";
    public static final String PARTLY_TRANSFERRED = "Передано частично";
    public static final String NOT_TRANSFERRED = "Не передано";

    private ProductAgreementRules() {
    }

    public static Integer optionalLicenseYear(Integer year, String field) {
        if (year != null && (year < MIN_LICENSE_YEAR || year > MAX_LICENSE_YEAR)) {
            throw new InteractionValidationException(
                    field,
                    "Год должен быть от " + MIN_LICENSE_YEAR + " до " + MAX_LICENSE_YEAR
            );
        }
        return year;
    }

    public static String transferStatus(List<ProductTransfer> transfers) {
        if (transfers.isEmpty()) {
            return null;
        }
        long transferred = transfers.stream().filter(transfer -> transfer.status() == ProductTransferStatus.TRANSFERRED).count();
        if (transferred == ProductTransferKind.values().length) {
            return TRANSFERRED;
        }
        return transferred > 0 ? PARTLY_TRANSFERRED : NOT_TRANSFERRED;
    }
}
