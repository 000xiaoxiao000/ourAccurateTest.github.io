package com.oAT.relay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oat.relay")
public class RelayProperties {
    private String targetBaseUrl = "http://127.0.0.1:8899";
    private ForwardMode forwardMode = ForwardMode.HYBRID;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 10000;
    private int maxBodySizeMb = 20;
    private String authToken = "";
    private final Queue queue = new Queue();
    private final Retry retry = new Retry();
    private final RateLimit rateLimit = new RateLimit();

    public enum ForwardMode {
        SYNC,
        ASYNC,
        HYBRID
    }

    public enum QueueFullPolicy {
        FAIL,
        REJECT,
        SYNC_FALLBACK
    }

    public String getTargetBaseUrl() {
        return targetBaseUrl;
    }

    public void setTargetBaseUrl(String targetBaseUrl) {
        this.targetBaseUrl = trimTrailingSlash(targetBaseUrl);
    }

    public ForwardMode getForwardMode() {
        return forwardMode;
    }

    public void setForwardMode(ForwardMode forwardMode) {
        this.forwardMode = forwardMode;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getMaxBodySizeMb() {
        return maxBodySizeMb;
    }

    public void setMaxBodySizeMb(int maxBodySizeMb) {
        this.maxBodySizeMb = maxBodySizeMb;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public Queue getQueue() {
        return queue;
    }

    public Retry getRetry() {
        return retry;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public static class Queue {
        private boolean enabled = true;
        private int capacity = 10000;
        private int workers = 4;
        private int offerTimeoutMs = 100;
        private QueueFullPolicy fullPolicy = QueueFullPolicy.SYNC_FALLBACK;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getWorkers() {
            return workers;
        }

        public void setWorkers(int workers) {
            this.workers = workers;
        }

        public int getOfferTimeoutMs() {
            return offerTimeoutMs;
        }

        public void setOfferTimeoutMs(int offerTimeoutMs) {
            this.offerTimeoutMs = offerTimeoutMs;
        }

        public QueueFullPolicy getFullPolicy() {
            return fullPolicy;
        }

        public void setFullPolicy(QueueFullPolicy fullPolicy) {
            this.fullPolicy = fullPolicy;
        }
    }

    public static class RateLimit {
        private boolean enabled = false;
        private int maxRequestsPerSecond = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxRequestsPerSecond() {
            return maxRequestsPerSecond;
        }

        public void setMaxRequestsPerSecond(int maxRequestsPerSecond) {
            this.maxRequestsPerSecond = maxRequestsPerSecond;
        }
    }

    public static class Retry {
        private int maxAttempts = 5;
        private long initialBackoffMs = 500;
        private long maxBackoffMs = 30000;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
        }
    }
}
