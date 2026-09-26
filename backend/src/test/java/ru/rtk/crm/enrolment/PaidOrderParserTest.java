package ru.rtk.crm.enrolment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.rtk.crm.interaction.InteractionValidationException;

class PaidOrderParserTest {
    private final PaidOrderParser parser = new PaidOrderParser(new ObjectMapper());

    @Test
    void parsesOrganizerShapedArraySkippingNullAndNormalizingFields() {
        PaidOrderBatch batch = parser.parse(json("""
                [null,
                 {"Номер заявки": "ORD-20261799999999-TST001", "Курс": "  Инженер-тестировщик   (демо) ",
                  "Фамилия": " Тестова ", "Имя": "Анна", "Отчество": "Сергеевна",
                  "Телефон": "7 (900) 000-00-01", "Email": " Anna.Testova@Example.test ", "Номер потока": 2},
                 {"Номер заявки": "ORD-2", "Курс": "Промпт-инжиниринг", "Фамилия": "Примеров", "Имя": "Пётр",
                  "Телефон": "8 900 000 00 02", "Email": "petr@example.test", "Номер потока": 1}]
                """));

        assertThat(batch.emptyElements()).isEqualTo(1);
        assertThat(batch.issues()).containsExactly(
                new PaidOrderIssue(1, null, "Пустой элемент пропущен"),
                new PaidOrderIssue(3, "Номер заявки",
                        "Номер заявки не соответствует шаблону ORD-<цифры>-<латинские буквы и цифры>; запись принята", true)
        );
        assertThat(batch.orders()).hasSize(2);
        PaidOrder first = batch.orders().get(0);
        assertThat(first.orderNumber()).isEqualTo("ORD-20261799999999-TST001");
        assertThat(first.course()).isEqualTo("Инженер-тестировщик (демо)");
        assertThat(first.lastName()).isEqualTo("Тестова");
        assertThat(first.phone()).isEqualTo("+79000000001");
        assertThat(first.email()).isEqualTo("Anna.Testova@Example.test");
        assertThat(first.emailKey()).isEqualTo("anna.testova@example.test");
        assertThat(first.streamNumber()).isEqualTo(2);
        assertThat(first.version()).matches("[0-9a-f]{64}");
        assertThat(first.toString()).doesNotContain("Тестова", "+79000000001", "example.test");
        assertThat(batch.orders().get(1).middleName()).isNull();
        assertThat(batch.orders().get(1).phone()).isEqualTo("+79000000002");
    }

    @Test
    void reportsEveryElementProblemWithoutEchoingValues() {
        PaidOrderBatch batch = parser.parse(json("""
                [{"Курс": "Курс", "Фамилия": "Тестов", "Имя": "Иван", "Телефон": "9000000003",
                  "Email": "ivan@example.test", "Номер потока": 1},
                 {"Номер заявки": "ORD-3", "Курс": "Курс", "Фамилия": "Тестов", "Имя": "  ",
                  "Телефон": "12345", "Email": "ivan-at-example", "Номер потока": 0},
                 "строка",
                 {"Номер заявки": "ORD-4", "Курс": ["Курс"], "Фамилия": "Тестов", "Имя": "Иван",
                  "Телефон": "9000000004", "Email": "ivan4@example.test", "Номер потока": "2"},
                 {"Номер заявки": "ORD-5", "Курс": "Курс", "Фамилия": "Тестов", "Имя": "Иван",
                  "Телефон": "9000000005", "Email": "ivan5@example.test", "Номер потока": 1.5}]
                """));

        assertThat(batch.orders()).isEmpty();
        assertThat(batch.issues()).containsExactly(
                new PaidOrderIssue(1, "Номер заявки", "Нет поля «Номер заявки»"),
                new PaidOrderIssue(2, "Имя", "Поле «Имя» пустое; поле не сохранено", true),
                new PaidOrderIssue(2, "Телефон", "Телефон должен содержать 10 цифр после +7 или 8; поле не сохранено", true),
                new PaidOrderIssue(2, "Email", "Email указан неверно; поле не сохранено", true),
                new PaidOrderIssue(2, "Номер потока", "Номер потока должен быть целым числом больше 0"),
                new PaidOrderIssue(3, null, "Элемент не является объектом с полями заявки"),
                new PaidOrderIssue(4, "Курс", "Поле «Курс» должно быть строкой"),
                new PaidOrderIssue(4, "Номер потока", "Номер потока должен быть целым числом больше 0"),
                new PaidOrderIssue(5, "Номер потока", "Номер потока должен быть целым числом больше 0")
        );
        assertThat(batch.issues()).extracting(PaidOrderIssue::message)
                .noneMatch(message -> message.contains("12345") || message.contains("ivan-at-example"));
    }

