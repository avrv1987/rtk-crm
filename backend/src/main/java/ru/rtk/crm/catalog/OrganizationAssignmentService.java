package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Comparator;
import java.util.HashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class OrganizationAssignmentService {
    private final OrganizationRepository organizationRepository;
    private final OrganizationAssignmentRepository organizationAssignmentRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public OrganizationAssignmentService(
            OrganizationRepository organizationRepository,
            OrganizationAssignmentRepository organizationAssignmentRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.organizationRepository = organizationRepository;
        this.organizationAssignmentRepository = organizationAssignmentRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<OrganizationAssignmentCandidate> options(CrmProfile profile, UUID organizationId) {
        Organization organization = requireVisibleOrganization(profile, organizationId);
        requireLeader(profile);
        return organizationAssignmentRepository.findActiveUsersByTeamId(organization.teamId());
    }

    @Transactional(readOnly = true)
    public List<OrganizationAssignmentEvent> events(CrmProfile profile, UUID organizationId) {
        requireVisibleOrganization(profile, organizationId);
        requireLeader(profile);
        return organizationAssignmentRepository.findEventsByOrganizationId(organizationId);
    }

    @Transactional
    public OrganizationAssignmentResult assign(
            CrmProfile profile,
            UUID organizationId,
            OrganizationAssignmentRequest request,
            String idempotencyKey,
            String requestId
    ) {
        Organization organization = requireVisibleOrganization(profile, organizationId);
        requireLeader(profile);
        int expectedVersion = requiredVersion(request.version());
        requireOwnerManagerId(request);
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        String auditRequestId = requiredRequestId(requestId);
        AssignmentCommand command = new AssignmentCommand(organizationId, expectedVersion, request.ownerManagerId());
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.ASSIGN_ORGANIZATION,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replay(profile.id(), normalizedKey, fingerprint);
        }
        if (Objects.equals(organization.ownerManagerId(), request.ownerManagerId())) {
            throw new InteractionValidationException("ownerManagerId", "Этот менеджер уже назначен ответственным");
        }
        Map<UUID, OrganizationAssignmentProfile> lockedProfiles = lockAssignmentProfiles(
                organization.ownerManagerId(),
                request.ownerManagerId()
        );
        OrganizationAssignmentCandidate newOwner = validatedOwner(
                lockedProfiles.get(request.ownerManagerId()),
                organization.teamId()
        );
        String previousOwnerDisplayName = lockedDisplayName(lockedProfiles, organization.ownerManagerId());
        String actorDisplayName = displayName(profile.id());
        if (!organizationAssignmentRepository.updateOwner(
                organizationId,
                organization.teamId(),
                expectedVersion,
                request.ownerManagerId(),
                now
        )) {
            if (request.ownerManagerId() != null
                    && organizationAssignmentRepository.findActiveUserInTeam(request.ownerManagerId(), organization.teamId()).isEmpty()) {
                throw new InteractionValidationException(
                        "ownerManagerId",
                        "Назначить можно только активного менеджера команды этого вуза"
                );
            }
            throw versionConflict(profile, organizationId);
        }
        organizationAssignmentRepository.incrementAccessRevisions(
                organization.ownerManagerId(),
                request.ownerManagerId()
        );
        int resultVersion = expectedVersion + 1;
        OrganizationAssignmentEvent event = organizationAssignmentRepository.insertEvent(
                UUID.randomUUID(),
                organizationId,
                commandId,
                organization.ownerManagerId(),
                previousOwnerDisplayName,
                request.ownerManagerId(),
                newOwner == null ? null : newOwner.displayName(),
                profile.id(),
                actorDisplayName,
                auditRequestId,
                resultVersion,
                now
        );
        Organization updated = requireVisibleOrganization(profile, organizationId);
        return store(commandId, new OrganizationAssignmentResult(updated, event));
    }

    private Organization requireVisibleOrganization(CrmProfile profile, UUID organizationId) {
        if (organizationId == null) {
            throw new InteractionValidationException("id", "Укажите вуз");
        }
        return organizationRepository.findVisibleById(profile, organizationId)
                .orElseThrow(OrganizationNotFoundException::new);
    }

    private void requireLeader(CrmProfile profile) {
        if (profile.role() != UserRole.LEADER) {
            throw new OrganizationAssignmentAccessDeniedException();
        }
    }

    private OrganizationAssignmentCandidate validatedOwner(
            OrganizationAssignmentProfile profile,
            UUID teamId
    ) {
        if (profile == null) {
            return null;
        }
        if (!profile.active() || profile.role() != UserRole.USER || !teamId.equals(profile.teamId())) {
            throw new InteractionValidationException(
                    "ownerManagerId",
                    "Назначить можно только активного менеджера команды этого вуза"
            );
        }
        return new OrganizationAssignmentCandidate(profile.id(), profile.displayName());
    }

    private void requireOwnerManagerId(OrganizationAssignmentRequest request) {
        if (!request.ownerManagerIdPresent()) {
            throw new InteractionValidationException(
                    "ownerManagerId",
                    "ownerManagerId is required and may be null only for explicit deassignment"
            );
        }
    }

    private Map<UUID, OrganizationAssignmentProfile> lockAssignmentProfiles(
            UUID previousOwnerManagerId,
            UUID ownerManagerId
    ) {
        List<UUID> profileIds = java.util.stream.Stream.of(previousOwnerManagerId, ownerManagerId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        Map<UUID, OrganizationAssignmentProfile> profiles = new HashMap<>();
        for (UUID profileId : profileIds) {
            OrganizationAssignmentProfile profile = organizationAssignmentRepository.lockProfileForUpdate(profileId)
                    .orElseThrow(() -> missingAssignmentProfile(profileId, ownerManagerId));
            profiles.put(profileId, profile);
        }
        return profiles;
    }

    private RuntimeException missingAssignmentProfile(UUID profileId, UUID ownerManagerId) {
        if (profileId.equals(ownerManagerId)) {
            return new InteractionValidationException(
                    "ownerManagerId",
                    "Назначить можно только активного менеджера команды этого вуза"
            );
        }
        return new IllegalStateException("Organization assignment profile is unavailable");
    }

    private String lockedDisplayName(Map<UUID, OrganizationAssignmentProfile> profiles, UUID profileId) {
        if (profileId == null) {
            return null;
        }
        OrganizationAssignmentProfile profile = profiles.get(profileId);
        if (profile == null) {
            throw new IllegalStateException("Organization assignment profile is unavailable");
        }
        return profile.displayName();
    }

    private String displayName(UUID profileId) {
        return organizationAssignmentRepository.findDisplayName(profileId)
                .orElseThrow(() -> new IllegalStateException("Organization assignment profile is unavailable"));
    }

    private int requiredVersion(Integer value) {
        if (value == null || value < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите страницу");
        }
        return value;
    }

    private String requiredIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException("Idempotency-Key", "Не передан ключ повтора запроса Idempotency-Key");
        }
        if (value.length() > 255) {
            throw new InteractionValidationException("Idempotency-Key", "Ключ повтора запроса Idempotency-Key длиннее 255 символов");
        }
        return value;
    }

    private String requiredRequestId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Request id is unavailable for organization assignment audit");
        }
        if (value.length() > 64) {
            throw new IllegalStateException("Request id exceeds the organization assignment audit limit");
        }
        return value;
    }

    private InteractionConflictException versionConflict(CrmProfile profile, UUID organizationId) {
        Organization visibleOrganization = requireVisibleOrganization(profile, organizationId);
        int currentVersion = organizationAssignmentRepository.findVersionByOrganizationId(organizationId)
                .orElse(visibleOrganization.version());
        return InteractionConflictException.organizationVersion(currentVersion);
    }

    private OrganizationAssignmentResult replay(UUID actorProfileId, String idempotencyKey, String fingerprint) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, CommandOperation.ASSIGN_ORGANIZATION, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved organization assignment command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved organization assignment command has no result");
        }
        return read(command.resultJson());
    }

    private OrganizationAssignmentResult store(UUID commandId, OrganizationAssignmentResult result) {
        String resultJson = write(result);
        commandIdempotencyRepository.complete(commandId, resultJson);
        return read(resultJson);
    }

    private OrganizationAssignmentResult read(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, OrganizationAssignmentResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored organization assignment command result cannot be read", exception);
        }
    }

    private String write(OrganizationAssignmentResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Organization assignment command result cannot be stored", exception);
        }
    }

    private record AssignmentCommand(UUID organizationId, int version, UUID ownerManagerId) {
    }
}
