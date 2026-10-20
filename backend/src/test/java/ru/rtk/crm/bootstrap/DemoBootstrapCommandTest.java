package ru.rtk.crm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.rtk.crm.access.UserProfileRepository;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.AgreementApproval;
import ru.rtk.crm.agreement.AgreementModels;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.attachment.AttachmentService;
import ru.rtk.crm.catalog.Contact;
import ru.rtk.crm.catalog.ContactCreateRequest;
import ru.rtk.crm.catalog.ContactService;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.interaction.Interaction;
import ru.rtk.crm.interaction.InteractionCreateRequest;
import ru.rtk.crm.interaction.InteractionStage;
import ru.rtk.crm.interaction.InteractionTransitionRequest;
import ru.rtk.crm.interaction.InteractionIssueKind;
import ru.rtk.crm.interaction.InteractionIssueRequest;
import ru.rtk.crm.interaction.InteractionIssueResolution;
import ru.rtk.crm.interaction.InteractionIssueService;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.ProductAgreementService;
import ru.rtk.crm.interaction.ProductAgreementUpdateRequest;
import ru.rtk.crm.interaction.ProductTransferStatus;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:demo-bootstrap;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DemoBootstrapCommandTest {
    private static final String ISSUER = "http://rtk.localhost:8081/idp/realms/rtk-crm";
    private static final List<DemoBootstrapProperties.Team> TEAMS = List.of(
            new DemoBootstrapProperties.Team("team-a", "Команда А"),
            new DemoBootstrapProperties.Team("team-b", "Команда Б")
    );
    private static final List<DemoBootstrapProperties.Identity> DEMO_IDENTITIES = List.of(
            identity("kam-a", "КАМ А", UserRole.USER, "team-a"),
            identity("kam-b", "КАМ Б", UserRole.USER, "team-b"),
            identity("kam-c", "КАМ В", UserRole.USER, "team-a"),
            identity("kam-d", "КАМ Г", UserRole.USER, "team-a"),
            identity("leader", "Руководитель", UserRole.LEADER, "team-a"),
            identity("leader-b", "Руководитель Б", UserRole.LEADER, "team-b"),
            identity("admin", "Администратор", UserRole.ADMIN, "team-a")
    );
    private static final List<DemoBootstrapProperties.Organization> DEMO_ORGANIZATIONS = List.of(
            new DemoBootstrapProperties.Organization("Университет А", "UNIVERSITY", "team-a", "kam-a"),
            new DemoBootstrapProperties.Organization("Университет Б", "UNIVERSITY", "team-b", "kam-b"),
            new DemoBootstrapProperties.Organization("Университет C — требует назначения", "UNIVERSITY", "team-a", null),
            new DemoBootstrapProperties.Organization("Школа № 1 (демо)", "SCHOOL", "team-a", "kam-d"),
            new DemoBootstrapProperties.Organization("Колледж связи (демо)", "COLLEGE", "team-b", "kam-b")
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcClient jdbcClient;

    private final ContactService contactService = mock(ContactService.class);
    private final InteractionService interactionService = mock(InteractionService.class);
    private final InteractionIssueService interactionIssueService = mock(InteractionIssueService.class);
    private final AttachmentService attachmentService = mock(AttachmentService.class);
    private final ProductAgreementService productAgreementService = mock(ProductAgreementService.class);
    private final ru.rtk.crm.agreement.AgreementFileService agreementFileService = mock(ru.rtk.crm.agreement.AgreementFileService.class);
    private final Map<UUID, Interaction> interactions = new HashMap<>();

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of("interaction_cycles", "interaction_events", "product_agreements", "agreement_status_events", "agreement_interactions",
                "agreement_programs", "agreement_files", "agreement_supplements", "agreements", "attachments", "contacts",
                "interaction_issues", "interactions", "products", "vendors", "programs", "directions",
                "organizations", "crm_user_profiles", "teams")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        when(contactService.create(any(), any(), any(ContactCreateRequest.class), anyString())).thenAnswer(invocation -> {
            ContactCreateRequest request = invocation.getArgument(2);
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO contacts (id, organization_id, name) VALUES (?, ?, ?)",
                    id, invocation.getArgument(1), request.name());
            return new Contact(id, invocation.getArgument(1), request.name(), null, null, null, 0, null,
                    OffsetDateTime.now(), OffsetDateTime.now(), null, false, false, null, null, null, PersonalDataStatus.ACTIVE);
        });
        when(interactionService.create(any(), any(InteractionCreateRequest.class), anyString())).thenAnswer(invocation -> {
            InteractionCreateRequest request = invocation.getArgument(1);
            UUID id = UUID.randomUUID();
            List<InteractionStage> stages = new ArrayList<>();
            for (int order = 0; order < 13; order++) {
                stages.add(new InteractionStage(UUID.randomUUID(), order == 5 ? "Подписание" : "Этап " + order, order, false));
            }
            jdbcTemplate.update("""
                    INSERT INTO interactions (id, organization_id, title, next_action, current_stage_id) VALUES (?, ?, ?, ?, ?)
                    """, id, request.organizationId(), request.title(), request.nextAction(), stages.getFirst().id());
            jdbcTemplate.update("""
                    INSERT INTO interaction_events (id, interaction_id, type, version, occurred_at)
                    VALUES (?, ?, 'CREATED', 0, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID(), id);
            for (UUID productId : request.productIds()) {
                jdbcTemplate.update("INSERT INTO product_agreements (id, interaction_id, product_id) VALUES (?, ?, ?)",
                        UUID.randomUUID(), id, productId);
            }
            Interaction created = interaction(id, request.organizationId(), request.title(), stages, 0);
            interactions.put(id, created);
            return created;
        });
        when(interactionService.transition(any(), any(), any(InteractionTransitionRequest.class), anyString())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(1);
            InteractionTransitionRequest request = invocation.getArgument(2);
            Interaction before = interactions.get(id);
            assertThat(request.version()).isEqualTo(before.version());
            jdbcTemplate.update("""
                    INSERT INTO interaction_events (id, interaction_id, type, version, occurred_at)
                    VALUES (?, ?, 'TRANSITIONED', ?, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID(), id, before.version() + 1);
            jdbcTemplate.update("UPDATE interactions SET version = version + 1, current_stage_id = ? WHERE id = ?",
                    request.toStageId(), id);
            InteractionStage target = before.stages().stream().filter(stage -> stage.id().equals(request.toStageId())).findFirst().orElseThrow();
            Interaction moved = new Interaction(id, before.organizationId(), before.title(), target.id(), target.name(), before.stages(),
                    List.of(), List.of(), null, null, List.of(), null, null, List.of(), List.of(), List.of(), List.of(), null,
                    before.version() + 1, UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now(), null, false, null, null);
            interactions.put(id, moved);
            return moved;
        });
        when(interactionService.get(any(), any())).thenAnswer(invocation -> interactions.get(invocation.<UUID>getArgument(1)));
        when(interactionIssueService.create(any(), any(), any(InteractionIssueRequest.class), anyString())).thenAnswer(invocation -> {
            InteractionIssueRequest request = invocation.getArgument(2);
            UUID interactionId = invocation.getArgument(1);
            assertThat(request.version()).isEqualTo(jdbcTemplate.queryForObject(
                    "SELECT version FROM interactions WHERE id = ?", Integer.class, interactionId));
            jdbcTemplate.update("""
                    INSERT INTO interaction_issues (id, interaction_id, kind, description, risk_level, due_on,
                        responsible_profile_id, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID(), interactionId, request.kind().name(), request.description(),
                    request.riskLevel() == null ? null : request.riskLevel().name(), request.dueOn(),
                    UUID.randomUUID(), UUID.randomUUID());
            jdbcTemplate.update("UPDATE interactions SET version = version + 1 WHERE id = ?", interactionId);
            // Как настоящая команда: событие истории со снимком этапа, на котором стоит работа в момент записи.
            jdbcTemplate.update("""
                    INSERT INTO interaction_events (id, interaction_id, type, version, occurred_at, stage_name_snapshot)
                    VALUES (?, ?, 'ISSUE_CREATED', 0, CURRENT_TIMESTAMP, ?)
                    """, UUID.randomUUID(), interactionId, interactions.get(interactionId).currentStageName());
            return null;
        });
        when(interactionIssueService.resolve(any(), any(), any(), any(InteractionIssueResolution.class), anyString())).thenAnswer(invocation -> {
            InteractionIssueResolution request = invocation.getArgument(3);
            jdbcTemplate.update("UPDATE interaction_issues SET status = 'RESOLVED', resolution = ? WHERE id = ?",
                    request.resolution(), invocation.getArgument(2));
            jdbcTemplate.update("UPDATE interactions SET version = version + 1 WHERE id = ?", (UUID) invocation.getArgument(1));
            return null;
        });
        when(productAgreementService.update(any(), any(), any(), any(ProductAgreementUpdateRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    ProductAgreementUpdateRequest request = invocation.getArgument(3);
                    jdbcTemplate.update("""
                            UPDATE product_agreements SET contract_number = ?, license_expiry_year = ?, transferred_kinds = ?
                            WHERE id = ?
                            """, request.contract().contractNumber(), request.contract().licenseExpiryYear(),
                            request.transfers().stream().filter(transfer -> transfer.status() == ProductTransferStatus.TRANSFERRED)
                                    .count(), invocation.getArgument(2));
                    jdbcTemplate.update("UPDATE interactions SET version = version + 1 WHERE id = ?", invocation.<UUID>getArgument(1));
                    return null;
                });
        when(attachmentService.upload(any(), any(), any(), any(), anyString())).thenAnswer(invocation -> {
            MultipartFile file = invocation.getArgument(3);
            assertThat(new String(file.getBytes(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
            jdbcTemplate.update("INSERT INTO attachments (id, interaction_id, original_name) VALUES (?, ?, ?)",
                    UUID.randomUUID(), invocation.getArgument(1), file.getOriginalFilename());
            return null;
        });
    }

    @Test
    void demoDataHasNamedTeamsThreeManagersInTeamASecondLeaderSchoolAndCollegeAndRepeatsWithoutDuplicates() {
        DemoBootstrapCommand command = command(demo());

        command.run(null);
        command.run(null);

        assertThat(jdbcTemplate.queryForList("SELECT name FROM teams ORDER BY name", String.class))
                .containsExactly("Команда А", "Команда Б");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM crm_user_profiles p JOIN teams t ON t.id = p.team_id
                WHERE t.name = 'Команда А' AND p.role = 'USER' AND p.active
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT t.name FROM crm_user_profiles p JOIN teams t ON t.id = p.team_id
                WHERE p.display_name = 'Руководитель Б' AND p.role = 'LEADER' AND p.active
                """, String.class)).isEqualTo("Команда Б");
        assertThat(jdbcTemplate.queryForList("""
                SELECT o.name, o.type, t.name AS team FROM organizations o JOIN teams t ON t.id = o.team_id
                WHERE o.type <> 'UNIVERSITY' ORDER BY o.name
                """)).extracting(row -> tuple(row.get("NAME"), row.get("TYPE"), row.get("TEAM")))
                .containsExactly(
                        tuple("Колледж связи (демо)", "COLLEGE", "Команда Б"),
                        tuple("Школа № 1 (демо)", "SCHOOL", "Команда А"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_user_profiles", Integer.class)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations", Integer.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agreements", Integer.class)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForList("""
                SELECT o.name, COUNT(*) AS total FROM agreements a JOIN organizations o ON o.id = a.organization_id
                WHERE a.planned_on IS NOT NULL AND a.created_by = o.owner_manager_id GROUP BY o.name ORDER BY o.name
                """)).extracting(row -> tuple(row.get("NAME"), row.get("TOTAL")))
                .containsExactly(
                        tuple("Колледж связи (демо)", 1L),
                        tuple("Университет А", 2L),
                        tuple("Университет Б", 2L),
                        tuple("Школа № 1 (демо)", 1L));
        verify(interactionService, times(1)).create(any(), any(InteractionCreateRequest.class), anyString());
    }

    @Test
    void demoWorkGetsARiskAContractAndLicensesWithTransferMarksAndRepeatsWithoutDuplicates() {
        DemoBootstrapCommand command = command(demo());

        command.run(null);
        command.run(null);

        verify(interactionIssueService, times(3)).create(any(), any(), any(InteractionIssueRequest.class), anyString());
        verify(interactionIssueService, times(1)).resolve(any(), any(), any(), any(InteractionIssueResolution.class), anyString());
        verify(productAgreementService, times(2)).update(any(), any(), any(), any(ProductAgreementUpdateRequest.class), anyString());
        assertThat(jdbcTemplate.queryForList("SELECT kind, risk_level, status FROM interaction_issues ORDER BY kind, status"))
                .extracting(row -> tuple(row.get("KIND"), row.get("RISK_LEVEL"), row.get("STATUS")))
                .containsExactly(
                        tuple(InteractionIssueKind.PROBLEM.name(), null, "OPEN"),
                        tuple(InteractionIssueKind.PROBLEM.name(), null, "RESOLVED"),
                        tuple(InteractionIssueKind.RISK.name(), "MEDIUM", "OPEN")
                );
        assertThat(jdbcTemplate.queryForList("""
                SELECT contract_number, license_expiry_year, transferred_kinds FROM product_agreements ORDER BY contract_number
                """)).extracting(row -> tuple(row.get("CONTRACT_NUMBER"), row.get("LICENSE_EXPIRY_YEAR"), row.get("TRANSFERRED_KINDS")))
                .containsExactly(tuple("ДЕМО-А/ЛС-1", 2026, 2), tuple("ДЕМО-А/ЛС-2", 2028, 1));
    }

    @Test
    void mainDemoWorkStandsOnTheTransferStageAfterSigningWithTheActiveAgreementAndMovesOnlyOnce() {
        DemoBootstrapCommand command = command(demo());

        command.run(null);
        command.run(null);

        verify(interactionService, times(6)).transition(any(), any(), any(InteractionTransitionRequest.class), anyString());
        Interaction main = interactions.values().iterator().next();
        assertThat(main.currentStageName()).isEqualTo("Этап 6");
        assertThat(jdbcTemplate.queryForList("""
                SELECT ag.number FROM agreements ag JOIN agreement_interactions ai ON ai.agreement_id = ag.id
                WHERE ai.interaction_id = ?
                """, String.class, main.id())).containsExactly("ДЕМО-А/2");
        List<OffsetDateTime> moves = jdbcTemplate.queryForList("""
                SELECT occurred_at FROM interaction_events WHERE interaction_id = ? AND type = 'TRANSITIONED' ORDER BY version
                """, OffsetDateTime.class, main.id());
        assertThat(moves).hasSize(6);
        assertThat(ChronoUnit.DAYS.between(moves.getLast(), OffsetDateTime.now())).isBetween(30L, 31L);
        OffsetDateTime created = jdbcTemplate.queryForObject("SELECT created_at FROM interactions WHERE id = ?",
                OffsetDateTime.class, main.id());
        assertThat(created).isBefore(moves.getFirst());
    }

    @Test
    void historyOfTheMainDemoWorkNamesTheStageWhereItsRisksAndProblemsWereRecorded() {
        command(demo()).run(null);

        Interaction main = interactions.values().iterator().next();
        assertThat(jdbcTemplate.queryForList("""
                SELECT stage_name_snapshot FROM interaction_events WHERE interaction_id = ? AND type = 'ISSUE_CREATED'
                """, String.class, main.id())).hasSize(3).containsOnly("Этап 6");
        assertThat(jdbcTemplate.queryForList("""
                SELECT stage_id FROM interaction_events WHERE interaction_id = ? AND type = 'ISSUE_CREATED'
                """, UUID.class, main.id())).containsOnly(main.currentStageId());
    }

    @Test
    void mainDemoWorkStaysInPlaceWhenTheScenarioAgreementHasExpiredOnAnOldStand() {
        DemoBootstrapCommand command = command(demo());
        command.run(null);
        Interaction main = interactions.values().iterator().next();
        jdbcTemplate.update("DELETE FROM interaction_events WHERE interaction_id = ? AND type = 'TRANSITIONED'", main.id());
        jdbcTemplate.update("UPDATE agreements SET valid_until = ? WHERE number = 'ДЕМО-А/2'", LocalDate.now().minusDays(4));

        command.run(null);

        verify(interactionService, times(6)).transition(any(), any(), any(InteractionTransitionRequest.class), anyString());
    }

    @Test
    void demoAgreementsTakeStatusDatesFromTheirTermsAndAnOldStandIsRedatedWithoutDuplicates() {
        DemoBootstrapCommand command = command(demo());
        command.run(null);
        UUID signed = agreementId("ДЕМО-А/3");
        UUID draft = agreementId("ДЕМО-А/1");
        UUID edited = agreementId("ДЕМО-Б/2");
        // Давний стенд: единственное событие «создан» и смена статуса — в день заполнения.
        for (UUID id : List.of(signed, edited)) {
            jdbcTemplate.update("DELETE FROM agreement_status_events WHERE agreement_id = ? AND from_status IS NOT NULL", id);
            jdbcTemplate.update("UPDATE agreement_status_events SET to_status = 'ACTIVE', occurred_at = CURRENT_TIMESTAMP"
                    + " WHERE agreement_id = ?", id);
            jdbcTemplate.update("UPDATE agreements SET status_changed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        }
        jdbcTemplate.update("UPDATE agreements SET version = 1 WHERE id = ?", edited);

        command.run(null);
        command.run(null);

        LocalDate concludedOn = jdbcTemplate.queryForObject("SELECT concluded_on FROM agreements WHERE id = ?", LocalDate.class, signed);
        assertThat(concludedOn).isBefore(LocalDate.now().minusYears(1));
        assertThat(statusChangedOn(signed)).isEqualTo(concludedOn);
        assertThat(jdbcTemplate.queryForList("""
                SELECT from_status, to_status, occurred_at FROM agreement_status_events WHERE agreement_id = ? ORDER BY occurred_at
                """, signed)).extracting(row -> row.get("FROM_STATUS"), row -> row.get("TO_STATUS"))
                .containsExactly(tuple(null, "DRAFT"), tuple("DRAFT", "REVIEW"), tuple("REVIEW", "SIGNING"), tuple("SIGNING", "ACTIVE"));
        assertThat(jdbcTemplate.queryForObject("SELECT MAX(occurred_at) FROM agreement_status_events WHERE agreement_id = ?",
                OffsetDateTime.class, signed).atZoneSameInstant(ZoneId.of("Europe/Moscow")).toLocalDate()).isEqualTo(concludedOn);
        assertThat(jdbcTemplate.queryForObject("SELECT sent_on FROM agreements WHERE id = ?", LocalDate.class, signed))
                .isEqualTo(concludedOn.minusDays(26));
        LocalDate plannedOn = jdbcTemplate.queryForObject("SELECT planned_on FROM agreements WHERE id = ?", LocalDate.class, draft);
        assertThat(statusChangedOn(draft)).isEqualTo(plannedOn.minusDays(30));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agreement_status_events WHERE agreement_id = ?", Integer.class, draft))
                .isEqualTo(1);
        assertThat(statusChangedOn(edited)).isEqualTo(LocalDate.now(ZoneId.of("Europe/Moscow")));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agreement_status_events WHERE agreement_id = ?", Integer.class, edited))
                .isEqualTo(1);
    }

    @Test
    void agreementUnderReviewForFortyEightDaysShowsTheFirstRoundAtTheUniversityFromItsHistory() {
        List<DemoBootstrapProperties.Identity> identities = new ArrayList<>(DEMO_IDENTITIES);
        identities.add(identity("kam-g", "КАМ Ж", UserRole.USER, "team-a"));
        List<DemoBootstrapProperties.Organization> organizations = new ArrayList<>(DEMO_ORGANIZATIONS);
        organizations.add(new DemoBootstrapProperties.Organization("Колледж автоматики (демо)", "COLLEGE", "team-a", "kam-g"));
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(true, false, TEAMS, identities, organizations, null));

        command.run(null);
        command.run(null);

        UUID review = agreementId("ДЕМО-КА/1");
        ZoneId zone = ZoneId.of("Europe/Moscow");
        LocalDate today = LocalDate.now(zone);
        List<AgreementModels.StatusEntry> history = jdbcTemplate.query("""
                SELECT to_status, occurred_at FROM agreement_status_events WHERE agreement_id = ? ORDER BY occurred_at
                """, (resultSet, rowNumber) -> new AgreementModels.StatusEntry(
                AgreementModels.AgreementStatus.valueOf(resultSet.getString("to_status")),
                resultSet.getObject("occurred_at", OffsetDateTime.class)), review);
        AgreementApproval rules = new AgreementApproval(42, 21, 7, 60, 90, List.of(7, 8));
        var approval = rules.state(AgreementModels.AgreementStatus.REVIEW,
                jdbcTemplate.queryForObject("SELECT status_changed_at FROM agreements WHERE id = ?", OffsetDateTime.class, review),
                jdbcTemplate.queryForObject("SELECT sent_on FROM agreements WHERE id = ?", LocalDate.class, review), history, today);

        assertThat(history).extracting(AgreementModels.StatusEntry::toStatus).containsExactly(
                AgreementModels.AgreementStatus.DRAFT, AgreementModels.AgreementStatus.REVIEW);
        assertThat(approval.round()).isEqualTo(1);
        assertThat(approval.side()).isEqualTo(AgreementModels.ApprovalSide.UNIVERSITY);
        long counted = rules.countedDays(today.minusDays(48), today);
        assertThat(approval.sideDays()).isEqualTo(counted).isPositive();
        assertThat(approval.color()).isEqualTo(counted > 42
                ? AgreementModels.ApprovalColor.YELLOW : AgreementModels.ApprovalColor.NORMAL);
    }

    private UUID agreementId(String number) {
        return jdbcTemplate.queryForObject("SELECT id FROM agreements WHERE number = ?", UUID.class, number);
    }

    private LocalDate statusChangedOn(UUID agreementId) {
        return jdbcTemplate.queryForObject("SELECT status_changed_at FROM agreements WHERE id = ?", OffsetDateTime.class, agreementId)
                .atZoneSameInstant(ZoneId.of("Europe/Moscow")).toLocalDate();
    }

    @Test
    void partnerRepresentsUniversityAWithSharedDemoDocumentsAndVisibleStepAndRepeatsWithoutDuplicates() {
        List<DemoBootstrapProperties.Identity> identities = new ArrayList<>(DEMO_IDENTITIES);
        identities.add(new DemoBootstrapProperties.Identity("partner", ISSUER, "subject-partner", "Представитель вуза",
                UserRole.PARTNER, null, null, "Университет А", "Александра Демонстрационная"));
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(true, false, TEAMS, identities, DEMO_ORGANIZATIONS, null));

        command.run(null);
        command.run(null);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT p.role, p.team_id, p.active, o.name AS organization, c.name AS contact
                FROM crm_user_profiles p
                JOIN organizations o ON o.id = p.partner_organization_id
                JOIN contacts c ON c.id = p.partner_contact_id
                WHERE p.subject = 'subject-partner'
                """))
                .containsEntry("ROLE", "PARTNER")
                .containsEntry("TEAM_ID", null)
                .containsEntry("ACTIVE", true)
                .containsEntry("ORGANIZATION", "Университет А")
                .containsEntry("CONTACT", "Александра Демонстрационная");
        verify(attachmentService, times(2)).upload(any(), any(), any(), any(), anyString());
        assertThat(jdbcTemplate.queryForList("SELECT original_name FROM attachments WHERE partner_visible ORDER BY original_name",
                String.class)).containsExactly("Демо: план сотрудничества.pdf", "Демо: рабочая программа.pdf");
        assertThat(jdbcTemplate.queryForObject("SELECT next_step_partner_visible FROM interactions", Boolean.class)).isTrue();

        identities.set(identities.size() - 1, new DemoBootstrapProperties.Identity("partner", ISSUER, "subject-partner",
                "Представитель вуза", UserRole.PARTNER, "team-a", null, "Университет А", "Александра Демонстрационная"));
        assertThatThrownBy(() -> command(new DemoBootstrapProperties(true, false, TEAMS, identities, DEMO_ORGANIZATIONS, null))
                .run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enrolmentOperatorJoinsTheOpenEnrolmentTeamOfTheMigrationAndCoursesOfTheSiteFixtureHavePrograms() {
        UUID openEnrolment = UUID.fromString("0e7e0000-0000-4000-8000-000000000001");
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, 'Открытый набор')", openEnrolment);
        List<DemoBootstrapProperties.Team> teams = new ArrayList<>(TEAMS);
        teams.add(new DemoBootstrapProperties.Team("open-enrolment", "Открытый набор"));
        List<DemoBootstrapProperties.Identity> identities = new ArrayList<>(DEMO_IDENTITIES);
        identities.add(new DemoBootstrapProperties.Identity("enrol", ISSUER, "subject-enrol", "Менеджер учебного процесса", UserRole.USER,
                "open-enrolment", true, null, null));
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(true, false, teams, identities, DEMO_ORGANIZATIONS, null));

        command.run(null);
        command.run(null);

        assertThat(jdbcTemplate.queryForList("SELECT display_name FROM crm_user_profiles WHERE enrolment_operator", String.class))
                .containsExactly("Менеджер учебного процесса");
        assertThat(jdbcTemplate.queryForObject("SELECT team_id FROM crm_user_profiles WHERE display_name = 'Менеджер учебного процесса'",
                UUID.class)).isEqualTo(openEnrolment);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM teams", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("""
                SELECT p.name FROM programs p JOIN directions d ON d.id = p.direction_id
                WHERE d.name = 'Демо-направление: курсы для физлиц' ORDER BY p.name
                """, String.class)).containsExactly("Инженер-тестировщик", "Промпт-инжиниринг");
    }

    @Test
    void bringsAPendingProfileFromAnEarlierLoginToTheConfiguredRoleTeamAndActivityAndRepeatsWithoutChanges() {
        DemoBootstrapProperties.Identity management = identity("management", "Руководство", UserRole.MANAGEMENT, null);
        new UserProfileRepository(jdbcClient).insertPendingIfAbsent(UUID.randomUUID(), ISSUER, management.subject(), "management", "management");
        List<DemoBootstrapProperties.Identity> identities = new ArrayList<>(DEMO_IDENTITIES);
        identities.add(management);
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(true, false, TEAMS, identities, DEMO_ORGANIZATIONS, null));

        command.run(null);
        command.run(null);

        assertThat(jdbcTemplate.queryForList("""
                SELECT role, team_id, active, pending_activation FROM crm_user_profiles WHERE display_name = 'Руководство'
                """)).extracting(row -> tuple(row.get("ROLE"), row.get("TEAM_ID"), row.get("ACTIVE"), row.get("PENDING_ACTIVATION")))
                .containsExactly(tuple("MANAGEMENT", null, true, false));
    }

    @Test
    void bringsAnAlreadyActiveProfileWithAnotherRoleToTheConfiguredRoleTeamAndActivityAndRepeatsWithoutChanges() {
        DemoBootstrapProperties.Identity management = identity("management", "Руководство", UserRole.MANAGEMENT, null);
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, active, pending_activation)
                VALUES (?, ?, ?, 'Руководство', 'USER', NULL, TRUE, FALSE)
                """, UUID.randomUUID(), ISSUER, management.subject());
        List<DemoBootstrapProperties.Identity> identities = new ArrayList<>(DEMO_IDENTITIES);
        identities.add(management);
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(true, false, TEAMS, identities, DEMO_ORGANIZATIONS, null));

        command.run(null);
        command.run(null);

        assertThat(jdbcTemplate.queryForList("""
                SELECT role, active, pending_activation, team_id FROM crm_user_profiles WHERE display_name = 'Руководство'
                """)).extracting(row -> tuple(row.get("ROLE"), row.get("ACTIVE"), row.get("PENDING_ACTIVATION"), row.get("TEAM_ID")))
                .containsExactly(tuple("MANAGEMENT", true, false, null));
    }

    @Test
    void renamesOnlyTeamsStillNamedByTheirKeyAndKeepsAdministratorChanges() {
        insertTeam("team-a", "team-a");
        insertTeam("team-b", "Продажи Сибирь");

        command(demo()).run(null);

        assertThat(jdbcTemplate.queryForList("SELECT name FROM teams ORDER BY name", String.class))
                .containsExactly("Команда А", "Продажи Сибирь");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM teams WHERE name = 'Команда А'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM teams WHERE name = 'Продажи Сибирь'", Integer.class)).isZero();
    }

    @Test
    void installationWithoutDemoDataCreatesOnlyOneAdministratorAndActivatesAnEarlyLogin() {
        DemoBootstrapProperties.Identity admin = identity("crm-admin", "Администратор CRM", UserRole.ADMIN, null);
        new UserProfileRepository(jdbcClient).insertPendingIfAbsent(UUID.randomUUID(), ISSUER, admin.subject(), "crm-admin", "crm-admin");
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(false, false, List.of(), List.of(admin), null, null));

        command.run(null);
        command.run(null);

        assertThat(jdbcTemplate.queryForList("SELECT display_name, role, team_id, active, pending_activation FROM crm_user_profiles"))
                .extracting(row -> tuple(row.get("DISPLAY_NAME"), row.get("ROLE"), row.get("TEAM_ID"), row.get("ACTIVE"),
                        row.get("PENDING_ACTIVATION")))
                .containsExactly(tuple("Администратор CRM", "ADMIN", null, true, false));
        for (String table : List.of("teams", "organizations", "directions", "programs", "vendors", "products", "interactions")) {
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
        }
        verifyNoInteractions(contactService, interactionService);
    }

    @Test
    void installationWithoutDemoDataRejectsDemoIdentitiesAndWritesNothing() {
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(false, false, TEAMS, DEMO_IDENTITIES, null, null));

        assertThatThrownBy(() -> command.run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_user_profiles", Integer.class)).isZero();
        verify(interactionService, never()).create(any(), any(InteractionCreateRequest.class), anyString());
    }

    @Test
    void controlScenarioAddsWorksOnDifferentStagesWithBackdatedMovesAndRepeatsWithoutDuplicates() {
        List<DemoBootstrapProperties.Identity> identities = new ArrayList<>(DEMO_IDENTITIES);
        identities.add(identity("leader-c", "Руководитель В", UserRole.LEADER, "team-c"));
        List<DemoBootstrapProperties.Team> teams = new ArrayList<>(TEAMS);
        teams.add(new DemoBootstrapProperties.Team("team-c", "Команда В"));
        List<DemoBootstrapProperties.Organization> organizations = new ArrayList<>(DEMO_ORGANIZATIONS);
        organizations.add(new DemoBootstrapProperties.Organization("Политехнический университет (демо)", "UNIVERSITY", "team-a", "kam-a"));
        organizations.add(new DemoBootstrapProperties.Organization("Педагогический университет (демо)", "UNIVERSITY", "team-a", "kam-c"));
        organizations.add(new DemoBootstrapProperties.Organization("Институт без ответственного (демо)", "UNIVERSITY", "team-c", null));
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(true, false, teams, identities, organizations, null));

        command.run(null);
        command.run(null);

        verify(interactionService, times(8)).create(any(), any(InteractionCreateRequest.class), anyString());
        verify(interactionService, times(39)).transition(any(), any(), any(InteractionTransitionRequest.class), anyString());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interactions", Integer.class)).isEqualTo(8);
        assertThat(jdbcTemplate.queryForList("""
                SELECT o.name, COUNT(*) AS total FROM interactions i JOIN organizations o ON o.id = i.organization_id
                GROUP BY o.name ORDER BY o.name
                """)).extracting(row -> row.get("NAME"), row -> row.get("TOTAL"))
                .contains(tuple("Институт без ответственного (демо)", 1L), tuple("Педагогический университет (демо)", 1L),
                        tuple("Политехнический университет (демо)", 1L));
        verify(interactionIssueService, times(5)).create(any(), any(), any(InteractionIssueRequest.class), anyString());

        UUID interactionId = jdbcTemplate.queryForObject("""
                SELECT i.id FROM interactions i JOIN organizations o ON o.id = i.organization_id
                WHERE o.name = 'Политехнический университет (демо)'
                """, UUID.class);
        List<OffsetDateTime> moves = jdbcTemplate.queryForList("""
                SELECT occurred_at FROM interaction_events WHERE interaction_id = ? AND type = 'TRANSITIONED' ORDER BY version
                """, OffsetDateTime.class, interactionId);
        assertThat(moves).hasSize(7);
        assertThat(jdbcTemplate.queryForList("""
                SELECT ag.status, ag.concluded_on IS NOT NULL AS signed FROM agreements ag
                JOIN agreement_interactions ai ON ai.agreement_id = ag.id WHERE ai.interaction_id = ?
                """, interactionId)).singleElement().satisfies(row -> {
                    assertThat(row.get("STATUS")).isEqualTo("ACTIVE");
                    assertThat(row.get("SIGNED")).isEqualTo(true);
                });
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agreement_status_events e JOIN agreement_interactions ai ON ai.agreement_id = e.agreement_id
                WHERE ai.interaction_id = ?
                """, Integer.class, interactionId)).isEqualTo(4);
        assertThat(ChronoUnit.DAYS.between(moves.getLast(), OffsetDateTime.now())).isBetween(11L, 12L);
        assertThat(ChronoUnit.DAYS.between(moves.getFirst(), moves.getLast())).isEqualTo(36);
        OffsetDateTime created = jdbcTemplate.queryForObject("SELECT created_at FROM interactions WHERE id = ?",
                OffsetDateTime.class, interactionId);
        assertThat(ChronoUnit.DAYS.between(created, moves.getFirst())).isEqualTo(9);
    }

    @Test
    void classicUniversityGetsALinkedPairOfCompletedAndNextCycleOnce() {
        List<DemoBootstrapProperties.Identity> identities = new ArrayList<>(DEMO_IDENTITIES);
        identities.add(identity("leader-c", "Руководитель В", UserRole.LEADER, "team-c"));
        identities.add(identity("kam-h", "КАМ З", UserRole.USER, "team-a"));
        List<DemoBootstrapProperties.Team> teams = new ArrayList<>(TEAMS);
        teams.add(new DemoBootstrapProperties.Team("team-c", "Команда В"));
        List<DemoBootstrapProperties.Organization> organizations = new ArrayList<>(DEMO_ORGANIZATIONS);
        organizations.add(new DemoBootstrapProperties.Organization("Классический университет (демо)", "UNIVERSITY", "team-a", "kam-h"));
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(true, false, teams, identities, organizations, null));

        command.run(null);
        command.run(null);

        List<Map<String, Object>> cycles = jdbcTemplate.queryForList("""
                SELECT n.title AS next_title, p.title AS previous_title, c.starts_on, n.created_at
                FROM interaction_cycles c JOIN interactions n ON n.id = c.interaction_id
                JOIN interactions p ON p.id = c.previous_interaction_id
                """);
        assertThat(cycles).singleElement().satisfies(row -> {
            assertThat(row.get("NEXT_TITLE")).isEqualTo("Демо: повышение квалификации");
            assertThat(row.get("PREVIOUS_TITLE")).isEqualTo("Демо: закрытый цикл");
            assertThat(row.get("STARTS_ON")).isNotNull();
        });
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM interaction_cycles c JOIN interactions n ON n.id = c.interaction_id
                JOIN interactions p ON p.id = c.previous_interaction_id WHERE p.created_at < n.created_at
                """, Integer.class)).as("прошлый цикл начат раньше нового").isEqualTo(1);
    }

    private DemoBootstrapCommand command(DemoBootstrapProperties properties) {
        return new DemoBootstrapCommand(properties, jdbcClient, new UserProfileRepository(jdbcClient), contactService,
                interactionService, interactionIssueService, attachmentService, productAgreementService, agreementFileService);
    }

    private static Interaction interaction(UUID id, UUID organizationId, String title, List<InteractionStage> stages, int version) {
        return new Interaction(id, organizationId, title, stages.getFirst().id(), stages.getFirst().name(), stages, List.of(),
                List.of(), null, null, List.of(), null, null, List.of(), List.of(), List.of(), List.of(), null, version,
                UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now(), null, false, null, null);
    }

    private static DemoBootstrapProperties demo() {
        return new DemoBootstrapProperties(true, false, TEAMS, DEMO_IDENTITIES, DEMO_ORGANIZATIONS, null);
    }

    private static DemoBootstrapProperties.Identity identity(String key, String displayName, UserRole role, String teamKey) {
        return new DemoBootstrapProperties.Identity(key, ISSUER, "subject-" + key, displayName, role, teamKey, null, null, null);
    }

    private void insertTeam(String key, String name) {
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, ?)",
                UUID.nameUUIDFromBytes(("team:" + key).getBytes(StandardCharsets.UTF_8)), name);
    }

    private void createSchema() {
        ru.rtk.crm.interaction.StagePropertiesTestSchema.create(jdbcTemplate);
        for (String statement : List.of(
                """
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL UNIQUE, version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (role_synced_at TIMESTAMP WITH TIME ZONE, last_login_at TIMESTAMP WITH TIME ZONE, role_reconcile_required BOOLEAN DEFAULT FALSE NOT NULL, partner_organization_id UUID, partner_contact_id UUID, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY, issuer VARCHAR(512) NOT NULL, subject VARCHAR(512) NOT NULL,
                    display_name VARCHAR(200) NOT NULL, login VARCHAR(200), role VARCHAR(16) NOT NULL, team_id UUID,
                    idp_enabled BOOLEAN NOT NULL DEFAULT TRUE, activation_requested_at TIMESTAMP WITH TIME ZONE,
                    anonymized_at TIMESTAMP WITH TIME ZONE,
                    active BOOLEAN NOT NULL DEFAULT TRUE, pending_activation BOOLEAN NOT NULL DEFAULT FALSE,
                    access_revision INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (issuer, subject), CHECK (NOT (pending_activation AND active))
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY, name VARCHAR(300) NOT NULL UNIQUE, type VARCHAR(16) NOT NULL, team_id UUID NOT NULL,
                    owner_manager_id UUID, version INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS agreements (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, number VARCHAR(100) NOT NULL, concluded_on DATE,
                    valid_until DATE, planned_kind VARCHAR(16), planned_on DATE, planned_base_until DATE, parties VARCHAR(2000),
                    status VARCHAR(16) NOT NULL, version INTEGER NOT NULL DEFAULT 0, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    sent_on DATE, status_changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    university_signer_id UUID, school_signer_id UUID,
                    UNIQUE (organization_id, number)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS agreement_status_events (
                    id UUID PRIMARY KEY, agreement_id UUID NOT NULL, from_status VARCHAR(16), to_status VARCHAR(16) NOT NULL,
                    comment VARCHAR(2000), actor_profile_id UUID, interaction_id UUID, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS agreement_interactions (
                    agreement_id UUID NOT NULL, interaction_id UUID NOT NULL, PRIMARY KEY (agreement_id, interaction_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS agreement_programs (
                    agreement_id UUID NOT NULL, program_id UUID NOT NULL, PRIMARY KEY (agreement_id, program_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS agreement_supplements (
                    id UUID PRIMARY KEY, agreement_id UUID NOT NULL, number VARCHAR(100) NOT NULL, concluded_on DATE,
                    subject VARCHAR(2000), status VARCHAR(16) NOT NULL, version INTEGER NOT NULL DEFAULT 0, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS agreement_files (
                    id UUID PRIMARY KEY, agreement_id UUID NOT NULL, supplement_id UUID, version_number INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS directions (
                    id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL UNIQUE, archived BOOLEAN NOT NULL DEFAULT FALSE,
                    version INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS programs (
                    id UUID PRIMARY KEY, direction_id UUID NOT NULL, name VARCHAR(200) NOT NULL,
                    archived BOOLEAN NOT NULL DEFAULT FALSE, version INTEGER NOT NULL DEFAULT 0, UNIQUE (direction_id, name)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS vendors (
                    id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL UNIQUE, archived BOOLEAN NOT NULL DEFAULT FALSE,
                    version INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS products (
                    id UUID PRIMARY KEY, vendor_contact_id UUID, vendor_id UUID NOT NULL, name VARCHAR(200) NOT NULL,
                    archived BOOLEAN NOT NULL DEFAULT FALSE, version INTEGER NOT NULL DEFAULT 0, UNIQUE (vendor_id, name)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS vendor_contacts (
                    id UUID PRIMARY KEY, vendor_id UUID NOT NULL, name VARCHAR(200) NOT NULL, phone VARCHAR(16),
                    email VARCHAR(320), prefers_email BOOLEAN DEFAULT FALSE NOT NULL,
                    prefers_telegram BOOLEAN DEFAULT FALSE NOT NULL, archived BOOLEAN DEFAULT FALSE NOT NULL,
                    personal_data_status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, external_key VARCHAR(200) UNIQUE,
                    version INTEGER DEFAULT 0 NOT NULL, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_issues (last_comment VARCHAR(1000), last_comment_by UUID, last_comment_at TIMESTAMP WITH TIME ZONE,
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, kind VARCHAR(16) NOT NULL,
                    description VARCHAR(1000) NOT NULL, risk_level VARCHAR(16), responsible_profile_id UUID NOT NULL, due_on DATE,
                    status VARCHAR(16) DEFAULT 'OPEN' NOT NULL, resolution VARCHAR(1000), created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, resolved_by UUID, resolved_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interactions (interaction_kind_id UUID, workflow_template_id UUID, workflow_version INTEGER, next_step_partner_visible BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL, current_stage_id UUID,
                    version INTEGER NOT NULL DEFAULT 0,
                    next_action VARCHAR(500), created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_events (import_source VARCHAR(300), import_author VARCHAR(200), contact_channel VARCHAR(16),
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, type VARCHAR(32) NOT NULL,
                    version INTEGER NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    stage_id UUID, stage_name_snapshot VARCHAR(200)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_cycles (
                    interaction_id UUID PRIMARY KEY, previous_interaction_id UUID NOT NULL UNIQUE, starts_on DATE NOT NULL,
                    created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS product_agreements (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, product_id UUID NOT NULL, contract_number VARCHAR(200),
                    license_expiry_year INTEGER, transferred_kinds INTEGER,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS contacts (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, name VARCHAR(200) NOT NULL,
                    decision_role VARCHAR(32), inactive BOOLEAN DEFAULT FALSE NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS attachments (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, original_name VARCHAR(255) NOT NULL,
                    partner_visible BOOLEAN DEFAULT FALSE NOT NULL
                )
                """
        )) {
            jdbcTemplate.execute(statement);
        }
    }
}
