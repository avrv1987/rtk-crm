package ru.rtk.crm.interaction;

import java.util.List;

public record WorkflowRolloutRequest(
        List<WorkflowStageMapInput> stageMap,
        List<WorkflowRolloutWorkInput> works,
        Boolean replaceLocalChanges
) {
}
