package ru.rtk.crm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import ru.rtk.crm.access.AdminCrmProfileAccessDeniedException;
import ru.rtk.crm.access.ContactInteractionMutationAccessDeniedException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.people.PeopleModels.ConfirmationStatus;
import ru.rtk.crm.people.PeopleModels.HourUnit;
import ru.rtk.crm.people.PeopleModels.ImportResult;
import ru.rtk.crm.people.PeopleModels.InternKind;
import ru.rtk.crm.people.PeopleModels.InternshipInput;
import ru.rtk.crm.people.PeopleModels.InternshipRecord;
import ru.rtk.crm.people.PeopleModels.InternshipResult;
import ru.rtk.crm.people.PeopleModels.PeopleOverview;
import ru.rtk.crm.people.PeopleModels.PeopleReport;
import ru.rtk.crm.people.PeopleModels.PeopleSettings;
import ru.rtk.crm.people.PeopleModels.PeopleSettingsRequest;
import ru.rtk.crm.people.PeopleModels.QualificationInput;
import ru.rtk.crm.people.PeopleModels.QualificationRecord;
import ru.rtk.crm.people.PeopleModels.QualificationResult;
import ru.rtk.crm.people.PeopleModels.TeachingInput;
import ru.rtk.crm.people.PeopleModels.TeachingRecord;
import ru.rtk.crm.robustness.H2Schema;

// Учёт людей и часов на схеме из настоящих миграций: записи, связи с договором и списком LMS, права ролей, отчёт,
// настройки, напоминание о ФРДО и загрузка XLSX.
@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:people;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrganizationRepository.class, AuditJournalRepository.class, PeopleRepository.class, PeopleService.class, PeopleReportService.class})
class PeopleServiceTest {
    private static final UUID TEAM_A = UUID.fromString("62000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("62000000-0000-0000-0000-000000000002");
    private static final UUID KAM_A = UUID.fromString("62000000-0000-0000-0000-000000000011");
    private static final UUID KAM_B = UUID.fromString("62000000-0000-0000-0000-000000000012");
    private static final UUID LEADER_A = UUID.fromString("62000000-0000-0000-0000-000000000013");
    private static final UUID MANAGEMENT = UUID.fromString("62000000-0000-0000-0000-000000000014");
    private static final UUID ADMIN = UUID.fromString("62000000-0000-0000-0000-000000000015");
    private static final UUID LECTURER = UUID.fromString("62000000-0000-0000-0000-000000000016");
    private static final UUID UNIVERSITY_A = UUID.fromString("62000000-0000-0000-0000-000000000101");
    private static final UUID UNIVERSITY_B = UUID.fromString("62000000-0000-0000-0000-000000000102");
    private static final UUID WORK_A = UUID.fromString("62000000-0000-0000-0000-000000000201");
    private static final UUID AGREEMENT_A = UUID.fromString("62000000-0000-0000-0000-000000000301");
    private static final UUID AGREEMENT_B = UUID.fromString("62000000-0000-0000-0000-000000000302");
    private static final UUID ACTIVITY_A = UUID.fromString("62000000-0000-0000-0000-000000000311");
    private static final UUID ACTIVITY_B = UUID.fromString("62000000-0000-0000-0000-000000000312");
    private static final UUID ROSTER_A = UUID.fromString("62000000-0000-0000-0000-000000000401");
    private static final UUID TEACHER_A = UUID.fromString("62000000-0000-0000-0000-000000000501");
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 12, 25);

