package ru.rtk.crm.agreement;

import java.time.LocalDate;

import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.web.InputRules;

// Правила дат договора одни для ручного создания и правки и для импорта договоров из файла.
public final class AgreementDateRules {
    // Окно разумных дат договора: 0001 и 9999 из опечаток портили реестр и отчёты.
    static final int YEARS_BACK = 30;
    static final int YEARS_AHEAD = 30;

    private AgreementDateRules() {
    }

    public static void check(AgreementStatus status, LocalDate concludedOn, LocalDate validUntil, LocalDate sentOn, LocalDate today) {
        if (concludedOn != null && validUntil != null && concludedOn.isAfter(validUntil)) {
            throw new InteractionValidationException("validUntil", "Срок действия раньше даты подписания");
        }
        // Подписанный договор с датой подписания в будущем закрывал бы этап «Подписание» договором, которого ещё нет:
        // то же правило, что и при переходе в «Действует», действует и при создании и правке.
        if (status.signed() && concludedOn != null && concludedOn.isAfter(today)) {
            throw new InteractionValidationException("concludedOn", "Дата подписания не может быть позже сегодняшней");
        }
        InputRules.requireDateWithin(concludedOn, "concludedOn", today, YEARS_BACK, YEARS_AHEAD);
        InputRules.requireDateWithin(validUntil, "validUntil", today, YEARS_BACK, YEARS_AHEAD);
        InputRules.requireDateWithin(sentOn, "sentOn", today, YEARS_BACK, 0);
    }
}
