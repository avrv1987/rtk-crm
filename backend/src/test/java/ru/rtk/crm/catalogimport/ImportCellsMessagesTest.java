package ru.rtk.crm.catalogimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import ru.rtk.crm.interaction.InteractionValidationException;

// Б-8, Б-9 проверки 2.5.3: понятные тексты ошибок импорта и «пусто» с учётом невидимых символов.
class ImportCellsMessagesTest {
    @Test
    void nonexistentDateIsReportedAsSuchAndBadFormatAsFormat() {
        PlannedRow row = row(Map.of("on", "29.02.2025", "at", "2026-02-31 10:00", "bad", "31/12/2026"));

        assertThat(ImportCells.date(row, "on")).isNull();
        assertThat(ImportCells.dateTime(row, "at")).isNull();
        assertThat(ImportCells.date(row, "bad")).isNull();

        assertThat(row.errors.get("on")).isEqualTo("Такой даты нет: проверьте день и месяц (29.02.2025)");
        assertThat(row.errors.get("at")).isEqualTo("Такой даты нет: проверьте день и месяц (2026-02-31)");
        assertThat(row.errors.get("bad")).startsWith("Укажите дату в формате");
        assertThat(ImportCells.date(row(Map.of("on", "29.02.2024")), "on")).hasToString("2024-02-29");
    }

    @Test
    void invisibleOnlyValuesAreEmpty() {
        PlannedRow row = row(Map.of("name", "​​", "comment", "  ⁠"));

        ImportCells.required(row, "name", 100);
        assertThat(ImportCells.text(row, "comment", 100)).isEmpty();
        assertThat(row.errors.get("name")).isEqualTo("Заполните значение");
    }

    @Test
    void utf16CsvGetsAnEncodingHint() {
        MockMultipartFile utf16 = new MockMultipartFile("file", "catalog.csv", "text/csv",
                "Вуз;ИНН\nУниверситет;123".getBytes(StandardCharsets.UTF_16LE));

        assertThatThrownBy(() -> new CatalogImportWorkbookReader().read(utf16, CatalogImportCsv.SHEET))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.getMessage()).contains("UTF-16").contains("CSV UTF-8"));
    }

    private static PlannedRow row(Map<String, String> values) {
        Map<String, String> columns = new java.util.LinkedHashMap<>();
        values.keySet().forEach(key -> columns.put(key, key));
        return new PlannedRow(new CatalogImportWorkbookRow(2, values, Map.of()), "Лист",
                new CatalogImportMapping(columns, Map.of(), Map.of(), null, null));
    }
}
