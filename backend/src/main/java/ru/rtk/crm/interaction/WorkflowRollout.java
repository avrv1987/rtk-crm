package ru.rtk.crm.interaction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class WorkflowRollout {
    private WorkflowRollout() {
    }

    // Сопоставление этапов работы с этапами новой версии шаблона. Порядок приоритета: ручной выбор руководителя,
    // затем тот же идентификатор этапа шаблона (имя могло смениться — «переименован»), затем то же название,
    // и лишь если этап никуда не попал — «удалён»: его работа переходит на ближайший сохранившийся этап.
    static List<Mapped> map(List<RouteStage> stages, WorkflowRoute target, Map<UUID, UUID> overrides) {
        List<WorkflowTemplateStage> targetStages = ordered(target);
        Map<UUID, WorkflowTemplateStage> targetById = new HashMap<>();
        Map<String, WorkflowTemplateStage> targetByName = new LinkedHashMap<>();
        for (WorkflowTemplateStage stage : targetStages) {
            targetById.put(stage.id(), stage);
            targetByName.putIfAbsent(stage.name(), stage);
        }
        Set<UUID> seen = new HashSet<>();
        List<UUID> keys = new ArrayList<>();
        Set<UUID> claimed = new HashSet<>();
        for (RouteStage stage : stages) {
            // Идентификатор этапа шаблона учитывается один раз: если в карточке этап продублировали, сопоставляется
            // только первая копия, остальные идут по названию или считаются удалёнными.
            UUID key = stage.templateStageId() != null && seen.add(stage.templateStageId()) ? stage.templateStageId() : null;
            keys.add(key);
            // «Заняты» этапы с прямым совпадением по идентификатору: по названию на них претендовать нельзя, иначе
            // два этапа слились бы из-за случайного совпадения имён.
            if (key != null && !overrides.containsKey(key) && targetById.containsKey(key)) {
                claimed.add(key);
            }
        }
        List<Mapped> primary = new ArrayList<>();
        for (int index = 0; index < stages.size(); index++) {
            RouteStage stage = stages.get(index);
            UUID key = keys.get(index);
            if (key != null && overrides.containsKey(key)) {
                primary.add(new Mapped(stage, targetById.get(overrides.get(key)), WorkflowStageMatch.MANUAL));
            } else if (key != null && targetById.containsKey(key)) {
                WorkflowTemplateStage same = targetById.get(key);
                primary.add(new Mapped(
                        stage,
                        same,
                        same.name().equals(stage.name()) ? WorkflowStageMatch.SAME : WorkflowStageMatch.RENAMED
                ));
            } else {
                WorkflowTemplateStage byName = targetByName.get(stage.name());
                primary.add(byName != null && !claimed.contains(byName.id())
                        ? new Mapped(stage, byName, WorkflowStageMatch.BY_NAME)
                        : null);
            }
        }
        List<Mapped> result = new ArrayList<>();
        for (int index = 0; index < stages.size(); index++) {
            Mapped mapped = primary.get(index);
            result.add(mapped != null ? mapped : new Mapped(
                    stages.get(index),
                    fallback(primary, index, targetStages.getFirst()),
                    WorkflowStageMatch.REMOVED
            ));
        }
        return result;
    }

    // План перевода одной работы. Идентификаторы этапов карточки сохраняются там, где этап сопоставлен, чтобы не
    // рвались ссылки в истории, файлах и отметках «выполнено»; новые этапы получают новые идентификаторы.
    static Plan plan(
            List<RouteStage> work,
            List<InteractionStageTransition> workTransitions,
            UUID currentStageId,
            WorkflowRoute source,
            WorkflowRoute target,
            Map<UUID, UUID> overrides
    ) {
        List<Mapped> mapped = map(work, target, overrides);
        // Несколько этапов работы могут претендовать на один этап новой версии. «Носителем» его идентификатора
        // становится самый надёжный: сначала совпадение по идентификатору, затем по названию или вручную, затем
        // «удалённые»; остальные объединяются в носителя (merged) вместо появления дубликата этапа.
        Map<UUID, Mapped> carriers = new HashMap<>();
        for (Set<WorkflowStageMatch> priority : List.of(
                Set.of(WorkflowStageMatch.SAME, WorkflowStageMatch.RENAMED),
                Set.of(WorkflowStageMatch.BY_NAME, WorkflowStageMatch.MANUAL),
                Set.of(WorkflowStageMatch.REMOVED)
        )) {
            for (Mapped item : mapped) {
                if (priority.contains(item.match())) {
                    carriers.putIfAbsent(item.target().id(), item);
                }
            }
        }
        Map<UUID, UUID> stageIds = new HashMap<>();
        List<RouteStage> stages = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<WorkflowTemplateStage> targetStages = ordered(target);
        for (int order = 0; order < targetStages.size(); order++) {
            WorkflowTemplateStage stage = targetStages.get(order);
            Mapped carrier = carriers.get(stage.id());
            UUID id = carrier == null ? UUID.randomUUID() : carrier.stage().id();
            if (carrier == null) {
                added.add("добавлен этап «" + stage.name() + "»");
            }
            stageIds.put(stage.id(), id);
            stages.add(new RouteStage(id, stage.name(), order, stage.optional(), stage.id(),
                    stage.requiresAgreement(), stage.lmsRole(), stage.documentKinds()));
        }
        List<InteractionStageTransition> transitions = target.transitions().stream()
                .map(transition -> new InteractionStageTransition(
                        stageIds.get(transition.fromStageId()),
                        stageIds.get(transition.toStageId()),
                        transition.commentRequired()
                ))
                .toList();
        Mapped current = mapped.stream()
                .filter(item -> item.stage().id().equals(currentStageId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Current stage is not part of the route"));
        // Текущий этап не сохранён, если его место занял другой носитель; тогда работа встаёт на этап-носитель, и
        // в предпросмотре это отмечается для подтверждения.
        boolean currentKept = carriers.get(current.target().id()) == current;
        List<String> changes = new ArrayList<>();
        if (!currentKept || !current.target().name().equals(current.stage().name())) {
            changes.add("текущий этап «" + current.stage().name() + "» → «" + current.target().name() + "»");
        }
        Map<UUID, UUID> merged = new LinkedHashMap<>();
        for (Mapped item : mapped) {
            if (carriers.get(item.target().id()) != item) {
                merged.put(item.stage().id(), stageIds.get(item.target().id()));
                if (item != current) {
                    changes.add(item.match() == WorkflowStageMatch.REMOVED
                            ? "удалён этап «" + item.stage().name() + "»"
                            : "этап «" + item.stage().name() + "» объединён с «" + item.target().name() + "»");
                }
            } else if (item != current && !item.target().name().equals(item.stage().name())) {
                changes.add("этап «" + item.stage().name() + "» переименован в «" + item.target().name() + "»");
            }
        }
        changes.addAll(added);
        // Переходы сравниваются по названиям этапов назначения: убранный переход к финалу так же важен, как удалённый
        // этап, и без этой строки откат к «ловушке» выглядел бы как «изменений нет».
        Map<UUID, String> targetName = new HashMap<>();
        mapped.forEach(item -> targetName.put(item.stage().id(), item.target().name()));
        // «Убран» говорится только о переходах между сохранившимися этапами. Переход удалённого или объединённого этапа
        // после подстановки выглядел бы переходом, которого у карточки не было (Б → Г при Б → А — «убран А → Г»,
        // Н-РТ-4); об этих этапах уже сказано строкой «удалён этап» или «объединён».
        Set<UUID> kept = new java.util.HashSet<>();
        mapped.stream().filter(item -> carriers.get(item.target().id()) == item).forEach(item -> kept.add(item.stage().id()));
        Set<String> keptBefore = new java.util.LinkedHashSet<>();
        Map<UUID, String> newName = new HashMap<>();
        targetStages.forEach(stage -> newName.put(stageIds.get(stage.id()), stage.name()));
        Set<String> before = new java.util.LinkedHashSet<>();
        for (InteractionStageTransition transition : workTransitions) {
            String from = targetName.get(transition.fromStageId());
            String to = targetName.get(transition.toStageId());
            if (from != null && to != null && !from.equals(to)) {
                before.add("«" + from + "» → «" + to + "»");
                if (kept.contains(transition.fromStageId()) && kept.contains(transition.toStageId())) {
                    keptBefore.add("«" + from + "» → «" + to + "»");
                }
            }
        }
        Set<String> after = new java.util.LinkedHashSet<>();
        transitions.forEach(transition -> after.add(
                "«" + newName.get(transition.fromStageId()) + "» → «" + newName.get(transition.toStageId()) + "»"));
        after.stream().filter(edge -> !before.contains(edge)).forEach(edge -> changes.add("добавлен переход " + edge));
        keptBefore.stream().filter(edge -> !after.contains(edge)).forEach(edge -> changes.add("убран переход " + edge));
        UUID targetCurrent = stageIds.get(current.target().id());
        List<InteractionStage> targetRoute = stages.stream().map(RouteStage::asStage).toList();
        boolean noExit = WorkflowGraph.stagesWithoutPathToFinal(targetRoute, transitions).stream()
                .anyMatch(stage -> stage.id().equals(targetCurrent));
        if (noExit) {
            changes.add("с этапа «" + current.target().name() + "» нельзя дойти до финального этапа");
        }
        boolean moved = !currentKept || current.match() == WorkflowStageMatch.REMOVED || current.match() == WorkflowStageMatch.MANUAL;
        boolean landsOnFinal = moved && current.target().id().equals(targetStages.getLast().id());
        if (landsOnFinal) {
            changes.add("карточка окажется на финальном этапе «" + current.target().name() + "»");
        }
        // Если этапы в карточке правили вручную, перевод на версию их заменит; без явного подтверждения
        // руководителя применение такой работы отклоняется (replaceLocalChanges).
        boolean edited = edited(work, workTransitions, source);
        if (edited) {
            changes.add("изменения этапов, сделанные в карточке, заменены этапами шаблона");
        }
        return new Plan(
                List.copyOf(stages),
                transitions,
                merged,
                targetCurrent,
                current.target().name(),
                moved,
                edited,
                List.copyOf(changes),
                noExit,
                landsOnFinal
        );
    }

    // Работа «правлена в карточке», если её этапы или переходы отличаются от версии шаблона, по которой она
    // идёт. Переходы сравниваются через идентификаторы этапов шаблона, потому что у работы свои идентификаторы.
    static boolean edited(List<RouteStage> work, List<InteractionStageTransition> workTransitions, WorkflowRoute source) {
        List<String> workStages = work.stream()
                .map(stage -> stage.templateStageId() + "|" + stage.name() + "|" + stage.optional())
                .toList();
        List<String> sourceStages = ordered(source).stream()
                .map(stage -> stage.id() + "|" + stage.name() + "|" + stage.optional())
                .toList();
        if (!workStages.equals(sourceStages)) {
            return true;
        }
        Map<UUID, UUID> keys = new HashMap<>();
        work.forEach(stage -> keys.put(stage.id(), stage.templateStageId()));
        Set<String> workEdges = workTransitions.stream()
                .map(transition -> keys.get(transition.fromStageId()) + ">" + keys.get(transition.toStageId())
                        + ":" + transition.commentRequired())
                .collect(Collectors.toSet());
        Set<String> sourceEdges = source.transitions().stream()
                .map(transition -> transition.fromStageId() + ">" + transition.toStageId()
                        + ":" + transition.commentRequired())
                .collect(Collectors.toSet());
        return !workEdges.equals(sourceEdges);
    }

    static List<RouteStage> asRoute(WorkflowRoute route) {
        return ordered(route).stream()
                .map(stage -> new RouteStage(stage.id(), stage.name(), stage.order(), stage.optional(), stage.id(),
                        stage.requiresAgreement(), stage.lmsRole(), stage.documentKinds()))
                .toList();
    }

    // Для удалённого этапа берётся ближайший сохранившийся выше по маршруту, затем ниже, и лишь потом первый этап
    // шаблона. Сначала назад: перенос вперёд молча засчитывал бы непройденные этапы, вплоть до финального, откуда
    // переходов нет. Другой этап руководитель выбирает в мастере явно (overrides).
    private static WorkflowTemplateStage fallback(List<Mapped> primary, int index, WorkflowTemplateStage first) {
        for (int previous = index - 1; previous >= 0; previous--) {
            if (primary.get(previous) != null) {
                return primary.get(previous).target();
            }
        }
        for (int next = index + 1; next < primary.size(); next++) {
            if (primary.get(next) != null) {
                return primary.get(next).target();
            }
        }
        return first;
    }

    private static List<WorkflowTemplateStage> ordered(WorkflowRoute route) {
        return route.stages().stream().sorted(Comparator.comparingInt(WorkflowTemplateStage::order)).toList();
    }

    // Свойства этапа (договор, роль LMS, документы) в плане берутся из этапа версии: перевод на версию переносит их в
    // карточку так же, как название и обязательность.
    record RouteStage(
            UUID id,
            String name,
            int order,
            boolean optional,
            UUID templateStageId,
            boolean requiresAgreement,
            StageLmsRole lmsRole,
            List<String> documentKinds
    ) {
        RouteStage(UUID id, String name, int order, boolean optional, UUID templateStageId) {
            this(id, name, order, optional, templateStageId, false, null, List.of());
        }

        InteractionStage asStage() {
            return new InteractionStage(id, name, order, optional, requiresAgreement, lmsRole, documentKinds);
        }
    }

    record Mapped(RouteStage stage, WorkflowTemplateStage target, WorkflowStageMatch match) {
    }

    record Plan(
            List<RouteStage> stages,
            List<InteractionStageTransition> transitions,
            Map<UUID, UUID> merged,
            UUID currentStageId,
            String currentStageName,
            boolean currentStageRemoved,
            boolean edited,
            List<String> changes,
            boolean noExit,
            boolean landsOnFinal
    ) {
    }
}
