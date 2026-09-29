package ru.rtk.crm.catalog;

import java.util.EnumSet;
import java.util.Set;

public enum OrganizationHistoryKind {
    CREATED,
    TRANSITIONED,
    COMMENTED,
    STAGES_EDITED,
    PLAN_UPDATED,
    DETAILS_UPDATED,
    STATUS_CHANGED,
    AGREEMENT_UPDATED,
    ATTACHMENT_DELETED,
    STAGE_COMPLETED,
    STAGE_COMPLETION_CLEARED,
    ASSIGNMENT,
    CONTACT;

    static final Set<OrganizationHistoryKind> WORK_KINDS = EnumSet.range(CREATED, STAGE_COMPLETION_CLEARED);
}
