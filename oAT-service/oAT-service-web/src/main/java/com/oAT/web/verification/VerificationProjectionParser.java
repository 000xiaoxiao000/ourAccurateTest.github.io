package com.oAT.web.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.oAT.web.common.UtilJson;
import com.oAT.web.verification.model.VerificationModels.AcceptanceCriterion;
import com.oAT.web.verification.model.VerificationModels.TestcaseProjection;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VerificationProjectionParser {
    private static final Pattern REQUIREMENT = Pattern.compile(
            "^(?:#{1,6}\\s*)?((?:REQ|FR|US)[-_A-Z0-9.]+|需求[-_A-Z0-9.]+)\\s*[:：\\-]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CRITERION = Pattern.compile(
            "^(?:[-*]\\s*)?((?:AC|CR)[-_A-Z0-9.]+|验收(?:标准)?\\s*\\d*|\\d+[.)、])\\s*[:：\\-]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSTRAINT = Pattern.compile(
            "(必须|应当|不得|不允许|至少|最多|仅当|不能|需要|should|must|shall|may not|at least|at most|\\d+)",
            Pattern.CASE_INSENSITIVE);

    public List<AcceptanceCriterion> parseRequirements(String baselineId, String content) {
        List<AcceptanceCriterion> result = new ArrayList<>();
        String requirementKey = "REQ-1";
        String requirementTitle = "导入需求";
        int acSequence = 1;
        String[] lines = normalize(content).split("\\n");
        for (int index = 0; index < lines.length; index++) {
            String line = clean(lines[index]);
            if (!StringUtils.hasText(line)) continue;
            Matcher requirement = REQUIREMENT.matcher(line);
            if (requirement.matches()) {
                requirementKey = requirement.group(1).trim().toUpperCase(Locale.ROOT);
                requirementTitle = fallback(requirement.group(2), requirementKey);
                acSequence = 1;
                continue;
            }
            Matcher criterion = CRITERION.matcher(line);
            if (criterion.matches()) {
                String text = fallback(criterion.group(2), line);
                result.add(criterion(baselineId, requirementKey, normalizeKey(criterion.group(1), acSequence++),
                        requirementTitle, text, index + 1, 0.92));
            } else if (CONSTRAINT.matcher(line).find() && !isHeading(line)) {
                result.add(criterion(baselineId, requirementKey, "AC-" + acSequence++, requirementTitle,
                        line, index + 1, 0.68));
            }
        }
        if (result.isEmpty() && StringUtils.hasText(content)) {
            result.add(criterion(baselineId, requirementKey, "AC-1", requirementTitle,
                    truncate(normalize(content).trim(), 2000), 1, 0.35));
        }
        return result;
    }

    public List<TestcaseProjection> parseTestcases(String baselineId, String fileName, String content) {
        String normalized = normalize(content).trim();
        if (!StringUtils.hasText(normalized)) return List.of();
        if (normalized.startsWith("[") || normalized.startsWith("{")) {
            List<TestcaseProjection> json = parseJsonTestcases(baselineId, normalized);
            if (!json.isEmpty()) return json;
        }
        String first = normalized.lines().findFirst().orElse("");
        String delimiter = first.contains("\t") ? "\t" : first.contains(",") ? "," : "";
        if (StringUtils.hasText(delimiter)) {
            List<TestcaseProjection> tabular = parseDelimitedTestcases(baselineId, normalized, delimiter);
            if (!tabular.isEmpty()) return tabular;
        }
        return parseNarrativeTestcases(baselineId, normalized);
    }

    private List<TestcaseProjection> parseDelimitedTestcases(String baselineId, String content, String delimiter) {
        List<String> lines = content.lines().filter(StringUtils::hasText).toList();
        if (lines.size() < 2) return List.of();
        List<String> headers = splitRow(lines.get(0), delimiter).stream().map(this::canonicalHeader).toList();
        List<TestcaseProjection> result = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> values = splitRow(lines.get(i), delimiter);
            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < Math.min(headers.size(), values.size()); j++) row.put(headers.get(j), values.get(j).trim());
            String title = first(row, "title", "用例-" + i);
            result.add(new TestcaseProjection(UUID.randomUUID().toString(), baselineId,
                    first(row, "id", "TC-" + i), title, first(row, "precondition", ""),
                    first(row, "steps", ""), first(row, "data", ""), first(row, "expected", ""),
                    first(row, "requirement", ""), "row:" + (i + 1)));
        }
        return result;
    }

    private List<TestcaseProjection> parseJsonTestcases(String baselineId, String content) {
        try {
            JsonNode root = UtilJson.getObjectMapper().readTree(content);
            JsonNode array = root.isArray() ? root : root.path("testcases");
            if (!array.isArray()) return List.of();
            List<TestcaseProjection> result = new ArrayList<>();
            int index = 0;
            for (JsonNode node : array) {
                index++;
                result.add(new TestcaseProjection(UUID.randomUUID().toString(), baselineId,
                        text(node, List.of("id", "key", "caseId"), "TC-" + index),
                        text(node, List.of("title", "name"), "用例-" + index),
                        text(node, List.of("preconditions", "precondition"), ""),
                        text(node, List.of("steps", "actions"), ""), text(node, List.of("data", "testData"), ""),
                        text(node, List.of("expected", "expectedResult"), ""),
                        text(node, List.of("requirementRefs", "requirementId"), ""), "json:" + index));
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<TestcaseProjection> parseNarrativeTestcases(String baselineId, String content) {
        List<TestcaseProjection> result = new ArrayList<>();
        String[] blocks = content.split("\\n\\s*\\n");
        int index = 0;
        for (String block : blocks) {
            if (!StringUtils.hasText(block)) continue;
            index++;
            List<String> lines = block.lines().map(this::clean).filter(StringUtils::hasText).toList();
            if (lines.isEmpty()) continue;
            String key = lines.get(0).matches("(?i)^TC[-_A-Z0-9.]+.*")
                    ? lines.get(0).split("[ :：-]", 2)[0] : "TC-" + index;
            String expected = lines.stream().filter(v -> v.matches("(?i).*(预期|expected).*"))
                    .findFirst().orElse("");
            result.add(new TestcaseProjection(UUID.randomUUID().toString(), baselineId, key, lines.get(0), "",
                    String.join("\n", lines), "", expected, extractRequirementRefs(block), "block:" + index));
        }
        return result;
    }

    private AcceptanceCriterion criterion(String baselineId, String requirementKey, String acKey, String title,
                                          String content, int line, double confidence) {
        boolean ambiguity = content.contains("等") || content.contains("适当") || content.contains("尽快")
                || content.contains("合理") || content.contains("视情况");
        return new AcceptanceCriterion(UUID.randomUUID().toString(), baselineId, requirementKey, acKey, title,
                content, "line:" + line, priority(content), true, ambiguity, confidence);
    }

    private List<String> splitRow(String row, String delimiter) {
        if ("\t".equals(delimiter)) return Arrays.asList(row.split("\\t", -1));
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) { values.add(value.toString()); value.setLength(0); }
            else value.append(c);
        }
        values.add(value.toString());
        return values;
    }

    private String canonicalHeader(String header) {
        String value = header.trim().toLowerCase(Locale.ROOT);
        if (value.matches(".*(需求|requirement|prd).*")) return "requirement";
        if (value.matches(".*(编号|id|key).*")) return "id";
        if (value.matches(".*(标题|名称|title|name).*")) return "title";
        if (value.matches(".*(前置|precondition).*")) return "precondition";
        if (value.matches(".*(步骤|step|action).*")) return "steps";
        if (value.matches(".*(数据|data).*")) return "data";
        if (value.matches(".*(预期|expected).*")) return "expected";
        return value;
    }

    private String extractRequirementRefs(String value) {
        Matcher matcher = Pattern.compile("(?i)(REQ[-_A-Z0-9.]+|需求[-_A-Z0-9.]+)").matcher(value);
        List<String> refs = new ArrayList<>();
        while (matcher.find()) refs.add(matcher.group(1));
        return String.join(",", refs);
    }

    private String priority(String content) {
        String upper = content.toUpperCase(Locale.ROOT);
        if (upper.contains("P0") || upper.contains("必须") || upper.contains("不得")) return "HIGH";
        return "MEDIUM";
    }

    private String text(JsonNode node, List<String> keys, String fallback) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isMissingNode() && !value.isNull()) return value.isTextual() ? value.asText() : value.toString();
        }
        return fallback;
    }

    private String first(Map<String, String> row, String key, String fallback) {
        return StringUtils.hasText(row.get(key)) ? row.get(key) : fallback;
    }

    private String normalizeKey(String value, int sequence) {
        String cleaned = value.replaceAll("[.)、\\s]", "").toUpperCase(Locale.ROOT);
        return cleaned.startsWith("AC") || cleaned.startsWith("CR") ? cleaned : "AC-" + sequence;
    }

    private String clean(String line) { return line == null ? "" : line.trim(); }
    private String normalize(String value) { return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n'); }
    private String fallback(String value, String fallback) { return StringUtils.hasText(value) ? value.trim() : fallback; }
    private boolean isHeading(String value) { return value.startsWith("#") || value.length() < 5; }
    private String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
