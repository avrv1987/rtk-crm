package ru.rtk.crm.privacy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
        List<SubjectPersonRecord> peopleRecords = findPeopleRecords(terms);
        boolean truncated = contacts.size() > LIMIT || profiles.size() > LIMIT || mentions.size() > LIMIT
                || attachments.size() > LIMIT || sourceRecords.size() > LIMIT || peopleRecords.size() > LIMIT;
        return new SubjectSearchResult(
                first(contacts), first(profiles), first(mentions), first(attachments), first(sourceRecords), List.of(),
                first(peopleRecords), truncated
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

    // Упоминания — все текстовые места реестра PersonalDataPlace вида «текст карточки»; замена при обезличивании
    // проходит те же места, поэтому найденное здесь не остаётся после «Обезличить».
    private List<SubjectMention> findMentions(SubjectTerms terms) {
        List<SubjectMention> mentions = new ArrayList<>();
        for (PersonalDataPlace place : PersonalDataPlace.values()) {
            if (place.kind() == PersonalDataPlace.Kind.MENTION || place.kind() == PersonalDataPlace.Kind.RETAINED) {
                mentions.addAll(findMentions(terms, place));
            }
        }
        return mentions.stream()
                .sorted(Comparator.comparing(SubjectMention::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(LIMIT + 1L)
                .toList();
    }

    private List<SubjectMention> findMentions(SubjectTerms terms, PersonalDataPlace place) {
        String column = "x." + place.column();
        SqlMatch match = place.fileName() ? terms.attachmentMatch(column) : terms.textMatch(column);
        PersonalDataPlace.Context context = place.context();
        return bind(jdbcClient.sql("""
                SELECT %1$s AS interaction_id, %2$s AS title, o.name AS organization_name, %3$s AS text, %4$s AS occurred_at
                FROM %5$s x %6$s
                WHERE %3$s IS NOT NULL AND %7$s
                ORDER BY occurred_at DESC
                LIMIT :limit
                """.formatted(context.interaction(), context.caption(), column, place.time(), place.table(), context.joins(),
                match.sql())), match)
                .param("limit", LIMIT + 1)
                .query((resultSet, rowNumber) -> new SubjectMention(
                        resultSet.getObject("interaction_id", UUID.class),
                        Objects.requireNonNullElse(resultSet.getString("title"), place.place().label()),
                        resultSet.getString("organization_name"),
                        place.place(),
                        resultSet.getString("text"),
                        resultSet.getObject("occurred_at", OffsetDateTime.class)
                ))
                .list()
                .stream()
                .filter(mention -> !place.fileName() || terms.matchesAttachmentName(mention.text()))
                .toList();
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
                .list()
                .stream()
                .filter(attachment -> terms.matchesAttachmentName(attachment.fileName()))
                .toList();
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

    // Учёт людей и часов: ФИО хранится текстом в записи, поэтому ищется так же, как упоминания.
    private List<SubjectPersonRecord> findPeopleRecords(SubjectTerms terms) {
        List<SubjectPersonRecord> records = new ArrayList<>();
        records.addAll(findPeopleRecords(terms, SubjectPersonRecord.Kind.TEACHING_PERFORMER, "university_teaching", "x.performer_name",
                "x.discipline", "CAST(NULL AS TIMESTAMP WITH TIME ZONE)", new String[]{"x.performer_name"}));
        records.addAll(findPeopleRecords(terms, SubjectPersonRecord.Kind.INTERN, "internships", "x.intern_name", "x.place",
                "x.anonymized_at", new String[]{"x.intern_name"}));
        records.addAll(findPeopleRecords(terms, SubjectPersonRecord.Kind.MENTOR, "internships", "x.mentor_name", "x.place",
                "CAST(NULL AS TIMESTAMP WITH TIME ZONE)", new String[]{"x.mentor_name"}));
        records.addAll(findPeopleRecords(terms, SubjectPersonRecord.Kind.QUALIFICATION, "teacher_qualifications", "x.person_name",
                "x.program", "x.anonymized_at", new String[]{"x.person_name", "x.person_email"}));
        return records;
    }

    private List<SubjectPersonRecord> findPeopleRecords(SubjectTerms terms, SubjectPersonRecord.Kind kind, String table, String name,
                                                        String details, String anonymizedAt, String[] columns) {
        SqlMatch match = terms.textMatch(columns);
        return bind(jdbcClient.sql("""
                SELECT x.id, o.name AS organization_name, %1$s AS name, %2$s AS details, x.starts_on, x.ends_on,
                       %3$s AS anonymized_at
                FROM %4$s x
                JOIN organizations o ON o.id = x.organization_id
                WHERE %1$s IS NOT NULL AND %5$s
                ORDER BY x.starts_on DESC, x.id
                LIMIT :limit
                """.formatted(name, details, anonymizedAt, table, match.sql())), match)
                .param("limit", LIMIT + 1)
                .query((resultSet, rowNumber) -> new SubjectPersonRecord(
                        resultSet.getObject("id", UUID.class),
                        kind,
                        resultSet.getString("organization_name"),
                        resultSet.getString("name"),
                        resultSet.getString("details"),
                        resultSet.getObject("starts_on", java.time.LocalDate.class),
                        resultSet.getObject("ends_on", java.time.LocalDate.class),
                        resultSet.getObject("anonymized_at", OffsetDateTime.class) != null
                ))
                .list();
    }

    // Все известные системе люди — контакты вузов и сотрудники. Нужны выгрузке субъекта, чтобы закрыть в его
    // упоминаниях ФИО и почту других людей.
    List<KnownPerson> knownPeople() {
        return jdbcClient.sql("""
                SELECT name, email, phone FROM contacts
                UNION ALL
                SELECT display_name, CAST(NULL AS VARCHAR(320)), CAST(NULL AS VARCHAR(50)) FROM crm_user_profiles
                """)
                .query((resultSet, rowNumber) -> new KnownPerson(
                        resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)
                ))
                .list();
    }

    record KnownPerson(String name, String email, String phone) {
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
