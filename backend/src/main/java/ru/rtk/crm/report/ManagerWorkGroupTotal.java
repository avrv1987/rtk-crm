package ru.rtk.crm.report;

import java.util.List;
import java.util.stream.Stream;

// Итог отчёта «Работа менеджеров» по продукту или по вузу. Последняя строка списка — «Всего» по уникальным
// взаимодействиям: одно взаимодействие может относиться к нескольким продуктам, и сумма строк по продуктам тогда больше.
public record ManagerWorkGroupTotal(
        String name,
        long organizations,
        long works,
        long overdue,
        long thisWeek,
        long noNextStep,
        long transitions
) {
    static final List<String> COLUMNS = List.of("Вузов", "Взаимодействий", "Просрочено", "На этой неделе", "Без шага или срока",
            "Переходов за период");
    static final int[] COLUMN_WIDTHS = {10, 14, 12, 14, 18, 14};

    static List<String> titles(String first) {
        return Stream.concat(Stream.of(first), COLUMNS.stream()).toList();
    }

    static int[] widths() {
        int[] widths = new int[COLUMN_WIDTHS.length + 1];
        widths[0] = 34;
        System.arraycopy(COLUMN_WIDTHS, 0, widths, 1, COLUMN_WIDTHS.length);
        return widths;
    }

    List<Object> values() {
        return List.of(name, organizations, works, overdue, thisWeek, noNextStep, transitions);
    }
}
