package com.oAT.web.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AuditLogger {
    private static final Logger SECURITY = LoggerFactory.getLogger("audit.security");
    private static final Logger BUSINESS = LoggerFactory.getLogger("audit.business");
    private static final Logger PERFORMANCE = LoggerFactory.getLogger("monitor.performance");

    public void securitySuccess(String action, Map<String, ?> fields) {
        log(SECURITY, "security", action, "success", fields, false);
    }

    public void securityFailure(String action, Map<String, ?> fields) {
        log(SECURITY, "security", action, "failure", fields, true);
    }

    public void business(String action, Map<String, ?> fields) {
        log(BUSINESS, "business", action, "success", fields, false);
    }

    public void performance(String action, long durationMs, Map<String, ?> fields, boolean warn) {
        String formatted = LogFields.of(fields);
        if (warn) {
            PERFORMANCE.warn("event=performance action={} outcome=success duration_ms={} {}", action, durationMs, formatted);
        } else {
            PERFORMANCE.info("event=performance action={} outcome=success duration_ms={} {}", action, durationMs, formatted);
        }
    }

    private void log(Logger logger, String category, String action, String outcome, Map<String, ?> fields, boolean warn) {
        LogContext.putEvent(category + "." + action);
        try {
            String formatted = LogFields.of(fields);
            if (warn) {
                logger.warn("event=audit category={} action={} outcome={} {}", category, action, outcome, formatted);
            } else {
                logger.info("event=audit category={} action={} outcome={} {}", category, action, outcome, formatted);
            }
        } finally {
            LogContext.clearEvent();
        }
    }
}
