package ru.rtk.crm.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.source.SourceRepository.MappedTarget;
import ru.rtk.crm.source.SourceRepository.RunDates;
import ru.rtk.crm.source.SourceRepository.StoredRecord;
import ru.rtk.crm.source.SourceRepository.StoredRun;
import ru.rtk.crm.source.SourceRepository.SyncTotals;

@Service
public class SourceSyncService {
    private static final Logger log = LoggerFactory.getLogger(SourceSyncService.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final int PROBLEM_RECORDS_LIMIT = 200;
    private static final int REAPPLY_LIMIT = 500;
    private static final int RUN_HISTORY_LIMIT = 50;

    private final SourceRepository repository;
    private final SiteRecordApplier applier;
    private final SiteApiClient siteApiClient;
    private final MoodleSnapshotApplier moodleApplier;
    private final MoodleClient moodleClient;
    private final SourceSyncExecutor executor;
    private final SourceProperties properties;
    private final ObjectMapper objectMapper;
    private final OffsetDateTime processStartedAt = OffsetDateTime.now();

    public SourceSyncService(
            SourceRepository repository,
            SiteRecordApplier applier,
            SiteApiClient siteApiClient,
            MoodleSnapshotApplier moodleApplier,
            MoodleClient moodleClient,
            SourceSyncExecutor executor,
            SourceProperties properties,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.applier = applier;
        this.siteApiClient = siteApiClient;
        this.moodleApplier = moodleApplier;
        this.moodleClient = moodleClient;
        this.executor = executor;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<SourceView> sources(CrmProfile profile) {
        requireAdmin(profile);
        return Arrays.stream(SourceCode.values()).map(this::view).toList();
    }

    public List<SyncRunView> runs(CrmProfile profile, SourceCode source) {
        requireAdmin(profile);
        return repository.findRuns(source, RUN_HISTORY_LIMIT);
    }

    public SyncRunCreated start(CrmProfile profile, SourceCode source) {
        requireAdmin(profile);
        return enqueue(source, profile.id(), SyncTrigger.MANUAL);
    }

    @Scheduled(cron = "${app.sources.sync-cron}", zone = "Europe/Moscow")
    public void startScheduled() {
        for (SourceCode source : SourceCode.values()) {
            if (!configured(source)) {
                continue;
            }
            Optional<UUID> actor = repository.findScheduleActor(source);
            if (actor.isEmpty()) {
                log.info("Scheduled sync of {} skipped: no active administrator has started it manually yet", source);
                continue;
            }
            try {
                enqueue(source, actor.get(), SyncTrigger.SCHEDULE);
            } catch (SourceException exception) {
                log.info("Scheduled sync of {} skipped: {}", source, exception.code());
            }
        }
    }

    Optional<SyncRunView> synchronizeIfNeverSucceeded(UUID actorProfileId, SourceCode source) {
        if (!configured(source) || repository.findLastFullSuccessAt(source).isPresent()) {
            return Optional.empty();
        }
        UUID runId = insertRun(source, actorProfileId, SyncTrigger.BOOTSTRAP, null);
        run(runId, null, null);
        return repository.findRun(runId).map(StoredRun::run);
    }

    SyncRunView refreshLearning(CrmProfile profile, VisibilityScope scope, UUID interactionId) {
        if (!repository.interactionVisible(interactionId, scope)) {
            throw new InteractionNotFoundException();
        }
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        MappedTarget target = repository.findInteractionProgramTarget(interactionId).orElseThrow(SourceException::learningNotMapped);
        List<Long> courseIds = repository.findLearningMappingKeys(target.organizationId(), target.programId()).stream()
                .map(key -> Long.valueOf(key.split(":", 2)[0]))
                .distinct()
                .filter(moodleClient.courseIds()::contains)
                .toList();
        if (courseIds.isEmpty()) {
            throw SourceException.learningNotMapped();
        }
        if (!configured(SourceCode.MOODLE)) {
            throw SourceException.notConfigured(SourceCode.MOODLE);
        }
        UUID runId = insertRun(SourceCode.MOODLE, profile.id(), SyncTrigger.CARD, target.organizationId());
        run(runId, new LearningScope(target.organizationId(), target.programId(), courseIds), null);
        SyncRunView run = repository.findRun(runId).map(StoredRun::run).orElseThrow(SourceException::recordNotFound);
        if (run.status() != SyncRunStatus.SUCCEEDED) {
            throw SourceException.learningSyncFailed(run.errorMessage());
        }
        return run;
    }

    SyncRunView refreshSite(CrmProfile profile, VisibilityScope scope, UUID interactionId) {
        if (!repository.interactionVisible(interactionId, scope)) {
            throw new InteractionNotFoundException();
        }
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (!configured(SourceCode.WEBSITE)) {
            throw SourceException.notConfigured(SourceCode.WEBSITE);
        }
        UUID organizationId = repository.findInteractionOrganizationId(interactionId).orElseThrow(InteractionNotFoundException::new);
        UUID runId = insertRun(SourceCode.WEBSITE, profile.id(), SyncTrigger.CARD, organizationId);
        run(runId, null, organizationId);
        return repository.findRun(runId).map(StoredRun::run).orElseThrow(SourceException::recordNotFound);
    }

    boolean configured(SourceCode source) {
        return source == SourceCode.MOODLE ? moodleClient.configured() : siteApiClient.configured();
    }

    boolean stale(SourceCode source, OffsetDateTime lastSuccessAt) {
        return configured(source)
                && (lastSuccessAt == null || lastSuccessAt.isBefore(OffsetDateTime.now().minus(properties.staleAfter())));
    }

    private SyncRunCreated enqueue(SourceCode source, UUID actorProfileId, SyncTrigger trigger) {
        if (!configured(source)) {
            throw SourceException.notConfigured(source);
        }
        UUID runId = insertRun(source, actorProfileId, trigger, null);
        try {
            executor.execute(() -> run(runId));
        } catch (TaskRejectedException exception) {
            repository.deleteRun(runId);
            throw SourceException.capacityExceeded();
        }
        return new SyncRunCreated(runId);
    }

    private UUID insertRun(SourceCode source, UUID actorProfileId, SyncTrigger trigger, UUID organizationId) {
        if (repository.hasActiveRun(source)) {
            throw SourceException.alreadyRunning();
        }
        UUID runId = UUID.randomUUID();
        try {
            repository.insertRun(runId, source, actorProfileId, trigger, organizationId, OffsetDateTime.now());
        } catch (DuplicateKeyException exception) {
            throw SourceException.alreadyRunning();
        }
        return runId;
    }

    public List<SourceRecordView> problemRecords(CrmProfile profile) {
        requireAdmin(profile);
        return repository.findProblemRecords(PROBLEM_RECORDS_LIMIT).stream().map(this::recordView).toList();
    }

    public SourceMappingOptions mappingOptions(CrmProfile profile) {
        requireAdmin(profile);
        return new SourceMappingOptions(repository.findOrganizationOptions(), repository.findProgramOptions());
    }

    public SourceRecordApplyResult apply(CrmProfile profile, UUID recordId, SourceRecordApplyRequest request) {
        requireAdmin(profile);
        StoredRecord stored = repository.findRecord(recordId).orElseThrow(SourceException::recordNotFound);
        OffsetDateTime now = OffsetDateTime.now();
        if (stored.source() == SourceCode.MOODLE) {
            saveMoodleMapping(profile, stored, request, now);
        } else {
            saveSiteMappings(profile, stored, request, now);
        }
        reapply(stored.source(), recordId, profile.id());
        int reapplied = 0;
        for (UUID otherId : repository.findNeedsMappingIds(stored.source(), REAPPLY_LIMIT)) {
            if (!otherId.equals(recordId) && reapply(stored.source(), otherId, profile.id()) == SourceRecordStatus.APPLIED) {
                reapplied++;
            }
        }
        StoredRecord updated = repository.findRecord(recordId).orElseThrow(SourceException::recordNotFound);
        return new SourceRecordApplyResult(recordView(updated), reapplied);
    }

    SourceRecordApplyResult applyWebsiteMatching(UUID recordId, Predicate<SiteRecord> sameReference, UUID actorProfileId) {
        reapply(SourceCode.WEBSITE, recordId, actorProfileId);
        int reapplied = 0;
        for (UUID otherId : repository.findNeedsMappingIds(SourceCode.WEBSITE, REAPPLY_LIMIT)) {
            StoredRecord other = repository.findRecord(otherId).orElse(null);
            if (other != null && !otherId.equals(recordId)
                    && sameReference.test(SiteRecord.parseStored(objectMapper, other.payload()))
                    && reapply(SourceCode.WEBSITE, otherId, actorProfileId) == SourceRecordStatus.APPLIED) {
                reapplied++;
            }
        }
        StoredRecord updated = repository.findRecord(recordId).orElseThrow(SourceException::recordNotFound);
        return new SourceRecordApplyResult(recordView(updated), reapplied);
    }

    void reresolveWebsiteLinked(String column, UUID targetId, Predicate<SiteRecord> sameReference, UUID actorProfileId) {
        for (StoredRecord stored : repository.findWebsiteRecordsLinkedTo(column, targetId, REAPPLY_LIMIT)) {
            if (!sameReference.test(SiteRecord.parseStored(objectMapper, stored.payload()))) {
                continue;
            }
            try {
                applier.reresolve(stored.id(), actorProfileId);
            } catch (RuntimeException exception) {
                log.warn("Source record {} was not resolved again after a mapping change", stored.id(), exception);
                applier.markFailed(stored.id(), failureMessage(exception));
            }
        }
    }

    private void saveSiteMappings(CrmProfile profile, StoredRecord stored, SourceRecordApplyRequest request, OffsetDateTime now) {
        SiteRecord item = SiteRecord.parseStored(objectMapper, stored.payload());
        if (request != null && request.organizationId() != null) {
            if (item.organizationKey() == null) {
                throw new InteractionValidationException("organizationId", "В записи нет организации для сопоставления");
            }
            if (repository.findOrganizationById(request.organizationId()).isEmpty()) {
                throw new InteractionValidationException("organizationId", "Организация не найдена");
            }
            repository.saveMapping(stored.source(), "ORGANIZATION", item.organizationKey(), request.organizationId(), null,
                    null, profile.id(), now);
        }
        if (request != null && request.programId() != null) {
            if (item.programName() == null) {
                throw new InteractionValidationException("programId", "В записи нет программы для сопоставления");
            }
            if (!repository.activeProgramExists(request.programId())) {
                throw new InteractionValidationException("programId", "Программа не найдена или в архиве");
            }
            repository.saveMapping(stored.source(), "PROGRAM", SiteRecord.programKey(item.programName()), null,
                    request.programId(), null, profile.id(), now);
        }
    }

    private void saveMoodleMapping(CrmProfile profile, StoredRecord stored, SourceRecordApplyRequest request, OffsetDateTime now) {
        boolean targetChosen = request != null && (request.organizationId() != null || request.programId() != null);
        if (!targetChosen && stored.status() != SourceRecordStatus.NEEDS_MAPPING) {
            return;
        }
        if (request == null || request.organizationId() == null) {
            throw new InteractionValidationException("organizationId", "Выберите вуз для курса или группы Moodle");
        }
        RunDates run = validatedRun(request.organizationId(), request.programId(), request.runStartsOn(), request.runEndsOn());
        moodleApplier.saveMapping(LearningUnit.parseStored(objectMapper, stored.payload()), request.organizationId(),
                request.programId(), run, request.runKind(), profile.id(), now);
    }

    RunDates validatedRun(UUID organizationId, UUID programId, LocalDate startsOn, LocalDate endsOn) {
        if (organizationId == null) {
            throw new InteractionValidationException("organizationId", "Выберите вуз для курса или группы Moodle");
        }
        if (programId == null) {
            throw new InteractionValidationException("programId", "Выберите программу для курса или группы Moodle");
        }
        if (repository.findOrganizationById(organizationId).isEmpty()) {
            throw new InteractionValidationException("organizationId", "Организация не найдена");
        }
        if (!repository.activeProgramExists(programId)) {
            throw new InteractionValidationException("programId", "Программа не найдена или в архиве");
        }
        if (startsOn == null) {
            throw new InteractionValidationException("runStartsOn", "Укажите дату начала потока");
        }
        if (endsOn == null) {
            throw new InteractionValidationException("runEndsOn", "Укажите дату окончания потока");
        }
        if (!startsOn.isBefore(endsOn)) {
            throw new InteractionValidationException("runEndsOn", "Дата окончания потока должна быть позже даты начала");
        }
        return new RunDates(startsOn, endsOn);
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        if (event.getApplicationContext() instanceof WebServerApplicationContext) {
            int failed = repository.failUnfinishedCreatedBefore(
                    processStartedAt,
                    "SYNC_INTERRUPTED",
                    "Синхронизация прервана перезапуском сервера; запустите её снова"
            );
            if (failed > 0) {
                log.warn("{} unfinished source sync runs were marked as failed after restart", failed);
            }
        }
    }

    void run(UUID runId) {
        run(runId, null, null);
    }

    private void run(UUID runId, LearningScope scope, UUID siteOrganizationId) {
        StoredRun stored = repository.findRun(runId).orElse(null);
        if (stored == null) {
            return;
        }
        SourceCode source = stored.run().source();
        OffsetDateTime updatedSince = repository.findUpdatedSince(source).orElse(null);
        if (!repository.claimRun(runId, updatedSince, OffsetDateTime.now())) {
            return;
        }
        try {
            if (source == SourceCode.MOODLE) {
                runMoodle(runId, stored.startedBy(), scope);
            } else {
                runWebsite(runId, source, updatedSince, stored.startedBy(), siteOrganizationId);
            }
        } catch (SourceFetchException exception) {
            log.warn("Source sync run {} of {} failed: {}", runId, source, exception.code());
            repository.failRun(runId, exception.code(), exception.getMessage(), OffsetDateTime.now());
        } catch (RuntimeException exception) {
            log.error("Source sync run {} failed", runId, exception);
            repository.failRun(
                    runId,
                    "SYNC_FAILED",
                    "Синхронизация завершилась ошибкой; подробности в журнале сервера",
                    OffsetDateTime.now()
            );
        }
    }

    private void runWebsite(UUID runId, SourceCode source, OffsetDateTime updatedSince, UUID actorProfileId,
                            UUID organizationId) {
        List<SiteRecord> fetched = siteApiClient.fetch(updatedSince);
        List<SiteRecord> items = organizationId == null
                ? fetched
                : fetched.stream()
                        .filter(SiteRecord::storable)
                        .filter(item -> applier.resolvedOrganizationId(item).filter(organizationId::equals).isPresent())
                        .toList();
        SyncTotals totals = applyAll(items, runId, actorProfileId);
        int unstorable = (int) items.stream().filter(item -> !item.storable()).count();
        repository.completeRun(
                runId,
                totals,
                unstorable == 0 ? null : "Записей без externalId, type или updatedAt: " + unstorable + "; они не сохранены",
                OffsetDateTime.now()
        );
        if (organizationId != null) {
            return;
        }
        items.stream()
                .filter(SiteRecord::storable)
                .map(SiteRecord::updatedAt)
                .max(OffsetDateTime::compareTo)
                .ifPresent(latest -> repository.advanceUpdatedSince(source, latest, OffsetDateTime.now()));
    }

    private void runMoodle(UUID runId, UUID actorProfileId, LearningScope scope) {
        List<LearningUnit> units = scope == null
                ? moodleClient.fetch(moodleClient.courseIds())
                : moodleClient.fetch(scope.courseIds()).stream().filter(unit -> scope.covers(repository, unit)).toList();
        OffsetDateTime observedAt = OffsetDateTime.now();
        int[] counts = new int[SyncOutcome.values().length];
        for (LearningUnit unit : units) {
            SyncOutcome outcome;
            try {
                outcome = moodleApplier.apply(unit, observedAt, runId, actorProfileId);
            } catch (RuntimeException exception) {
                log.warn("Moodle snapshot {} {} was not applied", unit.recordType(), unit.externalId(), exception);
                moodleApplier.recordFailure(unit, observedAt, runId, failureMessage(exception));
                outcome = SyncOutcome.FAILED;
            }
            counts[outcome.ordinal()]++;
        }
        repository.completeRun(runId, totals(units.size(), counts), null, OffsetDateTime.now());
    }

    private SyncTotals applyAll(List<SiteRecord> items, UUID runId, UUID actorProfileId) {
        int[] counts = new int[SyncOutcome.values().length];
        for (SiteRecord item : items) {
            SyncOutcome outcome = item.storable() ? applyOne(item, runId, actorProfileId) : SyncOutcome.FAILED;
            counts[outcome.ordinal()]++;
        }
        return totals(items.size(), counts);
    }

    private static SyncTotals totals(int fetched, int[] counts) {
        return new SyncTotals(
                fetched,
                counts[SyncOutcome.CREATED.ordinal()],
                counts[SyncOutcome.UPDATED.ordinal()],
                counts[SyncOutcome.SKIPPED.ordinal()],
                counts[SyncOutcome.NEEDS_MAPPING.ordinal()],
                counts[SyncOutcome.FAILED.ordinal()]
        );
    }

    private SyncOutcome applyOne(SiteRecord item, UUID runId, UUID actorProfileId) {
        try {
            return applier.apply(item, runId, actorProfileId);
        } catch (RuntimeException exception) {
            log.warn("Source record {} {} was not applied", item.type(), item.externalId(), exception);
            applier.recordFailure(item, runId, failureMessage(exception));
            return SyncOutcome.FAILED;
        }
    }

    private SourceRecordStatus reapply(SourceCode source, UUID recordId, UUID actorProfileId) {
        boolean moodle = source == SourceCode.MOODLE;
        try {
            return moodle ? moodleApplier.reapply(recordId, actorProfileId) : applier.reapply(recordId, actorProfileId);
        } catch (RuntimeException exception) {
            log.warn("Source record {} was not applied again", recordId, exception);
            if (moodle) {
                moodleApplier.markFailed(recordId, failureMessage(exception));
            } else {
                applier.markFailed(recordId, failureMessage(exception));
            }
            return SourceRecordStatus.FAILED;
        }
    }

    private static String failureMessage(RuntimeException exception) {
        return exception instanceof InteractionValidationException
                ? "Запись не применена: " + exception.getMessage()
                : "Запись не применена из-за внутренней ошибки; подробности в журнале сервера";
    }

    private SourceView view(SourceCode source) {
        OffsetDateTime lastSuccessAt = repository.findLastFullSuccessAt(source).orElse(null);
        String schedule = properties.scheduled() ? properties.syncCron().strip() : null;
        OffsetDateTime nextRunAt = schedule != null && configured(source) && repository.findScheduleActor(source).isPresent()
                ? Optional.ofNullable(CronExpression.parse(schedule).next(ZonedDateTime.now(ZONE)))
                        .map(ZonedDateTime::toOffsetDateTime)
                        .orElse(null)
                : null;
        return new SourceView(
                source,
                source.title(),
                true,
                configured(source),
                source == SourceCode.WEBSITE,
                repository.findUpdatedSince(source).orElse(null),
                lastSuccessAt,
                repository.countProblems(source),
                repository.findLatestFullRun(source).orElse(null),
                schedule,
                nextRunAt,
                stale(source, lastSuccessAt)
        );
    }

    SourceRecordView recordView(StoredRecord stored) {
        String organizationExternalId = null;
        String organizationName;
        String programName = null;
        if (stored.source() == SourceCode.MOODLE) {
            organizationName = LearningUnit.parseStored(objectMapper, stored.payload()).label();
        } else {
            SiteRecord item = SiteRecord.parseStored(objectMapper, stored.payload());
            organizationExternalId = item.organizationExternalId();
            organizationName = item.organizationName();
            programName = item.programName();
        }
        return new SourceRecordView(
                stored.id(),
                stored.source(),
                stored.recordType(),
                stored.externalId(),
                stored.externalUpdatedAt(),
                stored.externalStatus(),
                stored.status(),
                stored.error(),
                organizationExternalId,
                organizationName,
                programName,
                stored.organizationId(),
                stored.programId(),
                stored.interactionId(),
                stored.updatedAt()
        );
    }

    private record LearningScope(UUID organizationId, UUID programId, List<Long> courseIds) {
        boolean covers(SourceRepository repository, LearningUnit unit) {
            return repository.findLearningTargets(SourceCode.MOODLE, unit.mappingKind(), unit.externalId()).stream()
                    .anyMatch(target -> target.organizationId().equals(organizationId) && target.programId().equals(programId));
        }
    }

    static void requireAdmin(CrmProfile profile) {
        if (profile.role() != UserRole.ADMIN) {
            throw SourceException.adminRequired();
        }
    }
}
