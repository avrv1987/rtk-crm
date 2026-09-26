package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;

@Component
public class IdempotentCommandRunner {
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public IdempotentCommandRunner(CommandIdempotencyRepository commandIdempotencyRepository, ObjectMapper objectMapper) {
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
    }

    public <T> T run(
            UUID actorProfileId,
            CommandOperation operation,
            String idempotencyKey,
            Object command,
            Class<T> resultType,
            Function<UUID, T> action
    ) {
        String normalizedKey = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        if (!commandIdempotencyRepository.reserve(
                commandId, actorProfileId, operation, normalizedKey, fingerprint, OffsetDateTime.now()
        )) {
            CommandIdempotencyRepository.CommandRecord previous = commandIdempotencyRepository
                    .find(actorProfileId, operation, normalizedKey)
                    .orElseThrow(() -> new IllegalStateException("Reserved command is unavailable"));
            if (!fingerprint.equals(previous.requestFingerprint())) {
                throw InteractionConflictException.idempotency();
            }
            if (previous.resultJson() == null) {
                throw new IllegalStateException("Reserved command has no result");
            }
            return read(previous.resultJson(), resultType);
        }
        String resultJson = write(action.apply(commandId));
        commandIdempotencyRepository.complete(commandId, resultJson);
        return read(resultJson, resultType);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Command result cannot be stored", exception);
        }
    }

    private <T> T read(String value, Class<T> resultType) {
        try {
            return objectMapper.readValue(value, resultType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored command result cannot be read", exception);
        }
    }
}
