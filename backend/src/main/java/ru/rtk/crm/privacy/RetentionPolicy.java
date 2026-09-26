package ru.rtk.crm.privacy;

import ru.rtk.crm.audit.AuditEntry;
import ru.rtk.crm.enrolment.LearnerPrivacyService;

public record RetentionPolicy(
        long reportFilesDays,
        long inactiveContactsDays,
        long dismissedProfilesDays,
        long auditEventsDays,
        String learnerProfilesTerm,
        String schedule,
        AuditEntry lastRun,
        LearnerPrivacyService.Counters learners
) {
}
