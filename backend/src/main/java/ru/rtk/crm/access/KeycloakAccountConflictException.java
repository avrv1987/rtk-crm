package ru.rtk.crm.access;

public class KeycloakAccountConflictException extends AccountSyncException {
    public KeycloakAccountConflictException() {
        super("В Keycloak уже есть учётная запись с таким логином или почтой; уточните почту контакта или обратитесь к администратору");
    }
}
