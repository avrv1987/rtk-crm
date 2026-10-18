package ru.rtk.crm.mail;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// MAIL_ENABLED=false (по умолчанию): ни бинов, ни заданий, ни отправки; все пути модуля отвечают 404. Пароль новому
// сотруднику при этом показывается на экране, как раньше (EmployeeAccountHttpTest).
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:mail-off;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-mail-off-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class MailModuleOffHttpTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void configReportsModuleOff() throws Exception {
        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules.mail").value(false));
    }

    @Test
    void everyModulePathAnswersNotFound() throws Exception {
        mockMvc.perform(post("/api/mail/invitations").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/mail/preferences").with(oidcLogin())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/mail/admin/settings").with(oidcLogin())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/mail/admin/test").with(oidcLogin()).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void noModuleBeansSoNothingIsQueuedOrSent() {
        List<String> moduleBeans = Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    Class<?> type = context.getType(name);
                    return type != null && type.getPackageName().equals(MailEnabled.class.getPackageName());
                })
                .toList();
        assertThat(moduleBeans).isEmpty();
    }
}
