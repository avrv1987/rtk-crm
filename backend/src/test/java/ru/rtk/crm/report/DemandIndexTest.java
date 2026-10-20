package ru.rtk.crm.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.DemandIndex.Component;
import ru.rtk.crm.report.DemandIndex.Input;
import ru.rtk.crm.report.DemandIndex.Result;
import ru.rtk.crm.report.DemandIndex.Settings;

// Расчёт индекса востребованности (М17) без базы: детерминированность, равные и пустые данные, малое покрытие,
// перераспределение веса и «данных недостаточно» вместо нуля.
class DemandIndexTest {
    private static final Set<Component> ALL = Set.of();

    @Test
    void ranksByWeightedPercentilesAndContributionsSumToTheIndex() {
        List<Result> results = DemandIndex.rank(List.of(
                full(40, 20, 32, 40, 30, 40, 5, 3),
                full(20, 10, 12, 20, 10, 20, 2, 1),
                full(10, 10, 1, 10, 2, 10, 0, null)
        ), Settings.DEFAULT, 70, ALL);

        assertThat(results).extracting(Result::place).containsExactly(1, 2, 3);
        assertThat(results.getFirst().index()).isEqualByComparingTo("100.0");
        // Третья: всё наименьшее, у оплат нет данных — вес оплат делится между остальными, покрытие 90 %.
        assertThat(results.get(2).index()).isEqualByComparingTo("0.0");
        assertThat(results.get(2).coverage()).startsWith("90 % веса");
        assertThat(results.get(2).contributions()).contains("Оплаты открытого набора: нет данных, вес перераспределён");
        for (Result result : results) {
            BigDecimal sum = contributions(result.contributions());
            assertThat(sum.subtract(result.index()).abs()).isLessThanOrEqualTo(new BigDecimal("0.2"));
        }
        // Три равных вклада по 33,33…: каждый округлён до 33,3, индекс — из точной суммы, 100,0, а не 99,9.
        List<Result> thirds = DemandIndex.rank(List.of(
                new Input(true, 10L, null, 9L, 10L, 9L, 10L, 3L, null, 0),
                new Input(true, 10L, null, 1L, 10L, 1L, 10L, 1L, null, 0)
        ), new Settings(0, 30, 30, 30, 10, 50, 10), 70, ALL);
        assertThat(thirds.getFirst().index()).isEqualByComparingTo("100.0");
        assertThat(results.getFirst().growth()).isEqualTo(20L);
        assertThat(results.getFirst().contributions()).startsWith("Прирост записанных: +20 участий, процентиль 100 → 30,0");
    }

    // Н3 (2.5.3): число согласовано со словом — тексты вклада и покрытия идут на экран, в XLSX и в MCP одинаково.
    @Test
    void contributionAndCoverageTextsAgreeInNumber() {
        List<Result> results = DemandIndex.rank(List.of(
                new Input(true, 4L, 5L, null, null, null, null, 2L, 1L, 0),
                new Input(true, 22L, 1L, null, null, null, null, 21L, 3L, 0)
        ), Settings.DEFAULT, 70, ALL);
        Result small = results.stream().filter(result -> result.coverage().contains("малая база")).findFirst().orElseThrow();
        assertThat(small.coverage()).contains("малая база: 4 участия");
        assertThat(small.contributions()).contains(": −1 участие,", ": 2 взаимодействия,", ": 1 оплата,")
                .doesNotContain("участий", "взаимодействий", "оплат,");
        Result big = results.stream().filter(result -> result != small).findFirst().orElseThrow();
        assertThat(big.contributions()).contains(": +21 участие,", ": 21 взаимодействие,", ": 3 оплаты,");
    }

    @Test
    void sameInputGivesSameResultAndEqualProgramsShareThePlace() {
        List<Input> inputs = List.of(full(10, 5, 2, 10, 5, 10, 1, 1), full(10, 5, 2, 10, 5, 10, 1, 1),
                full(4, 5, 1, 4, 1, 4, 0, 1));
        List<Result> first = DemandIndex.rank(inputs, Settings.DEFAULT, 70, ALL);

        assertThat(DemandIndex.rank(inputs, Settings.DEFAULT, 70, ALL)).isEqualTo(first);
        assertThat(first).extracting(Result::place).containsExactly(1, 1, 3);
        assertThat(first.get(0).index()).isEqualByComparingTo(first.get(1).index());

        List<Result> equal = DemandIndex.rank(List.of(full(10, 5, 2, 10, 5, 10, 1, 1), full(10, 5, 2, 10, 5, 10, 1, 1)),
                Settings.DEFAULT, 70, ALL);
        assertThat(equal).extracting(Result::place).containsExactly(1, 1);
        assertThat(equal).extracting(Result::index).allSatisfy(index -> assertThat(index).isEqualByComparingTo("50.0"));
    }

