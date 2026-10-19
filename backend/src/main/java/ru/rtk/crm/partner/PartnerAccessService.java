package ru.rtk.crm.partner;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import ru.rtk.crm.access.AccountSyncException;
import ru.rtk.crm.access.AdminCrmProfileRepository;
import ru.rtk.crm.access.AdminCrmProfileRepository.ProfileState;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.KeycloakAccountClient;
import ru.rtk.crm.access.TemporaryPassword;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.CatalogArchivedEvent;
import ru.rtk.crm.catalog.ContactEmailChangedEvent;
import ru.rtk.crm.catalog.ContactNotFoundException;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationStatus;
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.mail.MailInvitationService;
import ru.rtk.crm.partner.PartnerAccessRepository.ManagedOrganization;
import ru.rtk.crm.partner.PartnerAccessRepository.PartnerContact;
import ru.rtk.crm.partner.PartnerAccessRepository.PartnerProfile;
import ru.rtk.crm.partner.PartnerModels.PartnerAccess;
import ru.rtk.crm.partner.PartnerModels.PartnerAccessGranted;
import ru.rtk.crm.security.RequestId;

@Service
public class PartnerAccessService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PartnerAccessService.class);
    private static final int KEYCLOAK_NAME_LIMIT = 255;
    private static final String SYSTEM_REQUEST = "system";

    private final PartnerAccessRepository partnerAccessRepository;
    private final OrganizationRepository organizationRepository;
    private final AdminCrmProfileRepository adminCrmProfileRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final KeycloakAccountClient keycloakAccountClient;
    private final AuditJournalRepository auditJournalRepository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<MailInvitationService> mailInvitations;
    private final SecureRandom random = new SecureRandom();

    public PartnerAccessService(
            PartnerAccessRepository partnerAccessRepository,
            OrganizationRepository organizationRepository,
            AdminCrmProfileRepository adminCrmProfileRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            KeycloakAccountClient keycloakAccountClient,
            AuditJournalRepository auditJournalRepository,
            ObjectMapper objectMapper,
            ObjectProvider<MailInvitationService> mailInvitations
    ) {
        this.partnerAccessRepository = partnerAccessRepository;
        this.organizationRepository = organizationRepository;
        this.adminCrmProfileRepository = adminCrmProfileRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.keycloakAccountClient = keycloakAccountClient;
        this.auditJournalRepository = auditJournalRepository;
        this.objectMapper = objectMapper;
        this.mailInvitations = mailInvitations;
    }

    // При включённом модуле почты координатор получает письмо со ссылкой и видит временный пароль сам; без почты в
    // контакте или без модуля пароль показывается менеджеру, как раньше.
    private Optional<String> invite(UUID actorId, UUID profileId, String login, String email, boolean newAccount, String requestId) {
        MailInvitationService invitations = mailInvitations.getIfAvailable();
        return invitations == null || email == null
                ? Optional.empty()
                : invitations.invite(actorId, profileId, login, email, newAccount, requestId);
    }

    @Transactional(readOnly = true)
    public List<PartnerAccess> list(CrmProfile actor, UUID organizationId) {
        requireVisible(actor, organizationId);
        return partnerAccessRepository.findByOrganization(organizationId);
    }

    @Transactional
    public PartnerAccessGranted open(CrmProfile actor, UUID organizationId, UUID contactId, String requestId) {
        ManagedOrganization organization = requireManageable(actor, organizationId);
        if (organization.status() != OrganizationStatus.ACTIVE || organization.type() == OrganizationType.OPEN_ENROLLMENT) {
            throw new InteractionValidationException(
                    "organizationId", "Кабинет открывают только действующему вузу, школе или колледжу, не из архива и не на подтверждении"
            );
        }
        PartnerContact contact = partnerAccessRepository.lockContact(organizationId, requiredId(contactId, "contactId"))
                .orElseThrow(ContactNotFoundException::new);
        if (contact.inactive() || contact.personalDataStatus() != PersonalDataStatus.ACTIVE) {
            throw new InteractionValidationException(
                    "contactId", "Доступ открывают только актуальному контакту без ограничения обработки данных"
            );
        }
        PartnerProfile existing = partnerAccessRepository.lockProfileByContact(contact.id()).orElse(null);
        if (existing != null && existing.active()) {
            throw PartnerException.accessExists();
        }
        String password = TemporaryPassword.generate();
        OffsetDateTime now = OffsetDateTime.now();
        UUID profileId;
        String login;
        String subject;
        Optional<String> invitationSentTo;
        String email = contact.email() == null || contact.email().isBlank() ? null : contact.email().strip().toLowerCase(Locale.ROOT);
        if (existing == null) {
            String issuer = adminCrmProfileRepository.findIssuer(actor.id())
                    .orElseThrow(() -> new IllegalStateException("Actor profile is unavailable for partner access"));
            login = email == null ? "vuz-" + HexFormat.of().formatHex(randomBytes(4)) : email;
            subject = keycloakAccountClient.createPartnerUser(
                    login, email, limit(contact.name()), limit(organization.name()), password
            );
            profileId = UUID.randomUUID();
            partnerAccessRepository.insert(profileId, issuer, subject, contact.name(), login, organizationId, contact.id(), now);
            recordProfileEvent(actor.id(), profileId, contact.name(), false, true, requestId, now);
            invitationSentTo = invite(actor.id(), profileId, login, email, true, requestId);
            auditJournalRepository.record(
                    AuditAction.ACCOUNT_CREATED, actor.id(), "PROFILE", profileId, contact.name(),
                    details(organization, contact) + "; логин " + login
                            + (invitationSentTo.isPresent() ? "; приглашение отправлено на почту" : "; временный пароль выдан"),
                    requestId
            );
        } else {
            profileId = existing.id();
            subject = existing.subject();
            keycloakAccountClient.enableWithTemporaryPassword(subject, password);
            partnerAccessRepository.changeActive(profileId, true, now);
            partnerAccessRepository.markIdpEnabled(profileId, true);
            recordProfileEvent(actor.id(), profileId, existing.displayName(), false, true, requestId, now);
            login = partnerAccessRepository.findAccess(profileId).login();
            invitationSentTo = invite(actor.id(), profileId, login, email, true, requestId);
            auditJournalRepository.record(
                    AuditAction.ACCOUNT_ENABLED, actor.id(), "PROFILE", profileId, existing.displayName(),
                    details(organization, contact)
                            + (invitationSentTo.isPresent() ? "; приглашение отправлено на почту заново" : "; временный пароль выдан заново"),
                    requestId
            );
        }
        assignPartnerRole(actor.id(), profileId, subject, contact.name(), requestId, now);
        return new PartnerAccessGranted(partnerAccessRepository.findAccess(profileId), login,
                invitationSentTo.isPresent() ? null : password, invitationSentTo.orElse(null));
    }

    @Transactional
    public PartnerAccess close(CrmProfile actor, UUID organizationId, UUID contactId, String requestId) {
        requireManageable(actor, organizationId);
        partnerAccessRepository.lockContact(organizationId, requiredId(contactId, "contactId"))
                .orElseThrow(ContactNotFoundException::new);
        PartnerProfile profile = partnerAccessRepository.lockProfileByContact(contactId)
                .orElseThrow(PartnerException::accessMissing);
        if (!profile.active()) {
            return partnerAccessRepository.findAccess(profile.id());
        }
        return deactivate(actor.id(), profile, requestId);
    }

    // Новая почта контакта сразу становится почтой учётки координатора, иначе письма и приглашения уходили бы на
    // старый адрес. Логин учётки не меняется. Сбой Keycloak откатывает и правку контакта: адреса не расходятся.
    @EventListener
    @Transactional
    public void contactEmailChanged(ContactEmailChangedEvent event) {
        Optional<PartnerProfile> profile = partnerAccessRepository.lockProfileByContact(event.contactId()).filter(PartnerProfile::active);
        if (profile.isEmpty()) {
            return;
        }
        if (event.email() == null) {
            throw new InteractionValidationException("email",
                    "У контакта открыт доступ в кабинет вуза: почту нельзя удалить, сначала закройте доступ");
        }
        keycloakAccountClient.changeEmail(profile.get().subject(), event.email().strip().toLowerCase(Locale.ROOT));
        auditJournalRepository.record(AuditAction.ACCOUNT_EMAIL_CHANGED, event.actorProfileId(), "PROFILE", profile.get().id(),
                profile.get().displayName(), "почта учётки обновлена вместе с почтой контакта", currentRequestId());
    }

    @EventListener
    @Transactional
    public void catalogArchived(CatalogArchivedEvent event) {
        String requestId = event.requestId() == null ? currentRequestId() : event.requestId();
        for (UUID profileId : partnerAccessRepository.findActiveProfileIds(event.organizationId(), event.contactId())) {
            partnerAccessRepository.lockProfile(profileId)
                    .filter(PartnerProfile::active)
                    .ifPresent(profile -> deactivate(event.actorProfileId(), profile, requestId));
        }
    }

    private void assignPartnerRole(
            UUID actorProfileId,
            UUID profileId,
            String subject,
            String displayName,
            String requestId,
            OffsetDateTime now
    ) {
        try {
            keycloakAccountClient.setCrmRole(subject, UserRole.PARTNER);
            adminCrmProfileRepository.markRoleSynced(profileId, now);
        } catch (AccountSyncException exception) {
            LOGGER.warn("Keycloak role of partner profile {} is not assigned: {}", profileId, exception.getMessage());
            auditJournalRepository.record(
                    AuditAction.ACCOUNT_SYNC_FAILED, actorProfileId, "PROFILE", profileId, displayName,
                    "роль не назначена: " + exception.getMessage(), requestId
            );
        }
    }

    private PartnerAccess deactivate(UUID actorProfileId, PartnerProfile profile, String requestId) {
        OffsetDateTime now = OffsetDateTime.now();
        partnerAccessRepository.changeActive(profile.id(), false, now);
        recordProfileEvent(actorProfileId, profile.id(), profile.displayName(), true, false, requestId, now);
        // Без доступа координатор не ответит: открытые запросы ему закрываются с причиной, а не висят «Ожидает ответа».
        int cancelled = partnerAccessRepository.cancelOpenRequests(partnerAccessRepository.contactOf(profile.id()),
                "Доступ координатора в кабинет вуза закрыт", now);
        try {
            keycloakAccountClient.setEnabled(profile.subject(), false);
        } catch (AccountSyncException exception) {
            LOGGER.warn("Keycloak account of partner profile {} requires synchronization: {}", profile.id(), exception.getMessage());
            auditJournalRepository.record(
                    AuditAction.ACCOUNT_SYNC_FAILED, actorProfileId, "PROFILE", profile.id(), profile.displayName(),
                    exception.getMessage(), requestId
            );
            return partnerAccessRepository.findAccess(profile.id()).withAccountSyncError(exception.getMessage());
        }
        partnerAccessRepository.markIdpEnabled(profile.id(), false);
        auditJournalRepository.record(
                AuditAction.ACCOUNT_DISABLED, actorProfileId, "PROFILE", profile.id(), profile.displayName(),
                cancelled == 0 ? null : "отменено открытых запросов вузу: " + cancelled, requestId
        );
        return partnerAccessRepository.findAccess(profile.id());
    }

    private void recordProfileEvent(
            UUID actorProfileId,
            UUID profileId,
            String displayName,
            boolean previousActive,
            boolean active,
            String requestId,
            OffsetDateTime now
    ) {
        CommandOperation operation = active ? CommandOperation.OPEN_PARTNER_ACCESS : CommandOperation.CLOSE_PARTNER_ACCESS;
        UUID commandId = UUID.randomUUID();
        commandIdempotencyRepository.reserve(
                commandId,
                actorProfileId,
                operation,
                commandId.toString(),
                CommandFingerprint.of(objectMapper, new ProfileAccessCommand(profileId, active)),
                now
        );
        String actorDisplayName = adminCrmProfileRepository.findDisplayName(actorProfileId)
                .orElseThrow(() -> new IllegalStateException("Actor profile is unavailable for partner access audit"));
        int version = adminCrmProfileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalStateException("Partner profile is unavailable for audit"))
                .version();
        adminCrmProfileRepository.insertEvent(
                UUID.randomUUID(),
                profileId,
                commandId,
                actorProfileId,
                actorDisplayName,
                new ProfileState(displayName, UserRole.PARTNER, null, previousActive, false),
                new ProfileState(displayName, UserRole.PARTNER, null, active, false),
                requestId,
                version,
                now
        );
    }

    private ManagedOrganization requireVisible(CrmProfile actor, UUID organizationId) {
        UUID id = requiredId(organizationId, "id");
        if (actor.role() != UserRole.ADMIN) {
            organizationRepository.findVisibleById(actor, id).orElseThrow(OrganizationNotFoundException::new);
        }
        return partnerAccessRepository.findOrganization(id).orElseThrow(OrganizationNotFoundException::new);
    }

    private ManagedOrganization requireManageable(CrmProfile actor, UUID organizationId) {
        ManagedOrganization organization = requireVisible(actor, organizationId);
        boolean allowed = switch (actor.role()) {
            case ADMIN -> true;
            case LEADER -> Objects.equals(actor.teamId(), organization.teamId());
            case USER -> Objects.equals(actor.teamId(), organization.teamId()) && actor.id().equals(organization.ownerManagerId());
            case MANAGEMENT, PARTNER -> false;
        };
        if (!allowed) {
            throw PartnerException.managementForbidden();
        }
        return organization;
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static String details(ManagedOrganization organization, PartnerContact contact) {
        return "кабинет вуза «" + organization.name() + "», контакт «" + contact.name() + "»";
    }

    private static String limit(String value) {
        return value.length() > KEYCLOAK_NAME_LIMIT ? value.substring(0, KEYCLOAK_NAME_LIMIT) : value;
    }

    private static UUID requiredId(UUID value, String field) {
        if (value == null) {
            throw new InteractionValidationException(field, "Укажите идентификатор");
        }
        return value;
    }

    private static String currentRequestId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object value = attributes == null ? null : attributes.getAttribute(RequestId.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof String requestId ? requestId : SYSTEM_REQUEST;
    }

    private record ProfileAccessCommand(UUID profileId, boolean active) {
    }
}
