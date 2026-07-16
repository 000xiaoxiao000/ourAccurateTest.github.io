package com.oAT.web.verification;

import com.oAT.web.common.UtilJson;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.verification.model.VerificationModels.AssetSnapshot;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SourceAssetFilter {
    private static final String SOURCE_TREE_BEGIN = "// SOURCE_TREE_BEGIN";
    private static final String SOURCE_TREE_END = "// SOURCE_TREE_END";
    private static final String SOURCE_FILE_PREFIX = "// SOURCE_FILE:";
    private static final String FILE_PREFIX = "// FILE:";
    private static final String SNAPSHOT_ID_PREFIX = "// SNAPSHOT_ID:";

    private SourceAssetFilter() {
    }

    public static SourceProfile fromApp(AppVo app) {
        if (app == null) return SourceProfile.any();
        Map<String, Object> config = parseConfig(app.getLanguageConfig());
        return new SourceProfile(
                value(app.getId()),
                normalizeLanguage(app.getLanguage()),
                normalizePath(value(config.get("sourceRoot"))),
                normalizePath(value(config.get("packageRoot"))));
    }

    public static Map<String, Object> metadataForApp(AppVo app) {
        if (app == null) return Map.of();
        SourceProfile profile = fromApp(app);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appId", app.getId());
        result.put("appLanguage", profile.language());
        if (StringUtils.hasText(profile.sourceRoot())) result.put("sourceRoot", profile.sourceRoot());
        if (StringUtils.hasText(profile.packageRoot())) result.put("packageRoot", profile.packageRoot());
        return result;
    }

    public static boolean assetMatchesApp(AssetSnapshot asset, SourceProfile profile) {
        if (asset == null || profile == null || !StringUtils.hasText(profile.appId())) return true;
        String assetAppId = metadataText(asset.metadata(), "appId");
        return !StringUtils.hasText(assetAppId) || profile.appId().equals(assetAppId);
    }

    public static String filterContent(String content, SourceProfile profile) {
        if (!StringUtils.hasText(content) || profile == null || profile.isAny()) return content;
        List<String> manifest = new ArrayList<>();
        List<SourceFile> files = new ArrayList<>();
        String snapshotId = "";
        String currentPath = null;
        StringBuilder currentContent = new StringBuilder();
        boolean hasStructuredMarkers = false;
        boolean inManifest = false;
        for (String line : content.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.equals(SOURCE_TREE_BEGIN)) {
                hasStructuredMarkers = true;
                inManifest = true;
                continue;
            }
            if (trimmed.equals(SOURCE_TREE_END)) {
                inManifest = false;
                continue;
            }
            if (trimmed.startsWith(SNAPSHOT_ID_PREFIX)) {
                snapshotId = line;
                continue;
            }
            if (inManifest && trimmed.startsWith(SOURCE_FILE_PREFIX)) {
                hasStructuredMarkers = true;
                String path = normalizePath(trimmed.substring(SOURCE_FILE_PREFIX.length()).trim());
                if (profile.matches(path)) manifest.add(path);
                continue;
            }
            if (trimmed.startsWith(FILE_PREFIX)) {
                hasStructuredMarkers = true;
                if (currentPath != null) {
                    addIfMatches(files, profile, currentPath, currentContent.toString());
                }
                currentPath = normalizePath(trimmed.substring(FILE_PREFIX.length()).trim());
                currentContent = new StringBuilder();
                continue;
            }
            if (currentPath != null) {
                currentContent.append(line).append('\n');
            }
        }
        if (currentPath != null) {
            addIfMatches(files, profile, currentPath, currentContent.toString());
        }
        if (!hasStructuredMarkers) return content;

        LinkedHashMap<String, Boolean> filePaths = new LinkedHashMap<>();
        manifest.forEach(path -> filePaths.put(path, Boolean.TRUE));
        files.forEach(file -> filePaths.put(file.path(), Boolean.TRUE));

        StringBuilder result = new StringBuilder();
        result.append(SOURCE_TREE_BEGIN).append('\n');
        filePaths.keySet().forEach(path -> result.append(SOURCE_FILE_PREFIX).append(' ').append(path).append('\n'));
        result.append(SOURCE_TREE_END).append('\n');
        files.forEach(file -> result.append("\n\n").append(FILE_PREFIX).append(' ').append(file.path()).append('\n').append(file.content()));
        if (StringUtils.hasText(snapshotId)) {
            result.append("\n\n").append(snapshotId);
        }
        return result.toString();
    }

    private static void addIfMatches(List<SourceFile> files, SourceProfile profile, String path, String content) {
        if (profile.matches(path)) {
            files.add(new SourceFile(path, content));
        }
    }

    private static Map<String, Object> parseConfig(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            Map<String, Object> parsed = UtilJson.toMap(json);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static String metadataText(Map<String, Object> metadata, String key) {
        if (metadata == null || !metadata.containsKey(key)) return "";
        return value(metadata.get(key));
    }

    private static String normalizeLanguage(String language) {
        return StringUtils.hasText(language) ? language.trim().toUpperCase(Locale.ROOT) : "JAVA";
    }

    private static String normalizePath(String value) {
        String path = value(value).replace('\\', '/').trim();
        while (path.startsWith("./")) path = path.substring(2);
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record SourceFile(String path, String content) {
    }

    public record SourceProfile(String appId, String language, String sourceRoot, String packageRoot) {
        private static final Set<String> JAVA_EXTENSIONS = Set.of(".java", ".kt", ".kts", ".scala", ".groovy");
        private static final Set<String> FRONTEND_EXTENSIONS = Set.of(".js", ".jsx", ".ts", ".tsx", ".vue", ".css", ".scss", ".less");
        private static final Set<String> PYTHON_EXTENSIONS = Set.of(".py");
        private static final Set<String> GO_EXTENSIONS = Set.of(".go");
        private static final Set<String> CPP_EXTENSIONS = Set.of(".c", ".cc", ".cpp", ".h", ".hpp");

        public static SourceProfile any() {
            return new SourceProfile("", "", "", "");
        }

        public boolean isAny() {
            return !StringUtils.hasText(language) && !StringUtils.hasText(sourceRoot) && !StringUtils.hasText(packageRoot);
        }

        public boolean matches(String rawPath) {
            String path = normalizePath(rawPath);
            if (!StringUtils.hasText(path) || !extensionAllowed(path)) return false;
            if (StringUtils.hasText(sourceRoot) && !path.equals(sourceRoot) && !path.startsWith(sourceRoot + "/") && !path.contains("/" + sourceRoot + "/")) {
                return false;
            }
            if (StringUtils.hasText(packageRoot)) {
                String packagePath = packageRoot.replace('.', '/');
                return path.contains("/" + packagePath + "/") || path.contains("/" + packagePath + ".") || path.startsWith(packagePath + "/");
            }
            return true;
        }

        private boolean extensionAllowed(String path) {
            String lower = path.toLowerCase(Locale.ROOT);
            Set<String> extensions = switch (language) {
                case "JAVA" -> JAVA_EXTENSIONS;
                case "FRONTEND" -> FRONTEND_EXTENSIONS;
                case "PYTHON" -> PYTHON_EXTENSIONS;
                case "GO" -> GO_EXTENSIONS;
                case "CPP" -> CPP_EXTENSIONS;
                default -> Set.of();
            };
            return extensions.isEmpty() || extensions.stream().anyMatch(lower::endsWith);
        }
    }
}
