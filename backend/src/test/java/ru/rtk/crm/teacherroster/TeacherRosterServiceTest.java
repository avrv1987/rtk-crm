package ru.rtk.crm.teacherroster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
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
import ru.rtk.crm.access.ContactInteractionMutationAccessDeniedException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.enrolment.LmsRosterWorkbookWriter;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.teacherroster.TeacherRosterModels.FileMode;
import ru.rtk.crm.teacherroster.TeacherRosterModels.MemberStatus;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRoster;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterFile;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterFileRequest;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterMarked;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterMember;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterRequest;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:teacherrosters;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        AuditJournalRepository.class,
        LmsRosterWorkbookWriter.class,
        TeacherRosterRepository.class,
        TeacherRosterService.class
})
class TeacherRosterServiceTest {
    private static final List<String> TEMPLATE_HEADERS = List.of(
            "Фамилия", "Имя", "Отчествопри наличии)", "Номер телефона", "Email", "СНИЛС", "Серия паспорта", "Номер паспорта",
            "Кем выдан паспорт", "Дата выдачи паспорта", "Код подразделения", "Пол", "Дата рождения", "Регион регистрации",
            "Населенный пункт регистрации", "Улица регистрации", "Дом регистрации", "Квартира регистрации",
            "Индекс регистрации", "Имядательный падеж)", "Фамилиядательный падеж)", "Отчестводательный падеж)",
            "Образование", "Профессия по диплому", "Учебное заведение по диплому", "Фамилия, указанная в дипломе",
            "Номер диплома", "Серия диплома", "Регистрационный номер диплома", "Дата выдачи диплома"
    );
    private static final UUID TEAM_A = uuid(1);
    private static final UUID TEAM_B = uuid(2);
    private static final UUID KAM_A = uuid(11);
    private static final UUID KAM_B = uuid(12);
    private static final UUID LEADER_A = uuid(13);
    private static final UUID MANAGEMENT = uuid(14);
    private static final UUID ADMIN = uuid(15);
    private static final UUID ORGANIZATION_A = uuid(101);
    private static final UUID ORGANIZATION_B = uuid(102);
    private static final UUID WORK_A = uuid(301);
    private static final UUID LECTOROVA = uuid(501);
    private static final UUID SEMINAROV = uuid(502);
    private static final UUID NO_EMAIL = uuid(503);
    private static final UUID INITIALS = uuid(504);
    private static final UUID INACTIVE = uuid(505);
    private static final UUID RESTRICTED = uuid(506);
    private static final UUID FOREIGN = uuid(507);

