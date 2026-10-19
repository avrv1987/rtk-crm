package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

import ru.rtk.crm.catalog.OrganizationType;

public final class InteractionKindModels {
    private InteractionKindModels() {
    }

    /** Вид взаимодействия; teamTemplate* — шаблон команды текущего пользователя для этого вида, если он задан. */
    public record InteractionKind(
            UUID id,
            String name,
            String description,
            boolean active,
            UUID templateId,
            String templateName,
            int sortOrder,
            int version,
            UUID teamTemplateId,
            String teamTemplateName,
            boolean defaultKind
    ) {
        public InteractionKind(UUID id, String name, String description, boolean active, UUID templateId, String templateName,
                               int sortOrder, int version, UUID teamTemplateId, String teamTemplateName) {
            this(id, name, description, active, templateId, templateName, sortOrder, version, teamTemplateId, teamTemplateName, false);
        }
    }

    public record InteractionKindRef(UUID id, String name) {
    }

    public record InteractionKindRequest(
            Integer version,
            String name,
            String description,
            Boolean active,
            UUID templateId,
            Integer sortOrder,
            Boolean defaultKind
    ) {
        public InteractionKindRequest(Integer version, String name, String description, Boolean active, UUID templateId,
                                      Integer sortOrder) {
            this(version, name, description, active, templateId, sortOrder, null);
        }
    }

    public record TeamTemplateRequest(UUID templateId) {
    }

    public record InteractionKindRule(
            UUID id,
            UUID kindId,
            String kindName,
            OrganizationType organizationType,
            UUID programId,
            String programName,
            UUID productId,
            String productName,
            int priority,
            int version
    ) {
    }

    public record InteractionKindRuleRequest(
            Integer version,
            UUID kindId,
            OrganizationType organizationType,
            UUID programId,
            UUID productId,
            Integer priority
    ) {
    }

    public enum KindSource {
        SELECTED,
        RULE,
        FIRST_ACTIVE
    }

    public enum TemplateSource {
        TEAM_KIND,
        KIND,
        DEFAULT
    }

    /** Что получит новая карточка: вид и шаблон маршрута с объяснением, откуда они взяты. */
    public record InteractionKindResolution(
            UUID kindId,
            String kindName,
            KindSource kindSource,
            UUID templateId,
            String templateName,
            TemplateSource templateSource
    ) {
    }

    record ResolvedRoute(UUID kindId, WorkflowTemplate template) {
    }

}
