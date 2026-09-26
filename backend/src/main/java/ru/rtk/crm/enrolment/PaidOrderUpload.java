package ru.rtk.crm.enrolment;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public record PaidOrderUpload(
        UUID runId,
        int received,
        int emptyElements,
        int created,
        int updated,
        int skipped,
        int needsMapping,
        int failed,
        String message,
        List<Stream> streams,
        List<PaidOrderIssue> issues,
        LearnerIntake learners
) {
    public PaidOrderUpload {
        learners = learners == null ? LearnerIntake.NONE : learners;
    }

    public static PaidOrderUpload of(
            UUID runId,
            int created,
            int updated,
            int skipped,
            int needsMapping,
            int failed,
            String message,
            PaidOrderBatch batch,
            LearnerIntake learners
    ) {
        Map<Stream, Long> streams = batch.orders().stream()
                .collect(Collectors.groupingBy(order -> new Stream(order.course(), order.streamNumber(), 0), Collectors.counting()));
        return new PaidOrderUpload(
                runId,
                batch.received(),
                batch.emptyElements(),
                created,
                updated,
                skipped,
                needsMapping,
                failed,
                message,
                streams.entrySet().stream()
                        .map(entry -> new Stream(entry.getKey().course(), entry.getKey().streamNo(), entry.getValue().intValue()))
                        .sorted(Comparator.comparing(Stream::course).thenComparingInt(Stream::streamNo))
                        .toList(),
                batch.issues(),
                learners
        );
    }

    public record Stream(String course, int streamNo, int orders) {
    }
}
