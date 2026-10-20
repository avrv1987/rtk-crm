package ru.rtk.crm.privacy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

// Для тестов со схемой «вручную»: досоздаёт таблицы и столбцы, которые обходят поиск и замена по реестру мест с ПДн.
// Уже созданные тестом таблицы не меняются, кроме недостающих столбцов.
public final class PersonalDataPlacesTestSchema {
    // Столбцы, которые поиск берёт из соседних таблиц (вуз, карточка, договор) и которые трогает обезличивание профиля.
    private static final List<String> CONTEXT = List.of(
            "interactions.organization_id", "interactions.title", "interactions.created_at", "interactions.updated_at",
            "organizations.name", "agreements.organization_id", "agreements.number", "agreements.updated_at",
            "agreement_activity_lists.organization_id", "agreement_activity_lists.title",
            "partner_requests.interaction_id", "partner_requests.organization_id", "contacts.organization_id",
            "vendors.id", "vendors.name", "agreements.id", "agreement_activity_lists.id", "partner_requests.id",
            "organization_deputies.deputy_profile_id", "organization_deputies.deputy_display_name",
            "organization_deputies.actor_profile_id", "organization_deputies.actor_display_name",
            "organization_deputies.ended_by_profile_id", "organization_deputies.ended_by_display_name",
            "catalog_change_events.actor_profile_id", "catalog_change_events.actor_display_name", "mail_outbox.mime_message",
            "contact_events.contact_id", "university_teaching.organization_id", "internships.organization_id",
            "teacher_qualifications.organization_id"
    );

    private PersonalDataPlacesTestSchema() {
    }

    public static void create(JdbcTemplate jdbc) {
        List<String> columns = new ArrayList<>(CONTEXT);
        for (PersonalDataPlace place : PersonalDataPlace.values()) {
            String table = place.table();
            place.key().forEach(key -> columns.add(table + "." + key));
            columns.add(table + "." + place.column());
            if (place.versioned()) {
                columns.add(table + ".version");
            }
            for (String part : (place.context().joins() + " " + place.time()).split("[^a-z_.]+")) {
                if (part.startsWith("x.")) {
                    columns.add(table + "." + part.substring(2));
                }
            }
        }
        for (String qualified : columns) {
            String table = qualified.substring(0, qualified.indexOf('.'));
            String column = qualified.substring(qualified.indexOf('.') + 1);
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + table + " (" + column + " " + type(column) + ")");
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column + " " + type(column));
        }
    }

    private static String type(String column) {
        if (column.equals("item_order")) {
            return "INTEGER";
        }
        if (column.equals("id") || column.endsWith("_id")) {
            return "UUID";
        }
        if (column.endsWith("_at")) {
            return "TIMESTAMP WITH TIME ZONE";
        }
        if (column.equals("version")) {
            return "INTEGER DEFAULT 0";
        }
        return "VARCHAR(100000)";
    }
}
