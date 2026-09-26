package ru.rtk.crm.enrolment;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;

@Component
class EnrolmentCommands {
    private final CommandIdempotencyRepository repository;
    private final LearnerDataCipher cipher;
    private final ObjectMapper objectMapper;

    EnrolmentCommands(CommandIdempotencyRepository repository, LearnerDataCipher cipher, ObjectMapper objectMapper) {
        this.repository = repository;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    String fingerprint(Object command) {
        return CommandFingerprint.of(objectMapper, command);
    }

    String personalFingerprint(Object command) {
        return cipher.requestFingerprint(json(command));
    }

    Reservation reserve(UUID actorId, CommandOperation operation, String idempotencyKey, String fingerprint) {
        String key = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        UUID commandId = UUID.randomUUID();
        if (repository.reserve(commandId, actorId, operation, key, fingerprint, OffsetDateTime.now())) {
            return new Reservation(commandId, null);
        }
        CommandIdempotencyRepository.CommandRecord stored = repository.find(actorId, operation, key)
                .orElseThrow(() -> new IllegalStateException("Reserved enrolment command is unavailable"));
        if (!fingerprint.equals(stored.requestFingerprint()) || stored.resultJson() == null) {
            throw InteractionConflictException.idempotency();
        }
        return new Reservation(commandId, stored.resultJson());
    }

    void complete(Reservation reservation, Object result) {
        repository.complete(reservation.commandId(), json(result));
    }

    <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored enrolment command result cannot be read", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Enrolment command value cannot be written", exception);
        }
    }

    record Reservation(UUID commandId, String previousResult) {
        boolean repeated() {
            return previousResult != null;
        }
    }
}
