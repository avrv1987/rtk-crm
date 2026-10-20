package ru.rtk.crm.privacy;

import java.util.List;

// Единый реестр мест, где в базе могут лежать ФИО, почта или телефон человека текстом. По нему работают поиск
// субъекта, предпросмотр и замена при обезличивании, а маскировка выгрузки — по найденным упоминаниям. Новое
// текстовое поле добавляется сюда или в список «без ПДн» теста PersonalDataPlacesTest: тест сверяет реестр со схемой
// из миграций и не даст завести поле, о котором поиск и замена не знают.
enum PersonalDataPlace {
    EVENT_COMMENT("interaction_events", "comment", 0, Kind.MENTION, MentionPlace.COMMENT, Context.INTERACTION, "x.occurred_at"),
    EVENT_NEXT_ACTION("interaction_events", "next_action", 500, Kind.MENTION, MentionPlace.PLAN_HISTORY, Context.INTERACTION,
            "x.occurred_at"),
    EVENT_IMPORT_SOURCE("interaction_events", "import_source", 300, Kind.MENTION, MentionPlace.IMPORT, Context.INTERACTION,
            "x.occurred_at"),
    EVENT_IMPORT_AUTHOR("interaction_events", "import_author", 200, Kind.MENTION, MentionPlace.IMPORT, Context.INTERACTION,
            "x.occurred_at"),
    INTERACTION_NEXT_ACTION("interactions", "next_action", 500, Kind.MENTION, MentionPlace.NEXT_ACTION, Context.WORK, "x.updated_at"),
    INTERACTION_TITLE("interactions", "title", 200, Kind.MENTION, MentionPlace.TITLE, Context.WORK, "x.created_at"),
    INTERACTION_STATUS_REASON("interactions", "work_status_reason", 1000, Kind.MENTION, MentionPlace.WORK_STATUS, Context.WORK,
            "x.updated_at"),
    INTERACTION_WAITING_NOTE("interactions", "waiting_note", 500, Kind.MENTION, MentionPlace.WORK_STATUS, Context.WORK,
            "x.updated_at"),
    STAGE_COMPLETION_COMMENT("interaction_stage_completions", "comment", 0, Kind.MENTION, MentionPlace.STAGE_COMMENT,
            Context.INTERACTION, null),
    AGENT_PLAN_NOTE("agent_change_plans", "note_text", 4000, Kind.MENTION, MentionPlace.AGENT_PLAN, Context.INTERACTION,
            "x.created_at"),
    AGENT_PLAN_NEXT_ACTION("agent_change_plans", "next_action", 500, Kind.MENTION, MentionPlace.AGENT_PLAN, Context.INTERACTION,
            "x.created_at"),
    ISSUE_DESCRIPTION("interaction_issues", "description", 1000, Kind.MENTION, MentionPlace.ISSUE, Context.INTERACTION,
            "x.created_at"),
    ISSUE_RESOLUTION("interaction_issues", "resolution", 1000, Kind.MENTION, MentionPlace.ISSUE, Context.INTERACTION,
            "x.resolved_at"),
    ISSUE_LAST_COMMENT("interaction_issues", "last_comment", 1000, Kind.MENTION, MentionPlace.ISSUE, Context.INTERACTION,
            "x.last_comment_at"),
    AGREEMENT_PARTIES("agreements", "parties", 2000, Kind.MENTION, MentionPlace.AGREEMENT, Context.AGREEMENT, "x.updated_at"),
    AGREEMENT_STATUS_COMMENT("agreement_status_events", "comment", 2000, Kind.MENTION, MentionPlace.AGREEMENT,
            Context.AGREEMENT_PART, "x.occurred_at"),
    SUPPLEMENT_SUBJECT("agreement_supplements", "subject", 2000, Kind.MENTION, MentionPlace.AGREEMENT, Context.AGREEMENT_PART,
            "x.updated_at"),
    AGREEMENT_FILE_COMMENT("agreement_files", "comment", 500, Kind.MENTION, MentionPlace.AGREEMENT, Context.AGREEMENT_PART,
            "x.created_at"),
    AGREEMENT_FILE_NAME("agreement_files", "original_name", 255, Kind.MENTION, MentionPlace.AGREEMENT_FILE, Context.AGREEMENT_PART,
            "x.created_at"),
    ACTIVITY_TITLE("agreement_activities", "title", 300, Kind.MENTION, MentionPlace.AGREEMENT_ACTIVITY, Context.AGREEMENT_PART,
            "x.updated_at"),
    ACTIVITY_DOCUMENT_NOTE("agreement_activities", "document_note", 500, Kind.MENTION, MentionPlace.AGREEMENT_ACTIVITY,
            Context.AGREEMENT_PART, "x.updated_at"),
    ACTIVITY_LIST_TITLE("agreement_activity_lists", "title", 200, Kind.MENTION, MentionPlace.ACTIVITY_LIST, Context.LIST,
            "x.updated_at"),
    ACTIVITY_LIST_COMMENT("agreement_activity_list_events", "comment", 2000, Kind.MENTION, MentionPlace.ACTIVITY_LIST,
            Context.LIST_PART, "x.occurred_at"),
    ACTIVITY_LIST_ITEM_TITLE("agreement_activity_list_items", "title", 300, Kind.MENTION, MentionPlace.ACTIVITY_LIST,
            Context.LIST_PART, null),
    ACTIVITY_LIST_ITEM_EXECUTOR("agreement_activity_list_items", "executor", 200, Kind.MENTION, MentionPlace.ACTIVITY_LIST,
            Context.LIST_PART, null),
    HANDOVER_NOTE("organization_assignment_events", "handover_note", 2000, Kind.MENTION, MentionPlace.HANDOVER,
            Context.ORGANIZATION, "x.occurred_at"),
    PARTNER_REQUEST_ACTION("partner_requests", "action", 300, Kind.MENTION, MentionPlace.PARTNER_REQUEST, Context.PARTNER_REQUEST,
            "x.created_at"),
    PARTNER_REQUEST_RESULT("partner_requests", "expected_result", 1000, Kind.MENTION, MentionPlace.PARTNER_REQUEST,
            Context.PARTNER_REQUEST, "x.created_at"),
    PARTNER_REQUEST_RETURN("partner_requests", "return_reason", 1000, Kind.MENTION, MentionPlace.PARTNER_REQUEST,
            Context.PARTNER_REQUEST, "x.updated_at"),
    PARTNER_REQUEST_CLARIFICATION("partner_requests", "clarification", 2000, Kind.MENTION, MentionPlace.PARTNER_REQUEST,
            Context.PARTNER_REQUEST, "x.updated_at"),
    PARTNER_REQUEST_CANCEL("partner_requests", "cancel_reason", 1000, Kind.MENTION, MentionPlace.PARTNER_REQUEST,
            Context.PARTNER_REQUEST, "x.updated_at"),
    PARTNER_RESPONSE_MESSAGE("partner_request_responses", "message", 4000, Kind.MENTION, MentionPlace.PARTNER_REQUEST,
            Context.PARTNER_RESPONSE, "x.submitted_at"),
    PARTNER_RESPONSE_DECISION("partner_request_responses", "decision_reason", 1000, Kind.MENTION, MentionPlace.PARTNER_REQUEST,
            Context.PARTNER_RESPONSE, "x.decided_at"),
    CONSENT_DOCUMENT("personal_data_consents", "document", 300, Kind.MENTION, MentionPlace.CONSENT, Context.CONSENT, "x.created_at"),
    CONSENT_BASIS("personal_data_consents", "basis", 300, Kind.MENTION, MentionPlace.CONSENT, Context.CONSENT, "x.created_at"),
    // Имя в реестре обращений остаётся: по нему оператор отчитывается, что обращение исполнено в срок (ст. 20–21
    // 152-ФЗ). Поиск его показывает, замена не трогает; закрытое обращение удаляется по сроку хранения.
    REQUEST_SUBJECT("personal_data_requests", "subject_name", 300, Kind.RETAINED, MentionPlace.PERSONAL_DATA_REQUEST, Context.NONE,
            "x.created_at"),
    REQUEST_CHANNEL("personal_data_requests", "channel", 200, Kind.MENTION, MentionPlace.PERSONAL_DATA_REQUEST, Context.NONE,
            "x.created_at"),
    REQUEST_NOTE("personal_data_requests", "note", 1000, Kind.MENTION, MentionPlace.PERSONAL_DATA_REQUEST, Context.NONE,
            "x.created_at"),
    SAVED_REPORT_NAME("saved_reports", "name", 200, Kind.MENTION, MentionPlace.SAVED_REPORT, Context.NONE, "x.updated_at"),
    CONTACT_HISTORY("contact_events", "changes", 0, Kind.MENTION, MentionPlace.CONTACT_HISTORY, Context.CONTACT, "x.occurred_at"),
    TEACHING_DOCUMENT("university_teaching", "confirmation_document", 300, Kind.MENTION, MentionPlace.PEOPLE_DOCUMENT,
            Context.PEOPLE, "x.updated_at"),
    INTERN_DOCUMENT("internships", "document", 300, Kind.MENTION, MentionPlace.PEOPLE_DOCUMENT, Context.PEOPLE, "x.updated_at"),
    VENDOR_CONTACT_NAME("vendor_contacts", "name", 200, Kind.MENTION, MentionPlace.VENDOR_CONTACT, Context.VENDOR, "x.updated_at"),
    VENDOR_CONTACT_EMAIL("vendor_contacts", "email", 320, Kind.MENTION, MentionPlace.VENDOR_CONTACT, Context.VENDOR, "x.updated_at"),
    VENDOR_CONTACT_PHONE("vendor_contacts", "phone", 16, Kind.MENTION, MentionPlace.VENDOR_CONTACT, Context.VENDOR, "x.updated_at"),
    // Учёт людей и часов: ФИО записано текстом в записи; поиск показывает такие записи отдельным списком.
    TEACHING_PERFORMER("university_teaching", "performer_name", 300, Kind.PEOPLE),
    INTERN_NAME("internships", "intern_name", 300, Kind.PEOPLE),
    MENTOR_NAME("internships", "mentor_name", 300, Kind.PEOPLE),
    QUALIFICATION_PERSON("teacher_qualifications", "person_name", 300, Kind.PEOPLE),
    QUALIFICATION_EMAIL("teacher_qualifications", "person_email", 254, Kind.PEOPLE),
    ATTACHMENT_NAME("attachments", "original_name", 255, Kind.ATTACHMENT),
    SOURCE_PAYLOAD("source_records", "payload", 0, Kind.SOURCE),
    // Служебные записи: их не видно в интерфейсе как текст карточки, но в них остаются копии данных.
    SOURCE_ERROR("source_records", "error", 500, Kind.TECHNICAL),
    IMPORT_PLAN("catalog_import_rows", "plan_json", 0, Kind.TECHNICAL),
    IMPORT_ERRORS("catalog_import_rows", "errors_json", 0, Kind.TECHNICAL),
    IMPORT_RESULT("catalog_import_jobs", "result_json", 0, Kind.TECHNICAL),
    // Сохранённые ответы повторов команд: повтор с тем же ключом вернул бы ФИО и телефон из ответа.
    COMMAND_RESULT("command_idempotency_records", "result_json", 0, Kind.TECHNICAL),
    AUDIT_OBJECT("audit_events", "object_name", 500, Kind.TECHNICAL),
    AUDIT_DETAILS("audit_events", "details", 2000, Kind.TECHNICAL),
    SAVED_REPORT_DEFINITION("saved_reports", "definition_json", 0, Kind.TECHNICAL),
    REPORT_REQUEST("report_jobs", "request_json", 0, Kind.TECHNICAL),
    REPORT_FILE_NAME("report_jobs", "result_file_name", 255, Kind.TECHNICAL),
    CRM_EXCHANGE_PAYLOAD("crm_exchange_events", "payload", 0, Kind.TECHNICAL),
    MAIL_SUBJECT("mail_outbox", "subject", 300, Kind.TECHNICAL),
    MAIL_TEXT("mail_outbox", "body_text", 0, Kind.TECHNICAL),
    MAIL_HTML("mail_outbox", "body_html", 0, Kind.TECHNICAL),
    MAIL_RECIPIENT("mail_outbox", "recipient_email", 254, Kind.TECHNICAL),
    MAIL_ATTACHMENT("mail_outbox", "attachment_name", 200, Kind.TECHNICAL),
    ENROLMENT_FILE("enrolment_batches", "file_name", 400, Kind.TECHNICAL),
    ENROLMENT_ITEM_ERROR("enrolment_batch_items", "error", 500, Kind.TECHNICAL),
    LMS_RECONCILE_REASON("learner_enrolments", "lms_reconcile_reason", 500, Kind.TECHNICAL),
    CATALOG_ENTITY_NAME("catalog_change_events", "entity_name", 300, Kind.TECHNICAL),
    CATALOG_CHANGES("catalog_change_events", "changes", 2000, Kind.TECHNICAL);

