package ru.rtk.crm.report;

import java.time.OffsetDateTime;

import ru.rtk.crm.interaction.InteractionDue;
import ru.rtk.crm.interaction.InteractionWorkStatus;

public enum ManagerWorkDue {
    OVERDUE("Просрочено"),
    THIS_WEEK("На этой неделе"),
    NO_NEXT_STEP("Без шага или срока"),
    IN_ORDER("В порядке");

    private final String label;

    ManagerWorkDue(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    // Правило срока общее с «Моей работой» (InteractionDue): завершённой работе следующий шаг не нужен — она «В порядке»,
    // приостановленная оценивается по дате возврата и в «Без шага» не попадает.
    public static ManagerWorkDue of(InteractionWorkStatus status, String nextAction, OffsetDateTime nextActionAt, OffsetDateTime now) {
        if (InteractionDue.OVERDUE.matches(status, nextAction, nextActionAt, now)) {
            return OVERDUE;
        }
        if (InteractionDue.NO_NEXT_STEP.matches(status, nextAction, nextActionAt, now)) {
            return NO_NEXT_STEP;
        }
        return InteractionDue.THIS_WEEK.matches(status, nextAction, nextActionAt, now) ? THIS_WEEK : IN_ORDER;
    }
}
