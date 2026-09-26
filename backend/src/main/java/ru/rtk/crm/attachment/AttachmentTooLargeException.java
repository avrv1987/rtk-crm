package ru.rtk.crm.attachment;

public class AttachmentTooLargeException extends RuntimeException {
    public AttachmentTooLargeException() {
        super("Файл больше допустимого размера");
    }
}
