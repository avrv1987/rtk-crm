package ru.rtk.crm.interaction;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record WorkflowRoute(List<WorkflowTemplateStage> stages, List<WorkflowTemplateTransition> transitions) {
    private static final UUID BASE_SIGNING_STAGE = UUID.fromString("00000000-0000-0000-0000-000000000816");
    private static final Map<String, StageLmsRole> LEGACY_LMS_ROLES = Map.of(
            "Обучение преподавателей", StageLmsRole.TEACHER_TRAINING,
            "Занятия", StageLmsRole.CLASSES,
            "Повышение квалификации", StageLmsRole.QUALIFICATION
    );

    // Версии, сохранённые до V59, свойств этапа не содержат. Им подставляются те же отметки, что миграция поставила
    // этапам шаблонов и карточек: возврат к старой версии не должен снимать правило договора и роли LMS.
    static WorkflowRoute read(ObjectMapper objectMapper, String value) {
        try {
            JsonNode tree = objectMapper.readTree(value);
            for (JsonNode stage : tree.path("stages")) {
                if (stage instanceof ObjectNode node && !node.has("requiresAgreement")) {
                    String name = node.path("name").asText("");
                    node.put("requiresAgreement", BASE_SIGNING_STAGE.toString().equals(node.path("id").asText())
                            || "подписание".equals(name.strip().toLowerCase(Locale.ROOT)));
                    StageLmsRole role = LEGACY_LMS_ROLES.get(name);
                    if (role != null) {
                        node.put("lmsRole", role.name());
                    }
                }
            }
            return objectMapper.treeToValue(tree, WorkflowRoute.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored workflow version cannot be read", exception);
        }
    }

    String write(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Workflow version cannot be stored", exception);
        }
    }
}
