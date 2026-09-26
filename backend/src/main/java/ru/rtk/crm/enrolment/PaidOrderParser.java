package ru.rtk.crm.enrolment;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.InteractionValidationException;

@Component
public class PaidOrderParser {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_ELEMENTS = 10_000;
    static final String ORDER_NUMBER = "Номер заявки";
    static final String COURSE = "Курс";
    static final String LAST_NAME = "Фамилия";
    static final String FIRST_NAME = "Имя";
    static final String MIDDLE_NAME = "Отчество";
    static final String PHONE = "Телефон";
    static final String EMAIL = "Email";
    static final String STREAM_NUMBER = "Номер потока";
    private static final Pattern ORDER_NUMBER_FORM = Pattern.compile("ORD-\\d+-[A-Z0-9]+");

    private final ObjectMapper objectMapper;

    public PaidOrderParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PaidOrderBatch parse(InputStream input) {
        JsonNode root = read(input);
        if (root == null || !root.isArray()) {
            throw new InteractionValidationException("file", "Файл оплат должен содержать JSON-массив записей");
        }
        if (root.size() > MAX_ELEMENTS) {
            throw new InteractionValidationException("file", "В файле оплат больше " + MAX_ELEMENTS + " элементов; разделите файл");
        }
        List<PaidOrderIssue> issues = new ArrayList<>();
        Map<String, List<Candidate>> byNumber = new LinkedHashMap<>();
        int emptyElements = 0;
        for (int index = 0; index < root.size(); index++) {
            int position = index + 1;
            JsonNode item = root.get(index);
            if (item.isNull()) {
                emptyElements++;
                issues.add(new PaidOrderIssue(position, null, "Пустой элемент пропущен"));
            } else if (!item.isObject()) {
                issues.add(new PaidOrderIssue(position, null, "Элемент не является объектом с полями заявки"));
            } else {
                PaidOrder order = order(item, position, issues);
                if (order != null) {
                    byNumber.computeIfAbsent(order.orderNumber(), key -> new ArrayList<>()).add(new Candidate(position, order));
                }
            }
        }
        List<PaidOrder> orders = new ArrayList<>();
        for (List<Candidate> candidates : byNumber.values()) {
            Candidate first = candidates.get(0);
            boolean identical = candidates.stream().allMatch(candidate -> candidate.order().equals(first.order()));
            if (identical) {
                orders.add(first.order());
                candidates.stream().skip(1).forEach(candidate -> issues.add(new PaidOrderIssue(
                        candidate.position(), ORDER_NUMBER, "Повтор заявки с тем же содержимым пропущен"
                )));
            } else {
                candidates.forEach(candidate -> issues.add(new PaidOrderIssue(
                        candidate.position(), ORDER_NUMBER, "Номер заявки повторяется в файле с разным содержимым; запись не принята"
                )));
            }
        }
        issues.sort(Comparator.comparingInt(PaidOrderIssue::position));
        return new PaidOrderBatch(List.copyOf(orders), List.copyOf(issues), emptyElements);
    }

    private JsonNode read(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new InteractionValidationException("file", "Файл оплат больше " + MAX_BYTES / (1024 * 1024) + " МиБ");
            }
            return objectMapper.readTree(bytes);
        } catch (IOException exception) {
            throw new InteractionValidationException("file", "Файл оплат не является корректным JSON");
        }
    }

    private PaidOrder order(JsonNode item, int position, List<PaidOrderIssue> issues) {
        int issuesBefore = issues.size();
        String orderNumber = text(item, ORDER_NUMBER, 100, true, position, issues);
        String course = text(item, COURSE, 300, true, position, issues);
        String lastName = text(item, LAST_NAME, 100, true, position, issues);
        String firstName = text(item, FIRST_NAME, 100, true, position, issues);
        String middleName = text(item, MIDDLE_NAME, 100, false, position, issues);
        String phoneText = text(item, PHONE, 50, true, position, issues);
        String phone = phoneText == null ? null : LearnerRules.normalizePhone(phoneText);
        if (phoneText != null && phone == null) {
            issues.add(new PaidOrderIssue(position, PHONE, "Телефон должен содержать 10 цифр после +7 или 8"));
        }
        String email = text(item, EMAIL, LearnerRules.MAX_EMAIL_LENGTH, true, position, issues);
        if (email != null && !LearnerRules.isEmail(email)) {
            issues.add(new PaidOrderIssue(position, EMAIL, "Email указан неверно"));
        }
        Integer streamNumber = streamNumber(item, position, issues);
        if (issues.size() > issuesBefore) {
            return null;
        }
        if (!ORDER_NUMBER_FORM.matcher(orderNumber).matches()) {
            issues.add(new PaidOrderIssue(position, ORDER_NUMBER,
                    "Номер заявки не соответствует шаблону ORD-<цифры>-<латинские буквы и цифры>; запись принята", true));
        }
        String version = CommandFingerprint.of(objectMapper, List.of(course, streamNumber));
        return new PaidOrder(orderNumber, course, streamNumber, lastName, firstName, middleName, phone, email, version);
    }

    private static String text(
            JsonNode item, String field, int maxLength, boolean required, int position, List<PaidOrderIssue> issues
    ) {
        JsonNode value = item.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                issues.add(new PaidOrderIssue(position, field, "Нет поля «" + field + "»"));
            }
            return null;
        }
        if (!value.isTextual() && !value.isIntegralNumber()) {
            issues.add(new PaidOrderIssue(position, field, "Поле «" + field + "» должно быть строкой"));
            return null;
        }
        String text = LearnerRules.collapseSpaces(value.asText());
        if (text.isEmpty()) {
            if (required) {
                issues.add(new PaidOrderIssue(position, field, "Поле «" + field + "» пустое"));
            }
            return null;
        }
        if (text.length() > maxLength) {
            issues.add(new PaidOrderIssue(position, field, "Поле «" + field + "» длиннее " + maxLength + " символов"));
            return null;
        }
        return text;
    }

    private static Integer streamNumber(JsonNode item, int position, List<PaidOrderIssue> issues) {
        JsonNode value = item.get(STREAM_NUMBER);
        if (value == null || value.isNull()) {
            issues.add(new PaidOrderIssue(position, STREAM_NUMBER, "Нет поля «" + STREAM_NUMBER + "»"));
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            issues.add(new PaidOrderIssue(position, STREAM_NUMBER, "Номер потока должен быть целым числом больше 0"));
            return null;
        }
        return value.intValue();
    }

    private record Candidate(int position, PaidOrder order) {
    }
}
