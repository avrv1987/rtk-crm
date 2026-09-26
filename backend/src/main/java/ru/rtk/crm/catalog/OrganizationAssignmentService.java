package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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
        return organizationAssignmentRepository.findCandidates(organization.teamId(), profile.id());
    }

    @Transactional(readOnly = true)
    public List<OrganizationAssignmentEvent> events(CrmProfile profile, UUID organizationId) {
        requireVisibleOrganization(profile, organizationId);
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
        String handoverNote = optionalHandoverNote(request.handoverNote());
        AssignmentCommand command = new AssignmentCommand(
                organizationId,
                expectedVersion,
                request.ownerManagerId(),
                handoverNote
        );
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
        OrganizationAssignmentResult result = applyAssignment(
                profile,
                organization,
                expectedVersion,
                request.ownerManagerId(),
                handoverNote,
                commandId,
                displayName(profile.id()),
                auditRequestId,
                now
        );
        return store(commandId, result);
    }

    @Transactional
    public OrganizationBulkAssignmentResult bulkAssign(
            CrmProfile profile,
            OrganizationBulkAssignmentRequest request,
            String idempotencyKey,
            String requestId
    ) {
        requireLeader(profile);
        List<OrganizationBulkAssignmentRequest.Item> items = validatedItems(request);
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        String auditRequestId = requiredRequestId(requestId);
        String fingerprint = CommandFingerprint.of(objectMapper, items);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.BULK_ASSIGN_ORGANIZATIONS,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replayBulk(profile.id(), normalizedKey, fingerprint);
        }
        String actorDisplayName = displayName(profile.id());
        List<OrganizationAssignmentResult> assigned = new ArrayList<>();
        List<UUID> unchanged = new ArrayList<>();
        List<OrganizationBulkAssignmentRequest.Item> lockOrder = items.stream()
                .sorted(Comparator.comparing(item -> item.organizationId().toString()))
                .toList();
        Map<UUID, Organization> organizations = new HashMap<>();
        for (OrganizationBulkAssignmentRequest.Item item : lockOrder) {
            organizations.put(item.organizationId(), requireVisibleOrganization(profile, item.organizationId()));
        }
        java.util.stream.Stream.concat(
                        organizations.values().stream().map(Organization::ownerManagerId),
                        items.stream().map(OrganizationBulkAssignmentRequest.Item::ownerManagerId)
                )
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .forEach(organizationAssignmentRepository::lockProfileForUpdate);
        for (OrganizationBulkAssignmentRequest.Item item : lockOrder) {
            Organization organization = organizations.get(item.organizationId());
            if (Objects.equals(organization.ownerManagerId(), item.ownerManagerId()) && !organization.requiresAssignment()) {
                unchanged.add(organization.id());
                continue;
            }
            if (Objects.equals(organization.ownerManagerId(), item.ownerManagerId())) {
                throw new InteractionValidationException(
                        "ownerManagerId",
                        "Назначить можно только активного менеджера команды этого вуза"
                );
            }
            assigned.add(applyAssignment(
                    profile,
                    organization,
                    item.version(),
                    item.ownerManagerId(),
                    null,
                    commandId,
                    actorDisplayName,
                    auditRequestId,
                    now
            ));
        }
        String resultJson = write(new OrganizationBulkAssignmentResult(assigned, unchanged));
        commandIdempotencyRepository.complete(commandId, resultJson);
        return readBulk(resultJson);
    }

    private OrganizationAssignmentResult applyAssignment(
            CrmProfile profile,
            Organization organization,
            int expectedVersion,
            UUID ownerManagerId,
            String handoverNote,
            UUID commandId,
            String actorDisplayName,
            String auditRequestId,
            OffsetDateTime now
    ) {
        if (organization.type() == OrganizationType.OPEN_ENROLLMENT) {
            throw new InteractionValidationException(
                    "ownerManagerId", "У служебной организации «Открытый набор (физлица)» нет ответственного КАМ"
            );
        }
        Map<UUID, OrganizationAssignmentProfile> lockedProfiles = lockAssignmentProfiles(
                organization.ownerManagerId(),
                ownerManagerId
        );
        OrganizationAssignmentCandidate newOwner = validatedOwner(
                lockedProfiles.get(ownerManagerId),
                organization.teamId(),
                profile.id()
        );
        String previousOwnerDisplayName = lockedDisplayName(lockedProfiles, organization.ownerManagerId());
        if (!organizationAssignmentRepository.updateOwner(
                organization.id(),
                organization.teamId(),
                expectedVersion,
                ownerManagerId,
                profile.id(),
                now
        )) {
            if (ownerManagerId != null
                    && organizationAssignmentRepository.findCandidate(ownerManagerId, organization.teamId(), profile.id()).isEmpty()) {
                throw new InteractionValidationException(
                        "ownerManagerId",
                        "Назначить можно только активного менеджера команды этого вуза"
                );
            }
            throw versionConflict(profile, organization.id());
        }
        organizationAssignmentRepository.incrementAccessRevisions(
                organization.ownerManagerId(),
                ownerManagerId
        );
        organizationAssignmentRepository.endOpenDeputy(organization.id(), now, profile.id(), actorDisplayName)
                .ifPresent(deputyProfileId -> organizationAssignmentRepository.incrementAccessRevisions(deputyProfileId, null));
        OrganizationAssignmentEvent event = organizationAssignmentRepository.insertEvent(
                UUID.randomUUID(),
                organization.id(),
                commandId,
                organization.ownerManagerId(),
                previousOwnerDisplayName,
                ownerManagerId,
                newOwner == null ? null : newOwner.displayName(),
                profile.id(),
                actorDisplayName,
                auditRequestId,
                null,
                handoverNote,
                expectedVersion + 1,
                now
        );
        return new OrganizationAssignmentResult(requireVisibleOrganization(profile, organization.id()), event);
    }

    private List<OrganizationBulkAssignmentRequest.Item> validatedItems(OrganizationBulkAssignmentRequest request) {
        List<OrganizationBulkAssignmentRequest.Item> items = request == null || request.items() == null
                ? List.of()
                : request.items();
        if (items.isEmpty() || items.size() > 100) {
            throw new InteractionValidationException("items", "Выберите от 1 до 100 вузов");
        }
        if (items.stream().anyMatch(item -> item == null || item.organizationId() == null)) {
            throw new InteractionValidationException("items", "Укажите вуз в каждой строке");
        }
        if (items.stream().anyMatch(item -> item.ownerManagerId() == null)) {
            throw new InteractionValidationException("ownerManagerId", "Выберите нового ответственного для каждого вуза");
        }
        if (items.stream().anyMatch(item -> item.version() == null || item.version() < 0)) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите страницу");
        }
        if (items.stream().map(OrganizationBulkAssignmentRequest.Item::organizationId).distinct().count() != items.size()) {
            throw new InteractionValidationException("items", "Вузы в списке не должны повторяться");
        }
        return List.copyOf(items);
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
            UUID teamId,
            UUID leaderId
    ) {
        if (profile == null) {
            return null;
        }
        boolean assignableRole = profile.role() == UserRole.USER
                || (profile.role() == UserRole.LEADER && profile.id().equals(leaderId));
        if (!profile.active() || !assignableRole || !teamId.equals(profile.teamId())) {
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
                    "Выберите нового ответственного или явно снимите назначение"
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

    private String optionalHandoverNote(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 2_000) {
            throw new InteractionValidationException("handoverNote", "Комментарий к передаче длиннее 2000 символов");
        }
        return normalized;
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

    private OrganizationBulkAssignmentResult replayBulk(UUID actorProfileId, String idempotencyKey, String fingerprint) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, CommandOperation.BULK_ASSIGN_ORGANIZATIONS, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved bulk organization assignment command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved bulk organization assignment command has no result");
        }
        return readBulk(command.resultJson());
    }

    private OrganizationBulkAssignmentResult readBulk(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, OrganizationBulkAssignmentResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored bulk organization assignment result cannot be read", exception);
        }
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

    private String write(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Organization assignment command result cannot be stored", exception);
        }
    }

    private record AssignmentCommand(UUID organizationId, int version, UUID ownerManagerId, String handoverNote) {
    }
}