    private final CrmProfile kamA = new CrmProfile(KAM_A, UserRole.USER, TEAM_A, 0);
    private final CrmProfile kamB = new CrmProfile(KAM_B, UserRole.USER, TEAM_B, 0);
    private final CrmProfile leaderA = new CrmProfile(LEADER_A, UserRole.LEADER, TEAM_A, 0);
    private final CrmProfile management = new CrmProfile(MANAGEMENT, UserRole.MANAGEMENT, null, 0);
    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);

    @Autowired
    private TeacherRosterService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of(
                "teacher_roster_members", "teacher_roster_exports", "teacher_rosters", "audit_events", "contacts",
                "interactions", "organizations", "crm_user_profiles", "teams"
        )) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO teams (id, name) VALUES (?, 'Команда А'), (?, 'Команда Б')", TEAM_A, TEAM_B);
        profile(KAM_A, "Анна Смирнова", "USER", TEAM_A);
        profile(KAM_B, "Борис Орлов", "USER", TEAM_B);
        profile(LEADER_A, "Вера Ковалёва", "LEADER", TEAM_A);
        profile(MANAGEMENT, "Руководство", "MANAGEMENT", null);
        profile(ADMIN, "Администратор", "ADMIN", null);
        organization(ORGANIZATION_A, "Университет А", TEAM_A, KAM_A);
        organization(ORGANIZATION_B, "Университет Б", TEAM_B, KAM_B);
        jdbc.update("INSERT INTO interactions (id, organization_id, title) VALUES (?, ?, 'Повышение квалификации преподавателей')",
                WORK_A, ORGANIZATION_A);
        contact(LECTOROVA, ORGANIZATION_A, "Лекторова Мария Демовна", "lectorova@example.test", false, "ACTIVE");
        contact(SEMINAROV, ORGANIZATION_A, "Семинаров Павел", "Seminarov@Example.test", false, "ACTIVE");
        contact(NO_EMAIL, ORGANIZATION_A, "Ассистентов Кирилл Демович", null, false, "ACTIVE");
        contact(INITIALS, ORGANIZATION_A, "Доцентова И.", "docentova@example.test", false, "ACTIVE");
        contact(INACTIVE, ORGANIZATION_A, "Уволенов Олег", "uvolenov@example.test", true, "ACTIVE");
        contact(RESTRICTED, ORGANIZATION_A, "Ограниченова Нина", "nina@example.test", false, "RESTRICTED");
        contact(FOREIGN, ORGANIZATION_B, "Чужой Преподаватель", "foreign@example.test", false, "ACTIVE");
    }

    @Test
    void fileHasTemplateHeadersAndOnlyNameAndEmailColumnsOfReadyTeachers() throws IOException {
        TeacherRoster roster = service.create(kamA, WORK_A, new TeacherRosterRequest("  ППС 2026:  цифровой университет ", "Группа 1"));
        service.addMember(kamA, roster.id(), SEMINAROV);
        service.addMember(kamA, roster.id(), LECTOROVA);
        service.addMember(kamA, roster.id(), NO_EMAIL);
        roster = service.addMember(kamA, roster.id(), INITIALS);

        assertThat(roster.lmsCourse()).isEqualTo("ППС 2026: цифровой университет");
        assertThat(roster.readyCount()).isEqualTo(2);
        assertThat(member(roster, LECTOROVA)).satisfies(member -> {
            assertThat(member.lastName()).isEqualTo("Лекторова");
            assertThat(member.firstName()).isEqualTo("Мария");
            assertThat(member.middleName()).isEqualTo("Демовна");
            assertThat(member.problems()).isEmpty();
            assertThat(member.status()).isEqualTo(MemberStatus.LISTED);
        });
        assertThat(member(roster, NO_EMAIL).problems()).containsExactly("Не указана электронная почта: она нужна для входа в LMS");
        assertThat(member(roster, INITIALS).problems()).containsExactly("Имя или фамилия записаны инициалом: укажите ФИО полностью");

        TeacherRosterFile file = service.export(kamA, roster.id(), new TeacherRosterFileRequest(FileMode.PENDING), "request-1");

        assertThat(file.rows()).isEqualTo(2);
        assertThat(file.skipped()).isEqualTo(2);
        assertThat(file.fileName()).startsWith("LMS_преподаватели_ППС 2026_ цифровой университет_Группа 1_").endsWith(".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(1)).isEqualTo("Лист2");
            Sheet sheet = workbook.getSheet("Лист1");
            assertThat(texts(sheet.getRow(0), TEMPLATE_HEADERS.size())).isEqualTo(TEMPLATE_HEADERS);
            assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) TEMPLATE_HEADERS.size());
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(texts(sheet.getRow(1), 5)).containsExactly("Лекторова", "Мария", "Демовна", null, "lectorova@example.test");
            assertThat(texts(sheet.getRow(2), 5)).containsExactly("Семинаров", "Павел", null, null, "Seminarov@Example.test");
            for (int row = 1; row <= 2; row++) {
                for (int column = 5; column < TEMPLATE_HEADERS.size(); column++) {
                    assertThat(sheet.getRow(row).getCell(column)).isNull();
                }
            }
        }
        TeacherRoster exported = service.list(kamA, WORK_A).getFirst();
        assertThat(member(exported, LECTOROVA).status()).isEqualTo(MemberStatus.EXPORTED);
        assertThat(member(exported, NO_EMAIL).status()).isEqualTo(MemberStatus.LISTED);
        assertThat(exported.exports()).singleElement().satisfies(export -> {
            assertThat(export.id()).isEqualTo(file.exportId());
            assertThat(export.rows()).isEqualTo(2);
            assertThat(export.exportedByName()).isEqualTo("Анна Смирнова");
            assertThat(export.transferredAt()).isNull();
        });
        assertThat(journal("TEACHER_ROSTER_EXPORTED")).singleElement().satisfies(details -> {
            assertThat(details).contains("строк: 2", "не готовы и не выгружены: 2", file.exportId().toString());
            assertThat(details).doesNotContain("lectorova", "Лекторова", "Семинаров");
        });
    }

    @Test
    void markTransferredIsExactAndRepeatSafeAndPendingFileTakesOnlyNewTeachers() throws IOException {
        TeacherRoster roster = service.create(kamA, WORK_A, new TeacherRosterRequest("ППС 2026", null));
        service.addMember(kamA, roster.id(), LECTOROVA);
        service.addMember(kamA, roster.id(), SEMINAROV);
        TeacherRosterFile first = service.export(kamA, roster.id(), new TeacherRosterFileRequest(FileMode.PENDING), "r1");

        TeacherRosterMarked marked = service.markTransferred(leaderA, first.exportId(), "r2");
        TeacherRosterMarked repeated = service.markTransferred(leaderA, first.exportId(), "r3");

        assertThat(marked.marked()).isEqualTo(2);
        assertThat(repeated.marked()).isZero();
        assertThat(marked.roster().transferredCount()).isEqualTo(2);
        assertThat(marked.roster().exports().getFirst().transferredByName()).isEqualTo("Вера Ковалёва");
        assertThat(journal("TEACHER_ROSTER_MARKED")).singleElement().asString().contains("отмечено переданными: 2");
        assertThatThrownBy(() -> service.export(kamA, roster.id(), new TeacherRosterFileRequest(FileMode.PENDING), "r4"))
                .isInstanceOf(InteractionValidationException.class)
                .hasMessageContaining("все из списка уже переданы");

        service.addMember(kamA, roster.id(), jdbcContact("Практикова Ольга", "praktikova@example.test"));
        TeacherRosterFile second = service.export(kamA, roster.id(), new TeacherRosterFileRequest(FileMode.PENDING), "r5");
        TeacherRosterFile again = service.export(kamA, roster.id(), new TeacherRosterFileRequest(FileMode.PENDING), "r6");
        TeacherRosterFile all = service.export(kamA, roster.id(), new TeacherRosterFileRequest(FileMode.ALL), "r7");

        assertThat(second.rows()).isEqualTo(1);
        assertThat(again.rows()).isEqualTo(1);
        assertThat(again.exportId()).isNotEqualTo(second.exportId());
        assertThat(all.rows()).isEqualTo(3);
        assertThat(service.markTransferred(kamA, second.exportId(), "r8").marked()).isZero();
        TeacherRoster afterAll = service.markTransferred(kamA, all.exportId(), "r9").roster();
        assertThat(afterAll.transferredCount()).isEqualTo(3);
        assertThat(afterAll.members()).allSatisfy(member -> assertThat(member.status()).isEqualTo(MemberStatus.TRANSFERRED));
        assertThat(afterAll.exports()).hasSize(4);
        assertThat(journal("TEACHER_ROSTER_EXPORTED")).hasSize(4);
    }

    @Test
    void rightsFollowWorkScopeAndManagementOnlyReads() {
        TeacherRoster roster = service.create(kamA, WORK_A, new TeacherRosterRequest("ППС 2026", null));
        service.addMember(leaderA, roster.id(), LECTOROVA);
        TeacherRosterFile file = service.export(leaderA, roster.id(), new TeacherRosterFileRequest(FileMode.ALL), "r1");

        assertThat(service.list(management, WORK_A)).singleElement()
                .satisfies(visible -> assertThat(visible.members()).hasSize(1));
        assertThatThrownBy(() -> service.list(kamB, WORK_A)).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> service.list(admin, WORK_A)).isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> service.create(kamB, WORK_A, new TeacherRosterRequest("Чужой", null)))
                .isInstanceOf(InteractionNotFoundException.class);
        assertThatThrownBy(() -> service.addMember(kamB, roster.id(), SEMINAROV)).isInstanceOf(TeacherRosterNotFoundException.class);
        assertThatThrownBy(() -> service.markTransferred(kamB, file.exportId(), "r2")).isInstanceOf(TeacherRosterNotFoundException.class);
        assertThatThrownBy(() -> service.markTransferred(kamA, UUID.randomUUID(), "r3")).isInstanceOf(TeacherRosterNotFoundException.class);
        assertThatThrownBy(() -> service.create(management, WORK_A, new TeacherRosterRequest("ППС", null)))
                .isInstanceOf(ContactInteractionMutationAccessDeniedException.class);
        assertThatThrownBy(() -> service.export(management, roster.id(), new TeacherRosterFileRequest(FileMode.ALL), "r4"))
                .isInstanceOf(ContactInteractionMutationAccessDeniedException.class);
        assertThatThrownBy(() -> service.markTransferred(management, file.exportId(), "r5"))
                .isInstanceOf(ContactInteractionMutationAccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teacher_roster_exports", Integer.class)).isEqualTo(1);
    }

    @Test
    void listRulesKeepDataConsistent() {
        TeacherRoster roster = service.create(kamA, WORK_A, new TeacherRosterRequest("ППС 2026", "Группа 1"));

        assertThatThrownBy(() -> service.create(kamA, WORK_A, new TeacherRosterRequest("ппс 2026", "группа 1")))
                .isInstanceOf(InteractionValidationException.class).hasMessageContaining("уже есть");
        assertThatThrownBy(() -> service.create(kamA, WORK_A, new TeacherRosterRequest(" ", null)))
                .isInstanceOf(InteractionValidationException.class).hasMessage("Укажите курс LMS");
        assertThatThrownBy(() -> service.addMember(kamA, roster.id(), INACTIVE))
                .isInstanceOf(InteractionValidationException.class).hasMessageContaining("неактуальный");
        assertThatThrownBy(() -> service.addMember(kamA, roster.id(), RESTRICTED))
                .isInstanceOf(InteractionValidationException.class).hasMessageContaining("ограничена");
        assertThatThrownBy(() -> service.addMember(kamA, roster.id(), FOREIGN))
                .isInstanceOf(InteractionValidationException.class).hasMessageContaining("не относится к вузу");
        assertThatThrownBy(() -> service.export(kamA, roster.id(), new TeacherRosterFileRequest(FileMode.ALL), "r0"))
                .isInstanceOf(InteractionValidationException.class).hasMessage("В списке нет преподавателей");

        service.addMember(kamA, roster.id(), LECTOROVA);
        TeacherRoster twice = service.addMember(kamA, roster.id(), LECTOROVA);
        assertThat(twice.members()).hasSize(1);
        UUID duplicate = jdbcContact("Лекторова-Вторая Мария", "LECTOROVA@example.test");
        TeacherRoster withDuplicate = service.addMember(kamA, roster.id(), duplicate);
        assertThat(withDuplicate.readyCount()).isZero();
        assertThat(member(withDuplicate, duplicate).problems()).containsExactly("Эта почта указана у другого преподавателя списка");
        assertThat(service.removeMember(kamA, roster.id(), duplicate).members()).hasSize(1);

        TeacherRosterFile file = service.export(kamA, roster.id(), new TeacherRosterFileRequest(FileMode.PENDING), "r1");
        service.markTransferred(kamA, file.exportId(), "r2");
        assertThatThrownBy(() -> service.removeMember(kamA, roster.id(), LECTOROVA))
                .isInstanceOf(InteractionValidationException.class).hasMessageContaining("уже передан");
        assertThatThrownBy(() -> service.delete(kamA, roster.id()))
                .isInstanceOf(InteractionValidationException.class).hasMessageContaining("уже выгружался");

        TeacherRoster empty = service.create(kamA, WORK_A, new TeacherRosterRequest("ППС 2027", null));
        service.addMember(kamA, empty.id(), SEMINAROV);
        service.delete(kamA, empty.id());
        assertThat(service.list(kamA, WORK_A)).extracting(TeacherRoster::id).containsExactly(roster.id());
    }

    @Test
    void nameSplitsIntoTemplateColumns() {
        assertThat(TeacherRosterService.split("  Иванова   Анна  Мария Петровна "))
                .isEqualTo(new TeacherRosterService.NameParts("Иванова", "Анна", "Мария Петровна"));
        assertThat(TeacherRosterService.split("Иванова"))
                .isEqualTo(new TeacherRosterService.NameParts("Иванова", null, null));
    }

    private static TeacherRosterMember member(TeacherRoster roster, UUID contactId) {
        return roster.members().stream().filter(member -> member.contactId().equals(contactId)).findFirst().orElseThrow();
    }

    private static List<String> texts(Row row, int count) {
        List<String> values = new ArrayList<>();
        for (int column = 0; column < count; column++) {
            values.add(row.getCell(column) == null ? null : row.getCell(column).getStringCellValue());
        }
        return values;
    }

    private List<String> journal(String action) {
        return jdbc.queryForList("SELECT details FROM audit_events WHERE action = ? ORDER BY occurred_at", String.class, action);
    }

    private UUID jdbcContact(String name, String email) {
        UUID id = UUID.randomUUID();
        contact(id, ORGANIZATION_A, name, email, false, "ACTIVE");
        return id;
    }

    private void profile(UUID id, String name, String role, UUID teamId) {
        jdbc.update("""
                INSERT INTO crm_user_profiles (id, display_name, role, team_id, active, access_revision)
                VALUES (?, ?, ?, ?, TRUE, 0)
                """, id, name, role, teamId);
    }

    private void organization(UUID id, String name, UUID teamId, UUID ownerId) {
        jdbc.update("""
                INSERT INTO organizations (id, name, type, team_id, owner_manager_id, version, updated_at)
                VALUES (?, ?, 'UNIVERSITY', ?, ?, 0, CURRENT_TIMESTAMP)
                """, id, name, teamId, ownerId);
    }

    private void contact(UUID id, UUID organizationId, String name, String email, boolean inactive, String status) {
        jdbc.update("""
                INSERT INTO contacts (id, organization_id, name, position, email, decision_role, inactive, personal_data_status)
                VALUES (?, ?, ?, 'Доцент', ?, 'TEACHER', ?, ?)
                """, id, organizationId, name, email, inactive, status);
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS audit_events (
                    id UUID PRIMARY KEY, category VARCHAR(32) NOT NULL, action VARCHAR(64) NOT NULL, actor_profile_id UUID,
                    actor_display_name VARCHAR(200) NOT NULL, object_type VARCHAR(32), object_id UUID,
                    object_name VARCHAR(500), details VARCHAR(2000), request_id VARCHAR(64),
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS teams (id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS crm_user_profiles (
                    id UUID PRIMARY KEY,
                    display_name VARCHAR(200) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    team_id UUID,
                    active BOOLEAN NOT NULL,
                    access_revision INTEGER NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY,
                    name VARCHAR(300) NOT NULL,
                    type VARCHAR(16) NOT NULL,
                    team_id UUID NOT NULL,
                    owner_manager_id UUID,
                    version INTEGER NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL,
                    city VARCHAR(200),
                    website VARCHAR(300),
                    inn VARCHAR(12)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS organization_assignment_events (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    previous_owner_manager_id UUID,
                    owner_manager_id UUID,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS organization_deputies (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    deputy_profile_id UUID NOT NULL,
                    deputy_display_name VARCHAR(200) NOT NULL,
                    starts_on DATE NOT NULL,
                    ends_on DATE NOT NULL,
                    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    ended_at TIMESTAMP WITH TIME ZONE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS contacts (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    position VARCHAR(200),
                    email VARCHAR(320),
                    decision_role VARCHAR(32),
                    inactive BOOLEAN DEFAULT FALSE NOT NULL,
                    personal_data_status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL,
                    confirmed_at TIMESTAMP WITH TIME ZONE,
                    confirmed_by UUID
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interactions (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    title VARCHAR(200) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS teacher_rosters (
                    id UUID PRIMARY KEY,
                    interaction_id UUID NOT NULL REFERENCES interactions(id),
                    organization_id UUID NOT NULL,
                    lms_course VARCHAR(300) NOT NULL,
                    lms_group VARCHAR(300),
                    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS teacher_roster_exports (
                    id UUID PRIMARY KEY,
                    roster_id UUID NOT NULL REFERENCES teacher_rosters(id) ON DELETE CASCADE,
                    rows_count INTEGER NOT NULL CHECK (rows_count > 0),
                    exported_by UUID NOT NULL REFERENCES crm_user_profiles(id),
                    exported_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    transferred_by UUID REFERENCES crm_user_profiles(id),
                    transferred_at TIMESTAMP WITH TIME ZONE,
                    CHECK ((transferred_at IS NULL) = (transferred_by IS NULL))
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS teacher_roster_members (
                    roster_id UUID NOT NULL REFERENCES teacher_rosters(id),
                    organization_id UUID NOT NULL,
                    contact_id UUID NOT NULL REFERENCES contacts(id),
                    export_id UUID REFERENCES teacher_roster_exports(id),
                    transferred_at TIMESTAMP WITH TIME ZONE,
                    added_by UUID NOT NULL REFERENCES crm_user_profiles(id),
                    added_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (roster_id, contact_id),
                    CHECK (transferred_at IS NULL OR export_id IS NOT NULL)
                )
                """);
    }

    private static UUID uuid(int value) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
    }
}
