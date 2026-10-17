package ru.rtk.crm.source;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.catalog.Contact;
import ru.rtk.crm.catalog.ContactRepository;
import ru.rtk.crm.catalog.ContactRole;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.source.SourceRepository.ApplyResult;
import ru.rtk.crm.source.SourceRepository.SourceOrganization;
import ru.rtk.crm.source.SourceRepository.StoredRecord;

@Service
public class CrmRecordApplier implements SourceRecordApplier {
    static final String ORGANIZATION = "crm_organization";
    static final String CONTACT = "crm_contact";
    private static final SourceCode SOURCE = SourceCode.CUSTOMER_CRM;
    private static final Pattern INN = Pattern.compile("\\d{10}|\\d{12}");
    private static final Pattern OGRN = Pattern.compile("\\d{13}|\\d{15}");
    private static final List<String> ORGANIZATION_TYPES = List.of("UNIVERSITY", "COLLEGE", "SCHOOL");
    private static final int MAX_LISTED = 5;
    private static final String RESTRICTED = "Контакт не изменён: обработка его персональных данных ограничена";

    private final SourceRepository repository;
    private final ContactRepository contactRepository;
    private final ObjectMapper objectMapper;

    public CrmRecordApplier(SourceRepository repository, ContactRepository contactRepository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.contactRepository = contactRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SyncOutcome apply(SiteRecord item, UUID runId, UUID actorProfileId) {
        OffsetDateTime now = OffsetDateTime.now();
        Optional<StoredRecord> existing = repository.findRecordForUpdate(SOURCE, item.type(), item.externalId());
        if (existing.isPresent() && !replaces(existing.get(), item)) {
            return SyncOutcome.SKIPPED;
        }
        UUID recordId = existing.map(StoredRecord::id).orElseGet(UUID::randomUUID);
        // У новой записи id ещё нет, связанного контакта искать негде. Найденный контакт запоминается на записи
        // (setRecordContact), чтобы следующая версия обновляла тот же контакт, а не искала его заново.
        Resolution resolution = resolve(item, existing.map(StoredRecord::id).orElse(null), actorProfileId, now);
        if (existing.isPresent()) {
            repository.updateRecord(recordId, item.version(), resolution.result(), runId, now);
        } else {
            repository.insertRecord(recordId, SOURCE, item.version(), resolution.result(), runId, now);
        }
        if (resolution.contactId() != null) {
            repository.setRecordContact(recordId, resolution.contactId());
        }
        return switch (resolution.result().status()) {
            case APPLIED -> existing.isPresent() ? SyncOutcome.UPDATED : SyncOutcome.CREATED;
            case NEEDS_MAPPING -> SyncOutcome.NEEDS_MAPPING;
            case FAILED -> SyncOutcome.FAILED;
            case SKIPPED -> SyncOutcome.SKIPPED;
        };
    }

    @Transactional
    public void recordFailure(SiteRecord item, UUID runId, String error) {
        OffsetDateTime now = OffsetDateTime.now();
        Optional<StoredRecord> existing = repository.findRecordForUpdate(SOURCE, item.type(), item.externalId());
        if (existing.isEmpty()) {
            repository.insertRecord(UUID.randomUUID(), SOURCE, item.version(), failed(error), runId, now);
        } else if (replaces(existing.get(), item)) {
            repository.updateRecord(existing.get().id(), item.version(), failed(error), runId, now);
        }
    }

    @Transactional
    public SourceRecordStatus reapply(UUID recordId, UUID actorProfileId) {
        StoredRecord stored = repository.findRecordForUpdate(recordId).orElseThrow(SourceException::recordNotFound);
        if (stored.status() != SourceRecordStatus.NEEDS_MAPPING && stored.status() != SourceRecordStatus.FAILED) {
            return stored.status();
        }
        return resolveStored(stored, actorProfileId);
    }

    // В отличие от reapply пересчитывает и применённую запись: сопоставление организации могло измениться.
    @Transactional
    public SourceRecordStatus reresolve(UUID recordId, UUID actorProfileId) {
        return resolveStored(repository.findRecordForUpdate(recordId).orElseThrow(SourceException::recordNotFound), actorProfileId);
    }

    @Transactional
    public void markFailed(UUID recordId, String error) {
        repository.findRecordForUpdate(recordId).ifPresent(stored -> repository.updateRecord(
                stored.id(), SiteRecord.parseStored(objectMapper, stored.payload()).version(), failed(error), null,
                OffsetDateTime.now()));
    }

    // Варианты нужны только при неоднозначности: при нуле и одном совпадении выбирать не из чего.
    List<SourceMappingOption> candidates(StoredRecord stored) {
        if (stored.source() != SOURCE || stored.status() != SourceRecordStatus.NEEDS_MAPPING) {
            return List.of();
        }
        SiteRecord item = SiteRecord.parseStored(objectMapper, stored.payload());
        if (item.problem() != null) {
            return List.of();
        }
        if (ORGANIZATION.equals(item.type())) {
            List<SourceOrganization> found = matchOrganization(item, payload(item)).found();
            return found.size() > 1 ? found.stream().map(o -> new SourceMappingOption(o.id(), o.name())).toList() : List.of();
        }
        if (stored.organizationId() == null) {
            return List.of();
        }
        List<Contact> found = matchContacts(stored.organizationId(), item);
        return found.size() > 1 ? found.stream().map(c -> new SourceMappingOption(c.id(), contactLabel(c))).toList() : List.of();
    }

    String organizationType(StoredRecord stored) {
        return text(payload(SiteRecord.parseStored(objectMapper, stored.payload())), "organizationType");
    }

    void chooseContact(StoredRecord stored, UUID contactId) {
        if (!CONTACT.equals(stored.recordType()) || stored.organizationId() == null) {
            throw new InteractionValidationException("contactId", "Контакт выбирается только для контакта из CRM заказчика");
        }
        // Контакт ищется внутри организации записи: контакт чужой организации выбрать нельзя.
        if (contactRepository.findById(stored.organizationId(), contactId).isEmpty()) {
            throw new InteractionValidationException("contactId", "Контакт не найден среди контактов организации");
        }
        repository.setRecordContact(stored.id(), contactId);
    }

    // Предпросмотр ничего не пишет. resolvedOrganizations передаёт найденные организации контактам из того же ответа,
    // чтобы показать итог до применения.
    CrmPreviewItem preview(SiteRecord item, Map<String, UUID> resolvedOrganizations) {
        String name = ORGANIZATION.equals(item.type()) ? item.organizationName() : item.contactName();
        if (!item.storable() || item.problem() != null) {
            return previewItem(item, name, CrmPreviewOutcome.INVALID, item.problem() == null ? "нет externalId или updatedAt" : item.problem());
        }
        Optional<StoredRecord> existing = repository.findRecord(SOURCE, item.type(), item.externalId());
        if (existing.isPresent() && !replaces(existing.get(), item)) {
            return previewItem(item, name, CrmPreviewOutcome.UNCHANGED, "запись уже загружена");
        }
        String problem = problem(item, payload(item));
        if (problem != null) {
            return previewItem(item, name, CrmPreviewOutcome.INVALID, problem);
        }
        if (ORGANIZATION.equals(item.type())) {
            OrganizationMatch match = matchOrganization(item, payload(item));
            if (match.found().size() == 1) {
                resolvedOrganizations.put(item.externalId(), match.found().getFirst().id());
                return previewItem(item, name, CrmPreviewOutcome.FOUND, "найдена " + match.via() + ": " + match.found().getFirst().name());
            }
            return match.found().isEmpty()
                    ? previewItem(item, name, CrmPreviewOutcome.NOT_FOUND, "организации нет в CRM: выберите существующую или создайте")
                    : previewItem(item, name, CrmPreviewOutcome.AMBIGUOUS, ambiguity(match));
        }
        UUID organizationId = repository.findMappedOrganization(SOURCE, item.organizationKey()).map(SourceOrganization::id)
                .orElse(resolvedOrganizations.get(item.organizationExternalId()));
        if (organizationId == null) {
            return previewItem(item, name, CrmPreviewOutcome.ORGANIZATION_UNRESOLVED, "организация контакта ещё не сопоставлена");
        }
        List<Contact> found = matchContacts(organizationId, item);
        if (found.isEmpty()) {
            return previewItem(item, name, CrmPreviewOutcome.CREATE, "будет создан контакт");
        }
        if (found.size() > 1) {
            return previewItem(item, name, CrmPreviewOutcome.AMBIGUOUS, "подходят несколько контактов: "
                    + found.stream().limit(MAX_LISTED).map(CrmRecordApplier::contactLabel).collect(Collectors.joining("; ")));
        }
        return found.getFirst().personalDataStatus() == PersonalDataStatus.ACTIVE
                ? previewItem(item, name, CrmPreviewOutcome.UPDATE, "будут заполнены пустые поля контакта «" + found.getFirst().name() + "»")
                : previewItem(item, name, CrmPreviewOutcome.RESTRICTED, RESTRICTED);
    }

    private SourceRecordStatus resolveStored(StoredRecord stored, UUID actorProfileId) {
        SiteRecord item = SiteRecord.parseStored(objectMapper, stored.payload());
        OffsetDateTime now = OffsetDateTime.now();
        Resolution resolution = resolve(item, stored.id(), actorProfileId, now);
        repository.updateRecord(stored.id(), item.version(), resolution.result(), null, now);
        if (resolution.contactId() != null) {
            repository.setRecordContact(stored.id(), resolution.contactId());
        }
        return resolution.result().status();
    }

    private Resolution resolve(SiteRecord item, UUID recordId, UUID actorProfileId, OffsetDateTime now) {
        if (item.problem() != null) {
            return new Resolution(failed("Запись не соответствует контракту: " + item.problem()), null);
        }
        JsonNode payload = payload(item);
        String problem = problem(item, payload);
        if (problem != null) {
            return new Resolution(failed("Запись не соответствует контракту: " + problem), null);
        }
        return ORGANIZATION.equals(item.type())
                ? new Resolution(resolveOrganization(item, payload, actorProfileId, now), null)
                : resolveContact(item, payload, recordId, actorProfileId, now);
    }

    private ApplyResult resolveOrganization(SiteRecord item, JsonNode payload, UUID actorProfileId, OffsetDateTime now) {
        OrganizationMatch match = matchOrganization(item, payload);
        if (match.found().isEmpty()) {
            return needsMapping("Организации «" + item.organizationName() + "» нет в CRM; выберите существующую или создайте новую", null);
        }
        if (match.found().size() > 1) {
            return needsMapping(ambiguity(match), null);
        }
        SourceOrganization organization = match.found().getFirst();
        // Найденная по ИНН или названию организация запоминается как сопоставление по внешнему ID: следующие загрузки и
        // контакты находят её сразу.
        if (!match.mapped()) {
            repository.insertMapping(SOURCE, "ORGANIZATION", item.organizationKey(), organization.id(), null, null, null,
                    actorProfileId, now);
        }
        // Заполняются только пустые ИНН и регион: введённое пользователем не затирается.
        repository.fillOrganizationDetails(organization.id(), text(payload, "inn"), text(payload, "region"), now);
        return new ApplyResult(SourceRecordStatus.APPLIED, null, organization.id(), null, 1, null);
    }

    private Resolution resolveContact(SiteRecord item, JsonNode payload, UUID recordId, UUID actorProfileId, OffsetDateTime now) {
        Optional<SourceOrganization> organization = repository.findMappedOrganization(SOURCE, item.organizationKey());
        if (organization.isEmpty()) {
            return new Resolution(needsMapping("Организация контакта (внешний ID " + item.organizationExternalId()
                    + ") не сопоставлена с организацией CRM; сначала разберите её", null), null);
        }
        UUID organizationId = organization.get().id();
        Optional<UUID> linkedId = recordId == null ? Optional.empty() : repository.findRecordContactId(recordId);
        // Контакт, найденный при прошлой версии записи, главнее нового поиска: меняется тот же контакт, даже если почта
        // или имя у заказчика изменились. Блокируется на время применения.
        Optional<Contact> linked = linkedId.flatMap(id -> contactRepository.findByIdForUpdate(organizationId, id));
        List<Contact> matches = linked.isPresent() ? List.of(linked.get()) : matchContacts(organizationId, item);
        // Неоднозначность не угадывается: запись ждёт выбора человека (chooseContact).
        if (matches.size() > 1) {
            return new Resolution(needsMapping("Подходят несколько контактов организации: "
                    + matches.stream().limit(MAX_LISTED).map(CrmRecordApplier::contactLabel).collect(Collectors.joining("; "))
                    + "; выберите нужный", organizationId), null);
        }
        if (matches.isEmpty()) {
            Contact created = contactRepository.insert(UUID.randomUUID(), organizationId, item.contactName(), item.contactPosition(),
                    item.contactEmail(), item.contactPhone(), role(payload), false, actorProfileId, now);
            return new Resolution(new ApplyResult(SourceRecordStatus.APPLIED, null, organizationId, null, 1, null), created.id());
        }
        Contact contact = matches.getFirst();
        // Контакт с ограниченной или снятой обработкой ПДн не меняется, но к записи привязывается.
        if (contact.personalDataStatus() != PersonalDataStatus.ACTIVE) {
            return new Resolution(new ApplyResult(SourceRecordStatus.SKIPPED, RESTRICTED, organizationId, null, 1, null),
                    contact.id());
        }
        // Контакт дополняется, а не перезаписывается: заполняются только пустые должность, почта, телефон и роль;
        // введённое пользователем остаётся.
        Contact filled = new Contact(contact.id(), contact.organizationId(), contact.name(), blank(contact.position(), item.contactPosition()),
                blank(contact.email(), item.contactEmail()), blank(contact.phone(), item.contactPhone()), contact.version(),
                contact.createdBy(), contact.createdAt(), contact.updatedAt(), contact.role() == null ? role(payload) : contact.role(),
                contact.primary(), contact.inactive(), contact.confirmedAt(), contact.confirmedBy(), contact.confirmedByName(),
                contact.personalDataStatus());
        // Версия контакта проверяется: если его изменили за время применения, применение прерывается и повторяется.
        if (!filled.equals(contact) && !contactRepository.update(filled, contact.version(), now)) {
            throw new IllegalStateException("Contact was changed while the CRM record was applied");
        }
        return new Resolution(new ApplyResult(SourceRecordStatus.APPLIED, null, organizationId, null, 1, null), contact.id());
    }

    // Порядок: сопоставление по внешнему ID, затем ИНН, затем название без учёта регистра и лишних пробелов. ИНН точнее
    // названия; название — единственный ключ у организаций без ИНН.
    private OrganizationMatch matchOrganization(SiteRecord item, JsonNode payload) {
        Optional<SourceOrganization> mapped = repository.findMappedOrganization(SOURCE, item.organizationKey());
        if (mapped.isPresent()) {
            return new OrganizationMatch(List.of(mapped.get()), "по внешнему ID", true);
        }
        String inn = text(payload, "inn");
        if (inn != null) {
            List<SourceOrganization> byInn = repository.findOrganizationsByInn(inn);
            if (!byInn.isEmpty()) {
                return new OrganizationMatch(byInn, "по ИНН", false);
            }
        }
        List<SourceOrganization> byName = item.organizationName() == null
                ? List.of()
                : repository.findOrganizationsByNormalizedName(normalized(item.organizationName()));
        return new OrganizationMatch(byName, "по названию", false);
    }

    // Сначала по почте. Без совпадения по почте — по имени, но только среди контактов без почты: с другой почтой это,
    // вероятно, другой человек с тем же именем. Обезличенные контакты исключены.
    private List<Contact> matchContacts(UUID organizationId, SiteRecord item) {
        List<Contact> contacts = contactRepository.findByOrganizationId(organizationId).stream()
                .filter(contact -> contact.personalDataStatus() != PersonalDataStatus.ANONYMIZED)
                .toList();
        if (item.contactEmail() != null) {
            List<Contact> byEmail = contacts.stream().filter(contact -> item.contactEmail().equalsIgnoreCase(contact.email())).toList();
            if (!byEmail.isEmpty()) {
                return byEmail;
            }
        }
        String name = normalized(item.contactName());
        return contacts.stream()
                .filter(contact -> normalized(contact.name()).equals(name))
                .filter(contact -> item.contactEmail() == null || contact.email() == null || contact.email().isBlank())
                .toList();
    }

    private String problem(SiteRecord item, JsonNode payload) {
        if (ORGANIZATION.equals(item.type())) {
            if (item.organizationName() == null) {
                return "не указано название организации";
            }
            if (!ORGANIZATION_TYPES.contains(text(payload, "organizationType"))) {
                return "тип организации должен быть UNIVERSITY, COLLEGE или SCHOOL";
            }
            if (text(payload, "inn") != null && !INN.matcher(text(payload, "inn")).matches()) {
                return "ИНН должен состоять из 10 или 12 цифр";
            }
            if (text(payload, "ogrn") != null && !OGRN.matcher(text(payload, "ogrn")).matches()) {
                return "ОГРН должен состоять из 13 или 15 цифр";
            }
            return text(payload, "region") != null && text(payload, "region").length() > 200 ? "регион длиннее 200 символов" : null;
        }
        if (!CONTACT.equals(item.type())) {
            return "неизвестный тип записи «" + item.type() + "»";
        }
        if (item.organizationExternalId() == null) {
            return "не указана организация контакта";
        }
        if (item.contactName() == null) {
            return "не указано ФИО контакта";
        }
        if (text(payload, "role") != null && role(payload) == null) {
            return "роль должна быть одной из SIGNATORY, IMPLEMENTER, TEACHER, APPROVER, OTHER";
        }
        return null;
    }

    private JsonNode payload(SiteRecord item) {
        try {
            return objectMapper.readTree(item.payload());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored source record payload cannot be read", exception);
        }
    }

    // Новее хранимой версии — применяется; та же версия повторно только у записи, ждущей сопоставления или упавшей.
    private static boolean replaces(StoredRecord existing, SiteRecord item) {
        if (item.updatedAt().isAfter(existing.externalUpdatedAt())) {
            return true;
        }
        return item.updatedAt().isEqual(existing.externalUpdatedAt())
                && (existing.status() == SourceRecordStatus.NEEDS_MAPPING || existing.status() == SourceRecordStatus.FAILED);
    }

    private static String ambiguity(OrganizationMatch match) {
        return "Найдено несколько организаций CRM " + match.via() + ": "
                + match.found().stream().limit(MAX_LISTED).map(SourceOrganization::name).collect(Collectors.joining("; "))
                + "; выберите нужную";
    }

    private static CrmPreviewItem previewItem(SiteRecord item, String name, CrmPreviewOutcome outcome, String note) {
        return new CrmPreviewItem(item.type(), item.externalId(), name, outcome, note);
    }

    private static String contactLabel(Contact contact) {
        return contact.email() == null || contact.email().isBlank() ? contact.name() : contact.name() + ", " + contact.email();
    }

    private static ContactRole role(JsonNode payload) {
        String value = text(payload, "role");
        for (ContactRole role : ContactRole.values()) {
            if (role.name().equals(value)) {
                return role;
            }
        }
        return null;
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().strip();
    }

    private static String blank(String current, String candidate) {
        return current == null || current.isBlank() ? candidate : current;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static ApplyResult needsMapping(String error, UUID organizationId) {
        return new ApplyResult(SourceRecordStatus.NEEDS_MAPPING, error, organizationId, null, 1, null);
    }

    private static ApplyResult failed(String error) {
        return new ApplyResult(SourceRecordStatus.FAILED, error.length() <= 500 ? error : error.substring(0, 499) + "…", null, null, 1, null);
    }

    private record Resolution(ApplyResult result, UUID contactId) {
    }

    private record OrganizationMatch(List<SourceOrganization> found, String via, boolean mapped) {
    }
}
