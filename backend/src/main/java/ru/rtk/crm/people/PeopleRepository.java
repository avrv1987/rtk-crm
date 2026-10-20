package ru.rtk.crm.people;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.people.PeopleModels.ActivityOption;
import ru.rtk.crm.people.PeopleModels.ConfirmationStatus;
import ru.rtk.crm.people.PeopleModels.ContactOption;
import ru.rtk.crm.people.PeopleModels.FrdoReminder;
import ru.rtk.crm.people.PeopleModels.HourUnit;
import ru.rtk.crm.people.PeopleModels.InternKind;
import ru.rtk.crm.people.PeopleModels.InternshipInput;
import ru.rtk.crm.people.PeopleModels.InternshipRecord;
import ru.rtk.crm.people.PeopleModels.InternshipResult;
import ru.rtk.crm.people.PeopleModels.Option;
import ru.rtk.crm.people.PeopleModels.PeopleSettings;
import ru.rtk.crm.people.PeopleModels.QualificationInput;
import ru.rtk.crm.people.PeopleModels.QualificationRecord;
import ru.rtk.crm.people.PeopleModels.QualificationResult;
import ru.rtk.crm.people.PeopleModels.TeachingInput;
import ru.rtk.crm.people.PeopleModels.TeachingRecord;

@Repository
public class PeopleRepository {
    private static final String TEACHING_SELECT = """
            SELECT t.*, o.name AS organization_name, i.title AS interaction_title, a.number AS agreement_number,
                   act.title AS activity_title, COALESCE(pp.display_name, t.performer_name) AS performer_display,
                   up.display_name AS updated_by_name
            FROM university_teaching t
            JOIN organizations o ON o.id = t.organization_id
            LEFT JOIN interactions i ON i.id = t.interaction_id
            LEFT JOIN agreements a ON a.id = t.agreement_id
            LEFT JOIN agreement_activities act ON act.id = t.agreement_activity_id
            LEFT JOIN crm_user_profiles pp ON pp.id = t.performer_profile_id
            LEFT JOIN crm_user_profiles up ON up.id = t.updated_by
            """;
    private static final String INTERNSHIP_SELECT = """
            SELECT s.*, o.name AS organization_name, i.title AS interaction_title, act.title AS activity_title,
                   COALESCE(mp.display_name, s.mentor_name) AS mentor_display, up.display_name AS updated_by_name
            FROM internships s
            JOIN organizations o ON o.id = s.organization_id
            LEFT JOIN interactions i ON i.id = s.interaction_id
            LEFT JOIN agreement_activities act ON act.id = s.agreement_activity_id
            LEFT JOIN crm_user_profiles mp ON mp.id = s.mentor_profile_id
            LEFT JOIN crm_user_profiles up ON up.id = s.updated_by
            """;
    private static final String QUALIFICATION_SELECT = """
            SELECT q.*, o.name AS organization_name, i.title AS interaction_title, act.title AS activity_title,
                   r.lms_course AS roster_course, r.lms_group AS roster_group, up.display_name AS updated_by_name
            FROM teacher_qualifications q
            JOIN organizations o ON o.id = q.organization_id
            LEFT JOIN interactions i ON i.id = q.interaction_id
            LEFT JOIN agreement_activities act ON act.id = q.agreement_activity_id
            LEFT JOIN teacher_rosters r ON r.id = q.teacher_roster_id
            LEFT JOIN crm_user_profiles up ON up.id = q.updated_by
            """;
    // Отчёт и списки по периоду: запись входит, если её период пересекается с отчётным.
    private static final String OVERLAP = " AND %1$s.starts_on <= :to AND %1$s.ends_on >= :from";

    private final JdbcClient jdbcClient;

    public PeopleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // ---------- настройки ----------

    Optional<PeopleSettings> findSettings() {
        return jdbcClient.sql("""
                        SELECT s.*, p.display_name FROM people_settings s
                        LEFT JOIN crm_user_profiles p ON p.id = s.updated_by WHERE s.id = 1
                        """)
                .query((rs, row) -> new PeopleSettings(
                        HourUnit.valueOf(rs.getString("teaching_unit")),
                        HourUnit.valueOf(rs.getString("internship_unit")),
                        HourUnit.valueOf(rs.getString("qualification_unit")),
                        (Integer) rs.getObject("frdo_deadline_days"),
                        rs.getInt("frdo_notice_days"),
                        true,
                        rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getString("display_name")
                ))
                .optional();
    }