    @Test
    void emptyAndMissingDataGiveNoPlaceInsteadOfZero() {
        assertThat(DemandIndex.rank(List.of(), Settings.DEFAULT, 70, ALL)).isEmpty();

        List<Result> results = DemandIndex.rank(List.of(
                new Input(true, null, null, null, null, null, null, 3L, 5L, 0),
                new Input(false, 10L, 5L, null, null, null, null, null, null, 0),
                full(10, 5, 2, 10, 5, 10, 1, 1)
        ), Settings.DEFAULT, 70, ALL);

        assertThat(results.get(0).place()).isNull();
        assertThat(results.get(0).index()).isNull();
        assertThat(results.get(0).coverage()).isEqualTo("нет данных Moodle за период");
        assertThat(results.get(1).place()).isNull();
        assertThat(results.get(1).coverage()).isEqualTo("программа не указана");
        assertThat(results.get(2).place()).isEqualTo(1);
        assertThat(results.get(2).index()).isEqualByComparingTo("40.0");
    }

    @Test
    void lowCompletionCoverageDropsTheComponentAndLowWeightCoverageDropsThePlace() {
        // Статус завершения известен у 6 из 10 — меньше 70 %: доля не считается, её вес уходит остальным.
        List<Result> lowStatus = DemandIndex.rank(List.of(
                new Input(true, 10L, 5L, 3L, 6L, 5L, 10L, 2L, 1L, 0),
                new Input(true, 20L, 5L, 18L, 20L, 15L, 20L, 4L, 2L, 0)
        ), Settings.DEFAULT, 70, ALL);
        assertThat(lowStatus.getFirst().contributions()).contains("Доля завершивших: нет данных, вес перераспределён");
        assertThat(lowStatus.getFirst().coverage()).startsWith("75 % веса");
        assertThat(lowStatus.getFirst().place()).isEqualTo(2);

        // Есть только обучающиеся и спрос вузов: 10 % веса из 100 — ниже порога 50 %, места нет.
        List<Result> lowWeight = DemandIndex.rank(List.of(new Input(true, 10L, null, null, null, null, null, 2L, null, 0)),
                Settings.DEFAULT, 70, ALL);
        assertThat(lowWeight.getFirst().place()).isNull();
        assertThat(lowWeight.getFirst().coverage()).isEqualTo("покрытие 10 % веса — ниже порога 50 %");
    }

    @Test
    void structurallyMissingComponentsLeaveTheDenominatorAndMarksNameSmallBaseAndStaleData() {
        List<Result> results = DemandIndex.rank(List.of(
                new Input(true, 6L, null, 5L, 6L, 3L, 6L, 1L, null, 2),
                new Input(true, 30L, null, 10L, 30L, 20L, 30L, 3L, null, 0)
        ), Settings.DEFAULT, 70, Set.of(Component.GROWTH, Component.PAYMENTS));

        assertThat(results.getFirst().contributions()).contains("Прирост записанных: не применяется")
                .contains("Оплаты открытого набора: не применяется");
        assertThat(results.getFirst().coverage())
                .isEqualTo("100 % веса; малая база: 6 участий; данные Moodle устарели у потоков: 2");
        assertThat(results.get(1).coverage()).isEqualTo("100 % веса");
    }

    @Test
    void settingsRejectWeightsThatDoNotSumToHundred() {
        assertThatThrownBy(() -> new Settings(30, 30, 30, 10, 10, 50, 10)).isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> new Settings(-10, 50, 40, 10, 10, 50, 10)).isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> new Settings(30, 25, 25, 10, 10, 0, 10)).isInstanceOf(InteractionValidationException.class);
        assertThat(Settings.DEFAULT.describe()).startsWith("прирост записанных 30, доля завершивших 25, активность за 7 дней 25");
        assertThat(DemandIndex.percentiles(new Double[]{1.0, null, 3.0, 3.0})).containsExactly(0.0, 0.0, 0.75, 0.75);
    }

    private static Input full(long participants, long previous, long completed, long known, long active, long activityBase,
                              long interactions, Integer paid) {
        return new Input(true, participants, previous, completed, known, active, activityBase, interactions,
                paid == null ? null : paid.longValue(), 0);
    }

    private static BigDecimal contributions(String text) {
        BigDecimal sum = BigDecimal.ZERO;
        for (String part : text.split("; ")) {
            int arrow = part.indexOf(" → ");
            if (arrow >= 0) {
                sum = sum.add(new BigDecimal(part.substring(arrow + 3).replace(',', '.')));
            }
        }
        return sum;
    }
}