    // Таблицы, где у строки есть оптимистическая версия: правка текста меняет версию, чтобы открытая форма не
    // записала поверх старое значение.
    private static final List<String> VERSIONED = List.of(
            "interactions", "agreements", "agreement_supplements", "agreement_activities", "agreement_activity_lists",
            "partner_requests", "saved_reports", "university_teaching", "internships", "teacher_qualifications", "vendor_contacts"
    );
    // Имена файлов сверяются по правилам имён файлов: ФИО и его транслитерация целиком.
    private static final List<String> FILE_NAMES = List.of(
            "attachments.original_name", "agreement_files.original_name", "audit_events.object_name",
            "report_jobs.result_file_name", "mail_outbox.attachment_name", "enrolment_batches.file_name"
    );
    private static final List<String> JSON = List.of(
            "source_records.payload", "catalog_import_rows.plan_json", "catalog_import_rows.errors_json",
            "catalog_import_jobs.result_json", "command_idempotency_records.result_json", "saved_reports.definition_json",
            "report_jobs.request_json", "crm_exchange_events.payload", "contact_events.changes"
    );

    private final String table;
    private final String column;
    private final int maxLength;
    private final Kind kind;
    private final MentionPlace place;
    private final Context context;
    private final String time;

    PersonalDataPlace(String table, String column, int maxLength, Kind kind) {
        this(table, column, maxLength, kind, null, Context.NONE, null);
    }

