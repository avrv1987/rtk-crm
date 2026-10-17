package ru.rtk.crm.interaction;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public record WorkflowRoute(List<WorkflowTemplateStage> stages, List<WorkflowTemplateTransition> transitions) {
    static WorkflowRoute read(ObjectMapper objectMapper, String value) {
        try {
            return objectMapper.readValue(value, WorkflowRoute.class);
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
