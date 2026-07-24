package com.oAT.web.concurrency;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiUserRequestCoordinatorTest {

    @Test
    void localIdempotencyHonorsTtlAndRelease() throws Exception {
        MultiUserRequestCoordinator coordinator = new MultiUserRequestCoordinator(null, false);

        assertTrue(coordinator.acquireIdempotency("idem:test", Duration.ofMillis(30)));
        assertFalse(coordinator.acquireIdempotency("idem:test", Duration.ofMillis(30)));

        Thread.sleep(40);
        assertTrue(coordinator.acquireIdempotency("idem:test", Duration.ofMillis(30)));

        coordinator.releaseIdempotency("idem:test");
        assertTrue(coordinator.acquireIdempotency("idem:test", Duration.ofMillis(30)));
    }

    @Test
    void localRateLimitHonorsLimitAndWindow() throws Exception {
        MultiUserRequestCoordinator coordinator = new MultiUserRequestCoordinator(null, false);

        assertTrue(coordinator.allow("rate:test", 2, Duration.ofMillis(30)));
        assertTrue(coordinator.allow("rate:test", 2, Duration.ofMillis(30)));
        assertFalse(coordinator.allow("rate:test", 2, Duration.ofMillis(30)));

        Thread.sleep(40);
        assertTrue(coordinator.allow("rate:test", 2, Duration.ofMillis(30)));
    }
}
