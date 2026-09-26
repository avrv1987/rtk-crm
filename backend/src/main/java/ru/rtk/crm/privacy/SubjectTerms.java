package ru.rtk.crm.privacy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final int MIN_WORD = 4;
    private static final int MIN_PHONE_DIGITS = 5;
    private static final int MAX_SPELLINGS = 10;
    private static final String SEPARATORS = "[\\s().+\\-]*";
    private static final String NOT_AFTER_WORD = "(?<![\\p{L}\\p{N}])";
    private static final String NOT_BEFORE_WORD = "(?![\\p{L}\\p{N}])";
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final String STRIPPED =
            "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(%s, ''), ' ', ''), '-', ''), '(', ''), ')', ''), '.', ''), '+', '')";

    private final Map<String, String> textTerms;
    private final Map<String, String> attachmentTerms;
    private final Map<String, Phone> phones;

    private SubjectTerms(Map<String, String> textTerms, Map<String, String> attachmentTerms, Map<String, Phone> phones) {
        this.textTerms = textTerms;
        this.attachmentTerms = attachmentTerms;
        this.phones = phones;
    }

    static SubjectTerms empty() {
        return new SubjectTerms(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    static SubjectTerms of(SubjectQuery query) {
        if (query == null) {
            throw new InteractionValidationException("name", "Укажите ФИО, почту, телефон, СНИЛС или другое написание");
        }
        SubjectTerms terms = empty();
        String name = validatedText(query.name(), "name");
        if (name != null) {
            terms.addName(name);
        }
        String email = validatedText(query.email(), "email");
        if (email != null) {
            terms.addText(email);
        }
        if (query.phone() != null && !query.phone().isBlank()) {
            Phone phone = Phone.parse(query.phone());
            if (phone == null) {
                throw new InteractionValidationException("phone", "Телефон должен содержать не меньше 5 цифр");
            }
            terms.phones.put(phone.core(), phone);
        }
        if (query.otherSpellings() != null && !query.otherSpellings().isBlank()) {
            List<String> spellings = List.of(query.otherSpellings().split("[,;\\n]"));
            if (spellings.size() > MAX_SPELLINGS) {
                throw new InteractionValidationException("otherSpellings", "Укажите не больше 10 других написаний");
            }
            for (String spelling : spellings) {
                String value = validatedText(spelling, "otherSpellings");
                if (value != null) {
                    terms.addText(value);
                    terms.addAttachmentTerm(value);
                }
            }
        }
        if (terms.isEmpty() && (query.snils() == null || query.snils().isBlank())) {
            throw new InteractionValidationException("name", "Укажите ФИО, почту, телефон, СНИЛС или другое написание");
        }
        return terms;
    }

    SubjectTerms with(String name, String email, String phone) {
        SubjectTerms copy = new SubjectTerms(
                new LinkedHashMap<>(textTerms), new LinkedHashMap<>(attachmentTerms), new LinkedHashMap<>(phones)
        );
        String normalizedName = lenientText(name);
        if (normalizedName != null) {
            copy.addName(normalizedName);
        }
        String normalizedEmail = lenientText(email);
        if (normalizedEmail != null) {
            copy.addText(normalizedEmail);
        }
        Phone parsedPhone = phone == null ? null : Phone.parse(phone);
        if (parsedPhone != null) {
            copy.phones.put(parsedPhone.core(), parsedPhone);
        }
        return copy;
    }

    boolean isEmpty() {
        return textTerms.isEmpty() && phones.isEmpty();
    }

    SqlMatch textMatch(String... columns) {
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

    SqlMatch attachmentMatch(String column) {
        List<String> parts = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        int index = 0;
        for (String term : attachmentNameTerms().values()) {
            String param = "a" + index++;
            params.put(param, SearchPattern.contains(term));
            parts.add("LOWER(" + column + ") LIKE :" + param + " " + SearchPattern.LIKE_ESCAPE);
        }
        return new SqlMatch(parts.isEmpty() ? "FALSE" : "(" + String.join(" OR ", parts) + ")", params);
    }

    String replace(String text, String marker) {
        return apply(text, marker, textPatterns());
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
        if (!replaceValues(root, marker, textPatterns())) {
            return json;
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Anonymized JSON cannot be written", exception);
        }
    }

    String replaceInAttachmentName(String text, String marker) {
        List<Pattern> patterns = new ArrayList<>(literalPatterns(attachmentNameTerms()));
        for (Phone phone : phones.values()) {
            patterns.add(phone.pattern());
        }
        return apply(text, marker, patterns);
    }

    private Map<String, String> attachmentNameTerms() {
        Map<String, String> merged = new LinkedHashMap<>(textTerms);
        attachmentTerms.forEach(merged::putIfAbsent);
        return merged;
    }

    private List<Pattern> textPatterns() {
        List<Pattern> patterns = new ArrayList<>(literalPatterns(textTerms));
        for (Phone phone : phones.values()) {
            patterns.add(phone.pattern());
        }
        return patterns;
    }

    private static List<Pattern> literalPatterns(Map<String, String> terms) {
        return terms.values().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(term -> Pattern.compile(
                        NOT_AFTER_WORD + Pattern.quote(term) + NOT_BEFORE_WORD, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                ))
                .toList();
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

    private void addName(String name) {
        addText(name);
        for (String word : name.split("[^\\p{L}]+")) {
            if (word.length() >= MIN_WORD) {
                addAttachmentTerm(word);
                addAttachmentTerm(transliterate(word));
            }
        }
    }

    private void addText(String value) {
        textTerms.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
    }

    private void addAttachmentTerm(String value) {
        attachmentTerms.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
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

    private record Phone(String core, boolean national) {
        static Phone parse(String value) {
            String digits = value.replaceAll("\\D", "");
            if (digits.length() < MIN_PHONE_DIGITS) {
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
