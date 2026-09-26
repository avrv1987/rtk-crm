package ru.rtk.crm.catalog;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class OrganizationAssignmentRequest {
    @NotNull
    @Min(0)
    private final Integer version;
    private UUID ownerManagerId;
    private boolean ownerManagerIdPresent;
    private String handoverNote;

    public OrganizationAssignmentRequest(Integer version, UUID ownerManagerId) {
        this.version = version;
        this.ownerManagerId = ownerManagerId;
        this.ownerManagerIdPresent = true;
    }

    @JsonCreator
    public OrganizationAssignmentRequest(@JsonProperty("version") Integer version) {
        this.version = version;
    }

    public Integer version() {
        return version;
    }

    public UUID ownerManagerId() {
        return ownerManagerId;
    }

    @JsonSetter("ownerManagerId")
    public void setOwnerManagerId(UUID ownerManagerId) {
        this.ownerManagerId = ownerManagerId;
        this.ownerManagerIdPresent = true;
    }

    public String handoverNote() {
        return handoverNote;
    }

    @JsonSetter("handoverNote")
    public void setHandoverNote(String handoverNote) {
        this.handoverNote = handoverNote;
    }

    @JsonIgnore
    public boolean ownerManagerIdPresent() {
        return ownerManagerIdPresent;
    }
}
