package ru.rtk.crm.access;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminCrmProfile(
        UUID id,
        String displayName,
        UserRole role,
        UUID teamId,
        String teamName,
        boolean active,
        boolean enrolmentOperator,
        boolean pendingActivation,
        int accessRevision,
        int version,
        String login,
        OffsetDateTime activationRequestedAt,
        boolean accountSyncRequired,
        String accountSyncError,
        String partnerOrganizationName
) {
    AdminCrmProfile withAccountSyncError(String error) {
        return new AdminCrmProfile(
                id, displayName, role, teamId, teamName, active, enrolmentOperator, pendingActivation, accessRevision, version, login,
                activationRequestedAt, accountSyncRequired, error, partnerOrganizationName
        );
    }
}
