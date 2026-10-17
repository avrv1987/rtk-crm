package ru.rtk.crm.agreement;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.AgreementModels.Activity;
import ru.rtk.crm.agreement.AgreementModels.ActivityDeleted;
import ru.rtk.crm.agreement.AgreementModels.ActivityKind;
import ru.rtk.crm.agreement.AgreementModels.ActivityKindCreateRequest;
import ru.rtk.crm.agreement.AgreementModels.ActivityKindUpdateRequest;
import ru.rtk.crm.agreement.AgreementModels.ActivityRequest;
import ru.rtk.crm.agreement.AgreementModels.Agreement;
import ru.rtk.crm.agreement.AgreementModels.AgreementRequest;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.AgreementSummary;
import ru.rtk.crm.agreement.AgreementModels.Confirmation;
import ru.rtk.crm.agreement.AgreementModels.ConfirmationQuery;
import ru.rtk.crm.agreement.AgreementModels.LinkOptions;
import ru.rtk.crm.agreement.AgreementModels.PlanKind;
import ru.rtk.crm.agreement.AgreementModels.Supplement;
import ru.rtk.crm.agreement.AgreementModels.SupplementRequest;
import ru.rtk.crm.agreement.AgreementModels.TransitionOption;
import ru.rtk.crm.agreement.AgreementModels.TransitionRequest;
import ru.rtk.crm.agreement.AgreementModels.WorkAgreements;
import ru.rtk.crm.agreement.AgreementRepository.ActivityRow;
import ru.rtk.crm.agreement.AgreementRepository.ActivityValues;
import ru.rtk.crm.agreement.AgreementRepository.AgreementRow;
import ru.rtk.crm.agreement.AgreementRepository.AgreementValues;
import ru.rtk.crm.agreement.AgreementRepository.ConfirmationRow;
import ru.rtk.crm.agreement.AgreementRepository.SubjectLink;
import ru.rtk.crm.agreement.AgreementRepository.SupplementRow;
import ru.rtk.crm.agreement.AgreementRepository.SupplementValues;
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
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class AgreementService {
    public static final int CONFIRMATION_LIMIT = 500;
    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final OrganizationRepository organizationRepository;
    private final AgreementRepository repository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;
    private final DataSize archiveMaxSize;
    private final AuditJournalRepository auditJournalRepository;
    private final AgreementStages agreementStages;

    public AgreementService(
            OrganizationRepository organizationRepository,
            AgreementRepository repository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper,
            @Value("${app.agreements.archive-max-size}") DataSize archiveMaxSize,
            AuditJournalRepository auditJournalRepository,
            AgreementStages agreementStages
    ) {
        this.organizationRepository = organizationRepository;
        this.repository = repository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
        this.archiveMaxSize = archiveMaxSize;
        this.auditJournalRepository = auditJournalRepository;
        this.agreementStages = agreementStages;
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
                repository.findResponsibles(organization.teamId()),
                repository.findSignerContacts(organizationId),
                repository.findSchoolSigners(organization.teamId()),
                repository.findActiveCatalog(SubjectLink.PROGRAMS),
                repository.findActiveCatalog(SubjectLink.PRODUCTS),
                repository.findActiveCatalog(SubjectLink.DIRECTIONS)
        );
    }

    @Transactional(readOnly = true)
    public Agreement get(CrmProfile profile, UUID agreementId) {
        visibleAgreement(profile, agreementId);
        return repository.findAgreement(agreementId).orElseThrow(AgreementException::agreementNotFound);
    }

    @Transactional(readOnly = true)
    public WorkAgreements workAgreements(CrmProfile profile, UUID interactionId) {
        UUID organizationId = repository.findInteractionOrganization(interactionId).orElseThrow(InteractionNotFoundException::new);
        organizationRepository.findVisibleById(profile, organizationId).orElseThrow(InteractionNotFoundException::new);
        return new WorkAgreements(
                interactionId,
                organizationId,
                agreementStages.signingRule(),
                agreementStages.stages(interactionId),
                repository.findInteractionSummaries(interactionId)
        );
    }

    // Договор создаётся только проектом или сразу подписанным (его вносят в CRM постфактум); любой другой
    // статус достигается переходом по графу, чтобы в истории статусов не появлялось «скачков».
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
        AgreementStatus status = request.status() == null ? AgreementStatus.DRAFT : request.status();
        if (status != AgreementStatus.DRAFT && status != AgreementStatus.ACTIVE) {
            throw new InteractionValidationException("status", "Новый договор создаётся проектом или уже подписанным");
        }
        AgreementValues values = agreementValues(organization, request, null);
        if (status == AgreementStatus.ACTIVE && values.concludedOn() == null) {
            throw new InteractionValidationException("concludedOn", "Укажите дату подписания");
        }
        Links links = agreementLinks(organizationId, request, true);
        UUID fileAttachmentId = fileAttachment(organizationId, request.fileAttachmentId());
        return idempotent(
                profile,
                CommandOperation.CREATE_AGREEMENT,
                idempotencyKey,
                new CreateAgreementCommand(organizationId, status, values, links, fileAttachmentId),
                Agreement.class,
                () -> {
                    UUID id = UUID.randomUUID();
                    OffsetDateTime now = OffsetDateTime.now();
                    try {
                        repository.insertAgreement(id, organizationId, values, status, profile.id(), now);
                    } catch (DuplicateKeyException exception) {
                        throw numberTaken();
                    }
                    // Первое событие истории с from = null фиксирует начальный статус: история статусов полная с самого создания.
                    repository.insertStatusEvent(id, null, status, null, profile.id(), null, now);
                    saveLinks(id, links);
                    if (fileAttachmentId != null) {
                        repository.insertAttachmentVersion(id, fileAttachmentId, profile.id(), now);
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
        // Статус меняется только командой перехода: обычная правка не должна обходить граф статусов и проверки дат.
        if (request.status() != null && request.status() != agreement.status()) {
            throw new InteractionValidationException("status", "Статус договора меняется переходом");
        }
        AgreementValues values = agreementValues(organization, request, agreement);
        // У действующего или завершённого договора дата подписания обязательна: без неё договор не считается
        // подписанным для правила этапа «Подписание» (AgreementStages.requireSignedAgreement).
        if (agreement.status().signed() && agreement.status() != AgreementStatus.TERMINATED && values.concludedOn() == null) {
            throw new InteractionValidationException("concludedOn", "Укажите дату подписания");
        }
        Links links = agreementLinks(agreement.organizationId(), request, false);
        UUID fileAttachmentId = fileAttachment(agreement.organizationId(), request.fileAttachmentId());
        return idempotent(
                profile,
                CommandOperation.UPDATE_AGREEMENT,
                idempotencyKey,
                new UpdateAgreementCommand(agreementId, version, values, links, fileAttachmentId),
                Agreement.class,
                () -> {
                    OffsetDateTime now = OffsetDateTime.now();
                    if (updatedAgreement(agreementId, version, values, now) != 1) {
                        throw AgreementException.agreementVersion(currentAgreementVersion(agreement));
                    }
                    saveLinks(agreementId, links);
                    // Файл из работы добавляется версией договора один раз: повторное сохранение формы с тем же файлом не
                    // плодит версии. Блокировка договора нужна, чтобы два одновременных добавления получили разные номера версий.
                    if (fileAttachmentId != null && !repository.hasAttachmentVersion(agreementId, fileAttachmentId)) {
                        repository.lockAgreement(agreementId);
                        repository.insertAttachmentVersion(agreementId, fileAttachmentId, profile.id(), now);
                    }
                    recordPlanChange(profile, organization, agreementId, values, agreement.plannedKind(), agreement.plannedOn(), requestId);
                    return repository.findAgreement(agreementId).orElseThrow(AgreementException::agreementNotFound);
                }
        );
    }

    // Переход статуса договора идёт по графу AgreementStatus.transitions(): допустимы только перечисленные
    // шаги, возвраты назад требуют причины. Версия и граф проверяются внутри идемпотентного действия, чтобы
    // повтор того же запроса вернул сохранённый результат, а не ошибку «версия устарела».
    @Transactional
    public Agreement transition(CrmProfile profile, UUID agreementId, TransitionRequest request, String idempotencyKey) {
        AgreementRow agreement = visibleAgreement(profile, agreementId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        int version = requiredVersion(request.version());
        if (request.toStatus() == null) {
            throw new InteractionValidationException("toStatus", "Выберите новый статус");
        }
        String comment = nullableText(request.comment());
        TransitionCommand command = new TransitionCommand(agreementId, version, request.toStatus(), comment, request.signedOn(),
                request.validUntil(), request.sentOn(), request.interactionId());
        return idempotent(profile, CommandOperation.TRANSITION_AGREEMENT, idempotencyKey, command, Agreement.class, () -> {
            if (agreement.version() != version) {
                throw AgreementException.agreementVersion(agreement.version());
            }
            TransitionOption option = agreement.status().transitions().stream()
                    .filter(candidate -> candidate.toStatus() == request.toStatus())
                    .findFirst()
                    .orElseThrow(() -> new InteractionValidationException(
                            "toStatus",
                            "Из статуса «" + agreement.status().title() + "» нельзя перейти в «" + request.toStatus().title() + "»"
                    ));
            if (option.commentRequired() && comment == null) {
                throw new InteractionValidationException("comment", "Укажите причину");
            }
            // Переход можно привязать к работе только из числа связанных с договором: событие истории показывается в
            // карточке этой работы, и чужую работу подставить нельзя.
            if (request.interactionId() != null && repository.findLinkedInteractions(agreementId).stream()
                    .noneMatch(work -> work.id().equals(request.interactionId()))) {
                throw new InteractionValidationException("interactionId", "Взаимодействие не связано с этим договором");
            }
            LocalDate today = LocalDate.now(ZONE);
            // Дата отправки при первом переходе «На согласовании» ставится автоматически и потом не перезаписывается:
            // фиксируется момент, когда договор впервые ушёл вузу, а не последняя правка.
            LocalDate sentOn = request.sentOn() != null ? request.sentOn()
                    : request.toStatus() == AgreementStatus.REVIEW && agreement.sentOn() == null ? today : agreement.sentOn();
            LocalDate concludedOn = agreement.concludedOn();
            LocalDate validUntil = request.validUntil() != null ? request.validUntil() : agreement.validUntil();
            if (request.toStatus() == AgreementStatus.ACTIVE) {
                // Дата подписания обязательна при переходе в «Действует»: берётся из запроса, иначе из уже сохранённой
                // (при возврате из «Завершён»), и не может быть в будущем.
                concludedOn = request.signedOn() != null ? request.signedOn() : concludedOn;
                if (concludedOn == null) {
                    throw new InteractionValidationException("signedOn", "Укажите дату подписания");
                }
                if (concludedOn.isAfter(today)) {
                    throw new InteractionValidationException("signedOn", "Дата подписания не может быть позже сегодняшней");
                }
            }
            requireOrder(concludedOn, validUntil, "validUntil", "Срок действия раньше даты подписания");
            if (sentOn != null && sentOn.isAfter(today)) {
                throw new InteractionValidationException("sentOn", "Дата отправки не может быть позже сегодняшней");
            }
            OffsetDateTime now = OffsetDateTime.now();
            // Статус, даты и версия обновляются одним оператором с условием по версии; событие истории пишется в той
            // же транзакции, поэтому статус и его история не расходятся.
            if (repository.updateStatus(agreementId, version, request.toStatus(), concludedOn, validUntil, sentOn, now) != 1) {
                throw AgreementException.agreementVersion(currentAgreementVersion(agreement));
            }
            repository.insertStatusEvent(agreementId, agreement.status(), request.toStatus(), comment, profile.id(),
                    request.interactionId(), now);
            return repository.findAgreement(agreementId).orElseThrow(AgreementException::agreementNotFound);
        });
    }

    @Transactional
    public Supplement createSupplement(CrmProfile profile, UUID agreementId, SupplementRequest request, String idempotencyKey) {
        visibleAgreement(profile, agreementId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        SupplementValues values = supplementValues(request);
        return idempotent(
                profile,
                CommandOperation.CREATE_AGREEMENT_SUPPLEMENT,
                idempotencyKey,
                new CreateSupplementCommand(agreementId, values),
                Supplement.class,
                () -> {
                    UUID id = UUID.randomUUID();
                    try {
                        repository.insertSupplement(id, agreementId, values, profile.id(), OffsetDateTime.now());
                    } catch (DuplicateKeyException exception) {
                        throw supplementNumberTaken();
                    }
                    return repository.findSupplement(id).orElseThrow(AgreementException::supplementNotFound);
                }
        );
    }

    @Transactional
    public Supplement updateSupplement(CrmProfile profile, UUID supplementId, SupplementRequest request, String idempotencyKey) {
        SupplementRow supplement = visibleSupplement(profile, supplementId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        int version = requiredVersion(request.version());
        SupplementValues values = supplementValues(request);
        return idempotent(
                profile,
                CommandOperation.UPDATE_AGREEMENT_SUPPLEMENT,
                idempotencyKey,
                new UpdateSupplementCommand(supplementId, version, values),
                Supplement.class,
                () -> {
                    int updated;
                    try {
                        updated = repository.updateSupplement(supplementId, version, values, OffsetDateTime.now());
                    } catch (DuplicateKeyException exception) {
                        throw supplementNumberTaken();
                    }
                    if (updated != 1) {
                        throw AgreementException.supplementVersion(repository.findSupplementRow(supplementId)
                                .map(SupplementRow::version)
                                .orElse(supplement.version()));
                    }
                    return repository.findSupplement(supplementId).orElseThrow(AgreementException::supplementNotFound);
                }
        );
    }

    @Transactional
    public Activity createActivity(CrmProfile profile, UUID agreementId, ActivityRequest request, String idempotencyKey) {
        AgreementRow agreement = visibleAgreement(profile, agreementId);
        Organization organization = visibleOrganization(profile, agreement.organizationId());
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        ActivityValues values = activityValues(organization, request, null, null);
        ActivityLinks links = activityLinks(agreement.organizationId(), request);
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
        ActivityLinks links = activityLinks(activity.organizationId(), request);
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

    public static void requireRegistryReader(CrmProfile profile) {
        if (profile.role() != UserRole.USER && profile.role() != UserRole.LEADER && profile.role() != UserRole.MANAGEMENT) {
            throw AgreementException.forbidden();
        }
    }

    AgreementRow visibleAgreement(CrmProfile profile, UUID agreementId) {
        AgreementRow agreement = repository.findAgreementRow(agreementId).orElseThrow(AgreementException::agreementNotFound);
        organizationRepository.findVisibleById(profile, agreement.organizationId())
                .orElseThrow(AgreementException::agreementNotFound);
        return agreement;
    }

    SupplementRow visibleSupplement(CrmProfile profile, UUID supplementId) {
        SupplementRow supplement = repository.findSupplementRow(supplementId).orElseThrow(AgreementException::supplementNotFound);
        organizationRepository.findVisibleById(profile, supplement.organizationId())
                .orElseThrow(AgreementException::supplementNotFound);
        return supplement;
    }

    private int updatedKind(UUID kindId, int version, String name, boolean archived) {
        try {
            return repository.updateKind(kindId, version, name, archived, OffsetDateTime.now());
        } catch (DuplicateKeyException exception) {
            throw kindNameTaken();
        }
    }

    private int updatedAgreement(UUID agreementId, int version, AgreementValues values, OffsetDateTime now) {
        try {
            return repository.updateAgreement(agreementId, version, values, now);
        } catch (DuplicateKeyException exception) {
            throw numberTaken();
        }
    }

    private int currentAgreementVersion(AgreementRow agreement) {
        return repository.findAgreementRow(agreement.id()).map(AgreementRow::version).orElse(agreement.version());
    }

    private int currentActivityVersion(ActivityRow activity) {
        return repository.findActivityRow(activity.id()).map(ActivityRow::version).orElseThrow(AgreementException::activityNotFound);
    }

    private Organization visibleOrganization(CrmProfile profile, UUID organizationId) {
        return organizationRepository.findVisibleById(profile, organizationId).orElseThrow(OrganizationNotFoundException::new);
    }

    private ActivityRow visibleActivity(CrmProfile profile, UUID activityId) {
        ActivityRow activity = repository.findActivityRow(activityId).orElseThrow(AgreementException::activityNotFound);
        organizationRepository.findVisibleById(profile, activity.organizationId())
                .orElseThrow(AgreementException::activityNotFound);
        return activity;
    }

    private AgreementValues agreementValues(Organization organization, AgreementRequest request, AgreementRow current) {
        requireOrder(request.concludedOn(), request.validUntil(), "validUntil", "Срок действия раньше даты подписания");
        // План подписания или продления — пара «вид + дата»; в БД это же гарантирует ограничение agreements_plan_pair.
        if ((request.plannedKind() == null) != (request.plannedOn() == null)) {
            throw new InteractionValidationException(
                    request.plannedKind() == null ? "plannedKind" : "plannedOn",
                    "Укажите вид и дату плана вместе или очистите оба поля"
            );
        }
        if (request.plannedKind() == PlanKind.RENEWAL) {
            requireOrder(request.concludedOn(), request.plannedOn(), "plannedOn", "Плановое продление раньше даты подписания");
        }
        UUID universitySigner = request.universitySignerContactId();
        if (universitySigner != null
                // Уже выбранного подписанта проверка не затрагивает: контакт мог стать неактуальным позже, и правка
                // договора не должна из-за этого ломаться. Новый выбор допускается только из действующих.
                && !(current != null && universitySigner.equals(current.universitySignerId()))
                && !repository.isActiveContact(organization.id(), universitySigner)) {
            throw new InteractionValidationException("universitySignerContactId", "Подписантом вуза может быть действующий контакт этого вуза");
        }
        UUID schoolSigner = request.schoolSignerProfileId();
        if (schoolSigner != null
                && !(current != null && schoolSigner.equals(current.schoolSignerId()))
                && repository.findSchoolSigners(organization.teamId()).stream().noneMatch(signer -> signer.id().equals(schoolSigner))) {
            throw new InteractionValidationException(
                    "schoolSignerProfileId",
                    "Подписантом школы может быть активный сотрудник команды вуза или «Руководства»"
            );
        }
        return new AgreementValues(
                requiredText(request.number(), "number"),
                request.concludedOn(),
                request.validUntil(),
                request.sentOn(),
                nullableText(request.parties()),
                request.plannedKind(),
                request.plannedOn(),
                universitySigner,
                schoolSigner
        );
    }

    private Links agreementLinks(UUID organizationId, AgreementRequest request, boolean creating) {
        List<UUID> interactionIds = optionalIds(request.interactionIds(), "interactionIds", creating);
        if (interactionIds != null
                && repository.findOrganizationInteractionIds(organizationId, interactionIds).size() != interactionIds.size()) {
            throw new InteractionValidationException("interactionIds", "Можно связать только взаимодействия этого вуза");
        }
        return new Links(
                interactionIds,
                subject(SubjectLink.PROGRAMS, request.programIds(), "programIds", creating),
                subject(SubjectLink.PRODUCTS, request.productIds(), "productIds", creating),
                subject(SubjectLink.DIRECTIONS, request.directionIds(), "directionIds", creating)
        );
    }

    private List<UUID> subject(SubjectLink link, List<UUID> requested, String field, boolean creating) {
        List<UUID> ids = optionalIds(requested, field, creating);
        if (ids != null && repository.findExisting(link, ids).size() != ids.size()) {
            throw new InteractionValidationException(field, "Значение не найдено в каталоге");
        }
        return ids;
    }

    private void saveLinks(UUID agreementId, Links links) {
        if (links.interactionIds() != null) {
            repository.replaceInteractions(agreementId, links.interactionIds());
        }
        if (links.programIds() != null) {
            repository.replaceLinks(agreementId, SubjectLink.PROGRAMS, links.programIds());
        }
        if (links.productIds() != null) {
            repository.replaceLinks(agreementId, SubjectLink.PRODUCTS, links.productIds());
        }
        if (links.directionIds() != null) {
            repository.replaceLinks(agreementId, SubjectLink.DIRECTIONS, links.directionIds());
        }
    }

    private UUID fileAttachment(UUID organizationId, UUID attachmentId) {
        if (attachmentId != null && repository.findOrganizationCleanAttachmentIds(organizationId, List.of(attachmentId)).isEmpty()) {
            throw new InteractionValidationException(
                    "fileAttachmentId",
                    "Файл договора должен быть проверенным документом из взаимодействий этого вуза"
            );
        }
        return attachmentId;
    }

    private static SupplementValues supplementValues(SupplementRequest request) {
        if (request.status() == null) {
            throw new InteractionValidationException("status", "Укажите статус допсоглашения");
        }
        return new SupplementValues(
                requiredText(request.number(), "number"),
                request.concludedOn(),
                nullableText(request.subject()),
                request.status()
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
        // В журнал пишется только фактическое изменение плана, а не каждое сохранение карточки договора.
        if (previousKind == values.plannedKind() && Objects.equals(previousOn, values.plannedOn())) {
            return;
        }
        auditJournalRepository.record(AuditAction.AGREEMENT_PLAN_CHANGED, profile.id(), "AGREEMENT", agreementId,
                organization.name() + ", договор № " + values.number(),
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

    private ActivityLinks activityLinks(UUID organizationId, ActivityRequest request) {
        List<UUID> interactionIds = uniqueIds(request.interactionIds(), "interactionIds");
        List<UUID> attachmentIds = uniqueIds(request.attachmentIds(), "attachmentIds");
        Set<UUID> knownInteractions = repository.findOrganizationInteractionIds(organizationId, interactionIds);
        if (knownInteractions.size() != interactionIds.size()) {
            throw new InteractionValidationException("interactionIds", "Можно связать только взаимодействия этого вуза");
        }
        Set<UUID> knownAttachments = repository.findOrganizationCleanAttachmentIds(organizationId, attachmentIds);
        if (knownAttachments.size() != attachmentIds.size()) {
            throw new InteractionValidationException(
                    "attachmentIds",
                    "Подтверждением может быть только проверенный документ из взаимодействий этого вуза"
            );
        }
        return new ActivityLinks(interactionIds, attachmentIds);
    }

    private static List<UUID> optionalIds(List<UUID> values, String field, boolean creating) {
        if (values == null) {
            return creating ? List.of() : null;
        }
        return uniqueIds(values, field);
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
        return new InteractionValidationException("number", "У этого вуза уже есть договор с таким номером");
    }

    private static InteractionValidationException supplementNumberTaken() {
        return new InteractionValidationException("number", "У договора уже есть допсоглашение с таким номером");
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

    static String nullableText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // Общая обёртка идемпотентных команд договора: резерв ключа и отпечаток запроса, повтор отдаёт сохранённый
    // результат, другое тело под тем же ключом — конфликт. Результат дописывается к резерву после выполнения.
    <T> T idempotent(
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

    private record Links(List<UUID> interactionIds, List<UUID> programIds, List<UUID> productIds, List<UUID> directionIds) {
    }

    private record ActivityLinks(List<UUID> interactionIds, List<UUID> attachmentIds) {
    }

    private record UpdateKindCommand(UUID kindId, int version, String name, boolean archived) {
    }

    private record CreateAgreementCommand(
            UUID organizationId,
            AgreementStatus status,
            AgreementValues values,
            Links links,
            UUID fileAttachmentId
    ) {
    }

    private record UpdateAgreementCommand(UUID agreementId, int version, AgreementValues values, Links links, UUID fileAttachmentId) {
    }

    private record TransitionCommand(
            UUID agreementId,
            int version,
            AgreementStatus toStatus,
            String comment,
            LocalDate signedOn,
            LocalDate validUntil,
            LocalDate sentOn,
            UUID interactionId
    ) {
    }

    private record CreateSupplementCommand(UUID agreementId, SupplementValues values) {
    }

    private record UpdateSupplementCommand(UUID supplementId, int version, SupplementValues values) {
    }

    private record CreateActivityCommand(UUID agreementId, ActivityValues values, ActivityLinks links) {
    }

    private record UpdateActivityCommand(UUID activityId, int version, ActivityValues values, ActivityLinks links) {
    }

    private record DeleteActivityCommand(UUID activityId, int version) {
    }
}
