package ru.rtk.crm.teacherroster;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserProfileRepository;
import ru.rtk.crm.bootstrap.DemoBootstrapProperties;
import ru.rtk.crm.catalog.Contact;
import ru.rtk.crm.catalog.ContactCreateRequest;
import ru.rtk.crm.catalog.ContactRole;
import ru.rtk.crm.catalog.ContactService;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRoster;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterRequest;

@Component
@Profile("demo-bootstrap")
@Order(3)
public class DemoTeacherRosterBootstrap implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DemoTeacherRosterBootstrap.class);
    private static final String ORGANIZATION_NAME = "Университет А";
    private static final String INTERACTION_TITLE = "Демо: внедрение цифрового университета";
    private static final List<DemoTeacher> TEACHERS = List.of(
            new DemoTeacher("lectorova", "Лекторова Мария Демовна", "Доцент кафедры информатики", "lectorova.demo@example.test"),
            new DemoTeacher("seminarov", "Семинаров Павел Демович", "Старший преподаватель", "seminarov.demo@example.test"),
            new DemoTeacher("praktikova", "Практикова Ольга", "Преподаватель", "praktikova.demo@example.test"),
            new DemoTeacher("assistentov", "Ассистентов Кирилл Демович", "Ассистент кафедры", null)
    );

    private final DemoBootstrapProperties properties;
    private final JdbcClient jdbcClient;
    private final UserProfileRepository userProfileRepository;
    private final ContactService contactService;
    private final TeacherRosterService rosterService;

    public DemoTeacherRosterBootstrap(
            DemoBootstrapProperties properties,
            JdbcClient jdbcClient,
            UserProfileRepository userProfileRepository,
            ContactService contactService,
            TeacherRosterService rosterService
    ) {
        this.properties = properties;
        this.jdbcClient = jdbcClient;
        this.userProfileRepository = userProfileRepository;
        this.contactService = contactService;
        this.rosterService = rosterService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.demoData()) {
            return;
        }
        Optional<DemoWork> work = demoWork();
        if (work.isEmpty() || rosterExists(work.get().interactionId())) {
            return;
        }
        CrmProfile kam = properties.identities().stream()
                .filter(identity -> "kam-a".equals(identity.key()))
                .findFirst()
                .flatMap(identity -> userProfileRepository.findActiveByIdentity(identity.issuer(), identity.subject()))
                .orElseThrow(() -> new IllegalStateException("Demo teacher roster requires active kam-a profile"));
        TeacherRoster roster = rosterService.create(kam, work.get().interactionId(),
                new TeacherRosterRequest("Демо: обучение преподавателей", ORGANIZATION_NAME));
        for (DemoTeacher teacher : TEACHERS) {
            Contact contact = contactService.create(
                    kam,
                    work.get().organizationId(),
                    new ContactCreateRequest(teacher.name(), teacher.position(), teacher.email(), null, ContactRole.TEACHER, false),
                    "demo-bootstrap/v1/contact:teacher-" + teacher.key()
            );
            rosterService.addMember(kam, roster.id(), contact.id());
        }
        LOGGER.info("Demo teacher roster created");
    }

    private Optional<DemoWork> demoWork() {
        return jdbcClient.sql("""
                SELECT i.id, i.organization_id
                FROM interactions i
                JOIN organizations o ON o.id = i.organization_id
                WHERE o.name = :organization AND i.title = :title
                ORDER BY i.created_at, i.id
                LIMIT 1
                """)
                .param("organization", ORGANIZATION_NAME)
                .param("title", INTERACTION_TITLE)
                .query((resultSet, rowNumber) -> new DemoWork(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class)
                ))
                .optional();
    }

    private boolean rosterExists(UUID interactionId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM teacher_rosters WHERE interaction_id = :id")
                .param("id", interactionId)
                .query(Integer.class)
                .single() > 0;
    }

    private record DemoWork(UUID interactionId, UUID organizationId) {
    }

    private record DemoTeacher(String key, String name, String position, String email) {
    }
}
