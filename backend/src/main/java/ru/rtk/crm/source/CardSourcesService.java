package ru.rtk.crm.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.source.SourceRepository.LearningCoverage;
import ru.rtk.crm.source.SourceRepository.MappedTarget;

@Service
public class CardSourcesService {
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter MOSCOW_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final OrganizationRepository organizationRepository;
    private final SourceRepository repository;
    private final SourceSyncService syncService;

    public CardSourcesService(
            OrganizationRepository organizationRepository,
            SourceRepository repository,
            SourceSyncService syncService
    ) {
        this.organizationRepository = organizationRepository;
        this.repository = repository;
        this.syncService = syncService;
    }

    public List<LearningSnapshotView> snapshots(CrmProfile profile, UUID interactionId) {
        return repository.findInteractionSnapshots(interactionId, visibleScope(profile, interactionId)).stream()
                .map(snapshot -> new LearningSnapshotView(
                        snapshot.mappingId(),
                        snapshot.runKind(),
                        snapshot.unit().courseId(),
                        snapshot.unit().courseName(),
                        snapshot.unit().groupId(),
                        snapshot.unit().groupName(),
                        snapshot.unit().participants(),
                        snapshot.unit().teachers(),
                        snapshot.unit().completed(),
                        snapshot.unit().notCompleted(),
                        snapshot.unit().unknown(),
                        snapshot.unit().groupsCount(),
                        snapshot.runStartsOn(),
                        snapshot.runEndsOn(),
                        snapshot.observedAt(),
                        snapshot.changedAt()
                ))
                .toList();
    }

    public LearningSnapshotsRefresh refreshLearning(CrmProfile profile, UUID interactionId) {
        VisibilityScope scope = visibleScope(profile, interactionId);
        SyncRunView run = syncService.refreshLearning(profile, scope, interactionId);
        return new LearningSnapshotsRefresh(run, snapshots(profile, interactionId));
    }

    public InteractionSourceStatus status(CrmProfile profile, UUID interactionId) {
        visibleScope(profile, interactionId);
        return status(interactionId, snapshots(profile, interactionId));
    }

