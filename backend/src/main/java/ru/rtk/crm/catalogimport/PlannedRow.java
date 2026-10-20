package ru.rtk.crm.catalogimport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class PlannedRow {
    static final CatalogImportRowTarget EMPTY_TARGET = new CatalogImportRowTarget(null, null, null, null);

    final UUID id = UUID.randomUUID();
    final String sheetName;
    final int rowNumber;
    final CatalogImportMapping mapping;
    final CatalogImportRowTarget target;
    final Map<String, String> values = new LinkedHashMap<>();
    final Map<String, String> errors = new LinkedHashMap<>();
    final Map<String, String> oldValues = new LinkedHashMap<>();
    final Map<String, String> newValues = new LinkedHashMap<>();
    final Map<String, Integer> expectedVersions = new LinkedHashMap<>();
    final Map<String, Claim> claims = new LinkedHashMap<>();
    String managerPendingFor;
    List<UUID> managerCandidateIds = List.of();
    private boolean created;
    private boolean updated;
    private boolean conflicted;

    PlannedRow(CatalogImportWorkbookRow row, String sheetName, CatalogImportMapping mapping) {
        this.sheetName = sheetName;
        this.rowNumber = row.rowNumber();
        this.mapping = mapping;
        this.target = mapping.rowTargets().getOrDefault(row.rowNumber(), EMPTY_TARGET);
        for (Map.Entry<String, String> entry : mapping.columns().entrySet()) {
            values.put(entry.getKey(), row.values().getOrDefault(entry.getValue(), ""));
            String cellError = row.fieldErrors().get(entry.getValue());
            if (cellError != null) {
                errors.put(entry.getKey(), cellError);
            }
        }
    }

    // Заявка (claim) — «эта строка утверждает о записи X следующее». Две строки об одной записи с разными
    // утверждениями противоречат друг другу, и обе получают конфликт. У договора описание пустое: любая вторая
    // строка про тот же договор недопустима, даже если значения те же, чтобы файл не задавал договор дважды.
    // Сравниваются все строки записи между собой, а не только с первой: иначе строка, совпавшая с первой
    // (которая сама в конфликте), прошла бы и применилась без разрешения противоречия.
    static void resolveConflicts(List<PlannedRow> rows) {
        Map<String, List<PlannedRow>> byClaim = new LinkedHashMap<>();
        for (PlannedRow row : rows) {
            if (row.hasErrors()) {
                continue;
            }
            for (String key : row.claims.keySet()) {
                byClaim.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }
        for (Map.Entry<String, List<PlannedRow>> group : byClaim.entrySet()) {
            String key = group.getKey();
            List<PlannedRow> same = group.getValue();
            if (same.size() < 2) {
                continue;
            }
            PlannedRow first = same.get(0);
            Claim firstClaim = first.claims.get(key);
            if (firstClaim.description().isEmpty()) {
                for (PlannedRow row : same.subList(1, same.size())) {
                    row.conflict(firstClaim.field(), "Этот договор уже указан в строке " + first.rowNumber);
                }
                differing(same, key, firstClaim).ifPresent(other -> first.conflict(firstClaim.field(),
                        "Строка " + other.rowNumber + " задаёт для этого договора другие значения"));
                continue;
            }
            for (PlannedRow row : same) {
                Claim claim = row.claims.get(key);
                differing(same, key, claim).ifPresent(other -> row.conflict(claim.field(),
                        "Противоречит строке " + other.rowNumber + ": " + claim.description()));
            }
        }
    }

    private static Optional<PlannedRow> differing(List<PlannedRow> rows, String key, Claim claim) {
        return rows.stream().filter(other -> !other.claims.get(key).signature().equals(claim.signature())).findFirst();
    }

    boolean mapped(String field) {
        return mapping.columns().containsKey(field);
    }

    void error(String field, String message) {
        errors.putIfAbsent(field, message);
    }

    void conflict(String field, String message) {
        conflicted = true;
        errors.putIfAbsent(field, message);
    }

    void compare(boolean exists, String field, String oldValue, String newValue) {
        oldValues.put(field, exists ? text(oldValue) : "");
        newValues.put(field, text(newValue));
        if (!exists) {
            created = true;
        } else if (!text(oldValue).equals(text(newValue))) {
            updated = true;
        }
    }

    void expected(String entity, int version) {
        expectedVersions.put(entity, version);
    }

    void claim(String entity, String identity, String field, String signature, String description) {
        claims.put(entity + "|" + identity, new Claim(field, signature, description));
    }

    boolean hasErrors() {
        return !errors.isEmpty();
    }

    // Приоритет статуса: конфликт важнее ошибки, ошибка важнее «создать», «создать» важнее «обновить».
    // Строка с ошибкой или конфликтом применена быть не может (canApply).
    CatalogImportRowStatus status() {
        if (conflicted) {
            return CatalogImportRowStatus.CONFLICT;
        }
        if (!errors.isEmpty()) {
            return CatalogImportRowStatus.INVALID;
        }
        return created ? CatalogImportRowStatus.CREATE
                : updated ? CatalogImportRowStatus.UPDATE : CatalogImportRowStatus.UNCHANGED;
    }

    CatalogImportStoredRow stored() {
        CatalogImportPlan plan = new CatalogImportPlan(
                Map.copyOf(values),
                target,
                Map.copyOf(expectedVersions),
                Map.copyOf(oldValues),
                Map.copyOf(newValues),
                managerCandidateIds.isEmpty() ? null : managerCandidateIds
        );
        return new CatalogImportStoredRow(id, sheetName, rowNumber, status(), plan, Map.copyOf(errors), false);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    record Claim(String field, String signature, String description) {
    }
}
