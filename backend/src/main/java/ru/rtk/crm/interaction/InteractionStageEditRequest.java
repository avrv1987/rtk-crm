package ru.rtk.crm.interaction;

import java.util.List;

public record InteractionStageEditRequest(Integer version, List<InteractionStageEditOperation> operations) {
}
