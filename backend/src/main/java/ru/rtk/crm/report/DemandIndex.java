package ru.rtk.crm.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.web.Plural;

// Индекс востребованности программы — паспорт М17 в docs/metrics.md. Каждый компонент переводится в процентиль среди
// программ той же выборки (права, период, отборы), поэтому разные шкалы не делятся друг на друга и не складываются
// в своих единицах (разбор А10). Вес компонента без данных делится между остальными, а доля веса с данными —
// покрытие: ниже порога места нет. Неизвестное не становится нулём.
public final class DemandIndex {
    public static final String INSUFFICIENT = "данных недостаточно";

    public enum Component {
        GROWTH("Прирост записанных"),
        COMPLETION("Доля завершивших"),
        ACTIVITY("Активность за 7 дней"),
        UNIVERSITY("Спрос вузов"),
        PAYMENTS("Оплаты открытого набора");

        private final String title;

        Component(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    public record Settings(
            int growthWeight,
            int completionWeight,
            int activityWeight,
            int universityWeight,
            int paymentsWeight,
            int minCoveragePercent,
            int smallBase
    ) {
        // Обоснование значений — в паспорте М17: 80 % веса у данных LMS, по 10 % у отдельно подписанных компонентов.
        public static final Settings DEFAULT = new Settings(30, 25, 25, 10, 10, 50, 10);

        public Settings {
            for (int weight : new int[]{growthWeight, completionWeight, activityWeight, universityWeight, paymentsWeight}) {
                if (weight < 0 || weight > 100) {
                    throw new InteractionValidationException("weights", "Вес компонента — целое число от 0 до 100");
                }
            }
            if (growthWeight + completionWeight + activityWeight + universityWeight + paymentsWeight != 100) {
                throw new InteractionValidationException("weights", "Сумма весов компонентов должна быть 100");
            }
            if (minCoveragePercent < 1 || minCoveragePercent > 100) {
                throw new InteractionValidationException("minCoveragePercent", "Порог покрытия — от 1 до 100 %");
            }
            if (smallBase < 0 || smallBase > 10_000) {
                throw new InteractionValidationException("smallBase", "Порог малой базы — от 0 до 10 000 участий");
            }
        }

        public int weight(Component component) {
            return switch (component) {
                case GROWTH -> growthWeight;
                case COMPLETION -> completionWeight;
                case ACTIVITY -> activityWeight;
                case UNIVERSITY -> universityWeight;
                case PAYMENTS -> paymentsWeight;
            };
        }

        public String describe() {
            return Arrays.stream(Component.values())
                    .map(component -> component.title().toLowerCase(java.util.Locale.ROOT) + " " + weight(component))
                    .collect(Collectors.joining(", "))
                    + "; порог покрытия " + minCoveragePercent + " %; малая база — меньше " + Plural.withCount(smallBase, "участия", "участий", "участий");
        }
    }

    /** Факты программы за период; null — источник значения не дал. */
    public record Input(
            boolean program,
            Long participants,
            Long previousParticipants,
            Long completed,
            Long completedBase,
            Long active7d,
            Long activityBase,
            Long interactions,
            Long paidOrders,
            long staleRuns
    ) {
    }

    public record Result(Integer place, BigDecimal index, Long growth, String contributions, String coverage) {
        static Result insufficient(Long growth, String contributions, String reason) {
            return new Result(null, null, growth, contributions, reason);
        }
    }

    private DemandIndex() {
    }

    // notApplicable — компоненты, которых в этой выборке не может быть по устройству: прирост без прошлого периода,
    // оплаты при выключенном модуле. Они не входят в знаменатель покрытия. Отсутствие данных у всех программ — не
    // повод выключить компонент: покрытие от этого падает, и место без LMS-данных не появляется.
    public static List<Result> rank(List<Input> inputs, Settings settings, int completionCoveragePercent,
                                    Set<Component> notApplicable) {
        Map<Component, Double[]> values = new EnumMap<>(Component.class);
        for (Component component : Component.values()) {
            Double[] column = new Double[inputs.size()];
            for (int index = 0; index < inputs.size(); index++) {
                column[index] = inputs.get(index).program() ? value(component, inputs.get(index), completionCoveragePercent) : null;
            }
            values.put(component, column);
        }
        List<Component> applicable = Arrays.stream(Component.values())
                .filter(component -> settings.weight(component) > 0 && !notApplicable.contains(component))
                .toList();
        int totalWeight = applicable.stream().mapToInt(settings::weight).sum();
        Map<Component, double[]> percentiles = new EnumMap<>(Component.class);
        applicable.forEach(component -> percentiles.put(component, percentiles(values.get(component))));

        List<Result> results = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            Input input = inputs.get(index);
            Long growth = values.get(Component.GROWTH)[index] == null ? null : values.get(Component.GROWTH)[index].longValue();
            if (!input.program()) {
                results.add(Result.insufficient(growth, null, "программа не указана"));
                continue;
            }
            int available = 0;
            for (Component component : applicable) {
                if (values.get(component)[index] != null) {
                    available += settings.weight(component);
                }
            }
            long coverage = totalWeight == 0 ? 0 : Math.round(available * 100.0 / totalWeight);
            if (input.participants() == null) {
                results.add(Result.insufficient(growth, null, "нет данных Moodle за период"));
                continue;
            }
            if (totalWeight == 0 || available * 100L < (long) settings.minCoveragePercent() * totalWeight) {
                results.add(Result.insufficient(growth, null,
                        "покрытие " + coverage + " % веса — ниже порога " + settings.minCoveragePercent() + " %"));
                continue;
            }
            List<String> parts = new ArrayList<>();
            double sum = 0;
            for (Component component : Component.values()) {
                Double raw = values.get(component)[index];
                if (!applicable.contains(component)) {
                    parts.add(component.title() + ": не применяется");
                } else if (raw == null) {
                    parts.add(component.title() + ": нет данных, вес перераспределён");
                } else {
                    double percentile = percentiles.get(component)[index];
                    double contribution = 100.0 * settings.weight(component) * percentile / available;
                    sum += contribution;
                    parts.add(component.title() + ": " + raw(component, raw) + ", процентиль "
                            + Math.round(percentile * 100) + " → " + decimal(round(contribution)));
                }
            }
            List<String> coverageNotes = new ArrayList<>(List.of(coverage + " % веса"));
            if (input.participants() < settings.smallBase()) {
                coverageNotes.add("малая база: " + participations(input.participants()));
            }
            if (input.staleRuns() > 0) {
                coverageNotes.add("данные Moodle устарели у потоков: " + input.staleRuns());
            }
            // Индекс округляется из точной суммы; вклады округлены каждый сам, поэтому их сумма может отличаться на 0,1.
            results.add(new Result(null, round(sum), growth, String.join("; ", parts), String.join("; ", coverageNotes)));
        }
        return places(results);
    }

