package ru.rtk.crm.interaction;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.attachment.AttachmentRepository;
import ru.rtk.crm.attachment.AttachmentStatus;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationRepository;

@Service
public class ProductAgreementService {
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int MAX_CONTRACT_NUMBER_LENGTH = 200;

    private final OrganizationRepository organizationRepository;
    private final InteractionRepository interactionRepository;
    private final ProductAgreementRepository productAgreementRepository;
    private final AttachmentRepository attachmentRepository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final InteractionService interactionService;
    private final ObjectMapper objectMapper;

    public ProductAgreementService(
            OrganizationRepository organizationRepository,
            InteractionRepository interactionRepository,
            ProductAgreementRepository productAgreementRepository,
            AttachmentRepository attachmentRepository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            InteractionService interactionService,
            ObjectMapper objectMapper
    ) {
        this.organizationRepository = organizationRepository;
        this.interactionRepository = interactionRepository;
        this.productAgreementRepository = productAgreementRepository;
        this.attachmentRepository = attachmentRepository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.interactionService = interactionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Interaction update(
            CrmProfile profile,
            UUID interactionId,
            UUID agreementId,
            ProductAgreementUpdateRequest request,
            String idempotencyKey
    ) {
        InteractionRepository.InteractionRow row = interactionRepository.findByIdForUpdate(interactionId)
                .orElseThrow(InteractionNotFoundException::new);
        Organization organization = organizationRepository.findVisibleById(profile, row.organizationId())
                .orElseThrow(InteractionNotFoundException::new);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (request == null || request.version() == null || request.version() < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите страницу");
        }
        if (request.contract() == null && request.transfers() == null) {
            throw new InteractionValidationException("body", "Передайте данные договора или отметки передачи");
        }
        ProductAgreementContract contract = request.contract() == null ? null : normalizedContract(request.contract());
        List<ProductTransfer> transfers = request.transfers() == null ? null : normalizedTransfers(request.transfers());
        String normalizedKey = requiredIdempotencyKey(idempotencyKey);
        String fingerprint = CommandFingerprint.of(
                objectMapper,
                new UpdateAgreementCommand(interactionId, agreementId, request.version(), contract, transfers)
        );
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.UPDATE_PRODUCT_AGREEMENT,
                normalizedKey,
                fingerprint,
                now
        )) {
            return replay(profile.id(), normalizedKey, fingerprint);
        }
        if (row.version() != request.version()) {
            throw InteractionConflictException.version(row.version());
        }
        ProductAgreement agreement = interactionRepository.findProductAgreements(interactionId).stream()
                .filter(candidate -> candidate.id().equals(agreementId))
                .findFirst()
                .orElseThrow(ProductAgreementNotFoundException::new);
        if (contract != null && contract.scanAttachmentId() != null) {
            requireCleanAttachment(interactionId, contract.scanAttachmentId(), "contract.scanAttachmentId");
        }
        if (transfers != null) {
            transfers.stream()
                    .map(ProductTransfer::attachmentId)
                    .filter(Objects::nonNull)
                    .forEach(attachmentId -> requireCleanAttachment(interactionId, attachmentId, "transfers.attachmentId"));
        }
        List<String> changes = new ArrayList<>();
        if (contract != null) {
            contractChanges(changes, agreement, contract);
        }
        String transferStatus = transfers == null ? agreement.transferStatus() : ProductAgreementRules.transferStatus(transfers);
        if (transfers != null) {
            transferChanges(changes, agreement, transfers);
            change(changes, "статус передачи", agreement.transferStatus(), transferStatus, "не указан");
        }
        if (changes.isEmpty()) {
            return store(commandId, interactionService.get(profile, interactionId));
        }
        if (!interactionRepository.touchVersion(interactionId, request.version(), now)) {
            throw InteractionConflictException.version(row.version());
        }
        if (contract != null) {
            productAgreementRepository.updateContract(agreementId, contract, now);
        }
        if (transfers != null) {
            productAgreementRepository.replaceTransfers(agreementId, transfers, transferStatus, profile.id(), now);
        }
        InteractionStage currentStage = interactionRepository.findStages(interactionId).stream()
                .filter(stage -> stage.id().equals(row.currentStageId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Current interaction stage is unavailable"));
        interactionRepository.insertEvent(
                UUID.randomUUID(),
                interactionId,
                commandId,
                InteractionEventType.AGREEMENT_UPDATED,
                currentStage,
                null,
                null,
                "«" + agreement.productName() + "»: " + String.join("; ", changes),
                null,
                profile.id(),
                organization.ownerManagerId(),
                request.version() + 1,
                now
        );
        return store(commandId, interactionService.get(profile, interactionId));
    }

    private ProductAgreementContract normalizedContract(ProductAgreementContract contract) {
        String number = contract.contractNumber() == null || contract.contractNumber().isBlank()
                ? null
                : contract.contractNumber().strip();
        if (number != null && number.length() > MAX_CONTRACT_NUMBER_LENGTH) {
            throw new InteractionValidationException(
                    "contract.contractNumber",
                    "Номер договора длиннее " + MAX_CONTRACT_NUMBER_LENGTH + " символов"
            );
        }
        return new ProductAgreementContract(
                number,
                contract.licenseSigned(),
                ProductAgreementRules.optionalLicenseYear(contract.licenseExpiryYear(), "contract.licenseExpiryYear"),
                contract.scanAttachmentId()
        );
    }

    private List<ProductTransfer> normalizedTransfers(List<ProductTransfer> transfers) {
        Set<ProductTransferKind> kinds = EnumSet.noneOf(ProductTransferKind.class);
        LocalDate today = LocalDate.now(ZONE);
        for (ProductTransfer transfer : transfers) {
            if (transfer == null || transfer.kind() == null || transfer.status() == null) {
                throw new InteractionValidationException("transfers", "Укажите вид и статус каждой отметки передачи");
            }
            if (!kinds.add(transfer.kind())) {
                throw new InteractionValidationException("transfers", "Каждый вид передачи указывается один раз");
            }
            if (transfer.status() == ProductTransferStatus.TRANSFERRED) {
                if (transfer.transferredOn() == null) {
                    throw new InteractionValidationException("transfers.transferredOn", "Укажите дату передачи");
                }
                if (transfer.transferredOn().isAfter(today)) {
                    throw new InteractionValidationException("transfers.transferredOn", "Дата передачи не может быть в будущем");
                }
            } else if (transfer.transferredOn() != null || transfer.attachmentId() != null) {
                throw new InteractionValidationException(
                        "transfers",
                        "Для статуса «Не передано» дата и подтверждающий файл не указываются"
                );
            }
        }
        return transfers.stream().sorted(Comparator.comparing(ProductTransfer::kind)).toList();
    }

    private void requireCleanAttachment(UUID interactionId, UUID attachmentId, String field) {
        boolean usable = attachmentRepository.findById(attachmentId)
                .filter(attachment -> attachment.interactionId().equals(interactionId))
                .filter(attachment -> attachment.status() == AttachmentStatus.CLEAN)
                .isPresent();
        if (!usable) {
            throw new InteractionValidationException(field, "Выберите проверенный документ этого взаимодействия");
        }
    }

    private void contractChanges(List<String> changes, ProductAgreement agreement, ProductAgreementContract contract) {
        change(changes, "номер договора", agreement.contractNumber(), contract.contractNumber(), "не указан");
        change(changes, "подписание лицензии", licenseText(agreement.licenseSigned()), licenseText(contract.licenseSigned()),
                "не указано");
        change(changes, "срок лицензии", yearText(agreement.licenseExpiryYear()), yearText(contract.licenseExpiryYear()),
                "не указан");
        change(changes, "скан", fileText(agreement.scanAttachmentId()), fileText(contract.scanAttachmentId()), "нет");
    }

    private void transferChanges(List<String> changes, ProductAgreement agreement, List<ProductTransfer> transfers) {
        for (ProductTransferKind kind : ProductTransferKind.values()) {
            change(changes, kind.title(), transferText(agreement.transfers(), kind), transferText(transfers, kind), "не указано");
        }
    }

    private void change(List<String> changes, String field, String before, String after, String empty) {
        if (!Objects.equals(before, after)) {
            changes.add(field + ": " + (before == null ? empty : before) + " → " + (after == null ? empty : after));
        }
    }

    private String transferText(List<ProductTransfer> transfers, ProductTransferKind kind) {
        return transfers.stream()
                .filter(transfer -> transfer.kind() == kind)
                .findFirst()
                .map(transfer -> transfer.status() == ProductTransferStatus.NOT_TRANSFERRED
                        ? "не передано"
                        : "передано " + DATE.format(transfer.transferredOn())
                                + (transfer.attachmentId() == null ? "" : " (" + fileText(transfer.attachmentId()) + ")"))
                .orElse(null);
    }

    private String licenseText(Boolean signed) {
        if (signed == null) {
            return null;
        }
        return signed ? "подписана" : "не подписана";
    }

    private String yearText(Integer year) {
        return year == null ? null : year.toString();
    }

    private String fileText(UUID attachmentId) {
        if (attachmentId == null) {
            return null;
        }
        return attachmentRepository.findById(attachmentId)
                .map(attachment -> "«" + attachment.originalName() + "»")
                .orElse("удалённый файл");
    }

    private Interaction replay(UUID actorProfileId, String idempotencyKey, String fingerprint) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository
                .find(actorProfileId, CommandOperation.UPDATE_PRODUCT_AGREEMENT, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Reserved agreement command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved agreement command has no result");
        }
        try {
            return objectMapper.readValue(command.resultJson(), Interaction.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored agreement result cannot be read", exception);
        }
    }

    private Interaction store(UUID commandId, Interaction interaction) {
        try {
            String resultJson = objectMapper.writeValueAsString(interaction);
            commandIdempotencyRepository.complete(commandId, resultJson);
            return objectMapper.readValue(resultJson, Interaction.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agreement result cannot be stored", exception);
        }
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

    private record UpdateAgreementCommand(
            UUID interactionId,
            UUID agreementId,
            int version,
            ProductAgreementContract contract,
            List<ProductTransfer> transfers
    ) {
    }
}
