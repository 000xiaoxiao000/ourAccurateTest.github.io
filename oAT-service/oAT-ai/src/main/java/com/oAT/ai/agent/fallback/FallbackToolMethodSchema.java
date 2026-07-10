package com.oAT.ai.agent.fallback;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class FallbackToolMethodSchema {
    private final String methodName;
    private final List<FallbackToolParameterSchema> parameters;

    public FallbackToolMethodSchema(String methodName, List<FallbackToolParameterSchema> parameters) {
        this.methodName = methodName;
        this.parameters = parameters;
    }

    public List<String> getCandidatesFor(String paramName, String annotatedName) {
        for (FallbackToolParameterSchema parameter : parameters) {
            boolean sameParamName = Objects.equals(parameter.paramName(), paramName);
            boolean sameAnnotatedName = Objects.equals(parameter.annotatedName(), annotatedName);
            if (sameParamName || sameAnnotatedName) {
                return parameter.candidates();
            }
        }
        return Collections.emptyList();
    }

    public boolean matchesTypeHint(String key, Class<?> targetType) {
        String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if ((normalizedKey.contains("time") || normalizedKey.contains("date"))
                && (targetType == LocalDate.class || targetType == LocalDateTime.class
                || targetType == Instant.class || targetType == OffsetDateTime.class
                || targetType == ZonedDateTime.class)) {
            return true;
        }
        if ((normalizedKey.contains("count") || normalizedKey.contains("limit") || normalizedKey.contains("size")
                || normalizedKey.contains("num"))
                && Number.class.isAssignableFrom(targetType)) {
            return true;
        }
        if ((normalizedKey.contains("flag") || normalizedKey.contains("enabled") || normalizedKey.contains("disable")
                || normalizedKey.contains("switch") || normalizedKey.contains("is"))
                && (targetType == boolean.class || targetType == Boolean.class)) {
            return true;
        }
        if ((normalizedKey.contains("list") || normalizedKey.contains("ids") || normalizedKey.contains("names"))
                && (targetType.isArray() || Collection.class.isAssignableFrom(targetType))) {
            return true;
        }
        return false;
    }

    public String describe() {
        List<String> parameterDescriptions = new ArrayList<>();
        for (FallbackToolParameterSchema parameter : parameters) {
            parameterDescriptions.add(parameter.paramName() + ":" + parameter.paramType().getSimpleName()
                    + " candidates=" + parameter.candidates());
        }
        return methodName + " -> " + String.join("; ", parameterDescriptions);
    }
}
