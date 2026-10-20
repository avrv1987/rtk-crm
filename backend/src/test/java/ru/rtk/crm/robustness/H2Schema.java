package ru.rtk.crm.robustness;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

// Схема продукта на H2 из настоящих миграций Flyway — чтобы фаззер бил в те же столбцы, длины и CHECK, что и на
// сервере. H2 не понимает часть PostgreSQL, поэтому:
// - частичные индексы, индексы по выражениям и блоки PL/pgSQL пропускаются (уникальность «по нижнему регистру»
//   и «одна открытая запись» здесь не проверяется);
// - отложенный внешний ключ интерактивного этапа пропускается: H2 проверял бы его сразу, а код вставляет
//   взаимодействие раньше этапов;
// - переносы данных (INSERT/UPDATE/WITH) с функциями PostgreSQL пропускаются: на пустой базе им нечего переносить;
// - безымянные CHECK PostgreSQL называет «таблица_столбец_check», H2 — по-своему, поэтому такой DROP CONSTRAINT
//   снимает CHECK по имени столбца.
public final class H2Schema {
    private static final Pattern VERSION = Pattern.compile("V(\\d+)__");
    private static final Pattern PARTIAL_INDEX = Pattern.compile("(?is)^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\b.*\\)\\s*WHERE\\b.*");
    private static final Pattern INDEX = Pattern.compile("(?is)^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\b.*");
    private static final Pattern DATA_CHANGE = Pattern.compile("(?is)^\\s*(INSERT|UPDATE|DELETE|WITH)\\b.*");
    private static final Pattern DROP_CONSTRAINT = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(\\w+)\\s+DROP\\s+CONSTRAINT\\s+(IF\\s+EXISTS\\s+)?(\\w+)\\s*$");
    private static final Pattern MULTI_ADD = Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+(\\w+)\\s+(ADD\\s+COLUMN\\b.*)");
    private static final Pattern NEXT_ADD = Pattern.compile("(?i),\\s*(?=ADD\\s+COLUMN\\b)");

    private H2Schema() {
    }

    public static void apply(JdbcTemplate jdbc) throws IOException {
        List<Resource> files = new ArrayList<>(List.of(
                new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql")));
        files.sort(Comparator.comparingInt(H2Schema::version));
        for (Resource file : files) {
            String sql;
            try (InputStream input = file.getInputStream()) {
                sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String statement : split(sql)) {
                execute(jdbc, file.getFilename(), statement.strip());
            }
        }
    }

    private static void execute(JdbcTemplate jdbc, String file, String statement) {
        if (statement.isEmpty() || statement.startsWith("DO $$") || statement.contains("DEFERRABLE")
                || PARTIAL_INDEX.matcher(statement).matches()) {
            return;
        }
        Matcher drop = DROP_CONSTRAINT.matcher(statement);
        if (drop.matches()) {
            dropConstraint(jdbc, drop.group(1), drop.group(3));
            return;
        }
        Matcher add = MULTI_ADD.matcher(statement);
        if (add.matches() && NEXT_ADD.matcher(add.group(2)).find()) {
            for (String clause : NEXT_ADD.split(add.group(2))) {
                jdbc.execute("ALTER TABLE " + add.group(1) + " " + clause);
            }
            return;
        }
        try {
            jdbc.execute(statement);
        } catch (RuntimeException exception) {
            if (INDEX.matcher(statement).matches() || DATA_CHANGE.matcher(statement).matches()) {
                return;
            }
            throw new IllegalStateException(file + ": " + statement, exception);
        }
    }

    private static void dropConstraint(JdbcTemplate jdbc, String table, String name) {
        String upperTable = table.toUpperCase(Locale.ROOT);
        String upperName = name.toUpperCase(Locale.ROOT);
        Integer named = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_NAME = ? AND CONSTRAINT_NAME = ?",
                Integer.class, upperTable, upperName);
        if (named != null && named > 0) {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + name);
        } else if (upperName.endsWith("_PKEY")) {
            // H2 держит на индексе первичного ключа и внешний ключ того же столбца — пока он есть, ключ не снять.
            for (String constraint : jdbc.queryForList("""
                    SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_NAME = ?
                      AND INDEX_NAME = (SELECT INDEX_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                                        WHERE TABLE_NAME = ? AND CONSTRAINT_TYPE = 'PRIMARY KEY')
                    ORDER BY CASE CONSTRAINT_TYPE WHEN 'PRIMARY KEY' THEN 1 ELSE 0 END
                    """, String.class, upperTable, upperTable)) {
                jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + constraint);
            }
        } else if (upperName.endsWith("_CHECK") && upperName.startsWith(upperTable + "_")) {
            String column = upperName.substring(upperTable.length() + 1, upperName.length() - "_CHECK".length());
            for (String check : jdbc.queryForList("""
                    SELECT tc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
                    JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                    WHERE tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'CHECK' AND cc.CHECK_CLAUSE LIKE ?
                    """, String.class, upperTable, "%\"" + column + "\"%")) {
                jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + check);
            }
        }
    }

    private static int version(Resource resource) {
        Matcher matcher = VERSION.matcher(String.valueOf(resource.getFilename()));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    // Разбивка по «;» вне строк, комментариев и $$-блоков.
    static List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean dollar = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (!quoted && !dollar && c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? sql.length() : end;
                current.append('\n');
                continue;
            }
            if (!quoted && c == '$' && i + 1 < sql.length() && sql.charAt(i + 1) == '$') {
                dollar = !dollar;
                current.append("$$");
                i++;
                continue;
            }
            if (!dollar && c == '\'') {
                quoted = !quoted;
            }
            if (c == ';' && !quoted && !dollar) {
                statements.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        statements.add(current.toString());
        return statements;
    }
}
