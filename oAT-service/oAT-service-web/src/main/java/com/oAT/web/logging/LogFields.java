package com.oAT.web.logging;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class LogFields {
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Set<String> SENSITIVE_TOKENS = Set.of(
            "password", "passwd", "secret", "token", "credential", "authorization", "cookie", "session", "apikey", "api_key", "key");
    private static final int DEFAULT_MAX_VALUE_LENGTH = 256;

    private LogFields() {
    }

    public static String of(Map<String, ?> fields) {
        return of(fields, DEFAULT_MAX_VALUE_LENGTH);
    }

    public static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (keyValues == null) {
            return result;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key != null) {
                result.put(String.valueOf(key), keyValues[i + 1]);
            }
        }
        return result;
    }

    public static String of(Map<String, ?> fields, int maxValueLength) {
        if (fields == null || fields.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        fields.forEach((key, value) -> {
            if (!StringUtils.hasText(key)) {
                return;
            }
            String safeKey = safeKey(key);
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(safeKey).append('=').append(safeValue(safeKey, value, maxValueLength));
        });
        return builder.toString();
    }

    public static String safeValue(String key, Object value) {
        return safeValue(key, value, DEFAULT_MAX_VALUE_LENGTH);
    }

    public static String safeValue(String key, Object value, int maxValueLength) {
        if (isSensitive(key)) {
            return "\"***\"";
        }
        String text = value == null ? "" : String.valueOf(value);
        text = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (text.length() > maxValueLength) {
            text = text.substring(0, maxValueLength) + "...";
        }
        return quote(text);
    }

    private static String safeKey(String key) {
        String trimmed = key.trim();
        return SAFE_KEY.matcher(trimmed).matches() ? trimmed : trimmed.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase().replace("-", "_");
        return SENSITIVE_TOKENS.stream().anyMatch(normalized::contains);
    }
}
