package ru.rtk.crm.agreement;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.agreement.AgreementModels.AgreementFile;
import ru.rtk.crm.agreement.AgreementRepository.NewFile;
import ru.rtk.crm.agreement.AgreementRepository.StoredFile;
import ru.rtk.crm.agreement.AgreementRepository.SupplementRow;
import ru.rtk.crm.attachment.AttachmentContentValidator;
import ru.rtk.crm.attachment.AttachmentScanOutcome;
import ru.rtk.crm.attachment.AttachmentScanner;
import ru.rtk.crm.attachment.AttachmentStatus;
import ru.rtk.crm.attachment.AttachmentStorage;
import ru.rtk.crm.attachment.AttachmentStorageException;
import ru.rtk.crm.attachment.AttachmentUploadInspection;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class AgreementFileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgreementFileService.class);
    private static final int MAX_COMMENT_LENGTH = 500;

    private final AgreementService agreementService;
    private final AgreementRepository repository;
    private final AttachmentContentValidator contentValidator;
    private final AttachmentStorage storage;
    private final AttachmentScanner scanner;

    public AgreementFileService(
            AgreementService agreementService,
            AgreementRepository repository,
            AttachmentContentValidator contentValidator,
            AttachmentStorage storage,
            AttachmentScanner scanner
    ) {
        this.agreementService = agreementService;
        this.repository = repository;
        this.contentValidator = contentValidator;
        this.storage = storage;
        this.scanner = scanner;
    }

    @Transactional
    public AgreementFile upload(
            CrmProfile profile,
            UUID agreementId,
            UUID supplementId,
            String comment,
            MultipartFile file,
            String idempotencyKey
    ) {
        agreementService.visibleAgreement(profile, agreementId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (supplementId != null) {
            SupplementRow supplement = agreementService.visibleSupplement(profile, supplementId);
            if (!supplement.agreementId().equals(agreementId)) {
                throw new InteractionValidationException("supplementId", "Допсоглашение относится к другому договору");
            }
        }
        String normalizedComment = AgreementService.nullableText(comment);
        if (normalizedComment != null && normalizedComment.length() > MAX_COMMENT_LENGTH) {
            throw new InteractionValidationException("comment", "Комментарий длиннее " + MAX_COMMENT_LENGTH + " символов");
        }
        AttachmentUploadInspection inspection = contentValidator.inspect(file);
        UploadCommand command = new UploadCommand(agreementId, supplementId, normalizedComment, inspection.originalName(),
                inspection.mediaType(), inspection.sizeBytes(), inspection.checksum());
        return agreementService.idempotent(profile, CommandOperation.UPLOAD_AGREEMENT_FILE, idempotencyKey, command,
                AgreementFile.class, () -> store(profile, agreementId, supplementId, normalizedComment, file, inspection));
    }

    @Transactional(readOnly = true)
    public DownloadedFile download(CrmProfile profile, UUID fileId) {
        StoredFile file = repository.findStoredFile(fileId).orElseThrow(AgreementException::fileNotFound);
        try {
            agreementService.visibleAgreement(profile, file.agreementId());
        } catch (AgreementException exception) {
            throw AgreementException.fileNotFound();
        }
        if (!file.downloadable() || file.storageKey() == null) {
            throw AgreementException.fileNotFound();
        }
        return new DownloadedFile(file, storage.open(file.storageKey()));
    }

    private AgreementFile store(
            CrmProfile profile,
            UUID agreementId,
            UUID supplementId,
            String comment,
            MultipartFile file,
            AttachmentUploadInspection inspection
    ) {
        UUID id = UUID.randomUUID();
        UUID storageKey = UUID.randomUUID();
        try {
            try (InputStream input = file.getInputStream()) {
                storage.store(storageKey, input, inspection);
            } catch (IOException exception) {
                throw new AttachmentStorageException("Agreement file cannot be read for storage", exception);
            }
            // Файл сначала сохраняется, затем проверяется антивирусом по сохранённой копии. Отклонённый файл удаляется
            // из хранилища, а запись остаётся без ключа хранения (для истории); непроверенный (антивирус недоступен)
            // хранится, но не скачивается.
            AttachmentStatus status = switch (scan(storageKey, inspection.sizeBytes())) {
                case REJECTED -> AttachmentStatus.REJECTED;
                case UNVERIFIABLE -> AttachmentStatus.UNVERIFIABLE;
                case CLEAN -> AttachmentStatus.CLEAN;
            };
            if (status == AttachmentStatus.REJECTED) {
                storage.delete(storageKey);
            }
            // Блокировка перед выбором номера версии (см. lockAgreement). При любой ошибке сохранённый файл удаляется,
            // чтобы в хранилище не оставалось файлов без записи в БД.
            repository.lockAgreement(agreementId);
            repository.insertFile(new NewFile(id, agreementId, supplementId, repository.nextFileVersion(agreementId, supplementId), null,
                    status == AttachmentStatus.REJECTED ? null : storageKey, inspection.originalName(), inspection.mediaType(),
                    inspection.sizeBytes(), inspection.checksum(), status.name(), comment, profile.id(), OffsetDateTime.now()));
            return repository.findFile(id).orElseThrow(AgreementException::fileNotFound);
        } catch (RuntimeException exception) {
            deleteQuietly(storageKey);
            throw exception;
        }
    }

    private AttachmentScanOutcome scan(UUID storageKey, long sizeBytes) {
        try (InputStream input = storage.open(storageKey)) {
            return scanner.scan(input, sizeBytes);
        } catch (IOException exception) {
            return AttachmentScanOutcome.UNVERIFIABLE;
        }
    }

    private void deleteQuietly(UUID storageKey) {
        try {
            storage.delete(storageKey);
        } catch (AttachmentStorageException exception) {
            LOGGER.warn("Agreement file {} was not cleaned up", storageKey, exception);
        }
    }

    public record DownloadedFile(StoredFile file, InputStream content) {
    }

    private record UploadCommand(
            UUID agreementId,
            UUID supplementId,
            String comment,
            String originalName,
            String mediaType,
            long sizeBytes,
            String checksum
    ) {
    }
}
