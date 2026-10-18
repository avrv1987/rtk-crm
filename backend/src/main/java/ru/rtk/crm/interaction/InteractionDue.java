package ru.rtk.crm.interaction;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Map;

// Единое правило срока следующего шага: список, лента и сводка «Моей работы», показатели руководителя и «Контроль»
// (ManagerWorkLine.due) берут его отсюда. Просрочка важнее всего: работа со сроком в прошлом — только «Просрочено»,
// даже без текста шага, и не попадает ни в «Сегодня», ни в «На этой неделе», ни в «Без следующего шага».
// TODAY входит в THIS_WEEK; границы дня и недели — по Москве.
// Срок требуется только от идущей работы: завершённая не попадает ни в одну группу, у приостановленной срок — дата
// возврата (она хранится следующим шагом), и «Без следующего шага» к ней не относится.
public enum InteractionDue {
    OVERDUE("(i.work_status <> 'COMPLETED' AND i.next_action_at < :dueNow)"),
    TODAY("(" + Sql.STEP + " AND i.next_action_at >= :dueNow AND i.next_action_at < :dueDayEnd)"),
    THIS_WEEK("(" + Sql.STEP + " AND i.next_action_at >= :dueNow AND i.next_action_at < :dueWeekEnd)"),
    LATER("(" + Sql.STEP + " AND i.next_action_at >= :dueWeekEnd)"),
    NO_NEXT_STEP("(i.work_status = 'ACTIVE' AND (i.next_action_at IS NULL OR (i.next_action IS NULL AND i.next_action_at >= :dueNow)))");

    static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    private final String condition;

    InteractionDue(String condition) {
        this.condition = condition;
    }

    public String condition() {
        return condition;
    }

    public static Map<String, Object> parameters(OffsetDateTime now) {
        return Map.of("dueNow", now, "dueDayEnd", dayEnd(now), "dueWeekEnd", weekEnd(now));
    }

    public boolean matches(InteractionWorkStatus status, String nextAction, OffsetDateTime nextActionAt, OffsetDateTime now) {
        if (status == InteractionWorkStatus.COMPLETED) {
            return false;
        }
        boolean paused = status == InteractionWorkStatus.PAUSED;
        boolean overdue = nextActionAt != null && nextActionAt.isBefore(now);
        boolean step = (paused || nextAction != null) && nextActionAt != null && !overdue;
        return switch (this) {
            case OVERDUE -> overdue;
            case TODAY -> step && nextActionAt.isBefore(dayEnd(now));
            case THIS_WEEK -> step && nextActionAt.isBefore(weekEnd(now));
            case LATER -> step && !nextActionAt.isBefore(weekEnd(now));
            case NO_NEXT_STEP -> !paused && !overdue && !step;
        };
    }

    static InteractionDue from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(due -> due.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new InteractionValidationException("due", "Такой отбор по сроку не поддерживается"));
    }

    public static OffsetDateTime weekStart(OffsetDateTime now) {
        return now.atZoneSameInstant(ZONE)
                .toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZONE)
                .toOffsetDateTime();
    }

    public static OffsetDateTime dayEnd(OffsetDateTime now) {
        return now.atZoneSameInstant(ZONE).toLocalDate().plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
    }

    private static OffsetDateTime weekEnd(OffsetDateTime now) {
        return weekStart(now).plusWeeks(1);
    }

    private static final class Sql {
        private static final String STEP = "i.work_status <> 'COMPLETED' AND (i.next_action IS NOT NULL OR i.work_status = 'PAUSED')";
    }
}
