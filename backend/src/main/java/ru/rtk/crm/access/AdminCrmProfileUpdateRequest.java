package ru.rtk.crm.access;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class AdminCrmProfileUpdateRequest {
    @NotNull
    @Min(0)
    private final Integer version;
    private String displayName;
    private UserRole role;
    private UUID teamId;
    private boolean teamIdPresent;
    private Boolean active;

    @JsonCreator
    public AdminCrmProfileUpdateRequest(@JsonProperty(value = "version", required = true) Integer version) {
        this.version = version;
    }

    public static AdminCrmProfileUpdateRequest active(int version, boolean active) {
        AdminCrmProfileUpdateRequest request = new AdminCrmProfileUpdateRequest(version);
        request.setActive(active);
        return request;
    }

    public Integer version() {
        return version;
    }

    public String displayName() {
        return displayName;
    }

    public UserRole role() {
        return role;
    }

    public UUID teamId() {
        return teamId;
    }

    @JsonIgnore
    public boolean teamIdPresent() {
        return teamIdPresent;
    }

    public Boolean active() {
        return active;
    }

    @JsonSetter("displayName")
    public AdminCrmProfileUpdateRequest setDisplayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    @JsonSetter("role")
    public AdminCrmProfileUpdateRequest setRole(UserRole role) {
        this.role = role;
        return this;
    }

    @JsonSetter("teamId")
    public AdminCrmProfileUpdateRequest setTeamId(UUID teamId) {
        this.teamId = teamId;
        this.teamIdPresent = true;
        return this;
    }

    @JsonSetter("active")
    public AdminCrmProfileUpdateRequest setActive(Boolean active) {
        this.active = active;
        return this;
    }

    @JsonAnySetter
    public void rejectUnsupportedField(String field, Object value) {
        throw new IllegalArgumentException("Only version, displayName, role, teamId and active are supported");
    }
}
