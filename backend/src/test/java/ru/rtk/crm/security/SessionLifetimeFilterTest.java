package ru.rtk.crm.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class SessionLifetimeFilterTest {
    private final SessionLifetimeFilter filter = new SessionLifetimeFilter(Duration.ofHours(12));

    @Test
    void sessionOlderThanTheAbsoluteLimitIsClosedEvenWhenActive() throws Exception {
        MockHttpSession expired = sessionCreatedAgo(Duration.ofHours(12).plusMinutes(1));
        MockHttpSession fresh = sessionCreatedAgo(Duration.ofHours(11));

        filter.doFilter(request(expired), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(request(fresh), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(expired.isInvalid()).isTrue();
        assertThat(fresh.isInvalid()).isFalse();
    }

    private static MockHttpServletRequest request(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.setSession(session);
        return request;
    }

    private static MockHttpSession sessionCreatedAgo(Duration age) {
        long createdAt = System.currentTimeMillis() - age.toMillis();
        return new MockHttpSession() {
            @Override
            public long getCreationTime() {
                return createdAt;
            }
        };
    }
}