    private final CrmProfile kamA = new CrmProfile(KAM_A, UserRole.USER, TEAM_A, 0);
    private final CrmProfile kamB = new CrmProfile(KAM_B, UserRole.USER, TEAM_B, 0);
    private final CrmProfile leaderA = new CrmProfile(LEADER_A, UserRole.LEADER, TEAM_A, 0);
    private final CrmProfile management = new CrmProfile(MANAGEMENT, UserRole.MANAGEMENT, null, 0);
    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);

    @Autowired
    private PeopleService service;

    @Autowired
    private PeopleReportService reportService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws IOException {
        Integer tables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'UNIVERSITY_TEACHING'", Integer.class);
        if (tables == null || tables == 0) {
            H2Schema.apply(jdbc);
        }
        jdbc.update("INSERT INTO teams (id, name) VALUES (?, 'Команда А люди'), (?, 'Команда Б люди')", TEAM_A, TEAM_B);
        profile(KAM_A, "Анна КАМ", "USER", TEAM_A);
        profile(KAM_B, "Борис КАМ", "USER", TEAM_B);
        profile(LEADER_A, "Вера Руководитель", "LEADER", TEAM_A);
        profile(MANAGEMENT, "Руководство", "MANAGEMENT", null);
        profile(ADMIN, "Администратор", "ADMIN", null);
        profile(LECTURER, "Лектор Сотрудникович", "USER", TEAM_A);
        jdbc.update("""
                INSERT INTO organizations (id, name, type, team_id, owner_manager_id) VALUES
                (?, 'Университет людей А', 'UNIVERSITY', ?, ?), (?, 'Университет людей Б', 'UNIVERSITY', ?, ?)
                """, UNIVERSITY_A, TEAM_A, KAM_A, UNIVERSITY_B, TEAM_B, KAM_B);
        jdbc.update("INSERT INTO interactions (id, organization_id, title, current_stage_id, created_by, interaction_kind_id) VALUES (?, ?, 'Преподавание', ?, ?, (SELECT id FROM interaction_kinds ORDER BY id LIMIT 1))",
                WORK_A, UNIVERSITY_A, UUID.randomUUID(), KAM_A);
        UUID kind = jdbc.queryForObject("SELECT id FROM agreement_activity_kinds ORDER BY sort_order LIMIT 1", UUID.class);
        agreement(AGREEMENT_A, UNIVERSITY_A, ACTIVITY_A, kind);
        agreement(AGREEMENT_B, UNIVERSITY_B, ACTIVITY_B, kind);
        jdbc.update("INSERT INTO teacher_rosters (id, interaction_id, organization_id, lms_course, created_by, created_at) VALUES (?, ?, ?, 'PPS-2026', ?, CURRENT_TIMESTAMP)",
                ROSTER_A, WORK_A, UNIVERSITY_A, KAM_A);
        jdbc.update("INSERT INTO contacts (id, organization_id, name, email, created_by) VALUES (?, ?, 'Доцентова Ирина Петровна', 'docent@example.test', ?)",
                TEACHER_A, UNIVERSITY_A, KAM_A);
    }

    @Test
    void teachingIsRecordedWithEmployeeOrGroupPerformerAndLinkedToTheAgreementActivity() {
        TeachingRecord employee = service.createTeaching(kamA, UNIVERSITY_A, teaching(LECTURER, null, ACTIVITY_A), "rq-1");

        assertThat(employee.performerName()).isEqualTo("Лектор Сотрудникович");
        assertThat(employee.agreementId()).as("договор подставлен по мероприятию").isEqualTo(AGREEMENT_A);
        assertThat(employee.hourUnit()).as("единица по умолчанию из настроек").isEqualTo(HourUnit.ACADEMIC);
        assertThat(employee.confirmationStatus()).isEqualTo(ConfirmationStatus.NOT_REQUESTED);

        TeachingRecord partner = service.createTeaching(kamA, UNIVERSITY_A, teaching(null, "Иванов Исполнитель", null), "rq-2");
        assertThat(partner.performerName()).isEqualTo("Иванов Исполнитель");
        assertThat(partner.performerCompany()).isEqualTo("ООО «Дочерняя компания»");

        assertThatThrownBy(() -> service.createTeaching(kamA, UNIVERSITY_A, teaching(null, null, null), "rq-3"))
                .isInstanceOfSatisfying(InteractionValidationException.class, e -> assertThat(e.field()).isEqualTo("performerName"));
        assertThatThrownBy(() -> service.createTeaching(kamA, UNIVERSITY_A, teaching(LECTURER, null, ACTIVITY_B), "rq-4"))
                .as("мероприятие чужого договора").isInstanceOfSatisfying(InteractionValidationException.class,
                        e -> assertThat(e.field()).isEqualTo("agreementActivityId"));
        assertThatThrownBy(() -> service.createTeaching(kamA, UNIVERSITY_A, withHours(teaching(LECTURER, null, null), "0.25"), "rq-5"))
                .isInstanceOfSatisfying(InteractionValidationException.class, e -> assertThat(e.field()).isEqualTo("hours"));

        String journal = String.join("\n", jdbc.queryForList(
                "SELECT details FROM audit_events WHERE action = 'PEOPLE_RECORD_CHANGED'", String.class));
        assertThat(journal).contains("создана запись", "36 академ. ч").doesNotContain("Иванов", "Лектор");
    }

    @Test
    void staleVersionIsRejectedAndDeletionNeedsTheCurrentVersion() {
        TeachingRecord record = service.createTeaching(kamA, UNIVERSITY_A, teaching(LECTURER, null, null), "rq-1");
        TeachingRecord confirmed = service.updateTeaching(kamA, record.id(), confirm(teaching(LECTURER, null, null), 0), "rq-2");
        assertThat(confirmed.confirmationStatus()).isEqualTo(ConfirmationStatus.CONFIRMED);
        assertThat(confirmed.version()).isEqualTo(1);

        assertThatThrownBy(() -> service.updateTeaching(kamA, record.id(), confirm(teaching(LECTURER, null, null), 0), "rq-3"))
                .isInstanceOf(InteractionConflictException.class);
        assertThatThrownBy(() -> service.delete(kamA, PeopleService.TEACHING, record.id(), 0, "rq-4"))
                .isInstanceOf(InteractionConflictException.class);
        service.delete(kamA, PeopleService.TEACHING, record.id(), 1, "rq-5");
        assertThat(service.overview(kamA, UNIVERSITY_A, null).teaching()).isEmpty();
    }

    @Test
    void kamSeesOwnUniversitiesLeaderTheTeamManagementReadsAdministratorHasNoBusinessData() {
        service.createTeaching(kamA, UNIVERSITY_A, teaching(LECTURER, null, null), "rq-1");

        assertThatThrownBy(() -> service.overview(kamB, UNIVERSITY_A, null)).isInstanceOf(OrganizationNotFoundException.class);
        assertThatThrownBy(() -> service.createTeaching(kamB, UNIVERSITY_A, teaching(LECTURER, null, null), "rq-2"))
                .isInstanceOf(OrganizationNotFoundException.class);

        PeopleOverview leader = service.overview(leaderA, UNIVERSITY_A, null);
        assertThat(leader.canEdit()).isTrue();
        assertThat(leader.options().employees()).extracting(PeopleModels.Option::title).contains("Лектор Сотрудникович");
        service.createInternship(leaderA, UNIVERSITY_A, internship("Студентов Пётр"), "rq-3");

        PeopleOverview read = service.overview(management, UNIVERSITY_A, null);
        assertThat(read.canEdit()).isFalse();
        assertThat(read.teaching()).hasSize(1);
        assertThat(read.options().employees()).isEmpty();
        assertThatThrownBy(() -> service.createTeaching(management, UNIVERSITY_A, teaching(LECTURER, null, null), "rq-4"))
                .isInstanceOf(ContactInteractionMutationAccessDeniedException.class);

        assertThatThrownBy(() -> service.overview(admin, UNIVERSITY_A, null))
                .isInstanceOf(ContactInteractionMutationAccessDeniedException.class);
        assertThatThrownBy(() -> reportService.report(admin, null, null))
                .isInstanceOf(ContactInteractionMutationAccessDeniedException.class);
        assertThatThrownBy(() -> service.updateSettings(kamA, settings(30), "rq-5"))
                .isInstanceOf(AdminCrmProfileAccessDeniedException.class);
    }

    @Test
    void interactionTabShowsOnlyItsRecords() {
        service.createTeaching(kamA, UNIVERSITY_A, teaching(LECTURER, null, null), "rq-1");
        TeachingInput linked = new TeachingInput(WORK_A, null, null, "Сети", LECTURER, null, null, START, END,
                new BigDecimal("12"), null, null, null, null, null, null);
        service.createTeaching(kamA, UNIVERSITY_A, linked, "rq-2");

        assertThat(service.overview(kamA, UNIVERSITY_A, WORK_A).teaching()).extracting(TeachingRecord::discipline).containsExactly("Сети");
        assertThat(service.overview(kamA, UNIVERSITY_A, null).teaching()).hasSize(2);
        assertThatThrownBy(() -> service.overview(kamB, UNIVERSITY_B, WORK_A)).isInstanceOf(InteractionValidationException.class);
    }

    @Test
    void anonymizedInternshipCannotBeEdited() {
        InternshipRecord record = service.createInternship(kamA, UNIVERSITY_A, internship("Стажёров Илья"), "rq-1");
        assertThat(record.hourUnit()).isEqualTo(HourUnit.ASTRONOMICAL);
        assertThat(record.mentorName()).isEqualTo("Лектор Сотрудникович");
        jdbc.update("UPDATE internships SET intern_name = 'Стажёр обезличен', anonymized_at = CURRENT_TIMESTAMP WHERE id = ?", record.id());

        assertThatThrownBy(() -> service.updateInternship(kamA, record.id(), internship("Стажёров Илья"), "rq-2"))
                .isInstanceOf(InteractionValidationException.class).hasMessageContaining("обезличена");
    }

    @Test
    void qualificationTakesContactDataLinksMoodleRosterAndCountsTheFrdoDeadlineFromSettings() {
        QualificationRecord record = service.createQualification(kamA, UNIVERSITY_A, qualification(QualificationResult.CERTIFIED,
                LocalDate.now(PeopleService.ZONE).minusDays(40), null), "rq-1");
        assertThat(record.personName()).isEqualTo("Доцентова Ирина Петровна");
        assertThat(record.personEmail()).isEqualTo("docent@example.test");
        assertThat(record.teacherRosterTitle()).isEqualTo("PPS-2026");
        assertThat(record.frdoDueOn()).as("срок по умолчанию пуст").isNull();
        assertThat(service.frdoReminders(scopeOf(kamA), LocalDate.now(PeopleService.ZONE), 10)).isEmpty();

        PeopleSettings saved = service.updateSettings(admin, settings(30), "rq-2");
        assertThat(saved.frdoDeadlineDays()).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT details FROM audit_events WHERE action = 'PEOPLE_SETTINGS_CHANGED'", String.class))
                .contains("срок передачи в ФРДО, дней: не задан → 30");

        QualificationRecord overdue = service.overview(kamA, UNIVERSITY_A, null).qualifications().getFirst();
        assertThat(overdue.frdoOverdue()).isTrue();
        assertThat(service.frdoReminders(scopeOf(kamA), LocalDate.now(PeopleService.ZONE), 10))
                .extracting(PeopleModels.FrdoReminder::qualificationId).containsExactly(record.id());
        assertThat(service.frdoReminders(scopeOf(kamB), LocalDate.now(PeopleService.ZONE), 10)).as("чужой вуз").isEmpty();

        service.updateQualification(kamA, record.id(), withVersion(qualification(QualificationResult.CERTIFIED,
                LocalDate.now(PeopleService.ZONE).minusDays(40), LocalDate.now(PeopleService.ZONE)), 0), "rq-3");
        assertThat(service.frdoReminders(scopeOf(kamA), LocalDate.now(PeopleService.ZONE), 10)).as("передано в ФРДО").isEmpty();

        assertThatThrownBy(() -> service.createQualification(kamA, UNIVERSITY_A, qualification(QualificationResult.NOT_CERTIFIED,
                null, LocalDate.now(PeopleService.ZONE)), "rq-4"))
                .isInstanceOfSatisfying(InteractionValidationException.class, e -> assertThat(e.field()).isEqualTo("frdoSubmittedOn"));
    }

    @Test
    void reportConvertsHoursToTheConfiguredUnitAndKeepsTheScope() {
        service.createTeaching(kamA, UNIVERSITY_A, teaching(LECTURER, null, null), "rq-1");
        TeachingInput astronomical = new TeachingInput(null, null, null, "Облака", null, "Иванов Исполнитель", null, START, END,
                new BigDecimal("3"), HourUnit.ASTRONOMICAL, "ИВТ-21", ConfirmationStatus.CONFIRMED, "Акт № 5", END, null);
        service.createTeaching(kamA, UNIVERSITY_A, astronomical, "rq-2");
        service.createInternship(kamA, UNIVERSITY_A, internship("Студентов Пётр"), "rq-3");
        service.createQualification(kamA, UNIVERSITY_A, qualification(QualificationResult.CERTIFIED, END, null), "rq-4");

        PeopleReport report = reportService.report(leaderA, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(report.teaching().records()).isEqualTo(2);
        assertThat(report.teaching().hours()).as("36 академ. + 3 астроном. = 40 академ.").isEqualByComparingTo("40");
        assertThat(report.teaching().confirmedHours()).isEqualByComparingTo("4");
        assertThat(report.teaching().byOrganization()).singleElement().satisfies(row -> assertThat(row.records()).isEqualTo(2));
        assertThat(report.teaching().byPerformer()).hasSize(2);
        assertThat(report.teaching().byMonth()).singleElement().satisfies(row -> assertThat(row.title()).isEqualTo("сентябрь 2026"));
        assertThat(report.internships().students()).isEqualTo(1);
        assertThat(report.internships().internHours()).isEqualByComparingTo("72");
        assertThat(report.internships().mentorHours()).isEqualByComparingTo("10");
        assertThat(report.qualifications().certified()).isEqualTo(1);
        assertThat(report.qualifications().frdoPending()).isEqualTo(1);

        assertThat(reportService.report(kamB, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).teaching().records()).isZero();
        assertThat(reportService.report(management, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).teaching().records()).isEqualTo(2);
        assertThat(reportService.report(kamA, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 1)).teaching().records())
                .as("период не пересекается").isZero();
        assertThatThrownBy(() -> reportService.report(kamA, LocalDate.of(2027, 1, 1), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(InteractionValidationException.class);
    }

    @Test
    void importIsAllOrNothingAndMatchesContactsByEmail() throws IOException {
        byte[] broken = workbook(
                List.of("Доцентова Ирина Петровна", "DOCENT@example.test", "Облачные технологии", "01.09.2026", "30.09.2026", "72", "аттестован", "У-1", "30.09.2026", ""),
                List.of("Без Программы", "", "", "01.09.2026", "30.09.2026", "16", "", "", "", ""),
                List.of("Датов Ошибка", "", "Сети", "31.02.2026", "30.09.2026", "16", "", "", "", ""),
                List.of("Много Ошибок", "", "", "10.09.2026", "01.09.2026", "много", "не аттестован", "", "", "15.09.2026")
        );
        ImportResult rejected = service.importQualifications(kamA, UNIVERSITY_A, file(broken), null, ROSTER_A, false, "rq-1");
        assertThat(rejected.applied()).isFalse();
        assertThat(rejected.created()).isZero();
        assertThat(rejected.errors()).extracting(PeopleModels.ImportError::row).containsExactly(3, 4, 5, 5, 5, 5);
        assertThat(rejected.errors().get(0).message()).startsWith("«Программа»");
        // Все ошибки строки сразу: разбор ячейки «Часы» и правила записи (программа, даты, ФРДО).
        assertThat(rejected.errors().subList(2, 6)).extracting(PeopleModels.ImportError::message).containsExactlyInAnyOrder(
                "«Часы»: нужно число", "«Программа»: Заполните поле", "«Дата окончания»: Окончание раньше начала",
                "«Передано в ФРДО»: В ФРДО передают сведения только об аттестованных");
        assertThat(service.overview(kamA, UNIVERSITY_A, null).qualifications()).isEmpty();

        byte[] valid = workbook(
                List.of("Доцентова И. П. (из файла)", "DOCENT@example.test", "Облачные технологии", "01.09.2026", "30.09.2026", "72", "аттестован", "У-1", "30.09.2026", ""),
                List.of("Ассистентов Кирилл", "", "Сети", "01.09.2026", "30.09.2026", "16,5", "обучается", "", "", "")
        );
        ImportResult preview = service.importQualifications(kamA, UNIVERSITY_A, file(valid), null, null, true, "rq-2");
        assertThat(preview.applied()).isFalse();
        assertThat(preview.errors()).isEmpty();
        assertThat(service.overview(kamA, UNIVERSITY_A, null).qualifications()).isEmpty();

        ImportResult applied = service.importQualifications(kamA, UNIVERSITY_A, file(valid), WORK_A, ROSTER_A, false, "rq-3");
        assertThat(applied.applied()).isTrue();
        assertThat(applied.created()).isEqualTo(2);
        List<QualificationRecord> records = service.overview(kamA, UNIVERSITY_A, WORK_A).qualifications();
        assertThat(records).filteredOn(record -> record.contactId() != null).singleElement()
                .satisfies(record -> {
                    assertThat(record.contactId()).isEqualTo(TEACHER_A);
                    // ФИО берётся из контакта, как при ручном выборе контакта.
                    assertThat(record.personName()).isEqualTo("Доцентова Ирина Петровна");
                });
        assertThat(records).extracting(QualificationRecord::hours).extracting(BigDecimal::toPlainString).contains("16.5", "72");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'PEOPLE_IMPORT_APPLIED'", Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> service.importQualifications(kamA, UNIVERSITY_A,
                new MockMultipartFile("file", "x.xlsx", null, "not a workbook".getBytes()), null, null, false, "rq-4"))
                .isInstanceOfSatisfying(InteractionValidationException.class, e -> assertThat(e.field()).isEqualTo("file"));
        assertThatThrownBy(() -> service.importQualifications(management, UNIVERSITY_A, file(valid), null, null, false, "rq-5"))
                .isInstanceOf(ContactInteractionMutationAccessDeniedException.class);
        assertThat(QualificationWorkbook.template()).isNotEmpty();
    }

    @Test
    void hourUnitsConvertByMinutes() {
        assertThat(HourUnit.ASTRONOMICAL.convert(new BigDecimal("3"), HourUnit.ACADEMIC)).isEqualByComparingTo("4");
        assertThat(HourUnit.ACADEMIC.convert(new BigDecimal("36"), HourUnit.ASTRONOMICAL)).isEqualByComparingTo("27");
        assertThat(HourUnit.ACADEMIC.convert(new BigDecimal("1"), HourUnit.ASTRONOMICAL)).isEqualByComparingTo("0.8");
    }

    private TeachingInput teaching(UUID profile, String name, UUID activity) {
        return new TeachingInput(null, null, activity, "Компьютерные сети", profile, name, name == null ? null : "ООО «Дочерняя компания»",
                START, END, new BigDecimal("36"), null, "ИВТ-21", null, null, null, null);
    }

    private static TeachingInput withHours(TeachingInput input, String hours) {
        return new TeachingInput(input.interactionId(), input.agreementId(), input.agreementActivityId(), input.discipline(),
                input.performerProfileId(), input.performerName(), input.performerCompany(), input.startsOn(), input.endsOn(),
                new BigDecimal(hours), input.hourUnit(), input.studentGroup(), input.confirmationStatus(), input.confirmationDocument(),
                input.confirmedOn(), input.version());
    }

    private static TeachingInput confirm(TeachingInput input, int version) {
        return new TeachingInput(input.interactionId(), input.agreementId(), input.agreementActivityId(), input.discipline(),
                input.performerProfileId(), input.performerName(), input.performerCompany(), input.startsOn(), input.endsOn(),
                input.hours(), input.hourUnit(), input.studentGroup(), ConfirmationStatus.CONFIRMED, "Справка вуза № 12", END, version);
    }

    private static InternshipInput internship(String name) {
        return new InternshipInput(null, null, name, InternKind.STUDENT, "Отдел облачных решений", LECTURER, null, START,
                LocalDate.of(2026, 10, 1), new BigDecimal("72"), new BigDecimal("10"), null, InternshipResult.IN_PROGRESS, false,
                null, 0);
    }

    private static QualificationInput qualification(QualificationResult result, LocalDate certificateDate, LocalDate frdo) {
        return new QualificationInput(null, null, ROSTER_A, TEACHER_A, null, null, "Облачные технологии", START,
                LocalDate.of(2026, 9, 30), new BigDecimal("72"), null, result, "У-0001", certificateDate, frdo, null);
    }

    private static QualificationInput withVersion(QualificationInput input, int version) {
        return new QualificationInput(input.interactionId(), input.agreementActivityId(), input.teacherRosterId(), input.contactId(),
                input.personName(), input.personEmail(), input.program(), input.startsOn(), input.endsOn(), input.hours(),
                input.hourUnit(), input.result(), input.certificateNumber(), input.certificateDate(), input.frdoSubmittedOn(), version);
    }

    private static PeopleSettingsRequest settings(Integer deadline) {
        return new PeopleSettingsRequest(HourUnit.ACADEMIC, HourUnit.ASTRONOMICAL, HourUnit.ACADEMIC, deadline, 7);
    }

    private OrganizationRepository.VisibilityScope scopeOf(CrmProfile profile) {
        return new OrganizationRepository(org.springframework.jdbc.core.simple.JdbcClient.create(jdbc)).visibilityScope(profile).orElseThrow();
    }

    private void profile(UUID id, String name, String role, UUID team) {
        jdbc.update("INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, active) VALUES (?, 'test', ?, ?, ?, ?, TRUE)",
                id, id.toString(), name, role, team);
    }

    private void agreement(UUID id, UUID organization, UUID activity, UUID kind) {
        jdbc.update("INSERT INTO agreements (id, organization_id, number, status, created_by) VALUES (?, ?, ?, 'ACTIVE', ?)",
                id, organization, "Д-" + id.toString().substring(34), KAM_A);
        jdbc.update("INSERT INTO agreement_activities (id, agreement_id, kind_id, title, status) VALUES (?, ?, ?, 'Преподавание практиков', 'PLANNED')",
                activity, id, kind);
    }

    private static MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "ПК.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }

    @SafeVarargs
    private static byte[] workbook(List<String>... rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Повышение квалификации");
            Row header = sheet.createRow(0);
            for (QualificationWorkbook.Column column : QualificationWorkbook.Column.values()) {
                header.createCell(column.ordinal()).setCellValue(column.title + (column.required ? " *" : ""));
            }
            for (int index = 0; index < rows.length; index++) {
                Row row = sheet.createRow(index + 1);
                for (int cell = 0; cell < rows[index].size(); cell++) {
                    row.createCell(cell).setCellValue(rows[index].get(cell));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
