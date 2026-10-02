package com.atompay.cardpaycore.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The exception types here are the ones PaymentServiceMySqlConcurrencyTest
 * shows actually surfacing from a lock wait timeout on real MySQL; this pins
 * down what the client sees for them.
 */
class GlobalExceptionHandlerTest {

    @RestController
    static class ThrowingController {
        @PostMapping("/lock-timeout")
        void lockTimeout() {
            throw new CannotAcquireLockException("Lock wait timeout exceeded; try restarting transaction");
        }

        @PostMapping("/deadlock")
        void deadlock() {
            throw new DeadlockLoserDataAccessException("Deadlock found when trying to get lock", null);
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void lockWaitTimeoutShouldBe409WithRetryAfterNot500() throws Exception {
        mockMvc.perform(post("/lock-timeout"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void deadlockVictimShouldBe409WithRetryAfterNot500() throws Exception {
        mockMvc.perform(post("/deadlock"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"));
    }
}