    public SourcesRefresh refresh(CrmProfile profile, UUID interactionId) {
        VisibilityScope scope = visibleScope(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        RefreshOutcome lms = refreshLms(profile, scope, interactionId);
        RefreshOutcome site = refreshSite(profile, scope, interactionId);
        List<LearningSnapshotView> snapshots = snapshots(profile, interactionId);
        return new SourcesRefresh(lms, site, status(interactionId, snapshots), snapshots);
    }

    private RefreshOutcome refreshLms(CrmProfile profile, VisibilityScope scope, UUID interactionId) {
        if (repository.findInteractionProgramTarget(interactionId).isEmpty()) {
            return new RefreshOutcome("SKIPPED", 0, "Программа не указана, поэтому данные LMS не запрашивались");
        }
        try {
            SyncRunView run = syncService.refreshLearning(profile, scope, interactionId);
            int changed = run.createdCount() + run.updatedCount();
            return changed == 0
                    ? new RefreshOutcome("UNCHANGED", 0, "Данные Moodle проверены, изменений нет.")
                    : new RefreshOutcome("UPDATED", changed, "Данные Moodle обновлены: изменилось курсов и групп — " + changed
                            + ". Событие добавлено в историю.");
        } catch (SourceException exception) {
            String status = exception.code().equals("LMS_SYNC_FAILED") ? "FAILED" : "SKIPPED";
            return new RefreshOutcome(status, 0, exception.getMessage());
        }
    }

    private RefreshOutcome refreshSite(CrmProfile profile, VisibilityScope scope, UUID interactionId) {
        try {
            SyncRunView run = syncService.refreshSite(profile, scope, interactionId);
            if (run.status() != SyncRunStatus.SUCCEEDED) {
                return new RefreshOutcome("FAILED", 0, "Данные сайта не обновлены, прежние значения сохранены: "
                        + (run.errorMessage() == null ? "ошибка синхронизации" : run.errorMessage()));
            }
            int changed = run.createdCount() + run.updatedCount();
            String waiting = run.needsMappingCount() == 0 ? ""
                    : " Ждут сопоставления программы: " + run.needsMappingCount() + " (разбирает администратор).";
            return changed == 0
                    ? new RefreshOutcome("UNCHANGED", 0, "Новых и изменённых заявок сайта по этому вузу нет." + waiting)
                    : new RefreshOutcome("UPDATED", changed, "Заявки сайта по этому вузу применены: " + changed + "." + waiting);
        } catch (SourceException exception) {
            return new RefreshOutcome("SKIPPED", 0, exception.getMessage());
        }
    }

    private InteractionSourceStatus status(UUID interactionId, List<LearningSnapshotView> snapshots) {
        UUID organizationId = repository.findInteractionOrganizationId(interactionId).orElseThrow(InteractionNotFoundException::new);
        OffsetDateTime observedAt = snapshots.stream().map(LearningSnapshotView::observedAt).max(Comparator.naturalOrder())
                .orElse(null);
        SourceState lms = sourceState(SourceCode.MOODLE, organizationId, observedAt);
        SourceState site = sourceState(SourceCode.WEBSITE, organizationId, null);
        return new InteractionSourceStatus(lms, site, learningState(interactionId, snapshots, lms));
    }

    private SourceState sourceState(SourceCode source, UUID organizationId, OffsetDateTime dataObservedAt) {
        OffsetDateTime lastSuccessAt = repository.findLastSuccessAtFor(source, organizationId).orElse(null);
        Optional<SyncRunView> failed = repository.findLatestFinishedRunFor(source, organizationId)
                .filter(run -> run.status() == SyncRunStatus.FAILED);
        return new SourceState(
                syncService.configured(source),
                lastSuccessAt,
                failed.map(SyncRunView::finishedAt).orElse(null),
                failed.map(SyncRunView::errorCode).orElse(null),
                failed.map(SyncRunView::errorMessage).orElse(null),
                syncService.stale(source, lastSuccessAt),
                dataObservedAt
        );
    }

    private LearningState learningState(UUID interactionId, List<LearningSnapshotView> snapshots, SourceState lms) {
        Optional<MappedTarget> target = repository.findInteractionProgramTarget(interactionId);
        if (target.isEmpty()) {
            return new LearningState("NO_PROGRAM", "Программа не указана, поэтому данные LMS не сопоставляются с этой работой."
                    + " Укажите программу в блоке «Следующий шаг».");
        }
        if (!snapshots.isEmpty()) {
            return new LearningState("AVAILABLE", null);
        }
        if (!lms.configured()) {
            return new LearningState("NOT_CONFIGURED", "LMS не подключена: адрес, токен и курсы Moodle не заданы в конфигурации"
                    + " CRM. Обратитесь к администратору CRM.");
        }
        LearningCoverage coverage = repository.findLearningCoverage(interactionId);
        if (coverage.mapped() == 0) {
            return new LearningState("NOT_MAPPED", "Курсы и группы Moodle для программы этого вуза не сопоставлены."
                    + " Сопоставление выполняет администратор CRM в разделе «Источники данных».");
        }
        if (coverage.inCycle() == 0) {
            return new LearningState("OTHER_CYCLE", "Потоки Moodle этой программы относятся к другому циклу работы; поток"
                    + " для этого цикла ещё не сопоставлен. Обратитесь к администратору CRM.");
        }
        if (coverage.undated() == coverage.mapped()) {
            return new LearningState("NOT_DATED", "Сопоставление не подтверждено: у потока не указаны даты начала и окончания."
                    + " Обратитесь к администратору CRM.");
        }
        if (lms.errorAt() != null) {
            return new LearningState("SOURCE_FAILED", "Синхронизация LMS " + time(lms.errorAt()) + " завершилась ошибкой: "
                    + lms.errorMessage() + ". Обновите данные позже или обратитесь к администратору CRM.");
        }
        if (coverage.skippedReason() != null) {
            return new LearningState("RUN_ENDED", coverage.skippedReason() + ".");
        }
        return new LearningState("NOT_SYNCED", "Курсы сопоставлены, но ещё не синхронизированы. Нажмите «Обновить данные"
                + " источников» или дождитесь плановой синхронизации.");
    }

    private VisibilityScope visibleScope(CrmProfile profile, UUID interactionId) {
        VisibilityScope scope = organizationRepository.visibilityScope(profile).orElseThrow(InteractionNotFoundException::new);
        if (!repository.interactionVisible(interactionId, scope)) {
            throw new InteractionNotFoundException();
        }
        return scope;
    }

    private static String time(OffsetDateTime value) {
        return MOSCOW_TIME.format(value.atZoneSameInstant(ZONE)) + " (МСК)";
    }

    public record LearningSnapshotsRefresh(SyncRunView run, List<LearningSnapshotView> snapshots) {
    }

    public record LearningSnapshotView(
            UUID mappingId,
            RunKind runKind,
            long courseId,
            String courseName,
            Long groupId,
            String groupName,
            int participants,
            int teachers,
            Integer completed,
            Integer notCompleted,
            int unknown,
            int groupsCount,
            LocalDate runStartsOn,
            LocalDate runEndsOn,
            OffsetDateTime observedAt,
            OffsetDateTime changedAt
    ) {
    }

    public record SourceState(
            boolean configured,
            OffsetDateTime lastSuccessAt,
            OffsetDateTime errorAt,
            String errorCode,
            String errorMessage,
            boolean stale,
            OffsetDateTime dataObservedAt
    ) {
    }

    public record LearningState(String state, String message) {
    }

    public record InteractionSourceStatus(SourceState lms, SourceState site, LearningState learning) {
    }

    public record RefreshOutcome(String status, int changedCount, String message) {
    }

    public record SourcesRefresh(
            RefreshOutcome lms,
            RefreshOutcome site,
            InteractionSourceStatus status,
            List<LearningSnapshotView> snapshots
    ) {
    }
}
