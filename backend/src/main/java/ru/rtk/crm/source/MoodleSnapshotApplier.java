package ru.rtk.crm.source;

import java.time.LocalDate;
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
import ru.rtk.crm.source.SourceRepository.StoredMapping;
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
        return republish(stored, actorProfileId);
    }

    @Transactional
    public void saveMapping(LearningUnit unit, UUID organizationId, UUID programId, RunDates run, RunKind runKind,
                            UUID actorProfileId, OffsetDateTime now) {
        requireExclusive(unit.group(), unit.courseKey());
        List<StoredMapping> mappings = repository.findMappings(SOURCE, unit.mappingKind(), unit.externalId());
        if (mappings.isEmpty()) {
            repository.insertMapping(SOURCE, unit.mappingKind(), unit.externalId(), organizationId, programId, run, runKind,
                    actorProfileId, now);
        } else if (mappings.size() == 1) {
            repository.updateMapping(mappings.getFirst().id(), mappings.getFirst().version(), organizationId, programId, run,
                    runKind, actorProfileId, now);
        } else {
            throw new InteractionValidationException("organizationId",
                    "У курса или группы несколько потоков; измените нужный поток в списке сохранённых сопоставлений");
        }
    }

    @Transactional
    public UUID addRun(StoredMapping template, UUID organizationId, UUID programId, RunDates run, RunKind runKind,
                       UUID actorProfileId, OffsetDateTime now) {
        Optional<StoredRecord> record = lockUnit(template);
        requireFreeInterval(template, run, null);
        UUID id = repository.insertMapping(SOURCE, template.kind(), template.externalKey(), organizationId, programId, run,
                runKind, actorProfileId, now);
        record.ifPresent(stored -> republish(stored, actorProfileId));
        return id;
    }

    @Transactional
    public void updateRun(StoredMapping mapping, UUID organizationId, UUID programId, RunDates run, RunKind runKind,
                          UUID actorProfileId, OffsetDateTime now) {
        Optional<StoredRecord> record = lockUnit(mapping);
        requireFreeInterval(mapping, run, mapping.id());
        if (!repository.updateMapping(mapping.id(), mapping.version(), organizationId, programId, run, runKind,
                actorProfileId, now)) {
            throw SourceException.mappingVersion(repository.findMapping(mapping.id()).map(StoredMapping::version).orElse(0));
        }
        Optional<StoredSnapshot> snapshot = repository.findSnapshot(mapping.id());
        if (snapshot.isPresent()) {
            boolean insideRun = localDate(snapshot.get().observedAt()).isBefore(run.endsOn());
            boolean sameTarget = snapshot.get().organizationId().equals(organizationId)
                    && snapshot.get().programId().equals(programId);
            boolean latestInsideRun = record.map(stored -> localDate(stored.externalUpdatedAt()).isBefore(run.endsOn()))
                    .orElse(false);
            if (!insideRun || (!sameTarget && latestInsideRun)) {
                repository.deleteSnapshot(mapping.id());
            } else if (!sameTarget) {
                repository.moveSnapshot(mapping.id(), organizationId, programId);
            }
        }
        record.ifPresent(stored -> republish(stored, actorProfileId));
    }

    @Transactional
    public void removeRun(StoredMapping mapping, UUID actorProfileId) {
        lockUnit(mapping);
        repository.deleteMapping(mapping.id());
        String courseKey = mapping.externalKey().split(":", 2)[0];
        for (StoredRecord stored : repository.findMoodleRecordsOfCourse(courseKey)) {
            republish(stored, actorProfileId);
        }
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

    private SourceRecordStatus republish(StoredRecord stored, UUID actorProfileId) {
        LearningUnit unit = LearningUnit.parseStored(objectMapper, stored.payload());
        return switch (publish(Optional.of(stored), unit, stored.externalUpdatedAt(), null, actorProfileId)) {
            case CREATED, UPDATED -> SourceRecordStatus.APPLIED;
            case NEEDS_MAPPING -> SourceRecordStatus.NEEDS_MAPPING;
            case FAILED -> SourceRecordStatus.FAILED;
            case SKIPPED -> repository.findRecord(stored.id()).map(StoredRecord::status).orElse(SourceRecordStatus.SKIPPED);
        };
    }

    private Optional<StoredRecord> lockUnit(StoredMapping mapping) {
        String courseKey = mapping.externalKey().split(":", 2)[0];
        Optional<StoredRecord> course = repository.findRecordForUpdate(SOURCE, LearningUnit.COURSE_RECORD, courseKey);
        return mapping.kind().equals("GROUP")
                ? repository.findRecordForUpdate(SOURCE, LearningUnit.GROUP_RECORD, mapping.externalKey())
                : course;
    }

    private void requireExclusive(boolean group, String courseKey) {
        repository.findRecordForUpdate(SOURCE, LearningUnit.COURSE_RECORD, courseKey);
        if (group && repository.hasMapping(SOURCE, "COURSE", courseKey)) {
            throw new InteractionValidationException(
                    "organizationId", "Курс уже сопоставлен целиком; его группы учитываются в нём и отдельно не сопоставляются"
            );
        }
        if (!group && repository.hasMapping(SOURCE, "GROUP", courseKey + ":%")) {
            throw new InteractionValidationException(
                    "organizationId", "Группы курса уже сопоставлены по отдельности; курс целиком одновременно не учитывается"
            );
        }
    }

    private void requireFreeInterval(StoredMapping unitMapping, RunDates run, UUID excludedId) {
        for (StoredMapping other : repository.findMappings(SOURCE, unitMapping.kind(), unitMapping.externalKey())) {
            if (other.id().equals(excludedId)) {
                continue;
            }
            if (other.run() == null) {
                throw new InteractionValidationException("runStartsOn",
                        "У другого потока этого курса или группы не указаны даты; сначала укажите их");
            }
            if (other.run().overlaps(run)) {
                throw new InteractionValidationException("runStartsOn", "Поток пересекается с потоком "
                        + MOSCOW_DATE.format(other.runStartsOn()) + " – " + MOSCOW_DATE.format(other.runEndsOn().minusDays(1))
                        + " этого же курса или группы: у одного курса или группы потоки идут по очереди");
            }
        }
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
        List<MappedTarget> targets = repository.findLearningTargets(SOURCE, unit.mappingKind(), unit.externalId());
        List<MappedTarget> dated = targets.stream().filter(target -> target.runStartsOn() != null).toList();
        LocalDate observedOn = localDate(observedAt);
        Optional<MappedTarget> upcoming = dated.stream().filter(target -> observedOn.isBefore(target.runStartsOn())).findFirst();
        Optional<MappedTarget> ended = dated.stream().filter(target -> !observedOn.isBefore(target.runEndsOn()))
                .reduce((first, second) -> second);
        Optional<MappedTarget> current = dated.stream()
                .filter(target -> !observedOn.isBefore(target.runStartsOn()) && observedOn.isBefore(target.runEndsOn()))
                .findFirst()
                .or(() -> ended.isPresent() ? Optional.empty() : upcoming);
        ApplyResult result;
        SyncOutcome outcome;
        Optional<StoredSnapshot> previous = Optional.empty();
        boolean changed = false;
        UUID snapshotInteractionId = null;
        if (targets.isEmpty()) {
            result = unmapped(unit, previousInteractionId);
            outcome = result.status() == SourceRecordStatus.SKIPPED ? SyncOutcome.SKIPPED : SyncOutcome.NEEDS_MAPPING;
        } else if (dated.isEmpty()) {
            result = new ApplyResult(SourceRecordStatus.NEEDS_MAPPING,
                    "Не указаны даты потока: выберите вуз и программу и укажите начало и окончание потока", null, null, 1,
                    previousInteractionId);
            outcome = SyncOutcome.NEEDS_MAPPING;
        } else if (current.isEmpty() && upcoming.isPresent()) {
            result = new ApplyResult(SourceRecordStatus.SKIPPED, "Наблюдение " + MOSCOW_DATE.format(observedOn)
                    + " между потоками: прошлый поток закончился " + MOSCOW_DATE.format(ended.get().runEndsOn())
                    + ", следующий начнётся " + MOSCOW_DATE.format(upcoming.get().runStartsOn())
                    + "; текущий состав курса ни к одному из них не относится", null, null, 1, previousInteractionId);
            outcome = SyncOutcome.SKIPPED;
        } else if (current.isEmpty()) {
            MappedTarget last = dated.getLast();
            result = repository.hasSnapshotForRecord(recordId)
                    ? new ApplyResult(SourceRecordStatus.APPLIED, null, last.organizationId(), last.programId(), 1,
                            previousInteractionId)
                    : new ApplyResult(SourceRecordStatus.SKIPPED, "Поток закончился "
                            + MOSCOW_DATE.format(last.runEndsOn()) + " до первого наблюдения: текущий состав курса"
                            + " к нему не относится", null, null, 1, previousInteractionId);
            outcome = SyncOutcome.SKIPPED;
        } else {
            MappedTarget target = current.get();
            previous = repository.findSnapshot(target.mappingId());
            changed = previous.map(snapshot -> !sameSnapshot(snapshot, unit, target)).orElse(true);
            UUID interactionId = previous.map(StoredSnapshot::interactionId)
                    .filter(id -> repository.interactionBelongsTo(id, target.organizationId()))
                    .orElse(null);
            if (changed) {
                interactionId = appendEvent(recordId, unit, target, interactionId, observedAt, actorProfileId, now);
            }
            snapshotInteractionId = interactionId;
            result = new ApplyResult(SourceRecordStatus.APPLIED, null, target.organizationId(), target.programId(), 1,
                    interactionId == null ? previousInteractionId : interactionId);
            outcome = !changed ? SyncOutcome.SKIPPED : previous.isEmpty() ? SyncOutcome.CREATED : SyncOutcome.UPDATED;
        }
        if (existing.isPresent()) {
            repository.updateRecord(recordId, version(unit, observedAt), result, runId, now);
        } else {
            repository.insertRecord(recordId, SOURCE, version(unit, observedAt), result, runId, now);
        }
        if (current.isPresent()) {
            OffsetDateTime changedAt = changed ? observedAt : previous.get().changedAt();
            repository.saveSnapshot(recordId, unit, current.get(), observedAt, changedAt, runId, snapshotInteractionId);
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
            UUID knownInteractionId,
            OffsetDateTime observedAt,
            UUID actorProfileId,
            OffsetDateTime now
    ) {
        String key = "source:" + SOURCE + ":" + target.mappingId() + ":" + target.organizationId() + ":" + target.programId()
                + ":" + observedAt.toInstant();
        Optional<UUID> alreadyApplied = repository.findAppliedInteraction(List.of(key), target.organizationId());
        if (alreadyApplied.isPresent()) {
            return alreadyApplied.get();
        }
        UUID commandId = UUID.randomUUID();
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
        UUID interactionId = knownInteractionId != null
                ? knownInteractionId
                : repository.findCycleInteraction(target.organizationId(), target.programId(), target.runStartsOn()).orElse(null);
        if (interactionId == null) {
            interactionId = interactionService.createSourceInteraction(
                    target.organizationId(),
                    target.ownerManagerId(),
                    truncate((target.runKind() == RunKind.TEACHERS ? "Обучение преподавателей в LMS: " : "Обучение в LMS: ")
                            + unit.courseName()),
                    target.programId(),
                    List.of(),
                    List.of(),
                    actorProfileId,
                    commandId,
                    now
            );
        }
        interactionService.appendSourceComment(
                interactionId, eventText(unit, observedAt, target.runKind()), List.of(), target.ownerManagerId(),
                actorProfileId, commandId, now
        );
        commandIdempotencyRepository.complete(commandId, write(Map.of("recordId", recordId, "interactionId", interactionId)));
        return interactionId;
    }

    static String eventText(LearningUnit unit, OffsetDateTime observedAt, RunKind runKind) {
        boolean teachers = runKind == RunKind.TEACHERS;
        StringBuilder text = new StringBuilder(teachers ? "Данные LMS (обучение преподавателей): курс «" : "Данные LMS: курс «")
                .append(unit.courseName()).append("»");
        if (unit.group()) {
            text.append(", группа «").append(unit.groupName()).append("»");
        }
        text.append(teachers ? ", записано " : ", обучающихся ").append(unit.participants());
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
        return text.append(teachers ? ", ведущих курс " : ", преподавателей ").append(unit.teachers())
                .append(". Наблюдение ").append(MOSCOW_TIME.format(observedAt.atZoneSameInstant(ZONE))).append(" (МСК)")
                .toString();
    }

    private static LocalDate localDate(OffsetDateTime value) {
        return value.atZoneSameInstant(ZONE).toLocalDate();
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
