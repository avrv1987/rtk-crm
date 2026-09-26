package ru.rtk.crm.interaction;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

public final class InteractionPlanRequest {
    private final Integer version;
    private Optional<String> nextAction;
    private Optional<OffsetDateTime> nextActionAt;
    private Optional<UUID> programId;
    private Optional<List<UUID>> productIds;

    public InteractionPlanRequest(
            Integer version,
            Optional<String> nextAction,
            Optional<OffsetDateTime> nextActionAt,
            Optional<UUID> programId,
            Optional<List<UUID>> productIds
    ) {
        this.version = version;
        this.nextAction = nextAction;
        this.nextActionAt = nextActionAt;
        this.programId = programId;
        this.productIds = productIds;
    }

    @JsonCreator
    public InteractionPlanRequest(@JsonProperty("version") Integer version) {
        this.version = version;
    }

    public Integer version() {
        return version;
    }

    public Optional<String> nextAction() {
        return nextAction;
    }

    public Optional<OffsetDateTime> nextActionAt() {
        return nextActionAt;
    }

    public Optional<UUID> programId() {
        return programId;
    }

    public Optional<List<UUID>> productIds() {
        return productIds;
    }

    @JsonSetter("nextAction")
    public void setNextAction(String nextAction) {
        this.nextAction = Optional.ofNullable(nextAction);
    }

    @JsonSetter("nextActionAt")
    public void setNextActionAt(OffsetDateTime nextActionAt) {
        this.nextActionAt = Optional.ofNullable(nextActionAt);
    }

    @JsonSetter("programId")
    public void setProgramId(UUID programId) {
        this.programId = Optional.ofNullable(programId);
    }

    @JsonSetter("productIds")
    public void setProductIds(List<UUID> productIds) {
        this.productIds = Optional.ofNullable(productIds);
    }
}
