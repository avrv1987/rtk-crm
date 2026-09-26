package ru.rtk.crm.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionRepository;
import ru.rtk.crm.interaction.InteractionStage;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.interaction.CommandFingerprint;

@Service
public class AttachmentService {
    private final OrganizationRepository organizationRepository;
    private final InteractionRepository interactionRepository;
    private final AttachmentRepository attachmentRepository;
    private final AttachmentContentValidator contentValidator;
    private final AttachmentStorage storage;
    private final AttachmentScanner scanner;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public AttachmentService(
            OrganizationRepository organizationRepository,
            InteractionRepository interactionRepository,
            AttachmentRepository attachmentRepository,
            AttachmentContentValidator contentValidator,
            AttachmentStorage storage,
            AttachmentScanner scanner,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.organizationRepository = organizationRepository;
        this.interactionRepository = interactionRepository;
        this.attachmentRepository = attachmentRepository;
        this.contentValidator = contentValidator;
        this.storage = storage;
        this.scanner = scanner;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Attachment upload(
            CrmProfile profile,
            UUID interactionId,
            UUID stageId,
            MultipartFile file,
            String idempotencyKey
    ) {
        requireVisibleInteraction(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        requireStage(interactionId, stageId);
        AttachmentUploadInspection inspection = contentValidator.inspect(file);
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        UploadAttachmentCommand command = new UploadAttachmentCommand(
                interactionId,
                stageId,
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
            return replay(profile.id(), normalizedKey, fingerprint);
        }

        UUID attachmentId = UUID.randomUUID();
        UUID storageKey = UUID.randomUUID();
        try {
            store(storageKey, file, inspection);
            AttachmentScanOutcome outcome = scan(storageKey, inspection.sizeBytes());
            requireVisibleInteractionForUpdate(profile, interactionId);
            requireStage(interactionId, stageId);
            attachmentRepository.insert(
                    attachmentId,
                    interactionId,
                    stageId,
                    inspection.originalName(),
                    inspection.mediaType(),
                    inspection.sizeBytes(),
                    storageKey,
                    inspection.checksum(),
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

    private void requireVisibleInteraction(CrmProfile profile, UUID interactionId) {
        if (interactionId == null) {
            throw new InteractionValidationException("id", "Укажите взаимодействие");
        }
        UUID organizationId = interactionRepository.findOrganizationIdById(interactionId)
                .orElseThrow(InteractionNotFoundException::new);
        organizationRepository.findVisibleById(profile, organizationId)
                .orElseThrow(InteractionNotFoundException::new);
    }

    private void requireVisibleInteractionForUpdate(CrmProfile profile, UUID interactionId) {
        if (interactionId == null) {
            throw new InteractionValidationException("id", "Укажите взаимодействие");
        }
        UUID organizationId = interactionRepository.findOrganizationIdByIdForUpdate(interactionId)
                .orElseThrow(InteractionNotFoundException::new);
        organizationRepository.findVisibleById(profile, organizationId)
                .orElseThrow(InteractionNotFoundException::new);
    }

    private AttachmentRepository.AttachmentRow requireVisibleAttachment(CrmProfile profile, UUID attachmentId) {
        AttachmentRepository.AttachmentRow attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(AttachmentNotFoundException::new);
        requireVisibleInteraction(profile, attachment.interactionId());
        return attachment;
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

    private Attachment replay(UUID actorProfileId, String idempotencyKey, String fingerprint) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, CommandOperation.UPLOAD_ATTACHMENT, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved attachment command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved attachment command has no result");
        }
        try {
            return objectMapper.readValue(command.resultJson(), Attachment.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored attachment result cannot be read", exception);
        }
    }

    private String write(Attachment attachment) {
        try {
            return objectMapper.writeValueAsString(attachment);
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
            String originalName,
            String mediaType,
            long sizeBytes,
            String checksum
    ) {
    }
}