    // Равные индексы делят место (1, 1, 3): порядок программ с равным индексом — по названию, а не скрытое старшинство.
    private static List<Result> places(List<Result> results) {
        List<BigDecimal> indexes = results.stream().map(Result::index).filter(java.util.Objects::nonNull).toList();
        return results.stream()
                .map(result -> result.index() == null ? result : new Result(
                        1 + (int) indexes.stream().filter(other -> other.compareTo(result.index()) > 0).count(),
                        result.index(), result.growth(), result.contributions(), result.coverage()))
                .toList();
    }

    private static Double value(Component component, Input input, int completionCoveragePercent) {
        return switch (component) {
            case GROWTH -> input.participants() == null || input.previousParticipants() == null
                    ? null : (double) (input.participants() - input.previousParticipants());
            // Доля — только по известным статусам и только при покрытии не ниже порога сигналов LMS (М12).
            case COMPLETION -> input.participants() == null || input.completedBase() == null || input.completedBase() == 0
                    || input.completedBase() * 100 < (long) completionCoveragePercent * input.participants()
                    ? null : (double) input.completed() / input.completedBase();
            case ACTIVITY -> input.active7d() == null || input.activityBase() == null || input.activityBase() == 0
                    ? null : (double) input.active7d() / input.activityBase();
            case UNIVERSITY -> input.interactions() == null ? null : input.interactions().doubleValue();
            case PAYMENTS -> input.paidOrders() == null ? null : input.paidOrders().doubleValue();
        };
    }

    // Процентиль со средним рангом: наименьшее — 0, наибольшее — 1, равные значения получают одно число; одна
    // программа с компонентом — 0,5, то есть ни выше, ни ниже других.
    static double[] percentiles(Double[] column) {
        List<Double> present = Arrays.stream(column).filter(java.util.Objects::nonNull).toList();
        double[] result = new double[column.length];
        for (int index = 0; index < column.length; index++) {
            Double value = column[index];
            if (value == null) {
                continue;
            }
            if (present.size() == 1) {
                result[index] = 0.5;
                continue;
            }
            long less = present.stream().filter(other -> other < value).count();
            long equal = present.stream().filter(other -> other.equals(value)).count();
            result[index] = (less + (equal - 1) / 2.0) / (present.size() - 1);
        }
        return result;
    }

    private static String raw(Component component, double value) {
        Function<Double, String> percent = share -> Math.round(share * 100) + " %";
        return switch (component) {
            case GROWTH -> (value > 0 ? "+" : value < 0 ? "−" : "") + participations(Math.round(Math.abs(value)));
            case COMPLETION, ACTIVITY -> percent.apply(value);
            case UNIVERSITY -> Plural.withCount(Math.round(value), "взаимодействие", "взаимодействия", "взаимодействий");
            case PAYMENTS -> Plural.withCount(Math.round(value), "оплата", "оплаты", "оплат");
        };
    }

    private static String participations(long count) {
        return Plural.withCount(count, "участие", "участия", "участий");
    }

    private static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    static String decimal(BigDecimal value) {
        return value.toPlainString().replace('.', ',');
    }
}
