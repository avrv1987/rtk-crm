package ru.rtk.crm.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationAssignmentRepository;
import ru.rtk.crm.report.ManagerWorkChange;
import ru.rtk.crm.report.ReportFilters;
import ru.rtk.crm.report.ReportRepository;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:work-control-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-work-control-http-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class WorkControlHttpTest {
    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MANAGER = UUID.fromString("00000000-0000-0000-0000-000000000011");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentProfileService currentProfileService;

    @MockitoBean
    private ReportRepository reportRepository;

    @MockitoBean
    private OrganizationAssignmentRepository organizationAssignmentRepository;

    @Test
    void onlyLeaderAndManagementReadTheControlMatrix() throws Exception {
        for (UserRole role : List.of(UserRole.USER, UserRole.ADMIN, UserRole.PARTNER)) {
            when(currentProfileService.requireActiveProfile(any())).thenReturn(new CrmProfile(MANAGER, role, TEAM, 0));
            mockMvc.perform(get("/api/work/control").with(oidcLogin()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        when(currentProfileService.requireActiveProfile(any())).thenReturn(new CrmProfile(MANAGER, UserRole.LEADER, TEAM, 0));
        mockMvc.perform(get("/api/work/control").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byTeams").value(false))
                .andExpect(jsonPath("$.total.works").value(0));
        when(currentProfileService.requireActiveProfile(any())).thenReturn(new CrmProfile(MANAGER, UserRole.MANAGEMENT, null, 0));
        mockMvc.perform(get("/api/work/control").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byTeams").value(true));
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/work/control")).andExpect(status().isUnauthorized());
    }

    @Test
    void queryParametersBecomeFiltersAndPeriod() throws Exception {
        when(currentProfileService.requireActiveProfile(any())).thenReturn(new CrmProfile(MANAGER, UserRole.MANAGEMENT, null, 0));
        UUID product = UUID.fromString("00000000-0000-0000-0000-000000000401");

        mockMvc.perform(get("/api/work/control").with(oidcLogin())
                        .param("productId", product.toString())
                        .param("managerId", MANAGER.toString(), "UNASSIGNED")
                        .param("stage", "Согласование, подписание")
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-30")
                        .param("change", "MOVED"))
                .andExpect(status().isOk());

        ArgumentCaptor<ReportFilters> filters = ArgumentCaptor.forClass(ReportFilters.class);
        verify(reportRepository).findManagerWork(
                any(), filters.capture(), eq(LocalDate.parse("2026-09-01")), eq(LocalDate.parse("2026-09-30")), any()
        );
        assertThat(filters.getValue().productIds()).containsExactly(product);
        assertThat(filters.getValue().managerIds()).containsExactly(MANAGER);
        assertThat(filters.getValue().includeNoManager()).isTrue();
        assertThat(filters.getValue().stages()).containsExactly("Согласование, подписание");
        assertThat(filters.getValue().change()).isEqualTo(ManagerWorkChange.MOVED);
    }

    @Test
    void malformedParametersAreBadRequests() throws Exception {
        when(currentProfileService.requireActiveProfile(any())).thenReturn(new CrmProfile(MANAGER, UserRole.MANAGEMENT, null, 0));
        when(reportRepository.findManagerWorkProducts(any())).thenReturn(Map.of());

        for (String[] parameter : new String[][]{{"productId", "не-идентификатор"}, {"from", "вчера"}, {"change", "ALL"}}) {
            mockMvc.perform(get("/api/work/control").with(oidcLogin()).param(parameter[0], parameter[1]))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/work/control").with(oidcLogin()).param("from", "2026-09-30").param("to", "2026-09-01"))
                .andExpect(status().isBadRequest());
    }
}
