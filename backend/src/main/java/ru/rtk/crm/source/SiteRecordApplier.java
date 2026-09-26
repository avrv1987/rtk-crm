package ru.rtk.crm.source;

import static ru.rtk.crm.source.SiteRecord.LEARNING_APPLICATION;
import static ru.rtk.crm.source.SiteRecord.PARTNERSHIP_REQUEST;
import static ru.rtk.crm.source.SiteRecord.WITHDRAWN;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.catalog.Contact;
import ru.rtk.crm.catalog.ContactRepository;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.source.SourceRepository.ApplyResult;
import ru.rtk.crm.source.SourceRepository.SourceOrganization;
import ru.rtk.crm.source.SourceRepository.StoredRecord;

@Service
public class SiteRecordApplier {
    private static final SourceCode SOURCE = SourceCode.WEBSITE;
    private static final int MAX_COMMENT_LENGTH = 4_000;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter MOSCOW_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final SourceRepository repository;
    private final ContactRepository contactRepository;
    private final InteractionService interactionService;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public SiteRecordApplier(
            SourceRepository repository,
            ContactRepository contactRepository,
            InteractionService interactionService,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.contactRepository = contactRepository;
        this.interactionService = interactionService;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SyncOutcome apply(SiteRecord item, UUID runId, UUID actorProfileId) {
        OffsetDateTime now = OffsetDateTime.now();
        Optional<StoredRecord> existing = repository.findRecordForUpdate(SOURCE, item.type(), item.externalId());
        if (existing.isPresent() && !replaces(existing.get(), item)) {
            return SyncOutcome.SKIPPED;
        }
        UUID recordId = existing.map(StoredRecord::id).orElseGet(UUID::randomUUID);
        ApplyResult result = resolve(recordId, item, existing.map(StoredRecord::interactionId).orElse(null), actorProfileId, now);
        if (existing.isPresent()) {
            repository.updateRecord(recordId, item.version(), result, runId, now);
        } else {
            repository.insertRecord(recordId, SOURCE, item.version(), result, runId, now);
        }
        return switch (result.status()) {
            case APPLIED -> existing.isPresent() ? SyncOutcome.UPDATED : SyncOutcome.CREATED;
            case NEEDS_MAPPING -> SyncOutcome.NEEDS_MAPPING;
            case FAILED -> SyncOutcome.FAILED;
            case SKIPPED -> SyncOutcome.SKIPPED;
        };
    }

    @Transactional
    public void recordFailure(SiteRecord item, UUID runId, String error) {
        OffsetDateTime now = OffsetDateTime.now();
        Optional<StoredRecord> existing = repository.findRecordForUpdate(SOURCE, item.type(), item.externalId());
        if (existing.isEmpty()) {
            repository.insertRecord(UUID.randomUUID(), SOURCE, item.version(), failed(error, null), runId, now);
        } else if (replaces(existing.get(), item)) {
            repository.updateRecord(existing.get().id(), item.version(), failed(error, existing.get().interactionId()), runId, now);
        }
    }

    @Transactional
    public SourceRecordStatus reapply(UUID recordId, UUID actorProfileId) {
        StoredRecord stored = repository.findRecordForUpdate(recordId).orElseThrow(SourceException::recordNotFound);
        if (stored.status() != SourceRecordStatus.NEEDS_MAPPING && stored.status() != SourceRecordStatus.FAILED) {
            return stored.status();
        }
        SiteRecord item = SiteRecord.parseStored(objectMapper, stored.payload());
        OffsetDateTime now = OffsetDateTime.now();
        ApplyResult result = resolve(stored.id(), item, stored.interactionId(), actorProfileId, now);
        repository.updateRecord(stored.id(), item.version(), result, null, now);
        return result.status();
    }

    @Transactional
    public void markFailed(UUID recordId, String error) {
        repository.findRecordForUpdate(recordId).ifPresent(stored -> repository.updateRecord(
                stored.id(),
                SiteRecord.parseStored(objectMapper, stored.payload()).version(),
                failed(error, stored.interactionId()),
                null,
                OffsetDateTime.now()
        ));
    }

    private static boolean replaces(StoredRecord existing, SiteRecord item) {
        if (item.updatedAt().isAfter(existing.externalUpdatedAt())) {
            return true;
        }
        return item.updatedAt().isEqual(existing.externalUpdatedAt())
                && (existing.status() == SourceRecordStatus.NEEDS_MAPPING || existing.status() == SourceRecordStatus.FAILED);
    }

    private ApplyResult resolve(
            UUID recordId,
            SiteRecord item,
            UUID previousInteractionId,
            UUID actorProfileId,
            OffsetDateTime now
    ) {
        if (item.problem() != null) {
            return failed("Запись не соответствует контракту: " + item.problem(), previousInteractionId);
        }
        boolean partnership = PARTNERSHIP_REQUEST.equals(item.type());
        if (!partnership && !LEARNING_APPLICATION.equals(item.type())) {
            return new ApplyResult(SourceRecordStatus.SKIPPED,
                    "Тип записи «" + item.type() + "» не входит в предложенный контракт", null, null, 1, previousInteractionId);
        }
        if (item.organizationKey() == null) {
            return failed("В записи не указана организация", previousInteractionId);
        }
        Optional<SourceOrganization> organization = organization(item);
        if (organization.isEmpty()) {
            return new ApplyResult(SourceRecordStatus.NEEDS_MAPPING, organizationProblem(item), null, null, 1,
                    previousInteractionId);
        }
        UUID organizationId = organization.get().id();
        UUID programId = null;
        if (item.programName() != null) {
            Optional<UUID> program = program(item.programName());
            if (program.isEmpty()) {
                return new ApplyResult(SourceRecordStatus.NEEDS_MAPPING,
                        "Программа «" + item.programName() + "» не найдена в справочнике CRM или неоднозначна; выберите программу",
                        organizationId, null, 1, previousInteractionId);
            }
            programId = program.get();
        }
        int applications = item.applicationsCount() == null ? 1 : item.applicationsCount();
        if (!partnership) {
            if (WITHDRAWN.equalsIgnoreCase(item.status())) {
                return new ApplyResult(SourceRecordStatus.SKIPPED, "Заявка отозвана на сайте и не учитывается в спросе",
                        organizationId, programId, applications, null);
            }
            return new ApplyResult(SourceRecordStatus.APPLIED, null, organizationId, programId, applications, null);
        }
        UUID interactionId = applyPartnership(
                recordId, item, organization.get(), programId, previousInteractionId, actorProfileId, now
        );
        return new ApplyResult(SourceRecordStatus.APPLIED, null, organizationId, programId, 1, interactionId);
    }

    private UUID applyPartnership(
            UUID recordId,
            SiteRecord item,
            SourceOrganization organization,
            UUID programId,
            UUID previousInteractionId,
            UUID actorProfileId,
            OffsetDateTime now
    ) {
        UUID commandId = UUID.randomUUID();
        String key = "source:" + SOURCE + ":" + recordId + ":" + item.updatedAt().toInstant();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                actorProfileId,
                CommandOperation.APPLY_SOURCE_RECORD,
                key,
                CommandFingerprint.of(objectMapper, key),
                now
        )) {
            throw new IllegalStateException("Source record version was already applied by this profile");
        }
        UUID productId = uniqueOrNull(item.productName() == null ? List.of() : repository.findActiveProductIdsByName(item.productName()));
        List<UUID> contactIds = contact(organization.id(), item, actorProfileId, now).map(List::of).orElse(List.of());
        UUID interactionId = previousInteractionId != null && repository.interactionBelongsTo(previousInteractionId, organization.id())
                ? previousInteractionId
                : repository.findLatestInteraction(organization.id(), programId, productId).orElse(null);
        if (interactionId == null) {
            interactionId = interactionService.createSourceInteraction(
                    organization.id(),
                    organization.ownerManagerId(),
                    title(item),
                    programId,
                    productId == null ? List.of() : List.of(productId),
                    contactIds,
                    actorProfileId,
                    commandId,
                    now
            );
        }
        interactionService.appendSourceComment(
                interactionId,
                comment(item, previousInteractionId != null),
                contactIds,
                organization.ownerManagerId(),
                actorProfileId,
                commandId,
                now
        );
        commandIdempotencyRepository.complete(commandId, write(Map.of("recordId", recordId, "interactionId", interactionId)));
        return interactionId;
    }

    private Optional<SourceOrganization> organization(SiteRecord item) {
        return repository.findMappedOrganization(SOURCE, item.organizationKey())
                .or(() -> item.organizationExternalId() == null
                        ? Optional.empty()
                        : repository.findOrganizationByExternalKey(item.organizationExternalId()))
                .or(() -> item.organizationName() == null
                        ? Optional.empty()
                        : repository.findOrganizationByName(item.organizationName()));
    }

    private Optional<UUID> program(String programName) {
        return repository.findMappedProgramId(SOURCE, SiteRecord.programKey(programName))
                .or(() -> Optional.ofNullable(uniqueOrNull(repository.findActiveProgramIdsByName(programName))));
    }

    private Optional<UUID> contact(UUID organizationId, SiteRecord item, UUID actorProfileId, OffsetDateTime now) {
        if (item.contactName() == null && item.contactEmail() == null) {
            return Optional.empty();
        }
        Optional<Contact> known = contactRepository.findByOrganizationId(organizationId).stream()
                .filter(contact -> item.contactEmail() != null
                        ? item.contactEmail().equalsIgnoreCase(contact.email())
                        : item.contactName().equals(contact.name()))
                .findFirst();
        if (known.isPresent()) {
            return Optional.of(known.get().id());
        }
        return Optional.of(contactRepository.insert(
                UUID.randomUUID(),
                organizationId,
                item.contactName() == null ? item.contactEmail() : item.contactName(),
                item.contactPosition(),
                item.contactEmail(),
                item.contactPhone(),
                actorProfileId,
                now
        ).id());
    }

    private static String organizationProblem(SiteRecord item) {
        String name = item.organizationName() == null ? "без названия" : "«" + item.organizationName() + "»";
        String externalId = item.organizationExternalId() == null ? "" : " (внешний ID " + item.organizationExternalId() + ")";
        return "Вуз " + name + externalId + " не сопоставлен с организацией CRM; выберите вуз";
    }

    private static String title(SiteRecord item) {
        String subject = item.programName() != null ? item.programName()
                : item.productName() != null ? item.productName()
                : "партнёрство";
        return truncate("Заявка с сайта: " + subject, MAX_TITLE_LENGTH);
    }

    private static String comment(SiteRecord item, boolean update) {
        StringBuilder text = new StringBuilder(update ? "Заявка с сайта обновлена: " : "Заявка с сайта: ")
                .append(item.message() == null ? "текст заявки не передан" : item.message())
                .append("\nВнешний ID: ").append(item.externalId())
                .append("; статус на сайте: ").append(item.status() == null ? "не указан" : item.status())
                .append("; дата: ").append(MOSCOW_TIME.format(item.submittedAt().atZoneSameInstant(ZONE)))
                .append(" (МСК)");
        if (item.programName() != null) {
            text.append("\nПрограмма: ").append(item.programName());
        }
        if (item.productName() != null) {
            text.append("\nПродукт: ").append(item.productName());
        }
        if (item.contactName() != null || item.contactEmail() != null || item.contactPhone() != null) {
            text.append("\nКонтакт: ").append(String.join(", ", Stream.of(
                    item.contactName(), item.contactPosition(), item.contactEmail(), item.contactPhone()
            ).filter(Objects::nonNull).toList()));
        }
        return truncate(text.toString(), MAX_COMMENT_LENGTH);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }

    private static UUID uniqueOrNull(List<UUID> ids) {
        return ids.size() == 1 ? ids.getFirst() : null;
    }

    private static ApplyResult failed(String error, UUID interactionId) {
        return new ApplyResult(SourceRecordStatus.FAILED, truncate(error, 500), null, null, 1, interactionId);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Source command result cannot be stored", exception);
        }
    }
}
