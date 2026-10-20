package ru.rtk.crm.privacy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Маска другого лица в выгрузке сведений субъекта (Н1, Н2 проверки 2.5.3). В свободном тексте сотрудник пишет имя
// как придётся: «Борис Методистов», «Методистов Борис», «Методистов Б.», «Б. И. Методистов», а в справочнике оно
// записано со служебной приставкой «(демо)». Поэтому имя из справочника очищается от скобок, и закрываются: все слова
// имени в любом порядке, любые два слова в любом порядке, слово с инициалами остальных. Отдельное слово не
// закрывается: «Ирина просила перезвонить» не скрывает, о ком речь, а по одному имени закрылись бы и посторонние слова.
// Приставка из справочника, стоящая в тексте сразу после имени, уходит вместе с ним — без лишней скобки.
final class OtherPersonMask {
    private static final String WORD_GAP = "[^\\p{L}\\p{N}]{1,3}";
    private static final String NOT_AFTER_WORD = "(?<![\\p{L}\\p{N}])";
    private static final String NOT_BEFORE_WORD = "(?![\\p{L}\\p{N}])";
    private static final Pattern BRACKETS = Pattern.compile("\\(([^()]*)\\)|\\[([^\\[\\]]*)]");
    private static final int MIN_WORD = 2;
    private static final int MAX_WORDS = 4;

    private final List<String> words;
    private final List<Pattern> patterns;

    private OtherPersonMask(List<String> words, List<Pattern> patterns) {
        this.words = words;
        this.patterns = patterns;
    }

    // null — в имени меньше двух слов: такое имя по тексту не узнать без риска закрыть постороннее.
    static OtherPersonMask of(String name) {
        if (name == null) {
            return null;
        }
        List<String> suffixes = new ArrayList<>();
        Matcher brackets = BRACKETS.matcher(name);
        while (brackets.find()) {
            suffixes.add(brackets.group());
        }
        List<String> words = words(BRACKETS.matcher(name).replaceAll(" "));
        if (words.size() < 2 || words.size() > MAX_WORDS) {
            return null;
        }
        String suffix = suffixes.isEmpty() ? "" : "(?:\\s*(?:" + suffixes.stream().map(Pattern::quote)
                .collect(Collectors.joining("|")) + "))?";
        List<String> phrases = new ArrayList<>();
        for (List<String> order : permutations(words)) {
            phrases.add(order.stream().map(Pattern::quote).collect(Collectors.joining(WORD_GAP)));
        }
        for (String first : words) {
            for (String second : words) {
                if (!first.equals(second)) {
                    phrases.add(Pattern.quote(first) + WORD_GAP + Pattern.quote(second));
                }
            }
        }
        // Слово целиком и инициалы остальных: «Методистов Б.», «Методистов Б. И.», «Б.И. Методистов».
        for (String full : words) {
            List<String> others = words.stream().filter(word -> !word.equals(full)).toList();
            String initials = others.stream().map(word -> Pattern.quote(word.substring(0, 1)) + "\\.")
                    .collect(Collectors.joining("\\s*"));
            String first = Pattern.quote(others.getFirst().substring(0, 1)) + "\\.";
            phrases.add(Pattern.quote(full) + "\\s+" + initials);
            phrases.add(initials + "\\s*" + Pattern.quote(full));
            phrases.add(Pattern.quote(full) + "\\s+" + first);
            phrases.add(first + "\\s*" + Pattern.quote(full));
        }
        List<Pattern> patterns = phrases.stream()
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(phrase -> Pattern.compile(NOT_AFTER_WORD + "(?:" + phrase + ")" + suffix
                                + (phrase.endsWith("\\.") ? "" : NOT_BEFORE_WORD),
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                .toList();
        return new OtherPersonMask(words, patterns);
    }

    // Дешёвый отбор перед регулярными выражениями: хотя бы одно слово имени есть в тексте.
    boolean mayOccurIn(String lowerText) {
        return words.stream().anyMatch(lowerText::contains);
    }

    boolean sameWords(OtherPersonMask other) {
        return other != null && words.stream().sorted().toList().equals(other.words.stream().sorted().toList());
    }

    // Заменяет имя на маркер, но не трогает места, где написан сам субъект (protectedMasks): у однофамильца те же
    // «Петров И.» закрыли бы и имя самого субъекта.
    static String replace(String text, List<OtherPersonMask> others, List<OtherPersonMask> protectedMasks, String marker) {
        if (text == null || others.isEmpty()) {
            return text;
        }
        String result = text;
        for (OtherPersonMask other : others) {
            for (Pattern pattern : other.patterns) {
                result = replaceOutside(result, pattern, protectedMasks, marker);
            }
        }
        return result;
    }

    private static String replaceOutside(String text, Pattern pattern, List<OtherPersonMask> protectedMasks, String marker) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        List<int[]> kept = protectedRanges(text, protectedMasks);
        StringBuilder result = new StringBuilder();
        int last = 0;
        do {
            int start = matcher.start();
            int end = matcher.end();
            if (kept.stream().noneMatch(range -> start < range[1] && range[0] < end)) {
                result.append(text, last, start).append(marker);
                last = end;
            }
        } while (matcher.find());
        return result.append(text, last, text.length()).toString();
    }

    private static List<int[]> protectedRanges(String text, List<OtherPersonMask> masks) {
        List<int[]> ranges = new ArrayList<>();
        for (OtherPersonMask mask : masks) {
            for (Pattern pattern : mask.patterns) {
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    ranges.add(new int[] {matcher.start(), matcher.end()});
                }
            }
        }
        return ranges;
    }

    private static List<String> words(String value) {
        return Stream.of(value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> word.length() >= MIN_WORD)
                .distinct()
                .toList();
    }

    private static List<List<String>> permutations(List<String> items) {
        if (items.size() <= 1) {
            return List.of(items);
        }
        List<List<String>> result = new ArrayList<>();
        for (String item : items) {
            List<String> rest = new ArrayList<>(items);
            rest.remove(item);
            for (List<String> tail : permutations(rest)) {
                List<String> order = new ArrayList<>();
                order.add(item);
                order.addAll(tail);
                result.add(order);
            }
        }
        return result;
    }
}
