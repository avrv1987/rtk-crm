package ru.rtk.crm.privacy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import ru.rtk.crm.catalog.SearchPattern;
import ru.rtk.crm.interaction.InteractionValidationException;

final class SubjectTerms {
    private static final int MIN_TEXT = 3;
    private static final int MAX_TEXT = 200;
    private static final int MIN_NAME_WORDS = 2;
    private static final int MIN_NAME_WORD_LENGTH = 2;
    private static final int MIN_PHONE_DIGITS = 5;
    private static final int MIN_SUBJECT_PHONE_DIGITS = 7;
    private static final int MIN_EXPORT_PHONE_DIGITS = 10;
    private static final int MAX_SPELLINGS = 10;
    private static final String SEPARATORS = "[\\s().+\\-]*";
    private static final String WORD_GAP = "[^\\p{L}\\p{N}]{1,3}";
    private static final String NOT_AFTER_WORD = "(?<![\\p{L}\\p{N}])";
    private static final String NOT_BEFORE_WORD = "(?![\\p{L}\\p{N}])";
    private static final Pattern FULL_EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final String STRIPPED =
            "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(%s, ''), ' ', ''), '-', ''), '(', ''), ')', ''), '.', ''), '+', '')";

    // Литеральные строки: то, что администратор ввёл в поиск, и почта субъекта.
    private final Map<String, String> textTerms;
    // ФИО целиком: в текстах и именах файлов; слова в том же порядке через 1–3 любых разделителя.
    private final Map<String, Phrase> namePhrases;
    // Только для имён файлов: транслитерация ФИО целиком и строки поиска.
    private final Map<String, Phrase> filePhrases;
    private final Map<String, Phone> phones;

    private SubjectTerms() {
        this.textTerms = new LinkedHashMap<>();
        this.namePhrases = new LinkedHashMap<>();
        this.filePhrases = new LinkedHashMap<>();
        this.phones = new LinkedHashMap<>();
    }

    // Термины поиска. Поиск только читает: строка ищется как введена, в именах файлов — ещё и её транслитерация
    // целиком. Отдельные слова ФИО и их транслитерация не ищутся — из-за них «Аудит» находил чужой «Audit-…».
    static SubjectTerms of(SubjectQuery query) {
        if (query == null) {
            throw new InteractionValidationException("name", "Укажите ФИО, почту, телефон, СНИЛС или другое написание");
        }
        SubjectTerms terms = new SubjectTerms();
        String name = validatedText(query.name(), "name");
        if (name != null) {
            terms.addText(name);
            addPhrase(terms.filePhrases, name);
            addPhrase(terms.filePhrases, transliterate(name));
        }
        String email = validatedText(query.email(), "email");
        if (email != null) {
            terms.addText(email);
        }
        if (query.phone() != null && !query.phone().isBlank()) {
            Phone phone = Phone.parse(query.phone(), MIN_PHONE_DIGITS);
            if (phone == null) {
                throw new InteractionValidationException("phone", "Телефон должен содержать не меньше 5 цифр");
            }
            terms.phones.put(phone.core(), phone);
        }
        for (String value : spellings(query)) {
            terms.addText(value);
            addPhrase(terms.filePhrases, value);
        }
        if (terms.isEmpty() && (query.snils() == null || query.snils().isBlank())) {
            throw new InteractionValidationException("name", "Укажите ФИО, почту, телефон, СНИЛС или другое написание");
        }
        return terms;
    }

    // Термины замены для одного субъекта: ФИО целиком (не меньше двух слов), его транслитерация целиком — только
    // в именах файлов, полная почта и телефон не короче 7 цифр. Отдельные слова ФИО не используются никогда.
    static SubjectTerms subject(String name, String email, String phone, List<String> otherSpellings) {
        SubjectTerms terms = new SubjectTerms();
        String normalizedName = lenientText(name);
        if (normalizedName != null && fullName(normalizedName)) {
            addPhrase(terms.namePhrases, normalizedName);
            addPhrase(terms.filePhrases, transliterate(normalizedName));
        }
        String normalizedEmail = lenientText(email);
        if (normalizedEmail != null && FULL_EMAIL.matcher(normalizedEmail).matches()) {
            terms.addText(normalizedEmail);
        }
        Phone parsedPhone = phone == null ? null : Phone.parse(phone, MIN_SUBJECT_PHONE_DIGITS);
        if (parsedPhone != null) {
            terms.phones.put(parsedPhone.core(), parsedPhone);
        }
        for (String spelling : otherSpellings) {
            if (fullName(spelling)) {
                addPhrase(terms.namePhrases, spelling);
            }
        }
        return terms;
    }

