package ru.rtk.crm.access;

public class KeycloakAccountConflictException extends AccountSyncException {
    private final String code;

    public KeycloakAccountConflictException() {
        this(
                "PARTNER_ACCOUNT_CONFLICT",
                "В Keycloak уже есть учётная запись с таким логином или почтой; уточните почту контакта или обратитесь к администратору"
        );
    }

    private KeycloakAccountConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static KeycloakAccountConflictException employee() {
        return new KeycloakAccountConflictException(
                "ACCOUNT_CONFLICT", "В Keycloak уже есть учётная запись с таким логином или почтой; укажите другие"
        );
    }

    public String code() {
        return code;
    }
}
