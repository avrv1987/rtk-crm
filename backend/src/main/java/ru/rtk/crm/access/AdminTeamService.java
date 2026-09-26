package ru.rtk.crm.access;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class AdminTeamService {
    private static final int NAME_LIMIT = 160;

    private final AdminTeamRepository adminTeamRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public AdminTeamService(
            AdminTeamRepository adminTeamRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.adminTeamRepository = adminTeamRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Team> list(CrmProfile actor) {
        AdminAuthorization.requireAdmin(actor);
        return adminTeamRepository.findAll();
    }

    @Transactional
    public Team create(CrmProfile actor, TeamRequest request, String idempotencyKey) {
        AdminAuthorization.requireAdmin(actor);
        TeamCommand command = new TeamCommand(null, null, requiredName(request));
        String normalizedKey = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(commandId, actor.id(), CommandOperation.CREATE_TEAM, normalizedKey, fingerprint, now)) {
            return replay(actor.id(), CommandOperation.CREATE_TEAM, normalizedKey, fingerprint);
        }
        requireFreeName(command.name(), null);
        UUID teamId = UUID.randomUUID();
        try {
            adminTeamRepository.insert(teamId, command.name(), now);
        } catch (DuplicateKeyException exception) {
            throw nameTaken();
        }
        return store(commandId, adminTeamRepository.findById(teamId).orElseThrow(TeamNotFoundException::new));
    }

    @Transactional
    public Team rename(CrmProfile actor, UUID teamId, TeamRequest request, String idempotencyKey) {
        AdminAuthorization.requireAdmin(actor);
        if (teamId == null) {
            throw new InteractionValidationException("id", "Укажите команду");
        }
        if (request == null || request.version() == null || request.version() < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите страницу");
        }
        TeamCommand command = new TeamCommand(teamId, request.version(), requiredName(request));
        String normalizedKey = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(commandId, actor.id(), CommandOperation.RENAME_TEAM, normalizedKey, fingerprint, now)) {
            return replay(actor.id(), CommandOperation.RENAME_TEAM, normalizedKey, fingerprint);
        }
        Team current = adminTeamRepository.findById(teamId).orElseThrow(TeamNotFoundException::new);
        if (current.version() != command.version()) {
            throw InteractionConflictException.teamVersion(current.version());
        }
        if (current.name().equals(command.name())) {
            throw new InteractionValidationException("name", "Команда уже так называется");
        }
        requireFreeName(command.name(), teamId);
        boolean renamed;
        try {
            renamed = adminTeamRepository.rename(teamId, command.version(), command.name(), now);
        } catch (DuplicateKeyException exception) {
            throw nameTaken();
        }
        if (!renamed) {
            throw InteractionConflictException.teamVersion(
                    adminTeamRepository.findById(teamId).map(Team::version).orElseThrow(TeamNotFoundException::new)
            );
        }
        return store(commandId, adminTeamRepository.findById(teamId).orElseThrow(TeamNotFoundException::new));
    }

    private String requiredName(TeamRequest request) {
        String name = request == null || request.name() == null ? "" : request.name().strip();
        if (name.isEmpty() || name.length() > NAME_LIMIT) {
            throw new InteractionValidationException("name", "Название команды должно содержать от 1 до 160 символов");
        }
        return name;
    }

    private void requireFreeName(String name, UUID exceptTeamId) {
        if (adminTeamRepository.nameTaken(name, exceptTeamId)) {
            throw nameTaken();
        }
    }

    private InteractionValidationException nameTaken() {
        return new InteractionValidationException("name", "Команда с таким названием уже есть");
    }

    private Team replay(UUID actorProfileId, CommandOperation operation, String idempotencyKey, String fingerprint) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, operation, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved team command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved team command has no result");
        }
        return read(command.resultJson());
    }

    private Team store(UUID commandId, Team result) {
        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Team command result cannot be stored", exception);
        }
        commandIdempotencyRepository.complete(commandId, resultJson);
        return read(resultJson);
    }

    private Team read(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, Team.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored team command result cannot be read", exception);
        }
    }

    private record TeamCommand(UUID teamId, Integer version, String name) {
    }
}
