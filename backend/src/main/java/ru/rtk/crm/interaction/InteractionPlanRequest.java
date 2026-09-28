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
    private Optional<String> title;
    private Optional<OffsetDateTime> lastContactAt;
    private Optional<List<UUID>> contactIds;
    private Optional<Boolean> nextStepPartnerVisible;

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

    public Optional<String> title() {
        return title;
    }

    public Optional<OffsetDateTime> lastContactAt() {
        return lastContactAt;
    }

    public Optional<List<UUID>> contactIds() {
        return contactIds;
    }

    public Optional<Boolean> nextStepPartnerVisible() {
        return nextStepPartnerVisible;
    }

    @JsonSetter("nextStepPartnerVisible")
    public void setNextStepPartnerVisible(Boolean nextStepPartnerVisible) {
        this.nextStepPartnerVisible = Optional.ofNullable(nextStepPartnerVisible);
    }

    @JsonSetter("title")
    public void setTitle(String title) {
        this.title = Optional.ofNullable(title);
    }

    @JsonSetter("lastContactAt")
    public void setLastContactAt(OffsetDateTime lastContactAt) {
        this.lastContactAt = Optional.ofNullable(lastContactAt);
    }

    @JsonSetter("contactIds")
    public void setContactIds(List<UUID> contactIds) {
        this.contactIds = Optional.ofNullable(contactIds);
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
