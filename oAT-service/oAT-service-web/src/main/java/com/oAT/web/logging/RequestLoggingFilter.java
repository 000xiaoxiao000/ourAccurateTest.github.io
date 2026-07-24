package com.oAT.web.logging;

import com.oAT.web.service.entity.UserVo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private final OatLoggingProperties properties;
    private final AtomicLong successCounter = new AtomicLong();

    public RequestLoggingFilter(OatLoggingProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.getRequest().isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return properties.getRequest().getExcludedPathPrefixes().stream()
                .anyMatch(prefix -> StringUtils.hasText(prefix) && path != null && path.startsWith(prefix));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        long startNanos = System.nanoTime();
        LogContext.ensureTraceId(request);
        LogContext.ensureRequestId(request);
        LogContext.putRequest(request);
        HttpSession session = request.getSession(false);
        LogContext.putUser(session == null ? null : (UserVo) session.getAttribute("user"));
        response.setHeader(LogContext.TRACE_HEADER, LogContext.currentTraceId());
        response.setHeader(LogContext.REQUEST_HEADER, MDC.get(LogContext.REQUEST_ID));

        if (log.isDebugEnabled()) {
            log.debug("event=http.request.start {}", LogFields.of(baseFields(request, 0, null),
                    properties.getRequest().getMaxFieldLength()));
        }

        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            failure = ex;
            throw ex;
        } catch (Error error) {
            failure = error;
            throw error;
        } finally {
            logCompletion(request, response, startNanos, failure);
            if (previousContext == null || previousContext.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousContext);
            }
        }
    }

    private void logCompletion(HttpServletRequest request, HttpServletResponse response, long startNanos, Throwable failure) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        int status = response.getStatus();
        Map<String, Object> fields = baseFields(request, durationMs, status);
        fields.put("success", failure == null && status < 500);
        if (failure != null) {
            fields.put("exception", failure.getClass().getSimpleName());
        }

        if (!shouldLog(status, durationMs, request.getMethod(), failure)) {
            return;
        }

        String formatted = LogFields.of(fields, properties.getRequest().getMaxFieldLength());
        if (failure != null || status >= 500) {
            log.error("event=http.request.completed {}", formatted, failure);
        } else if (status >= 400 || durationMs >= properties.getRequest().getWarnThresholdMs()) {
            log.warn("event=http.request.completed {}", formatted);
        } else {
            log.info("event=http.request.completed {}", formatted);
        }
    }

    private boolean shouldLog(int status, long durationMs, String method, Throwable failure) {
        if (failure != null || status >= 400) {
            return true;
        }
        if (durationMs >= properties.getRequest().getSlowThresholdMs()) {
            return true;
        }
        if (isMutating(method)) {
            return true;
        }
        int sampleRate = properties.getRequest().getSuccessSampleRate();
        return sampleRate <= 1 || successCounter.incrementAndGet() % sampleRate == 0;
    }

    private boolean isMutating(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    private Map<String, Object> baseFields(HttpServletRequest request, long durationMs, Integer status) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("method", request.getMethod());
        fields.put("path", request.getRequestURI());
        if (status != null) {
            fields.put("status", status);
        }
        if (durationMs >= 0) {
            fields.put("duration_ms", durationMs);
        }
        fields.put("client_ip", LogContext.clientIp(request));
        fields.put("user_agent", request.getHeader("User-Agent"));
        fields.put("trace_id", MDC.get(LogContext.TRACE_ID));
        fields.put("request_id", MDC.get(LogContext.REQUEST_ID));
        fields.put("user_id", MDC.get(LogContext.USER_ID));
        fields.put("project_id", MDC.get(LogContext.PROJECT_ID));
        return fields;
    }
}
