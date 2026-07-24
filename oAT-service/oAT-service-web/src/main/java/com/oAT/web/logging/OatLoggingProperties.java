package com.oAT.web.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "oat.logging")
public class OatLoggingProperties {
    private final Request request = new Request();

    public Request getRequest() {
        return request;
    }

    public static class Request {
        private boolean enabled = true;
        private int successSampleRate = 10;
        private long slowThresholdMs = 1000;
        private long warnThresholdMs = 3000;
        private int maxFieldLength = 256;
        private List<String> excludedPathPrefixes = List.of(
                "/favicon", "/assets/", "/static/", "/webjars/", "/r/", "/images/");

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSuccessSampleRate() {
            return successSampleRate;
        }

        public void setSuccessSampleRate(int successSampleRate) {
            this.successSampleRate = Math.max(1, successSampleRate);
        }

        public long getSlowThresholdMs() {
            return slowThresholdMs;
        }

        public void setSlowThresholdMs(long slowThresholdMs) {
            this.slowThresholdMs = Math.max(0, slowThresholdMs);
        }

        public long getWarnThresholdMs() {
            return warnThresholdMs;
        }

        public void setWarnThresholdMs(long warnThresholdMs) {
            this.warnThresholdMs = Math.max(0, warnThresholdMs);
        }

        public int getMaxFieldLength() {
            return maxFieldLength;
        }

        public void setMaxFieldLength(int maxFieldLength) {
            this.maxFieldLength = Math.max(64, maxFieldLength);
        }

        public List<String> getExcludedPathPrefixes() {
            return excludedPathPrefixes;
        }

        public void setExcludedPathPrefixes(List<String> excludedPathPrefixes) {
            this.excludedPathPrefixes = excludedPathPrefixes == null ? List.of() : excludedPathPrefixes;
        }
    }
}
