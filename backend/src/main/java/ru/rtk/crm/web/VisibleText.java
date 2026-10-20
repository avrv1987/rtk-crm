package ru.rtk.crm.web;

// Пусто ли значение для человека: String.isBlank не считает пробелом неразрывный пробел, а символы нулевой ширины
// (ZWSP, ZWJ, BOM) вообще не пробелы — комментарий из NBSP или название из двух ZWSP проходили проверку «Заполните».
public final class VisibleText {
    private VisibleText() {
    }

    public static boolean blank(String value) {
        return value == null || value.codePoints().allMatch(VisibleText::invisible);
    }

    static boolean invisible(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }
}
