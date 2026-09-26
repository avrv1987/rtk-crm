package ru.rtk.crm.attachment;

public class AttachmentBindingException extends RuntimeException {
    public AttachmentBindingException() {
        super("Прикрепить можно только проверенные файлы выбранного этапа, ещё не связанные с событием");
    }
}
