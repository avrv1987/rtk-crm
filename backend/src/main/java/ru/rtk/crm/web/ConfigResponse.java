package ru.rtk.crm.web;

public record ConfigResponse(Modules modules) {
    public record Modules(boolean enrolment, boolean partner, boolean max, boolean mcp, boolean mail) {
    }
}
