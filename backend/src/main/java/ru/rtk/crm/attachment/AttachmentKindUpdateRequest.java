package ru.rtk.crm.attachment;

public record AttachmentKindUpdateRequest(Integer version, String kind, Boolean partnerVisible) {
}
