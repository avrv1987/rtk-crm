package ru.rtk.crm.enrolment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.source.SourceSyncService;

@Service
public class PaidOrderUploadService {
    private final EnrolmentAccess enrolmentAccess;
    private final PaidOrderParser parser;
    private final SourceSyncService sourceSyncService;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final PaidOrderEnrolment paidOrderEnrolment;
    private final ObjectMapper objectMapper;

    public PaidOrderUploadService(
            EnrolmentAccess enrolmentAccess,
            PaidOrderParser parser,
            SourceSyncService sourceSyncService,
            CommandIdempotencyRepository commandIdempotencyRepository,
            PaidOrderEnrolment paidOrderEnrolment,
            ObjectMapper objectMapper
    ) {
        this.enrolmentAccess = enrolmentAccess;
        this.parser = parser;
        this.sourceSyncService = sourceSyncService;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.paidOrderEnrolment = paidOrderEnrolment;
        this.objectMapper = objectMapper;
    }

    public PaidOrderUpload upload(CrmProfile profile, MultipartFile file, String idempotencyKey, String requestId) {
        enrolmentAccess.requireOperator(profile);
        String key = AdminAuthorization.requiredIdempotencyKey(idempotencyKey);
        byte[] content = content(file);
        PaidOrderBatch batch = parser.parse(new ByteArrayInputStream(content));
        String fingerprint = CommandFingerprint.of(objectMapper, content);
        UUID commandId = UUID.randomUUID();
        if (!commandIdempotencyRepository.reserve(
                commandId, profile.id(), CommandOperation.UPLOAD_PAID_ORDERS, key, fingerprint, OffsetDateTime.now()
        )) {
            CommandIdempotencyRepository.CommandRecord previous = commandIdempotencyRepository
                    .find(profile.id(), CommandOperation.UPLOAD_PAID_ORDERS, key)
                    .orElseThrow(() -> new IllegalStateException("Paid order upload command is unavailable"));
            if (!fingerprint.equals(previous.requestFingerprint()) || previous.resultJson() == null) {
                throw InteractionConflictException.idempotency();
            }
            return read(previous.resultJson());
        }
        PaidOrderUpload result = sourceSyncService.uploadPaidOrders(profile.id(), batch);
        paidOrderEnrolment.journalUpload(profile.id(), result, requestId);
        commandIdempotencyRepository.complete(commandId, write(result));
        return result;
    }

    private static byte[] content(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InteractionValidationException("file", "Выберите файл оплат в формате JSON");
        }
        try (InputStream input = file.getInputStream()) {
            return input.readNBytes(PaidOrderParser.MAX_BYTES + 1);
        } catch (IOException exception) {
            throw new InteractionValidationException("file", "Файл оплат не удалось прочитать");
        }
    }

    private PaidOrderUpload read(String json) {
        try {
            return objectMapper.readValue(json, PaidOrderUpload.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored paid order upload result cannot be read", exception);
        }
    }

    private String write(PaidOrderUpload result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Paid order upload result cannot be stored", exception);
        }
    }
}
