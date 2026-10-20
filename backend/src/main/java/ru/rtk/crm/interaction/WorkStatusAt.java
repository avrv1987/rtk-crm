package ru.rtk.crm.interaction;

// Статус взаимодействия `i` на момент — work_status_after последнего события смены статуса до границы (V47); без таких
// событий взаимодействие активно с создания. Созданное не раньше границы статуса на неё не имеет (NULL). Одно выражение
// на «Контроль», отчёты и состав активных: сегодняшний статус на прошлое не переносится.
public final class WorkStatusAt {
    private static final String SQL = """
            CASE WHEN i.created_at >= :%1$s THEN CAST(NULL AS VARCHAR(16)) ELSE COALESCE(
                (SELECT se.work_status_after FROM interaction_events se
                 WHERE se.interaction_id = i.id AND se.type = 'STATUS_CHANGED' AND se.occurred_at < :%1$s
                 ORDER BY se.occurred_at DESC, se.version DESC LIMIT 1),
                'ACTIVE') END""";

    private WorkStatusAt() {
    }

    // parameter — имя параметра запроса с границей (без двоеточия).
    public static String sql(String parameter) {
        return SQL.formatted(parameter);
    }
}
