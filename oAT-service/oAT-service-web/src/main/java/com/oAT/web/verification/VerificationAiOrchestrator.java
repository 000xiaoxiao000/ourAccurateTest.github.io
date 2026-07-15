package com.oAT.web.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.oAT.ai.service.LLMService;
import com.oAT.web.common.UtilJson;
import com.oAT.web.esDao.entity.StaticSourceInfo;
import com.oAT.web.verification.model.VerificationModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class VerificationAiOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(VerificationAiOrchestrator.class);
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
        return analyze(input, ignored -> { });
    }

    public AiVerificationResult analyze(AiVerificationInput input, Consumer<String> progress) {
        if (!llmService.isAvailable()) {
            throw new IllegalStateException("AI服务不可用，无法执行需求一致性分析");
        }
        progress.accept("正在请求 AI 生成需求、用例和证据关系");
        String response = llmService.chat(SYSTEM_PROMPT, buildUserMessage(input));
        if (!StringUtils.hasText(response)) {
            throw new IllegalStateException("AI分析没有返回结果");
        }
        try {
            progress.accept("AI 已返回内容，正在校验分析结果");
            return parse(input.baselineId(), response);
        } catch (RuntimeException firstFailure) {
            progress.accept("AI 返回格式不完整，正在自动修复并重试");
            String repaired = llmService.chat(SYSTEM_PROMPT, buildRepairMessage(response));
            if (!StringUtils.hasText(repaired)) throw firstFailure;
            try {
                progress.accept("正在校验修复后的分析结果");
                return parse(input.baselineId(), repaired);
            } catch (RuntimeException repairFailure) {
                repairFailure.addSuppressed(firstFailure);
                throw repairFailure;
            }
        }
    }

    private String buildRepairMessage(String response) {
        return """
                下面是一次 AI 分析的原始返回，但它不是可直接解析的完整 JSON。
                请从中保留有效分析内容，修复截断、Markdown 包裹、解释文本、字段格式问题，重新输出完整 JSON。
                只能输出 JSON 对象，不能输出 Markdown、代码围栏或任何解释文本。
                如果原始结果没有有效内容，仍需返回 {\"criteria\":[],\"testcases\":[],\"traceLinks\":[],\"findings\":[]}。

                原始返回：
                """ + value(response);
    }

    private AiVerificationResult parse(String baselineId, String response) {
        try {
            JsonNode root = normalizeResponseRoot(UtilJson.getObjectMapper().readTree(extractJsonObject(response)));
            List<AcceptanceCriterion> criteria = parseCriteria(baselineId, firstArray(root, "criteria", "acceptanceCriteria", "acceptance_criteria", "acs"));
            if (criteria.isEmpty()) {
                throw new IllegalArgumentException("AI分析结果缺少验收标准");
            }
            List<TestcaseProjection> testcases = parseTestcases(baselineId, firstArray(root, "testcases", "testCases", "test_cases", "cases"));
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
            List<TraceLink> traceLinks = parseTraceLinks(baselineId,
                    firstArray(root, "traceLinks", "trace_links", "links", "traceability"), acIdByKey, testcaseIdByKey);
            List<Finding> findings = parseFindings(baselineId,
                    firstArray(root, "findings", "issues", "problems", "risks"), acIdByKey);
            findings = ensureFindings(baselineId, criteria, traceLinks, findings);
            return new AiVerificationResult(criteria, testcases, traceLinks, findings);
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) throw e;
            logger.warn("无法解析 AI 需求验证结果: {}, 原因: {}", responseSummary(response), e.toString());
            throw new IllegalStateException("AI分析返回格式不合法，请重试或检查模型配置", e);
        } catch (Exception e) {
            logger.warn("无法解析 AI 需求验证结果: {}, 原因: {}", responseSummary(response), e.toString());
            throw new IllegalStateException("AI分析返回格式不合法，请重试或检查模型配置", e);
        }
    }

    private List<AcceptanceCriterion> parseCriteria(String baselineId, JsonNode node) {
        if (!node.isArray()) return List.of();
        List<AcceptanceCriterion> result = new ArrayList<>();
        int index = 1;
        for (JsonNode item : node) {
            String content = text(item, "content", "description", "text", "acceptanceCriterion");
            if (!StringUtils.hasText(content)) continue;
            String acKey = textOrDefault(item, "AC-" + index, "acKey", "acId", "key", "id");
            result.add(new AcceptanceCriterion(UUID.randomUUID().toString(), baselineId,
                    textOrDefault(item, "REQ-" + index, "requirementKey", "requirementId", "requirementRef", "reqKey"),
                    acKey,
                    truncate(textOrDefault(item, acKey, "title", "name"), 512),
                    truncate(content, 4000),
                    truncate(text(item, "sourceLocator", "locator", "source"), 512),
                    normalizePriority(textOrDefault(item, "MEDIUM", "priority")),
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
            String title = text(item, "title", "name");
            String steps = text(item, "steps", "step", "actions");
            String expected = text(item, "expected", "expectation", "expectedResult");
            if (!StringUtils.hasText(title) && !StringUtils.hasText(steps) && !StringUtils.hasText(expected)) continue;
            String externalKey = textOrDefault(item, "TC-" + index, "externalKey", "caseKey", "testcaseKey", "id", "key");
            result.add(new TestcaseProjection(UUID.randomUUID().toString(), baselineId,
                    truncate(externalKey, 128),
                    truncate(StringUtils.hasText(title) ? title : externalKey, 512),
                    truncate(text(item, "preconditions", "precondition"), 2000),
                    truncate(steps, 4000),
                    truncate(text(item, "testData", "data"), 2000),
                    truncate(expected, 4000),
                    truncate(text(item, "requirementRefs", "requirementRef", "requirementKey"), 1000),
                    truncate(text(item, "sourceLocator", "locator", "source"), 512)));
            index++;
        }
        return result;
    }

    private List<TraceLink> parseTraceLinks(String baselineId, JsonNode node, Map<String, String> acIdByKey,
                                            Map<String, String> testcaseIdByKey) {
        if (!node.isArray()) return List.of();
        List<TraceLink> result = new ArrayList<>();
        for (JsonNode item : node) {
            String sourceId = acIdByKey.get(normalKey(text(item, "sourceKey", "acKey", "criterionKey", "from")));
            if (!StringUtils.hasText(sourceId)) continue;
            String targetType = normalizeTargetType(textOrDefault(item, "TESTCASE", "targetType", "type"));
            String targetKey = text(item, "targetKey", "targetId", "target", "to");
            String targetId = "TESTCASE".equals(targetType)
                    ? testcaseIdByKey.getOrDefault(normalKey(targetKey), targetKey)
                    : targetKey;
            if (!StringUtils.hasText(targetId)) continue;
            result.add(new TraceLink(UUID.randomUUID().toString(), baselineId, "AC", sourceId,
                    targetType, truncate(targetId, 512),
                    normalizeRelationType(textOrDefault(item, "VERIFIED_BY", "relationType", "relation")),
                    "AI", confidence(item.path("confidence").asDouble(0.70)),
                    enumValue(EvidenceLevel.class, text(item, "evidenceLevel", "level"), EvidenceLevel.E1),
                    enumValue(ReviewStatus.class, text(item, "reviewStatus", "status"), ReviewStatus.PENDING),
                    objectMap(item.path("evidence"))));
        }
        return result;
    }

    private List<Finding> parseFindings(String baselineId, JsonNode node, Map<String, String> acIdByKey) {
        if (!node.isArray()) return List.of();
        List<Finding> result = new ArrayList<>();
        for (JsonNode item : node) {
            String title = text(item, "title", "name");
            String description = text(item, "description", "detail", "summary");
            if (!StringUtils.hasText(title) && !StringUtils.hasText(description)) continue;
            result.add(new Finding(UUID.randomUUID().toString(), baselineId,
                    acIdByKey.get(normalKey(text(item, "acKey", "sourceKey", "criterionKey"))),
                    truncate(textOrDefault(item, "OTHER", "findingType", "type").toUpperCase(Locale.ROOT), 64),
                    normalizePerspective(text(item, "perspective")),
                    enumValue(Severity.class, text(item, "severity"), Severity.MEDIUM),
                    truncate(StringUtils.hasText(title) ? title : textOrDefault(item, "AI分析问题", "findingType", "type"), 512),
                    truncate(description, 4000),
                    truncate(text(item, "suggestion", "recommendation"), 4000),
                    confidence(item.path("confidence").asDouble(0.70)),
                    enumValue(EvidenceLevel.class, text(item, "evidenceLevel", "level"), EvidenceLevel.E1),
                    enumValue(Verdict.class, text(item, "verdict", "conclusion"), Verdict.PARTIAL),
                    ReviewStatus.PENDING,
                    objectList(item.path("evidence")), null, null, null));
        }
        return result;
    }

    private List<Finding> ensureFindings(String baselineId, List<AcceptanceCriterion> criteria,
                                         List<TraceLink> traceLinks, List<Finding> findings) {
        List<Finding> result = new ArrayList<>(findings);
        for (AcceptanceCriterion criterion : criteria) {
            if (result.stream().anyMatch(finding -> criterion.id().equals(finding.acId()))) continue;
            boolean hasTestcase = traceLinks.stream()
                    .anyMatch(link -> criterion.id().equals(link.sourceId()) && "TESTCASE".equals(link.targetType()));
            boolean hasImplementation = traceLinks.stream()
                    .anyMatch(link -> criterion.id().equals(link.sourceId()) && "SOURCE_SYMBOL".equals(link.targetType()));
            boolean hasExecutionOrCoverage = traceLinks.stream()
                    .anyMatch(link -> criterion.id().equals(link.sourceId())
                            && ("EXECUTION".equals(link.targetType()) || "COVERAGE".equals(link.targetType())));
            Verdict verdict = hasTestcase && hasImplementation && hasExecutionOrCoverage
                    ? Verdict.SATISFIED
                    : hasTestcase || hasImplementation ? Verdict.PARTIAL : Verdict.NOT_VERIFIABLE;
            Severity severity = verdict == Verdict.SATISFIED ? Severity.INFO : Severity.MEDIUM;
            EvidenceLevel level = hasExecutionOrCoverage ? EvidenceLevel.E3
                    : hasImplementation ? EvidenceLevel.E2 : hasTestcase ? EvidenceLevel.E1 : EvidenceLevel.E0;
            String type = verdict == Verdict.SATISFIED ? "SATISFIED_SUMMARY" : "MISSING_EVIDENCE";
            String title = verdict == Verdict.SATISFIED ? "验收标准证据完整" : "验收标准缺少完整证据";
            String description = verdict == Verdict.SATISFIED
                    ? "AI 返回了追溯证据，平台自动补充满足结论。"
                    : "AI 未返回该验收标准的问题结论，平台根据现有追溯证据自动标记为证据不足。";
            Perspective perspective = verdict == Verdict.SATISFIED ? Perspective.CROSS
                    : !hasTestcase ? Perspective.TEST
                    : !hasImplementation ? Perspective.DEVELOPMENT : Perspective.CROSS;
            result.add(new Finding(UUID.randomUUID().toString(), baselineId, criterion.id(), type, perspective,
                    severity, title, description, "补充测试用例、源码、执行或覆盖率证据后重新分析。",
                    0.55, level, verdict, ReviewStatus.PENDING, List.of(), null, null, null));
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

    private JsonNode firstArray(JsonNode root, String... fields) {
        if (root.isArray()) return root;
        for (String field : fields) {
            JsonNode value = root.path(field);
            if (value.isArray()) return value;
        }
        return MissingNode.getInstance();
    }

    private String extractJsonObject(String value) {
        String trimmed = stripThinking(value).trim();
        if (trimmed.startsWith("```")) {
            int firstLine = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (firstLine > 0 && end > firstLine) return trimmed.substring(firstLine + 1, end).trim();
        }
        int start = trimmed.indexOf('{');
        if (start < 0) return trimmed;
        int fallbackEnd = -1;
        for (int candidateStart = start; candidateStart >= 0 && candidateStart < trimmed.length();
             candidateStart = trimmed.indexOf('{', candidateStart + 1)) {
            int candidateEnd = matchingObjectEnd(trimmed, candidateStart);
            if (candidateEnd <= candidateStart) continue;
            if (fallbackEnd < 0) fallbackEnd = candidateEnd;
            String candidate = trimmed.substring(candidateStart, candidateEnd + 1);
            if (candidate.matches("(?s).*\\\"(criteria|acceptanceCriteria|acceptance_criteria|acs)\\\"\\s*:.*")) {
                return candidate;
            }
        }
        return fallbackEnd > start ? trimmed.substring(start, fallbackEnd + 1) : trimmed;
    }

    private String stripThinking(String value) {
        String result = value(value);
        int end = result.lastIndexOf("</think>");
        if (end >= 0) return result.substring(end + "</think>".length());
        int start = result.indexOf("<think>");
        return start >= 0 ? result.substring(start + "<think>".length()) : result;
    }

    private int matchingObjectEnd(String value, int start) {
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') quoted = true;
            else if (current == '{') depth++;
            else if (current == '}' && --depth == 0) return index;
        }
        return -1;
    }

    private JsonNode normalizeResponseRoot(JsonNode root) throws Exception {
        JsonNode current = root;
        for (int depth = 0; depth < 4; depth++) {
            if (current.isTextual()) {
                String embedded = extractJsonObject(current.asText());
                current = UtilJson.getObjectMapper().readTree(embedded);
                continue;
            }
            if (!current.isObject()) return current;
            if (hasAnalysisArrays(current)) return current;
            JsonNode nested = firstObject(current, "result", "data", "output", "analysis", "payload", "content", "response");
            if (nested == null) return current;
            current = nested;
        }
        return current;
    }

    private boolean hasAnalysisArrays(JsonNode node) {
        return firstArray(node, "criteria", "acceptanceCriteria", "acceptance_criteria", "acs").isArray();
    }

    private JsonNode firstObject(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode value = root.path(field);
            if (value.isObject() || value.isTextual() || value.isArray()) return value;
        }
        return null;
    }

    private String responseSummary(String response) {
        return "回复长度=" + value(response).length();
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

    private Perspective normalizePerspective(String value) {
        String normalized = value(value).trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PRODUCT", "PM", "PRODUCT_MANAGER", "产品", "产品视角" -> Perspective.PRODUCT;
            case "TEST", "QA", "TESTING", "测试", "测试视角" -> Perspective.TEST;
            case "DEVELOPMENT", "DEV", "DEVELOPER", "开发", "开发视角" -> Perspective.DEVELOPMENT;
            default -> Perspective.CROSS;
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

    private String text(JsonNode item, String... fields) {
        for (String field : fields) {
            JsonNode value = item.path(field);
            if (value.isMissingNode() || value.isNull()) continue;
            String text = value.isTextual() ? value.asText().trim() : value.toString();
            if (StringUtils.hasText(text)) return text;
        }
        return "";
    }

    private String textOrDefault(JsonNode item, String defaultValue, String... fields) {
        String value = text(item, fields);
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
