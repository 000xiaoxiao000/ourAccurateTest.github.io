package com.oAT.web.api.map;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
public class CodeSymbolNormalizer {
    public static final String DEFAULT_LANGUAGE = "java";

    public String fileId(String language, String path) {
        return "code:" + normalizeLanguage(language) + ":" + normalizePath(path);
    }

    public String classId(String language, String path, String className) {
        String normalizedPath = normalizePath(path);
        String symbol = StringUtils.hasText(className) ? normalizeClassName(className) : classNameFromPath(normalizedPath);
        return "code:" + normalizeLanguage(language) + ":" + normalizedPath + "#" + symbol;
    }

    public String methodId(String language, String path, String className, String methodName, String descriptor) {
        String normalizedPath = normalizePath(path);
        String owner = StringUtils.hasText(className) ? normalizeClassName(className) : classNameFromPath(normalizedPath);
        String method = StringUtils.hasText(methodName) ? methodName.trim() : "<unknown>";
        String desc = StringUtils.hasText(descriptor) ? descriptor.trim().replaceAll("\\s+", "") : "()";
        return "code:" + normalizeLanguage(language) + ":" + normalizedPath + "#" + owner + "." + method + desc;
    }

    public String normalizeLookupKey(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim()
                .replace("SOURCE_ASSET:", "")
                .replace('\\', '/')
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    public String normalizePath(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        String path = value.trim().replace('\\', '/').replaceAll("/{2,}", "/");
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    public String pathFromClassName(String className) {
        if (!StringUtils.hasText(className)) {
            return "src/main/java/Unknown.java";
        }
        String normalized = className.trim().replace('\\', '/');
        if (isPathLike(normalized)) {
            return normalizePath(normalized.endsWith(".java") ? normalized : normalized + ".java");
        }
        String outer = normalized.contains("$") ? normalized.substring(0, normalized.indexOf('$')) : normalized;
        return "src/main/java/" + outer.replace('.', '/') + ".java";
    }

    public String normalizeClassName(String className) {
        if (!StringUtils.hasText(className)) {
            return "Unknown";
        }
        String normalized = className.trim().replace('/', '.');
        if (normalized.endsWith(".java")) {
            normalized = normalized.substring(0, normalized.length() - 5);
        }
        int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        return normalized;
    }

    public String simpleFileName(String path) {
        String normalized = normalizePath(path);
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public String normalizeLanguage(String language) {
        return StringUtils.hasText(language) ? language.trim().toLowerCase(Locale.ROOT) : DEFAULT_LANGUAGE;
    }

    private String classNameFromPath(String path) {
        String normalized = normalizePath(path);
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private boolean isPathLike(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return value.contains("/") || lower.matches(".*\\.(java|kt|scala|groovy|js|jsx|ts|tsx|vue|py|go|rs|cs|php|rb)$");
    }
}
