package ru.rtk.crm.interaction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationListStatus;
import ru.rtk.crm.catalog.OrganizationPage;
import ru.rtk.crm.catalog.OrganizationQuery;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationSort;
import ru.rtk.crm.catalog.OrganizationStatus;
import ru.rtk.crm.catalog.OrganizationType;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:work-lists-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-work-lists-http-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class WorkListsHttpTest {
    private static final UUID TEAM_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final CrmProfile PROFILE = new CrmProfile(
            UUID.fromString("00000000-0000-0000-0000-000000000011"),
            UserRole.LEADER,
            TEAM_A,
            0
    );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentProfileService currentProfileService;

    @MockitoBean
    private InteractionService interactionService;

    @MockitoBean
    private OrganizationRepository organizationRepository;

    @BeforeEach
    void setUp() {
        when(currentProfileService.requireActiveProfile(any())).thenReturn(PROFILE);
    }

    @Test
    void interactionListWithoutOrganizationPassesWorkFiltersToService() throws Exception {
        OffsetDateTime changedAt = OffsetDateTime.parse("2026-09-20T10:00:00+03:00");
        InteractionSummary summary = new InteractionSummary(
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                ORGANIZATION_A,
                "Встреча с деканом",
                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                "Первичный контакт",
                "Позвонить",
                OffsetDateTime.parse("2026-09-01T10:00:00+03:00"),
                List.of(),
                null,
                List.of(),
                null,
                3,
                PROFILE.id(),
                changedAt,
                changedAt,
                "Колледж А2",
                null,
                "Анна Смирнова",
                new InteractionMarks(
                        InteractionWorkStatus.PAUSED,
                        "Вуз перенёс старт",
                        InteractionWaiting.UNIVERSITY,
                        "Ждём доступы",
                        null,
                        InteractionRiskLevel.HIGH,
                        "Вуз не отвечает три недели"
                ),
                "COMMENTED",
                changedAt,
                changedAt,
                null,
                null
        );
        when(interactionService.list(any(), any(), any())).thenReturn(new InteractionPage(List.of(summary), 1, 10, 11));

        mockMvc.perform(get("/api/interactions")
                        .with(oidcLogin())
                        .param("q", " Колледж ")
                        .param("due", "OVERDUE")
                        .param("stage", "Первичный контакт")
                        .param("status", "PAUSED")
                        .param("flag", "WAITING_UNIVERSITY")
                        .param("sort", "nextActionAt,asc")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.items[0].organizationId").value(ORGANIZATION_A.toString()))
                .andExpect(jsonPath("$.items[0].organizationName").value("Колледж А2"))
                .andExpect(jsonPath("$.items[0].currentStageName").value("Первичный контакт"))
                .andExpect(jsonPath("$.items[0].ownerManagerName").value("Анна Смирнова"))
                .andExpect(jsonPath("$.items[0].programName").doesNotExist())
                .andExpect(jsonPath("$.items[0].marks.status").value("PAUSED"))
                .andExpect(jsonPath("$.items[0].marks.waitingOn").value("UNIVERSITY"))
                .andExpect(jsonPath("$.items[0].marks.riskLevel").value("HIGH"));

        verify(interactionService).list(
                eq(PROFILE),
                eq(new InteractionFilter(
                        null,
                        "Колледж",
                        InteractionDue.OVERDUE,
                        "Первичный контакт",
                        InteractionWorkStatus.PAUSED,
                        InteractionFlag.WAITING_UNIVERSITY,
                        null,
                        null,
                        false,
                        null
                )),
                eq(new InteractionQuery(1, 10, InteractionSort.NEXT_ACTION_AT_ASC))
        );
    }

    @Test
    void listWithoutStatusShowsOnlyActiveWorkAndAllStatusRemovesTheFilter() throws Exception {
        when(interactionService.list(any(), any(), any())).thenReturn(new InteractionPage(List.of(), 0, 25, 0));

        mockMvc.perform(get("/api/interactions").with(oidcLogin())).andExpect(status().isOk());
        mockMvc.perform(get("/api/interactions").with(oidcLogin()).param("status", "ALL")).andExpect(status().isOk());

        verify(interactionService).list(
                eq(PROFILE),
                eq(new InteractionFilter(null, null, null, null, InteractionWorkStatus.ACTIVE, null, null, null, false, null)),
                any()
        );
        verify(interactionService).list(
                eq(PROFILE),
                eq(new InteractionFilter(null, null, null, null, null, null, null, null, false, null)),
                any()
        );
    }

    @Test
    void workListPassesResponsibleStatusAndStageAgeToService() throws Exception {
        when(interactionService.list(any(), any(), any())).thenReturn(new InteractionPage(List.of(), 0, 25, 0));
        UUID responsible = UUID.randomUUID();

        mockMvc.perform(get("/api/interactions")
                        .with(oidcLogin())
                        .param("responsible", "UNASSIGNED")
                        .param("status", "ALL")
                        .param("minDaysOnStage", "30"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/interactions")
                        .with(oidcLogin())
                        .param("responsible", responsible.toString())
                        .param("status", "COMPLETED"))
                .andExpect(status().isOk());

        verify(interactionService).list(
                eq(PROFILE),
                eq(new InteractionFilter(null, null, null, null, null, null, null, null, true, 30)),
                eq(new InteractionQuery(0, 25, InteractionSort.UPDATED_DESC))
        );
        verify(interactionService).list(
                eq(PROFILE),
                eq(new InteractionFilter(null, null, null, null, InteractionWorkStatus.COMPLETED, null, null, responsible, false, null)),
                eq(new InteractionQuery(0, 25, InteractionSort.UPDATED_DESC))
        );
    }

    @Test
    void unknownFlagAndStatusAreValidationErrorsOfTheirFields() throws Exception {
        mockMvc.perform(get("/api/interactions").with(oidcLogin()).param("flag", "LATE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.flag").value("Такой признак не поддерживается"));
        mockMvc.perform(get("/api/interactions").with(oidcLogin()).param("status", "CLOSED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.status").value("Такой статус работы не поддерживается"));

        verifyNoInteractions(interactionService);
    }

    @Test
    void unknownDueFilterIsValidationErrorOfDueField() throws Exception {
        mockMvc.perform(get("/api/interactions").with(oidcLogin()).param("due", "LATER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.due").value("Такой отбор по сроку не поддерживается"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        verifyNoInteractions(interactionService);
    }

    @Test
    void organizationListPassesSearchAndAssignmentFilter() throws Exception {
        Organization organization = new Organization(
                ORGANIZATION_A,
                "Колледж А2",
                OrganizationType.SCHOOL,
                TEAM_A,
                null,
                2,
                OffsetDateTime.parse("2026-09-20T10:00:00+03:00"),
                null,
                "Команда А",
                true,
                OrganizationStatus.ACTIVE,
                null,
                null,
                null,
                false,
                null,
                null
        );
        when(organizationRepository.findVisible(any(), any())).thenReturn(new OrganizationPage(List.of(organization), 0, 25, 1));

        mockMvc.perform(get("/api/organizations")
                        .with(oidcLogin())
                        .param("q", " Колледж ")
                        .param("requiresAssignment", "true")
                        .param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].teamName").value("Команда А"))
                .andExpect(jsonPath("$.items[0].requiresAssignment").value(true))
                .andExpect(jsonPath("$.items[0].ownerManagerName").doesNotExist());

        verify(organizationRepository).findVisible(
                eq(PROFILE),
                eq(new OrganizationQuery(0, 25, OrganizationSort.NAME_ASC, "Колледж", true, OrganizationListStatus.CURRENT))
        );
    }

    @Test
    void nonBooleanAssignmentFilterIsValidationError() throws Exception {
        mockMvc.perform(get("/api/organizations").with(oidcLogin()).param("requiresAssignment", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.requiresAssignment").value("Значение в неверном формате"));

        verifyNoInteractions(organizationRepository);
    }
}
