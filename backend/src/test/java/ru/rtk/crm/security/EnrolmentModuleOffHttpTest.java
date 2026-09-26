package ru.rtk.crm.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:enrolment-module-off;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-enrolment-off-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.enrolment.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class EnrolmentModuleOffHttpTest {
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final UUID OPERATOR = UUID.fromString("51000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_user_profiles (
                    id UUID PRIMARY KEY, issuer VARCHAR(512) NOT NULL, subject VARCHAR(512) NOT NULL,
                    display_name VARCHAR(200) NOT NULL, role VARCHAR(16) NOT NULL, team_id UUID,
                    active BOOLEAN NOT NULL DEFAULT TRUE, pending_activation BOOLEAN NOT NULL DEFAULT FALSE,
                    access_revision INTEGER NOT NULL DEFAULT 0, enrolment_operator BOOLEAN NOT NULL DEFAULT FALSE,
                    UNIQUE (issuer, subject)
                )
                """);
        jdbcTemplate.update("DELETE FROM crm_user_profiles");
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, enrolment_operator)
                VALUES (?, ?, ?, 'Оператор зачисления', 'USER', TRUE)
                """, OPERATOR, ISSUER, OPERATOR.toString());
    }

    @Test
    void everyEnrolmentMethodAnswersNotFoundWhileTheModuleIsDisabledEvenForTheFlagHolder() throws Exception {
        String id = UUID.randomUUID().toString();
        MockMultipartFile file = new MockMultipartFile("file", "learners.xlsx", null, "x".getBytes(StandardCharsets.UTF_8));
        List<RequestBuilder> requests = List.of(
                get("/api/enrolment/streams").with(operator()),
                patch("/api/enrolment/streams/{id}", id).with(operator()).with(csrf()).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"endsOn\":null}"),
                get("/api/enrolment/streams/{id}/learners", id).with(operator()),
                multipart("/api/enrolment/streams/{id}/questionnaire-imports/preview", id).file(file).with(operator()).with(csrf()),
                multipart("/api/enrolment/streams/{id}/questionnaire-imports/apply", id).file(file).param("fingerprint", "0".repeat(64))
                        .with(operator()).with(csrf()).header("Idempotency-Key", "k"),
                post("/api/enrolment/streams/{id}/lms-roster", id).with(operator()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ALL\",\"incomplete\":\"INCLUDE\"}"),
                post("/api/enrolment/roster-exports/{id}/transferred", id).with(operator()).with(csrf()).header("Idempotency-Key", "k"),
                post("/api/enrolment/learners/search").with(operator()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"EMAIL\",\"value\":\"a@example.test\"}"),
                get("/api/enrolment/learners/{id}", id).with(operator()),
                get("/api/enrolment/learners/{id}", "not-an-id").with(operator()),
                patch("/api/enrolment/learners/{id}", id).with(operator()).with(csrf()).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"fields\":{\"APARTMENT\":\"1\"}}"),
                post("/api/enrolment/learners/{id}/reveal", id).with(operator()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"groups\":[\"DOCUMENTS\"]}"),
                post("/api/enrolment/learners/{id}/move-enrolments", id).with(operator()).with(csrf()).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"snils\":\"11223344595\",\"version\":0}"),
                get("/api/enrolment/learners/{id}/history", id).with(operator()),
                multipart("/api/enrolment/paid-orders").file(new MockMultipartFile("file", "orders.json", "application/json",
                        "[null]".getBytes(StandardCharsets.UTF_8))).with(operator()).with(csrf()).header("Idempotency-Key", "k")
        );
        for (RequestBuilder request : requests) {
            mockMvc.perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Раздел «Зачисление» недоступен: модуль «Слушатели» выключен"));
        }
        mockMvc.perform(get("/api/me").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolmentOperator").value(false));
    }

    private RequestPostProcessor operator() {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject(OPERATOR.toString()));
    }
}
