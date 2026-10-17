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
            UUID key = stage.templateStageId() != null && seen.add(stage.templateStageId()) ? stage.templateStageId() : null;
            keys.add(key);
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

    static Plan plan(
            List<RouteStage> work,
            List<InteractionStageTransition> workTransitions,
            UUID currentStageId,
            WorkflowRoute source,
            WorkflowRoute target,
            Map<UUID, UUID> overrides
    ) {
        List<Mapped> mapped = map(work, target, overrides);
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
            stages.add(new RouteStage(id, stage.name(), order, stage.optional(), stage.id()));
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
        boolean edited = edited(work, workTransitions, source);
        if (edited) {
            changes.add("изменения этапов, сделанные в карточке, заменены этапами шаблона");
        }
        return new Plan(
                List.copyOf(stages),
                transitions,
                merged,
                stageIds.get(current.target().id()),
                current.target().name(),
                !currentKept || current.match() == WorkflowStageMatch.REMOVED
                        || current.match() == WorkflowStageMatch.MANUAL,
                edited,
                List.copyOf(changes)
        );
    }

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
                .map(stage -> new RouteStage(stage.id(), stage.name(), stage.order(), stage.optional(), stage.id()))
                .toList();
    }

    private static WorkflowTemplateStage fallback(List<Mapped> primary, int index, WorkflowTemplateStage first) {
        for (int next = index + 1; next < primary.size(); next++) {
            if (primary.get(next) != null) {
                return primary.get(next).target();
            }
        }
        for (int previous = index - 1; previous >= 0; previous--) {
            if (primary.get(previous) != null) {
                return primary.get(previous).target();
            }
        }
        return first;
    }

    private static List<WorkflowTemplateStage> ordered(WorkflowRoute route) {
        return route.stages().stream().sorted(Comparator.comparingInt(WorkflowTemplateStage::order)).toList();
    }

    record RouteStage(UUID id, String name, int order, boolean optional, UUID templateStageId) {
        InteractionStage asStage() {
            return new InteractionStage(id, name, order, optional);
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
            List<String> changes
    ) {
    }
}
