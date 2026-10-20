package ru.rtk.crm.partner;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.attachment.Attachment;
import ru.rtk.crm.attachment.AttachmentService;
import ru.rtk.crm.attachment.AttachmentStatus;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.ContactNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationStatus;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.web.InputRules;
import ru.rtk.crm.mail.MailNotifications;
import ru.rtk.crm.partner.PartnerAccessRepository.ManagedOrganization;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerMaterialReceipt;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerReceipt;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestCancel;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestClarify;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestCreate;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestForPartner;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestMaterial;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestResponse;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestReview;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestStatus;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestView;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerResponseDecision;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerResponseKind;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerReviewItem;
import ru.rtk.crm.partner.PartnerRequestRepository.MaterialRow;
import ru.rtk.crm.partner.PartnerRequestRepository.RequestRow;
import ru.rtk.crm.partner.PartnerRequestRepository.ResponseRow;

@Service
public class PartnerRequestService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PartnerRequestService.class);
    static final String OBJECT_TYPE = "PARTNER_REQUEST";
    private static final int ACTION_LIMIT = 300;
    private static final int TEXT_LIMIT = 1000;
    private static final int MESSAGE_LIMIT = 4000;
    private static final int CLARIFICATION_LIMIT = 2000;
    private static final int REVIEW_LIMIT = 50;
    // Пока школа не уточнила, координатор может и ответить сам: вопрос не должен запирать уже готовый ответ.
    private static final Set<PartnerRequestStatus> ANSWERABLE = Set.of(
            PartnerRequestStatus.AWAITING_RESPONSE, PartnerRequestStatus.RETURNED, PartnerRequestStatus.CLARIFICATION_NEEDED);
    private static final Set<PartnerRequestStatus> CLOSED = Set.of(PartnerRequestStatus.ACCEPTED, PartnerRequestStatus.CANCELLED);

    private final PartnerRequestRepository repository;
    private final PartnerAccessRepository partnerAccessRepository;
    private final PartnerCabinetRepository partnerCabinetRepository;
    private final InteractionRepository interactionRepository;
    private final OrganizationRepository organizationRepository;
    private final AttachmentService attachmentService;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final AuditJournalRepository auditJournalRepository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<MailNotifications> mailNotifications;
    private final TransactionTemplate mailTransaction;

    public PartnerRequestService(
            PartnerRequestRepository repository,
            PartnerAccessRepository partnerAccessRepository,
            PartnerCabinetRepository partnerCabinetRepository,
            InteractionRepository interactionRepository,
            OrganizationRepository organizationRepository,
            AttachmentService attachmentService,
            CommandIdempotencyRepository commandIdempotencyRepository,
            AuditJournalRepository auditJournalRepository,
            ObjectMapper objectMapper,
            ObjectProvider<MailNotifications> mailNotifications,
            PlatformTransactionManager transactionManager
    ) {
        this.mailNotifications = mailNotifications;
        this.mailTransaction = new TransactionTemplate(transactionManager);
        this.mailTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.repository = repository;
        this.partnerAccessRepository = partnerAccessRepository;
        this.partnerCabinetRepository = partnerCabinetRepository;
        this.interactionRepository = interactionRepository;
        this.organizationRepository = organizationRepository;
        this.attachmentService = attachmentService;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.auditJournalRepository = auditJournalRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<PartnerRequestView> list(CrmProfile actor, UUID interactionId) {
        requireVisibleInteraction(actor, interactionId);
        return views(repository.findByInteraction(interactionId));
    }

    @Transactional
    public PartnerRequestView create(CrmProfile actor, UUID interactionId, PartnerRequestCreate request, String idempotencyKey,
                                     String requestId) {
        UUID organizationId = requireVisibleInteraction(actor, interactionId);
        ManagedOrganization organization = requireManageable(actor, organizationId);
        if (request == null) {
            throw new InteractionValidationException("action", "Опишите, что нужно от вуза");
        }
        String action = required(request.action(), "action", ACTION_LIMIT, "Опишите, что нужно от вуза");
        String expected = required(request.expectedResult(), "expectedResult", TEXT_LIMIT, "Опишите ожидаемый результат");
        if (request.dueOn() != null && request.dueOn().isBefore(LocalDate.now(PartnerCabinetService.MOSCOW))) {
            throw new InteractionValidationException("dueOn", "Срок ответа уже прошёл");
        }
        InputRules.requireDateWithin(request.dueOn(), "dueOn", LocalDate.now(PartnerCabinetService.MOSCOW), 0, 5);
        if (request.contactId() == null) {
            throw new InteractionValidationException("contactId", "Выберите координатора вуза");
        }
        List<UUID> materials = request.materialIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(request.materialIds()));
        String fingerprint = CommandFingerprint.of(objectMapper, Arrays.asList(interactionId, request.contactId(), action, expected,
                request.dueOn(), materials));
        Reservation reservation = reserve(actor.id(), CommandOperation.CREATE_PARTNER_REQUEST, idempotencyKey, fingerprint);
        if (reservation.repeated()) {
            return read(reservation.previousResult(), PartnerRequestView.class);
        }
        if (organization.status() != OrganizationStatus.ACTIVE) {
            throw new InteractionValidationException("contactId", "Запрос отправляют только действующему вузу");
        }
        partnerAccessRepository.lockContact(organizationId, request.contactId()).orElseThrow(ContactNotFoundException::new);
        if (!repository.hasActivePartnerAccess(organizationId, request.contactId())) {
            throw new InteractionValidationException("contactId",
                    "У контакта нет действующего доступа в кабинет вуза: сначала откройте доступ на вкладке «Контакты»");
        }
        List<PartnerRequestRepository.AttachmentRow> found = repository.findAttachments(interactionId, materials);
        if (found.size() != materials.size() || found.stream().anyMatch(row -> !row.partnerVisible() || row.deleted()
                || row.status() == AttachmentStatus.REJECTED)) {
            throw new InteractionValidationException("materialIds",
                    "К запросу прикладывают только документы этого взаимодействия, открытые вузу");
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        repository.insert(new RequestRow(id, interactionId, organizationId, request.contactId(), null, null, action, expected,
                request.dueOn(), PartnerRequestStatus.AWAITING_RESPONSE, null, null, null, null, now, now, 0), actor.id());
        materials.forEach(material -> repository.insertMaterial(id, material));
        auditJournalRepository.record(AuditAction.PARTNER_REQUEST_CREATED, actor.id(), OBJECT_TYPE, id, organization.name(),
                "запрос вузу; материалов: " + materials.size(), requestId);
        notifyCoordinator(organizationId, request.contactId(), id, CoordinatorLetter.REQUEST);
        PartnerRequestView view = view(id);
        complete(reservation, view);
        return view;
    }

    // Решение КАМ относится к последнему ответу вуза. Внутренний этап маршрута при этом не меняется: принятие
    // результата и движение процесса — разные решения, второе КАМ делает отдельно в карточке.
    @Transactional
    public PartnerRequestView review(CrmProfile actor, UUID requestId, PartnerRequestReview request, String idempotencyKey,
                                     String auditRequestId) {
        RequestRow current = requireStaffRequest(actor, requestId, true);
        if (request == null || request.decision() == null) {
            throw new InteractionValidationException("decision", "Выберите: принять ответ или вернуть на доработку");
        }
        int version = requiredVersion(request.version());
        String reason = request.decision() == PartnerResponseDecision.RETURNED
                ? required(request.reason(), "reason", TEXT_LIMIT, "Укажите причину возврата: вуз увидит её в кабинете")
                : optional(request.reason(), "reason", TEXT_LIMIT);
        Reservation reservation = reserve(actor.id(), CommandOperation.REVIEW_PARTNER_RESPONSE, idempotencyKey,
                CommandFingerprint.of(objectMapper, Arrays.asList(requestId, version, request.decision(), reason)));
        if (reservation.repeated()) {
            return read(reservation.previousResult(), PartnerRequestView.class);
        }
        repository.lock(requestId);
        current = repository.find(requestId).orElseThrow(PartnerException::requestNotFound);
        if (current.version() != version) {
            throw InteractionConflictException.partnerRequestVersion(current.version());
        }
        if (current.status() != PartnerRequestStatus.RESPONDED) {
            throw InteractionConflictException.stateConflict("Проверить можно только переданный вузом ответ");
        }
        ResponseRow answer = repository.findResponses(List.of(requestId)).stream()
                .filter(response -> response.kind() == PartnerResponseKind.ANSWER)
                .findFirst()
                .orElseThrow(() -> InteractionConflictException.stateConflict("У запроса нет ответа вуза"));
        // Принять ответ с файлом можно только после антивирусной проверки: непроверенный файл не становится
        // результатом, даже если КАМ видит текст ответа.
        if (request.decision() == PartnerResponseDecision.ACCEPTED && answer.attachmentId() != null
                && answer.fileStatus() != AttachmentStatus.CLEAN) {
            throw InteractionConflictException.stateConflict(answer.fileStatus() == AttachmentStatus.REJECTED
                    ? "Файл ответа отклонён антивирусом: верните ответ на доработку"
                    : "Файл ответа ещё проверяется антивирусом: принять его можно после проверки");
        }
        OffsetDateTime now = OffsetDateTime.now();
        PartnerRequestStatus next = request.decision() == PartnerResponseDecision.ACCEPTED
                ? PartnerRequestStatus.ACCEPTED : PartnerRequestStatus.RETURNED;
        if (!repository.changeStatus(requestId, version, next, texts("returnReason", reason), now)) {
            throw InteractionConflictException.partnerRequestVersion(current.version());
        }
        repository.decide(answer.id(), request.decision(), reason, actor.id(), now);
        auditJournalRepository.record(AuditAction.PARTNER_RESPONSE_REVIEWED, actor.id(), OBJECT_TYPE, requestId, current.workTitle(),
                (next == PartnerRequestStatus.ACCEPTED ? "принят" : "возвращён на доработку") + "; квитанция " + answer.receiptNumber(),
                auditRequestId);
        notifyCoordinator(current.organizationId(), current.contactId(), answer.id(),
                next == PartnerRequestStatus.RETURNED ? CoordinatorLetter.RETURNED : CoordinatorLetter.ACCEPTED);
        PartnerRequestView view = view(requestId);
        complete(reservation, view);
        return view;
    }

    @Transactional
    public PartnerRequestView clarify(CrmProfile actor, UUID requestId, PartnerRequestClarify request, String idempotencyKey,
                                      String auditRequestId) {
        requireStaffRequest(actor, requestId, true);
        if (request == null) {
            throw new InteractionValidationException("text", "Напишите уточнение для вуза");
        }
        int version = requiredVersion(request.version());
        String text = required(request.text(), "text", CLARIFICATION_LIMIT, "Напишите уточнение для вуза");
        Reservation reservation = reserve(actor.id(), CommandOperation.CLARIFY_PARTNER_REQUEST, idempotencyKey,
                CommandFingerprint.of(objectMapper, Arrays.asList(requestId, version, text)));
        if (reservation.repeated()) {
            return read(reservation.previousResult(), PartnerRequestView.class);
        }
        RequestRow current = lockedVersion(requestId, version);
        if (current.status() != PartnerRequestStatus.CLARIFICATION_NEEDED) {
            throw InteractionConflictException.stateConflict("Уточнение отправляют, когда вуз задал вопрос по запросу");
        }
        if (!repository.changeStatus(requestId, version, PartnerRequestStatus.AWAITING_RESPONSE, texts("clarification", text),
                OffsetDateTime.now())) {
            throw InteractionConflictException.partnerRequestVersion(current.version());
        }
        auditJournalRepository.record(AuditAction.PARTNER_REQUEST_CHANGED, actor.id(), OBJECT_TYPE, requestId, current.workTitle(),
                "уточнение отправлено вузу", auditRequestId);
        PartnerRequestView view = view(requestId);
        complete(reservation, view);
        return view;
    }

    @Transactional
    public PartnerRequestView cancel(CrmProfile actor, UUID requestId, PartnerRequestCancel request, String idempotencyKey,
                                     String auditRequestId) {
        requireStaffRequest(actor, requestId, true);
        if (request == null) {
            throw new InteractionValidationException("version", "Укажите версию запроса");
        }
        int version = requiredVersion(request.version());
        String reason = optional(request.reason(), "reason", TEXT_LIMIT);
        Reservation reservation = reserve(actor.id(), CommandOperation.CANCEL_PARTNER_REQUEST, idempotencyKey,
                CommandFingerprint.of(objectMapper, Arrays.asList(requestId, version, reason)));
        if (reservation.repeated()) {
            return read(reservation.previousResult(), PartnerRequestView.class);
        }
        RequestRow current = lockedVersion(requestId, version);
        if (CLOSED.contains(current.status())) {
            throw InteractionConflictException.stateConflict("Запрос уже закрыт");
        }
        if (!repository.changeStatus(requestId, version, PartnerRequestStatus.CANCELLED, texts("cancelReason", reason),
                OffsetDateTime.now())) {
            throw InteractionConflictException.partnerRequestVersion(current.version());
        }
        auditJournalRepository.record(AuditAction.PARTNER_REQUEST_CHANGED, actor.id(), OBJECT_TYPE, requestId, current.workTitle(),
                "запрос отменён", auditRequestId);
        PartnerRequestView view = view(requestId);
        complete(reservation, view);
        return view;
    }

    @Transactional(readOnly = true)
    public List<PartnerReviewItem> awaitingReview(CrmProfile actor) {
        if (actor.role() != UserRole.USER && actor.role() != UserRole.LEADER) {
            return List.of();
        }
        return organizationRepository.visibilityScope(actor)
                .map(scope -> repository.findAwaitingReview(scope.condition(), scope.parameters(), REVIEW_LIMIT))
                .orElse(List.of());
    }

    List<PartnerRequestForPartner> forPartner(UUID organizationId, UUID contactId, UUID partnerProfileId) {
        List<RequestRow> requests = repository.findForContact(organizationId, contactId);
        List<UUID> ids = requests.stream().map(RequestRow::id).toList();
        Map<UUID, List<PartnerRequestMaterial>> materials = materials(ids);
        Map<UUID, List<PartnerReceipt>> responses = repository.findResponses(ids).stream()
                .collect(Collectors.groupingBy(ResponseRow::requestId, Collectors.mapping(response -> new PartnerReceipt(
                        response.id(),
                        response.receiptNumber(),
                        response.kind(),
                        response.message(),
                        response.fileName(),
                        PartnerRequestModels.fileState(response.fileStatus()),
                        response.submittedAt(),
                        response.decision(),
                        response.decisionReason(),
                        partnerProfileId.equals(response.submittedBy()) && response.fileStatus() == AttachmentStatus.CLEAN
                                && !response.fileDeleted()
                ), Collectors.toList())));
        return requests.stream()
                .map(request -> new PartnerRequestForPartner(
                        request.id(),
                        request.interactionId(),
                        request.workTitle(),
                        request.action(),
                        request.expectedResult(),
                        request.dueOn(),
                        request.status(),
                        request.status() == PartnerRequestStatus.RETURNED ? request.returnReason() : null,
                        request.clarification(),
                        materials.getOrDefault(request.id(), List.of()),
                        responses.getOrDefault(request.id(), List.of()),
                        request.createdAt(),
                        request.version()
                ))
                .toList();
    }

    // Ответ координатора: квитанция с номером и временем. Повтор с тем же ключом после обрыва связи возвращает
    // ту же квитанцию и не создаёт второй ответ и второй файл. Строка запроса блокируется до записи файла: антивирус
    // может идти долго, но держит только этот запрос, а не карточку взаимодействия, и параллельная отмена КАМ
    // не проскочит между проверкой статуса и записью ответа.
    @Transactional
    public PartnerReceipt submit(
            CrmProfile partner,
            UUID requestId,
            PartnerResponseKind kind,
            Integer version,
            String message,
            MultipartFile file,
            String idempotencyKey,
            String auditRequestId
    ) {
        PartnerScope scope = requirePartner(partner);
        PartnerResponseKind responseKind = kind == null ? PartnerResponseKind.ANSWER : kind;
        String text = optional(message, "message", MESSAGE_LIMIT);
        boolean hasFile = file != null && !file.isEmpty();
        if (responseKind == PartnerResponseKind.QUESTION && (text == null || hasFile)) {
            throw new InteractionValidationException("message", "Вопрос по запросу — это текст без файла");
        }
        if (text == null && !hasFile) {
            throw new InteractionValidationException("message", "Напишите ответ или приложите файл");
        }
        int expectedVersion = requiredVersion(version);
        String fingerprint = CommandFingerprint.of(objectMapper, Arrays.asList(requestId, responseKind, expectedVersion, text,
                hasFile ? file.getOriginalFilename() : null, hasFile ? checksum(file) : null));
        Reservation reservation = reserve(partner.id(), CommandOperation.SUBMIT_PARTNER_RESPONSE, idempotencyKey, fingerprint);
        if (reservation.repeated()) {
            return read(reservation.previousResult(), PartnerReceipt.class);
        }
        repository.lock(requestId).orElseThrow(PartnerException::requestNotFound);
        RequestRow request = repository.find(requestId)
                .filter(row -> row.organizationId().equals(scope.organizationId()) && row.contactId().equals(scope.contactId()))
                .orElseThrow(PartnerException::requestNotFound);
        if (request.version() != expectedVersion) {
            throw InteractionConflictException.partnerRequestVersion(request.version());
        }
        if (!ANSWERABLE.contains(request.status())) {
            throw InteractionConflictException.stateConflict(request.status() == PartnerRequestStatus.RESPONDED
                    ? "Ответ уже передан и ждёт проверки школой"
                    : "Запрос закрыт: ответить на него уже нельзя");
        }
        OffsetDateTime now = OffsetDateTime.now();
        Attachment attachment = null;
        if (hasFile) {
            UUID stageId = repository.findCurrentStage(request.interactionId()).orElseThrow(PartnerException::requestNotFound);
            attachment = attachmentService.storeIncoming(request.interactionId(), stageId, file, partner.id(), now);
        }
        UUID responseId = UUID.randomUUID();
        String receipt = receiptNumber(responseId);
        repository.insertResponse(responseId, requestId, receipt, responseKind, text, attachment == null ? null : attachment.id(),
                partner.id(), now);
        PartnerRequestStatus next = responseKind == PartnerResponseKind.ANSWER
                ? PartnerRequestStatus.RESPONDED : PartnerRequestStatus.CLARIFICATION_NEEDED;
        repository.changeStatus(requestId, expectedVersion, next, Map.of(), now);
        auditJournalRepository.record(AuditAction.PARTNER_RESPONSE_SUBMITTED, partner.id(), OBJECT_TYPE, requestId, request.workTitle(),
                (responseKind == PartnerResponseKind.ANSWER ? "ответ" : "вопрос") + "; квитанция " + receipt
                        + (attachment == null ? "" : "; файл проверен: " + attachment.status().name().toLowerCase(Locale.ROOT)),
                auditRequestId);
        UUID organizationId = request.organizationId();
        UUID interactionId = request.interactionId();
        afterCommit(mail -> repository.findMailTarget(organizationId, scope.contactId())
                .filter(target -> target.ownerManagerId() != null)
                .ifPresent(target -> mail.partnerReply(target.ownerManagerId(), organizationId, interactionId,
                        target.organizationName(), responseId)));
        PartnerReceipt result = new PartnerReceipt(responseId, receipt, responseKind, text,
                attachment == null ? null : attachment.originalName(),
                attachment == null ? null : PartnerRequestModels.fileState(attachment.status()), now, null, null,
                attachment != null && attachment.status() == AttachmentStatus.CLEAN);
        complete(reservation, result);
        return result;
    }

    // Подтверждение относится к конкретной версии файла (у каждой версии свой идентификатор): новая версия
    // документа приходит неподтверждённой. Скачивание не считается подтверждением.
    @Transactional
    public PartnerMaterialReceipt confirmReceived(CrmProfile partner, UUID attachmentId, String auditRequestId) {
        PartnerScope scope = requirePartner(partner);
        partnerCabinetRepository.findDownloadable(scope.organizationId(), attachmentId).orElseThrow(PartnerException::documentNotFound);
        if (repository.findReceipt(attachmentId, partner.id()).isEmpty()) {
            repository.insertReceipt(attachmentId, partner.id(), OffsetDateTime.now());
            auditJournalRepository.record(AuditAction.PARTNER_MATERIAL_RECEIVED, partner.id(), "ATTACHMENT", attachmentId, null,
                    "получение подтверждено", auditRequestId);
        }
        return new PartnerMaterialReceipt(attachmentId, repository.findReceipt(attachmentId, partner.id()).orElseThrow());
    }

    private enum CoordinatorLetter { REQUEST, RETURNED, ACCEPTED }

    private void notifyCoordinator(UUID organizationId, UUID contactId, UUID eventId, CoordinatorLetter letter) {
        afterCommit(mail -> repository.findMailTarget(organizationId, contactId)
                .filter(target -> target.coordinatorProfileId() != null)
                .ifPresent(target -> {
                    UUID profile = target.coordinatorProfileId();
                    switch (letter) {
                        case REQUEST -> mail.partnerRequest(profile, organizationId, target.organizationName(), eventId);
                        case RETURNED -> mail.partnerReturned(profile, organizationId, target.organizationName(), eventId);
                        case ACCEPTED -> mail.partnerAccepted(profile, organizationId, target.organizationName(), eventId);
                    }
                }));
    }

    // Письмо ставится только после коммита: откат не должен оставить письмо о несуществующем запросе. Очередь пишется
    // в отдельной транзакции, потому что после коммита соединение основной уже не фиксирует изменения, а сбой почты
    // не отменяет сохранённое действие. Без модуля почты бина нет, и ничего не регистрируется.
    void afterCommit(Consumer<MailNotifications> letter) {
        MailNotifications mail = mailNotifications.getIfAvailable();
        if (mail == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mailTransaction.executeWithoutResult(status -> letter.accept(mail));
                } catch (RuntimeException exception) {
                    LOGGER.warn("Partner request mail was not queued", exception);
                }
            }
        });
    }

    Optional<PartnerCabinetRepository.DownloadRow> ownResponseFile(UUID responseId, UUID profileId, UUID organizationId, UUID contactId) {
        return repository.findOwnResponseFile(responseId, profileId, organizationId, contactId);
    }

    Map<UUID, OffsetDateTime> receipts(UUID profileId) {
        return repository.findReceipts(profileId);
    }

    private PartnerScope requirePartner(CrmProfile partner) {
        return partnerCabinetRepository.findScope(partner.id())
                .map(scope -> new PartnerScope(scope.organizationId(), scope.contactId()))
                .orElseThrow(PartnerException::accessClosed);
    }

    private UUID requireVisibleInteraction(CrmProfile actor, UUID interactionId) {
        if (interactionId == null) {
            throw new InteractionValidationException("id", "Укажите взаимодействие");
        }
        UUID organizationId = interactionRepository.findOrganizationIdById(interactionId).orElseThrow(InteractionNotFoundException::new);
        organizationRepository.findVisibleById(actor, organizationId).orElseThrow(InteractionNotFoundException::new);
        return organizationId;
    }

    // Запросы вузу ведут КАМ этого вуза и руководитель его команды. Администратор и «Руководство» видят их только
    // через карточку, если она им видна.
    private ManagedOrganization requireManageable(CrmProfile actor, UUID organizationId) {
        ManagedOrganization organization = partnerAccessRepository.findOrganization(organizationId)
                .orElseThrow(InteractionNotFoundException::new);
        boolean allowed = switch (actor.role()) {
            case LEADER -> Objects.equals(actor.teamId(), organization.teamId());
            case USER -> Objects.equals(actor.teamId(), organization.teamId()) && actor.id().equals(organization.ownerManagerId());
            case ADMIN, MANAGEMENT, PARTNER -> false;
        };
        if (!allowed) {
            throw PartnerException.requestForbidden();
        }
        return organization;
    }

    private RequestRow requireStaffRequest(CrmProfile actor, UUID requestId, boolean manage) {
        RequestRow request = repository.find(requestId).orElseThrow(PartnerException::requestNotFound);
        UUID organizationId = requireVisibleInteraction(actor, request.interactionId());
        if (manage) {
            requireManageable(actor, organizationId);
        }
        return request;
    }

    private RequestRow lockedVersion(UUID requestId, int version) {
        repository.lock(requestId);
        RequestRow current = repository.find(requestId).orElseThrow(PartnerException::requestNotFound);
        if (current.version() != version) {
            throw InteractionConflictException.partnerRequestVersion(current.version());
        }
        return current;
    }

    private PartnerRequestView view(UUID requestId) {
        return views(List.of(repository.find(requestId).orElseThrow(PartnerException::requestNotFound))).getFirst();
    }

    private List<PartnerRequestView> views(List<RequestRow> requests) {
        List<UUID> ids = requests.stream().map(RequestRow::id).toList();
        Map<UUID, List<PartnerRequestMaterial>> materials = materials(ids);
        Map<UUID, List<PartnerRequestResponse>> responses = repository.findResponses(ids).stream()
                .collect(Collectors.groupingBy(ResponseRow::requestId, Collectors.mapping(response -> new PartnerRequestResponse(
                        response.id(),
                        response.receiptNumber(),
                        response.kind(),
                        response.message(),
                        response.attachmentId(),
                        response.fileName(),
                        PartnerRequestModels.fileState(response.fileStatus()),
                        response.submittedAt(),
                        response.submittedByName(),
                        response.decision(),
                        response.decisionReason(),
                        response.decidedAt()
                ), Collectors.toList())));
        List<PartnerRequestView> views = new ArrayList<>();
        for (RequestRow request : requests) {
            views.add(new PartnerRequestView(
                    request.id(),
                    request.interactionId(),
                    request.contactId(),
                    request.contactName(),
                    request.action(),
                    request.expectedResult(),
                    request.dueOn(),
                    request.status(),
                    request.returnReason(),
                    request.clarification(),
                    request.cancelReason(),
                    materials.getOrDefault(request.id(), List.of()),
                    responses.getOrDefault(request.id(), List.of()),
                    request.createdByName(),
                    request.createdAt(),
                    request.updatedAt(),
                    request.version()
            ));
        }
        return views;
    }

    private Map<UUID, List<PartnerRequestMaterial>> materials(List<UUID> requestIds) {
        return repository.findMaterials(requestIds).stream()
                .collect(Collectors.groupingBy(MaterialRow::requestId, Collectors.mapping(material -> new PartnerRequestMaterial(
                        material.id(),
                        material.name(),
                        material.revision(),
                        material.published() && material.status() == AttachmentStatus.CLEAN,
                        material.receivedAt()
                ), Collectors.toList())));
    }

    private static Map<String, String> texts(String key, String value) {
        return value == null ? Map.of() : Map.of(key, value);
    }

    private static String receiptNumber(UUID responseId) {
        return "В-" + responseId.toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static int requiredVersion(Integer version) {
        if (version == null || version < 0) {
            throw new InteractionValidationException("version", "Укажите версию запроса");
        }
        return version;
    }

    private static String required(String value, String field, int limit, String message) {
        String text = optional(value, field, limit);
        if (text == null) {
            throw new InteractionValidationException(field, message);
        }
        return text;
    }

    private static String optional(String value, String field, int limit) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        if (text.length() > limit) {
            throw new InteractionValidationException(field, "Текст длиннее " + limit + " символов");
        }
        return text;
    }

    private static String checksum(MultipartFile file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getBytes()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Partner response file cannot be read", exception);
        }
    }

    private Reservation reserve(UUID actorId, CommandOperation operation, String idempotencyKey, String fingerprint) {
        String key = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        UUID commandId = UUID.randomUUID();
        if (commandIdempotencyRepository.reserve(commandId, actorId, operation, key, fingerprint, OffsetDateTime.now())) {
            return new Reservation(commandId, null);
        }
        CommandIdempotencyRepository.CommandRecord stored = commandIdempotencyRepository.find(actorId, operation, key)
                .orElseThrow(() -> new IllegalStateException("Reserved partner request command is unavailable"));
        if (!fingerprint.equals(stored.requestFingerprint()) || stored.resultJson() == null) {
            throw InteractionConflictException.idempotency();
        }
        return new Reservation(commandId, stored.resultJson());
    }

    private void complete(Reservation reservation, Object result) {
        try {
            commandIdempotencyRepository.complete(reservation.commandId(), objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Partner request result cannot be stored", exception);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored partner request result cannot be read", exception);
        }
    }

    private record Reservation(UUID commandId, String previousResult) {
        boolean repeated() {
            return previousResult != null;
        }
    }

    private record PartnerScope(UUID organizationId, UUID contactId) {
    }
}
