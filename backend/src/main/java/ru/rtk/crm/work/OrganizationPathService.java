package ru.rtk.crm.work;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementRepository;
import ru.rtk.crm.agreement.AgreementRepository.WorkAgreementStatus;
import ru.rtk.crm.interaction.InteractionMarks;
import ru.rtk.crm.interaction.InteractionPage;
import ru.rtk.crm.interaction.InteractionRepository;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionStage;
import ru.rtk.crm.interaction.InteractionSummary;
import ru.rtk.crm.interaction.InteractionWorkStatus;
import ru.rtk.crm.report.ManagerWorkDue;
import ru.rtk.crm.report.ReportRepository;
import ru.rtk.crm.training.CycleLink;
import ru.rtk.crm.training.TrainingRepository;
import ru.rtk.crm.work.LmsSignalModels.WorkLearning;

// «Путь вуза»: все взаимодействия вуза, включая завершённые, с маршрутом, договором, обучением и связью повторных циклов.
@Service
public class OrganizationPathService {
    // ponytail: одна страница списка взаимодействий; у вуза их единицы, при сотне понадобится постраничная выдача.
    private static final int MAX_WORKS = 100;
    private static final Set<UserRole> READERS = Set.of(UserRole.USER, UserRole.LEADER, UserRole.MANAGEMENT);

    private final InteractionService interactionService;
    private final InteractionRepository interactionRepository;
    private final ReportRepository reportRepository;
    private final AgreementRepository agreementRepository;
    private final LmsWorkLearningService lmsWorkLearningService;
    private final TrainingRepository trainingRepository;

    public OrganizationPathService(
            InteractionService interactionService,
            InteractionRepository interactionRepository,
            ReportRepository reportRepository,
            AgreementRepository agreementRepository,
            LmsWorkLearningService lmsWorkLearningService,
            TrainingRepository trainingRepository
    ) {
        this.interactionService = interactionService;
        this.interactionRepository = interactionRepository;
        this.reportRepository = reportRepository;
        this.agreementRepository = agreementRepository;
        this.lmsWorkLearningService = lmsWorkLearningService;
        this.trainingRepository = trainingRepository;
    }

    public record Path(UUID organizationId, OffsetDateTime calculatedAt, int total, List<PathWork> works) {
    }

    public record PathStage(String name, boolean optional, OffsetDateTime enteredAt) {
    }

    public record PathWork(
            UUID interactionId,
            String title,
            String kindName,
            InteractionWorkStatus status,
            UUID programId,
            String programName,
            List<String> products,
            List<PathStage> stages,
            String stageName,
            int stagePosition,
            Long daysOnStage,
            String nextAction,
            OffsetDateTime nextActionAt,
            ManagerWorkDue due,
            int problems,
            int risks,
            AgreementStatus agreementStatus,
            String agreementNumber,
            LocalDate agreementValidUntil,
            WorkLearning learning,
            LocalDate cycleStartsOn,
            CycleLink previous,
            CycleLink next,
            OffsetDateTime createdAt
    ) {
    }

    @Transactional(readOnly = true)
    public Path path(CrmProfile profile, UUID organizationId) {
        if (!READERS.contains(profile.role())) {
            throw new WorkAccessDeniedException();
        }
        // Список взаимодействий проверяет, что вуз виден пользователю: КАМ — свои вузы и замещаемые, руководитель — команда.
        InteractionPage page = interactionService.find(profile, organizationId, null, null, null, "ALL", null, MAX_WORKS);
        List<UUID> ids = page.items().stream().map(InteractionSummary::id).toList();
        Map<UUID, List<String>> products = reportRepository.findManagerWorkProducts(ids);
        Map<UUID, WorkAgreementStatus> agreements = agreementRepository.findWorkStatuses(ids);
        Map<UUID, WorkLearning> learning = lmsWorkLearningService.byWork(profile, ids);
        OffsetDateTime now = OffsetDateTime.now();
        List<PathWork> works = page.items().stream()
                .map(item -> work(item, products.getOrDefault(item.id(), List.of()), agreements.get(item.id()), learning.get(item.id()), now))
                .sorted(Comparator.comparing(PathWork::createdAt).reversed())
                .toList();
        return new Path(organizationId, now, (int) page.total(), works);
    }

    private PathWork work(InteractionSummary item, List<String> products, WorkAgreementStatus agreement, WorkLearning learning,
                          OffsetDateTime now) {
        List<InteractionStage> stages = interactionRepository.findStages(item.id());
        Map<UUID, OffsetDateTime> entries = interactionRepository.findStageEntries(item.id());
        int position = 0;
        for (int index = 0; index < stages.size(); index++) {
            if (stages.get(index).id().equals(item.currentStageId())) {
                position = index + 1;
            }
        }
        InteractionMarks marks = item.marks();
        InteractionWorkStatus status = marks == null ? InteractionWorkStatus.ACTIVE : marks.status();
        // У завершённой работы «дней на этапе» нет: счётчик продолжал бы расти без смысла.
        Long days = status == InteractionWorkStatus.COMPLETED || item.stageEnteredAt() == null
                ? null
                : Math.max(0, Duration.between(item.stageEnteredAt(), now).toDays());
        return new PathWork(
                item.id(),
                item.title(),
                item.kindName(),
                status,
                item.programId(),
                item.programName(),
                products,
                stages.stream().map(stage -> new PathStage(stage.name(), stage.optional(), entries.get(stage.id()))).toList(),
                item.currentStageName(),
                position,
                days,
                item.nextAction(),
                item.nextActionAt(),
                ManagerWorkDue.of(status, item.nextAction(), item.nextActionAt(), now),
                marks == null ? 0 : marks.problemCount(),
                marks == null ? 0 : marks.riskCount(),
                agreement == null ? null : agreement.status(),
                agreement == null ? null : agreement.number(),
                agreement == null ? null : agreement.validUntil(),
                learning,
                trainingRepository.findCycleStart(item.id()).orElse(null),
                trainingRepository.findPrevious(item.id()).orElse(null),
                trainingRepository.findNext(item.id()).orElse(null),
                item.createdAt()
        );
    }
}
