package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class LmsSignalModels {
    private LmsSignalModels() {
    }

    public enum LmsSignalType {
        STUDENTS_APPEARED,
        NO_STUDENTS,
        LOW_COMPLETION,
        LOW_ACTIVITY,
        NOT_STARTED,
        DROPOUT,
        TEACHER_INACTIVE,
        NO_ACTIVITY;

        public boolean activity() {
            return ordinal() >= LOW_ACTIVITY.ordinal();
        }
    }

    public record LmsSignals(OffsetDateTime calculatedAt, List<LmsSignal> items) {
    }

    public record LmsSignal(
            String id,
            LmsSignalType type,
            String title,
            String message,
            UUID interactionId,
            String interactionTitle,
            UUID organizationId,
            String organizationName,
            UUID ownerManagerId,
            String ownerManagerName,
            String currentStageName,
            UUID mappingId,
            String courseName,
            String groupName,
            LocalDate runStartsOn,
            LocalDate runEndsOn,
            int participants,
            Integer completed,
            OffsetDateTime observedAt,
            LmsSignalAction action,
            String actionHint,
            boolean dismissed,
            boolean recordedAsRisk
    ) {
    }

    public record LmsSignalAction(UUID stageId, String stageName, boolean commentRequired) {
    }

    public record LmsSignalDismissalRequest(LmsSignalType type, UUID mappingId) {
    }

    public record LmsSignalRiskRequest(Integer version, LmsSignalType type, UUID mappingId) {
    }

    /**
     * Обучение по работе для контроля менеджеров: идущие потоки занятий студентов, действующие (не скрытые) сигналы
     * и сверка этапа с LMS. Числа — последние снимки, а не значения на конец периода отчёта.
     */
    public record WorkLearning(
            int runs,
            int participants,
            Integer active7d,
            int signals,
            List<String> signalTitles,
            String mismatch
    ) {
        public String summary() {
            StringBuilder text = new StringBuilder(runs == 0 ? "идущих потоков нет"
                    : "потоков идёт " + runs + ", обучающихся " + participants
                    + (active7d == null ? "" : ", заходили за 7 дней " + active7d));
            if (signals > 0) {
                text.append("; сигналы: ").append(String.join(", ", signalTitles));
            }
            return text.toString();
        }
    }
}
