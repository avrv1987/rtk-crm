package ru.rtk.crm.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PluralTest {
    @Test
    void choosesTheFormByTheLastDigitsAndIgnoresTheSign() {
        assertThat(Plural.withCount(1, "участие", "участия", "участий")).isEqualTo("1 участие");
        assertThat(Plural.withCount(2, "участие", "участия", "участий")).isEqualTo("2 участия");
        assertThat(Plural.withCount(4, "участие", "участия", "участий")).isEqualTo("4 участия");
        assertThat(Plural.withCount(5, "участие", "участия", "участий")).isEqualTo("5 участий");
        assertThat(Plural.withCount(11, "участие", "участия", "участий")).isEqualTo("11 участий");
        assertThat(Plural.withCount(14, "участие", "участия", "участий")).isEqualTo("14 участий");
        assertThat(Plural.withCount(21, "участие", "участия", "участий")).isEqualTo("21 участие");
        assertThat(Plural.withCount(112, "участие", "участия", "участий")).isEqualTo("112 участий");
        assertThat(Plural.withCount(0, "участие", "участия", "участий")).isEqualTo("0 участий");
        assertThat(Plural.form(-1, "участие", "участия", "участий")).isEqualTo("участие");
        assertThat(Plural.form(-3, "день", "дня", "дней")).isEqualTo("дня");
    }
}
