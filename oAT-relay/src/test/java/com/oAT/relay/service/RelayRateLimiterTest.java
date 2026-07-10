package com.oAT.relay.service;

import com.oAT.relay.config.RelayProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayRateLimiterTest {

    @Test
    void shouldAllowWhenDisabled() {
        RelayProperties properties = new RelayProperties();
        properties.getRateLimit().setEnabled(false);
        RelayRateLimiter limiter = new RelayRateLimiter(properties);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
    }

    @Test
    void shouldRejectWhenSecondQuotaExceeded() {
        RelayProperties properties = new RelayProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setMaxRequestsPerSecond(1);
        RelayRateLimiter limiter = new RelayRateLimiter(properties);

        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }
}