    PersonalDataPlace(String table, String column, int maxLength, Kind kind, MentionPlace place, Context context, String time) {
        this.table = table;
        this.column = column;
        this.maxLength = maxLength;
        this.kind = kind;
        this.place = place;
        this.context = context;
        this.time = time;
    }

    String table() {
        return table;
    }

    String column() {
        return column;
    }

    Kind kind() {
        return kind;
    }

    MentionPlace place() {
        return place;
    }

    Context context() {
        return context;
    }

    String time() {
        return time == null ? "CAST(NULL AS TIMESTAMP WITH TIME ZONE)" : time;
    }

    boolean versioned() {
        return VERSIONED.contains(table);
    }

    boolean fileName() {
        return FILE_NAMES.contains(table + "." + column);
    }

    boolean json() {
        return JSON.contains(table + "." + column);
    }

    // У двух таблиц нет столбца id: строку находим по составному ключу.
    List<String> key() {
        return switch (table) {
            case "interaction_stage_completions" -> List.of("interaction_id", "stage_id");
            case "agreement_activity_list_items" -> List.of("list_id", "item_order");
            default -> List.of("id");
        };
    }

    // Копия письма в журнале закодирована (MIME), заменить в ней текст нельзя — при правке письма она стирается.
    String clearedWith() {
        return "mail_outbox".equals(table) ? "mime_message" : null;
    }

