package ru.rtk.crm.mail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import ru.rtk.crm.access.AccountSyncRepository;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.KeycloakAccountClient;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.mail.MailRepository.MailMode;
import ru.rtk.crm.mail.MailRepository.NewLetter;
import ru.rtk.crm.mail.MailRepository.OutboxRow;
import ru.rtk.crm.mail.MailRepository.Recipient;
import ru.rtk.crm.mail.MailRepository.Settings;
import ru.rtk.crm.mail.MailTemplates.Letter;
import ru.rtk.crm.source.SourceSettingsService;

// Исходящая очередь почты. Письмо ставится в очередь в момент события, а решение «отправлять ли» принимается заново
// перед каждой отправкой: за время ожидания профиль могут заблокировать, вуз — передать другому КАМ, сотрудник —
// отключить тему. Адрес сотрудника берётся из Keycloak в момент отправки: смена почты учётки действует сразу.
// Однократную доставку очередь не обещает: при обрыве связи после приёма письма сервером оно может прийти дважды.
@Service
@MailEnabled
public class MailOutbox {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailOutbox.class);
    static final int BATCH = 20;
    static final String PASSWORD_CONTEXT = "mail:smtp-password";
    static final String INVITATION_KEY = "invitation:";
    private static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(60)
    );

    private final MailRepository repository;
    private final Map<MailMode, MailTransport> transports = new EnumMap<>(MailMode.class);
    private final SourceSettingsService secrets;
    private final AccountSyncRepository accountSyncRepository;
    private final KeycloakAccountClient keycloakAccountClient;
    private final OrganizationRepository organizationRepository;
    private final MailProperties properties;

    public MailOutbox(
            MailRepository repository,
            List<MailTransport> transports,
            SourceSettingsService secrets,
            AccountSyncRepository accountSyncRepository,
            KeycloakAccountClient keycloakAccountClient,
            OrganizationRepository organizationRepository,
            MailProperties properties
    ) {
        this.repository = repository;
        transports.forEach(transport -> this.transports.put(transport.mode(), transport));
        this.secrets = secrets;
        this.accountSyncRepository = accountSyncRepository;
        this.keycloakAccountClient = keycloakAccountClient;
        this.organizationRepository = organizationRepository;
        this.properties = properties;
    }

    public boolean toProfile(String eventKey, MailKind kind, UUID profileId, UUID organizationId, Letter letter) {
        return toProfile(eventKey, kind, profileId, organizationId, letter, null);
    }

    // expiresAt — когда письмо теряет смысл (сводка — к концу дня); null — общий срок app.mail.message-ttl.
    public boolean toProfile(String eventKey, MailKind kind, UUID profileId, UUID organizationId, Letter letter, OffsetDateTime expiresAt) {
        return enqueue(new NewLetter(eventKey, kind, profileId, null, organizationId, letter.subject(), letter.text(), letter.html(),
                null, null, null), expiresAt);
    }

    public boolean enqueue(NewLetter letter, OffsetDateTime expiresAt) {
        // Время усечено до миллисекунд: база округляет микросекунды, и время постановки могло бы оказаться позже
        // момента ближайшего прохода отправщика.
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        return repository.enqueue(letter, now, expiresAt == null ? now.plus(properties.messageTtl()) : expiresAt);
    }

    public int sendBatch() {
        Settings settings = repository.settings();
        MailTransport transport = transports.get(settings.mode());
        if (settings.mode() == MailMode.SMTP && (settings.host() == null || settings.host().isBlank())) {
            return 0;
        }
        MailTransport.Connection connection = connection(settings);
        OffsetDateTime now = OffsetDateTime.now();
        int sent = 0;
        for (OutboxRow row : repository.findDue(now, BATCH)) {
            if (send(row, settings, transport, connection, now)) {
                sent++;
            }
        }
        return sent;
    }

    // Отправка одного письма сразу (тестовое письмо, сведения субъекту): администратор видит итог на экране. Если SMTP
    // не настроен, письмо остаётся в очереди, как и при обычном проходе.
    public UUID sendNow(String eventKey) {
        OutboxRow row = repository.findByKey(eventKey).orElseThrow(MailException::letterNotFound);
        Settings settings = repository.settings();
        if (settings.mode() == MailMode.STUB || (settings.host() != null && !settings.host().isBlank())) {
            send(row, settings, transports.get(settings.mode()), connection(settings), OffsetDateTime.now());
        }
        return row.id();
    }

    private boolean send(OutboxRow row, Settings settings, MailTransport transport, MailTransport.Connection connection, OffsetDateTime now) {
        Optional<Recipient> recipient = row.profileId() == null ? Optional.empty() : repository.recipient(row.profileId());
        String reason = dropReason(row, recipient, now);
        if (reason != null) {
            repository.markDropped(row.id(), reason);
            return false;
        }
        String to;
        MimeMessage message;
        try {
            to = row.recipientEmail() != null ? row.recipientEmail() : email(row.profileId()).orElse(null);
            if (to == null) {
                repository.markDropped(row.id(), "у учётной записи получателя не указана почта");
                return false;
            }
            message = build(row, settings, to, true, now);
        } catch (RuntimeException | MessagingException exception) {
            retry(row, now, MailSmtpTransport.reason(exception));
            return false;
        }
        try {
            transport.send(message, connection);
        } catch (RuntimeException exception) {
            LOGGER.warn("Mail was not sent kind={} id={}: {}", row.kind(), row.id(), MailSmtpTransport.reason(exception));
            retry(row, now, MailSmtpTransport.reason(exception));
            return false;
        }
        try {
            // В журнал сведений субъекту ПДн сохраняется письмо без вложения: сам отчёт уже ушёл адресату.
            MimeMessage stored = row.kind() == MailKind.SUBJECT_REPORT ? build(row, settings, to, false, now) : message;
            repository.markSent(row.id(), now, settings.mode(), MailCodes.mask(to), mime(stored));
        } catch (MessagingException | IOException exception) {
            repository.markSent(row.id(), now, settings.mode(), MailCodes.mask(to), null);
        }
        return true;
    }

    // Причина не отправлять или null. Проверки повторяют права CRM: тот же профиль, та же область видимости вуза.
    String dropReason(OutboxRow row, Optional<Recipient> found, OffsetDateTime now) {
        if (!row.expiresAt().isAfter(now)) {
            return "письмо устарело";
        }
        if (row.profileId() == null) {
            return null;
        }
        if (found.isEmpty()) {
            return "профиль получателя не найден";
        }
        Recipient recipient = found.get();
        if (!recipient.active() || recipient.pendingActivation()) {
            return "профиль получателя заблокирован или ждёт активации";
        }
        UserRole role = UserRole.valueOf(recipient.role());
        if (!row.kind().roles().contains(role) || (row.kind().topic() != null && recipient.enrolmentOperator())) {
            return "роль получателя больше не получает такие письма";
        }
        if (row.kind().topic() != null && repository.disabledTopics(recipient.id()).contains(row.kind().topic())) {
            return "получатель отключил эту тему уведомлений";
        }
        // Ключ письма-приглашения — "invitation:" + идентификатор приглашения (MailInvitationService.invite).
        if (row.kind() == MailKind.INVITATION
                && !repository.invitationOpen(UUID.fromString(row.eventKey().substring(INVITATION_KEY.length())), now)) {
            return "приглашение уже использовано, заменено новым или истекло";
        }
        if (row.kind().organizationBound() && row.organizationId() != null) {
            boolean visible = role == UserRole.PARTNER
                    ? row.organizationId().equals(recipient.partnerOrganizationId())
                    : organizationRepository.findVisibleById(
                            new CrmProfile(recipient.id(), role, recipient.teamId(), recipient.accessRevision()), row.organizationId()
                    ).isPresent();
            if (!visible) {
                return "вуз больше не виден получателю";
            }
        }
        return null;
    }

    Optional<String> email(UUID profileId) {
        return accountSyncRepository.findAccount(profileId)
                .flatMap(account -> keycloakAccountClient.contact(account.subject()))
                .map(KeycloakAccountClient.UserContact::email);
    }

    private void retry(OutboxRow row, OffsetDateTime now, String error) {
        Duration delay = BACKOFF.get(Math.min(row.attempts(), BACKOFF.size() - 1));
        repository.markRetry(row.id(), now.plus(delay), error);
    }

    MailTransport.Connection connection(Settings settings) {
        String password = secrets.decryptModuleSecret(PASSWORD_CONTEXT, settings.passwordEncrypted()).orElse(null);
        return new MailTransport.Connection(settings.host(), settings.port(), settings.security(), settings.username(), password,
                properties.connectTimeout());
    }

    MailTransport transport(MailMode mode) {
        return transports.get(mode);
    }

    // Письмо собирается одинаково для заглушки и SMTP: тема, текст и HTML в multipart/alternative, вложение — рядом.
    MimeMessage build(OutboxRow row, Settings settings, String to, boolean withAttachment, OffsetDateTime now) throws MessagingException {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
        try {
            helper.setFrom(new InternetAddress(settings.fromAddress(), settings.fromName(), "UTF-8"));
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new MessagingException("Кодировка имени отправителя не поддерживается", exception);
        }
        helper.setTo(to);
        helper.setSubject(row.subject());
        helper.setSentDate(Date.from(now.toInstant()));
        helper.setText(row.bodyText(), row.bodyHtml());
        if (row.attachmentName() != null && withAttachment && row.attachment() != null) {
            helper.addAttachment(row.attachmentName(), new ByteArrayResource(row.attachment()), row.attachmentType());
        }
        message.setHeader("Auto-Submitted", "auto-generated");
        if (row.kind().topic() != null) {
            message.setHeader("List-Unsubscribe", "<" + properties.crmUrl(MailTemplates.SETTINGS_HASH) + ">");
        }
        message.saveChanges();
        return message;
    }

    static String mime(MimeMessage message) throws IOException, MessagingException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    // Просмотр письма администратором: отправленное читается из сохранённого MIME — это ровно то, что ушло (или ушло
    // бы по SMTP); ещё не отправленное показывается из очереди. Адрес получателя — только маской.
    LetterView view(OutboxRow row) {
        if (row.mime() == null) {
            return new LetterView(row.id(), row.kind().title(), row.status(), row.transport(), row.recipientMasked(), row.subject(),
                    null, row.sentAt(), null, row.bodyText(), row.bodyHtml(),
                    row.attachmentName() == null ? List.of() : List.of(row.attachmentName()), 0, row.lastError());
        }
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(row.mime().getBytes(StandardCharsets.UTF_8)));
            Parts parts = new Parts();
            parts.collect(message);
            // toString отдаёт имя в MIME-виде (=?UTF-8?B?...?=), а экрану нужно читаемое «CRM ИТ Школы РТК <crm@...>».
            String from = message.getFrom() == null || message.getFrom().length == 0 ? null
                    : message.getFrom()[0] instanceof InternetAddress address && address.getPersonal() != null
                    ? address.getPersonal() + " <" + address.getAddress() + ">" : message.getFrom()[0].toString();
            return new LetterView(row.id(), row.kind().title(), row.status(), row.transport(), row.recipientMasked(), message.getSubject(),
                    from, row.sentAt(), message.getMessageID(), parts.text, parts.html, parts.attachments,
                    row.mime().length(), row.lastError());
        } catch (MessagingException | IOException exception) {
            throw new IllegalStateException("Сохранённое письмо не читается", exception);
        }
    }

    private static final class Parts {
        private String text;
        private String html;
        private final List<String> attachments = new ArrayList<>();

        void collect(Part part) throws MessagingException, IOException {
            if (part.isMimeType("multipart/*")) {
                Multipart multipart = (Multipart) part.getContent();
                for (int index = 0; index < multipart.getCount(); index++) {
                    BodyPart child = multipart.getBodyPart(index);
                    collect(child);
                }
            } else if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) {
                attachments.add(part.getFileName());
            } else if (part.isMimeType("text/html") && html == null) {
                html = (String) part.getContent();
            } else if (part.isMimeType("text/plain") && text == null) {
                text = (String) part.getContent();
            }
        }
    }

    public record LetterView(
            UUID id,
            String kindTitle,
            String status,
            MailMode transport,
            String recipient,
            String subject,
            String from,
            OffsetDateTime sentAt,
            String messageId,
            String text,
            String html,
            List<String> attachments,
            int mimeSize,
            String error
    ) {
    }
}
