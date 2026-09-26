package ru.rtk.crm.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.rtk.crm.security.RequestId;

class ApiErrorSerializationTest {
    @Test
    void omitsOptionalFieldsWhenTheyAreAbsent() throws JsonProcessingException {
        String json = new ObjectMapper().writeValueAsString(ApiError.of("IDEMPOTENCY_CONFLICT", "Conflict", "request-id"));

        assertThat(json).doesNotContain("fieldErrors");
        assertThat(json).doesNotContain("currentVersion");
    }

    @Test
    void mapsContactInteractionMutationRoleDenialToGenericForbidden() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestId.ATTRIBUTE, "request-id");

        var response = new ApiExceptionHandler().contactInteractionMutationAccessDenied(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isEqualTo(ApiError.of("FORBIDDEN", "Доступ запрещён", "request-id"));
    }
}
