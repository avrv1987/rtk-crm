package ru.rtk.crm.mail;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.mail.MailRepository.MailMode;
import ru.rtk.crm.mail.MailRepository.MailSecurity;
import ru.rtk.crm.mail.MailRepository.NewLetter;
import ru.rtk.crm.mail.MailRepository.OutboxRow;
import ru.rtk.crm.mail.MailRepository.Settings;
import ru.rtk.crm.mail.MailTemplates.Letter;
import ru.rtk.crm.privacy.PersonalDataService;
import ru.rtk.crm.privacy.SubjectExportFormat;
import ru.rtk.crm.privacy.SubjectQuery;
import ru.rtk.crm.source.SourceSettingsService;

// Экран «Почта» администратора и настройки «что присылать на почту» сотрудника. Пароль SMTP шифруется тем же ключом,
// что токены источников, и наружу не отдаётся: в ответе только признак «задан». В журнал аудита пишутся действия
// без адресов и паролей.
@Service
@MailEnabled
public class MailSettingsService {
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,255}");
    private static final int PAGE_LIMIT = 100;

    private final MailRepository repository;
    private final MailOutbox outbox;
    private final MailTemplates templates;
    private final SourceSettingsService secrets;
    private final PersonalDataService personalDataService;
    private final AuditJournalRepository auditJournalRepository;

    public MailSettingsService(
            MailRepository repository,
            MailOutbox outbox,
            MailTemplates templates,
            SourceSettingsService secrets,
            PersonalDataService personalDataService,
            AuditJournalRepository auditJournalRepository
    ) {
        this.repository = repository;
        this.outbox = outbox;
        this.templates = templates;
        this.secrets = secrets;
        this.personalDataService = personalDataService;
        this.auditJournalRepository = auditJournalRepository;
    }

    @Transactional(readOnly = true)
    public View view(CrmProfile actor) {
        AdminAuthorization.requireAdmin(actor);
        return view();
    }

    // Пароль: null — оставить прежний, пустая строка — не менять, clearPassword — удалить.
    @Transactional
    public View update(CrmProfile actor, UpdateRequest request, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        if (request == null || request.mode() == null) {
            throw new InteractionValidationException("mode", "Выберите режим: заглушка или SMTP");
        }
        Settings current = repository.settings();
        String host = blankToNull(request.host());
        Integer port = request.port();
        MailSecurity security = request.security() == null ? MailSecurity.STARTTLS : request.security();
        if (host != null && !HOST.matcher(host).matches()) {
            throw new InteractionValidationException("host", "Сервер SMTP: имя или адрес без пробелов, например smtp.example.ru");
        }
        if (port != null && (port < 1 || port > 65535)) {
            throw new InteractionValidationException("port", "Порт — число от 1 до 65535");
        }
        if (request.mode() == MailMode.SMTP && (host == null || port == null)) {
            throw new InteractionValidationException("host", "Для режима SMTP укажите сервер и порт");
        }
        String username = blankToNull(request.username());
        if (username != null && (username.length() > 255 || username.chars().anyMatch(Character::isISOControl))) {
            throw new InteractionValidationException("username", "Логин SMTP — до 255 символов в одну строку");
        }
        String fromAddress = blankToNull(request.fromAddress());
        // Прежний адрес принимается как есть: значение по умолчанию crm@localhost не проходит проверку формы, и иначе
        // настройки нельзя было бы сохранить, не трогая поле.
        boolean sameAddress = fromAddress != null && fromAddress.equalsIgnoreCase(current.fromAddress());
        if (fromAddress == null || fromAddress.length() > 254 || !sameAddress && !EMAIL.matcher(fromAddress).matches()) {
            throw new InteractionValidationException("fromAddress", "Укажите адрес отправителя в виде crm@example.ru");
        }
        String fromName = blankToNull(request.fromName());
        if (fromName == null || fromName.length() > 200 || fromName.chars().anyMatch(Character::isISOControl)) {
            throw new InteractionValidationException("fromName", "Имя отправителя — от 1 до 200 символов в одну строку");
        }
        String passwordEncrypted = current.passwordEncrypted();
        boolean passwordChanged = false;
        if (Boolean.TRUE.equals(request.clearPassword())) {
            passwordChanged = passwordEncrypted != null;
            passwordEncrypted = null;
        } else if (request.password() != null && !request.password().isEmpty()) {
            if (request.password().length() > 500 || request.password().chars().anyMatch(Character::isISOControl)) {
                throw new InteractionValidationException("password", "Пароль SMTP — до 500 символов в одну строку");
            }
            if (!secrets.moduleSecretsAvailable()) {
                throw new InteractionValidationException("password",
                        "Ключ шифрования SOURCES_SETTINGS_KEY не задан: пароль нельзя сохранить");
            }
            passwordEncrypted = secrets.encryptModuleSecret(MailOutbox.PASSWORD_CONTEXT, request.password());
            passwordChanged = true;
        }
        repository.saveSettings(new Settings(request.mode(), host, port, security, username, passwordEncrypted,
                fromAddress.toLowerCase(Locale.ROOT), fromName, null, null, null, null, null), OffsetDateTime.now());
        String details = "режим " + (request.mode() == MailMode.STUB ? "заглушка" : "SMTP")
                + (host == null ? "" : "; сервер " + host + ":" + port + ", шифрование " + security)
                + (username == null ? "; без входа" : "; вход по логину")
                + (passwordChanged ? (passwordEncrypted == null ? "; пароль удалён" : "; пароль заменён") : "");
        auditJournalRepository.record(AuditAction.MAIL_SETTINGS_CHANGED, actor.id(), "MAIL", null, "Почта", details, requestId);
        return view();
    }

    @Transactional
    public CheckResult check(CrmProfile actor, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        Settings settings = repository.settings();
        boolean ok;
        String message;
        try {
            message = outbox.transport(settings.mode()).check(outbox.connection(settings));
            ok = true;
        } catch (RuntimeException exception) {
            message = MailSmtpTransport.reason(exception);
            ok = false;
        }
        repository.saveCheck(ok, message, OffsetDateTime.now());
        auditJournalRepository.record(AuditAction.MAIL_CONNECTION_CHECKED, actor.id(), "MAIL", null, "Почта",
                (ok ? "подключение есть" : "ошибка подключения") + "; режим " + settings.mode(), requestId);
        return new CheckResult(ok, MailRepository.truncate(message));
    }

    // Тестовое письмо себе уходит сразу, не дожидаясь отправщика: администратор видит результат на экране.
    @Transactional
    public MailRepository.JournalRow test(CrmProfile actor, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        if (outbox.email(actor.id()).isEmpty()) {
            throw new InteractionValidationException("email", "У вашей учётной записи в Keycloak не указана почта: тестовое письмо некуда отправить");
        }
        Letter letter = templates.test();
        String key = "test:" + UUID.randomUUID();
        outbox.toProfile(key, MailKind.TEST, actor.id(), null, letter);
        UUID id = outbox.sendNow(key);
        auditJournalRepository.record(AuditAction.MAIL_TEST_SENT, actor.id(), "MAIL", id, "Почта", "тестовое письмо себе", requestId);
        return journalRow(id);
    }

    // Сведения субъекту ПДн: тот же отчёт PDF, что «Выгрузить сведения» (выгрузка и её запись в журнал — в
    // PersonalDataService), отправляется на адрес, который указал администратор по запросу субъекта.
    @Transactional
    public MailRepository.JournalRow sendSubjectReport(CrmProfile actor, SubjectReportRequest request, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        String email = request == null || request.email() == null ? "" : request.email().strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new InteractionValidationException("email", "Укажите почту субъекта в виде name@example.ru");
        }
        byte[] report = personalDataService.export(actor, request.query(), SubjectExportFormat.PDF, requestId).content();
        Letter letter = templates.subjectReport();
        String key = "subject:" + UUID.randomUUID();
        outbox.enqueue(new NewLetter(key, MailKind.SUBJECT_REPORT, null, email, null, letter.subject(), letter.text(), letter.html(),
                "Сведения о субъекте ПДн " + LocalDate.now(MailProperties.ZONE) + ".pdf", "application/pdf", report), null);
        UUID id = outbox.sendNow(key);
        auditJournalRepository.record(AuditAction.MAIL_SUBJECT_REPORT_SENT, actor.id(), "SUBJECT", null, null,
                "сведения отправлены на почту, указанную субъектом", requestId);
        return journalRow(id);
    }

    @Transactional(readOnly = true)
    public JournalPage journal(CrmProfile actor, String status, int page, int size) {
        AdminAuthorization.requireAdmin(actor);
        if (status != null && !status.isBlank() && !Set.of("QUEUED", "SENT", "ERROR", "DROPPED", "CANCELLED").contains(status)) {
            throw new InteractionValidationException("status", "Неизвестный статус письма");
        }
        int limit = Math.max(1, Math.min(size, PAGE_LIMIT));
        int offset = Math.max(0, page) * limit;
        String filter = status == null ? "" : status.strip();
        return new JournalPage(repository.journal(filter, limit, offset), repository.journalTotal(filter));
    }

    // Просмотр письма — в журнал: в тексте бывают названия вузов, а в приглашении — ссылка на пароль.
    @Transactional
    public MailOutbox.LetterView letter(CrmProfile actor, UUID id, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        OutboxRow row = repository.find(id).orElseThrow(MailException::letterNotFound);
        auditJournalRepository.record(AuditAction.MAIL_LETTER_VIEWED, actor.id(), "MAIL", id, row.kind().title(), null, requestId);
        return outbox.view(row);
    }

    @Transactional
    public MailRepository.JournalRow retry(CrmProfile actor, UUID id, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        repository.find(id).orElseThrow(MailException::letterNotFound);
        if (!repository.retry(id, OffsetDateTime.now())) {
            throw new MailException(HttpStatus.CONFLICT, "MAIL_LETTER_STATE",
                    "Повторить можно только письмо с ошибкой, у которого не истёк срок");
        }
        auditJournalRepository.record(AuditAction.MAIL_OUTBOX_REQUEUED, actor.id(), "MAIL", id, "Очередь почты", "письмо возвращено в очередь", requestId);
        return journalRow(id);
    }

    @Transactional
    public MailRepository.JournalRow cancel(CrmProfile actor, UUID id, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        repository.find(id).orElseThrow(MailException::letterNotFound);
        if (!repository.cancel(id)) {
            throw new MailException(HttpStatus.CONFLICT, "MAIL_LETTER_STATE", "Отменить можно только письмо в очереди или с ошибкой");
        }
        auditJournalRepository.record(AuditAction.MAIL_OUTBOX_CANCELLED, actor.id(), "MAIL", id, "Очередь почты", "письмо отменено", requestId);
        return journalRow(id);
    }

    @Transactional
    public int requeue(CrmProfile actor, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        int count = repository.requeueErrors(OffsetDateTime.now());
        auditJournalRepository.record(AuditAction.MAIL_OUTBOX_REQUEUED, actor.id(), "MAIL", null, "Очередь почты",
                "возвращено писем: " + count, requestId);
        return count;
    }

    @Transactional(readOnly = true)
    public Preferences preferences(CrmProfile actor) {
        Set<MailTopic> disabled = repository.disabledTopics(actor.id());
        List<Topic> topics = availableTopics(actor).stream()
                .map(topic -> new Topic(topic, topic.label(), !disabled.contains(topic)))
                .toList();
        String email;
        try {
            email = topics.isEmpty() ? null : outbox.email(actor.id()).orElse(null);
        } catch (RuntimeException exception) {
            email = null;
        }
        return new Preferences(email, topics);
    }

    @Transactional
    public Preferences savePreferences(CrmProfile actor, PreferencesUpdate update) {
        Set<MailTopic> available = availableTopics(actor);
        Set<MailTopic> disabled = EnumSet.noneOf(MailTopic.class);
        if (update != null && update.disabledTopics() != null) {
            for (MailTopic topic : update.disabledTopics()) {
                if (topic != null && available.contains(topic)) {
                    disabled.add(topic);
                }
            }
        }
        repository.replaceDisabledTopics(actor.id(), disabled);
        return preferences(actor);
    }

    // Менеджер учебного процесса писем по вузам не получает, как и в MAX; представитель вуза тем не настраивает.
    private Set<MailTopic> availableTopics(CrmProfile actor) {
        boolean operator = repository.recipient(actor.id()).map(MailRepository.Recipient::enrolmentOperator).orElse(false);
        Set<MailTopic> topics = EnumSet.noneOf(MailTopic.class);
        if (operator) {
            return topics;
        }
        for (MailTopic topic : MailTopic.values()) {
            if (topic.roles().contains(actor.role())) {
                topics.add(topic);
            }
        }
        return topics;
    }

    private MailRepository.JournalRow journalRow(UUID id) {
        return repository.journalRow(id).orElseThrow(MailException::letterNotFound);
    }

    private View view() {
        Settings settings = repository.settings();
        String passwordState = settings.passwordEncrypted() == null ? "NONE"
                : secrets.decryptModuleSecret(MailOutbox.PASSWORD_CONTEXT, settings.passwordEncrypted()).isPresent() ? "SET" : "UNREADABLE";
        return new View(
                settings.mode(),
                settings.host(),
                settings.port(),
                settings.security(),
                settings.username(),
                passwordState,
                secrets.moduleSecretsAvailable(),
                settings.fromAddress(),
                settings.fromName(),
                settings.changedAt(),
                settings.checkedAt(),
                settings.checkOk(),
                settings.checkMessage(),
                repository.counts(OffsetDateTime.now().minusHours(24))
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record UpdateRequest(
            MailMode mode,
            String host,
            Integer port,
            MailSecurity security,
            String username,
            String password,
            Boolean clearPassword,
            String fromAddress,
            String fromName
    ) {
        @Override
        public String toString() {
            return "UpdateRequest[mode=" + mode + ", host=" + host + ", port=" + port + "]";
        }
    }

    public record View(
            MailMode mode,
            String host,
            Integer port,
            MailSecurity security,
            String username,
            String passwordState,
            boolean encryptionAvailable,
            String fromAddress,
            String fromName,
            OffsetDateTime changedAt,
            OffsetDateTime checkedAt,
            Boolean checkOk,
            String checkMessage,
            MailRepository.QueueCounts queue
    ) {
    }

    public record CheckResult(boolean ok, String message) {
    }

    public record JournalPage(List<MailRepository.JournalRow> items, long total) {
    }

    public record SubjectReportRequest(String email, SubjectQuery query) {
    }

    public record Topic(MailTopic topic, String label, boolean enabled) {
    }

    public record Preferences(String email, List<Topic> topics) {
    }

    public record PreferencesUpdate(List<MailTopic> disabledTopics) {
    }
}
