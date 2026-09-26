package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final OrganizationRepository organizationRepository;
    private final ContactRepository contactRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public ContactService(
            OrganizationRepository organizationRepository,
            ContactRepository contactRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.organizationRepository = organizationRepository;
        this.contactRepository = contactRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Contact> list(CrmProfile profile, UUID organizationId) {
        requireVisibleOrganization(profile, organizationId);
        return contactRepository.findByOrganizationId(organizationId);
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
                nullableText(request.phone())
        );
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.CREATE_CONTACT,
                normalizedKey,
                fingerprint,
                OffsetDateTime.now()
        )) {
            return replay(profile.id(), normalizedKey, fingerprint);
        }
        OffsetDateTime now = OffsetDateTime.now();
        Contact contact = contactRepository.insert(
                UUID.randomUUID(),
                organizationId,
                command.name(),
                command.position(),
                command.email(),
                command.phone(),
                profile.id(),
                now
        );
        return store(commandId, contact);
    }

    private void requireVisibleOrganization(CrmProfile profile, UUID organizationId) {
        organizationRepository.findVisibleById(profile, organizationId)
                .orElseThrow(OrganizationNotFoundException::new);
    }

    private Contact replay(UUID actorProfileId, String idempotencyKey, String fingerprint) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, CommandOperation.CREATE_CONTACT, idempotencyKey)
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
            String phone
    ) {
    }
}
