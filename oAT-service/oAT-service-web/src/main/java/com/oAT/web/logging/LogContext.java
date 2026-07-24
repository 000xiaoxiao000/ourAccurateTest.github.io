package com.oAT.web.logging;

import com.oAT.web.service.entity.UserVo;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.regex.Pattern;

public final class LogContext {
    public static final String TRACE_ID = "traceId";
    public static final String REQUEST_ID = "requestId";
    public static final String USER_ID = "userId";
    public static final String USER_NAME = "userName";
    public static final String PROJECT_ID = "projectId";
    public static final String CLIENT_IP = "clientIp";
    public static final String HTTP_METHOD = "httpMethod";
    public static final String HTTP_PATH = "httpPath";
    public static final String EVENT = "event";

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String REQUEST_HEADER = "X-Request-Id";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{8,128}");

    private LogContext() {
    }

    public static String currentTraceId() {
        return MDC.get(TRACE_ID);
    }

    public static String ensureTraceId(HttpServletRequest request) {
        String traceId = firstValidId(request.getHeader(TRACE_HEADER), request.getHeader(REQUEST_HEADER));
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(TRACE_ID, traceId);
        return traceId;
    }

    public static String ensureRequestId(HttpServletRequest request) {
        String requestId = firstValidId(request.getHeader(REQUEST_HEADER), request.getHeader(TRACE_HEADER));
        if (!StringUtils.hasText(requestId)) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(REQUEST_ID, requestId);
        return requestId;
    }

    public static void putRequest(HttpServletRequest request) {
        put(HTTP_METHOD, request.getMethod());
        put(HTTP_PATH, request.getRequestURI());
        put(CLIENT_IP, clientIp(request));
        putProjectId(extractProjectId(request.getRequestURI()));
    }

    public static void putUser(UserVo user) {
        if (user == null) {
            return;
        }
        put(USER_ID, user.getId());
        put(USER_NAME, user.getName());
    }

    public static void putProjectId(String projectId) {
        put(PROJECT_ID, projectId);
    }

    public static void putEvent(String event) {
        put(EVENT, event);
    }

    public static void clearEvent() {
        MDC.remove(EVENT);
    }

    public static void put(String key, String value) {
        if (StringUtils.hasText(value)) {
            MDC.put(key, value);
        }
    }

    public static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            int comma = forwardedFor.indexOf(',');
            return comma >= 0 ? forwardedFor.substring(0, comma).trim() : forwardedFor.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? realIp.trim() : request.getRemoteAddr();
    }

    public static String extractProjectId(String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        String[] parts = path.split("/");
        if (parts.length > 3 && "api".equals(parts[1]) && "projects".equals(parts[2])) {
            return parts[3];
        }
        if (parts.length > 2 && "p".equals(parts[1])) {
            return parts[2];
        }
        return null;
    }

    private static String firstValidId(String first, String second) {
        String normalized = normalizeId(first);
        return StringUtils.hasText(normalized) ? normalized : normalizeId(second);
    }

    private static String normalizeId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return SAFE_ID.matcher(trimmed).matches() ? trimmed : null;
    }
}
