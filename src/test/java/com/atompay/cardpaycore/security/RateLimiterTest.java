package com.atompay.cardpaycore.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void shouldAllowUpToTheLimitWithinAWindow() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter rateLimiter = new RateLimiter(3, 1000, now::get);

        assertThat(rateLimiter.tryAcquire("actor-1")).isTrue();
        assertThat(rateLimiter.tryAcquire("actor-1")).isTrue();
        assertThat(rateLimiter.tryAcquire("actor-1")).isTrue();
        assertThat(rateLimiter.tryAcquire("actor-1")).isFalse();
    }

    @Test
    void shouldTrackDifferentActorsIndependently() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter rateLimiter = new RateLimiter(1, 1000, now::get);

        assertThat(rateLimiter.tryAcquire("actor-1")).isTrue();
        assertThat(rateLimiter.tryAcquire("actor-2")).isTrue();
        assertThat(rateLimiter.tryAcquire("actor-1")).isFalse();
        assertThat(rateLimiter.tryAcquire("actor-2")).isFalse();
    }

    @Test
    void shouldResetAfterTheWindowElapses() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter rateLimiter = new RateLimiter(1, 1000, now::get);

        assertThat(rateLimiter.tryAcquire("actor-1")).isTrue();
        assertThat(rateLimiter.tryAcquire("actor-1")).isFalse();

        now.set(1000);

        assertThat(rateLimiter.tryAcquire("actor-1")).isTrue();
    }

    @Test
    void millisUntilResetShouldCountDownWithinTheWindow() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter rateLimiter = new RateLimiter(1, 1000, now::get);

        rateLimiter.tryAcquire("actor-1");
        now.set(400);

        assertThat(rateLimiter.millisUntilReset("actor-1")).isEqualTo(600);
    }
}
