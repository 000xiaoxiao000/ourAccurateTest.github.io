package com.oAT.web.concurrency;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiUserRequestCoordinatorTest {

    @Test
    void localIdempotencyHonorsTtlAndRelease() throws Exception {
        MultiUserRequestCoordinator coordinator = new MultiUserRequestCoordinator(null);

        assertTrue(coordinator.acquireIdempotency("idem:test", Duration.ofMillis(30)));
        assertFalse(coordinator.acquireIdempotency("idem:test", Duration.ofMillis(30)));

        Thread.sleep(40);
        assertTrue(coordinator.acquireIdempotency("idem:test", Duration.ofMillis(30)));

        coordinator.releaseIdempotency("idem:test");
        assertTrue(coordinator.acquireIdempotency("idem:test", Duration.ofMillis(30)));
    }

    @Test
    void localRateLimitHonorsLimitAndWindow() throws Exception {
        MultiUserRequestCoordinator coordinator = new MultiUserRequestCoordinator(null);

        Duration window = Duration.ofMillis(100);
        assertTrue(coordinator.allow("rate:test", 2, window));
        assertTrue(coordinator.allow("rate:test", 2, window));
        assertFalse(coordinator.allow("rate:test", 2, window));

        Thread.sleep(150);
        assertTrue(coordinator.allow("rate:test", 2, window));
    }
}
