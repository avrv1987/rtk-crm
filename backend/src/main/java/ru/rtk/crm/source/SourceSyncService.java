package ru.rtk.crm.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.enrolment.LearnerIntake;
import ru.rtk.crm.enrolment.PaidOrder;
import ru.rtk.crm.enrolment.PaidOrderBatch;
import ru.rtk.crm.enrolment.PaidOrderEnrolment;
import ru.rtk.crm.enrolment.PaidOrderUpload;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.source.SiteApiClient.SiteResponse;
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
    private final CrmRecordApplier crmApplier;
    private final CrmExchangeService crmExchange;
    private final PaidOrderEnrolment paidOrderEnrolment;
    private final SiteApiClient siteApiClient;
    private final MoodleSnapshotApplier moodleApplier;
    private final MoodleClient moodleClient;
    private final LaborMarketSync laborMarketSync;
    private final SourceSyncExecutor executor;
    private final SourceProperties properties;
    private final SourceSettingsService settings;
    private final ObjectMapper objectMapper;
    private final OffsetDateTime processStartedAt = OffsetDateTime.now();

    public SourceSyncService(
            SourceRepository repository,
            SiteRecordApplier applier,
            CrmRecordApplier crmApplier,
            CrmExchangeService crmExchange,
            PaidOrderEnrolment paidOrderEnrolment,
            SiteApiClient siteApiClient,
            MoodleSnapshotApplier moodleApplier,
            MoodleClient moodleClient,
            LaborMarketSync laborMarketSync,
            SourceSyncExecutor executor,
            SourceProperties properties,
            SourceSettingsService settings,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.applier = applier;
        this.crmApplier = crmApplier;
        this.crmExchange = crmExchange;
        this.paidOrderEnrolment = paidOrderEnrolment;
        this.siteApiClient = siteApiClient;
        this.moodleApplier = moodleApplier;
        this.moodleClient = moodleClient;
        this.laborMarketSync = laborMarketSync;
        this.executor = executor;
        this.properties = properties;
        this.settings = settings;
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

    public void startScheduled() {
        for (SourceCode source : SourceCode.values()) {
            if (!configured(source)) {
                continue;
            }
            // Запуск по расписанию идёт от администратора, который уже запускал источник вручную: записи и карточки
            // создаются от имени действующего пользователя. Пока такого нет, расписание источник пропускает.
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

    // Начальная загрузка только пока источник ни разу не загружался полностью; позже данные обновляют обычные запуски.
    Optional<SyncRunView> synchronizeIfNeverSucceeded(UUID actorProfileId, SourceCode source) {
        if (repository.findLastFullSuccessAt(source).isPresent()) {
            return Optional.empty();
        }
        return synchronizeNow(actorProfileId, source);
    }

    Optional<SyncRunView> synchronizeNow(UUID actorProfileId, SourceCode source) {
        if (!configured(source)) {
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
                // Сопоставление могло остаться от курса, убранного из списка CRM: такой курс не запрашивается.
                .filter(settings.moodle().courseIds()::contains)
                .toList();
        if (courseIds.isEmpty()) {
            throw SourceException.learningNotMapped();
        }
        if (!configured(SourceCode.MOODLE)) {
            throw SourceException.notConfigured(SourceCode.MOODLE);
        }
        UUID runId = insertRun(SourceCode.MOODLE, profile.id(), SyncTrigger.CARD, target.organizationId());
        // Запуск из карточки читает курсы программы, но применяет только записи этого вуза и программы
        // (LearningScope.covers).
        run(runId, new LearningScope(target.organizationId(), target.programId(), courseIds), null);
        SyncRunView run = repository.findRun(runId).map(StoredRun::run).orElseThrow(SourceException::recordNotFound);
        // Частичный запуск тоже SUCCEEDED: карточка получает данные прочитанных курсов, ошибка нужна только при полном
        // провале.
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
        return switch (source) {
            case MOODLE -> settings.moodle().configured();
            case LABOR_MARKET -> laborMarketSync.configured();
            case CUSTOMER_CRM -> settings.crm().configured();
            case WEBSITE -> settings.website().configured();
        };
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
            // Отклонённый очередью запуск удаляется: висящий PENDING блокировал бы источник (hasActiveRun).
            repository.deleteRun(runId);
            throw SourceException.capacityExceeded();
        }
        return new SyncRunCreated(runId);
    }

    private UUID insertRun(SourceCode source, UUID actorProfileId, SyncTrigger trigger, UUID organizationId) {
        // Одновременно активен один запуск источника: два запуска применяли бы одни и те же записи.
        if (repository.hasActiveRun(source)) {
            throw SourceException.alreadyRunning();
        }
        UUID runId = UUID.randomUUID();
        try {
            repository.insertRun(runId, source, actorProfileId, trigger, organizationId, OffsetDateTime.now());
        } catch (DuplicateKeyException exception) {
            // hasActiveRun не защищает от гонки двух запросов: единственность активного запуска окончательно держит
            // ограничение в базе.
            throw SourceException.alreadyRunning();
        }
        return runId;
    }

    public PaidOrderUpload uploadPaidOrders(UUID actorProfileId, PaidOrderBatch batch) {
        UUID runId = insertRun(SourceCode.WEBSITE, actorProfileId, SyncTrigger.UPLOAD, null);
        // Загрузка применяется в этом же потоке, поэтому запуск занимается сразу, минуя очередь исполнителя.
        if (!repository.claimRun(runId, null, OffsetDateTime.now())) {
            throw new IllegalStateException("Paid order upload run was claimed by another worker");
        }
        PaidOrdersApplied applied;
        try {
            applied = applyPaidOrders(batch, runId, actorProfileId, actorProfileId);
            repository.completeRun(runId, applied.totals(), paidOrdersMessage(batch), OffsetDateTime.now());
        } catch (RuntimeException exception) {
            log.error("Paid order upload run {} failed", runId, exception);
            repository.failRun(runId, "SYNC_FAILED", "Загрузка оплат завершилась ошибкой; подробности в журнале сервера",
                    OffsetDateTime.now());
            throw exception;
        }
        SyncRunView run = repository.findRun(runId).map(StoredRun::run).orElseThrow(SourceException::recordNotFound);
        return PaidOrderUpload.of(run.id(), run.createdCount(), run.updatedCount(), run.skippedCount(), run.needsMappingCount(),
                run.failedCount(), run.errorMessage(), batch, applied.intake());
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
            if (stored.source() == SourceCode.CUSTOMER_CRM && request != null && request.contactId() != null) {
                crmApplier.chooseContact(stored, request.contactId());
            }
        }
        // Выбор вуза или программы может разрешить и другие записи с теми же ключами: ожидающие сопоставления
        // переприменяются ограниченным пакетом (REAPPLY_LIMIT), остальные подхватит следующая загрузка.
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

    // Как apply, но из ожидающих сопоставления берутся только записи с той же ссылкой (sameReference): чужие записи
    // этим выбором не решаются.
    SourceRecordApplyResult applyMatching(SourceCode source, UUID recordId, Predicate<SiteRecord> sameReference, UUID actorProfileId) {
        reapply(source, recordId, actorProfileId);
        int reapplied = 0;
        for (UUID otherId : repository.findNeedsMappingIds(source, REAPPLY_LIMIT)) {
            StoredRecord other = repository.findRecord(otherId).orElse(null);
            if (other != null && !otherId.equals(recordId)
                    && sameReference.test(SiteRecord.parseStored(objectMapper, other.payload()))
                    && reapply(source, otherId, actorProfileId) == SourceRecordStatus.APPLIED) {
                reapplied++;
            }
        }
        StoredRecord updated = repository.findRecord(recordId).orElseThrow(SourceException::recordNotFound);
        return new SourceRecordApplyResult(recordView(updated), reapplied);
    }

    void reresolveLinked(SourceCode source, String column, UUID targetId, Predicate<SiteRecord> sameReference, UUID actorProfileId) {
        SourceRecordApplier recordApplier = source == SourceCode.CUSTOMER_CRM ? crmApplier : applier;
        for (StoredRecord stored : repository.findRecordsLinkedTo(source, column, targetId, REAPPLY_LIMIT)) {
            if (!sameReference.test(SiteRecord.parseStored(objectMapper, stored.payload()))) {
                continue;
            }
            try {
                recordApplier.reresolve(stored.id(), actorProfileId);
            } catch (RuntimeException exception) {
                // Сбой одной записи не срывает остальные: она помечается ошибкой, пересчёт продолжается.
                log.warn("Source record {} was not resolved again after a mapping change", stored.id(), exception);
                recordApplier.markFailed(stored.id(), failureMessage(exception));
            }
        }
    }

    private void saveSiteMappings(CrmProfile profile, StoredRecord stored, SourceRecordApplyRequest request, OffsetDateTime now) {
        SiteRecord item = SiteRecord.parseStored(objectMapper, stored.payload());
        if (request != null && request.organizationId() != null) {
            if (item.paidOrder()) {
                throw new InteractionValidationException("organizationId",
                        "Оплаты относятся к служебной организации «" + SiteRecord.OPEN_ENROLMENT_NAME + "»; выберите только программу");
            }
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
        // Запись уже сопоставлена и выбор не менялся: повторное сохранение ничего не делает. Ожидающей записи нужен
        // явный выбор вуза.
        if (!targetChosen && stored.status() != SourceRecordStatus.NEEDS_MAPPING) {
            return;
        }
        if (request == null || request.organizationId() == null) {
            throw new InteractionValidationException("organizationId", "Выберите вуз для курса или группы Moodle");
        }
        RunDates run = validatedRun(request.organizationId(), request.programId(), request.runStartsOn(), request.runEndsOn());
        LearningUnit unit = LearningUnit.parseStored(objectMapper, stored.payload());
        moodleApplier.saveMapping(unit, request.organizationId(), request.programId(), run,
                request.runKind() != null ? request.runKind() : suggestedRunKind(unit), profile.id(), now);
    }

    // Без дат потока сопоставление не сохраняется: по датам наблюдение относится к потоку
    // (MoodleSnapshotApplier.publish).
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

    // Запуск, оставшийся «в процессе» от прежнего процесса, не завершится никогда и блокировал бы источник. Только в
    // веб-контексте (тесты и утилиты чужие запуски не трогают) и только созданные до старта этого процесса.
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
        // Захват атомарный (PENDING → RUNNING): запуск, уже взятый другим исполнителем, второй раз не стартует. Курсор
        // «обновлено с» фиксируется в запуске; продвигается он только после успешной загрузки, поэтому сбой не
        // пропускает записи.
        if (!repository.claimRun(runId, updatedSince, OffsetDateTime.now())) {
            return;
        }
        try {
            if (source == SourceCode.MOODLE) {
                runMoodle(runId, stored.startedBy(), scope);
            } else if (source == SourceCode.LABOR_MARKET) {
                runLaborMarket(runId, stored.startedBy());
            } else if (source == SourceCode.CUSTOMER_CRM) {
                crmExchange.run(runId, updatedSince, stored.startedBy());
            } else {
                runWebsite(runId, source, updatedSince, stored.startedBy(), siteOrganizationId);
            }
        } catch (SourceFetchException exception) {
            // Текст ошибки источника безопасен для показа; для остальных исключений в запуск пишется общее сообщение, а
            // подробности остаются в журнале сервера.
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
        SiteResponse response = siteApiClient.fetch(settings.website(), updatedSince);
        if (response.paidOrders() != null) {
            PaidOrderBatch batch = response.paidOrders();
            if (!paidOrderEnrolment.enabled()) {
                repository.completeRun(runId, totals(0, new int[SyncOutcome.values().length]),
                        "Оплаты физлиц не принимаются: модуль «Слушатели» выключен", OffsetDateTime.now());
            } else if (organizationId == null) {
                PaidOrdersApplied applied = applyPaidOrders(batch, runId, actorProfileId, null);
                repository.completeRun(runId, applied.totals(), paidOrdersMessage(batch), OffsetDateTime.now());
                if (!batch.orders().isEmpty()) {
                    paidOrderEnrolment.journalSync(runId, applied.intake());
                }
            } else {
                repository.completeRun(runId, totals(0, new int[SyncOutcome.values().length]),
                        "Сайт вернул оплаты физлиц; заявок этой организации в ответе нет", OffsetDateTime.now());
            }
            return;
        }
        List<SiteRecord> fetched = response.records();
        // Обновление из карточки берёт только записи этого вуза и не двигает курсор updatedSince: иначе общая загрузка
        // пропустила бы записи других вузов.
        List<SiteRecord> items = organizationId == null
                ? fetched
                : fetched.stream()
                        .filter(SiteRecord::storable)
                        .filter(item -> applier.resolvedOrganizationId(item).filter(organizationId::equals).isPresent())
                        .toList();
        SyncTotals totals = applyAll(items, runId, actorProfileId);
        // Записи без обязательных полей считаются ошибками в итогах, а сообщение запуска называет их число.
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
                // Курсор — наибольший updatedAt среди сохранённых записей; несохранённые его не двигают.
                .max(OffsetDateTime::compareTo)
                .ifPresent(latest -> repository.advanceUpdatedSince(source, latest, OffsetDateTime.now()));
    }

    private void runLaborMarket(UUID runId, UUID actorProfileId) {
        LaborMarketSync.Result result = laborMarketSync.run(runId, actorProfileId);
        repository.completeRun(runId, result.totals(), result.message(), OffsetDateTime.now());
    }

    private void runMoodle(UUID runId, UUID actorProfileId, LearningScope scope) {
        SourceProperties.Moodle moodle = settings.moodle();
        // Поток группы определяется на сегодня по МСК, как и дата наблюдения при применении.
        LocalDate today = LocalDate.now(ZONE);
        MoodleClient.Fetched fetched = moodleClient.fetch(moodle, scope == null ? moodle.courseIds() : scope.courseIds(),
                groupKey -> moodleApplier.groupFlowKey(groupKey, today).orElse(null));
        List<LearningUnit> units = scope == null
                ? fetched.units()
                : fetched.units().stream().filter(unit -> scope.covers(repository, unit)).toList();
        // Одно время наблюдения на весь запуск: оно входит в ключ идемпотентности событий и в версию записей.
        OffsetDateTime observedAt = OffsetDateTime.now();
        int[] counts = new int[SyncOutcome.values().length];
        for (LearningUnit unit : units) {
            // Ошибка одной записи не прерывает запуск: она фиксируется на записи, остальные применяются.
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
        for (MoodleClient.CourseFailure failure : fetched.failures()) {
            log.warn("Moodle course {} was not read in run {}: {}", failure.courseId(), runId, failure.error().code());
            // Записи курса помечаются ошибкой, а их прежние числа и снимки остаются: ошибка одного курса не стирает данные.
            moodleApplier.markCourseFailed(Long.toString(failure.courseId()), "Курс не обновлён: " + failure.error().getMessage(),
                    runId);
        }
        counts[SyncOutcome.FAILED.ordinal()] += fetched.failures().size();
        repository.completeRun(runId, totals(units.size() + fetched.failures().size(), counts),
                partialMessage(fetched.failures()), OffsetDateTime.now());
    }

    // Частичный запуск завершается как успешный: данные прочитанных курсов применены, сообщение называет не обновлённые
    // курсы.
    private static String partialMessage(List<MoodleClient.CourseFailure> failures) {
        if (failures.isEmpty()) {
            return null;
        }
        return "Частично: не обновлены курсы " + failures.stream().map(failure -> Long.toString(failure.courseId()))
                .collect(Collectors.joining(", ")) + "; их прежние данные сохранены. Причины: "
                + failures.stream().map(failure -> failure.courseId() + " — " + failure.error().getMessage())
                .collect(Collectors.joining("; "));
    }

    private SyncTotals applyAll(List<SiteRecord> items, UUID runId, UUID actorProfileId) {
        int[] counts = new int[SyncOutcome.values().length];
        for (SiteRecord item : items) {
            SyncOutcome outcome = item.storable() ? applyOne(item, runId, actorProfileId) : SyncOutcome.FAILED;
            counts[outcome.ordinal()]++;
        }
        return totals(items.size(), counts);
    }

    private PaidOrdersApplied applyPaidOrders(PaidOrderBatch batch, UUID runId, UUID actorProfileId, UUID learnerActorId) {
        OffsetDateTime receivedAt = OffsetDateTime.now();
        int[] counts = new int[SyncOutcome.values().length];
        LearnerIntake intake = LearnerIntake.NONE;
        for (PaidOrder order : batch.orders()) {
            SiteRecord item = SiteRecord.paidOrder(order.orderNumber(), order.course(), order.streamNumber(), order.version(), receivedAt);
            PaidOrderApplied applied = applyPaidOrder(item, order, runId, actorProfileId, learnerActorId);
            counts[applied.outcome().ordinal()]++;
            intake = intake.plus(applied.intake());
        }
        counts[SyncOutcome.SKIPPED.ordinal()] += batch.duplicates();
        counts[SyncOutcome.FAILED.ordinal()] += batch.rejected();
        return new PaidOrdersApplied(totals(batch.received(), counts), intake);
    }

    private PaidOrderApplied applyPaidOrder(SiteRecord item, PaidOrder order, UUID runId, UUID actorProfileId, UUID learnerActorId) {
        try {
            return applier.applyPaidOrder(item, order, runId, actorProfileId, learnerActorId);
        } catch (RuntimeException exception) {
            log.warn("Paid order record {} was not applied", item.externalId(), exception);
            applier.recordFailure(item, runId, failureMessage(exception));
            return new PaidOrderApplied(SyncOutcome.FAILED, LearnerIntake.NONE);
        }
    }

    private static String paidOrdersMessage(PaidOrderBatch batch) {
        List<String> parts = new ArrayList<>();
        if (batch.emptyElements() > 0) {
            parts.add("пустых элементов пропущено: " + batch.emptyElements());
        }
        if (batch.duplicates() > 0) {
            parts.add("повторов заявки в файле пропущено: " + batch.duplicates());
        }
        if (batch.rejected() > 0) {
            parts.add("записей не принято из-за ошибок в номере заявки, курсе или номере потока: " + batch.rejected());
        }
        return parts.isEmpty() ? null : "Оплаты в формате организатора: " + String.join("; ", parts);
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
        SourceRecordApplier recordApplier = source == SourceCode.CUSTOMER_CRM ? crmApplier : applier;
        try {
            return moodle ? moodleApplier.reapply(recordId, actorProfileId) : recordApplier.reapply(recordId, actorProfileId);
        } catch (RuntimeException exception) {
            log.warn("Source record {} was not applied again", recordId, exception);
            if (moodle) {
                moodleApplier.markFailed(recordId, failureMessage(exception));
            } else {
                recordApplier.markFailed(recordId, failureMessage(exception));
            }
            return SourceRecordStatus.FAILED;
        }
    }

    // Текст исключения показывается только для ошибок проверки; остальные могут содержать внутренние данные.
    private static String failureMessage(RuntimeException exception) {
        return exception instanceof InteractionValidationException
                ? "Запись не применена: " + exception.getMessage()
                : "Запись не применена из-за внутренней ошибки; подробности в журнале сервера";
    }

    private SourceView view(SourceCode source) {
        OffsetDateTime lastSuccessAt = repository.findLastFullSuccessAt(source).orElse(null);
        String schedule = settings.syncCron();
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
                source != SourceCode.MOODLE,
                repository.findUpdatedSince(source).orElse(null),
                lastSuccessAt,
                repository.countProblems(source),
                repository.findLatestFullRun(source).orElse(null),
                schedule,
                nextRunAt,
                stale(source, lastSuccessAt)
        );
    }

    // Вид потока, который можно предложить по названию курса: курсы преподавателей помечены «ППС». Решает администратор.
    private RunKind suggestedRunKind(LearningUnit unit) {
        return settings.moodle().teacherCourse(unit.courseShortname(), unit.courseName()) ? RunKind.TEACHERS : null;
    }

    SourceRecordView recordView(StoredRecord stored) {
        String organizationExternalId = null;
        String organizationName;
        String programName = null;
        Integer streamNo = null;
        String contactName = null;
        RunKind suggestedRunKind = null;
        if (stored.source() == SourceCode.MOODLE) {
            LearningUnit unit = LearningUnit.parseStored(objectMapper, stored.payload());
            organizationName = unit.label();
            suggestedRunKind = suggestedRunKind(unit);
        } else {
            SiteRecord item = SiteRecord.parseStored(objectMapper, stored.payload());
            organizationExternalId = item.organizationExternalId();
            organizationName = item.organizationName();
            programName = item.programName();
            streamNo = item.streamNo();
            contactName = stored.source() == SourceCode.CUSTOMER_CRM ? item.contactName() : null;
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
                streamNo,
                stored.organizationId(),
                stored.programId(),
                stored.interactionId(),
                stored.updatedAt(),
                contactName,
                crmApplier.candidates(stored),
                suggestedRunKind
        );
    }

    private record PaidOrdersApplied(SyncTotals totals, LearnerIntake intake) {
    }

    private record LearningScope(UUID organizationId, UUID programId, List<Long> courseIds) {
        // Запись входит в область, если у неё есть сопоставление именно с этим вузом и программой: курс может быть
        // сопоставлен и с другими.
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
