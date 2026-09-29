package ru.rtk.crm.agreement;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.agreement.AgreementModels.Activity;
import ru.rtk.crm.agreement.AgreementModels.ActivityDeleted;
import ru.rtk.crm.agreement.AgreementModels.ActivityKind;
import ru.rtk.crm.agreement.AgreementModels.ActivityKindCreateRequest;
import ru.rtk.crm.agreement.AgreementModels.ActivityKindUpdateRequest;
import ru.rtk.crm.agreement.AgreementModels.ActivityRequest;
import ru.rtk.crm.agreement.AgreementModels.Agreement;
import ru.rtk.crm.agreement.AgreementModels.AgreementRequest;
import ru.rtk.crm.agreement.AgreementModels.AgreementSummary;
import ru.rtk.crm.agreement.AgreementModels.Confirmation;
import ru.rtk.crm.agreement.AgreementModels.ConfirmationQuery;
import ru.rtk.crm.agreement.AgreementModels.LinkOptions;
import ru.rtk.crm.agreement.AgreementModels.PlanKind;
import ru.rtk.crm.agreement.AgreementRepository.ActivityRow;
import ru.rtk.crm.agreement.AgreementRepository.ActivityValues;
import ru.rtk.crm.agreement.AgreementRepository.AgreementRow;
import ru.rtk.crm.agreement.AgreementRepository.AgreementValues;
import ru.rtk.crm.agreement.AgreementRepository.ConfirmationRow;
import ru.rtk.crm.attachment.AttachmentStatus;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class AgreementService {
    public static final int CONFIRMATION_LIMIT = 500;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final OrganizationRepository organizationRepository;
    private final AgreementRepository repository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;
    private final DataSize archiveMaxSize;
    private final AuditJournalRepository auditJournalRepository;

    public AgreementService(
            OrganizationRepository organizationRepository,
            AgreementRepository repository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper,
            @Value("${app.agreements.archive-max-size}") DataSize archiveMaxSize,
            AuditJournalRepository auditJournalRepository
    ) {
        this.organizationRepository = organizationRepository;
        this.repository = repository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
        this.archiveMaxSize = archiveMaxSize;
        this.auditJournalRepository = auditJournalRepository;
    }

    @Transactional(readOnly = true)
    public List<ActivityKind> kinds(boolean includeArchived) {
        return repository.findKinds(includeArchived);
    }

    @Transactional
    public ActivityKind createKind(CrmProfile profile, ActivityKindCreateRequest request, String idempotencyKey) {
        AdminAuthorization.requireAdmin(profile);
        String name = requiredText(request.name(), "name");
        return idempotent(profile, CommandOperation.CREATE_AGREEMENT_ACTIVITY_KIND, idempotencyKey, name, ActivityKind.class, () -> {
            UUID id = UUID.randomUUID();
            try {
                repository.insertKind(id, name, OffsetDateTime.now());
            } catch (DuplicateKeyException exception) {
                throw kindNameTaken();
            }
            return repository.findKind(id).orElseThrow(AgreementException::kindNotFound);
        });
    }

    @Transactional
    public ActivityKind updateKind(CrmProfile profile, UUID kindId, ActivityKindUpdateRequest request, String idempotencyKey) {
        AdminAuthorization.requireAdmin(profile);
        ActivityKind current = repository.findKind(kindId).orElseThrow(AgreementException::kindNotFound);
        String name = requiredText(request.name(), "name");
        int version = requiredVersion(request.version());
        boolean archived = Boolean.TRUE.equals(request.archived());
        return idempotent(
                profile,
                CommandOperation.UPDATE_AGREEMENT_ACTIVITY_KIND,
                idempotencyKey,
                new UpdateKindCommand(kindId, version, name, archived),
                ActivityKind.class,
                () -> {
                    if (updatedKind(kindId, version, name, archived) != 1) {
                        throw AgreementException.kindVersion(repository.findKind(kindId).map(ActivityKind::version)
                                .orElse(current.version()));
                    }
                    return repository.findKind(kindId).orElseThrow(AgreementException::kindNotFound);
                }
        );
    }

    @Transactional(readOnly = true)
    public List<AgreementSummary> list(CrmProfile profile, UUID organizationId) {
        visibleOrganization(profile, organizationId);
        return repository.findSummaries(organizationId);
    }

    @Transactional(readOnly = true)
    public LinkOptions linkOptions(CrmProfile profile, UUID organizationId) {
        Organization organization = visibleOrganization(profile, organizationId);
        return new LinkOptions(
                repository.findOrganizationInteractions(organizationId),
                repository.findOrganizationCleanAttachments(organizationId),
                repository.findResponsibles(organization.teamId())
        );
    }

    @Transactional(readOnly = true)
    public Agreement get(CrmProfile profile, UUID agreementId) {
        visibleAgreement(profile, agreementId);
        return repository.findAgreement(agreementId).orElseThrow(AgreementException::agreementNotFound);
    }

    @Transactional
    public Agreement create(
            CrmProfile profile,
            UUID organizationId,
            AgreementRequest request,
            String idempotencyKey,
            String requestId
    ) {
        Organization organization = visibleOrganization(profile, organizationId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        AgreementValues values = agreementValues(organizationId, request);
        return idempotent(
                profile,
                CommandOperation.CREATE_AGREEMENT,
                idempotencyKey,
                new CreateAgreementCommand(organizationId, values),
                Agreement.class,
                () -> {
                    UUID id = UUID.randomUUID();
                    try {
                        repository.insertAgreement(id, organizationId, values, profile.id(), OffsetDateTime.now());
                    } catch (DuplicateKeyException exception) {
                        throw numberTaken();
                    }
                    recordPlanChange(profile, organization, id, values, null, null, requestId);
                    return repository.findAgreement(id).orElseThrow(AgreementException::agreementNotFound);
                }
        );
    }

    @Transactional
    public Agreement update(CrmProfile profile, UUID agreementId, AgreementRequest request, String idempotencyKey, String requestId) {
        AgreementRow agreement = visibleAgreement(profile, agreementId);
        Organization organization = visibleOrganization(profile, agreement.organizationId());
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        int version = requiredVersion(request.version());
        AgreementValues values = agreementValues(agreement.organizationId(), request);
        return idempotent(
                profile,
                CommandOperation.UPDATE_AGREEMENT,
                idempotencyKey,
                new UpdateAgreementCommand(agreementId, version, values),
                Agreement.class,
                () -> {
                    if (updatedAgreement(agreementId, version, values) != 1) {
                        throw AgreementException.agreementVersion(repository.findAgreementRow(agreementId)
                                .map(AgreementRow::version)
                                .orElse(agreement.version()));
                    }
                    recordPlanChange(profile, organization, agreementId, values, agreement.plannedKind(), agreement.plannedOn(), requestId);
                    return repository.findAgreement(agreementId).orElseThrow(AgreementException::agreementNotFound);
                }
        );
    }

    @Transactional
    public Activity createActivity(CrmProfile profile, UUID agreementId, ActivityRequest request, String idempotencyKey) {
        AgreementRow agreement = visibleAgreement(profile, agreementId);
        Organization organization = visibleOrganization(profile, agreement.organizationId());
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        ActivityValues values = activityValues(organization, request, null, null);
        Links links = links(agreement.organizationId(), request);
        return idempotent(
                profile,
                CommandOperation.CREATE_AGREEMENT_ACTIVITY,
                idempotencyKey,
                new CreateActivityCommand(agreementId, values, links),
                Activity.class,
                () -> {
                    UUID id = UUID.randomUUID();
                    repository.insertActivity(id, agreementId, values, OffsetDateTime.now());
                    repository.replaceActivityLinks(id, links.interactionIds(), links.attachmentIds());
                    return repository.findActivity(id).orElseThrow(AgreementException::activityNotFound);
                }
        );
    }

    @Transactional
    public Activity updateActivity(CrmProfile profile, UUID activityId, ActivityRequest request, String idempotencyKey) {
        ActivityRow activity = visibleActivity(profile, activityId);
        Organization organization = visibleOrganization(profile, activity.organizationId());
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        int version = requiredVersion(request.version());
        ActivityValues values = activityValues(organization, request, activity.kindId(), activity.responsibleProfileId());
        Links links = links(activity.organizationId(), request);
        return idempotent(
                profile,
                CommandOperation.UPDATE_AGREEMENT_ACTIVITY,
                idempotencyKey,
                new UpdateActivityCommand(activityId, version, values, links),
                Activity.class,
                () -> {
                    if (repository.updateActivity(activityId, version, values, OffsetDateTime.now()) != 1) {
                        throw AgreementException.activityVersion(currentActivityVersion(activity));
                    }
                    repository.replaceActivityLinks(activityId, links.interactionIds(), links.attachmentIds());
                    return repository.findActivity(activityId).orElseThrow(AgreementException::activityNotFound);
                }
        );
    }

    @Transactional
    public ActivityDeleted deleteActivity(CrmProfile profile, UUID activityId, Integer version, String idempotencyKey) {
        int expectedVersion = requiredVersion(version);
        return idempotent(
                profile,
                CommandOperation.DELETE_AGREEMENT_ACTIVITY,
                idempotencyKey,
                new DeleteActivityCommand(activityId, expectedVersion),
                ActivityDeleted.class,
                () -> {
                    ActivityRow activity = visibleActivity(profile, activityId);
                    ContactInteractionMutationAuthorization.requireCardEditor(profile);
                    if (repository.deleteActivity(activityId, expectedVersion) != 1) {
                        throw AgreementException.activityVersion(currentActivityVersion(activity));
                    }
                    return new ActivityDeleted(activityId);
                }
        );
    }

    @Transactional(readOnly = true)
    public List<Confirmation> confirmations(CrmProfile profile, ConfirmationQuery query) {
        return confirmationRows(profile, query).stream().map(ConfirmationRow::confirmation).toList();
    }

    @Transactional(readOnly = true)
    public List<ConfirmationRow> confirmationRows(CrmProfile profile, ConfirmationQuery query) {
        if (query.from() != null && query.to() != null && query.from().isAfter(query.to())) {
            throw new InteractionValidationException("to", "Дата окончания периода раньше даты начала");
        }
        if (query.organizationId() != null) {
            visibleOrganization(profile, query.organizationId());
        }
        return organizationRepository.visibilityScope(profile)
                .map(scope -> {
                    List<ConfirmationRow> rows = repository.findConfirmations(scope, query, CONFIRMATION_LIMIT + 1);
                    if (rows.size() > CONFIRMATION_LIMIT) {
                        throw AgreementException.confirmationLimit(CONFIRMATION_LIMIT);
                    }
                    return rows;
                })
                .orElse(List.of());
    }

    @Transactional
    public List<ConfirmationRow> archiveRows(CrmProfile profile, ConfirmationQuery query, String fileName, String requestId) {
        List<ConfirmationRow> rows = confirmationRows(profile, query);
        List<Confirmation> clean = rows.stream()
                .map(ConfirmationRow::confirmation)
                .filter(confirmation -> AttachmentStatus.CLEAN.name().equals(confirmation.status()))
                .toList();
        if (clean.stream().mapToLong(Confirmation::sizeBytes).sum() > archiveMaxSize.toBytes()) {
            throw AgreementException.archiveSize(archiveMaxSize);
        }
        auditJournalRepository.record(AuditAction.CONFIRMATIONS_DOWNLOADED, profile.id(), "CONFIRMATIONS", query.agreementId(),
                fileName, "файлов в архиве: " + clean.size() + ", строк описи: " + rows.size(), requestId);
        return rows;
    }

    private int updatedKind(UUID kindId, int version, String name, boolean archived) {
        try {
            return repository.updateKind(kindId, version, name, archived, OffsetDateTime.now());
        } catch (DuplicateKeyException exception) {
            throw kindNameTaken();
        }
    }

    private int updatedAgreement(UUID agreementId, int version, AgreementValues values) {
        try {
            return repository.updateAgreement(agreementId, version, values, OffsetDateTime.now());
        } catch (DuplicateKeyException exception) {
            throw numberTaken();
        }
    }

    private int currentActivityVersion(ActivityRow activity) {
        return repository.findActivityRow(activity.id()).map(ActivityRow::version).orElseThrow(AgreementException::activityNotFound);
    }

    private Organization visibleOrganization(CrmProfile profile, UUID organizationId) {
        return organizationRepository.findVisibleById(profile, organizationId).orElseThrow(OrganizationNotFoundException::new);
    }

    private AgreementRow visibleAgreement(CrmProfile profile, UUID agreementId) {
        AgreementRow agreement = repository.findAgreementRow(agreementId).orElseThrow(AgreementException::agreementNotFound);
        organizationRepository.findVisibleById(profile, agreement.organizationId())
                .orElseThrow(AgreementException::agreementNotFound);
        return agreement;
    }

    private ActivityRow visibleActivity(CrmProfile profile, UUID activityId) {
        ActivityRow activity = repository.findActivityRow(activityId).orElseThrow(AgreementException::activityNotFound);
        organizationRepository.findVisibleById(profile, activity.organizationId())
                .orElseThrow(AgreementException::activityNotFound);
        return activity;
    }

    private AgreementValues agreementValues(UUID organizationId, AgreementRequest request) {
        if (request.status() == null) {
            throw new InteractionValidationException("status", "Укажите статус соглашения");
        }
        requireOrder(request.concludedOn(), request.validUntil(), "validUntil", "Срок действия раньше даты заключения");
        if ((request.plannedKind() == null) != (request.plannedOn() == null)) {
            throw new InteractionValidationException(
                    request.plannedKind() == null ? "plannedKind" : "plannedOn",
                    "Укажите вид и дату плана вместе или очистите оба поля"
            );
        }
        if (request.plannedKind() == PlanKind.RENEWAL) {
            requireOrder(request.concludedOn(), request.plannedOn(), "plannedOn", "Плановое продление раньше даты заключения");
        }
        if (request.fileAttachmentId() != null && repository.findOrganizationCleanAttachmentIds(
                organizationId,
                List.of(request.fileAttachmentId())
        ).isEmpty()) {
            throw new InteractionValidationException(
                    "fileAttachmentId",
                    "Файл соглашения должен быть проверенным документом из работ этого вуза"
            );
        }
        return new AgreementValues(
                requiredText(request.number(), "number"),
                request.concludedOn(),
                request.validUntil(),
                nullableText(request.parties()),
                request.status(),
                request.fileAttachmentId(),
                request.plannedKind(),
                request.plannedOn()
        );
    }

    private void recordPlanChange(
            CrmProfile profile,
            Organization organization,
            UUID agreementId,
            AgreementValues values,
            PlanKind previousKind,
            LocalDate previousOn,
            String requestId
    ) {
        if (previousKind == values.plannedKind() && Objects.equals(previousOn, values.plannedOn())) {
            return;
        }
        auditJournalRepository.record(AuditAction.AGREEMENT_PLAN_CHANGED, profile.id(), "AGREEMENT", agreementId,
                organization.name() + ", соглашение № " + values.number(),
                "план: " + planText(previousKind, previousOn) + " → " + planText(values.plannedKind(), values.plannedOn()),
                requestId);
    }

    private static String planText(PlanKind kind, LocalDate on) {
        return kind == null ? "не задан" : kind.title().toLowerCase(Locale.ROOT) + " " + DATE.format(on);
    }

    private ActivityValues activityValues(
            Organization organization,
            ActivityRequest request,
            UUID currentKindId,
            UUID currentResponsibleId
    ) {
        if (request.kindId() == null) {
            throw new InteractionValidationException("kindId", "Выберите вид мероприятия");
        }
        ActivityKind kind = repository.findKind(request.kindId())
                .orElseThrow(() -> new InteractionValidationException("kindId", "Вид мероприятия не найден"));
        if (kind.archived() && !kind.id().equals(currentKindId)) {
            throw new InteractionValidationException("kindId", "Вид мероприятия в архиве; выберите действующий");
        }
        if (request.status() == null) {
            throw new InteractionValidationException("status", "Укажите статус мероприятия");
        }
        requireOrder(request.plannedStart(), request.plannedEnd(), "plannedEnd", "Плановое окончание раньше планового начала");
        requireOrder(request.actualStart(), request.actualEnd(), "actualEnd", "Фактическое окончание раньше фактического начала");
        if (request.responsibleProfileId() != null
                && !request.responsibleProfileId().equals(currentResponsibleId)
                && repository.findResponsibles(organization.teamId()).stream()
                .noneMatch(responsible -> responsible.id().equals(request.responsibleProfileId()))) {
            throw new InteractionValidationException(
                    "responsibleProfileId",
                    "Ответственным может быть активный менеджер или руководитель команды вуза"
            );
        }
        return new ActivityValues(
                kind.id(),
                requiredText(request.title(), "title"),
                nullableText(request.unit()),
                request.plannedVolume(),
                request.actualVolume(),
                request.plannedStart(),
                request.plannedEnd(),
                request.actualStart(),
                request.actualEnd(),
                request.responsibleProfileId(),
                request.status()
        );
    }

    private Links links(UUID organizationId, ActivityRequest request) {
        List<UUID> interactionIds = uniqueIds(request.interactionIds(), "interactionIds");
        List<UUID> attachmentIds = uniqueIds(request.attachmentIds(), "attachmentIds");
        Set<UUID> knownInteractions = repository.findOrganizationInteractionIds(organizationId, interactionIds);
        if (knownInteractions.size() != interactionIds.size()) {
            throw new InteractionValidationException("interactionIds", "Можно связать только работы этого вуза");
        }
        Set<UUID> knownAttachments = repository.findOrganizationCleanAttachmentIds(organizationId, attachmentIds);
        if (knownAttachments.size() != attachmentIds.size()) {
            throw new InteractionValidationException(
                    "attachmentIds",
                    "Подтверждением может быть только проверенный документ из работ этого вуза"
            );
        }
        return new Links(interactionIds, attachmentIds);
    }

    private static List<UUID> uniqueIds(List<UUID> values, String field) {
        if (values == null) {
            return List.of();
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new InteractionValidationException(field, "Список не должен содержать пустых значений");
        }
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static void requireOrder(LocalDate start, LocalDate end, String field, String message) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new InteractionValidationException(field, message);
        }
    }

    private static InteractionValidationException numberTaken() {
        return new InteractionValidationException("number", "У этого вуза уже есть соглашение с таким номером");
    }

    private static InteractionValidationException kindNameTaken() {
        return new InteractionValidationException("name", "Вид мероприятия с таким названием уже есть");
    }

    private static int requiredVersion(Integer version) {
        if (version == null) {
            throw new InteractionValidationException("version", "Укажите версию, которую вы изменяете");
        }
        return version;
    }

    private static String requiredText(String value, String field) {
        String normalized = nullableText(value);
        if (normalized == null) {
            throw new InteractionValidationException(field, "Заполните поле");
        }
        return normalized;
    }

    private static String nullableText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private <T> T idempotent(
            CrmProfile profile,
            CommandOperation operation,
            String idempotencyKey,
            Object command,
            Class<T> resultType,
            Supplier<T> action
    ) {
        String key = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        if (!commandIdempotencyRepository.reserve(commandId, profile.id(), operation, key, fingerprint, OffsetDateTime.now())) {
            CommandIdempotencyRepository.CommandRecord stored = commandIdempotencyRepository.find(profile.id(), operation, key)
                    .orElseThrow(() -> new IllegalStateException("Reserved agreement command is unavailable"));
            if (!fingerprint.equals(stored.requestFingerprint())) {
                throw InteractionConflictException.idempotency();
            }
            if (stored.resultJson() == null) {
                throw new IllegalStateException("Reserved agreement command has no result");
            }
            return read(stored.resultJson(), resultType);
        }
        T result = action.get();
        commandIdempotencyRepository.complete(commandId, write(result));
        return result;
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored agreement command result cannot be read", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agreement command result cannot be stored", exception);
        }
    }

    private record Links(List<UUID> interactionIds, List<UUID> attachmentIds) {
    }

    private record UpdateKindCommand(UUID kindId, int version, String name, boolean archived) {
    }

    private record CreateAgreementCommand(UUID organizationId, AgreementValues values) {
    }

    private record UpdateAgreementCommand(UUID agreementId, int version, AgreementValues values) {
    }

    private record CreateActivityCommand(UUID agreementId, ActivityValues values, Links links) {
    }

    private record UpdateActivityCommand(UUID activityId, int version, ActivityValues values, Links links) {
    }

    private record DeleteActivityCommand(UUID activityId, int version) {
    }
}
