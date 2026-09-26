package ru.rtk.crm.privacy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.privacy.SubjectTerms.SqlMatch;

@Repository
public class SubjectSearchRepository {
    static final int LIMIT = 200;

    private static final String CONTACT_SELECT = """
            SELECT c.id, c.organization_id, o.name AS organization_name, c.name, c.position, c.email, c.phone,
                   c.personal_data_status, c.version, p.display_name AS created_by_name, c.created_at, c.updated_at,
                   (SELECT COUNT(*) FROM interaction_contacts ic WHERE ic.contact_id = c.id) AS interactions_count
            FROM contacts c
            JOIN organizations o ON o.id = c.organization_id
            LEFT JOIN crm_user_profiles p ON p.id = c.created_by
            """;

    private final JdbcClient jdbcClient;

    public SubjectSearchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    SubjectSearchResult search(SubjectTerms terms) {
        List<SubjectContact> contacts = findContacts(terms);
        List<SubjectProfile> profiles = findProfiles(terms);
        List<SubjectMention> mentions = findMentions(terms);
        List<SubjectAttachment> attachments = findAttachments(terms);
        List<SubjectSourceRecord> sourceRecords = findSourceRecords(terms);
        boolean truncated = contacts.size() > LIMIT || profiles.size() > LIMIT || mentions.size() > LIMIT
                || attachments.size() > LIMIT || sourceRecords.size() > LIMIT;
        return new SubjectSearchResult(
                first(contacts), first(profiles), first(mentions), first(attachments), first(sourceRecords), truncated
        );
    }

    Optional<SubjectContact> findContact(UUID contactId) {
        return jdbcClient.sql(CONTACT_SELECT + " WHERE c.id = :contactId")
                .param("contactId", contactId)
                .query(this::mapContact)
                .optional();
    }

    private List<SubjectContact> findContacts(SubjectTerms terms) {
        SqlMatch match = terms.textMatch("c.name", "c.email", "c.phone");
        return bind(jdbcClient.sql(CONTACT_SELECT + " WHERE " + match.sql() + " ORDER BY o.name, c.name, c.id LIMIT :limit"), match)
                .param("limit", LIMIT + 1)
                .query(this::mapContact)
                .list();
    }

    private List<SubjectProfile> findProfiles(SubjectTerms terms) {
        SqlMatch match = terms.textMatch("p.display_name", "p.login");
        return bind(jdbcClient.sql("""
                SELECT p.id, p.display_name, p.login, p.active, p.pending_activation, p.anonymized_at
                FROM crm_user_profiles p
                WHERE %s
                ORDER BY p.display_name, p.id
                LIMIT :limit
                """.formatted(match.sql())), match)
                .param("limit", LIMIT + 1)
                .query((resultSet, rowNumber) -> new SubjectProfile(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("display_name"),
                        resultSet.getString("login"),
                        resultSet.getBoolean("active"),
                        resultSet.getBoolean("pending_activation"),
                        resultSet.getObject("anonymized_at", OffsetDateTime.class) != null
                ))
                .list();
    }

    private List<SubjectMention> findMentions(SubjectTerms terms) {
        List<SubjectMention> mentions = new ArrayList<>();
        mentions.addAll(findMentions(terms, MentionPlace.COMMENT, "e.comment", "e.occurred_at", """
                FROM interaction_events e
                JOIN interactions i ON i.id = e.interaction_id
                JOIN organizations o ON o.id = i.organization_id
                """));
        mentions.addAll(findMentions(terms, MentionPlace.PLAN_HISTORY, "e.next_action", "e.occurred_at", """
                FROM interaction_events e
                JOIN interactions i ON i.id = e.interaction_id
                JOIN organizations o ON o.id = i.organization_id
                """));
        mentions.addAll(findMentions(terms, MentionPlace.NEXT_ACTION, "i.next_action", "i.updated_at", """
                FROM interactions i
                JOIN organizations o ON o.id = i.organization_id
                """));
        mentions.addAll(findMentions(terms, MentionPlace.TITLE, "i.title", "i.created_at", """
                FROM interactions i
                JOIN organizations o ON o.id = i.organization_id
                """));
        return mentions.stream()
                .sorted(Comparator.comparing(SubjectMention::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(LIMIT + 1L)
                .toList();
    }

    private List<SubjectMention> findMentions(SubjectTerms terms, MentionPlace place, String column, String time, String from) {
        SqlMatch match = terms.textMatch(column);
        return bind(jdbcClient.sql("""
                SELECT i.id AS interaction_id, i.title, o.name AS organization_name, %1$s AS text, %2$s AS occurred_at
                %3$s
                WHERE %1$s IS NOT NULL AND %4$s
                ORDER BY %2$s DESC
                LIMIT :limit
                """.formatted(column, time, from, match.sql())), match)
                .param("limit", LIMIT + 1)
                .query((resultSet, rowNumber) -> new SubjectMention(
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getString("organization_name"),
                        place,
                        resultSet.getString("text"),
                        resultSet.getObject("occurred_at", OffsetDateTime.class)
                ))
                .list();
    }

    private List<SubjectAttachment> findAttachments(SubjectTerms terms) {
        SqlMatch match = terms.attachmentMatch("a.original_name");
        return bind(jdbcClient.sql("""
                SELECT a.id, a.interaction_id, i.title, o.name AS organization_name, a.original_name, a.size_bytes, a.created_at
                FROM attachments a
                JOIN interactions i ON i.id = a.interaction_id
                JOIN organizations o ON o.id = i.organization_id
                WHERE %s
                ORDER BY a.created_at DESC, a.id
                LIMIT :limit
                """.formatted(match.sql())), match)
                .param("limit", LIMIT + 1)
                .query((resultSet, rowNumber) -> new SubjectAttachment(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getString("organization_name"),
                        resultSet.getString("original_name"),
                        resultSet.getLong("size_bytes"),
                        resultSet.getObject("created_at", OffsetDateTime.class)
                ))
                .list();
    }

    private List<SubjectSourceRecord> findSourceRecords(SubjectTerms terms) {
        SqlMatch match = terms.textMatch("r.payload");
        return bind(jdbcClient.sql("""
                SELECT r.id, r.source, r.record_type, r.external_id, r.status, o.name AS organization_name, r.submitted_at
                FROM source_records r
                LEFT JOIN organizations o ON o.id = r.organization_id
                WHERE %s
                ORDER BY r.submitted_at DESC, r.id
                LIMIT :limit
                """.formatted(match.sql())), match)
                .param("limit", LIMIT + 1)
                .query((resultSet, rowNumber) -> new SubjectSourceRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("source"),
                        resultSet.getString("record_type"),
                        resultSet.getString("external_id"),
                        resultSet.getString("status"),
                        resultSet.getString("organization_name"),
                        resultSet.getObject("submitted_at", OffsetDateTime.class)
                ))
                .list();
    }

    private SubjectContact mapContact(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SubjectContact(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getString("organization_name"),
                resultSet.getString("name"),
                resultSet.getString("position"),
                resultSet.getString("email"),
                resultSet.getString("phone"),
                PersonalDataStatus.valueOf(resultSet.getString("personal_data_status")),
                resultSet.getInt("version"),
                resultSet.getString("created_by_name"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class),
                resultSet.getInt("interactions_count")
        );
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, SqlMatch match) {
        match.params().forEach(statement::param);
        return statement;
    }

    private static <T> List<T> first(List<T> values) {
        return values.size() > LIMIT ? values.subList(0, LIMIT) : values;
    }
}
