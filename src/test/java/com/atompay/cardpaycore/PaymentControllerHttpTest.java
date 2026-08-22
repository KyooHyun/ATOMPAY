package com.atompay.cardpaycore;

import com.atompay.cardpaycore.dto.AuthorizeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the controller over real Spring MVC dispatch (not just the
 * service layer), so it catches wiring bugs that only manifest over HTTP --
 * e.g. the missing `-parameters` compiler flag that broke every
 * {@code @PathVariable} endpoint (getPayment, capture, cancel, refund...)
 * while every service-layer test kept passing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentControllerHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser(username = "admin")
    void getPaymentByPathVariableShouldSucceedOverRealHttpDispatch() throws Exception {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(1_000));

        String authorizeResponseBody = mockMvc.perform(post("/api/v1/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "mockmvc-http-test")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String authorizationId = objectMapper.readTree(authorizeResponseBody).get("authorizationId").asText();

        mockMvc.perform(get("/api/v1/payments/{authorizationId}", authorizationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationId").value(authorizationId))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));

        mockMvc.perform(get("/api/v1/payments/{authorizationId}/audit-log", authorizationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("AUTHORIZATION"));
    }
}