    @Test
    void acceptsOrderWhoseContactsFailChecksAndDropsOnlyThoseFields() {
        PaidOrderBatch batch = parser.parse(json("""
                [null,
                 {"Номер заявки": "ORD-20260901000001-TST001", "Курс": "Курс А", "Имя": "Иван",
                  "Телефон": "12345", "Email": "ivan-at-example", "Номер потока": 3}]
                """));

        assertThat(batch.orders()).singleElement().satisfies(order -> {
            assertThat(order.lastName()).isNull();
            assertThat(order.phone()).isNull();
            assertThat(order.email()).isNull();
            assertThat(order.emailKey()).isNull();
            assertThat(order.streamNumber()).isEqualTo(3);
        });
        assertThat(batch.issues()).filteredOn(PaidOrderIssue::warning).extracting(PaidOrderIssue::field)
                .containsExactly("Фамилия", "Телефон", "Email");
        assertThat(batch.issues()).extracting(PaidOrderIssue::message)
                .noneMatch(message -> message.contains("12345") || message.contains("ivan-at-example"));
        assertThat(batch.received()).isEqualTo(1);
        assertThat(batch.rejected()).isZero();
    }

    @Test
    void keepsOneOfIdenticalDuplicatesAndRejectsConflictingOnes() {
        String same = order("ORD-6-TST", "Курс А", 1, "Тестов");
        String conflictA = order("ORD-7-TST", "Курс А", 1, "Тестов");
        String conflictB = order("ORD-7-TST", "Курс А", 2, "Тестов");
        PaidOrderBatch batch = parser.parse(json("[" + String.join(",", same, same, conflictA, conflictB) + "]"));

        assertThat(batch.orders()).extracting(PaidOrder::orderNumber).containsExactly("ORD-6-TST");
        assertThat(batch.duplicates()).isEqualTo(1);
        assertThat(batch.rejected()).isEqualTo(2);
        assertThat(batch.issues()).containsExactly(
                new PaidOrderIssue(2, "Номер заявки", "Повтор заявки с тем же содержимым пропущен"),
                new PaidOrderIssue(3, "Номер заявки", "Номер заявки повторяется в файле с разным содержимым; запись не принята"),
                new PaidOrderIssue(4, "Номер заявки", "Номер заявки повторяется в файле с разным содержимым; запись не принята")
        );
    }

    @Test
    void versionDependsOnlyOnCourseAndStream() {
        List<PaidOrder> orders = parser.parse(json("[" + String.join(",",
                order("ORD-8-TST", "Курс А", 1, "Тестов"),
                order("ORD-9-TST", "Курс  А", 1, "Иначе"),
                order("ORD-10-TST", "Курс Б", 1, "Тестов"),
                order("ORD-11-TST", "Курс А", 2, "Тестов")
        ) + "]")).orders();

        assertThat(orders).allSatisfy(order -> assertThat(order.version()).matches("[0-9a-f]{64}"));
        assertThat(orders.get(1).version()).isEqualTo(orders.get(0).version());
        assertThat(orders.get(2).version()).isNotEqualTo(orders.get(0).version());
        assertThat(orders.get(3).version()).isNotEqualTo(orders.get(0).version()).isNotEqualTo(orders.get(2).version());
    }

    @Test
    void rejectsWholeFileThatIsNotABoundedJsonArray() {
        assertThatThrownBy(() -> parser.parse(json("{\"items\": []}")))
                .isInstanceOf(InteractionValidationException.class)
                .hasMessage("Файл оплат должен содержать JSON-массив записей");
        assertThatThrownBy(() -> parser.parse(json("[{")))
                .isInstanceOf(InteractionValidationException.class)
                .hasMessage("Файл оплат не является корректным JSON");
        assertThatThrownBy(() -> parser.parse(json("")))
                .isInstanceOf(InteractionValidationException.class)
                .hasMessage("Файл оплат должен содержать JSON-массив записей");
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(new byte[PaidOrderParser.MAX_BYTES + 1])))
                .isInstanceOf(InteractionValidationException.class)
                .hasMessage("Файл оплат больше 5 МиБ");
        String tooMany = "[" + "null,".repeat(PaidOrderParser.MAX_ELEMENTS) + "null]";
        assertThatThrownBy(() -> parser.parse(json(tooMany)))
                .isInstanceOf(InteractionValidationException.class)
                .hasMessage("В файле оплат больше 10000 элементов; разделите файл");
    }

    private static String order(String number, String course, int stream, String lastName) {
        return """
                {"Номер заявки": "%s", "Курс": "%s", "Фамилия": "%s", "Имя": "Иван", "Отчество": "Иванович",
                 "Телефон": "+7 900 000-00-09", "Email": "ivan@example.test", "Номер потока": %d}
                """.formatted(number, course, lastName, stream);
    }

    private static InputStream json(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
