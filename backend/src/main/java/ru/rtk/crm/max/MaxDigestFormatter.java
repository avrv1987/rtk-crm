package ru.rtk.crm.max;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import ru.rtk.crm.work.WorkModels.ReminderAgreement;
import ru.rtk.crm.work.WorkModels.ReminderDigest;
import ru.rtk.crm.work.WorkModels.ReminderFeed;
import ru.rtk.crm.work.WorkModels.ReminderStep;

// Текст утренней сводки из готовой ленты «Моей работы» (WorkService.reminders): те же группы и серверные итоги, что
// видит сотрудник в CRM; тем же текстом пользуется утреннее письмо модуля почты. По решению владельца в строках есть
// названия вузов; текст шага и комментарии сюда не попадают: их пишет КАМ, и там бывают ФИО и телефоны.
public final class MaxDigestFormatter {
    static final int STEP_LINES = 5;
    static final int AGREEMENT_LINES = 3;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private MaxDigestFormatter() {
    }

    // null — сказать нечего: пустая сводка не отправляется.
    public static String digest(ReminderDigest digest) {
        ReminderFeed feed = digest.feed();
        boolean work = feed.overdue() + feed.today() + feed.thisWeek() + feed.noNextStep() + feed.resumeDue() > 0;
        List<ReminderAgreement> agreements = digest.expiringAgreements() == null ? List.of() : digest.expiringAgreements();
        if (!work && agreements.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder("Доброе утро! Работа на сегодня\n")
                .append("Просрочено: ").append(feed.overdue())
                .append(" · Сегодня: ").append(feed.today())
                .append(" · На неделе: ").append(feed.thisWeek())
                .append(" · Без шага: ").append(feed.noNextStep());
        if (feed.resumeDue() > 0) {
            text.append("\nПора возобновить: ").append(feed.resumeDue());
        }
        List<String> steps = new ArrayList<>();
        for (ReminderStep step : digest.overdue()) {
            steps.add("• " + step.organizationName() + " — просрочено с " + DATE.format(step.nextActionAt().atZoneSameInstant(MaxProperties.ZONE)));
        }
        for (ReminderStep step : digest.upcoming()) {
            steps.add("• " + step.organizationName() + " — срок " + DATE.format(step.nextActionAt().atZoneSameInstant(MaxProperties.ZONE)));
        }
        if (!steps.isEmpty()) {
            text.append("\n\nБлижайшие шаги:\n").append(String.join("\n", steps.subList(0, Math.min(STEP_LINES, steps.size()))));
            if (steps.size() > STEP_LINES) {
                text.append("\n… и ещё ").append(steps.size() - STEP_LINES);
            }
        }
        if (!agreements.isEmpty()) {
            text.append("\n\nДоговоры с истекающим сроком:");
            for (ReminderAgreement agreement : agreements.subList(0, Math.min(AGREEMENT_LINES, agreements.size()))) {
                text.append("\n• № ").append(agreement.number()).append(", ").append(agreement.organizationName());
                if (agreement.validUntil() != null) {
                    text.append(" — до ").append(FULL_DATE.format(agreement.validUntil()));
                }
            }
            if (agreements.size() > AGREEMENT_LINES) {
                text.append("\n… и ещё ").append(agreements.size() - AGREEMENT_LINES);
            }
        }
        return text.toString();
    }

    static String assignment(String organizationName) {
        return "Вам назначен вуз «" + organizationName + "».";
    }

    static String agreementCorrection(String number, String organizationName) {
        return "Договор № " + number + " с «" + organizationName + "» вернули на доработку.";
    }

    static String agreementExpiry(String number, String organizationName, LocalDate validUntil, LocalDate today) {
        long days = validUntil.toEpochDay() - today.toEpochDay();
        return "Срок договора № " + number + " с «" + organizationName + "» истекает " + FULL_DATE.format(validUntil)
                + (days == 0 ? " (сегодня)." : " (через " + days + " дн.).");
    }

    static String siteRequest(String organizationName) {
        return "Новая заявка с сайта: «" + organizationName + "». Разберите её в CRM.";
    }
}
