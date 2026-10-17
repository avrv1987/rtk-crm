package ru.rtk.crm.access;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.rtk.crm.access.AccountSyncRepository.Account;
import ru.rtk.crm.access.AccountSyncRepository.RoleAccount;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;

@Service
public class AccountSyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountSyncService.class);

    private final AccountSyncRepository accountSyncRepository;
    private final AdminCrmProfileRepository adminCrmProfileRepository;
    private final KeycloakAccountClient keycloakAccountClient;
    private final AuditJournalRepository auditJournalRepository;

    public AccountSyncService(
            AccountSyncRepository accountSyncRepository,
            AdminCrmProfileRepository adminCrmProfileRepository,
            KeycloakAccountClient keycloakAccountClient,
            AuditJournalRepository auditJournalRepository
    ) {
        this.accountSyncRepository = accountSyncRepository;
        this.adminCrmProfileRepository = adminCrmProfileRepository;
        this.keycloakAccountClient = keycloakAccountClient;
        this.auditJournalRepository = auditJournalRepository;
    }

    public AdminCrmProfile sync(CrmProfile actor, UUID profileId, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        Account account = accountSyncRepository.findAccount(profileId).orElseThrow(AdminCrmProfileNotFoundException::new);
        if (!account.synced()) {
            if (!keycloakAccountClient.configured()) {
                LOGGER.warn("Keycloak account of CRM profile {} requires synchronization: account sync is not configured", profileId);
                throw AccountSyncException.notConfigured();
            }
            try {
                keycloakAccountClient.setEnabled(account.subject(), account.active());
            } catch (AccountSyncException exception) {
                LOGGER.warn("Keycloak account of CRM profile {} requires synchronization: {}", profileId, exception.getMessage());
                auditJournalRepository.record(
                        AuditAction.ACCOUNT_SYNC_FAILED, actor.id(), "PROFILE", profileId, null, exception.getMessage(), requestId
                );
                throw exception;
            }
            if (accountSyncRepository.markSynced(profileId, account.active())) {
                auditJournalRepository.record(
                        account.active() ? AuditAction.ACCOUNT_ENABLED : AuditAction.ACCOUNT_DISABLED,
                        actor.id(), "PROFILE", profileId, null, null, requestId
                );
            }
        }
        return adminCrmProfileRepository.findById(profileId).orElseThrow(AdminCrmProfileNotFoundException::new);
    }

    public RoleMigration migrateRoles(CrmProfile actor, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        if (!keycloakAccountClient.configured()) {
            throw new AccountSyncException("Связь с Keycloak не настроена: задайте APP_KEYCLOAK_ACCOUNT_SYNC_CLIENT_SECRET; роли не перенесены");
        }
        int assigned = 0;
        int kept = 0;
        int failed = 0;
        for (RoleAccount account : accountSyncRepository.findRolesNotInKeycloak()) {
            try {
                if (keycloakAccountClient.addCrmRoleIfNone(account.subject(), account.role())) {
                    assigned++;
                } else {
                    kept++;
                }
                adminCrmProfileRepository.markRoleSynced(account.id(), OffsetDateTime.now());
            } catch (AccountSyncException exception) {
                LOGGER.warn("Role of CRM profile {} is not moved to Keycloak: {}", account.id(), exception.getMessage());
                failed++;
            }
        }
        auditJournalRepository.record(
                AuditAction.ACCOUNT_ROLES_MIGRATED, actor.id(), "PROFILE", null, null,
                "назначено: " + assigned + "; уже были в Keycloak: " + kept + "; не перенесено: " + failed, requestId
        );
        return new RoleMigration(assigned, kept, failed);
    }

    public AdminCrmProfile syncAfterChange(CrmProfile actor, UUID profileId, String requestId) {
        try {
            return sync(actor, profileId, requestId);
        } catch (AccountSyncException exception) {
            return adminCrmProfileRepository.findById(profileId)
                    .orElseThrow(AdminCrmProfileNotFoundException::new)
                    .withAccountSyncError(exception.getMessage());
        }
    }

    public record RoleMigration(int assigned, int kept, int failed) {
    }
}
