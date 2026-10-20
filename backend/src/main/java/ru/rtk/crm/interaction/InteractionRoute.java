package ru.rtk.crm.interaction;

import java.util.UUID;

/** fromPreviousKind — вид карточки меняли, а маршрут остался от прежнего вида. */
public record InteractionRoute(UUID templateId, String templateName, int version, int latestVersion, boolean fromPreviousKind) {
    public InteractionRoute(UUID templateId, String templateName, int version, int latestVersion) {
        this(templateId, templateName, version, latestVersion, false);
    }
}
