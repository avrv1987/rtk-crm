package ru.rtk.crm.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.Interaction;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionEventType;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionRepository;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionStage;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.interaction.CommandFingerprint;

@Service
public class AttachmentService {
    private static final Set<String> PREVIEW_MEDIA_TYPES = Set.of("application/pdf", "image/png", "image/jpeg");
    private static final int MAX_REASON_LENGTH = 500;

    private final OrganizationRepository organizationRepository;
    private final InteractionRepository interactionRepository;
    private final AttachmentRepository attachmentRepository;
    private final AttachmentContentValidator contentValidator;
    private final AttachmentStorage storage;
    private final AttachmentScanner scanner;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final InteractionService interactionService;
    private final ObjectMapper objectMapper;

    public AttachmentService(
            OrganizationRepository organizationRepository,
            InteractionRepository interactionRepository,
            AttachmentRepository attachmentRepository,
            AttachmentContentValidator contentValidator,
            AttachmentStorage storage,
            AttachmentScanner scanner,
            CommandIdempotencyRepository commandIdempotencyRepository,
            InteractionService interactionService,
            ObjectMapper objectMapper
    ) {
        this.organizationRepository = organizationRepository;
        this.interactionRepository = interactionRepository;
        this.attachmentRepository = attachmentRepository;
        this.contentValidator = contentValidator;
        this.storage = storage;
        this.scanner = scanner;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.interactionService = interactionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Attachment upload(
            CrmProfile profile,
            UUID interactionId,
            AttachmentUploadRequest request,
            MultipartFile file,
            String idempotencyKey
    ) {
        requireVisibleInteraction(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        requireStage(interactionId, request.stageId());
        AttachmentUploadInspection inspection = contentValidator.inspect(file);
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        UploadAttachmentCommand command = new UploadAttachmentCommand(
                interactionId,
                request.stageId(),
                request.kind(),
                request.replacesId(),
                inspection.originalName(),
                inspection.mediaType(),
                inspection.sizeBytes(),
                inspection.checksum()
        );
        String fingerprint = CommandFingerprint.of(objectMapper, command);
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.UPLOAD_ATTACHMENT,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replay(profile.id(), CommandOperation.UPLOAD_ATTACHMENT, normalizedKey, fingerprint, Attachment.class);
        }
        if (request.replacesId() != null) {
            requireReplaceable(interactionId, request.replacesId());
        }

        UUID attachmentId = UUID.randomUUID();
        UUID storageKey = UUID.randomUUID();
        try {
            store(storageKey, file, inspection);
            AttachmentScanOutcome outcome = scan(storageKey, inspection.sizeBytes());
            requireVisibleInteractionForUpdate(profile, interactionId);
            requireStage(interactionId, request.stageId());
            AttachmentRepository.AttachmentRow replaced = request.replacesId() == null
                    ? null
                    : requireReplaceable(interactionId, request.replacesId());
            AttachmentKind kind = request.kind() != null
                    ? request.kind()
                    : replaced == null ? AttachmentKind.OTHER : replaced.kind();
            attachmentRepository.insert(
                    attachmentId,
                    interactionId,
                    request.stageId(),
                    inspection.originalName(),
                    inspection.mediaType(),
                    inspection.sizeBytes(),
                    storageKey,
                    inspection.checksum(),
                    kind,
                    replaced == null ? 1 : replaced.revision() + 1,
                    request.replacesId(),
                    profile.id(),
                    now
            );
            if (outcome == AttachmentScanOutcome.REJECTED) {
                storage.delete(storageKey);
                attachmentRepository.updateStatus(attachmentId, AttachmentStatus.REJECTED, OffsetDateTime.now());
            } else if (outcome == AttachmentScanOutcome.UNVERIFIABLE) {
                attachmentRepository.updateStatus(attachmentId, AttachmentStatus.UNVERIFIABLE, OffsetDateTime.now());
            } else {
                attachmentRepository.updateStatus(attachmentId, AttachmentStatus.CLEAN, OffsetDateTime.now());
            }
            Attachment attachment = attachmentRepository.findById(attachmentId)
                    .orElseThrow(AttachmentNotFoundException::new)
                    .attachment();
            commandIdempotencyRepository.complete(commandId, write(attachment));
            return attachment;
        } catch (RuntimeException exception) {
            deleteStoredFile(storageKey);
            throw exception;
        }
    }

    @Transactional
    public Attachment updateKind(CrmProfile profile, UUID attachmentId, AttachmentKindUpdateRequest request) {
        AttachmentRepository.AttachmentRow attachment = requireVisibleAttachment(profile, attachmentId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (request == null || request.kind() == null && request.partnerVisible() == null) {
            throw new AttachmentValidationException("kind", "Выберите вид документа");
        }
        if (request.version() == null || request.version() < 0) {
            throw new InteractionValidationException("version", "Укажите версию сведений о документе");
        }
        AttachmentKind kind = request.kind() == null ? attachment.kind() : request.kind();
        boolean partnerVisible = request.partnerVisible() == null ? attachment.partnerVisible() : request.partnerVisible();
        if (attachment.kind() == kind && attachment.partnerVisible() == partnerVisible) {
            return attachment.attachment();
        }
        if (!attachmentRepository.updateKind(attachmentId, kind, partnerVisible, request.version(), OffsetDateTime.now())) {
            throw InteractionConflictException.attachmentVersion(attachment.version());
        }
        return attachmentRepository.findById(attachmentId)
                .orElseThrow(AttachmentNotFoundException::new)
                .attachment();
    }

    @Transactional
    public Interaction delete(
            CrmProfile profile,
            UUID interactionId,
            UUID attachmentId,
            AttachmentDeletionRequest request,
            String idempotencyKey
    ) {
        Organization organization = requireVisibleInteractionForUpdate(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (request == null || request.version() == null || request.version() < 0) {
            throw new InteractionValidationException("version", "Укажите версию карточки");
        }
        String reason = optionalReason(request.reason());
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(
                objectMapper,
                new DeleteAttachmentCommand(interactionId, attachmentId, request.version(), reason)
        );
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.DELETE_ATTACHMENT,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replay(profile.id(), CommandOperation.DELETE_ATTACHMENT, normalizedKey, fingerprint, Interaction.class);
        }
        AttachmentRepository.AttachmentRow attachment = attachmentRepository.findById(attachmentId)
                .filter(row -> row.interactionId().equals(interactionId))
                .orElseThrow(AttachmentNotFoundException::new);
        if (!attachment.createdBy().equals(profile.id()) && profile.role() != UserRole.LEADER) {
            throw new AttachmentDeletionForbiddenException();
        }
        attachmentRepository.findAgreementUsage(attachmentId).ifPresent(productName -> {
            throw new AttachmentValidationException(
                    "attachmentId",
                    "Документ указан как скан договора или подтверждение передачи по продукту «" + productName
                            + "»; сначала выберите там другой файл"
            );
        });
        if (!interactionRepository.touchVersion(interactionId, request.version(), now)) {
            throw InteractionConflictException.version(interactionService.get(profile, interactionId).version());
        }
        attachmentRepository.markDeleted(attachmentId, profile.id(), now);
        InteractionStage stage = interactionRepository.findStages(interactionId).stream()
                .filter(candidate -> candidate.id().equals(attachment.stageId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Attachment stage is unavailable"));
        interactionRepository.insertEvent(
                UUID.randomUUID(),
                interactionId,
                commandId,
                InteractionEventType.ATTACHMENT_DELETED,
                stage,
                null,
                null,
                deletionComment(attachment, reason),
                null,
                profile.id(),
                organization.ownerManagerId(),
                request.version() + 1,
                now
        );
        String resultJson = write(interactionService.get(profile, interactionId));
        commandIdempotencyRepository.complete(commandId, resultJson);
        return read(resultJson, Interaction.class);
    }

    @Transactional(readOnly = true)
    public Attachment get(CrmProfile profile, UUID attachmentId) {
        return requireVisibleAttachment(profile, attachmentId).attachment();
    }

    @Transactional(readOnly = true)
    public DownloadedAttachment download(CrmProfile profile, UUID attachmentId) {
        AttachmentRepository.AttachmentRow attachment = requireVisibleAttachment(profile, attachmentId);
        if (attachment.status() != AttachmentStatus.CLEAN) {
            throw new AttachmentNotFoundException();
        }
        return new DownloadedAttachment(attachment.attachment(), storage.open(attachment.storageKey()));
    }

    @Transactional(readOnly = true)
    public DownloadedAttachment preview(CrmProfile profile, UUID attachmentId) {
        AttachmentRepository.AttachmentRow attachment = requireVisibleAttachment(profile, attachmentId);
        if (attachment.status() != AttachmentStatus.CLEAN) {
            throw new AttachmentNotFoundException();
        }
        if (!PREVIEW_MEDIA_TYPES.contains(attachment.mediaType())) {
            throw new AttachmentValidationException("id", "Просмотр доступен для PDF, PNG и JPEG; этот файл можно скачать");
        }
        return new DownloadedAttachment(attachment.attachment(), storage.open(attachment.storageKey()));
    }

    private void requireVisibleInteraction(CrmProfile profile, UUID interactionId) {
        if (interactionId == null) {
            throw new InteractionValidationException("id", "Укажите взаимодействие");
        }
        UUID organizationId = interactionRepository.findOrganizationIdById(interactionId)
                .orElseThrow(InteractionNotFoundException::new);
        organizationRepository.findVisibleById(profile, organizationId)
                .orElseThrow(InteractionNotFoundException::new);
    }

    private Organization requireVisibleInteractionForUpdate(CrmProfile profile, UUID interactionId) {
        if (interactionId == null) {
            throw new InteractionValidationException("id", "Укажите взаимодействие");
        }
        UUID organizationId = interactionRepository.findOrganizationIdByIdForUpdate(interactionId)
                .orElseThrow(InteractionNotFoundException::new);
        return organizationRepository.findVisibleById(profile, organizationId)
                .orElseThrow(InteractionNotFoundException::new);
    }

    private AttachmentRepository.AttachmentRow requireVisibleAttachment(CrmProfile profile, UUID attachmentId) {
        AttachmentRepository.AttachmentRow attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(AttachmentNotFoundException::new);
        requireVisibleInteraction(profile, attachment.interactionId());
        return attachment;
    }

    private AttachmentRepository.AttachmentRow requireReplaceable(UUID interactionId, UUID replacesId) {
        AttachmentRepository.AttachmentRow replaced = attachmentRepository.findById(replacesId)
                .filter(row -> row.interactionId().equals(interactionId))
                .orElseThrow(() -> new AttachmentValidationException(
                        "replacesId",
                        "Прежняя версия документа не найдена в этом взаимодействии"
                ));
        if (attachmentRepository.hasLiveReplacement(replacesId)) {
            throw InteractionConflictException.attachmentReplaced();
        }
        return replaced;
    }

    private void requireStage(UUID interactionId, UUID stageId) {
        if (stageId == null) {
            throw new AttachmentValidationException("stageId", "Выберите этап");
        }
        boolean belongsToInteraction = interactionRepository.findStages(interactionId).stream()
                .map(InteractionStage::id)
                .anyMatch(stageId::equals);
        if (!belongsToInteraction) {
            throw new AttachmentValidationException("stageId", "Этап не относится к этому взаимодействию");
        }
    }

    private String deletionComment(AttachmentRepository.AttachmentRow attachment, String reason) {
        String comment = "Удалён документ «" + attachment.originalName() + "» (вид: "
                + attachment.kind().title().toLowerCase(Locale.ROOT) + ", версия " + attachment.revision() + ")";
        return reason == null ? comment : comment + ". Причина: " + reason;
    }

    private String optionalReason(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String reason = value.strip();
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new InteractionValidationException("reason", "Причина длиннее " + MAX_REASON_LENGTH + " символов");
        }
        return reason;
    }

    private void store(UUID storageKey, MultipartFile file, AttachmentUploadInspection inspection) {
        try (InputStream input = file.getInputStream()) {
            storage.store(storageKey, input, inspection);
        } catch (IOException exception) {
            throw new AttachmentStorageException("Attachment cannot be read for storage", exception);
        }
    }

    private AttachmentScanOutcome scan(UUID storageKey, long sizeBytes) {
        try (InputStream input = storage.open(storageKey)) {
            return scanner.scan(input, sizeBytes);
        } catch (IOException exception) {
            return AttachmentScanOutcome.UNVERIFIABLE;
        }
    }

    private void deleteStoredFile(UUID storageKey) {
        try {
            storage.delete(storageKey);
        } catch (AttachmentStorageException ignored) {
        }
    }

    private <T> T replay(
            UUID actorProfileId,
            CommandOperation operation,
            String idempotencyKey,
            String fingerprint,
            Class<T> resultType
    ) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, operation, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved attachment command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved attachment command has no result");
        }
        return read(command.resultJson(), resultType);
    }

    private <T> T read(String resultJson, Class<T> resultType) {
        try {
            return objectMapper.readValue(resultJson, resultType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored attachment result cannot be read", exception);
        }
    }

    private String write(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Attachment result cannot be stored", exception);
        }
    }

    private String requiredIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new AttachmentValidationException("Idempotency-Key", "Не передан ключ повтора запроса Idempotency-Key");
        }
        if (value.length() > 255) {
            throw new AttachmentValidationException("Idempotency-Key", "Ключ повтора запроса Idempotency-Key длиннее 255 символов");
        }
        return value;
    }

    public record DownloadedAttachment(Attachment attachment, InputStream content) {
    }

    private record UploadAttachmentCommand(
            UUID interactionId,
            UUID stageId,
            AttachmentKind kind,
            UUID replacesId,
            String originalName,
            String mediaType,
            long sizeBytes,
            String checksum
    ) {
    }

    private record DeleteAttachmentCommand(UUID interactionId, UUID attachmentId, int version, String reason) {
    }
}
