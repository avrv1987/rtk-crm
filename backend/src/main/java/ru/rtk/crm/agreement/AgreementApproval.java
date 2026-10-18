package ru.rtk.crm.agreement;

import java.time.LocalDate;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.Approval;
import ru.rtk.crm.agreement.AgreementModels.ApprovalColor;
import ru.rtk.crm.agreement.AgreementModels.ApprovalSide;
import ru.rtk.crm.agreement.AgreementModels.StatusEntry;

// Пороги согласования договора по эталону (Д-12): у вуза 6 недель в первом круге и 3 недели в следующих, у школы
// неделя; общий срок от отправки — жёлтый на 60-й день, красный на 90-й. Дни календарные, июль и август не считаются:
// в это время вузы почти не согласуют договоры (Д-02).
@Component
public class AgreementApproval {
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    private final int universityFirstRoundDays;
    private final int universityNextRoundDays;
    private final int schoolDays;
    private final int totalYellowDays;
    private final int totalRedDays;
    private final Set<Month> excludedMonths;

    public AgreementApproval(
            @Value("${app.agreements.approval.university-first-round-days:42}") int universityFirstRoundDays,
            @Value("${app.agreements.approval.university-next-round-days:21}") int universityNextRoundDays,
            @Value("${app.agreements.approval.school-days:7}") int schoolDays,
            @Value("${app.agreements.approval.total-yellow-days:60}") int totalYellowDays,
            @Value("${app.agreements.approval.total-red-days:90}") int totalRedDays,
            @Value("${app.agreements.approval.excluded-months:7,8}") List<Integer> excludedMonths
    ) {
        this.universityFirstRoundDays = universityFirstRoundDays;
        this.universityNextRoundDays = universityNextRoundDays;
        this.schoolDays = schoolDays;
        this.totalYellowDays = totalYellowDays;
        this.totalRedDays = totalRedDays;
        this.excludedMonths = EnumSet.noneOf(Month.class);
        excludedMonths.forEach(month -> this.excludedMonths.add(Month.of(month)));
    }

    public static boolean inProgress(AgreementStatus status) {
        return status == AgreementStatus.REVIEW || status == AgreementStatus.CORRECTION || status == AgreementStatus.SIGNING;
    }

    // Круг — номер прохода «На согласовании» по истории статусов; сторона — по текущему статусу. Дни на стороне — с
    // последней смены статуса, общий срок — с даты отправки (если её нет — с первого входа в «На согласовании»).
    // Исключение — первый круг у вуза: введённая дата отправки и есть начало этого круга (решение владельца 18.10), иначе
    // договор, внесённый задним числом, получил бы порог «6 недель у вуза» позже на всё время до внесения. Для следующих
    // кругов даты отправки нет — они считаются от смены статуса.
    // history — переходы договора по времени. Для договоров не на согласовании возвращается null.
    public Approval state(AgreementStatus status, OffsetDateTime statusChangedAt, LocalDate sentOn, List<StatusEntry> history,
                          LocalDate today) {
        if (!inProgress(status)) {
            return null;
        }
        int round = (int) Math.max(1, history.stream().filter(entry -> entry.toStatus() == AgreementStatus.REVIEW).count());
        ApprovalSide side = switch (status) {
            case REVIEW -> ApprovalSide.UNIVERSITY;
            case CORRECTION -> ApprovalSide.SCHOOL;
            default -> ApprovalSide.SIGNING;
        };
        Integer sideLimit = switch (side) {
            case UNIVERSITY -> round == 1 ? universityFirstRoundDays : universityNextRoundDays;
            case SCHOOL -> schoolDays;
            case SIGNING -> null;
        };
        LocalDate sideSince = side == ApprovalSide.UNIVERSITY && round == 1 && sentOn != null ? sentOn : date(statusChangedAt);
        LocalDate since = sentOn != null ? sentOn : history.stream()
                .filter(entry -> entry.toStatus() == AgreementStatus.REVIEW)
                .map(entry -> date(entry.occurredAt()))
                .findFirst()
                .orElse(date(statusChangedAt));
        long sideDays = countedDays(sideSince, today);
        long totalDays = countedDays(since, today);
        ApprovalColor color = totalDays >= totalRedDays ? ApprovalColor.RED
                : totalDays >= totalYellowDays || sideLimit != null && sideDays > sideLimit ? ApprovalColor.YELLOW
                : ApprovalColor.NORMAL;
        return new Approval(side, round, sideDays, sideLimit, totalDays, color);
    }

    public String rule() {
        String excluded = excludedMonths.isEmpty() ? "" : "; не считаются: " + excludedMonths.stream()
                .map(month -> month.getDisplayName(TextStyle.FULL_STANDALONE, RUSSIAN))
                .collect(Collectors.joining(", "));
        return "у вуза дольше " + universityFirstRoundDays + " дн. в первом круге и " + universityNextRoundDays + " дн. в следующих,"
                + " у школы дольше " + schoolDays + " дн.; общий срок от отправки: жёлтый с " + totalYellowDays + "-го дня, красный с "
                + totalRedDays + "-го" + excluded;
    }

    // Сколько дней прошло после from по to включительно без исключённых месяцев.
    public long countedDays(LocalDate from, LocalDate to) {
        long days = 0;
        for (LocalDate day = from.plusDays(1); !day.isAfter(to); day = day.plusDays(1)) {
            if (!excludedMonths.contains(day.getMonth())) {
                days++;
            }
        }
        return days;
    }

    private static LocalDate date(OffsetDateTime value) {
        return value.atZoneSameInstant(AgreementService.ZONE).toLocalDate();
    }
}
