package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationAssignmentAccessDeniedException;
import ru.rtk.crm.catalog.OrganizationAssignmentProfile;
import ru.rtk.crm.catalog.OrganizationAssignmentRepository;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class OrganizationDeputyService {
    private static final int MAX_PERIOD_DAYS = 366;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final OrganizationRepository organizationRepository;
    private final OrganizationAssignmentRepository organizationAssignmentRepository;
    private final OrganizationDeputyRepository organizationDeputyRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public OrganizationDeputyService(
            OrganizationRepository organizationRepository,
            OrganizationAssignmentRepository organizationAssignmentRepository,
            OrganizationDeputyRepository organizationDeputyRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.organizationRepository = organizationRepository;
        this.organizationAssignmentRepository = organizationAssignmentRepository;
        this.organizationDeputyRepository = organizationDeputyRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<OrganizationDeputy> list(CrmProfile profile, UUID organizationId) {
        requireVisibleOrganization(profile, organizationId);
        requireLeader(profile);
        OffsetDateTime now = OffsetDateTime.now();
        return organizationDeputyRepository.findByOrganization(organizationId).stream()
                .map(row -> row.view(now))
                .toList();
    }

    @Transactional
    public OrganizationDeputy assign(
            CrmProfile profile,
            UUID organizationId,
            OrganizationDeputyRequest request,
            String idempotencyKey
    ) {
        requireVisibleOrganization(profile, organizationId);
        requireLeader(profile);
        OrganizationDeputyRequest command = validated(request);
        String normalizedKey = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(objectMapper, new AssignCommand(organizationId, command));
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.ASSIGN_ORGANIZATION_DEPUTY,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replay(profile.id(), CommandOperation.ASSIGN_ORGANIZATION_DEPUTY, normalizedKey, fingerprint);
        }
        LocalDate today = now.atZoneSameInstant(WorkProperties.ZONE).toLocalDate();
        if (command.endsOn().isBefore(today)) {
            throw new InteractionValidationException("endsOn", "Период замещения уже закончился");
        }
        organizationDeputyRepository.lockOrganization(organizationId);
        Organization organization = requireVisibleOrganization(profile, organizationId);
        if (organization.requiresAssignment()) {
            throw new InteractionValidationException(
                    "deputyProfileId",
                    "У вуза нет активного ответственного: назначьте ответственного, а не заместителя"
            );
        }
        if (command.deputyProfileId().equals(organization.ownerManagerId())) {
            throw new InteractionValidationException("deputyProfileId", "Заместитель должен отличаться от ответственного");
        }
        Optional<OrganizationDeputyRepository.DeputyRow> open = organizationDeputyRepository.findOpenForUpdate(organizationId);
        if (open.isPresent() && open.get().endsAt().isAfter(now)) {
            throw new InteractionValidationException(
                    "deputyProfileId",
                    "У вуза уже есть заместитель до " + DATE_FORMAT.format(open.get().endsOn())
                            + "; сначала завершите текущее замещение"
            );
        }
        open.ifPresent(expired -> endAutomatically(expired));
        OrganizationAssignmentProfile deputy = organizationAssignmentRepository.lockProfileForUpdate(command.deputyProfileId())
                .filter(value -> value.active() && value.role() == UserRole.USER && organization.teamId().equals(value.teamId()))
                .orElseThrow(() -> new InteractionValidationException(
                        "deputyProfileId",
                        "Заместителем можно назначить только активного менеджера команды этого вуза"
                ));
        OrganizationDeputyRepository.DeputyRow row = new OrganizationDeputyRepository.DeputyRow(
                UUID.randomUUID(),
                organizationId,
                deputy.id(),
                deputy.displayName(),
                command.startsOn(),
                command.endsOn(),
                command.startsOn().atStartOfDay(WorkProperties.ZONE).toOffsetDateTime(),
                command.endsOn().plusDays(1).atStartOfDay(WorkProperties.ZONE).toOffsetDateTime(),
                profile.id(),
                displayName(profile.id()),
                now,
                null,
                null,
                null
        );
        organizationDeputyRepository.insert(row, commandId);
        organizationAssignmentRepository.incrementAccessRevisions(deputy.id(), null);
        return store(commandId, row.view(now));
    }

    @Transactional
    public OrganizationDeputy end(CrmProfile profile, UUID organizationId, UUID deputyId, String idempotencyKey) {
        requireVisibleOrganization(profile, organizationId);
        requireLeader(profile);
        String normalizedKey = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(objectMapper, new EndCommand(organizationId, deputyId));
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.END_ORGANIZATION_DEPUTY,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replay(profile.id(), CommandOperation.END_ORGANIZATION_DEPUTY, normalizedKey, fingerprint);
        }
        OrganizationDeputyRepository.DeputyRow row = organizationDeputyRepository.findForUpdate(organizationId, deputyId)
                .orElseThrow(OrganizationDeputyNotFoundException::new);
        if (row.endedAt() != null || !row.endsAt().isAfter(now)) {
            throw new InteractionValidationException("deputyId", "Замещение уже завершено");
        }
        String actorDisplayName = displayName(profile.id());
        if (!organizationDeputyRepository.end(row.id(), now, profile.id(), actorDisplayName)) {
            throw new IllegalStateException("Organization deputy period changed while it was being ended");
        }
        organizationAssignmentRepository.incrementAccessRevisions(row.deputyProfileId(), null);
        OrganizationDeputyRepository.DeputyRow ended = organizationDeputyRepository.findForUpdate(organizationId, deputyId)
                .orElseThrow(OrganizationDeputyNotFoundException::new);
        return store(commandId, ended.view(now));
    }

    @Scheduled(
            fixedDelayString = "${app.work.deputy-expiry-delay}",
            initialDelayString = "${app.work.deputy-expiry-delay}"
    )
    @Transactional
    public void endExpiredPeriods() {
        organizationDeputyRepository.findExpiredForUpdate(OffsetDateTime.now()).forEach(this::endAutomatically);
    }

    private void endAutomatically(OrganizationDeputyRepository.DeputyRow row) {
        if (organizationDeputyRepository.end(row.id(), row.endsAt(), null, null)) {
            organizationAssignmentRepository.incrementAccessRevisions(row.deputyProfileId(), null);
        }
    }

    private OrganizationDeputyRequest validated(OrganizationDeputyRequest request) {
        if (request == null || request.deputyProfileId() == null) {
            throw new InteractionValidationException("deputyProfileId", "Выберите заместителя");
        }
        if (request.startsOn() == null) {
            throw new InteractionValidationException("startsOn", "Укажите дату начала замещения");
        }
        if (request.endsOn() == null) {
            throw new InteractionValidationException("endsOn", "Укажите дату окончания замещения");
        }
        if (request.endsOn().isBefore(request.startsOn())) {
            throw new InteractionValidationException("endsOn", "Дата окончания раньше даты начала");
        }
        if (ChronoUnit.DAYS.between(request.startsOn(), request.endsOn()) >= MAX_PERIOD_DAYS) {
            throw new InteractionValidationException("endsOn", "Замещение можно назначить не больше чем на год");
        }
        return request;
    }

    private Organization requireVisibleOrganization(CrmProfile profile, UUID organizationId) {
        return organizationRepository.findVisibleById(profile, organizationId)
                .orElseThrow(OrganizationNotFoundException::new);
    }

    private void requireLeader(CrmProfile profile) {
        if (profile.role() != UserRole.LEADER) {
            throw new OrganizationAssignmentAccessDeniedException();
        }
    }

    private String displayName(UUID profileId) {
        return organizationAssignmentRepository.findDisplayName(profileId)
                .orElseThrow(() -> new IllegalStateException("Deputy assignment actor profile is unavailable"));
    }

    private OrganizationDeputy replay(
            UUID actorProfileId,
            CommandOperation operation,
            String idempotencyKey,
            String fingerprint
    ) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, operation, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved deputy command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved deputy command has no result");
        }
        return read(command.resultJson());
    }

    private OrganizationDeputy store(UUID commandId, OrganizationDeputy deputy) {
        try {
            String resultJson = objectMapper.writeValueAsString(deputy);
            commandIdempotencyRepository.complete(commandId, resultJson);
            return read(resultJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Deputy command result cannot be stored", exception);
        }
    }

    private OrganizationDeputy read(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, OrganizationDeputy.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored deputy command result cannot be read", exception);
        }
    }

    private record AssignCommand(UUID organizationId, OrganizationDeputyRequest request) {
    }

    private record EndCommand(UUID organizationId, UUID deputyId) {
    }
}