    void saveSettings(PeopleSettings settings, UUID actorId, OffsetDateTime now) {
        jdbcClient.sql("DELETE FROM people_settings").update();
        jdbcClient.sql("""
                        INSERT INTO people_settings (id, teaching_unit, internship_unit, qualification_unit, frdo_deadline_days,
                                                     frdo_notice_days, updated_by, updated_at)
                        VALUES (1, :teaching, :internship, :qualification, :deadline, :notice, :actor, :now)
                        """)
                .param("teaching", settings.teachingUnit().name())
                .param("internship", settings.internshipUnit().name())
                .param("qualification", settings.qualificationUnit().name())
                .param("deadline", settings.frdoDeadlineDays())
                .param("notice", settings.frdoNoticeDays())
                .param("actor", actorId)
                .param("now", now)
                .update();
    }

    void deleteSettings() {
        jdbcClient.sql("DELETE FROM people_settings").update();
    }

    // ---------- справочные значения формы ----------

    List<Option> employees() {
        return jdbcClient.sql("""
                        SELECT id, display_name FROM crm_user_profiles
                        WHERE active = TRUE AND pending_activation = FALSE AND anonymized_at IS NULL AND role <> 'PARTNER'
                        ORDER BY display_name, id
                        """)
                .query((rs, row) -> new Option(rs.getObject("id", UUID.class), rs.getString("display_name")))
                .list();
    }

