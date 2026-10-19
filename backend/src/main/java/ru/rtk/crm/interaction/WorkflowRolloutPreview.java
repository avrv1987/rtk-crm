package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

public record WorkflowRolloutPreview(
        UUID templateId,
        int targetVersion,
        List<WorkflowTemplateStage> targetStages,
        List<Source> sources,
        List<Work> works
) {
    public record Source(int version, int works, List<StageMatch> stages) {
    }

    public record StageMatch(
            UUID stageId,
            String name,
            UUID targetStageId,
            String targetStageName,
            WorkflowStageMatch match,
            int works
    ) {
    }

    public record Work(
            UUID id,
            String title,
            String organizationName,
            int version,
            int sourceVersion,
            String currentStageName,
            String targetStageName,
            boolean currentStageRemoved,
            boolean edited,
            String changes,
            boolean noExit,
            boolean landsOnFinal
    ) {
    }
}
