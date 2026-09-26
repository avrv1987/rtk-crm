package ru.rtk.crm.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.rtk.crm.access.AdminCrmProfileAccessDeniedException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.AgreementModels.Activity;
import ru.rtk.crm.agreement.AgreementModels.ActivityKind;
import ru.rtk.crm.agreement.AgreementModels.ActivityKindCreateRequest;
import ru.rtk.crm.agreement.AgreementModels.ActivityKindUpdateRequest;
import ru.rtk.crm.agreement.AgreementModels.ActivityRequest;
import ru.rtk.crm.agreement.AgreementModels.ActivityStatus;
import ru.rtk.crm.agreement.AgreementModels.Agreement;
import ru.rtk.crm.agreement.AgreementModels.AgreementRequest;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.Confirmation;
import ru.rtk.crm.agreement.AgreementModels.ConfirmationQuery;
import ru.rtk.crm.attachment.AttachmentStorage;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.ReportColumn;
import ru.rtk.crm.report.ReportFilters;
import ru.rtk.crm.report.ReportKind;
import ru.rtk.crm.report.ReportPreview;
import ru.rtk.crm.report.ReportProperties;
import ru.rtk.crm.report.ReportRepository;
import ru.rtk.crm.report.ReportRequest;
import ru.rtk.crm.report.ReportService;
import ru.rtk.crm.report.StatisticsGroupBy;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:agreements;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "app.oidc.public-base-url=https://crm.test/"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        CommandIdempotencyRepository.class,
        AgreementRepository.class,
        AgreementService.class,
        ReportRepository.class,
        ReportService.class,
        AgreementServiceTest.TestBeans.class
})
class AgreementServiceTest {
    private static final UUID TEAM_A = uuid(1);
    private static final UUID TEAM_B = uuid(2);
    private static final UUID MANAGER_A = uuid(11);
    private static final UUID MANAGER_B = uuid(12);
    private static final UUID LEADER_A = uuid(13);
    private static final UUID LEADER_B = uuid(14);
    private static final UUID ADMIN = uuid(15);
    private static final UUID ORGANIZATION_A = uuid(101);
    private static final UUID ORGANIZATION_B = uuid(102);
    private static final UUID PROGRAM = uuid(201);
    private static final UUID INTERACTION_A = uuid(301);
    private static final UUID INTERACTION_B = uuid(302);
    private static final UUID STAGE_A = uuid(401);
    private static final UUID STAGE_B = uuid(402);
    private static final UUID ACT_CLEAN = uuid(501);
    private static final UUID PROGRAM_CLEAN = uuid(502);
    private static final UUID ACT_REJECTED = uuid(503);
    private static final UUID FOREIGN_CLEAN = uuid(504);
    private static final UUID KIND_PROGRAMS = UUID.fromString("7c2f0a10-0000-4000-8000-000000000010");
    private static final UUID KIND_TRAINING = UUID.fromString("7c2f0a10-0000-4000-8000-000000000040");
    private static final UUID KIND_OTHER = UUID.fromString("7c2f0a10-0000-4000-8000-000000000070");
    private static final LocalDate YEAR_START = LocalDate.parse("2026-01-01");
    private static final LocalDate YEAR_END = LocalDate.parse("2026-12-31");

