package ru.rtk.crm.source;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.source.SourceRepository.ApplyResult;
import ru.rtk.crm.source.SourceRepository.MappedTarget;
import ru.rtk.crm.source.SourceRepository.RecordVersion;
import ru.rtk.crm.source.SourceRepository.RunDates;
import ru.rtk.crm.source.SourceRepository.StoredRecord;
import ru.rtk.crm.source.SourceRepository.StoredSnapshot;

@Service
public class MoodleSnapshotApplier {
    private static final SourceCode SOURCE = SourceCode.MOODLE;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter MOSCOW_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter MOSCOW_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final SourceRepository repository;
    private final InteractionService interactionService;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public MoodleSnapshotApplier(
            SourceRepository repository,
            InteractionService interactionService,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.interactionService = interactionService;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SyncOutcome apply(LearningUnit unit, OffsetDateTime observedAt, UUID runId, UUID actorProfileId) {
        Optional<StoredRecord> existing = repository.findRecordForUpdate(SOURCE, unit.recordType(), unit.externalId());
        return publish(existing, unit, observedAt, runId, actorProfileId);
    }

    @Transactional
    public SourceRecordStatus reapply(UUID recordId, UUID actorProfileId) {
        StoredRecord stored = repository.findRecordForUpdate(recordId).orElseThrow(SourceException::recordNotFound);
        if (stored.status() != SourceRecordStatus.NEEDS_MAPPING && stored.status() != SourceRecordStatus.FAILED) {
            return stored.status();
        }
        LearningUnit unit = LearningUnit.parseStored(objectMapper, stored.payload());
        return switch (publish(Optional.of(stored), unit, stored.externalUpdatedAt(), null, actorProfileId)) {
            case CREATED, UPDATED -> SourceRecordStatus.APPLIED;
            case NEEDS_MAPPING -> SourceRecordStatus.NEEDS_MAPPING;
            case FAILED -> SourceRecordStatus.FAILED;
            case SKIPPED -> repository.findRecord(recordId).map(StoredRecord::status).orElse(SourceRecordStatus.SKIPPED);
        };
    }

    @Transactional
    public void saveMapping(LearningUnit unit, UUID organizationId, UUID programId, RunDates run, UUID actorProfileId,
                            OffsetDateTime now) {
        repository.findRecordForUpdate(SOURCE, LearningUnit.COURSE_RECORD, unit.courseKey());
        if (unit.group() && repository.hasMapping(SOURCE, "COURSE", unit.courseKey())) {
            throw new InteractionValidationException(
                    "organizationId", "Курс уже сопоставлен целиком; его группы учитываются в нём и отдельно не сопоставляются"
            );
        }
        if (!unit.group() && repository.hasMapping(SOURCE, "GROUP", unit.courseKey() + ":%")) {
            throw new InteractionValidationException(
                    "organizationId", "Группы курса уже сопоставлены по отдельности; курс целиком одновременно не учитывается"
            );
        }
        repository.saveMapping(SOURCE, unit.mappingKind(), unit.externalId(), organizationId, programId, run, actorProfileId, now);
    }

    @Transactional
    public void recordFailure(LearningUnit unit, OffsetDateTime observedAt, UUID runId, String error) {
        Optional<StoredRecord> existing = repository.findRecordForUpdate(SOURCE, unit.recordType(), unit.externalId());
        ApplyResult failed = new ApplyResult(SourceRecordStatus.FAILED, error, null, null, 1,
                existing.map(StoredRecord::interactionId).orElse(null));
        if (existing.isPresent()) {
            repository.updateRecord(existing.get().id(), version(unit, observedAt), failed, runId, OffsetDateTime.now());
        } else {
            repository.insertRecord(UUID.randomUUID(), SOURCE, version(unit, observedAt), failed, runId, OffsetDateTime.now());
        }
    }

    @Transactional
    public void markFailed(UUID recordId, String error) {
        repository.findRecordForUpdate(recordId).ifPresent(stored -> repository.updateRecord(
                stored.id(),
                version(LearningUnit.parseStored(objectMapper, stored.payload()), stored.externalUpdatedAt()),
                new ApplyResult(SourceRecordStatus.FAILED, error, null, null, 1, stored.interactionId()),
                null,
                OffsetDateTime.now()
        ));
    }

    private SyncOutcome publish(
            Optional<StoredRecord> existing,
            LearningUnit unit,
            OffsetDateTime observedAt,
            UUID runId,
            UUID actorProfileId
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        UUID recordId = existing.map(StoredRecord::id).orElseGet(UUID::randomUUID);
        UUID previousInteractionId = existing.map(StoredRecord::interactionId).orElse(null);
        Optional<MappedTarget> target = repository.findMappedTarget(SOURCE, unit.mappingKind(), unit.externalId());
        ApplyResult result;
        SyncOutcome outcome;
        Optional<StoredSnapshot> previous = Optional.empty();
        boolean changed = false;
        boolean published = false;
        if (target.isEmpty()) {
            result = unmapped(unit, previousInteractionId);
            outcome = result.status() == SourceRecordStatus.SKIPPED ? SyncOutcome.SKIPPED : SyncOutcome.NEEDS_MAPPING;
        } else if (target.get().runStartsOn() == null) {
            result = new ApplyResult(SourceRecordStatus.NEEDS_MAPPING,
                    "Не указаны даты потока: выберите вуз и программу и укажите начало и окончание потока", null, null, 1,
                    previousInteractionId);
            outcome = SyncOutcome.NEEDS_MAPPING;
        } else if (!observedAt.atZoneSameInstant(ZONE).toLocalDate().isBefore(target.get().runEndsOn())) {
            previous = repository.findSnapshot(recordId);
            result = previous.isPresent()
                    ? new ApplyResult(SourceRecordStatus.APPLIED, null, target.get().organizationId(), target.get().programId(),
                            1, previousInteractionId)
                    : new ApplyResult(SourceRecordStatus.SKIPPED, "Поток закончился "
                            + MOSCOW_DATE.format(target.get().runEndsOn()) + " до первого наблюдения: текущий состав курса"
                            + " к нему не относится", null, null, 1, previousInteractionId);
            outcome = SyncOutcome.SKIPPED;
        } else {
            published = true;
            previous = repository.findSnapshot(recordId);
            changed = previous.map(snapshot -> !sameSnapshot(snapshot, unit, target.get())).orElse(true);
            UUID interactionId = changed
                    ? appendEvent(recordId, unit, target.get(), previousInteractionId, observedAt, actorProfileId, now)
                    : previousInteractionId;
            result = new ApplyResult(SourceRecordStatus.APPLIED, null, target.get().organizationId(),
                    target.get().programId(), 1, interactionId);
            outcome = !changed ? SyncOutcome.SKIPPED : previous.isEmpty() ? SyncOutcome.CREATED : SyncOutcome.UPDATED;
        }
        if (existing.isPresent()) {
            repository.updateRecord(recordId, version(unit, observedAt), result, runId, now);
        } else {
            repository.insertRecord(recordId, SOURCE, version(unit, observedAt), result, runId, now);
        }
        if (published) {
            OffsetDateTime changedAt = changed ? observedAt : previous.get().changedAt();
            repository.saveSnapshot(recordId, unit, target.get(), observedAt, changedAt, runId);
        }
        return outcome;
    }

    private ApplyResult unmapped(LearningUnit unit, UUID previousInteractionId) {
        if (!unit.group() && repository.hasMapping(SOURCE, "GROUP", unit.courseKey() + ":%")) {
            return new ApplyResult(SourceRecordStatus.SKIPPED,
                    "Курс учитывается по сопоставленным группам", null, null, 1, previousInteractionId);
        }
        if (unit.group() && repository.hasMapping(SOURCE, "COURSE", unit.courseKey())) {
            return new ApplyResult(SourceRecordStatus.SKIPPED,
                    "Курс сопоставлен целиком одним потоком; учащиеся группы учтены в нём", null, null, 1, previousInteractionId);
        }
        String error = unit.group()
                ? "Группа не сопоставлена с вузом и программой CRM; выберите их или сопоставьте курс целиком"
                : "Курс не сопоставлен с вузом и программой CRM; выберите их для курса целиком или для его групп";
        return new ApplyResult(SourceRecordStatus.NEEDS_MAPPING, error, null, null, 1, previousInteractionId);
    }

    private static boolean sameSnapshot(StoredSnapshot snapshot, LearningUnit unit, MappedTarget target) {
        return snapshot.organizationId().equals(target.organizationId())
                && snapshot.programId().equals(target.programId())
                && snapshot.unit().sameCounts(unit);
    }

    private UUID appendEvent(
            UUID recordId,
            LearningUnit unit,
            MappedTarget target,
            UUID previousInteractionId,
            OffsetDateTime observedAt,
            UUID actorProfileId,
            OffsetDateTime now
    ) {
        UUID commandId = UUID.randomUUID();
        String key = "source:" + SOURCE + ":" + recordId + ":" + observedAt.toInstant();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                actorProfileId,
                CommandOperation.APPLY_SOURCE_RECORD,
                key,
                CommandFingerprint.of(objectMapper, key),
                now
        )) {
            throw new IllegalStateException("Moodle snapshot was already published for this observation");
        }
        UUID interactionId = previousInteractionId != null
                && repository.interactionBelongsTo(previousInteractionId, target.organizationId())
                ? previousInteractionId
                : repository.findLatestInteraction(target.organizationId(), target.programId(), null).orElse(null);
        if (interactionId == null) {
            interactionId = interactionService.createSourceInteraction(
                    target.organizationId(),
                    target.ownerManagerId(),
                    truncate("Обучение в LMS: " + unit.courseName()),
                    target.programId(),
                    List.of(),
                    List.of(),
                    actorProfileId,
                    commandId,
                    now
            );
        }
        interactionService.appendSourceComment(
                interactionId, eventText(unit, observedAt), List.of(), target.ownerManagerId(), actorProfileId, commandId, now
        );
        commandIdempotencyRepository.complete(commandId, write(Map.of("recordId", recordId, "interactionId", interactionId)));
        return interactionId;
    }

    static String eventText(LearningUnit unit, OffsetDateTime observedAt) {
        StringBuilder text = new StringBuilder("Данные LMS: курс «").append(unit.courseName()).append("»");
        if (unit.group()) {
            text.append(", группа «").append(unit.groupName()).append("»");
        }
        text.append(", обучающихся ").append(unit.participants());
        if (unit.completed() == null) {
            text.append(", завершили: нет данных (завершение курса в Moodle не отслеживается)");
        } else {
            text.append(", завершили ").append(unit.completed())
                    .append(", не завершили ").append(unit.notCompleted())
                    .append(", статус неизвестен ").append(unit.unknown());
        }
        if (!unit.group()) {
            text.append(", групп ").append(unit.groupsCount());
        }
        return text.append(", преподавателей ").append(unit.teachers())
                .append(". Наблюдение ").append(MOSCOW_TIME.format(observedAt.atZoneSameInstant(ZONE))).append(" (МСК)")
                .toString();
    }

    private RecordVersion version(LearningUnit unit, OffsetDateTime observedAt) {
        return new RecordVersion(unit.recordType(), unit.externalId(), observedAt, observedAt, null, write(unit));
    }

    private static String truncate(String value) {
        return value.length() <= MAX_TITLE_LENGTH ? value : value.substring(0, MAX_TITLE_LENGTH - 1) + "…";
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Moodle snapshot command result cannot be stored", exception);
        }
    }
}
