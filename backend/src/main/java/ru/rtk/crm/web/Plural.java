package ru.rtk.crm.web;

// Русское склонение по числу: 1 участие, 2 участия, 5 участий, 11 участий, 21 участие. Одна функция для экрана,
// файлов и MCP: тексты строит сервер, и в каждом месте склонение одинаковое. Знак числа не влияет на форму.
public final class Plural {
    private Plural() {
    }

    public static String form(long count, String one, String few, String many) {
        long lastTwo = Math.abs(count) % 100;
        long last = lastTwo % 10;
        if (last == 1 && lastTwo != 11) {
            return one;
        }
        return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? few : many;
    }

    public static String withCount(long count, String one, String few, String many) {
        return count + " " + form(count, one, few, many);
    }
}
