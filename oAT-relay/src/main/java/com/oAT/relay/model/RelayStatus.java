package com.oAT.relay.model;

public class RelayStatus {
    private final String targetBaseUrl;
    private final String forwardMode;
    private final int queueDepth;
    private final long receivedCount;
    private final long enqueueSuccessCount;
    private final long enqueueFailureCount;
    private final long forwardSuccessCount;
    private final long forwardFailureCount;
    private final long deadLetterCount;

    public RelayStatus(String targetBaseUrl, String forwardMode, int queueDepth, long receivedCount,
                       long enqueueSuccessCount, long enqueueFailureCount, long forwardSuccessCount,
                       long forwardFailureCount, long deadLetterCount) {
        this.targetBaseUrl = targetBaseUrl;
        this.forwardMode = forwardMode;
        this.queueDepth = queueDepth;
        this.receivedCount = receivedCount;
        this.enqueueSuccessCount = enqueueSuccessCount;
        this.enqueueFailureCount = enqueueFailureCount;
        this.forwardSuccessCount = forwardSuccessCount;
        this.forwardFailureCount = forwardFailureCount;
        this.deadLetterCount = deadLetterCount;
    }

    public String getTargetBaseUrl() {
        return targetBaseUrl;
    }

    public String getForwardMode() {
        return forwardMode;
    }

    public int getQueueDepth() {
        return queueDepth;
    }

    public long getReceivedCount() {
        return receivedCount;
    }

    public long getEnqueueSuccessCount() {
        return enqueueSuccessCount;
    }

    public long getEnqueueFailureCount() {
        return enqueueFailureCount;
    }

    public long getForwardSuccessCount() {
        return forwardSuccessCount;
    }

    public long getForwardFailureCount() {
        return forwardFailureCount;
    }

    public long getDeadLetterCount() {
        return deadLetterCount;
    }
}
