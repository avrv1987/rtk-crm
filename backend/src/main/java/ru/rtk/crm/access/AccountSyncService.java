package ru.rtk.crm.access;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.rtk.crm.access.AccountSyncRepository.Account;
import ru.rtk.crm.access.AccountSyncRepository.RoleAccount;
import ru.rtk.crm.access.AdminCrmProfileRepository.ProfileState;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;

@Service
public class AccountSyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountSyncService.class);

    private final AccountSyncRepository accountSyncRepository;
    private final AdminCrmProfileRepository adminCrmProfileRepository;
    private final KeycloakAccountClient keycloakAccountClient;
    private final AuditJournalRepository auditJournalRepository;
    private final TransactionTemplate transactionTemplate;

    public AccountSyncService(
            AccountSyncRepository accountSyncRepository,
            AdminCrmProfileRepository adminCrmProfileRepository,
            KeycloakAccountClient keycloakAccountClient,
            AuditJournalRepository auditJournalRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.accountSyncRepository = accountSyncRepository;
        this.adminCrmProfileRepository = adminCrmProfileRepository;
        this.keycloakAccountClient = keycloakAccountClient;
        this.auditJournalRepository = auditJournalRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public AdminCrmProfile sync(CrmProfile actor, UUID profileId, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        Account account = accountSyncRepository.findAccount(profileId).orElseThrow(AdminCrmProfileNotFoundException::new);
        if (account.roleReconcileRequired()) {
            try {
                reconcileRole(profileId, true, true);
            } catch (AccountSyncException exception) {
                recordReconcileFailure(actor.id(), profileId, exception, requestId);
                throw exception;
            }
            auditJournalRepository.record(AuditAction.ACCOUNT_ROLE_RECONCILED, actor.id(), "PROFILE", profileId, null, null, requestId);
        }
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

    // Записывает в Keycloak роль и требование второго фактора, сохранённые в CRM, и снимает признак «требует сверки».
    // Строка профиля заблокирована на время запросов к Keycloak: иначе параллельная команда успела бы сохранить
    // в CRM другую роль между чтением профиля и записью в Keycloak, и сверка вернула бы устаревшую роль.
    void reconcileRole(UUID profileId, boolean role, boolean privileged) {
        transactionTemplate.executeWithoutResult(status -> {
            AdminCrmProfile current = adminCrmProfileRepository.findByIdForUpdate(profileId)
                    .orElseThrow(AdminCrmProfileNotFoundException::new);
            String subject = accountSyncRepository.findAccount(profileId).orElseThrow(AdminCrmProfileNotFoundException::new).subject();
            if (role) {
                keycloakAccountClient.setCrmRole(subject, current.role());
                adminCrmProfileRepository.markRoleSynced(profileId, OffsetDateTime.now());
            }
            if (privileged && keycloakAccountClient.configured()) {
                keycloakAccountClient.setPrivileged(subject, ProfileState.of(current).privileged());
            }
            adminCrmProfileRepository.setRoleReconcileRequired(profileId, false);
        });
    }

    void recordReconcileFailure(UUID actorProfileId, UUID profileId, RuntimeException failure, String requestId) {
        LOGGER.warn("Keycloak role of CRM profile {} requires reconciliation: {}", profileId, failure.getMessage());
        adminCrmProfileRepository.setRoleReconcileRequired(profileId, true);
        auditJournalRepository.record(
                AuditAction.ACCOUNT_SYNC_FAILED, actorProfileId, "PROFILE", profileId, null,
                "роль в Keycloak требует сверки с CRM: " + failure.getMessage(), requestId
        );
    }

    public record RoleMigration(int assigned, int kept, int failed) {
    }
}
