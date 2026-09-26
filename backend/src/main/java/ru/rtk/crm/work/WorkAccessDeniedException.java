package ru.rtk.crm.work;

public class WorkAccessDeniedException extends RuntimeException {
    public WorkAccessDeniedException() {
        super("The current profile cannot read this work summary");
    }
}
