package com.oAT.relay.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class RelayMetrics {
    private final AtomicLong receivedCount = new AtomicLong();
    private final AtomicLong enqueueSuccessCount = new AtomicLong();
    private final AtomicLong enqueueFailureCount = new AtomicLong();
    private final AtomicLong forwardSuccessCount = new AtomicLong();
    private final AtomicLong forwardFailureCount = new AtomicLong();
    private final AtomicLong deadLetterCount = new AtomicLong();

    public void markReceived() {
        receivedCount.incrementAndGet();
    }

    public void markEnqueueSuccess() {
        enqueueSuccessCount.incrementAndGet();
    }

    public void markEnqueueFailure() {
        enqueueFailureCount.incrementAndGet();
    }

    public void markForwardSuccess() {
        forwardSuccessCount.incrementAndGet();
    }

    public void markForwardFailure() {
        forwardFailureCount.incrementAndGet();
    }

    public void markDeadLetter() {
        deadLetterCount.incrementAndGet();
    }

    public long getReceivedCount() {
        return receivedCount.get();
    }

    public long getEnqueueSuccessCount() {
        return enqueueSuccessCount.get();
    }

    public long getEnqueueFailureCount() {
        return enqueueFailureCount.get();
    }

    public long getForwardSuccessCount() {
        return forwardSuccessCount.get();
    }

    public long getForwardFailureCount() {
        return forwardFailureCount.get();
    }

    public long getDeadLetterCount() {
        return deadLetterCount.get();
    }
}
