package ru.rtk.crm.privacy;

import ru.rtk.crm.audit.AuditEntry;

public record RetentionPolicy(
        long reportFilesDays,
        long inactiveContactsDays,
        long dismissedProfilesDays,
        long auditEventsDays,
        String schedule,
        AuditEntry lastRun
) {
}
