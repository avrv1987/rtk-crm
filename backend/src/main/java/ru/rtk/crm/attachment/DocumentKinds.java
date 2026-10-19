package ru.rtk.crm.attachment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.rtk.crm.interaction.InteractionValidationException;

// Справочник «Виды документов» (V59): код вида хранится в attachments.kind и в обязательных документах этапа.
// Записи ведёт администратор общим механизмом справочников (CatalogKind.DOCUMENT_KINDS); здесь — чтение и проверки.
@Component
public class DocumentKinds {
    public static final String OTHER = "OTHER";
    public static final String CURRICULUM = "CURRICULUM";
    private static final int REQUIRED_LIMIT = 20;

    private final JdbcClient jdbcClient;

    public DocumentKinds(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<DocumentKind> findAll() {
        return jdbcClient.sql("""
                SELECT code, name, archived FROM document_kinds
                ORDER BY CASE WHEN code = 'OTHER' THEN 1 ELSE 0 END, name, code
                """)
                .query((resultSet, rowNumber) -> new DocumentKind(
                        resultSet.getString("code"), resultSet.getString("name"), resultSet.getBoolean("archived")))
                .list();
    }

    public Map<String, DocumentKind> byCode() {
        Map<String, DocumentKind> result = new LinkedHashMap<>();
        findAll().forEach(kind -> result.put(kind.code(), kind));
        return result;
    }

    /** Вид для нового или изменённого документа: только действующий вид справочника. */
    public String requireSelectable(String code, String field) {
        DocumentKind kind = code == null ? null : byCode().get(code);
        if (kind == null || kind.archived()) {
            throw new InteractionValidationException(field, "Выберите действующий вид документа из справочника");
        }
        return kind.code();
    }

    // Перечень обязательных документов этапа: без повторов, только виды справочника. Вид в архиве остаётся допустимым:
    // этап шаблона, сохранённый до архивации, должен сохраняться без правки перечня.
    public List<String> normalizeRequired(List<String> codes, String field) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        Map<String, DocumentKind> known = byCode();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String code : codes) {
            if (code == null || !known.containsKey(code)) {
                throw new InteractionValidationException(field, "Обязательный документ этапа должен быть видом из справочника");
            }
            result.add(code);
        }
        if (result.size() > REQUIRED_LIMIT) {
            throw new InteractionValidationException(field, "У этапа не больше " + REQUIRED_LIMIT + " обязательных документов");
        }
        return new ArrayList<>(result);
    }

    public record DocumentKind(String code, String name, boolean archived) {
    }
}
