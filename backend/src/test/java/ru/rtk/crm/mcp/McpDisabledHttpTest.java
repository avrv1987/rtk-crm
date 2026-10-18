package ru.rtk.crm.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// Флаг по умолчанию выключен: /mcp и метаданные ресурса не существуют, инструментов и транспорта в контексте нет.
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:mcp-disabled;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-mcp-disabled-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class McpDisabledHttpTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void mcpPathsAnswer404WhenTheFlagIsOff() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer any")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp")).andExpect(status().isNotFound());
        mockMvc.perform(get("/.well-known/oauth-protected-resource")).andExpect(status().isNotFound());
        assertThat(context.getBeanNamesForType(McpTools.class)).isEmpty();
        assertThat(context.containsBean("mcpServer")).isFalse();
        assertThat(context.getBeanNamesForType(AgentPlanService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AgentPlansApiController.class)).isEmpty();
    }

    // Экран «План ИИ-агента»: без модуля и без сеанса — 404, а не 401; признак модуля отдаётся в /api/config,
    // чтобы фронтенд не регистрировал маршрут #/agent-plans.
    @Test
    void agentPlansAnswer404WithoutSessionAndConfigReportsMcpOff() throws Exception {
        String id = "00000000-0000-0000-0000-000000000001";
        mockMvc.perform(get("/api/agent-plans/{id}", id)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/agent-plans/{id}/apply", id)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/agent-plans/{id}/reject", id)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/config")).andExpect(status().isOk()).andExpect(jsonPath("$.modules.mcp").value(false));
    }

    @Test
    void mainApiStillRequiresTheSession() throws Exception {
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer any")).andExpect(status().isUnauthorized());
    }
}
