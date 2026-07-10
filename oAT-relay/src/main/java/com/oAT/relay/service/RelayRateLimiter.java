package com.oAT.relay.service;

import com.oAT.relay.config.RelayProperties;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RelayRateLimiter {
    private final RelayProperties properties;
    private final AtomicLong currentSecond = new AtomicLong(System.currentTimeMillis() / 1000);
    private final AtomicInteger counter = new AtomicInteger();

    public RelayRateLimiter(RelayProperties properties) {
        this.properties = properties;
    }

    public boolean tryAcquire() {
        if (!properties.getRateLimit().isEnabled()) {
            return true;
        }
        long nowSecond = System.currentTimeMillis() / 1000;
        long observedSecond = currentSecond.get();
        if (nowSecond != observedSecond && currentSecond.compareAndSet(observedSecond, nowSecond)) {
            counter.set(0);
        }
        return counter.incrementAndGet() <= Math.max(1, properties.getRateLimit().getMaxRequestsPerSecond());
    }
}
