package com.oAT.web.concurrency;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates short-lived user actions. Redis is the primary path; the local
 * fallback keeps the service usable if Redis is temporarily unavailable.
 */
@Service
public class MultiUserRequestCoordinator {
    private static final Logger log = LoggerFactory.getLogger(MultiUserRequestCoordinator.class);
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if count > tonumber(ARGV[2]) then return 0 end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Cache<String, Long> localIdempotency = Caffeine.newBuilder()
            .maximumSize(20_000).expireAfterAccess(Duration.ofMinutes(5)).build();
    private final Cache<String, LocalCounter> localCounters = Caffeine.newBuilder()
            .maximumSize(20_000).expireAfterAccess(Duration.ofMinutes(5)).build();

    public MultiUserRequestCoordinator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean acquireIdempotency(String key, Duration ttl) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", ttl));
        } catch (RuntimeException exception) {
            log.warn("event=redis.idempotency.fallback key_hash={} reason={}", key.hashCode(), exception.getMessage());
        }
        long now = System.nanoTime();
        long expiresAt = now + ttl.toNanos();
        AtomicBoolean acquired = new AtomicBoolean(false);
        localIdempotency.asMap().compute(key, (ignored, existingExpiresAt) -> {
            if (existingExpiresAt == null || existingExpiresAt <= now) {
                acquired.set(true);
                return expiresAt;
            }
            return existingExpiresAt;
        });
        return acquired.get();
    }

    public void releaseIdempotency(String key) {
        try {
            redis.delete(key);
            return;
        } catch (RuntimeException exception) {
            log.warn("event=redis.idempotency.release_failed key_hash={} reason={}", key.hashCode(), exception.getMessage());
        }
        localIdempotency.invalidate(key);
    }

    public boolean allow(String key, int limit, Duration window) {
        try {
            Long allowed = redis.execute(RATE_LIMIT_SCRIPT, Collections.singletonList(key),
                    String.valueOf(window.toMillis()), String.valueOf(limit));
            return Long.valueOf(1L).equals(allowed);
        } catch (RuntimeException exception) {
            log.warn("event=redis.rate_limit.fallback key_hash={} reason={}", key.hashCode(), exception.getMessage());
        }
        long now = System.nanoTime();
        long windowNanos = window.toNanos();
        AtomicBoolean allowed = new AtomicBoolean(false);
        localCounters.asMap().compute(key, (ignored, current) -> {
            if (current == null || current.expiresAtNanos() <= now) {
                allowed.set(true);
                return new LocalCounter(new AtomicInteger(1), now + windowNanos);
            }
            allowed.set(current.count().incrementAndGet() <= limit);
            return current;
        });
        return allowed.get();
    }

    private record LocalCounter(AtomicInteger count, long expiresAtNanos) { }
}
