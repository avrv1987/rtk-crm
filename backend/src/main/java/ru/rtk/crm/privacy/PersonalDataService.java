package ru.rtk.crm.privacy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.attachment.AttachmentStorage;
import ru.rtk.crm.attachment.AttachmentStorageException;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.privacy.PersonalDataRepository.ContactState;
import ru.rtk.crm.privacy.PersonalDataRepository.ContactValues;
import ru.rtk.crm.privacy.PersonalDataRepository.ProfileState;
import ru.rtk.crm.privacy.PersonalDataRepository.StoredFile;
import ru.rtk.crm.privacy.PersonalDataRepository.TextColumn;
import ru.rtk.crm.privacy.PersonalDataRepository.TextKind;
import ru.rtk.crm.report.ReportStorage;

@Service
public class PersonalDataService {
    static final String CONTACT_MARKER = "Контакт обезличен";
    static final String EMPLOYEE_MARKER = "Сотрудник обезличен";
    static final String PREVIOUS_EMPLOYEE_MARKER = "Сотрудник обезличен (прежнее имя)";

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonalDataService.class);
    private static final String REPORTS_AFTER_ANONYMIZATION =
            "Файл удалён после обезличивания персональных данных; сформируйте отчёт заново";
    private static final int MAX_IDS = 200;

    private final SubjectSearchRepository searchRepository;
    private final PersonalDataRepository repository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final AuditJournalRepository auditJournalRepository;
    private final AttachmentStorage attachmentStorage;
    private final ReportStorage reportStorage;
    private final SubjectReportWriter reportWriter;
    private final RetentionProperties retentionProperties;
    private final ObjectMapper objectMapper;

    public PersonalDataService(
            SubjectSearchRepository searchRepository,
            PersonalDataRepository repository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            AuditJournalRepository auditJournalRepository,
            AttachmentStorage attachmentStorage,
            ReportStorage reportStorage,
            SubjectReportWriter reportWriter,
            RetentionProperties retentionProperties,
            ObjectMapper objectMapper
    ) {
        this.searchRepository = searchRepository;
        this.repository = repository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.auditJournalRepository = auditJournalRepository;
        this.attachmentStorage = attachmentStorage;
        this.reportStorage = reportStorage;
        this.reportWriter = reportWriter;
        this.retentionProperties = retentionProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SubjectSearchResult search(CrmProfile actor, SubjectQuery query, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        SubjectSearchResult result = searchRepository.search(SubjectTerms.of(query));
        auditJournalRepository.record(AuditAction.SUBJECT_SEARCHED, actor.id(), "SUBJECT", null, null, found(result), requestId);
        return result;
    }

    @Transactional
    public SubjectExport export(CrmProfile actor, SubjectQuery query, SubjectExportFormat format, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        if (format == null) {
            throw new InteractionValidationException("format", "Выберите формат выгрузки: JSON или PDF");
        }
        SubjectSearchResult result = searchRepository.search(SubjectTerms.of(query));
        SubjectReport report = new SubjectReport(
                OffsetDateTime.now(),
                SubjectReport.OPERATOR,
                query,
                SubjectReport.PURPOSES,
                SubjectReport.SOURCES,
                SubjectReport.RECIPIENTS,
                RetentionService.policyLines(retentionProperties),
                result
        );
        byte[] content = format == SubjectExportFormat.PDF ? reportWriter.pdf(report) : reportWriter.json(report);
        auditJournalRepository.record(
                AuditAction.SUBJECT_EXPORTED, actor.id(), "SUBJECT", null, null, "формат " + format + "; " + found(result), requestId
        );
        return new SubjectExport(content, format);
    }

    @Transactional
    public SubjectContact rectify(
            CrmProfile actor,
            UUID contactId,
            ContactRectification request,
            String idempotencyKey,
            String requestId
    ) {
        AdminAuthorization.requireAdmin(actor);
        requiredId(contactId, "id");
        if (request == null) {
            throw new InteractionValidationException("body", "Укажите данные контакта");
        }
        int version = requiredVersion(request.version());
        ContactValues values = new ContactValues(
                requiredText(request.name(), "name", 200),
                optionalText(request.position(), "position", 200),
                optionalText(request.email(), "email", 320),
                optionalText(request.phone(), "phone", 50)
        );
        RectifyCommand command = new RectifyCommand(contactId, version, values);
        return idempotent(actor, CommandOperation.RECTIFY_CONTACT, idempotencyKey, command, SubjectContact.class, () -> {
            ContactState contact = lockedContact(contactId, version);
            List<FieldChange> changed = changedFields(contact, values);
            if (changed.isEmpty()) {
                throw new InteractionValidationException("body", "Данные контакта не изменились");
            }
            if (!repository.updateContact(contactId, version, values, OffsetDateTime.now())) {
                throw PrivacyException.contactVersion(contact.version());
            }
            auditJournalRepository.record(
                    AuditAction.CONTACT_RECTIFIED, actor.id(), "CONTACT", contactId, contact.organizationName(),
                    rectificationDetails(changed), requestId
            );
            return searchRepository.findContact(contactId).orElseThrow(PrivacyException::contactNotFound);
        });
    }

    @Transactional
    public SubjectContact restrict(
            CrmProfile actor,
            UUID contactId,
            ContactRestriction request,
            String idempotencyKey,
            String requestId
    ) {
        AdminAuthorization.requireAdmin(actor);
        requiredId(contactId, "id");
        if (request == null || request.restricted() == null) {
            throw new InteractionValidationException("restricted", "Укажите, ограничить обработку или снять ограничение");
        }
        int version = requiredVersion(request.version());
        boolean restricted = request.restricted();
        RestrictCommand command = new RestrictCommand(contactId, version, restricted);
        return idempotent(actor, CommandOperation.RESTRICT_CONTACT_PROCESSING, idempotencyKey, command, SubjectContact.class, () -> {
            ContactState contact = lockedContact(contactId, version);
            PersonalDataStatus next = restricted ? PersonalDataStatus.RESTRICTED : PersonalDataStatus.ACTIVE;
            if (contact.status() == next) {
                throw new InteractionValidationException(
                        "restricted", restricted ? "Обработка данных контакта уже ограничена" : "Обработка данных контакта не ограничена"
                );
            }
            if (!repository.updateContactStatus(contactId, version, next, OffsetDateTime.now())) {
                throw PrivacyException.contactVersion(contact.version());
            }
            auditJournalRepository.record(
                    restricted ? AuditAction.CONTACT_RESTRICTED : AuditAction.CONTACT_RESTRICTION_LIFTED,
                    actor.id(), "CONTACT", contactId, contact.organizationName(), null, requestId
            );
            return searchRepository.findContact(contactId).orElseThrow(PrivacyException::contactNotFound);
        });
    }

    @Transactional
    public AnonymizationResult anonymize(
            CrmProfile actor,
            AnonymizationRequest request,
            String idempotencyKey,
            String requestId
    ) {
        AdminAuthorization.requireAdmin(actor);
        if (request == null) {
            throw new InteractionValidationException("body", "Укажите, что обезличить");
        }
        List<UUID> contactIds = ids(request.contactIds(), "contactIds");
        List<UUID> profileIds = ids(request.profileIds(), "profileIds");
        List<UUID> attachmentIds = ids(request.attachmentIds(), "attachmentIds");
        SubjectTerms terms = hasTerms(request.subject()) ? SubjectTerms.of(request.subject()) : null;
        if (terms == null && contactIds.isEmpty() && profileIds.isEmpty() && attachmentIds.isEmpty()) {
            throw new InteractionValidationException("contactIds", "Выберите контакты, профили или файлы либо укажите данные субъекта");
        }
        AnonymizeCommand command = new AnonymizeCommand(request.subject(), contactIds, profileIds, attachmentIds);
        return idempotent(actor, CommandOperation.ANONYMIZE_PERSONAL_DATA, idempotencyKey, command, AnonymizationResult.class, () -> {
            AnonymizationResult result = anonymizeLocked(
                    terms, lockContacts(contactIds), lockProfiles(profileIds), attachmentFiles(attachmentIds), false, true
            );
            auditJournalRepository.record(
                    AuditAction.SUBJECT_ANONYMIZED, actor.id(), "SUBJECT", null, null, result.summary(), requestId
            );
            return result;
        });
    }

    @Transactional
    public AnonymizationResult anonymizeForRetention(List<UUID> contactIds, List<UUID> profileIds) {
        return anonymizeLocked(null, lockContacts(contactIds), lockProfiles(profileIds), List.of(), true, false);
    }

    private AnonymizationResult anonymizeLocked(
            SubjectTerms queryTerms,
            List<ContactState> contacts,
            List<ProfileState> profiles,
            List<StoredFile> files,
            boolean scopedToOrganizations,
            boolean expireReports
    ) {
        if (profiles.stream().anyMatch(ProfileState::active)) {
            throw PrivacyException.profileActive();
        }
        OffsetDateTime now = OffsetDateTime.now();
        boolean employeeSubject = contacts.isEmpty() && !profiles.isEmpty();
        SubjectTerms base = queryTerms == null ? SubjectTerms.empty() : queryTerms;
        Counts counts = new Counts();
        List<ContactState> pendingContacts = contacts.stream()
                .filter(contact -> contact.status() != PersonalDataStatus.ANONYMIZED)
                .toList();
        if (scopedToOrganizations) {
            for (ContactState contact : pendingContacts) {
                replace(SubjectTerms.empty().with(contact.name(), contact.email(), contact.phone()),
                        CONTACT_MARKER, contact.organizationId(), counts);
            }
        } else {
            SubjectTerms contactTerms = employeeSubject ? SubjectTerms.empty() : base;
            for (ContactState contact : pendingContacts) {
                contactTerms = contactTerms.with(contact.name(), contact.email(), contact.phone());
            }
            replace(contactTerms, CONTACT_MARKER, null, counts);
        }
        SubjectTerms employeeTerms = employeeSubject ? base : SubjectTerms.empty();
        List<ProfileState> pendingProfiles = profiles.stream().filter(profile -> !profile.anonymized()).toList();
        for (ProfileState profile : pendingProfiles) {
            employeeTerms = employeeTerms.with(profile.displayName(), null, null);
        }
        replace(employeeTerms, EMPLOYEE_MARKER, null, counts);
        for (ContactState contact : pendingContacts) {
            repository.anonymizeContact(contact.id(), CONTACT_MARKER, now);
        }
        for (ProfileState profile : pendingProfiles) {
            repository.anonymizeProfile(profile.id(), EMPLOYEE_MARKER, PREVIOUS_EMPLOYEE_MARKER, now);
        }
        for (StoredFile file : files) {
            repository.deleteAttachment(file.id());
        }
        afterCommit(() -> deleteAttachmentFiles(files));
        AnonymizationResult changed = new AnonymizationResult(
                pendingContacts.size(), pendingProfiles.size(), counts.mentions, counts.sourceRecords, counts.technical,
                files.size(), 0
        );
        if (!expireReports || !changed.changedAnything()) {
            return changed;
        }
        List<UUID> reportFiles = repository.expireReportResults(null, REPORTS_AFTER_ANONYMIZATION);
        afterCommit(() -> reportFiles.forEach(key -> reportStorage.delete(reportStorage.resultFile(key))));
        return new AnonymizationResult(
                changed.contacts(), changed.profiles(), changed.mentions(), changed.sourceRecords(), changed.technicalRecords(),
                changed.attachmentsDeleted(), reportFiles.size()
        );
    }

    private void replace(SubjectTerms terms, String marker, UUID organizationId, Counts counts) {
        if (terms.isEmpty()) {
            return;
        }
        for (TextColumn column : TextColumn.values()) {
            int changed = repository.replaceText(column, terms, marker, organizationId);
            if (column.kind() == TextKind.MENTION) {
                counts.mentions += changed;
            } else if (column.kind() == TextKind.SOURCE) {
                counts.sourceRecords += changed;
            } else {
                counts.technical += changed;
            }
        }
    }

    private void deleteAttachmentFiles(List<StoredFile> files) {
        for (StoredFile file : files) {
            try {
                attachmentStorage.delete(file.storageKey());
            } catch (AttachmentStorageException exception) {
                LOGGER.warn("Attachment file {} was not deleted after anonymization", file.storageKey(), exception);
            }
        }
    }

    private List<ContactState> lockContacts(List<UUID> contactIds) {
        return contactIds.stream()
                .map(id -> repository.lockContact(id).orElseThrow(PrivacyException::contactNotFound))
                .toList();
    }

    private List<ProfileState> lockProfiles(List<UUID> profileIds) {
        return profileIds.stream()
                .map(id -> repository.lockProfile(id).orElseThrow(PrivacyException::profileNotFound))
                .toList();
    }

    private List<StoredFile> attachmentFiles(List<UUID> attachmentIds) {
        List<StoredFile> files = repository.findAttachmentFiles(attachmentIds);
        if (files.size() != attachmentIds.size()) {
            throw PrivacyException.attachmentNotFound();
        }
        return files;
    }

    private ContactState lockedContact(UUID contactId, int version) {
        ContactState contact = repository.lockContact(contactId).orElseThrow(PrivacyException::contactNotFound);
        if (contact.status() == PersonalDataStatus.ANONYMIZED) {
            throw PrivacyException.contactAnonymized();
        }
        if (contact.version() != version) {
            throw PrivacyException.contactVersion(contact.version());
        }
        return contact;
    }

    private static List<FieldChange> changedFields(ContactState contact, ContactValues values) {
        List<FieldChange> changed = new ArrayList<>();
        addChange(changed, "ФИО", contact.name(), values.name());
        addChange(changed, "должность", contact.position(), values.position());
        addChange(changed, "почта", contact.email(), values.email());
        addChange(changed, "телефон", contact.phone(), values.phone());
        return changed;
    }

    private static void addChange(List<FieldChange> changed, String label, String before, String after) {
        if (!Objects.equals(before, after)) {
            changed.add(new FieldChange(label, before, after));
        }
    }

    private static String rectificationDetails(List<FieldChange> changed) {
        return "изменены поля: " + String.join(", ", changed.stream().map(FieldChange::label).toList())
                + PersonalDataRepository.RECTIFIED_VALUES
                + String.join("; ", changed.stream()
                        .map(change -> change.label() + ": " + shown(change.before()) + " → " + shown(change.after()))
                        .toList());
    }

    private static String shown(String value) {
        return value == null ? "пусто" : "«" + value + "»";
    }

    private <T> T idempotent(
            CrmProfile actor,
            CommandOperation operation,
            String idempotencyKey,
            Object command,
            Class<T> resultType,
            Supplier<T> action
    ) {
        String key = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        if (!commandIdempotencyRepository.reserve(commandId, actor.id(), operation, key, fingerprint, OffsetDateTime.now())) {
            CommandIdempotencyRepository.CommandRecord stored = commandIdempotencyRepository.find(actor.id(), operation, key)
                    .orElseThrow(() -> new IllegalStateException("Reserved personal data command is unavailable"));
            if (!fingerprint.equals(stored.requestFingerprint())) {
                throw InteractionConflictException.idempotency();
            }
            if (stored.resultJson() == null) {
                throw new IllegalStateException("Reserved personal data command has no result");
            }
            return read(stored.resultJson(), resultType);
        }
        String resultJson = write(action.get());
        commandIdempotencyRepository.complete(commandId, resultJson);
        return read(resultJson, resultType);
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored personal data command result cannot be read", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Personal data command result cannot be stored", exception);
        }
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static String found(SubjectSearchResult result) {
        return "найдено контактов: " + result.contacts().size() + ", профилей: " + result.profiles().size()
                + ", упоминаний: " + result.mentions().size() + ", файлов: " + result.attachments().size()
                + ", записей источников: " + result.sourceRecords().size();
    }

    private static boolean hasTerms(SubjectQuery query) {
        return query != null && (notBlank(query.name()) || notBlank(query.email()) || notBlank(query.phone())
                || notBlank(query.otherSpellings()));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static List<UUID> ids(List<UUID> values, String field) {
        if (values == null) {
            return List.of();
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new InteractionValidationException(field, "Список содержит пустой идентификатор");
        }
        List<UUID> distinct = values.stream().distinct().toList();
        if (distinct.size() > MAX_IDS) {
            throw new InteractionValidationException(field, "Выберите не больше 200 записей за одну операцию");
        }
        return distinct;
    }

    private static void requiredId(UUID value, String field) {
        if (value == null) {
            throw new InteractionValidationException(field, "Укажите идентификатор");
        }
    }

    private static int requiredVersion(Integer value) {
        if (value == null || value < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; повторите поиск");
        }
        return value;
    }

    private static String requiredText(String value, String field, int limit) {
        String normalized = optionalText(value, field, limit);
        if (normalized == null) {
            throw new InteractionValidationException(field, "Заполните значение");
        }
        return normalized;
    }

    private static String optionalText(String value, String field, int limit) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > limit) {
            throw new InteractionValidationException(field, "Значение длиннее " + limit + " символов");
        }
        return normalized;
    }

    public record SubjectExport(byte[] content, SubjectExportFormat format) {
    }

    private static final class Counts {
        private int mentions;
        private int sourceRecords;
        private int technical;
    }

    private record FieldChange(String label, String before, String after) {
    }

    private record RectifyCommand(UUID contactId, int version, ContactValues values) {
    }

    private record RestrictCommand(UUID contactId, int version, boolean restricted) {
    }

    private record AnonymizeCommand(SubjectQuery subject, List<UUID> contactIds, List<UUID> profileIds, List<UUID> attachmentIds) {
    }
}
