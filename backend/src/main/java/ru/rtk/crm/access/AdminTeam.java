package ru.rtk.crm.access;

import java.util.List;
import java.util.UUID;

public record AdminTeam(
        UUID id,
        String name,
        int version,
        boolean archived,
        List<String> leaderNames,
        List<String> managerNames,
        long organizationCount,
        long otherProfileCount
) {
}
