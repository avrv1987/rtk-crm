package ru.rtk.crm.catalogimport;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    static void resolveConflicts(List<PlannedRow> rows) {
        List<PlannedRow> candidates = rows.stream().filter(row -> !row.hasErrors()).toList();
        Map<String, PlannedRow> firstByClaim = new HashMap<>();
        for (PlannedRow row : candidates) {
            for (Map.Entry<String, Claim> entry : row.claims.entrySet()) {
                PlannedRow first = firstByClaim.putIfAbsent(entry.getKey(), row);
                if (first == null) {
                    continue;
                }
                Claim claim = entry.getValue();
                boolean same = first.claims.get(entry.getKey()).signature().equals(claim.signature());
                if (claim.description().isEmpty()) {
                    row.conflict(claim.field(), "Этот договор уже указан в строке " + first.rowNumber);
                    if (!same) {
                        first.conflict(claim.field(), "Строка " + row.rowNumber + " задаёт для этого договора другие значения");
                    }
                } else if (!same) {
                    row.conflict(claim.field(), "Противоречит строке " + first.rowNumber + ": " + claim.description());
                    first.conflict(claim.field(), "Противоречит строке " + row.rowNumber + ": " + claim.description());
                }
            }
        }
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