    String fit(String value) {
        return maxLength == 0 || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    enum Kind {
        // Текст карточки: поиск показывает упоминанием, замена считает упоминанием.
        MENTION,
        // ФИО в учёте людей и часов: поиск показывает записью учёта, замена считает упоминанием.
        PEOPLE,
        ATTACHMENT,
        SOURCE,
        TECHNICAL,
        // Поиск показывает, замена не трогает (см. REQUEST_SUBJECT).
        RETAINED
    }

    // Откуда поиск берёт вуз, карточку и название объекта для строки упоминания; x — строка самой таблицы.
    enum Context {
        INTERACTION("LEFT JOIN interactions i ON i.id = x.interaction_id LEFT JOIN organizations o ON o.id = i.organization_id",
                "i.id", "i.title"),
        WORK("LEFT JOIN organizations o ON o.id = x.organization_id", "x.id", "x.title"),
        ORGANIZATION("LEFT JOIN organizations o ON o.id = x.organization_id", null, null),
        PEOPLE("LEFT JOIN organizations o ON o.id = x.organization_id LEFT JOIN interactions i ON i.id = x.interaction_id",
                "i.id", "i.title"),
        AGREEMENT("LEFT JOIN organizations o ON o.id = x.organization_id", null, "'Договор ' || x.number"),
        AGREEMENT_PART("LEFT JOIN agreements a ON a.id = x.agreement_id LEFT JOIN organizations o ON o.id = a.organization_id",
                null, "'Договор ' || a.number"),
        LIST("LEFT JOIN organizations o ON o.id = x.organization_id", null, "x.title"),
        LIST_PART("LEFT JOIN agreement_activity_lists l ON l.id = x.list_id LEFT JOIN organizations o ON o.id = l.organization_id",
                null, "l.title"),
        PARTNER_REQUEST("LEFT JOIN interactions i ON i.id = x.interaction_id LEFT JOIN organizations o ON o.id = x.organization_id",
                "i.id", "i.title"),
        PARTNER_RESPONSE("LEFT JOIN partner_requests r ON r.id = x.request_id LEFT JOIN interactions i ON i.id = r.interaction_id"
                + " LEFT JOIN organizations o ON o.id = r.organization_id", "i.id", "i.title"),
        CONSENT("LEFT JOIN contacts c ON x.subject_type = 'CONTACT' AND c.id = x.subject_id"
                + " LEFT JOIN organizations o ON o.id = c.organization_id", null, null),
        CONTACT("LEFT JOIN contacts c ON c.id = x.contact_id LEFT JOIN organizations o ON o.id = c.organization_id", null, null),
        VENDOR("LEFT JOIN vendors v ON v.id = x.vendor_id LEFT JOIN organizations o ON 1 = 0", null, "'Вендор ' || v.name"),
        NONE("LEFT JOIN organizations o ON 1 = 0", null, null);

        private final String joins;
        private final String interaction;
        private final String caption;

        Context(String joins, String interaction, String caption) {
            this.joins = joins;
            this.interaction = interaction;
            this.caption = caption;
        }

        String joins() {
            return joins;
        }

        String interaction() {
            return interaction == null ? "CAST(NULL AS UUID)" : interaction;
        }

        String caption() {
            return caption == null ? "CAST(NULL AS VARCHAR(300))" : caption;
        }
    }
}
