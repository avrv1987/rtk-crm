package ru.rtk.crm.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ApiRateLimitFilterTest {
    private final MutableClock clock = new MutableClock();
    private final ApiRateLimitFilter filter = new ApiRateLimitFilter(
            new ApiErrorWriter(new ObjectMapper(), new ApiErrorLog()), 3, 2, clock);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void signedInUserIsCountedByLoginAndGetsRetryAfter() throws Exception {
        signIn("kam-1");
        clock.at(10_000);
        for (int i = 0; i < 3; i++) {
            assertThat(call("/api/interactions", "10.0.0.1").getStatus()).isEqualTo(200);
        }
        clock.at(25_000);
        MockHttpServletResponse limited = call("/api/interactions", "10.0.0.1");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("45");
        assertThat(limited.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");

        // Другой пользователь с того же адреса (общий NAT, нагрузочный тест) считается отдельно.
        signIn("kam-2");
        assertThat(call("/api/interactions", "10.0.0.1").getStatus()).isEqualTo(200);

        signIn("kam-1");
        clock.at(70_000);
        assertThat(call("/api/interactions", "10.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void anonymousRequestsAreCountedByAddressAndExclusionsPass() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThat(call("/api/config", "10.0.0.2").getStatus()).isEqualTo(200);
        assertThat(call("/api/config", "10.0.0.2").getStatus()).isEqualTo(200);
        assertThat(call("/api/config", "10.0.0.2").getStatus()).isEqualTo(429);
        assertThat(call("/api/config", "10.0.0.3").getStatus()).isEqualTo(200);

        for (int i = 0; i < 5; i++) {
            assertThat(call("/api/max/webhook", "10.0.0.2").getStatus()).isEqualTo(200);
            assertThat(call("/healthz", "10.0.0.2").getStatus()).isEqualTo(200);
            assertThat(call("/assets/app.js", "10.0.0.2").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void zeroLimitTurnsTheFilterOff() throws Exception {
        ApiRateLimitFilter off = new ApiRateLimitFilter(new ApiErrorWriter(new ObjectMapper(), new ApiErrorLog()), 0, 0, clock);
        signIn("kam-1");
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            off.doFilter(request("/api/me", "10.0.0.1"), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    private MockHttpServletResponse call(String path, String address) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(path, address), response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request(String path, String address) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(address);
        return request;
    }

    private static void signIn(String login) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(login, "n/a", "OIDC_USER"));
    }

    private static final class MutableClock extends Clock {
        private long millis;

        void at(long value) {
            millis = value;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
