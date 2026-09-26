package ru.rtk.crm.access;

public class AccountSyncException extends RuntimeException {
    public AccountSyncException(String message) {
        super(message);
    }

    public AccountSyncException(String message, Throwable cause) {
        super(message, cause);
    }

    public static AccountSyncException notConfigured() {
        return new AccountSyncException(
                "Связь с Keycloak не настроена: задайте APP_KEYCLOAK_ACCOUNT_SYNC_CLIENT_SECRET; блокировка в CRM действует"
        );
    }
}
