package com.oAT.web.coverage.universal;

import java.util.Locale;

public enum SourceType {
    JAVA, FRONTEND, CPP, GO, PYTHON;

    public static SourceType from(String value) {
        if (value == null || value.isBlank()) return JAVA;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("JS") || normalized.contains("TS") || normalized.contains("FRONT")) return FRONTEND;
        try {
            return SourceType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return JAVA;
        }
    }
}
