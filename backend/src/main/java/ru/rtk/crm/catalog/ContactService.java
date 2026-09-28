package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class ContactService {
    private static final TypeReference<List<ContactEvent.Change>> CHANGES = new TypeReference<>() {
    };

    private final OrganizationRepository organizationRepository;
    private final ContactRepository contactRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ContactService(
            OrganizationRepository organizationRepository,
            ContactRepository contactRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        this.organizationRepository = organizationRepository;
        this.contactRepository = contactRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<Contact> list(CrmProfile profile, UUID organizationId) {
        requireVisibleOrganization(profile, organizationId);
        return contactRepository.findByOrganizationId(organizationId);
    }

    @Transactional(readOnly = true)
    public List<ContactEvent> events(CrmProfile profile, UUID organizationId, UUID contactId) {
        requireVisibleOrganization(profile, organizationId);
        contactRepository.findById(organizationId, contactId).orElseThrow(ContactNotFoundException::new);
        return contactRepository.findEvents(contactId).stream()
                .map(event -> new ContactEvent(
                        event.id(),
                        event.contactId(),
                        event.actorProfileId(),
                        event.actorDisplayName(),
                        readChanges(event.changes()),
                        event.version(),
                        event.occurredAt()
                ))
                .toList();
    }

    @Transactional
    public Contact create(CrmProfile profile, UUID organizationId, ContactCreateRequest request, String idempotencyKey) {
        requireVisibleOrganization(profile, organizationId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        String normalizedKey = requiredKey(idempotencyKey);
        CreateContactCommand command = new CreateContactCommand(
                organizationId,
                requiredText(request.name(), "name"),
                nullableText(request.position()),
                nullableText(request.email()),
                nullableText(request.phone()),
                request.role(),
                request.primary()
        );
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.CREATE_CONTACT,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replay(profile.id(), CommandOperation.CREATE_CONTACT, normalizedKey, fingerprint);
        }
        UUID contactId = UUID.randomUUID();
        if (command.primary()) {
            clearOtherPrimary(organizationId, contactId, profile.id(), commandId, now);
        }
        contactRepository.insert(
                contactId,
                organizationId,
                command.name(),
                command.position(),
                command.email(),
                command.phone(),
                command.role(),
                command.primary(),
                profile.id(),
                now
        );
        return store(commandId, contactRepository.findById(organizationId, contactId)
                .orElseThrow(() -> new IllegalStateException("Created contact is unavailable")));
    }

    @Transactional
    public Contact update(
            CrmProfile profile,
            UUID organizationId,
            UUID contactId,
            ContactUpdateRequest request,
            String idempotencyKey
    ) {
        requireVisibleOrganization(profile, organizationId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        int expectedVersion = requiredVersion(request.version());
        String normalizedKey = requiredKey(idempotencyKey);
        UpdateContactCommand command = new UpdateContactCommand(
                organizationId,
                contactId,
                expectedVersion,
                requiredText(request.name(), "name"),
                nullableText(request.position()),
                nullableText(request.email()),
                nullableText(request.phone()),
                request.role(),
                request.primary(),
                request.inactive(),
                request.confirm()
        );
        if (command.primary() && command.inactive()) {
            throw new InteractionValidationException("primary", "Неактуальный контакт не может быть основным");
        }
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.UPDATE_CONTACT,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replay(profile.id(), CommandOperation.UPDATE_CONTACT, normalizedKey, fingerprint);
        }
        if (command.primary()) {
            contactRepository.lockOrganization(organizationId);
        }
        Contact current = contactRepository.findByIdForUpdate(organizationId, contactId)
                .orElseThrow(ContactNotFoundException::new);
        if (current.personalDataStatus() != PersonalDataStatus.ACTIVE) {
            throw new InteractionValidationException(
                    "id", "Контакт обезличен или его обработка ограничена; уточнение — через «Субъект ПДн»"
            );
        }
        if (current.version() != expectedVersion) {
            throw InteractionConflictException.contactVersion(current.version());
        }
        Contact next = new Contact(
                current.id(),
                current.organizationId(),
                command.name(),
                command.position(),
                command.email(),
                command.phone(),
                current.version(),
                current.createdBy(),
                current.createdAt(),
                current.updatedAt(),
                command.role(),
                command.primary(),
                command.inactive(),
                command.confirm() ? now : current.confirmedAt(),
                command.confirm() ? profile.id() : current.confirmedBy(),
                null,
                current.personalDataStatus()
        );
        List<ContactEvent.Change> changes = changes(current, next, command.confirm());
        if (changes.isEmpty()) {
            return store(commandId, current);
        }
        if (command.primary() && !current.primary()) {
            clearOtherPrimary(organizationId, contactId, profile.id(), commandId, now);
        }
        if (!contactRepository.update(next, expectedVersion, now)) {
            throw InteractionConflictException.contactVersion(current.version());
        }
        contactRepository.insertEvent(
                UUID.randomUUID(),
                contactId,
                commandId,
                profile.id(),
                writeChanges(changes),
                expectedVersion + 1,
                now
        );
        if (next.inactive() && !current.inactive()) {
            eventPublisher.publishEvent(new CatalogArchivedEvent(organizationId, contactId, profile.id(), null));
        }
        return store(commandId, contactRepository.findById(organizationId, contactId)
                .orElseThrow(ContactNotFoundException::new));
    }

    private void clearOtherPrimary(UUID organizationId, UUID contactId, UUID actorProfileId, UUID commandId, OffsetDateTime now) {
        contactRepository.lockOrganization(organizationId);
        for (Contact previous : contactRepository.findOtherPrimaryForUpdate(organizationId, contactId)) {
            Contact cleared = new Contact(
                    previous.id(), previous.organizationId(), previous.name(), previous.position(), previous.email(),
                    previous.phone(), previous.version(), previous.createdBy(), previous.createdAt(), previous.updatedAt(),
                    previous.role(), false, previous.inactive(), previous.confirmedAt(), previous.confirmedBy(), null,
                    previous.personalDataStatus()
            );
            if (!contactRepository.update(cleared, previous.version(), now)) {
                throw new IllegalStateException("Primary contact changed while it was locked");
            }
            contactRepository.insertEvent(
                    UUID.randomUUID(),
                    previous.id(),
                    commandId,
                    actorProfileId,
                    writeChanges(List.of(new ContactEvent.Change("primary", "true", "false"))),
                    previous.version() + 1,
                    now
            );
        }
    }

    private List<ContactEvent.Change> changes(Contact current, Contact next, boolean confirmed) {
        List<ContactEvent.Change> changes = new ArrayList<>();
        change(changes, "name", current.name(), next.name());
        change(changes, "position", current.position(), next.position());
        change(changes, "email", current.email(), next.email());
        change(changes, "phone", current.phone(), next.phone());
        change(changes, "role", roleName(current.role()), roleName(next.role()));
        change(changes, "primary", Boolean.toString(current.primary()), Boolean.toString(next.primary()));
        change(changes, "inactive", Boolean.toString(current.inactive()), Boolean.toString(next.inactive()));
        if (confirmed) {
            changes.add(new ContactEvent.Change(
                    "confirmed",
                    current.confirmedAt() == null ? null : current.confirmedAt().toString(),
                    next.confirmedAt().toString()
            ));
        }
        return changes;
    }

    private void change(List<ContactEvent.Change> changes, String field, String previous, String value) {
        if (!Objects.equals(previous, value)) {
            changes.add(new ContactEvent.Change(field, previous, value));
        }
    }

    private String roleName(ContactRole role) {
        return role == null ? null : role.name();
    }

    private void requireVisibleOrganization(CrmProfile profile, UUID organizationId) {
        organizationRepository.findVisibleById(profile, organizationId)
                .orElseThrow(OrganizationNotFoundException::new);
    }

    private Contact replay(UUID actorProfileId, CommandOperation operation, String idempotencyKey, String fingerprint) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, operation, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved contact command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved contact command has no result");
        }
        return read(command.resultJson());
    }

    private Contact store(UUID commandId, Contact contact) {
        String resultJson = write(contact);
        commandIdempotencyRepository.complete(commandId, resultJson);
        return read(resultJson);
    }

    private Contact read(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, Contact.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored contact command result cannot be read", exception);
        }
    }

    private String write(Contact contact) {
        try {
            return objectMapper.writeValueAsString(contact);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Contact command result cannot be stored", exception);
        }
    }

    private List<ContactEvent.Change> readChanges(String changes) {
        try {
            return objectMapper.readValue(changes, CHANGES);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored contact changes cannot be read", exception);
        }
    }

    private String writeChanges(List<ContactEvent.Change> changes) {
        try {
            return objectMapper.writeValueAsString(changes);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Contact changes cannot be stored", exception);
        }
    }

    private int requiredVersion(Integer value) {
        if (value == null || value < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите страницу");
        }
        return value;
    }

    private String requiredKey(String value) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException("Idempotency-Key", "Не передан ключ повтора запроса Idempotency-Key");
        }
        if (value.length() > 255) {
            throw new InteractionValidationException("Idempotency-Key", "Ключ повтора запроса Idempotency-Key длиннее 255 символов");
        }
        return value;
    }

    private String requiredText(String value, String field) {
        String normalized = nullableText(value);
        if (normalized == null) {
            throw new InteractionValidationException(field, "Заполните значение");
        }
        return normalized;
    }

    private String nullableText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record CreateContactCommand(
            UUID organizationId,
            String name,
            String position,
            String email,
            String phone,
            ContactRole role,
            boolean primary
    ) {
    }

    private record UpdateContactCommand(
            UUID organizationId,
            UUID contactId,
            int version,
            String name,
            String position,
            String email,
            String phone,
            ContactRole role,
            boolean primary,
            boolean inactive,
            boolean confirm
    ) {
    }
}
