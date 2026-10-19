package ru.rtk.crm.mail;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AccountSyncRepository;
import ru.rtk.crm.access.AdminCrmProfileNotFoundException;
import ru.rtk.crm.access.KeycloakAccountClient;
import ru.rtk.crm.access.TemporaryPassword;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.mail.MailRepository.Invitation;
import ru.rtk.crm.mail.MailRepository.NewLetter;
import ru.rtk.crm.mail.MailRepository.Recipient;
import ru.rtk.crm.mail.MailTemplates.Letter;

// Приглашение по ссылке вместо пароля на экране администратора. Ссылка одноразовая, в базе — только SHA-256 токена.
// Пароль создаётся в Keycloak в момент открытия ссылки и показывается только получателю; пароль, заданный при
// создании учётки, никто не видел и после открытия ссылки уже не действует. Открывается ссылка кнопкой (POST), а не
// самим переходом: почтовые сканеры ссылок делают GET и иначе «съедали» бы приглашение.
@Service
@MailEnabled
public class MailInvitationService {
    private final MailRepository repository;
    private final MailOutbox outbox;
    private final MailTemplates templates;
    private final MailProperties properties;
    private final AccountSyncRepository accountSyncRepository;
    private final KeycloakAccountClient keycloakAccountClient;
    private final AuditJournalRepository auditJournalRepository;

    public MailInvitationService(
            MailRepository repository,
            MailOutbox outbox,
            MailTemplates templates,
            MailProperties properties,
            AccountSyncRepository accountSyncRepository,
            KeycloakAccountClient keycloakAccountClient,
            AuditJournalRepository auditJournalRepository
    ) {
        this.repository = repository;
        this.outbox = outbox;
        this.templates = templates;
        this.properties = properties;
        this.accountSyncRepository = accountSyncRepository;
        this.keycloakAccountClient = keycloakAccountClient;
        this.auditJournalRepository = auditJournalRepository;
    }

    // Пустой результат — почта получателя неизвестна: вызывающий показывает пароль на экране, как без модуля.
    // knownEmail — адрес, с которым только что создана учётка; null — адрес читается из Keycloak.
    // Вызывается внутри транзакции создания учётки или сброса пароля: откат команды откатывает и приглашение.
    @Transactional
    public Optional<String> invite(UUID actorId, UUID profileId, String login, String knownEmail, boolean newAccount, String requestId) {
        // Ссылка на пароль администратора дала бы войти от его имени — как и сброс пароля администратора из CRM.
        // Новая учётка администратора — исключение: её только что создал сам вызывающий.
        if (!newAccount && repository.recipient(profileId).filter(recipient -> "ADMIN".equals(recipient.role())).isPresent()) {
            throw new InteractionValidationException("id", "Ссылка на новый пароль администратору из CRM не выдаётся: администратор"
                    + " меняет пароль сам в личном кабинете Keycloak, а если доступ утерян — администратор Keycloak в консоли");
        }
        // В режиме заглушки письмо никто не получит, а ссылку из журнала администратору не показывают: пароль выдаётся
        // на экране, как без модуля почты.
        if (repository.settings().mode() == MailRepository.MailMode.STUB) {
            return Optional.empty();
        }
        String email = knownEmail != null && !knownEmail.isBlank() ? knownEmail : outbox.email(profileId).orElse(null);
        if (email == null) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plus(properties.invitationTtl());
        UUID invitationId = UUID.randomUUID();
        repository.closeOpenInvitations(profileId, now);
        // Действующий токен в очереди и журнале не хранится вовсе: в письме — заглушка ссылки, а токен создаётся в
        // момент отправки (MailOutbox.send), и в базу попадает только его хэш. Пока письмо не ушло, ссылки нет ни у кого.
        repository.insertInvitation(invitationId, MailCodes.sha256(MailCodes.token()), profileId, now, expiresAt);
        Letter letter = templates.invitation(login, properties.crmUrl(MailOutbox.HIDDEN_INVITE_LINK), properties.invitationTtl(), newAccount);
        MailKind kind = newAccount ? MailKind.INVITATION : MailKind.PASSWORD_RESET;
        outbox.enqueue(new NewLetter(MailOutbox.INVITATION_KEY + invitationId, kind, profileId, null, null,
                letter.subject(), letter.text(), letter.html(), null, null, null), expiresAt);
        auditJournalRepository.record(AuditAction.MAIL_INVITATION_SENT, actorId, "PROFILE", profileId, null,
                newAccount ? "ссылка на вход отправлена на почту учётной записи" : "ссылка на новый пароль отправлена на почту учётной записи",
                requestId);
        return Optional.of(MailCodes.mask(email));
    }

    @Transactional
    public Redeemed redeem(String token, String requestId) {
        if (token == null || token.isBlank() || token.length() > 100) {
            throw MailException.invitationInvalid();
        }
        Invitation invitation = repository.lockInvitation(MailCodes.sha256(token.strip())).orElseThrow(MailException::invitationInvalid);
        OffsetDateTime now = OffsetDateTime.now();
        if (invitation.redeemedAt() != null || !invitation.expiresAt().isAfter(now)) {
            throw MailException.invitationUsed();
        }
        Recipient profile = repository.recipient(invitation.profileId())
                .filter(Recipient::active)
                .orElseThrow(MailException::invitationInvalid);
        String subject = accountSyncRepository.findAccount(profile.id())
                .orElseThrow(AdminCrmProfileNotFoundException::new)
                .subject();
        String password = TemporaryPassword.generate();
        keycloakAccountClient.resetTemporaryPassword(subject, password);
        repository.markRedeemed(invitation.id(), now);
        auditJournalRepository.record(AuditAction.MAIL_INVITATION_REDEEMED, null, "PROFILE", profile.id(), null,
                "временный пароль показан по ссылке из письма", requestId);
        return new Redeemed(profile.login(), password, properties.crmUrl("#/"));
    }

    public record Redeemed(String login, String temporaryPassword, String loginUrl) {
    }
}
