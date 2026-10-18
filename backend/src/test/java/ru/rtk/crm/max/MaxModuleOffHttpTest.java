package ru.rtk.crm.max;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// Модуль выключен (значение по умолчанию): нет ни бинов модуля, ни его таблиц, а все пути модуля отвечают 404.
// Заданный MAX_BOT_TOKEN при выключенном модуле ничего не делает.
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:max-off;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:ru/rtk/crm/max/base-schema.sql'",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-max-off-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.max.bot-token=env-bot-token-module-off"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class MaxModuleOffHttpTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext context;

    @Test
    void configReportsModuleOff() throws Exception {
        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules.max").value(false));
    }

    @Test
    void everyModulePathAnswersNotFoundEvenWithoutSession() throws Exception {
        mockMvc.perform(post("/api/max/webhook").header(MaxWebhookController.SECRET_HEADER, "secret")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"update_type\":\"bot_started\",\"timestamp\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/api/max/app/session").contentType(MediaType.APPLICATION_JSON).content("{\"initData\":\"x\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/max/app/work").header("Authorization", "Bearer x"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/max/link").with(oidcLogin()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/max/link").with(oidcLogin()).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/max/admin/settings").with(oidcLogin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void noModuleTablesAndNoSchemaHistoryAreCreated() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT LOWER(table_name) FROM information_schema.tables WHERE LOWER(table_name) LIKE 'max%' OR LOWER(table_name) LIKE 'flyway%'",
                String.class);
        assertThat(tables).isEmpty();
    }

    @Test
    void noModuleBeansAreCreated() {
        List<String> moduleBeans = Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    Class<?> type = context.getType(name);
                    return type != null && type.getPackageName().equals(MaxEnabled.class.getPackageName());
                })
                .toList();
        assertThat(moduleBeans).isEmpty();
    }

    @Test
    void environmentTokenLeavesNoTraceWhenModuleIsOff() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action LIKE 'MAX%'", Integer.class)).isZero();
    }
}
