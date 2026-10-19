package ru.rtk.crm.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementRepository;
import ru.rtk.crm.agreement.AgreementRepository.WorkAgreementStatus;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
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

class OrganizationPathServiceTest {
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID TEAM = UUID.randomUUID();
    private static final UUID OLD = UUID.randomUUID();
    private static final UUID NEW = UUID.randomUUID();

    private final InteractionService interactionService = mock(InteractionService.class);
    private final InteractionRepository interactionRepository = mock(InteractionRepository.class);
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final AgreementRepository agreementRepository = mock(AgreementRepository.class);
    private final LmsWorkLearningService learningService = mock(LmsWorkLearningService.class);
    private final TrainingRepository trainingRepository = mock(TrainingRepository.class);
    private final OrganizationPathService service = new OrganizationPathService(
            interactionService, interactionRepository, reportRepository, agreementRepository, learningService, trainingRepository);

    @Test
    void onlyKamLeaderAndManagementReadThePath() {
        for (UserRole role : List.of(UserRole.ADMIN, UserRole.PARTNER)) {
            assertThatThrownBy(() -> service.path(new CrmProfile(UUID.randomUUID(), role, TEAM, 0), ORGANIZATION))
                    .isInstanceOf(WorkAccessDeniedException.class);
        }
        verifyNoInteractions(interactionService);
    }

    @Test
    void foreignOrganizationIsNotFoundByTheSameRuleAsTheInteractionList() {
        CrmProfile kam = new CrmProfile(UUID.randomUUID(), UserRole.USER, TEAM, 0);
        when(interactionService.find(eq(kam), eq(ORGANIZATION), isNull(), isNull(), isNull(), eq("ALL"), isNull(), anyInt()))
                .thenThrow(new OrganizationNotFoundException());

        assertThatThrownBy(() -> service.path(kam, ORGANIZATION)).isInstanceOf(OrganizationNotFoundException.class);
    }

    @Test
    void pathShowsRoutePositionContractLearningAndLinkedCyclesNewestFirst() {
        CrmProfile leader = new CrmProfile(UUID.randomUUID(), UserRole.LEADER, TEAM, 0);
        OffsetDateTime now = OffsetDateTime.now();
        UUID oldFinal = UUID.randomUUID();
        UUID newSecond = UUID.randomUUID();
        InteractionSummary completed = summary(OLD, "Цикл 2025", oldFinal, InteractionWorkStatus.COMPLETED, null, null, now.minusDays(400));
        InteractionSummary active = summary(NEW, "Цикл 2026", newSecond, InteractionWorkStatus.ACTIVE, "Позвонить", now.minusDays(1),
                now.minusDays(20));
        when(interactionService.find(eq(leader), eq(ORGANIZATION), isNull(), isNull(), isNull(), eq("ALL"), isNull(), anyInt()))
                .thenReturn(new InteractionPage(List.of(completed, active), 0, 100, 2));
        when(interactionRepository.findStages(OLD)).thenReturn(List.of(
                new InteractionStage(UUID.randomUUID(), "Поиск контакта", 1, false),
                new InteractionStage(oldFinal, "Повышение квалификации", 2, false)));
        when(interactionRepository.findStages(NEW)).thenReturn(List.of(
                new InteractionStage(UUID.randomUUID(), "Поиск контакта", 1, false),
                new InteractionStage(newSecond, "Встреча", 2, true),
                new InteractionStage(UUID.randomUUID(), "Подписание", 3, false)));
        when(reportRepository.findManagerWorkProducts(any())).thenReturn(Map.of(NEW, List.of("Продукт А")));
        when(agreementRepository.findWorkStatuses(any())).thenReturn(Map.of(NEW,
                new WorkAgreementStatus(AgreementStatus.ACTIVE, "Д-1", LocalDate.parse("2027-12-31"), null, false)));
        when(learningService.byWork(eq(leader), any())).thenReturn(Map.of(OLD,
                new LmsSignalModels.WorkLearning(1, 12, 5, 0, List.of(), null)));
        CycleLink oldLink = new CycleLink(OLD, "Цикл 2025", null, now.minusDays(400));
        CycleLink newLink = new CycleLink(NEW, "Цикл 2026", LocalDate.parse("2026-09-01"), now.minusDays(20));
        when(trainingRepository.findNext(OLD)).thenReturn(Optional.of(newLink));
        when(trainingRepository.findPrevious(NEW)).thenReturn(Optional.of(oldLink));
        when(trainingRepository.findCycleStart(NEW)).thenReturn(Optional.of(LocalDate.parse("2026-09-01")));

        OrganizationPathService.Path path = service.path(leader, ORGANIZATION);

        assertThat(path.total()).isEqualTo(2);
        assertThat(path.works()).extracting(OrganizationPathService.PathWork::interactionId).containsExactly(NEW, OLD);
        OrganizationPathService.PathWork current = path.works().getFirst();
        assertThat(current.stagePosition()).isEqualTo(2);
        assertThat(current.stages()).extracting(OrganizationPathService.PathStage::optional).containsExactly(false, true, false);
        assertThat(current.daysOnStage()).isEqualTo(3);
        assertThat(current.due()).isEqualTo(ManagerWorkDue.OVERDUE);
        assertThat(current.products()).containsExactly("Продукт А");
        assertThat(current.agreementNumber()).isEqualTo("Д-1");
        assertThat(current.previous()).isEqualTo(oldLink);
        assertThat(current.next()).isNull();
        assertThat(current.cycleStartsOn()).isEqualTo(LocalDate.parse("2026-09-01"));
        OrganizationPathService.PathWork finished = path.works().getLast();
        assertThat(finished.stagePosition()).isEqualTo(2);
        assertThat(finished.daysOnStage()).as("у завершённой работы дни на этапе не считаются").isNull();
        assertThat(finished.due()).isEqualTo(ManagerWorkDue.IN_ORDER);
        assertThat(finished.next()).isEqualTo(newLink);
        assertThat(finished.learning().participants()).isEqualTo(12);
    }

    private static InteractionSummary summary(UUID id, String title, UUID stageId, InteractionWorkStatus status, String nextAction,
                                              OffsetDateTime nextActionAt, OffsetDateTime createdAt) {
        InteractionSummary summary = mock(InteractionSummary.class);
        when(summary.id()).thenReturn(id);
        when(summary.title()).thenReturn(title);
        when(summary.currentStageId()).thenReturn(stageId);
        when(summary.nextAction()).thenReturn(nextAction);
        when(summary.nextActionAt()).thenReturn(nextActionAt);
        when(summary.createdAt()).thenReturn(createdAt);
        when(summary.stageEnteredAt()).thenReturn(OffsetDateTime.now().minusDays(3).minusHours(1));
        when(summary.marks()).thenReturn(new InteractionMarks(status, null, null, null, 0, 0, null, null, null));
        return summary;
    }
}
