package com.getjobs.application.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {
    @Test
    void localFrontendIsAllowedAndUnrelatedOriginsAreRejected() throws Exception {
        assertOrigin("http://127.0.0.1:6866", true);
        assertOrigin("http://localhost:9527", true);
        assertOrigin("https://unrelated.example", false);
        assertOrigin("http://localhost:9999", false);
        assertOrigin("http://localhost.evil.example:6866", false);
    }

    private void assertOrigin(String origin, boolean allowed) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cookie");
        request.addHeader("Origin", origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedController = new AtomicBoolean();
        new CorsConfig().corsFilter().doFilter(request, response,
                (req, res) -> reachedController.set(true));
        assertEquals(allowed, reachedController.get());
        if (allowed) {
            assertEquals(origin, response.getHeader("Access-Control-Allow-Origin"));
        } else {
            assertEquals(403, response.getStatus());
        }
    }
}
