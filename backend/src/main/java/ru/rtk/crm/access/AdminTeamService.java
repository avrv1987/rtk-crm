package ru.rtk.crm.access;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.CatalogChangeAction;
import ru.rtk.crm.catalog.CatalogChangeEventRepository;
import ru.rtk.crm.catalog.CatalogEntityType;
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
    private final AuditJournalRepository auditJournalRepository;
    private final CatalogChangeEventRepository catalogChangeEventRepository;
    private final ObjectMapper objectMapper;

    public AdminTeamService(
            AdminTeamRepository adminTeamRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            AuditJournalRepository auditJournalRepository,
            CatalogChangeEventRepository catalogChangeEventRepository,
            ObjectMapper objectMapper
    ) {
        this.adminTeamRepository = adminTeamRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.auditJournalRepository = auditJournalRepository;
        this.catalogChangeEventRepository = catalogChangeEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<AdminTeam> list(CrmProfile actor) {
        AdminAuthorization.requireAdmin(actor);
        Map<UUID, List<AdminTeamRepository.TeamMember>> members = adminTeamRepository.findActiveMembers().stream()
                .collect(Collectors.groupingBy(AdminTeamRepository.TeamMember::teamId));
        Map<UUID, Long> organizations = adminTeamRepository.countCurrentOrganizations().stream()
                .collect(Collectors.toMap(AdminTeamRepository.TeamCount::teamId, AdminTeamRepository.TeamCount::total));
        Map<UUID, Long> profiles = adminTeamRepository.countProfiles().stream()
                .collect(Collectors.toMap(AdminTeamRepository.TeamCount::teamId, AdminTeamRepository.TeamCount::total));
        return adminTeamRepository.findAll().stream().map(team -> {
            List<AdminTeamRepository.TeamMember> teamMembers = members.getOrDefault(team.id(), List.of());
            return new AdminTeam(
                    team.id(),
                    team.name(),
                    team.version(),
                    team.archived(),
                    namesWithRole(teamMembers, UserRole.LEADER),
                    namesWithRole(teamMembers, UserRole.USER),
                    organizations.getOrDefault(team.id(), 0L),
                    profiles.getOrDefault(team.id(), 0L) - teamMembers.size()
            );
        }).toList();
    }

    @Transactional
    public Team create(CrmProfile actor, TeamRequest request, String idempotencyKey, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
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
        auditJournalRepository.record(AuditAction.TEAM_CREATED, actor.id(), "TEAM", teamId, command.name(), null, auditRequestId);
        catalogChangeEventRepository.insert(
                CatalogEntityType.TEAM, teamId, CatalogChangeAction.CREATE, command.name(), null,
                actor.id(), auditRequestId, now
        );
        return store(commandId, adminTeamRepository.findById(teamId).orElseThrow(TeamNotFoundException::new));
    }

    @Transactional
    public Team rename(CrmProfile actor, UUID teamId, TeamRequest request, String idempotencyKey, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
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
        auditJournalRepository.record(
                AuditAction.TEAM_RENAMED, actor.id(), "TEAM", teamId, command.name(),
                "название: " + current.name() + " → " + command.name(), auditRequestId
        );
        catalogChangeEventRepository.insert(
                CatalogEntityType.TEAM, teamId, CatalogChangeAction.UPDATE, command.name(),
                "Название: «" + current.name() + "» → «" + command.name() + "»", actor.id(), auditRequestId, now
        );
        return store(commandId, adminTeamRepository.findById(teamId).orElseThrow(TeamNotFoundException::new));
    }

    @Transactional
    public Team changeArchived(
            CrmProfile actor,
            UUID teamId,
            TeamArchiveRequest request,
            String idempotencyKey,
            String requestId
    ) {
        AdminAuthorization.requireAdmin(actor);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        if (teamId == null) {
            throw new InteractionValidationException("id", "Укажите команду");
        }
        if (request == null || request.archived() == null) {
            throw new InteractionValidationException("archived", "Укажите, архивировать команду или восстановить");
        }
        if (request.version() == null || request.version() < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите страницу");
        }
        ArchiveCommand command = new ArchiveCommand(teamId, request.version(), request.archived());
        String normalizedKey = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(commandId, actor.id(), CommandOperation.ARCHIVE_TEAM, normalizedKey, fingerprint, now)) {
            return replay(actor.id(), CommandOperation.ARCHIVE_TEAM, normalizedKey, fingerprint);
        }
        Team current = adminTeamRepository.findByIdForUpdate(teamId).orElseThrow(TeamNotFoundException::new);
        if (current.version() != command.version()) {
            throw InteractionConflictException.teamVersion(current.version());
        }
        if (current.archived() == command.archived()) {
            throw new InteractionValidationException(
                    "archived", command.archived() ? "Команда уже в архиве" : "Команда не в архиве"
            );
        }
        if (command.archived()) {
            long organizations = adminTeamRepository.countCurrentOrganizations(teamId);
            if (organizations > 0) {
                throw new InteractionValidationException(
                        "archived", "В команде есть организации (" + organizations + "): перенесите их в другую команду или архивируйте"
                );
            }
            long profiles = adminTeamRepository.countProfiles(teamId);
            if (profiles > 0) {
                throw new InteractionValidationException(
                        "archived", "В команде есть сотрудники (" + profiles + "): переведите их в другую команду"
                );
            }
        }
        if (!adminTeamRepository.updateArchived(teamId, command.version(), command.archived(), now)) {
            throw InteractionConflictException.teamVersion(current.version());
        }
        auditJournalRepository.record(
                command.archived() ? AuditAction.TEAM_ARCHIVED : AuditAction.TEAM_RESTORED,
                actor.id(), "TEAM", teamId, current.name(), null, auditRequestId
        );
        catalogChangeEventRepository.insert(
                CatalogEntityType.TEAM, teamId,
                command.archived() ? CatalogChangeAction.ARCHIVE : CatalogChangeAction.RESTORE,
                current.name(), null, actor.id(), auditRequestId, now
        );
        return store(commandId, adminTeamRepository.findById(teamId).orElseThrow(TeamNotFoundException::new));
    }

    private static List<String> namesWithRole(List<AdminTeamRepository.TeamMember> members, UserRole role) {
        return members.stream()
                .filter(member -> member.role() == role)
                .map(AdminTeamRepository.TeamMember::displayName)
                .toList();
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

    private record ArchiveCommand(UUID teamId, int version, boolean archived) {
    }
}
