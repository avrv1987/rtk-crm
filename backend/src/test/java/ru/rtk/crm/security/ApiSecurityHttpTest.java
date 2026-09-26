package ru.rtk.crm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.DispatcherServlet;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:api-security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-api-security-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
@ExtendWith(OutputCaptureExtension.class)
class ApiSecurityHttpTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousApiRequestReceivesJsonUnauthorizedWithRequestId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("Требуется вход в систему"))
                .andReturn();

        String requestId = result.getResponse().getHeader("X-Request-Id");
        assertThat(requestId).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).contains("\"requestId\":\"" + requestId + "\"");
    }

    @Test
    void mutationWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/interactions")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Доступ запрещён"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void beanValidationErrorsAreReturnedInRussianWithTheSameCode() throws Exception {
        mockMvc.perform(post("/api/organizations/00000000-0000-0000-0000-000000000101/contacts")
                        .with(oidcLogin())
                        .with(csrf())
                        .header("Idempotency-Key", "contact-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Проверьте введённые данные"))
                .andExpect(jsonPath("$.fieldErrors.name").value("Заполните поле"))
                .andExpect(jsonPath("$.fieldErrors.email").value("Укажите адрес электронной почты в формате name@example.ru"));
    }

    @Test
    void unknownApiPathOfAuthenticatedUserEndsInApiNotFound() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/unknown").with(oidcLogin()))
                .andExpect(status().isNotFound())
                .andReturn();
        String requestId = result.getResponse().getHeader("X-Request-Id");

        mockMvc.perform(get("/error")
                        .with(oidcLogin())
                        .with(errorDispatch(result.getResponse().getStatus(), requestId)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").value(requestId));
    }

    @ParameterizedTest
    @CsvSource({
            "405, METHOD_NOT_ALLOWED",
            "415, UNSUPPORTED_MEDIA_TYPE",
            "500, INTERNAL_ERROR"
    })
    void errorDispatchOfAuthenticatedUserKeepsStatusAndReturnsApiError(int statusCode, String code) throws Exception {
        mockMvc.perform(get("/error").with(oidcLogin()).with(errorDispatch(statusCode, "request-" + statusCode)))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.requestId").value("request-" + statusCode))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @ParameterizedTest
    @MethodSource("exceptionAttributes")
    void serverErrorIsLoggedWithRequestIdAndStackTraceButNotReturned(String exceptionAttribute, CapturedOutput output)
            throws Exception {
        RequestPostProcessor failure = request -> {
            request.setAttribute(exceptionAttribute, new IllegalStateException("database password leaked"));
            return request;
        };

        mockMvc.perform(get("/error").with(errorDispatch(500, "request-failure")).with(failure))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(allOf(
                        not(containsString("IllegalStateException")),
                        not(containsString("database password leaked")))));

        assertThat(output).contains(
                "[request-failure]",
                "java.lang.IllegalStateException: database password leaked",
                "at ru.rtk.crm.security.ApiSecurityHttpTest"
        );
    }

    @Test
    void swaggerUiUsesTheStaticContract() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("swagger-ui")));
        mockMvc.perform(get("/swagger-ui/swagger-initializer.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("/v3/api-docs/swagger-config"),
                        not(containsString("petstore")))));
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/openapi.yaml"))
                .andExpect(jsonPath("$.validatorUrl").value("none"));
        mockMvc.perform(get("/v3/api-docs").with(oidcLogin()))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/v3/api-docs.yaml").with(oidcLogin()))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(startsWith("openapi: 3.1.0")));
    }

    @Test
    void logoutReturnsKeycloakEndSessionUrl() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .with(oidcLogin().idToken(token -> token.tokenValue("id-token-value")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.logoutUrl").value(
                        "http://crm.test/idp/realms/rtk-crm/protocol/openid-connect/logout"
                                + "?client_id=crm-bff"
                                + "&post_logout_redirect_uri=http://crm.test/"
                                + "&id_token_hint=id-token-value"));
    }

    private static Stream<String> exceptionAttributes() {
        return Stream.of(RequestDispatcher.ERROR_EXCEPTION, DispatcherServlet.EXCEPTION_ATTRIBUTE);
    }

    private static RequestPostProcessor errorDispatch(int statusCode, String requestId) {
        return request -> {
            request.setDispatcherType(DispatcherType.ERROR);
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, statusCode);
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/unknown");
            request.setAttribute(RequestId.ATTRIBUTE, requestId);
            return request;
        };
    }
}
