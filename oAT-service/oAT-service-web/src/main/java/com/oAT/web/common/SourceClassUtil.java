package com.oAT.web.common;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SourceClassUtil {

    private SourceClassUtil() {
    }

    public static List<String> buildSourceClassCandidates(String className) {
        if (!StringUtils.hasText(className)) {
            return Collections.emptyList();
        }
        if (isPathLikeName(className)) {
            return Collections.singletonList(normalizePathName(className));
        }

        String normalizedClassName = className.replace('$', '.');
        String[] segments = normalizedClassName.split("\\.");
        if (segments.length == 0) {
            return Collections.emptyList();
        }

        int firstTypeSegment = findFirstTypeSegmentIndex(segments);
        List<String> candidates = new ArrayList<>();
        int endIndex = firstTypeSegment >= 0 ? firstTypeSegment : 0;
        for (int idx = segments.length - 1; idx >= endIndex; idx--) {
            String candidate = String.join(".", java.util.Arrays.copyOfRange(segments, 0, idx + 1));
            if (StringUtils.hasText(candidate) && !candidates.contains(candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    public static List<String> buildSourcePathCandidates(String className) {
        List<String> candidates = new ArrayList<>();
        if (isPathLikeName(className)) {
            String normalizedPath = normalizePathName(className);
            addCandidate(candidates, normalizedPath);
            String withoutLeadingSlash = normalizedPath.startsWith("/") ? normalizedPath.substring(1) : normalizedPath;
            addCandidate(candidates, withoutLeadingSlash);
            addPathSuffixCandidates(candidates, withoutLeadingSlash);
            if (!hasKnownSourceExtension(normalizedPath)) {
                String[] extensions = {".js", ".jsx", ".ts", ".tsx", ".vue", ".css", ".scss", ".less"};
                for (String extension : extensions) {
                    addCandidate(candidates, normalizedPath + extension);
                    addCandidate(candidates, withoutLeadingSlash + extension);
                    addPathSuffixCandidates(candidates, withoutLeadingSlash + extension);
                }
            }
            return candidates;
        }
        for (String candidateClassName : buildSourceClassCandidates(className)) {
            candidates.add(candidateClassName.replace('.', '/') + ".java");
        }
        return candidates;
    }

    public static String resolveSourceOwnerClassName(String className) {
        if (!StringUtils.hasText(className)) {
            return className;
        }
        // Only apply inner-class collapsing when the original name contains '$'.
        // A plain dot-separated name like "controller.Workflow.SomeClass" means
        // Workflow is a package directory, not an outer class, so we must NOT
        // strip SomeClass off and return "controller.Workflow" as if it were a class.
        if (!className.contains("$")) {
            return className;
        }
        List<String> candidates = buildSourceClassCandidates(className);
        if (candidates.isEmpty()) {
            return className;
        }
        return candidates.get(candidates.size() - 1);
    }

    public static String toTreeDisplayName(String segment, String nodeType) {
        if (!StringUtils.hasText(segment)) {
            return segment;
        }
        if (!"class".equals(nodeType)) {
            return segment;
        }
        if (isAllDigits(segment)) {
            return "匿名类#" + segment;
        }

        int splitIndex = 0;
        while (splitIndex < segment.length() && Character.isDigit(segment.charAt(splitIndex))) {
            splitIndex++;
        }
        if (splitIndex > 0 && splitIndex < segment.length()) {
            return "局部类 " + segment.substring(splitIndex) + " (#" + segment.substring(0, splitIndex) + ")";
        }
        return segment;
    }

    public static int findFirstTypeSegmentIndex(String[] segments) {
        for (int i = 0; i < segments.length; i++) {
            if (isLikelyTypeSegment(segments[i])) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isPathLikeName(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || hasKnownSourceExtension(value);
    }

    public static String normalizePathName(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String normalized = value.replace('\\', '/');
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        return normalized;
    }

    public static String displayFileName(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String normalized = normalizePathName(value);
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    public static boolean isLikelyTypeSegment(String segment) {
        if (!StringUtils.hasText(segment)) {
            return false;
        }
        char firstChar = segment.charAt(0);
        return Character.isUpperCase(firstChar) || Character.isDigit(firstChar);
    }

    private static void addCandidate(List<String> candidates, String candidate) {
        if (StringUtils.hasText(candidate) && !candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    private static void addPathSuffixCandidates(List<String> candidates, String path) {
        String[] markers = {
                "src/",
                "src/main/",
                "src/main/java/",
                "src/main/resources/",
                "src/test/",
                "src/test/java/",
                "app/",
                "pages/",
                "components/",
                "lib/"
        };
        for (String marker : markers) {
            int index = path.lastIndexOf("/" + marker);
            if (index >= 0) {
                addCandidate(candidates, path.substring(index + 1));
            }
            if (path.startsWith(marker)) {
                addCandidate(candidates, path);
            }
        }
    }

    public static boolean hasKnownSourceExtension(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        String[] extensions = {".java", ".js", ".jsx", ".ts", ".tsx", ".vue", ".css", ".scss", ".less"};
        for (String extension : extensions) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllDigits(String segment) {
        for (int i = 0; i < segment.length(); i++) {
            if (!Character.isDigit(segment.charAt(i))) {
                return false;
            }
        }
        return segment.length() > 0;
    }
}