    static List<String> spellings(SubjectQuery query) {
        if (query == null || query.otherSpellings() == null || query.otherSpellings().isBlank()) {
            return List.of();
        }
        List<String> values = List.of(query.otherSpellings().split("[,;\\n]"));
        if (values.size() > MAX_SPELLINGS) {
            throw new InteractionValidationException("otherSpellings", "Укажите не больше 10 других написаний");
        }
        List<String> result = new ArrayList<>();
        for (String spelling : values) {
            String value = validatedText(spelling, "otherSpellings");
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    // Выгрузка уходит субъекту, поэтому чужие записи в неё попасть не должны: только ФИО целиком, полная почта
    // и телефон не короче 10 цифр. По «Анна» или «@example.test» собрались бы данные других людей.
    static void requireExact(SubjectQuery query) {
        if (query == null) {
            return;
        }
        String name = validatedText(query.name(), "name");
        if (name != null && !fullName(name)) {
            throw new InteractionValidationException("name", "Для выгрузки укажите ФИО полностью: фамилию и имя");
        }
        String email = validatedText(query.email(), "email");
        if (email != null && !FULL_EMAIL.matcher(email).matches()) {
            throw new InteractionValidationException("email", "Для выгрузки укажите почту полностью");
        }
        if (query.phone() != null && !query.phone().isBlank() && Phone.parse(query.phone(), MIN_EXPORT_PHONE_DIGITS) == null) {
            throw new InteractionValidationException("phone", "Для выгрузки укажите телефон полностью, не меньше 10 цифр");
        }
        for (String spelling : spellings(query)) {
            if (!fullName(spelling)) {
                throw new InteractionValidationException("otherSpellings", "Для выгрузки другие написания указываются ФИО целиком");
            }
        }
    }

    static boolean fullName(String value) {
        return words(value).stream().filter(word -> word.length() >= MIN_NAME_WORD_LENGTH).count() >= MIN_NAME_WORDS;
    }

    boolean isEmpty() {
        return textTerms.isEmpty() && namePhrases.isEmpty() && phones.isEmpty();
    }

    SqlMatch textMatch(String... columns) {
        return match(namePhrases.values(), columns);
    }

    SqlMatch attachmentMatch(String column) {
        return match(filePhraseList(), column);
    }

    // Встречается ли субъект в тексте готового отчёта: ФИО целиком, полная почта или телефон — те же признаки, что и
    // при замене, чтобы «найдено в отчёте» и «заменено в базе» означали одно и то же.
    boolean foundIn(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return patterns(namePhrases.values()).stream().anyMatch(pattern -> pattern.matcher(text).find());
    }

    String replace(String text, String marker) {
        return apply(text, marker, patterns(namePhrases.values()));
    }

    String replaceInJson(String json, String marker, ObjectMapper objectMapper) {
        if (json == null) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return replace(json, marker);
        }
        if (root == null || !root.isContainerNode()) {
            return replace(json, marker);
        }
        if (!replaceValues(root, marker, patterns(namePhrases.values()))) {
            return json;
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Anonymized JSON cannot be written", exception);
        }
    }

    String replaceInAttachmentName(String text, String marker) {
        return apply(text, marker, patterns(filePhraseList()));
    }

    boolean matchesAttachmentName(String text) {
        return text != null && !replaceInAttachmentName(text, "\u0000").equals(text);
    }

    private SqlMatch match(Collection<Phrase> phrases, String... columns) {
        List<String> parts = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        int index = 0;
        for (String term : textTerms.values()) {
            String param = "t" + index++;
            params.put(param, SearchPattern.contains(term));
            for (String column : columns) {
                parts.add("LOWER(COALESCE(" + column + ", '')) LIKE :" + param + " " + SearchPattern.LIKE_ESCAPE);
            }
        }
        // LIKE по словам ФИО по порядку — только грубый отбор; совпадение с границами слов проверяется в Java.
        index = 0;
        for (Phrase phrase : phrases) {
            String param = "n" + index++;
            params.put(param, phrase.like());
            for (String column : columns) {
                parts.add("LOWER(COALESCE(" + column + ", '')) LIKE :" + param + " " + SearchPattern.LIKE_ESCAPE);
            }
        }
        index = 0;
        for (Phone phone : phones.values()) {
            String param = "p" + index++;
            params.put(param, "%" + phone.core() + "%");
            for (String column : columns) {
                parts.add(STRIPPED.formatted(column) + " LIKE :" + param);
            }
        }
        return new SqlMatch(parts.isEmpty() ? "FALSE" : "(" + String.join(" OR ", parts) + ")", params);
    }

    private List<Phrase> filePhraseList() {
        Map<String, Phrase> merged = new LinkedHashMap<>(namePhrases);
        filePhrases.forEach(merged::putIfAbsent);
        return List.copyOf(merged.values());
    }

    private List<Pattern> patterns(Collection<Phrase> phrases) {
        List<Pattern> patterns = new ArrayList<>();
        phrases.stream()
                .sorted(Comparator.comparingInt(Phrase::length).reversed())
                .forEach(phrase -> patterns.add(phrase.pattern()));
        textTerms.values().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .forEach(term -> patterns.add(Pattern.compile(
                        NOT_AFTER_WORD + Pattern.quote(term) + NOT_BEFORE_WORD, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                )));
        for (Phone phone : phones.values()) {
            patterns.add(phone.pattern());
        }
        return patterns;
    }

    private static boolean replaceValues(JsonNode node, String marker, List<Pattern> patterns) {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> property : List.copyOf(object.properties())) {
                JsonNode next = replacedValue(property.getValue(), marker, patterns);
                if (next != null) {
                    object.set(property.getKey(), next);
                    changed = true;
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode next = replacedValue(array.get(index), marker, patterns);
                if (next != null) {
                    array.set(index, next);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static JsonNode replacedValue(JsonNode value, String marker, List<Pattern> patterns) {
        if (value.isContainerNode()) {
            return replaceValues(value, marker, patterns) ? value : null;
        }
        if (!value.isTextual() || UUID_TEXT.matcher(value.textValue()).matches()) {
            return null;
        }
        String replaced = apply(value.textValue(), marker, patterns);
        return replaced.equals(value.textValue()) ? null : TextNode.valueOf(replaced);
    }

    private static String apply(String text, String marker, List<Pattern> patterns) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (Pattern pattern : patterns) {
            result = pattern.matcher(result).replaceAll(Matcher.quoteReplacement(marker));
        }
        return result;
    }

    private void addText(String value) {
        textTerms.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
    }

    private static void addPhrase(Map<String, Phrase> target, String value) {
        List<String> words = words(value);
        if (!words.isEmpty()) {
            target.putIfAbsent(String.join(" ", words), new Phrase(words));
        }
    }

    private static List<String> words(String value) {
        return Stream.of(value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")).filter(word -> !word.isEmpty()).toList();
    }

    private static String validatedText(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().replaceAll("\\s+", " ");
        if (normalized.length() < MIN_TEXT || normalized.length() > MAX_TEXT) {
            throw new InteractionValidationException(field, "Значение должно содержать от 3 до 200 символов");
        }
        return normalized;
    }

    private static String lenientText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip().replaceAll("\\s+", " ");
        return normalized.length() < MIN_TEXT ? null : normalized;
    }

    static String transliterate(String word) {
        StringBuilder result = new StringBuilder(word.length() + 4);
        for (char letter : word.toLowerCase(Locale.ROOT).toCharArray()) {
            result.append(switch (letter) {
                case 'а' -> "a";
                case 'б' -> "b";
                case 'в' -> "v";
                case 'г' -> "g";
                case 'д' -> "d";
                case 'е', 'ё', 'э' -> "e";
                case 'ж' -> "zh";
                case 'з' -> "z";
                case 'и' -> "i";
                case 'й', 'ы' -> "y";
                case 'к' -> "k";
                case 'л' -> "l";
                case 'м' -> "m";
                case 'н' -> "n";
                case 'о' -> "o";
                case 'п' -> "p";
                case 'р' -> "r";
                case 'с' -> "s";
                case 'т' -> "t";
                case 'у' -> "u";
                case 'ф' -> "f";
                case 'х' -> "kh";
                case 'ц' -> "ts";
                case 'ч' -> "ch";
                case 'ш' -> "sh";
                case 'щ' -> "shch";
                case 'ъ', 'ь' -> "";
                case 'ю' -> "yu";
                case 'я' -> "ya";
                default -> String.valueOf(letter);
            });
        }
        return result.toString();
    }

    record SqlMatch(String sql, Map<String, Object> params) {
    }

    private record Phrase(List<String> words) {
        int length() {
            return String.join(" ", words).length();
        }

        String like() {
            return words.stream().map(SearchPattern::contains).collect(Collectors.joining());
        }

        Pattern pattern() {
            return Pattern.compile(
                    NOT_AFTER_WORD + words.stream().map(Pattern::quote).collect(Collectors.joining(WORD_GAP)) + NOT_BEFORE_WORD,
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            );
        }
    }

    private record Phone(String core, boolean national) {
        static Phone parse(String value, int minDigits) {
            String digits = value.replaceAll("\\D", "");
            if (digits.length() < minDigits) {
                return null;
            }
            if (digits.length() == 11 && (digits.charAt(0) == '7' || digits.charAt(0) == '8')) {
                return new Phone(digits.substring(1), true);
            }
            return new Phone(digits, digits.length() == 10);
        }

        Pattern pattern() {
            String body = String.join(SEPARATORS, core.split(""));
            String prefix = national ? "(?:(?:\\+\\s*7|8)" + SEPARATORS + ")?" : "";
            return Pattern.compile(NOT_AFTER_WORD + prefix + body + NOT_BEFORE_WORD);
        }
    }
}
