package ru.rtk.crm.mcp;

import java.util.Set;

import ru.rtk.crm.access.CrmProfile;

public record McpCaller(CrmProfile profile, String clientId, Set<String> scopes, String requestId) {
    public static final String ATTRIBUTE = McpCaller.class.getName();
}
