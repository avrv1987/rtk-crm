package ru.rtk.crm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

// Отказ базы на вводе и сбои java.time — неверный ввод или конфликт, а не 500. SQLSTATE одинаковы у PostgreSQL и H2.
class ApiExceptionHandlerRobustnessTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/organizations");

    @Test
    void databaseConstraintsMapToConflictOrValidation() {
        assertThat(db(new DuplicateKeyException("dup"))).satisfies(response -> expect(response, 409, "CONFLICT"));
        assertThat(db(violation("23505"))).satisfies(response -> expect(response, 409, "CONFLICT"));
        assertThat(db(violation("23503"))).satisfies(response -> expect(response, 409, "CONFLICT"));
        assertThat(db(violation("22001"))).satisfies(response -> expect(response, 400, "VALIDATION_ERROR"));
        assertThat(db(violation("22003"))).satisfies(response -> expect(response, 400, "VALIDATION_ERROR"));
        assertThat(db(violation("22008"))).satisfies(response -> expect(response, 400, "VALIDATION_ERROR"));
        assertThat(db(violation("23514"))).satisfies(response -> expect(response, 400, "VALIDATION_ERROR"));
        assertThat(db(new DataIntegrityViolationException("no sql state"))).satisfies(response -> expect(response, 400, "VALIDATION_ERROR"));
        // Б-9: символ NUL, который отвергла база, называется недопустимым символом, а не «слишком длинным значением».
        assertThat(db(violation("22021")).getBody().fieldErrors().get("body")).startsWith("Недопустимый символ");
        assertThat(db(violation("22023")).getBody().fieldErrors().get("body")).startsWith("Недопустимый символ");
    }

    @Test
    void dateOverflowLocksAndBrokenUploadsAreClientSide() {
        expect(handler.dateOutOfRange(request), 400, "VALIDATION_ERROR");
        expect(handler.brokenMultipart(request), 400, "VALIDATION_ERROR");
        expect(handler.concurrentChange(request), 409, "CONFLICT");
    }

    private ResponseEntity<ApiError> db(DataIntegrityViolationException exception) {
        return handler.constraintViolated(exception, request);
    }

    private static DataIntegrityViolationException violation(String sqlState) {
        return new DataIntegrityViolationException("rejected", new SQLException("value of a field", sqlState));
    }

    private static void expect(ResponseEntity<ApiError> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().message()).doesNotContain("value of a field");
    }
}