    private final CrmProfile managerA = new CrmProfile(MANAGER_A, UserRole.USER, TEAM_A, 0);
    private final CrmProfile managerB = new CrmProfile(MANAGER_B, UserRole.USER, TEAM_B, 0);
    private final CrmProfile leaderA = new CrmProfile(LEADER_A, UserRole.LEADER, TEAM_A, 0);
    private final CrmProfile leaderB = new CrmProfile(LEADER_B, UserRole.LEADER, TEAM_B, 0);
    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);

    @Autowired
    private AgreementService agreementService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of(
                "agreement_activity_attachments", "agreement_activity_interactions", "agreement_activities", "agreements",
                "agreement_activity_kinds", "learning_snapshots", "source_mappings", "source_records", "attachments", "interaction_stages", "interactions",
                "command_idempotency_records", "organizations", "crm_user_profiles", "teams"
        )) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO teams (id, name) VALUES (?, 'Команда А'), (?, 'Команда Б')", TEAM_A, TEAM_B);
        profile(MANAGER_A, "Анна Смирнова", "USER", TEAM_A);
        profile(MANAGER_B, "Борис Орлов", "USER", TEAM_B);
        profile(LEADER_A, "Вера Ковалёва", "LEADER", TEAM_A);
        profile(LEADER_B, "Глеб Соколов", "LEADER", TEAM_B);
        profile(ADMIN, "Администратор", "ADMIN", null);
        organization(ORGANIZATION_A, "Университет А", TEAM_A, MANAGER_A);
        organization(ORGANIZATION_B, "Университет Б", TEAM_B, MANAGER_B);
        interaction(INTERACTION_A, ORGANIZATION_A, STAGE_A, "Повышение квалификации преподавателей");
        interaction(INTERACTION_B, ORGANIZATION_B, STAGE_B, "Чужая работа");
        attachment(ACT_CLEAN, INTERACTION_A, STAGE_A, "акт-пк.pdf", "CLEAN", "2026-03-10T10:00:00+03:00");
        attachment(PROGRAM_CLEAN, INTERACTION_A, STAGE_A, "рабочая-программа.docx", "CLEAN", "2026-02-01T10:00:00+03:00");
        attachment(ACT_REJECTED, INTERACTION_A, STAGE_A, "вирус.pdf", "REJECTED", "2026-03-11T10:00:00+03:00");
        attachment(FOREIGN_CLEAN, INTERACTION_B, STAGE_B, "чужой.pdf", "CLEAN", "2026-03-12T10:00:00+03:00");
        learningRun(uuid(601), "course-1", null, 25, LocalDate.parse("2026-02-01"), LocalDate.parse("2026-06-30"));
        learningRun(uuid(602), "course-2/group-1", 1L, 7, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-01"));
        learningRun(uuid(603), "course-3", null, 100, null, null);
        learningRun(uuid(604), "course-4", null, 40, LocalDate.parse("2025-02-01"), LocalDate.parse("2025-06-30"));
        learningRun(uuid(605), "course-5", null, 50, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-12-25"));
        jdbc.update("""
                INSERT INTO agreement_activity_kinds (id, name, sort_order, archived, version) VALUES
                    (?, 'Разработка и актуализация образовательных программ', 10, FALSE, 0),
                    (?, 'Обучение и повышение квалификации преподавателей', 40, FALSE, 0),
                    (?, 'Иное', 70, TRUE, 0)
                """, KIND_PROGRAMS, KIND_TRAINING, KIND_OTHER);
    }

    @Test
    void managerKeepsAgreementPlanOfOwnUniversityAndOtherTeamsDoNotSeeIt() {
        Agreement agreement = agreementService.create(managerA, ORGANIZATION_A, agreementRequest(null, "7/2026"), "create-1");
        Activity training = agreementService.createActivity(
                managerA, agreement.id(), trainingRequest(null, List.of(ACT_CLEAN)), "activity-1"
        );

        assertThat(agreementService.list(managerA, ORGANIZATION_A)).singleElement().satisfies(summary -> {
            assertThat(summary.number()).isEqualTo("7/2026");
            assertThat(summary.activityCount()).isEqualTo(1);
            assertThat(summary.confirmationCount()).isEqualTo(1);
        });
        Agreement loaded = agreementService.get(leaderA, agreement.id());
        assertThat(loaded.organizationName()).isEqualTo("Университет А");
        assertThat(loaded.activities()).singleElement().satisfies(activity -> {
            assertThat(activity.kindName()).isEqualTo("Обучение и повышение квалификации преподавателей");
            assertThat(activity.responsibleName()).isEqualTo("Вера Ковалёва");
            assertThat(activity.interactions()).extracting(AgreementModels.LinkedInteraction::id).containsExactly(INTERACTION_A);
            assertThat(activity.attachments()).extracting(AgreementModels.LinkedAttachment::originalName).containsExactly("акт-пк.pdf");
        });

        assertThatThrownBy(() -> agreementService.list(managerB, ORGANIZATION_A)).isInstanceOf(OrganizationNotFoundException.class);
        assertThatThrownBy(() -> agreementService.get(leaderB, agreement.id())).isInstanceOfSatisfying(
                AgreementException.class, exception -> assertThat(exception.status().value()).isEqualTo(404)
        );
        assertThatThrownBy(() -> agreementService.updateActivity(
                managerB, training.id(), trainingRequest(training.version(), List.of()), "foreign-edit"
        )).isInstanceOfSatisfying(AgreementException.class, exception -> assertThat(exception.status().value()).isEqualTo(404));
        assertThatThrownBy(() -> agreementService.create(admin, ORGANIZATION_A, agreementRequest(null, "8/2026"), "admin"))
                .isInstanceOf(OrganizationNotFoundException.class);
        assertThat(agreementService.confirmations(managerB, query(null, null))).isEmpty();
    }

    @Test
    void leaderEditsTeamAgreementWithVersionCheckAndRepeatedCommandIsNotDuplicated() {
        Agreement created = agreementService.create(managerA, ORGANIZATION_A, agreementRequest(null, "7/2026"), "create-1");
        Agreement replayed = agreementService.create(managerA, ORGANIZATION_A, agreementRequest(null, "7/2026"), "create-1");

        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agreements", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> agreementService.create(managerA, ORGANIZATION_A, agreementRequest(null, "9/2026"), "create-1"))
                .isInstanceOfSatisfying(InteractionConflictException.class, exception -> assertThat(exception.code())
                        .isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(() -> agreementService.create(managerA, ORGANIZATION_A, agreementRequest(null, "7/2026"), "create-2"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field())
                        .isEqualTo("number"));

        Agreement updated = agreementService.update(leaderA, created.id(), agreementRequest(0, "7/2026-А"), "update-1");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.number()).isEqualTo("7/2026-А");
        assertThatThrownBy(() -> agreementService.update(managerA, created.id(), agreementRequest(0, "7/2026-Б"), "update-2"))
                .isInstanceOfSatisfying(AgreementException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
                    assertThat(exception.currentVersion()).isEqualTo(1);
                });

        Activity activity = agreementService.createActivity(managerA, created.id(), trainingRequest(null, List.of()), "activity-1");
        assertThatThrownBy(() -> agreementService.deleteActivity(managerA, activity.id(), 5, "delete-stale"))
                .isInstanceOfSatisfying(AgreementException.class, exception -> assertThat(exception.currentVersion()).isZero());
        agreementService.deleteActivity(managerA, activity.id(), 0, "delete-1");
        agreementService.deleteActivity(managerA, activity.id(), 0, "delete-1");
        assertThat(agreementService.get(managerA, created.id()).activities()).isEmpty();
    }

    @Test
    void planLinksOnlyCleanDocumentsAndWorksOfTheSameUniversityAndTeamResponsibles() {
        Agreement agreement = agreementService.create(managerA, ORGANIZATION_A, agreementRequest(null, "7/2026"), "create-1");

        assertThatThrownBy(() -> agreementService.createActivity(managerA, agreement.id(), trainingRequest(null, List.of(ACT_REJECTED)), "a1"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field())
                        .isEqualTo("attachmentIds"));
        assertThatThrownBy(() -> agreementService.createActivity(managerA, agreement.id(), trainingRequest(null, List.of(FOREIGN_CLEAN)), "a2"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field())
                        .isEqualTo("attachmentIds"));
        assertThatThrownBy(() -> agreementService.createActivity(managerA, agreement.id(), new ActivityRequest(
                null, KIND_TRAINING, "Курс", "чел.", 10, null, null, null, null, null, LEADER_B, ActivityStatus.PLANNED,
                List.of(INTERACTION_A), List.of()
        ), "a3")).isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field())
                .isEqualTo("responsibleProfileId"));
        assertThatThrownBy(() -> agreementService.createActivity(managerA, agreement.id(), new ActivityRequest(
                null, KIND_TRAINING, "Курс", "чел.", 10, null, null, null, null, null, null, ActivityStatus.PLANNED,
                List.of(INTERACTION_B), List.of()
        ), "a4")).isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field())
                .isEqualTo("interactionIds"));
        assertThatThrownBy(() -> agreementService.createActivity(managerA, agreement.id(), new ActivityRequest(
                null, KIND_OTHER, "Прочее", null, null, null, null, null, null, null, null, ActivityStatus.PLANNED,
                List.of(), List.of()
        ), "a5")).isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field())
                .isEqualTo("kindId"));
        assertThatThrownBy(() -> agreementService.update(managerA, agreement.id(), new AgreementRequest(
                0, "7/2026", LocalDate.parse("2026-02-01"), YEAR_END, null, AgreementStatus.ACTIVE, FOREIGN_CLEAN
        ), "u1")).isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field())
                .isEqualTo("fileAttachmentId"));

        Agreement withFile = agreementService.update(managerA, agreement.id(), new AgreementRequest(
                0, "7/2026", LocalDate.parse("2026-02-01"), YEAR_END, "РТК — Университет А", AgreementStatus.ACTIVE, PROGRAM_CLEAN
        ), "u2");
        assertThat(withFile.file().originalName()).isEqualTo("рабочая-программа.docx");
        assertThat(agreementService.linkOptions(managerA, ORGANIZATION_A).attachments())
                .extracting(AgreementModels.LinkedAttachment::id)
                .containsExactlyInAnyOrder(ACT_CLEAN, PROGRAM_CLEAN);
        assertThat(agreementService.linkOptions(managerA, ORGANIZATION_A).responsibles())
                .extracting(AgreementModels.Responsible::id)
                .containsExactlyInAnyOrder(MANAGER_A, LEADER_A);
    }

    @Test
    void confirmationsAreSelectedByUniversityPeriodAndKindAndDownloadedAsOneArchive() throws IOException {
        Agreement agreement = agreementService.create(managerA, ORGANIZATION_A, agreementRequest(null, "7/2026"), "create-1");
        agreementService.createActivity(managerA, agreement.id(), trainingRequest(null, List.of(ACT_CLEAN)), "activity-1");
        agreementService.createActivity(managerA, agreement.id(), new ActivityRequest(
                null, KIND_PROGRAMS, "Актуализация программы «Сети»", "программа", 1, 1,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15"), null, null, MANAGER_A, ActivityStatus.DONE,
                List.of(INTERACTION_A), List.of(PROGRAM_CLEAN)
        ), "activity-2");
        agreementService.createActivity(managerA, agreement.id(), new ActivityRequest(
                null, KIND_PROGRAMS, "Программа 2027", "программа", 1, null,
                LocalDate.parse("2027-02-01"), LocalDate.parse("2027-03-01"), null, null, MANAGER_A, ActivityStatus.PLANNED,
                List.of(), List.of(ACT_CLEAN)
        ), "activity-3");

        List<Confirmation> year = agreementService.confirmations(managerA, query(ORGANIZATION_A, null));
        assertThat(year).extracting(Confirmation::kindName, Confirmation::originalName).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Разработка и актуализация образовательных программ", "рабочая-программа.docx"),
                org.assertj.core.groups.Tuple.tuple("Обучение и повышение квалификации преподавателей", "акт-пк.pdf")
        );
        assertThat(agreementService.confirmations(managerA, query(ORGANIZATION_A, KIND_TRAINING)))
                .extracting(Confirmation::attachmentId).containsExactly(ACT_CLEAN);
        assertThat(agreementService.confirmations(leaderA, new ConfirmationQuery(null, null, null, null, null))).hasSize(3);
        assertThatThrownBy(() -> agreementService.confirmations(managerB, query(ORGANIZATION_A, null)))
                .isInstanceOf(OrganizationNotFoundException.class);

        AttachmentStorage storage = mock(AttachmentStorage.class);
        when(storage.open(any())).thenAnswer(invocation -> new ByteArrayInputStream(
                ("файл " + invocation.getArgument(0)).getBytes(StandardCharsets.UTF_8)
        ));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new AgreementConfirmationArchive(storage).write(agreementService.confirmationRows(managerA, query(ORGANIZATION_A, null)), output);

        Map<String, String> entries = unzip(output.toByteArray());
        jdbc.update("UPDATE attachments SET size_bytes = ? WHERE id IN (?, ?)", 600L * 1024 * 1024, ACT_CLEAN, PROGRAM_CLEAN);
        assertThat(agreementService.confirmations(managerA, query(ORGANIZATION_A, null))).hasSize(2);
        assertThat(agreementService.archiveRows(managerA, query(ORGANIZATION_A, KIND_TRAINING))).hasSize(1);
        assertThatThrownBy(() -> agreementService.archiveRows(managerA, query(ORGANIZATION_A, null)))
                .isInstanceOfSatisfying(AgreementException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(422);
                    assertThat(exception.code()).isEqualTo("CONFIRMATION_LIMIT");
                });
        assertThat(entries.keySet()).containsExactly(
                "Разработка и актуализация образовательных программ/Университет А — 7_2026/001_рабочая-программа.docx",
                "Обучение и повышение квалификации преподавателей/Университет А — 7_2026/002_акт-пк.pdf",
                AgreementConfirmationArchive.INVENTORY_NAME
        );
        String inventory = entries.get(AgreementConfirmationArchive.INVENTORY_NAME);
        assertThat(inventory).startsWith("\uFEFF\"№\";\"Вуз\";\"Соглашение\";\"Вид мероприятия\"");
        assertThat(inventory).contains("\"Университет А\";\"7/2026\";\"Обучение и повышение квалификации преподавателей\";"
                + "\"Курсы повышения квалификации\";\"01.03.2026 – 30.03.2026\"");
    }

    @Test
    void agreementReportShowsPlanFactConfirmationsAndLearningAggregatesWithinScopeAndPeriod() {
        Agreement agreement = agreementService.create(managerA, ORGANIZATION_A, agreementRequest(null, "7/2026"), "create-1");
        Activity training = agreementService.createActivity(
                managerA, agreement.id(), trainingRequest(null, List.of(ACT_CLEAN)), "activity-1"
        );
        agreementService.createActivity(managerA, agreement.id(), new ActivityRequest(
                null, KIND_PROGRAMS, "Программа 2027", "программа", 1, null,
                LocalDate.parse("2027-02-01"), LocalDate.parse("2027-03-01"), null, null, MANAGER_A, ActivityStatus.PLANNED,
                List.of(), List.of()
        ), "activity-2");
        Agreement empty = agreementService.create(managerA, ORGANIZATION_A, new AgreementRequest(
                null, "1/2025", LocalDate.parse("2025-01-10"), LocalDate.parse("2025-12-31"), null, AgreementStatus.COMPLETED, null
        ), "create-2");

        ReportPreview preview = reportService.preview(managerA, report(YEAR_START, YEAR_END), 0, 50);

        assertThat(preview.total()).isEqualTo(1);
        Map<String, Object> row = preview.items().getFirst();
        assertThat(row.get(ReportColumn.ORGANIZATION.name())).isEqualTo("Университет А");
        assertThat(row.get(ReportColumn.AGREEMENT.name())).isEqualTo("№ 7/2026 от 01.02.2026");
        assertThat(row.get(ReportColumn.AGREEMENT_STATUS.name())).isEqualTo("Действует");
        assertThat(row.get(ReportColumn.ACTIVITY_KIND.name())).isEqualTo("Обучение и повышение квалификации преподавателей");
        assertThat(row.get(ReportColumn.PLANNED_VOLUME.name())).isEqualTo(20L);
        assertThat(row.get(ReportColumn.ACTUAL_VOLUME.name())).isEqualTo(18L);
        assertThat(row.get(ReportColumn.ACTUAL_DATES.name())).isEqualTo("01.03.2026 – 30.03.2026");
        assertThat(row.get(ReportColumn.MANAGER.name())).isEqualTo("Вера Ковалёва");
        assertThat(row.get(ReportColumn.PARTICIPANTS.name())).isEqualTo(32L);
        assertThat(row.get(ReportColumn.WORKS.name())).isEqualTo("Повышение квалификации преподавателей");
        assertThat(row.get(ReportColumn.CONFIRMATIONS.name())).isEqualTo("акт-пк.pdf (10.03.2026)");
        assertThat(row.get(ReportColumn.CONFIRMATION_LINKS.name()))
                .isEqualTo("https://crm.test/api/attachments/" + ACT_CLEAN + "/download");

        ReportPreview unbounded = reportService.preview(managerA, report(null, null), 0, 50);
        assertThat(unbounded.total()).isEqualTo(3);
        assertThat(unbounded.items()).filteredOn(item -> "Курсы повышения квалификации".equals(item.get(ReportColumn.ACTIVITY.name())))
                .singleElement()
                .satisfies(item -> assertThat(item.get(ReportColumn.PARTICIPANTS.name())).isEqualTo(32L));
        agreementService.updateActivity(managerA, training.id(), new ActivityRequest(
                training.version(), KIND_TRAINING, "Курсы повышения квалификации", "преподаватели", 20, 18,
                null, null, null, null, LEADER_A, ActivityStatus.DONE, List.of(INTERACTION_A), List.of(ACT_CLEAN)
        ), "activity-undated");
        assertThat(reportService.preview(managerA, report(LocalDate.parse("2026-08-01"), YEAR_END), 0, 50).items())
                .filteredOn(item -> "Курсы повышения квалификации".equals(item.get(ReportColumn.ACTIVITY.name())))
                .singleElement()
                .satisfies(item -> assertThat(item.get(ReportColumn.PARTICIPANTS.name())).isEqualTo(50L));
        assertThat(reportService.preview(managerA, report(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31")), 0, 50)
                .items()).singleElement().satisfies(item -> {
                    assertThat(item.get(ReportColumn.AGREEMENT.name())).isEqualTo("№ 1/2025 от 10.01.2025");
                    assertThat(item.get(ReportColumn.ACTIVITY.name())).isNull();
                });
        assertThat(empty.activities()).isEmpty();
        assertThat(reportService.preview(managerB, report(null, null), 0, 50).total()).isZero();
        assertThat(reportService.preview(admin, report(null, null), 0, 50).total()).isZero();
        assertThatThrownBy(() -> reportService.statistics(managerA, new ru.rtk.crm.report.StatisticsRequest(
                ReportKind.AGREEMENTS, StatisticsGroupBy.ORGANIZATION, null, null, null, ReportFilters.none(), null, null
        ))).isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field())
                .isEqualTo("groupBy"));
    }

    @Test
    void activityKeepsResponsibleWhoLeftTheTeamWhenActualVolumeIsEntered() {
        Agreement agreement = agreementService.create(managerA, ORGANIZATION_A, agreementRequest(null, "7/2026"), "create-1");
        Activity activity = agreementService.createActivity(managerA, agreement.id(), trainingRequest(null, List.of()), "activity-1");
        jdbc.update("UPDATE crm_user_profiles SET active = FALSE WHERE id = ?", LEADER_A);

        Activity updated = agreementService.updateActivity(managerA, activity.id(), new ActivityRequest(
                activity.version(), KIND_TRAINING, "Курсы повышения квалификации", "преподаватели", 20, 20,
                LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"),
                LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"),
                LEADER_A, ActivityStatus.DONE, List.of(INTERACTION_A), List.of()
        ), "activity-2");

        assertThat(updated.actualVolume()).isEqualTo(20);
        assertThat(updated.responsibleName()).isEqualTo("Вера Ковалёва");
        assertThatThrownBy(() -> agreementService.createActivity(managerA, agreement.id(), trainingRequest(null, List.of()), "activity-3"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field())
                        .isEqualTo("responsibleProfileId"));
    }

    @Test
    void onlyAdministratorEditsActivityKindCatalog() {
        assertThatThrownBy(() -> agreementService.createKind(managerA, new ActivityKindCreateRequest("Хакатоны"), "kind-1"))
                .isInstanceOf(AdminCrmProfileAccessDeniedException.class);

        ActivityKind created = agreementService.createKind(admin, new ActivityKindCreateRequest("Хакатоны"), "kind-1");
        assertThat(created.sortOrder()).isEqualTo(80);
        assertThatThrownBy(() -> agreementService.createKind(admin, new ActivityKindCreateRequest("хакатоны"), "kind-2"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field())
                        .isEqualTo("name"));

        ActivityKind archived = agreementService.updateKind(admin, created.id(), new ActivityKindUpdateRequest(0, "Хакатоны", true), "kind-3");
        assertThat(archived.archived()).isTrue();
        assertThatThrownBy(() -> agreementService.updateKind(admin, created.id(), new ActivityKindUpdateRequest(1, "иное", true), "kind-5"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field())
                        .isEqualTo("name"));
        assertThatThrownBy(() -> agreementService.updateKind(admin, created.id(), new ActivityKindUpdateRequest(0, "Хакатон", false), "kind-4"))
                .isInstanceOfSatisfying(AgreementException.class, exception -> assertThat(exception.currentVersion()).isEqualTo(1));
        assertThat(agreementService.kinds(false)).extracting(ActivityKind::name)
                .containsExactly("Разработка и актуализация образовательных программ", "Обучение и повышение квалификации преподавателей");
        assertThat(agreementService.kinds(true)).hasSize(4);
    }

    private ReportRequest report(LocalDate from, LocalDate to) {
        return new ReportRequest(ReportKind.AGREEMENTS, from, to, null, ReportFilters.none(), List.of(), null, null, null);
    }

    private static ConfirmationQuery query(UUID organizationId, UUID kindId) {
        return new ConfirmationQuery(organizationId, null, kindId, YEAR_START, YEAR_END);
    }

    private static AgreementRequest agreementRequest(Integer version, String number) {
        return new AgreementRequest(
                version, number, LocalDate.parse("2026-02-01"), LocalDate.parse("2027-12-31"),
                "ПАО «Ростелеком», ИТ Школа; Университет А", AgreementStatus.ACTIVE, null
        );
    }

    private static ActivityRequest trainingRequest(Integer version, List<UUID> attachmentIds) {
        return new ActivityRequest(
                version, KIND_TRAINING, "Курсы повышения квалификации", "преподаватели", 20, 18,
                LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"),
                LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-30"),
                LEADER_A, ActivityStatus.DONE, List.of(INTERACTION_A), attachmentIds
        );
    }

    private static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
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

    private void interaction(UUID id, UUID organizationId, UUID stageId, String title) {
        jdbc.update("""
                INSERT INTO interactions (id, organization_id, title, current_stage_id, program_id, created_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, id, organizationId, title, stageId, PROGRAM);
        jdbc.update("INSERT INTO interaction_stages (id, interaction_id, stage_order, name) VALUES (?, ?, 0, '13. Повышение квалификации')",
                stageId, id);
    }

    private void attachment(UUID id, UUID interactionId, UUID stageId, String name, String status, String createdAt) {
        jdbc.update("""
                INSERT INTO attachments (id, interaction_id, stage_id, original_name, media_type, size_bytes, storage_key, status, created_at)
                VALUES (?, ?, ?, ?, 'application/pdf', 10, ?, ?, ?)
                """, id, interactionId, stageId, name, UUID.randomUUID(), status, OffsetDateTime.parse(createdAt));
    }

    private void learningRun(
            UUID recordId,
            String externalId,
            Long groupId,
            int participants,
            LocalDate runStartsOn,
            LocalDate runEndsOn
    ) {
        jdbc.update("INSERT INTO source_records (id, source, external_id) VALUES (?, 'MOODLE', ?)", recordId, externalId);
        jdbc.update("""
                INSERT INTO source_mappings (id, source, kind, external_key, run_starts_on, run_ends_on)
                VALUES (?, 'MOODLE', ?, ?, ?, ?)
                """, UUID.randomUUID(), groupId == null ? "COURSE" : "GROUP", externalId, runStartsOn, runEndsOn);
        jdbc.update("""
                INSERT INTO learning_snapshots (source_record_id, organization_id, program_id, group_id, participants_count)
                VALUES (?, ?, ?, ?, ?)
                """, recordId, ORGANIZATION_A, PROGRAM, groupId, participants);
    }

    private void createSchema() {
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
                CREATE TABLE IF NOT EXISTS contacts (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    confirmed_at TIMESTAMP WITH TIME ZONE,
                    confirmed_by UUID
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
                CREATE TABLE IF NOT EXISTS interactions (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    title VARCHAR(200) NOT NULL,
                    current_stage_id UUID NOT NULL,
                    program_id UUID,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interaction_stages (
                    id UUID PRIMARY KEY,
                    interaction_id UUID NOT NULL,
                    stage_order INTEGER NOT NULL,
                    name VARCHAR(200) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS attachments (
                    id UUID PRIMARY KEY,
                    interaction_id UUID NOT NULL,
                    stage_id UUID NOT NULL,
                    original_name VARCHAR(255) NOT NULL,
                    media_type VARCHAR(160) NOT NULL,
                    size_bytes BIGINT NOT NULL,
                    storage_key UUID NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    deleted_at TIMESTAMP WITH TIME ZONE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS learning_snapshots (
                    source_record_id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    program_id UUID NOT NULL,
                    group_id BIGINT,
                    participants_count INTEGER NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS source_records (
                    id UUID PRIMARY KEY,
                    source VARCHAR(16) NOT NULL,
                    external_id VARCHAR(200) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS source_mappings (
                    id UUID PRIMARY KEY,
                    source VARCHAR(16) NOT NULL,
                    kind VARCHAR(16) NOT NULL,
                    external_key VARCHAR(310) NOT NULL,
                    run_starts_on DATE,
                    run_ends_on DATE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS command_idempotency_records (
                    id UUID PRIMARY KEY,
                    actor_profile_id UUID NOT NULL,
                    operation VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    request_fingerprint CHAR(64) NOT NULL,
                    result_json VARCHAR(100000),
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (actor_profile_id, operation, idempotency_key)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_activity_kinds (
                    id UUID PRIMARY KEY,
                    name VARCHAR(200) NOT NULL,
                    name_key VARCHAR(200) GENERATED ALWAYS AS (LOWER(name)) UNIQUE,
                    sort_order INTEGER NOT NULL,
                    archived BOOLEAN NOT NULL DEFAULT FALSE,
                    version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreements (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    number VARCHAR(100) NOT NULL,
                    concluded_on DATE,
                    valid_until DATE,
                    parties VARCHAR(2000),
                    status VARCHAR(16) NOT NULL,
                    file_attachment_id UUID,
                    version INTEGER NOT NULL DEFAULT 0,
                    created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    CONSTRAINT agreements_organization_number_key UNIQUE (organization_id, number)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_activities (
                    id UUID PRIMARY KEY,
                    agreement_id UUID NOT NULL REFERENCES agreements(id) ON DELETE CASCADE,
                    kind_id UUID NOT NULL,
                    title VARCHAR(300) NOT NULL,
                    unit VARCHAR(50),
                    planned_volume INTEGER,
                    actual_volume INTEGER,
                    planned_start DATE,
                    planned_end DATE,
                    actual_start DATE,
                    actual_end DATE,
                    responsible_profile_id UUID,
                    status VARCHAR(16) NOT NULL,
                    version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_activity_interactions (
                    activity_id UUID NOT NULL REFERENCES agreement_activities(id) ON DELETE CASCADE,
                    interaction_id UUID NOT NULL,
                    PRIMARY KEY (activity_id, interaction_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_activity_attachments (
                    activity_id UUID NOT NULL REFERENCES agreement_activities(id) ON DELETE CASCADE,
                    attachment_id UUID NOT NULL,
                    PRIMARY KEY (activity_id, attachment_id)
                )
                """);
    }

    private static UUID uuid(int value) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @ConditionalOnMissingBean(ObjectMapper.class)
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        ReportProperties reportProperties() {
            return new ReportProperties(Path.of("target", "test-reports"), 10, 5, 1_000, 1_000);
        }
    }
}
