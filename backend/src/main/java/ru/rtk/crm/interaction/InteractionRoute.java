package ru.rtk.crm.interaction;

import java.util.UUID;

public record InteractionRoute(UUID templateId, String templateName, int version, int latestVersion) {
}
