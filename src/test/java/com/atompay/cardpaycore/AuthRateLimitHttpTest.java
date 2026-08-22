package com.atompay.cardpaycore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies RateLimitFilter is actually wired for /api/v1/auth/login, not just unit-tested in isolation. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"ratelimit.login.limit=3", "ratelimit.login.window-seconds=60"})
class AuthRateLimitHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginShouldBeRateLimitedPerRemoteAddress() throws Exception {
        String body = "{\"username\":\"admin\",\"password\":\"password123\"}";

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }
}
