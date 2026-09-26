package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class AdminOrganizationService {
    private final AdminOrganizationRepository adminOrganizationRepository;
    private final OrganizationAssignmentRepository organizationAssignmentRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public AdminOrganizationService(
            AdminOrganizationRepository adminOrganizationRepository,
            OrganizationAssignmentRepository organizationAssignmentRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.adminOrganizationRepository = adminOrganizationRepository;
        this.organizationAssignmentRepository = organizationAssignmentRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AdminOrganizationPage list(CrmProfile actor, OrganizationQuery query) {
        AdminAuthorization.requireAdmin(actor);
        return adminOrganizationRepository.findPage(query);
    }

    @Transactional
    public AdminOrganization transferTeam(
            CrmProfile actor,
            UUID organizationId,
            AdminOrganizationTeamRequest request,
            String idempotencyKey,
            String requestId
    ) {
        AdminAuthorization.requireAdmin(actor);
        if (organizationId == null) {
            throw new InteractionValidationException("id", "Укажите вуз");
        }
        if (request == null || request.version() == null || request.version() < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите страницу");
        }
        if (request.teamId() == null) {
            throw new InteractionValidationException("teamId", "Выберите команду");
        }
        TransferCommand command = new TransferCommand(organizationId, request.version(), request.teamId());
        String normalizedKey = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                actor.id(),
                CommandOperation.TRANSFER_ORGANIZATION_TEAM,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replay(actor.id(), normalizedKey, fingerprint);
        }

        adminOrganizationRepository.findById(organizationId)
                .map(AdminOrganization::ownerManagerId)
                .ifPresent(organizationAssignmentRepository::lockProfileForUpdate);
        AdminOrganization organization = adminOrganizationRepository.findByIdForUpdate(organizationId)
                .orElseThrow(OrganizationNotFoundException::new);
        if (organization.version() != command.version()) {
            throw InteractionConflictException.organizationVersion(organization.version());
        }
        if (organization.teamId().equals(command.teamId())) {
            throw new InteractionValidationException("teamId", "Вуз уже относится к этой команде");
        }
        if (!adminOrganizationRepository.activeTeamExists(command.teamId())) {
            throw new InteractionValidationException("teamId", "Команда не найдена или в архиве");
        }
        UUID previousOwnerId = organization.ownerManagerId();
        boolean clearOwner = previousOwnerId != null && !Objects.equals(
                adminOrganizationRepository.findProfileTeamId(previousOwnerId).orElse(null),
                command.teamId()
        );
        if (!adminOrganizationRepository.updateTeam(organizationId, command.version(), command.teamId(), clearOwner, now)) {
            throw InteractionConflictException.organizationVersion(
                    organizationAssignmentRepository.findVersionByOrganizationId(organizationId)
                            .orElseThrow(OrganizationNotFoundException::new)
            );
        }
        int resultVersion = command.version() + 1;
        String actorDisplayName = organizationAssignmentRepository.findDisplayName(actor.id())
                .orElseThrow(() -> new IllegalStateException("Administrator profile is unavailable for audit"));
        if (clearOwner) {
            organizationAssignmentRepository.insertEvent(
                    UUID.randomUUID(),
                    organizationId,
                    commandId,
                    previousOwnerId,
                    organization.ownerManagerName(),
                    null,
                    null,
                    actor.id(),
                    actorDisplayName,
                    auditRequestId,
                    OrganizationAssignmentReason.ORGANIZATION_TEAM_CHANGED,
                    null,
                    resultVersion,
                    now
            );
            organizationAssignmentRepository.incrementAccessRevisions(previousOwnerId, null);
        }
        adminOrganizationRepository.incrementLeaderAccessRevisions(organization.teamId(), command.teamId());
        adminOrganizationRepository.insertTeamEvent(
                UUID.randomUUID(),
                organizationId,
                commandId,
                organization.teamId(),
                command.teamId(),
                previousOwnerId,
                actor.id(),
                actorDisplayName,
                auditRequestId,
                resultVersion,
                now
        );
        return store(commandId, adminOrganizationRepository.findById(organizationId)
                .orElseThrow(OrganizationNotFoundException::new));
    }

    private AdminOrganization replay(UUID actorProfileId, String idempotencyKey, String fingerprint) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, CommandOperation.TRANSFER_ORGANIZATION_TEAM, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved organization transfer command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved organization transfer command has no result");
        }
        return read(command.resultJson());
    }

    private AdminOrganization store(UUID commandId, AdminOrganization result) {
        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Organization transfer result cannot be stored", exception);
        }
        commandIdempotencyRepository.complete(commandId, resultJson);
        return read(resultJson);
    }

    private AdminOrganization read(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, AdminOrganization.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored organization transfer result cannot be read", exception);
        }
    }

    private record TransferCommand(UUID organizationId, int version, UUID teamId) {
    }
}
