package ru.rtk.crm.interaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class WorkflowRolloutTest {
    private final UUID start = UUID.randomUUID();
    private final UUID check = UUID.randomUUID();
    private final UUID finish = UUID.randomUUID();
    private final UUID signing = UUID.randomUUID();

    private final WorkflowRoute source = route(
            List.of(stage(start, "Старт", 0), stage(check, "Проверка", 1), stage(finish, "Финиш", 2))
    );

    @Test
    void keepsIdentityRenamesAddsAndMovesTheCurrentStageWithItsName() {
        List<WorkflowRollout.RouteStage> work = snapshot(source);
        WorkflowRoute target = route(List.of(
                stage(start, "Старт", 0),
                stage(check, "Согласование", 1),
                stage(signing, "Подписание", 2),
                stage(finish, "Финиш", 3)
        ));

        WorkflowRollout.Plan plan = WorkflowRollout.plan(work, transitions(work), work.get(1).id(), source, target, Map.of());

        assertThat(plan.edited()).isFalse();
        assertThat(plan.merged()).isEmpty();
        assertThat(plan.currentStageId()).isEqualTo(work.get(1).id());
        assertThat(plan.currentStageName()).isEqualTo("Согласование");
        assertThat(plan.currentStageRemoved()).isFalse();
        assertThat(plan.stages()).extracting(WorkflowRollout.RouteStage::name)
                .containsExactly("Старт", "Согласование", "Подписание", "Финиш");
        assertThat(plan.stages()).extracting(WorkflowRollout.RouteStage::templateStageId)
                .containsExactly(start, check, signing, finish);
        assertThat(plan.transitions()).hasSize(3);
        assertThat(plan.changes()).containsExactly(
                "текущий этап «Проверка» → «Согласование»",
                "добавлен этап «Подписание»",
                "добавлен переход «Согласование» → «Подписание»",
                "добавлен переход «Подписание» → «Финиш»",
                "убран переход «Согласование» → «Финиш»"
        );
    }

    @Test
    void removedCurrentStageGoesToThePreviousKeptStageOrToTheManualChoiceAndIsMerged() {
        List<WorkflowRollout.RouteStage> work = snapshot(source);
        WorkflowRoute target = route(List.of(stage(start, "Старт", 0), stage(finish, "Финиш", 1)));

        WorkflowRollout.Plan automatic = WorkflowRollout.plan(work, transitions(work), work.get(1).id(), source, target, Map.of());
        WorkflowRollout.Plan manual = WorkflowRollout.plan(
                work, transitions(work), work.get(1).id(), source, target, Map.of(check, start)
        );

        // Удалённый текущий этап по умолчанию уходит назад, а не вперёд на финальный этап, откуда переходов нет.
        assertThat(automatic.currentStageId()).isEqualTo(work.get(0).id());
        assertThat(automatic.currentStageRemoved()).isTrue();
        assertThat(automatic.landsOnFinal()).isFalse();
        assertThat(automatic.merged()).containsExactly(Map.entry(work.get(1).id(), work.get(0).id()));
        assertThat(automatic.changes()).containsExactly("текущий этап «Проверка» → «Старт»");
        WorkflowRollout.Plan toFinal = WorkflowRollout.plan(
                work, transitions(work), work.get(1).id(), source, target, Map.of(check, finish)
        );
        assertThat(toFinal.landsOnFinal()).isTrue();
        assertThat(toFinal.changes()).contains("карточка окажется на финальном этапе «Финиш»");
        assertThat(manual.currentStageId()).isEqualTo(work.get(0).id());
        assertThat(manual.merged()).containsExactly(Map.entry(work.get(1).id(), work.get(0).id()));
        assertThat(WorkflowRollout.map(WorkflowRollout.asRoute(source), target, Map.of()))
                .extracting(WorkflowRollout.Mapped::match)
                .containsExactly(WorkflowStageMatch.SAME, WorkflowStageMatch.REMOVED, WorkflowStageMatch.SAME);
    }

    @Test
    void matchesByNameWhenIdentifiersDifferAndManualChoiceCreatesTheNewStageFromTheOldOne() {
        List<WorkflowRollout.RouteStage> work = snapshot(source);
        UUID otherStart = UUID.randomUUID();
        UUID otherFinish = UUID.randomUUID();
        WorkflowRoute imported = route(List.of(
                stage(otherStart, "Старт", 0),
                stage(signing, "Подписание", 1),
                stage(otherFinish, "Финиш", 2)
        ));

        WorkflowRollout.Plan plan = WorkflowRollout.plan(
                work, transitions(work), work.get(1).id(), source, imported, Map.of(check, signing)
        );

        assertThat(WorkflowRollout.map(work, imported, Map.of(check, signing)))
                .extracting(WorkflowRollout.Mapped::match)
                .containsExactly(WorkflowStageMatch.BY_NAME, WorkflowStageMatch.MANUAL, WorkflowStageMatch.BY_NAME);
        assertThat(plan.merged()).isEmpty();
        assertThat(plan.stages()).extracting(WorkflowRollout.RouteStage::id)
                .containsExactly(work.get(0).id(), work.get(1).id(), work.get(2).id());
        assertThat(plan.currentStageName()).isEqualTo("Подписание");
        assertThat(plan.currentStageRemoved()).isTrue();
    }

    @Test
    void localCardEditsMarkTheWorkAsEdited() {
        List<WorkflowRollout.RouteStage> work = new ArrayList<>(snapshot(source));
        work.set(1, new WorkflowRollout.RouteStage(work.get(1).id(), "Проверка у ректора", 1, false, check));
        List<WorkflowRollout.RouteStage> added = new ArrayList<>(snapshot(source));
        added.add(new WorkflowRollout.RouteStage(UUID.randomUUID(), "Своя встреча", 3, true, null));

        assertThat(WorkflowRollout.edited(snapshot(source), transitions(snapshot(source)), source)).isFalse();
        assertThat(WorkflowRollout.edited(work, transitions(work), source)).isTrue();
        assertThat(WorkflowRollout.edited(added, transitions(snapshot(source)), source)).isTrue();
        assertThat(WorkflowRollout.plan(work, transitions(work), work.get(0).id(), source, source, Map.of()).changes())
                .contains("этап «Проверка у ректора» переименован в «Проверка»");
    }

    @Test
    void previewShowsRemovedTransitionsFlagsARouteWithoutExitAndCarriesStageProperties() {
        List<WorkflowRollout.RouteStage> work = snapshot(source);
        // Версия-«ловушка»: из «Проверки» можно только назад, к «Финишу» переход пропал.
        WorkflowRoute trap = new WorkflowRoute(List.of(
                new WorkflowTemplateStage(start, "Старт", 0, false),
                new WorkflowTemplateStage(check, "Проверка", 1, false, true, StageLmsRole.CLASSES, List.of("ACT")),
                stage(finish, "Финиш", 2)
        ), List.of(
                new WorkflowTemplateTransition(start, check, false),
                new WorkflowTemplateTransition(check, start, true)
        ));

        WorkflowRollout.Plan plan = WorkflowRollout.plan(work, transitions(work), work.get(1).id(), source, trap, Map.of());

        assertThat(plan.changes()).contains(
                "добавлен переход «Проверка» → «Старт»",
                "убран переход «Проверка» → «Финиш»",
                "с этапа «Проверка» нельзя дойти до финального этапа"
        );
        assertThat(plan.noExit()).isTrue();
        assertThat(plan.stages().get(1).asStage())
                .satisfies(stage -> {
                    assertThat(stage.requiresAgreement()).isTrue();
                    assertThat(stage.lmsRole()).isEqualTo(StageLmsRole.CLASSES);
                    assertThat(stage.documentKinds()).containsExactly("ACT");
                });
        assertThat(WorkflowRollout.plan(work, transitions(work), work.get(1).id(), source, source, Map.of()).noExit()).isFalse();
    }

    // Н-РТ-4 (2.5): переходы удалённого или объединённого этапа не называются «убранными» после подстановки — у
    // карточки не было ни «Старт» → «Финиш», ни «Финиш» → «Работа».
    @Test
    void previewDoesNotNameTransitionsTheWorkNeverHad() {
        UUID work4 = UUID.randomUUID();
        WorkflowRoute linear = route(List.of(stage(start, "Старт", 0), stage(check, "Проверка", 1), stage(work4, "Работа", 2),
                stage(finish, "Финиш", 3)));
        List<WorkflowTemplateTransition> withSkip = new ArrayList<>(linear.transitions());
        withSkip.add(new WorkflowTemplateTransition(check, finish, true));
        WorkflowRoute four = new WorkflowRoute(linear.stages(), withSkip);
        List<WorkflowRollout.RouteStage> work = snapshot(four);
        List<InteractionStageTransition> edges = new ArrayList<>(transitions(work));
        edges.add(new InteractionStageTransition(work.get(1).id(), work.get(3).id(), true));
        WorkflowRoute without = route(List.of(stage(start, "Старт", 0), stage(work4, "Работа", 1), stage(finish, "Финиш", 2)));

        WorkflowRollout.Plan automatic = WorkflowRollout.plan(work, edges, work.get(1).id(), four, without, Map.of());
        WorkflowRollout.Plan toFinal = WorkflowRollout.plan(work, edges, work.get(1).id(), four, without, Map.of(check, finish));

        assertThat(automatic.changes()).containsExactly("текущий этап «Проверка» → «Старт»");
        assertThat(toFinal.changes()).noneMatch(change -> change.startsWith("убран переход"));
        assertThat(toFinal.changes()).contains("карточка окажется на финальном этапе «Финиш»");
    }

    private static WorkflowTemplateStage stage(UUID id, String name, int order) {
        return new WorkflowTemplateStage(id, name, order, false);
    }

    private static WorkflowRoute route(List<WorkflowTemplateStage> stages) {
        List<WorkflowTemplateTransition> transitions = new ArrayList<>();
        for (int index = 1; index < stages.size(); index++) {
            transitions.add(new WorkflowTemplateTransition(stages.get(index - 1).id(), stages.get(index).id(), false));
        }
        return new WorkflowRoute(stages, transitions);
    }

    private static List<WorkflowRollout.RouteStage> snapshot(WorkflowRoute route) {
        return route.stages().stream()
                .map(stage -> new WorkflowRollout.RouteStage(
                        UUID.nameUUIDFromBytes(stage.id().toString().getBytes()),
                        stage.name(),
                        stage.order(),
                        stage.optional(),
                        stage.id()
                ))
                .toList();
    }

    private static List<InteractionStageTransition> transitions(List<WorkflowRollout.RouteStage> work) {
        List<InteractionStageTransition> transitions = new ArrayList<>();
        for (int index = 1; index < work.size(); index++) {
            transitions.add(new InteractionStageTransition(work.get(index - 1).id(), work.get(index).id(), false));
        }
        return transitions;
    }
}
