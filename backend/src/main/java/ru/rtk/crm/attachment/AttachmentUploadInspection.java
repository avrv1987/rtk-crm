package ru.rtk.crm.attachment;

public record AttachmentUploadInspection(
        String originalName,
        String mediaType,
        long sizeBytes,
        String checksum
) {
}
