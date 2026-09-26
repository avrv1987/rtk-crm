package ru.rtk.crm.interaction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class WorkflowGraph {
    private WorkflowGraph() {
    }

    static List<InteractionStageTransition> rebuild(
            List<InteractionStage> stages,
            List<InteractionStageTransition> transitions,
            UUID removedStageId,
            UUID insertedStageId
    ) {
        if (removedStageId == null && insertedStageId == null) {
            return transitions;
        }
        Map<Edge, Boolean> edges = new LinkedHashMap<>();
        for (InteractionStageTransition transition : transitions) {
            merge(edges, Edge.of(transition), transition.commentRequired());
        }
        if (removedStageId != null) {
            edges = withoutStage(edges, removedStageId);
        }
        List<InteractionStage> ordered = ordered(stages);
        if (insertedStageId != null) {
            edges = withInsertedStage(ordered, edges, insertedStageId);
        }
        Map<UUID, Integer> positions = positions(ordered);
        List<InteractionStageTransition> result = new ArrayList<>();
        edges.forEach((edge, commentRequired) -> {
            InteractionStageTransition transition =
                    new InteractionStageTransition(edge.fromStageId(), edge.toStageId(), commentRequired);
            if (positions.containsKey(edge.fromStageId())
                    && positions.containsKey(edge.toStageId())
                    && allowed(transition, positions)) {
                result.add(transition);
            }
        });
        return List.copyOf(result);
    }

    static void validate(
            List<InteractionStage> stages,
            List<InteractionStageTransition> transitions,
            String field
    ) {
        if (stages.isEmpty() || stages.stream().noneMatch(stage -> stage.order() == 0)) {
            throw new InteractionValidationException(field, "В процессе нужен начальный этап");
        }
        List<InteractionStage> ordered = ordered(stages);
        Map<UUID, Integer> positions = positions(ordered);
        Map<UUID, List<UUID>> outgoing = new HashMap<>();
        Set<Edge> uniqueEdges = new HashSet<>();
        for (InteractionStageTransition transition : transitions) {
            if (!positions.containsKey(transition.fromStageId()) || !positions.containsKey(transition.toStageId())
                    || transition.fromStageId().equals(transition.toStageId())) {
                throw new InteractionValidationException(field, "Переход ссылается на этап вне процесса");
            }
            if (!uniqueEdges.add(Edge.of(transition))) {
                throw new InteractionValidationException(field, "Переходы не должны повторяться");
            }
            if (!allowed(transition, positions)) {
                throw new InteractionValidationException(
                        field,
                        "Переход «" + ordered.get(positions.get(transition.fromStageId())).name() + "» → «"
                                + ordered.get(positions.get(transition.toStageId())).name()
                                + "» пропускает этапы или ведёт назад и требует обязательного комментария: отметьте «Нужен комментарий» или удалите переход"
                );
            }
            outgoing.computeIfAbsent(transition.fromStageId(), ignored -> new ArrayList<>()).add(transition.toStageId());
        }
        for (InteractionStage stage : ordered.subList(0, ordered.size() - 1)) {
            if (!outgoing.containsKey(stage.id())) {
                throw new InteractionValidationException(
                        field,
                        "Этапу «" + stage.name() + "» нужен исходящий переход: добавьте переход из него в следующий этап"
                );
            }
        }
        Set<UUID> reachable = new HashSet<>();
        ArrayDeque<UUID> pending = new ArrayDeque<>();
        pending.add(ordered.getFirst().id());
        while (!pending.isEmpty()) {
            UUID stageId = pending.removeFirst();
            if (reachable.add(stageId)) {
                pending.addAll(outgoing.getOrDefault(stageId, List.of()));
            }
        }
        for (InteractionStage stage : ordered) {
            if (!reachable.contains(stage.id())) {
                throw new InteractionValidationException(
                        field,
                        "Этап «" + stage.name() + "» недостижим из начального этапа «" + ordered.getFirst().name()
                                + "»: добавьте переход в него из предыдущего этапа"
                );
            }
        }
    }

    private static Map<Edge, Boolean> withoutStage(Map<Edge, Boolean> edges, UUID stageId) {
        Map<Edge, Boolean> result = new LinkedHashMap<>();
        Map<UUID, Boolean> incoming = new LinkedHashMap<>();
        Map<UUID, Boolean> outgoing = new LinkedHashMap<>();
        edges.forEach((edge, commentRequired) -> {
            if (edge.toStageId().equals(stageId)) {
                incoming.put(edge.fromStageId(), commentRequired);
            } else if (edge.fromStageId().equals(stageId)) {
                outgoing.put(edge.toStageId(), commentRequired);
            } else {
                result.put(edge, commentRequired);
            }
        });
        incoming.forEach((from, incomingComment) -> outgoing.forEach((to, outgoingComment) -> {
            if (!from.equals(to)) {
                merge(result, new Edge(from, to), incomingComment || outgoingComment);
            }
        }));
        return result;
    }

    private static Map<Edge, Boolean> withInsertedStage(
            List<InteractionStage> ordered,
            Map<Edge, Boolean> edges,
            UUID stageId
    ) {
        Map<UUID, Integer> positions = positions(ordered);
        int index = positions.get(stageId);
        InteractionStage stage = ordered.get(index);
        UUID anchor = ordered.get(index - 1).id();
        UUID next = index + 1 < ordered.size() ? ordered.get(index + 1).id() : null;
        UUID left = next != null ? anchor : index > 1 ? ordered.get(index - 2).id() : null;
        UUID right = next != null ? next : anchor;
        Boolean forward = left == null ? null : edges.get(new Edge(left, right));
        Boolean backward = left == null ? null : edges.get(new Edge(right, left));
        Map<Edge, Boolean> result = new LinkedHashMap<>();
        edges.forEach((edge, commentRequired) -> {
            boolean crossesForward = positions.get(edge.fromStageId()) < index && positions.get(edge.toStageId()) > index;
            if (!crossesForward) {
                merge(result, edge, commentRequired);
            } else if (stage.optional()) {
                merge(result, edge, true);
            } else {
                merge(result, new Edge(edge.fromStageId(), stageId), commentRequired);
            }
        });
        boolean forwardComment = forward != null && forward;
        merge(result, new Edge(anchor, stageId), forwardComment);
        if (next != null) {
            merge(result, new Edge(stageId, next), forwardComment);
        }
        if (backward != null) {
            merge(result, new Edge(stageId, anchor), backward);
            if (next != null) {
                merge(result, new Edge(next, stageId), backward);
            }
        }
        return result;
    }

    private static void merge(Map<Edge, Boolean> edges, Edge edge, boolean commentRequired) {
        edges.merge(edge, commentRequired, (current, added) -> current && added);
    }

    private static boolean allowed(InteractionStageTransition transition, Map<UUID, Integer> positions) {
        return transition.commentRequired()
                || Math.abs(positions.get(transition.fromStageId()) - positions.get(transition.toStageId())) == 1;
    }

    private static List<InteractionStage> ordered(List<InteractionStage> stages) {
        return stages.stream().sorted(Comparator.comparingInt(InteractionStage::order)).toList();
    }

    private static Map<UUID, Integer> positions(List<InteractionStage> ordered) {
        Map<UUID, Integer> positions = new HashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            positions.put(ordered.get(index).id(), index);
        }
        return positions;
    }

    private record Edge(UUID fromStageId, UUID toStageId) {
        static Edge of(InteractionStageTransition transition) {
            return new Edge(transition.fromStageId(), transition.toStageId());
        }
    }
}
