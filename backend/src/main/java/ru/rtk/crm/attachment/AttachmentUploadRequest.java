package ru.rtk.crm.attachment;

import java.util.UUID;

public record AttachmentUploadRequest(UUID stageId, AttachmentKind kind, UUID replacesId) {
}
