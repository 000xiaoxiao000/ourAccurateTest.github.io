package com.oAT.web.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.oAT.ai.service.LLMService;
import com.oAT.web.common.UtilJson;
import com.oAT.web.esDao.entity.StaticSourceInfo;
import com.oAT.web.verification.model.VerificationModels.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class VerificationAiOrchestrator {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int MAX_REQUIREMENT_CHARS = 18_000;
    private static final int MAX_TESTCASE_CHARS = 18_000;
    private static final int MAX_ASSET_CHARS = 14_000;
    private static final int MAX_SOURCE_CLASSES = 18;
    private static final int MAX_SOURCE_CLASS_CHARS = 2_400;

    /**
     * Runs the 5-stage analysis pipeline:
     *   Stage 1 — Requirement structuring & AC extraction
     *   Stage 2 — Candidate traceability (requirement ↔ testcase ↔ source)
     *   Stage 3 — Testcase sufficiency validation
     *   Stage 4 — Code implementation consistency check
     *   Stage 5 — Cross-validation (execution / coverage / defect evidence)
     *
     * Each stage uses a focused prompt with minimal context for that stage only.
     * The combined output is merged into a single AiVerificationResult.
     */

    private static final String SYSTEM_PROMPT = """
            你是一个严谨的软件需求一致性分析 AI。平台对需求文档、测试用例、Bug/缺陷证据、源代码、执行报告、覆盖率报告的语义处理全部由你完成。

            工作边界：
            1. 只能基于用户提供的资料做抽取、匹配、判断和建议，不能编造不存在的需求、用例、类、方法、Bug 或执行结果。
            2. 你负责完成以下 AI 任务：需求验收标准抽取、测试用例结构化、需求-用例-源码-执行/覆盖证据追溯、问题发现。
            3. 常规程序不会替你做关键词匹配、相似度匹配或规则判定；因此你的输出必须完整、可落库、可追溯。
            4. 不确定时降低 confidence，并输出 AMBIGUOUS_REQUIREMENT、NOT_VERIFIABLE、MISSING_EVIDENCE 等发现，不要硬判满足。
            5. 源码实现证据必须引用输入中真实存在的类名、方法名、文件片段或源码快照定位。
            6. 测试用例证据必须引用输入中真实存在的用例编号/标题/定位。
            7. Bug/缺陷资料如果出现在输入中，应作为反向验证证据：它可能证明需求未满足、用例遗漏、实现偏差或历史风险。
            8. 证据链不足时只能输出 STATICALLY_CONSISTENT 或 NOT_VERIFIABLE，不能输出 SATISFIED。
               SATISFIED 要求测试用例、实现证据、执行或覆盖率三类中至少两类有支撑。

            只允许输出严格 JSON，不要 Markdown，不要解释文本。JSON 结构必须如下：
            {
              "criteria": [
                {
                  "requirementKey": "REQ-1",
                  "acKey": "AC-1",
                  "title": "简短标题",
                  "content": "可验证的验收标准原文或改写",
                  "sourceLocator": "需求文档: 段落/行号/标题",
                  "priority": "HIGH|MEDIUM|LOW",
                  "testable": true,
                  "ambiguity": false,
                  "confidence": 0.0
                }
              ],
              "testcases": [
                {
                  "externalKey": "TC-1",
                  "title": "用例标题",
                  "preconditions": "前置条件",
                  "steps": "测试步骤",
                  "testData": "测试数据",
                  "expected": "预期结果",
                  "requirementRefs": "需求引用，没有则为空",
                  "sourceLocator": "用例文档: 段落/行号/标题"
                }
              ],
              "traceLinks": [
                {
                  "sourceKey": "AC-1",
                  "targetType": "TESTCASE|SOURCE_SYMBOL|EXECUTION|COVERAGE|DEFECT",
                  "targetKey": "TC-1 或真实类/方法/报告/缺陷编号",
                  "relationType": "VERIFIED_BY|IMPLEMENTED_BY|PROVEN_BY|COVERED_BY|AFFECTED_BY",
                  "confidence": 0.0,
                  "evidenceLevel": "E0|E1|E2|E3|E4",
                  "reviewStatus": "PENDING|CONFIRMED",
                  "evidence": {"reason":"为什么建立该关系","locator":"证据定位"}
                }
              ],
              "findings": [
                {
                  "acKey": "AC-1",
                  "findingType": "SATISFIED_SUMMARY|MISSING_TESTCASE|WEAK_ASSERTION|WRONG_EXPECTATION|MISSING_IMPLEMENTATION|LOGIC_DEVIATION|AMBIGUOUS_REQUIREMENT|TRACEABILITY_BREAK|MISSING_EVIDENCE|BUG_RISK|OTHER",
                  "perspective": "PRODUCT|TEST|DEVELOPMENT|CROSS",
                  "severity": "CRITICAL|HIGH|MEDIUM|LOW|INFO",
                  "title": "问题标题",
                  "description": "证据化说明",
                  "suggestion": "可执行建议",
                  "confidence": 0.0,
                  "evidenceLevel": "E0|E1|E2|E3|E4",
                  "verdict": "SATISFIED|STATICALLY_CONSISTENT|PARTIAL|NOT_SATISFIED|AMBIGUOUS|NOT_VERIFIABLE|STALE",
                  "evidence": [{"type":"REQUIREMENT|TESTCASE|SOURCE|EXECUTION|COVERAGE|DEFECT","id":"证据ID","locator":"证据定位","summary":"证据摘要"}]
                }
              ]
            }
            每一个 criteria 至少输出一条 findings。若该验收标准已满足，输出 findingType=SATISFIED_SUMMARY、severity=INFO、verdict=SATISFIED，并说明支撑证据。
            """;

    private final LLMService llmService;

    public VerificationAiOrchestrator(LLMService llmService) {
        this.llmService = llmService;
    }

    public AiVerificationResult analyze(AiVerificationInput input) {
        if (!llmService.isAvailable()) {
            throw new IllegalStateException("AI服务不可用，无法执行需求一致性分析");
        }
        String response = llmService.chat(SYSTEM_PROMPT, buildUserMessage(input));
        if (!StringUtils.hasText(response)) {
            throw new IllegalStateException("AI分析没有返回结果");
        }
        return parse(input.baselineId(), response);
    }

    private AiVerificationResult parse(String baselineId, String response) {
        try {
            JsonNode root = UtilJson.getObjectMapper().readTree(stripFence(response));
            List<AcceptanceCriterion> criteria = parseCriteria(baselineId, root.path("criteria"));
            if (criteria.isEmpty()) {
                throw new IllegalArgumentException("AI分析结果缺少验收标准");
            }
            List<TestcaseProjection> testcases = parseTestcases(baselineId, root.path("testcases"));
            Map<String, String> acIdByKey = new LinkedHashMap<>();
            for (AcceptanceCriterion criterion : criteria) {
                acIdByKey.put(normalKey(criterion.acKey()), criterion.id());
                acIdByKey.put(normalKey(criterion.requirementKey()), criterion.id());
            }
            Map<String, String> testcaseIdByKey = new LinkedHashMap<>();
            for (TestcaseProjection testcase : testcases) {
                testcaseIdByKey.put(normalKey(testcase.externalKey()), testcase.id());
                testcaseIdByKey.put(normalKey(testcase.title()), testcase.id());
            }
            List<TraceLink> traceLinks = parseTraceLinks(baselineId, root.path("traceLinks"), acIdByKey, testcaseIdByKey);
            List<Finding> findings = parseFindings(baselineId, root.path("findings"), acIdByKey);
            return new AiVerificationResult(criteria, testcases, traceLinks, findings);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AI分析返回格式不合法，请重试或检查模型配置", e);
        }
    }

    private List<AcceptanceCriterion> parseCriteria(String baselineId, JsonNode node) {
        if (!node.isArray()) return List.of();
        List<AcceptanceCriterion> result = new ArrayList<>();
        int index = 1;
        for (JsonNode item : node) {
            String content = text(item, "content");
            if (!StringUtils.hasText(content)) continue;
            String acKey = textOrDefault(item, "acKey", "AC-" + index);
            result.add(new AcceptanceCriterion(UUID.randomUUID().toString(), baselineId,
                    textOrDefault(item, "requirementKey", "REQ-" + index),
                    acKey,
                    truncate(textOrDefault(item, "title", acKey), 512),
                    truncate(content, 4000),
                    truncate(text(item, "sourceLocator"), 512),
                    normalizePriority(textOrDefault(item, "priority", "MEDIUM")),
                    !item.has("testable") || item.path("testable").asBoolean(true),
                    item.path("ambiguity").asBoolean(false),
                    confidence(item.path("confidence").asDouble(0.75))));
            index++;
        }
        return result;
    }

    private List<TestcaseProjection> parseTestcases(String baselineId, JsonNode node) {
        if (!node.isArray()) return List.of();
        List<TestcaseProjection> result = new ArrayList<>();
        int index = 1;
        for (JsonNode item : node) {
            String title = text(item, "title");
            String steps = text(item, "steps");
            String expected = text(item, "expected");
            if (!StringUtils.hasText(title) && !StringUtils.hasText(steps) && !StringUtils.hasText(expected)) continue;
            String externalKey = textOrDefault(item, "externalKey", "TC-" + index);
            result.add(new TestcaseProjection(UUID.randomUUID().toString(), baselineId,
                    truncate(externalKey, 128),
                    truncate(StringUtils.hasText(title) ? title : externalKey, 512),
                    truncate(text(item, "preconditions"), 2000),
                    truncate(steps, 4000),
                    truncate(text(item, "testData"), 2000),
                    truncate(expected, 4000),
                    truncate(text(item, "requirementRefs"), 1000),
                    truncate(text(item, "sourceLocator"), 512)));
            index++;
        }
        return result;
    }

    private List<TraceLink> parseTraceLinks(String baselineId, JsonNode node, Map<String, String> acIdByKey,
                                            Map<String, String> testcaseIdByKey) {
        if (!node.isArray()) return List.of();
        List<TraceLink> result = new ArrayList<>();
        for (JsonNode item : node) {
            String sourceId = acIdByKey.get(normalKey(text(item, "sourceKey")));
            if (!StringUtils.hasText(sourceId)) continue;
            String targetType = normalizeTargetType(textOrDefault(item, "targetType", "TESTCASE"));
            String targetKey = text(item, "targetKey");
            String targetId = "TESTCASE".equals(targetType)
                    ? testcaseIdByKey.getOrDefault(normalKey(targetKey), targetKey)
                    : targetKey;
            if (!StringUtils.hasText(targetId)) continue;
            result.add(new TraceLink(UUID.randomUUID().toString(), baselineId, "AC", sourceId,
                    targetType, truncate(targetId, 512),
                    normalizeRelationType(textOrDefault(item, "relationType", "VERIFIED_BY")),
                    "AI", confidence(item.path("confidence").asDouble(0.70)),
                    enumValue(EvidenceLevel.class, text(item, "evidenceLevel"), EvidenceLevel.E1),
                    enumValue(ReviewStatus.class, text(item, "reviewStatus"), ReviewStatus.PENDING),
                    objectMap(item.path("evidence"))));
        }
        return result;
    }

    private List<Finding> parseFindings(String baselineId, JsonNode node, Map<String, String> acIdByKey) {
        if (!node.isArray()) return List.of();
        List<Finding> result = new ArrayList<>();
        for (JsonNode item : node) {
            String title = text(item, "title");
            String description = text(item, "description");
            if (!StringUtils.hasText(title) && !StringUtils.hasText(description)) continue;
            result.add(new Finding(UUID.randomUUID().toString(), baselineId,
                    acIdByKey.get(normalKey(text(item, "acKey"))),
                    truncate(textOrDefault(item, "findingType", "OTHER").toUpperCase(Locale.ROOT), 64),
                    enumValue(Perspective.class, text(item, "perspective"), Perspective.CROSS),
                    enumValue(Severity.class, text(item, "severity"), Severity.MEDIUM),
                    truncate(StringUtils.hasText(title) ? title : textOrDefault(item, "findingType", "AI分析问题"), 512),
                    truncate(description, 4000),
                    truncate(text(item, "suggestion"), 4000),
                    confidence(item.path("confidence").asDouble(0.70)),
                    enumValue(EvidenceLevel.class, text(item, "evidenceLevel"), EvidenceLevel.E1),
                    enumValue(Verdict.class, text(item, "verdict"), Verdict.PARTIAL),
                    ReviewStatus.PENDING,
                    objectList(item.path("evidence")), null, null, null));
        }
        return result;
    }

    private String buildUserMessage(AiVerificationInput input) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("分析基线ID: ").append(input.baselineId()).append('\n');
        prompt.append("资料说明: 以下内容均为真实输入。请你完成结构化抽取、双向追溯和问题判断。\n\n");
        appendSection(prompt, "需求文档", input.requirementContent(), MAX_REQUIREMENT_CHARS);
        appendSection(prompt, "测试用例", input.testcaseContent(), MAX_TESTCASE_CHARS);
        appendSection(prompt, "Bug/缺陷资料", input.defectContent(), MAX_ASSET_CHARS);
        appendSection(prompt, "源码快照", input.sourceAssetContent(), MAX_ASSET_CHARS);
        appendSection(prompt, "执行报告", input.executionContent(), MAX_ASSET_CHARS);
        appendSection(prompt, "覆盖率报告", input.coverageContent(), MAX_ASSET_CHARS);
        prompt.append("【静态源码索引】\n");
        if (input.staticSources() == null || input.staticSources().isEmpty()) {
            prompt.append("(无静态源码索引)\n");
        } else {
            int count = 0;
            for (StaticSourceInfo source : input.staticSources()) {
                if (source == null || source.getClassInfo() == null) continue;
                if (count++ >= MAX_SOURCE_CLASSES) break;
                String className = source.getClassInfo().getClassName();
                prompt.append("类: ").append(value(className)).append('\n');
                if (source.getClassInfo().getMethodMaps() != null && !source.getClassInfo().getMethodMaps().isEmpty()) {
                    prompt.append("方法: ").append(source.getClassInfo().getMethodMaps().keySet()).append('\n');
                }
                prompt.append(truncate(value(source.getClassInfo().getSourceCode()), MAX_SOURCE_CLASS_CHARS)).append("\n\n");
            }
        }
        prompt.append("""

                输出要求：
                - 必须返回一个 JSON 对象。
                - criteria 是从需求文档中抽取的可验证验收标准。
                - testcases 是从测试用例资料中抽取的结构化用例。
                - traceLinks 必须表达需求、用例、源码、执行、覆盖率、缺陷之间的追溯关系。
                - findings 必须指出缺失、偏差、弱断言、无法验证、Bug风险等问题。
                - 如果资料不足，请输出 NOT_VERIFIABLE 或 MISSING_EVIDENCE，而不是假设已满足。
                """);
        return prompt.toString();
    }

    private void appendSection(StringBuilder prompt, String title, String content, int maxChars) {
        prompt.append("【").append(title).append("】\n");
        if (StringUtils.hasText(content)) {
            prompt.append(truncate(content, maxChars)).append("\n\n");
        } else {
            prompt.append("(未提供)\n\n");
        }
    }

    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return Map.of();
        if (!node.isObject()) return Map.of("value", node.asText(""));
        return UtilJson.getObjectMapper().convertValue(node, MAP_TYPE);
    }

    private List<Map<String, Object>> objectList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) return List.of(objectMap(node));
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : node) result.add(objectMap(item));
        return result;
    }

    private String stripFence(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            int firstLine = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (firstLine > 0 && end > firstLine) return trimmed.substring(firstLine + 1, end).trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        return start >= 0 && end > start ? trimmed.substring(start, end + 1) : trimmed;
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value, T defaultValue) {
        if (!StringUtils.hasText(value)) return defaultValue;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String normalizePriority(String value) {
        String normalized = value(value).trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CRITICAL", "P0", "P1", "HIGH" -> "HIGH";
            case "LOW", "P3", "P4" -> "LOW";
            default -> "MEDIUM";
        };
    }

    private String normalizeTargetType(String value) {
        String normalized = value(value).trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "TEST", "TEST_CASE", "TESTCASE" -> "TESTCASE";
            case "SOURCE", "CODE", "CLASS", "METHOD", "SOURCE_SYMBOL" -> "SOURCE_SYMBOL";
            case "EXECUTION", "EXECUTION_REPORT" -> "EXECUTION";
            case "COVERAGE", "COVERAGE_REPORT" -> "COVERAGE";
            case "DEFECT", "BUG" -> "DEFECT";
            default -> "TESTCASE";
        };
    }

    private String normalizeRelationType(String value) {
        String normalized = value(value).trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "IMPLEMENTED_BY", "VERIFIED_BY", "PROVEN_BY", "COVERED_BY", "AFFECTED_BY" -> normalized;
            default -> "VERIFIED_BY";
        };
    }

    private double confidence(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private String text(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (value.isMissingNode() || value.isNull()) return "";
        return value.isTextual() ? value.asText().trim() : value.toString();
    }

    private String textOrDefault(JsonNode item, String field, String defaultValue) {
        String value = text(item, field);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private String normalKey(String value) {
        return value(value).trim().toLowerCase(Locale.ROOT);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int max) {
        String safe = value(value);
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    public record AiVerificationInput(
            String baselineId,
            String requirementContent,
            String testcaseContent,
            String defectContent,
            String sourceAssetContent,
            String executionContent,
            String coverageContent,
            List<StaticSourceInfo> staticSources) {
    }

    public record AiVerificationResult(
            List<AcceptanceCriterion> criteria,
            List<TestcaseProjection> testcases,
            List<TraceLink> traceLinks,
            List<Finding> findings) {
    }
}
