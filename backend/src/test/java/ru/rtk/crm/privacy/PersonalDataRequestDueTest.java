package ru.rtk.crm.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

// Срок ответа: календарные дни или рабочие (пн–пт, праздники не учитываются) — выбор эксплуатанта в «Настройках».
class PersonalDataRequestDueTest {
    @Test
    void responseDeadlineCountsCalendarOrWorkingDays() {
        LocalDate friday = LocalDate.of(2026, 10, 16);
        assertThat(PersonalDataRequestService.dueDate(friday, 10, false)).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(PersonalDataRequestService.dueDate(friday, 10, true)).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(PersonalDataRequestService.dueDate(friday, 1, true)).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(PersonalDataRequestService.dueDate(LocalDate.of(2026, 10, 17), 1, true)).isEqualTo(LocalDate.of(2026, 10, 19));
    }
}