    boolean employeeExists(UUID profileId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM crm_user_profiles
                        WHERE id = :id AND anonymized_at IS NULL AND role <> 'PARTNER'
                        """)
                .param("id", profileId)
                .query(Integer.class)
                .single() > 0;
    }

    List<Option> interactions(UUID organizationId) {
        return jdbcClient.sql("SELECT id, title FROM interactions WHERE organization_id = :org ORDER BY title, id")
                .param("org", organizationId)
                .query((rs, row) -> new Option(rs.getObject("id", UUID.class), rs.getString("title")))
                .list();
    }

    List<Option> agreements(UUID organizationId) {
        return jdbcClient.sql("SELECT id, number FROM agreements WHERE organization_id = :org ORDER BY number, id")
                .param("org", organizationId)
                .query((rs, row) -> new Option(rs.getObject("id", UUID.class), "№ " + rs.getString("number")))
                .list();
    }

    List<ActivityOption> activities(UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT act.id, act.agreement_id, a.number, act.title, k.name AS kind_name
                        FROM agreement_activities act
                        JOIN agreements a ON a.id = act.agreement_id
                        LEFT JOIN agreement_activity_kinds k ON k.id = act.kind_id
                        WHERE a.organization_id = :org
                        ORDER BY a.number, act.title, act.id
                        """)
                .param("org", organizationId)
                .query((rs, row) -> new ActivityOption(
                        rs.getObject("id", UUID.class),
                        rs.getObject("agreement_id", UUID.class),
                        rs.getString("number"),
                        rs.getString("title"),
                        rs.getString("kind_name")
                ))
                .list();
    }

    List<Option> teacherRosters(UUID organizationId) {
        return jdbcClient.sql("SELECT id, lms_course, lms_group FROM teacher_rosters WHERE organization_id = :org ORDER BY lms_course, id")
                .param("org", organizationId)
                .query((rs, row) -> new Option(rs.getObject("id", UUID.class), rosterTitle(rs.getString("lms_course"), rs.getString("lms_group"))))
                .list();
    }

    List<ContactOption> teachers(UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT id, name, email FROM contacts
                        WHERE organization_id = :org AND personal_data_status = 'ACTIVE' AND inactive = FALSE
                        ORDER BY name, id
                        """)
                .param("org", organizationId)
                .query((rs, row) -> new ContactOption(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email")))
                .list();
    }

    Optional<UUID> organizationOf(String table, UUID id) {
        return jdbcClient.sql("SELECT organization_id FROM " + table + " WHERE id = :id")
                .param("id", id)
                .query(UUID.class)
                .optional();
    }

    Optional<UUID> activityAgreement(UUID activityId) {
        return jdbcClient.sql("""
                        SELECT act.agreement_id FROM agreement_activities act JOIN agreements a ON a.id = act.agreement_id
                        WHERE act.id = :id
                        """)
                .param("id", activityId)
                .query(UUID.class)
                .optional();
    }

    Optional<UUID> activityOrganization(UUID activityId) {
        return jdbcClient.sql("""
                        SELECT a.organization_id FROM agreement_activities act JOIN agreements a ON a.id = act.agreement_id
                        WHERE act.id = :id
                        """)
                .param("id", activityId)
                .query(UUID.class)
                .optional();
    }

    Optional<ContactOption> contact(UUID organizationId, UUID contactId) {
        return jdbcClient.sql("""
                        SELECT id, name, email FROM contacts
                        WHERE id = :id AND organization_id = :org AND personal_data_status = 'ACTIVE'
                        """)
                .param("id", contactId)
                .param("org", organizationId)
                .query((rs, row) -> new ContactOption(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email")))
                .optional();
    }

    Optional<UUID> rosterByTitle(UUID organizationId, String course) {
        return jdbcClient.sql("""
                        SELECT id FROM teacher_rosters WHERE organization_id = :org AND LOWER(lms_course) = LOWER(:course)
                        ORDER BY created_at, id LIMIT 1
                        """)
                .param("org", organizationId)
                .param("course", course)
                .query(UUID.class)
                .optional();
    }

    Optional<ContactOption> contactByEmail(UUID organizationId, String email) {
        return jdbcClient.sql("""
                        SELECT id, name, email FROM contacts
                        WHERE organization_id = :org AND LOWER(email) = LOWER(:email) AND personal_data_status = 'ACTIVE'
                        ORDER BY id LIMIT 1
                        """)
                .param("org", organizationId)
                .param("email", email)
                .query((rs, row) -> new ContactOption(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email")))
                .optional();
    }

    // ---------- преподавание ----------

    List<TeachingRecord> teaching(UUID organizationId, UUID interactionId) {
        return jdbcClient.sql(TEACHING_SELECT + " WHERE t.organization_id = :org"
                        + (interactionId == null ? "" : " AND t.interaction_id = :interaction")
                        + " ORDER BY t.starts_on DESC, t.id")
                .param("org", organizationId)
                .params(interactionId == null ? Map.of() : Map.of("interaction", interactionId))
                .query(this::mapTeaching)
                .list();
    }

    Optional<TeachingRecord> teaching(UUID id) {
        return jdbcClient.sql(TEACHING_SELECT + " WHERE t.id = :id").param("id", id).query(this::mapTeaching).optional();
    }

    List<TeachingRecord> teachingReport(VisibilityScope scope, LocalDate from, LocalDate to) {
        return jdbcClient.sql(TEACHING_SELECT + " WHERE t.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")"
                        + OVERLAP.formatted("t") + " ORDER BY o.name, t.starts_on, t.id")
                .params(scope.parameters())
                .param("from", from)
                .param("to", to)
                .query(this::mapTeaching)
                .list();
    }

    void insertTeaching(UUID id, UUID organizationId, TeachingInput input, UUID actorId, OffsetDateTime now) {
        jdbcClient.sql("""
                        INSERT INTO university_teaching (id, organization_id, interaction_id, agreement_id, agreement_activity_id,
                            discipline, performer_profile_id, performer_name, performer_company, starts_on, ends_on, hours, hour_unit,
                            student_group, confirmation_status, confirmation_document, confirmed_on, version,
                            created_by, created_at, updated_by, updated_at)
                        VALUES (:id, :org, :interaction, :agreement, :activity, :discipline, :profile, :name, :company, :startsOn,
                            :endsOn, :hours, :unit, :studentGroup, :status, :document, :confirmedOn, 0, :actor, :now, :actor, :now)
                        """)
                .params(teachingParams(input))
                .param("id", id)
                .param("org", organizationId)
                .param("actor", actorId)
                .param("now", now)
                .update();
    }

    boolean updateTeaching(UUID id, int version, TeachingInput input, UUID actorId, OffsetDateTime now) {
        return jdbcClient.sql("""
                        UPDATE university_teaching SET interaction_id = :interaction, agreement_id = :agreement,
                            agreement_activity_id = :activity, discipline = :discipline, performer_profile_id = :profile,
                            performer_name = :name, performer_company = :company, starts_on = :startsOn, ends_on = :endsOn,
                            hours = :hours, hour_unit = :unit, student_group = :studentGroup, confirmation_status = :status,
                            confirmation_document = :document, confirmed_on = :confirmedOn, version = version + 1,
                            updated_by = :actor, updated_at = :now
                        WHERE id = :id AND version = :version
                        """)
                .params(teachingParams(input))
                .param("id", id)
                .param("version", version)
                .param("actor", actorId)
                .param("now", now)
                .update() == 1;
    }

    private static Map<String, Object> teachingParams(TeachingInput input) {
        java.util.HashMap<String, Object> params = new java.util.HashMap<>();
        params.put("interaction", input.interactionId());
        params.put("agreement", input.agreementId());
        params.put("activity", input.agreementActivityId());
        params.put("discipline", input.discipline());
        params.put("profile", input.performerProfileId());
        params.put("name", input.performerName());
        params.put("company", input.performerCompany());
        params.put("startsOn", input.startsOn());
        params.put("endsOn", input.endsOn());
        params.put("hours", input.hours());
        params.put("unit", input.hourUnit().name());
        params.put("studentGroup", input.studentGroup());
        params.put("status", input.confirmationStatus().name());
        params.put("document", input.confirmationDocument());
        params.put("confirmedOn", input.confirmedOn());
        return params;
    }

    private TeachingRecord mapTeaching(ResultSet rs, int row) throws SQLException {
        return new TeachingRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getString("organization_name"),
                rs.getObject("interaction_id", UUID.class),
                rs.getString("interaction_title"),
                rs.getObject("agreement_id", UUID.class),
                rs.getString("agreement_number"),
                rs.getObject("agreement_activity_id", UUID.class),
                rs.getString("activity_title"),
                rs.getString("discipline"),
                rs.getObject("performer_profile_id", UUID.class),
                rs.getString("performer_display"),
                rs.getString("performer_company"),
                rs.getObject("starts_on", LocalDate.class),
                rs.getObject("ends_on", LocalDate.class),
                hours(rs, "hours"),
                HourUnit.valueOf(rs.getString("hour_unit")),
                rs.getString("student_group"),
                ConfirmationStatus.valueOf(rs.getString("confirmation_status")),
                rs.getString("confirmation_document"),
                rs.getObject("confirmed_on", LocalDate.class),
                rs.getInt("version"),
                rs.getString("updated_by_name"),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    // ---------- стажировки ----------

    List<InternshipRecord> internships(UUID organizationId, UUID interactionId) {
        return jdbcClient.sql(INTERNSHIP_SELECT + " WHERE s.organization_id = :org"
                        + (interactionId == null ? "" : " AND s.interaction_id = :interaction")
                        + " ORDER BY s.starts_on DESC, s.id")
                .param("org", organizationId)
                .params(interactionId == null ? Map.of() : Map.of("interaction", interactionId))
                .query(this::mapInternship)
                .list();
    }

    Optional<InternshipRecord> internship(UUID id) {
        return jdbcClient.sql(INTERNSHIP_SELECT + " WHERE s.id = :id").param("id", id).query(this::mapInternship).optional();
    }

    List<InternshipRecord> internshipReport(VisibilityScope scope, LocalDate from, LocalDate to) {
        return jdbcClient.sql(INTERNSHIP_SELECT + " WHERE s.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")"
                        + OVERLAP.formatted("s") + " ORDER BY o.name, s.starts_on, s.id")
                .params(scope.parameters())
                .param("from", from)
                .param("to", to)
                .query(this::mapInternship)
                .list();
    }

    void insertInternship(UUID id, UUID organizationId, InternshipInput input, UUID actorId, OffsetDateTime now) {
        jdbcClient.sql("""
                        INSERT INTO internships (id, organization_id, interaction_id, agreement_activity_id, intern_name, intern_kind,
                            place, mentor_profile_id, mentor_name, starts_on, ends_on, intern_hours, mentor_hours, hour_unit, result,
                            employed, document, version, created_by, created_at, updated_by, updated_at)
                        VALUES (:id, :org, :interaction, :activity, :internName, :internKind, :place, :mentorProfile, :mentorName,
                            :startsOn, :endsOn, :internHours, :mentorHours, :unit, :result, :employed, :document, 0,
                            :actor, :now, :actor, :now)
                        """)
                .params(internshipParams(input))
                .param("id", id)
                .param("org", organizationId)
                .param("actor", actorId)
                .param("now", now)
                .update();
    }

    boolean updateInternship(UUID id, int version, InternshipInput input, UUID actorId, OffsetDateTime now) {
        return jdbcClient.sql("""
                        UPDATE internships SET interaction_id = :interaction, agreement_activity_id = :activity,
                            intern_name = :internName, intern_kind = :internKind, place = :place, mentor_profile_id = :mentorProfile,
                            mentor_name = :mentorName, starts_on = :startsOn, ends_on = :endsOn, intern_hours = :internHours,
                            mentor_hours = :mentorHours, hour_unit = :unit, result = :result, employed = :employed,
                            document = :document, version = version + 1, updated_by = :actor, updated_at = :now
                        WHERE id = :id AND version = :version AND anonymized_at IS NULL
                        """)
                .params(internshipParams(input))
                .param("id", id)
                .param("version", version)
                .param("actor", actorId)
                .param("now", now)
                .update() == 1;
    }

    private static Map<String, Object> internshipParams(InternshipInput input) {
        java.util.HashMap<String, Object> params = new java.util.HashMap<>();
        params.put("interaction", input.interactionId());
        params.put("activity", input.agreementActivityId());
        params.put("internName", input.internName());
        params.put("internKind", input.internKind().name());
        params.put("place", input.place());
        params.put("mentorProfile", input.mentorProfileId());
        params.put("mentorName", input.mentorName());
        params.put("startsOn", input.startsOn());
        params.put("endsOn", input.endsOn());
        params.put("internHours", input.internHours());
        params.put("mentorHours", input.mentorHours());
        params.put("unit", input.hourUnit().name());
        params.put("result", input.result().name());
        params.put("employed", Boolean.TRUE.equals(input.employed()));
        params.put("document", input.document());
        return params;
    }

    private InternshipRecord mapInternship(ResultSet rs, int row) throws SQLException {
        return new InternshipRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getString("organization_name"),
                rs.getObject("interaction_id", UUID.class),
                rs.getString("interaction_title"),
                rs.getObject("agreement_activity_id", UUID.class),
                rs.getString("activity_title"),
                rs.getString("intern_name"),
                InternKind.valueOf(rs.getString("intern_kind")),
                rs.getString("place"),
                rs.getObject("mentor_profile_id", UUID.class),
                rs.getString("mentor_display"),
                rs.getObject("starts_on", LocalDate.class),
                rs.getObject("ends_on", LocalDate.class),
                hours(rs, "intern_hours"),
                hours(rs, "mentor_hours"),
                HourUnit.valueOf(rs.getString("hour_unit")),
                InternshipResult.valueOf(rs.getString("result")),
                rs.getBoolean("employed"),
                rs.getString("document"),
                rs.getObject("anonymized_at", OffsetDateTime.class) != null,
                rs.getInt("version"),
                rs.getString("updated_by_name"),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    // ---------- повышение квалификации ----------

    List<QualificationRow> qualifications(UUID organizationId, UUID interactionId) {
        return jdbcClient.sql(QUALIFICATION_SELECT + " WHERE q.organization_id = :org"
                        + (interactionId == null ? "" : " AND q.interaction_id = :interaction")
                        + " ORDER BY q.ends_on DESC, q.person_name, q.id")
                .param("org", organizationId)
                .params(interactionId == null ? Map.of() : Map.of("interaction", interactionId))
                .query(this::mapQualification)
                .list();
    }

    Optional<QualificationRow> qualification(UUID id) {
        return jdbcClient.sql(QUALIFICATION_SELECT + " WHERE q.id = :id").param("id", id).query(this::mapQualification).optional();
    }

    List<QualificationRow> qualificationReport(VisibilityScope scope, LocalDate from, LocalDate to) {
        return jdbcClient.sql(QUALIFICATION_SELECT + " WHERE q.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")"
                        + OVERLAP.formatted("q") + " ORDER BY o.name, q.ends_on, q.id")
                .params(scope.parameters())
                .param("from", from)
                .param("to", to)
                .query(this::mapQualification)
                .list();
    }

    // Напоминание: удостоверение есть, в ФРДО не передано, срок (дата удостоверения + N дней) не позже границы.
    // Дата удостоверения сравнивается с границей, сдвинутой на срок, чтобы не считать даты в SQL по-разному на H2 и PostgreSQL.
    public List<FrdoReminder> frdoDue(VisibilityScope scope, int deadlineDays, LocalDate dueBy, int limit) {
        return jdbcClient.sql("""
                        SELECT q.id, q.organization_id, o.name AS organization_name, q.program, q.certificate_number, q.certificate_date
                        FROM teacher_qualifications q
                        JOIN organizations o ON o.id = q.organization_id
                        WHERE q.organization_id IN (SELECT id FROM organizations WHERE %s)
                          AND q.result = 'CERTIFIED' AND q.certificate_date IS NOT NULL AND q.frdo_submitted_on IS NULL
                          AND q.anonymized_at IS NULL AND q.certificate_date <= :certifiedBy
                        ORDER BY q.certificate_date, o.name, q.id
                        LIMIT :limit
                        """.formatted(scope.condition()))
                .params(scope.parameters())
                .param("certifiedBy", dueBy.minusDays(deadlineDays))
                .param("limit", limit)
                .query((rs, row) -> {
                    LocalDate certificateDate = rs.getObject("certificate_date", LocalDate.class);
                    return new FrdoReminder(
                            rs.getObject("id", UUID.class),
                            rs.getObject("organization_id", UUID.class),
                            rs.getString("organization_name"),
                            rs.getString("program"),
                            rs.getString("certificate_number"),
                            certificateDate,
                            certificateDate.plusDays(deadlineDays)
                    );
                })
                .list();
    }

    void insertQualification(UUID id, UUID organizationId, QualificationInput input, UUID actorId, OffsetDateTime now) {
        jdbcClient.sql("""
                        INSERT INTO teacher_qualifications (id, organization_id, interaction_id, agreement_activity_id, teacher_roster_id,
                            contact_id, person_name, person_email, program, starts_on, ends_on, hours, hour_unit, result,
                            certificate_number, certificate_date, frdo_submitted_on, version, created_by, created_at, updated_by, updated_at)
                        VALUES (:id, :org, :interaction, :activity, :roster, :contact, :personName, :personEmail, :program, :startsOn,
                            :endsOn, :hours, :unit, :result, :certificateNumber, :certificateDate, :frdo, 0, :actor, :now, :actor, :now)
                        """)
                .params(qualificationParams(input))
                .param("id", id)
                .param("org", organizationId)
                .param("actor", actorId)
                .param("now", now)
                .update();
    }

    boolean updateQualification(UUID id, int version, QualificationInput input, UUID actorId, OffsetDateTime now) {
        return jdbcClient.sql("""
                        UPDATE teacher_qualifications SET interaction_id = :interaction, agreement_activity_id = :activity,
                            teacher_roster_id = :roster, contact_id = :contact, person_name = :personName, person_email = :personEmail,
                            program = :program, starts_on = :startsOn, ends_on = :endsOn, hours = :hours, hour_unit = :unit,
                            result = :result, certificate_number = :certificateNumber, certificate_date = :certificateDate,
                            frdo_submitted_on = :frdo, version = version + 1, updated_by = :actor, updated_at = :now
                        WHERE id = :id AND version = :version AND anonymized_at IS NULL
                        """)
                .params(qualificationParams(input))
                .param("id", id)
                .param("version", version)
                .param("actor", actorId)
                .param("now", now)
                .update() == 1;
    }

    private static Map<String, Object> qualificationParams(QualificationInput input) {
        java.util.HashMap<String, Object> params = new java.util.HashMap<>();
        params.put("interaction", input.interactionId());
        params.put("activity", input.agreementActivityId());
        params.put("roster", input.teacherRosterId());
        params.put("contact", input.contactId());
        params.put("personName", input.personName());
        params.put("personEmail", input.personEmail());
        params.put("program", input.program());
        params.put("startsOn", input.startsOn());
        params.put("endsOn", input.endsOn());
        params.put("hours", input.hours());
        params.put("unit", input.hourUnit().name());
        params.put("result", input.result().name());
        params.put("certificateNumber", input.certificateNumber());
        params.put("certificateDate", input.certificateDate());
        params.put("frdo", input.frdoSubmittedOn());
        return params;
    }

    private QualificationRow mapQualification(ResultSet rs, int row) throws SQLException {
        String course = rs.getString("roster_course");
        return new QualificationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getString("organization_name"),
                rs.getObject("interaction_id", UUID.class),
                rs.getString("interaction_title"),
                rs.getObject("agreement_activity_id", UUID.class),
                rs.getString("activity_title"),
                rs.getObject("teacher_roster_id", UUID.class),
                course == null ? null : rosterTitle(course, rs.getString("roster_group")),
                rs.getObject("contact_id", UUID.class),
                rs.getString("person_name"),
                rs.getString("person_email"),
                rs.getString("program"),
                rs.getObject("starts_on", LocalDate.class),
                rs.getObject("ends_on", LocalDate.class),
                hours(rs, "hours"),
                HourUnit.valueOf(rs.getString("hour_unit")),
                QualificationResult.valueOf(rs.getString("result")),
                rs.getString("certificate_number"),
                rs.getObject("certificate_date", LocalDate.class),
                rs.getObject("frdo_submitted_on", LocalDate.class),
                rs.getObject("anonymized_at", OffsetDateTime.class) != null,
                rs.getInt("version"),
                rs.getString("updated_by_name"),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    // ---------- общее ----------

    int delete(String table, UUID id, int version) {
        return jdbcClient.sql("DELETE FROM " + table + " WHERE id = :id AND version = :version")
                .param("id", id)
                .param("version", version)
                .update();
    }

    Optional<Integer> version(String table, UUID id) {
        return jdbcClient.sql("SELECT version FROM " + table + " WHERE id = :id").param("id", id).query(Integer.class).optional();
    }

    static String rosterTitle(String course, String group) {
        return group == null ? course : course + ", группа " + group;
    }

    private static BigDecimal hours(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.stripTrailingZeros().scale() < 0 ? value.setScale(0) : value.stripTrailingZeros();
    }

    // Строка ПК до расчёта срока ФРДО: срок зависит от настройки, поэтому считается в сервисе.
    record QualificationRow(
            UUID id,
            UUID organizationId,
            String organizationName,
            UUID interactionId,
            String interactionTitle,
            UUID agreementActivityId,
            String agreementActivityTitle,
            UUID teacherRosterId,
            String teacherRosterTitle,
            UUID contactId,
            String personName,
            String personEmail,
            String program,
            LocalDate startsOn,
            LocalDate endsOn,
            BigDecimal hours,
            HourUnit hourUnit,
            QualificationResult result,
            String certificateNumber,
            LocalDate certificateDate,
            LocalDate frdoSubmittedOn,
            boolean anonymized,
            int version,
            String updatedByName,
            OffsetDateTime updatedAt
    ) {
        @Override
        public String toString() {
            return "QualificationRow[" + id + "]";
        }

        QualificationRecord record(Integer deadlineDays, LocalDate today) {
            LocalDate due = deadlineDays == null || result != QualificationResult.CERTIFIED || certificateDate == null
                    ? null : certificateDate.plusDays(deadlineDays);
            return new QualificationRecord(id, organizationId, organizationName, interactionId, interactionTitle, agreementActivityId,
                    agreementActivityTitle, teacherRosterId, teacherRosterTitle, contactId, personName, personEmail, program, startsOn,
                    endsOn, hours, hourUnit, result, certificateNumber, certificateDate, frdoSubmittedOn, due,
                    due != null && frdoSubmittedOn == null && due.isBefore(today), anonymized, version, updatedByName, updatedAt);
        }
    }
}
