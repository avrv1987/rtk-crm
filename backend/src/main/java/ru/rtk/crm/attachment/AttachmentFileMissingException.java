package ru.rtk.crm.attachment;

public class AttachmentFileMissingException extends AttachmentStorageException {
    public AttachmentFileMissingException(Throwable cause) {
        super("Attachment file is missing in storage", cause);
    }
}
