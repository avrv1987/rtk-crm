package ru.rtk.crm.report;

import java.util.List;

public record ManagerWorkTotal(
        String team,
        String manager,
        long organizations,
        long works,
        long overdue,
        long thisWeek,
        long noNextStep,
        long transitions
) {
    static final List<String> TITLES = List.of(
            "Команда", "Менеджер", "Вузов", "Взаимодействий", "Просрочено", "На этой неделе", "Без шага или срока",
            "Переходов за период"
    );
    static final int[] WIDTHS = {22, 26, 10, 14, 12, 14, 18, 14};

    List<Object> values() {
        return List.of(team, manager, organizations, works, overdue, thisWeek, noNextStep, transitions);
    }
}
