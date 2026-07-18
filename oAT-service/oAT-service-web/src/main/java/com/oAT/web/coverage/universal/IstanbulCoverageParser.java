package com.oAT.web.coverage.universal;

import com.fasterxml.jackson.databind.JsonNode;
import com.oAT.web.common.UtilJson;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class IstanbulCoverageParser implements CoverageParser {
    @Override
    public SourceType sourceType() {
        return SourceType.FRONTEND;
    }

    @Override
    public List<UniversalCoverageFile> parse(byte[] payload) {
        try {
            JsonNode root = UtilJson.getObjectMapper().readTree(new String(payload, StandardCharsets.UTF_8));
            List<UniversalCoverageFile> result = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.add(parseFile(field.getKey(), field.getValue()));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 Istanbul 覆盖率 JSON", e);
        }
    }

    private UniversalCoverageFile parseFile(String fallbackPath, JsonNode node) {
        String path = firstText(text(node.path("path")), fallbackPath);
        UniversalCoverageFile file = new UniversalCoverageFile(SourceType.FRONTEND, normalizeSourcePath(path));
        file.setLines(parseLines(node));
        file.setFunctions(parseFunctions(node));
        file.setBranches(parseBranches(node));
        return file;
    }

    private List<UniversalCoverageFile.LineCoverage> parseLines(JsonNode node) {
        JsonNode statementMap = node.path("statementMap");
        JsonNode hits = node.path("s");
        Map<Integer, UniversalCoverageFile.LineCoverage> result = new LinkedHashMap<>();
        Iterator<String> keys = statementMap.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            int line = statementMap.path(key).path("start").path("line").asInt(0);
            if (line <= 0) continue;
            int covered = hits.path(key).asInt(0);
            result.merge(line, new UniversalCoverageFile.LineCoverage(line, covered), UniversalCoverageFile.LineCoverage::merge);
        }
        return new ArrayList<>(result.values());
    }

    private List<UniversalCoverageFile.FunctionCoverage> parseFunctions(JsonNode node) {
        JsonNode functionMap = node.path("fnMap");
        JsonNode hits = node.path("f");
        List<UniversalCoverageFile.FunctionCoverage> result = new ArrayList<>();
        Iterator<String> keys = functionMap.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            JsonNode fn = functionMap.path(key);
            int start = fn.path("loc").path("start").path("line").asInt(0);
            int end = fn.path("loc").path("end").path("line").asInt(start);
            if (start <= 0) continue;
            result.add(new UniversalCoverageFile.FunctionCoverage(firstText(text(fn.path("name")), key), start, end, hits.path(key).asInt(0)));
        }
        return result;
    }

    private List<UniversalCoverageFile.BranchCoverage> parseBranches(JsonNode node) {
        JsonNode branchMap = node.path("branchMap");
        JsonNode hits = node.path("b");
        List<UniversalCoverageFile.BranchCoverage> result = new ArrayList<>();
        Iterator<String> keys = branchMap.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            JsonNode branch = branchMap.path(key);
            JsonNode locations = branch.path("locations");
            JsonNode hitValues = hits.path(key);
            int size = Math.max(locations.size(), hitValues.size());
            for (int i = 0; i < size; i++) {
                int line = resolveBranchLine(branch, locations.path(i), branch.path("loc").path("start").path("line").asInt(0));
                if (line <= 0) continue;
                result.add(new UniversalCoverageFile.BranchCoverage(line, i, hitValues.path(i).asInt(0), key));
            }
        }
        return result;
    }

    private int resolveBranchLine(JsonNode branch, JsonNode location, int fallback) {
        int line = location.path("start").path("line").asInt(0);
        if (line > 0) return line;
        line = branch.path("loc").path("start").path("line").asInt(0);
        return line > 0 ? line : fallback;
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }

    private String firstText(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private String normalizeSourcePath(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        int src = normalized.indexOf("/src/");
        if (src >= 0) return normalized.substring(src + 1);
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }
}
