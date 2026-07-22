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
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VerificationAiOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(VerificationAiOrchestrator.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int MAX_REQUIREMENT_CHARS  = 18_000;
    private static final int MAX_TESTCASE_CHARS     = 18_000;
    private static final int MAX_ASSET_CHARS        = 14_000;
    private static final int MAX_TOTAL_PROMPT_CHARS = 48_000;
    private static final int MAX_SOURCE_CLASSES     = 18;
    private static final int MAX_SOURCE_CLASS_CHARS = 2_400;
    /** Maximum number of entries kept in the in-process prompt-hash cache. */
    private static final int PROMPT_CACHE_MAX_SIZE  = 256;
    private static final Pattern HTTP_ENDPOINT = Pattern.compile("\\b(?:GET|POST|PUT|DELETE|PATCH)\\s*[:：]?\\s*(/[A-Za-z0-9_./{}-]+)");
    private static final Pattern IDENTIFIER_TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{3,}");
    private static final Pattern SOURCE_FILE_MARKER = Pattern.compile("^\\s*//\\s*FILE:\\s*(.+?)\\s*$");
    private static final Pattern SPRING_MAPPING = Pattern.compile("@(Request|Get|Post|Put|Delete|Patch)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\\\"([^\\\"]*)\\\"");
    private static final Pattern JAVA_CLASS = Pattern.compile("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern JAVA_METHOD = Pattern.compile("\\b(?:public|protected|private)\\s+(?:static\\s+)?(?:<[^>]+>\\s+)?[A-Za-z_$][A-Za-z0-9_$<>, ?\\[\\].]*\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

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
            你是一个严谨的软件需求一致性分析 AI。平台对需求文档、测试用例、Bug/缺陷依据、源代码、执行报告、覆盖率报告的语义处理全部由你完成。

            工作边界：
            1. 只能基于用户提供的资料做抽取、匹配、判断和建议，不能编造不存在的需求、用例、类、方法、Bug 或执行结果。
            2. 你负责完成以下 AI 任务：需求验收标准抽取、测试用例结构化、需求-用例-源码-执行/覆盖依据追溯、问题发现。
            3. 常规程序不会替你做关键词匹配、相似度匹配或规则判定；因此你的输出必须完整、可落库、可追溯。
            4. 不确定时降低 confidence，并输出 AMBIGUOUS_REQUIREMENT、NOT_VERIFIABLE、MISSING_EVIDENCE 等发现，不要硬判满足。
            5. 源码实现依据必须引用输入中真实存在的类名、方法名、文件片段或源码快照定位。
            6. 测试用例依据必须引用输入中真实存在的用例编号/标题/定位。
            7. Bug/缺陷资料如果出现在输入中，应作为反向验证依据：它可能证明需求未满足、用例遗漏、实现偏差或历史风险。
            8. 依据链不足时只能输出 STATICALLY_CONSISTENT 或 NOT_VERIFIABLE，不能输出 SATISFIED。
               SATISFIED 要求测试用例、实现依据、执行或覆盖率三类中至少两类有支撑。

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
                  "evidence": {"reason":"为什么建立该关系","locator":"依据定位"}
                }
              ],
              "findings": [
                {
                  "acKey": "AC-1",
                  "findingType": "SATISFIED_SUMMARY|MISSING_TESTCASE|WEAK_ASSERTION|WRONG_EXPECTATION|MISSING_IMPLEMENTATION|LOGIC_DEVIATION|AMBIGUOUS_REQUIREMENT|TRACEABILITY_BREAK|MISSING_EVIDENCE|BUG_RISK|OTHER",
                  "perspective": "PRODUCT|TEST|DEVELOPMENT|CROSS",
                  "severity": "CRITICAL|HIGH|MEDIUM|LOW|INFO",
                  "title": "问题标题",
                  "description": "依据化说明",
                  "suggestion": "可执行建议",
                  "confidence": 0.0,
                  "evidenceLevel": "E0|E1|E2|E3|E4",
                  "verdict": "SATISFIED|STATICALLY_CONSISTENT|PARTIAL|NOT_SATISFIED|AMBIGUOUS|NOT_VERIFIABLE|STALE",
                  "evidence": [{"type":"REQUIREMENT|TESTCASE|SOURCE|EXECUTION|COVERAGE|DEFECT","id":"依据ID","locator":"依据定位","summary":"依据摘要"}]
                }
              ]
            }
            每一个 criteria 至少输出一条 findings。若该验收标准已满足，输出 findingType=SATISFIED_SUMMARY、severity=INFO、verdict=SATISFIED，并说明支撑依据。
            """;

    private final LLMService llmService;
    /** Bounded LRU cache: promptHash → raw LLM response. Avoids identical re-calls within the same process lifetime. */
    private final java.util.Map<String, String> promptCache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(PROMPT_CACHE_MAX_SIZE, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > PROMPT_CACHE_MAX_SIZE;
                }
            });

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
        String userMessage = buildUserMessage(input);
        String promptHash = com.oAT.web.verification.model.GraphModels.fingerprint(SYSTEM_PROMPT + userMessage);
        String cachedResponse = promptCache.get(promptHash);
        if (cachedResponse != null) {
            progress.accept("命中 Prompt 缓存，跳过 LLM 调用，直接解析已有结果");
            logger.debug("Prompt cache hit hash={}", promptHash);
        } else {
            progress.accept("正在请求 AI 生成需求、用例和依据关系");
            cachedResponse = llmService.chat(SYSTEM_PROMPT, userMessage);
            if (!StringUtils.hasText(cachedResponse)) {
                throw new IllegalStateException("AI分析没有返回结果");
            }
            promptCache.put(promptHash, cachedResponse);
        }
        final String response = cachedResponse;
        try {
            progress.accept("AI 已返回内容，正在校验分析结果");
            AiVerificationResult result = parse(input, response);
            return validateAndStripHallucinatedRefs(result, input);
        } catch (RuntimeException firstFailure) {
            AiVerificationResult partial = tryParseTruncatedResponse(input, response);
            if (partial != null) {
                progress.accept("AI 返回被截断，已保留已完成的分析项并补全文档追溯");
                logger.warn("AI 返回被截断，已恢复已完成分析项: {}", responseSummary(response));
                return partial;
            }
            progress.accept("AI 返回格式不完整，正在自动修复并重试");
            String repaired = llmService.chat(SYSTEM_PROMPT, buildRepairMessage(response));
            if (!StringUtils.hasText(repaired)) {
                return fallbackFromDocuments(input, progress, firstFailure);
            }
            try {
                progress.accept("正在校验修复后的分析结果");
                return parse(input, repaired);
            } catch (RuntimeException repairFailure) {
                repairFailure.addSuppressed(firstFailure);
                return fallbackFromDocuments(input, progress, repairFailure);
            }
        }
    }

    /**
     * A provider can stop after a complete array item but before closing the
     * enclosing JSON object. Recover that useful prefix locally so a second
     * model call is not required and the completed items are not discarded.
     */
    private AiVerificationResult tryParseTruncatedResponse(AiVerificationInput input, String response) {
        String recovered = recoverTruncatedJson(response);
        if (!StringUtils.hasText(recovered)) return null;
        try {
            JsonNode root = normalizeResponseRoot(UtilJson.getObjectMapper().readTree(recovered));
            return buildResult(input, root);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String recoverTruncatedJson(String response) {
        String value = extractJsonObject(response);
        int start = value.indexOf('{');
        if (start < 0) return "";

        StringBuilder prefix = new StringBuilder(value.substring(start));
        ArrayList<Character> open = new ArrayList<>();
        boolean quoted = false;
        boolean escaped = false;
        int lastSafeComma = -1;
        for (int i = 0; i < prefix.length(); i++) {
            char current = prefix.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') {
                quoted = true;
            } else if (current == '{' || current == '[') {
                open.add(current);
            } else if (current == '}' || current == ']') {
                if (!open.isEmpty()) open.remove(open.size() - 1);
            } else if (current == ',') {
                lastSafeComma = i;
            }
        }

        // An unfinished quoted value cannot be made valid without retaining
        // an invented value. Drop that incomplete property/item first.
        if (quoted && lastSafeComma >= 0) {
            prefix.setLength(lastSafeComma);
            while (prefix.length() > 0 && Character.isWhitespace(prefix.charAt(prefix.length() - 1))) {
                prefix.setLength(prefix.length() - 1);
            }
            if (prefix.length() > 0 && prefix.charAt(prefix.length() - 1) == ',') {
                prefix.setLength(prefix.length() - 1);
            }
            open.clear();
            quoted = false;
            for (int i = 0; i < prefix.length(); i++) {
                char current = prefix.charAt(i);
                if (current == '{' || current == '[') open.add(current);
                else if (current == '}' || current == ']') {
                    if (!open.isEmpty()) open.remove(open.size() - 1);
                }
            }
        }

        while (!prefix.isEmpty() && Character.isWhitespace(prefix.charAt(prefix.length() - 1))) {
            prefix.setLength(prefix.length() - 1);
        }
        while (!prefix.isEmpty() && (prefix.charAt(prefix.length() - 1) == ','
                || prefix.charAt(prefix.length() - 1) == ':')) {
            do {
                prefix.setLength(prefix.length() - 1);
            } while (!prefix.isEmpty() && Character.isWhitespace(prefix.charAt(prefix.length() - 1)));
        }
        for (int i = open.size() - 1; i >= 0; i--) {
            prefix.append(open.get(i) == '{' ? '}' : ']');
        }
        return prefix.toString();
    }

    private String buildRepairMessage(String response) {
        return """
                下面是一次 AI 分析的原始返回，但它不是可直接解析的完整 JSON。
                请从中保留有效分析内容，修复截断、Markdown 包裹、解释文本、字段格式问题，重新输出完整 JSON。
                只能输出 JSON 对象，不能输出 Markdown、代码围栏或任何解释文本。
                如果原始结果没有有效内容，仍需返回 {"criteria":[],"testcases":[],"traceLinks":[],"findings":[]}。

                原始返回：
                """ + value(response);
    }

    private AiVerificationResult parse(AiVerificationInput input, String response) {
        try {
            JsonNode root = normalizeResponseRoot(UtilJson.getObjectMapper().readTree(extractJsonObject(response)));
            return buildResult(input, root);
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) throw e;
            throw new IllegalStateException("AI分析返回格式不合法，请重试或检查模型配置", e);
        } catch (Exception e) {
            throw new IllegalStateException("AI分析返回格式不合法，请重试或检查模型配置", e);
        }
    }

    private AiVerificationResult fallbackFromDocuments(AiVerificationInput input, Consumer<String> progress,
                                                       RuntimeException parseFailure) {
        progress.accept("AI 返回内容不完整，正在从需求和用例文档中提取可展示结果");
        try {
            AiVerificationResult result = buildResult(input, UtilJson.getObjectMapper().createObjectNode());
            progress.accept("已使用文档结构化内容补全分析结果，正在保存");
            logger.warn("AI 返回无法解析，已使用文档结构化兜底结果: 原因: {}", parseFailure.toString());
            return result;
        } catch (RuntimeException fallbackFailure) {
            fallbackFailure.addSuppressed(parseFailure);
            throw fallbackFailure;
        } catch (Exception fallbackFailure) {
            IllegalStateException wrapped = new IllegalStateException("AI返回不完整，且无法从文档中提取需求验收标准，请检查导入资料格式", fallbackFailure);
            wrapped.addSuppressed(parseFailure);
            throw wrapped;
        }
    }

    private AiVerificationResult buildResult(AiVerificationInput input, JsonNode root) {
        try {
            String baselineId = input.baselineId();
            List<AcceptanceCriterion> criteria = parseCriteria(baselineId, firstArray(root, "criteria", "acceptanceCriteria", "acceptance_criteria", "acs"));
            criteria = mergeCriteria(criteria, parseMarkdownCriteria(baselineId, input.requirementContent()));
            if (criteria.isEmpty()) {
                throw new IllegalArgumentException("AI分析结果缺少验收标准");
            }
            List<TestcaseProjection> testcases = parseTestcases(baselineId, firstArray(root, "testcases", "testCases", "test_cases", "cases"));
            testcases = mergeTestcases(testcases, parseMarkdownTestcases(baselineId, input.testcaseContent()));
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
            traceLinks = addDocumentTraceLinks(baselineId, criteria, testcases, traceLinks);
            traceLinks = addSourceIndexTraceLinks(baselineId, criteria, traceLinks, input.staticSources());
            traceLinks = addSourceAssetTraceLinks(baselineId, criteria, traceLinks, input.sourceAssetContent());
            traceLinks = addTextEvidenceTraceLinks(baselineId, criteria, traceLinks, input.executionContent(),
                    "EXECUTION", "PROVEN_BY", EvidenceLevel.E3, "执行报告");
            traceLinks = addTextEvidenceTraceLinks(baselineId, criteria, traceLinks, input.coverageContent(),
                    "COVERAGE", "COVERED_BY", EvidenceLevel.E4, "覆盖率报告");
            traceLinks = addTextEvidenceTraceLinks(baselineId, criteria, traceLinks, input.defectContent(),
                    "DEFECT", "AFFECTED_BY", EvidenceLevel.E1, "缺陷资料");
            List<Finding> findings = parseFindings(baselineId,
                    firstArray(root, "findings", "issues", "problems", "risks"), acIdByKey);
            findings = ensureFindings(baselineId, criteria, testcases, traceLinks, findings, input.staticSources());
            return new AiVerificationResult(criteria, testcases, traceLinks, findings);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AI分析结果转换失败", e);
        }
    }

    private List<AcceptanceCriterion> mergeCriteria(List<AcceptanceCriterion> aiItems, List<AcceptanceCriterion> documentItems) {
        List<AcceptanceCriterion> result = new ArrayList<>(aiItems);
        for (AcceptanceCriterion item : documentItems) {
            boolean exists = result.stream().anyMatch(existing -> sameKey(existing.acKey(), item.acKey())
                    || sameKey(existing.requirementKey(), item.requirementKey()));
            if (!exists) result.add(item);
        }
        return result;
    }

    private List<TestcaseProjection> mergeTestcases(List<TestcaseProjection> aiItems, List<TestcaseProjection> documentItems) {
        List<TestcaseProjection> result = new ArrayList<>(aiItems);
        for (TestcaseProjection item : documentItems) {
            if (result.stream().noneMatch(existing -> sameKey(existing.externalKey(), item.externalKey()))) result.add(item);
        }
        return result;
    }

    private List<AcceptanceCriterion> parseMarkdownCriteria(String baselineId, String content) {
        List<AcceptanceCriterion> result = new ArrayList<>();
        for (List<String> cells : markdownRows(content, Pattern.compile("^(?:REQ-)?(?:U|W3|W301|L|D|F|WF)-\\d{3}$"))) {
            String key = cells.get(0);
            String title = cells.size() > 1 ? cells.get(1) : key;
            String locator = cells.size() > 2 ? cells.get(2) : "";
            String description = cells.isEmpty() ? "" : cells.get(cells.size() - 1);
            if (!StringUtils.hasText(description)) continue;
            result.add(new AcceptanceCriterion(UUID.randomUUID().toString(), baselineId, key, "AC-" + key,
                    truncate(title, 512), truncate(description, 4000), truncate(locator, 512), "MEDIUM",
                    true, false, 0.75));
        }
        result.addAll(parseNumberedAcceptanceCriteria(baselineId, content));
        return result;
    }

    private List<AcceptanceCriterion> parseNumberedAcceptanceCriteria(String baselineId, String content) {
        List<AcceptanceCriterion> result = new ArrayList<>();
        if (!StringUtils.hasText(content)) return result;
        boolean inAcceptanceSection = false;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                inAcceptanceSection = trimmed.contains("验收标准");
                continue;
            }
            if (!inAcceptanceSection) continue;
            Matcher matcher = Pattern.compile("^(\\d+)\\.\\s*(.+)$").matcher(trimmed);
            if (!matcher.matches()) continue;
            String key = "G-" + String.format("%03d", Integer.parseInt(matcher.group(1)));
            String description = matcher.group(2).trim();
            if (!StringUtils.hasText(description)) continue;
            result.add(new AcceptanceCriterion(UUID.randomUUID().toString(), baselineId, key, "AC-" + key,
                    truncate("总体验收标准 " + matcher.group(1), 512), truncate(description, 4000),
                    "需求文档: 验收标准", "MEDIUM", true, false, 0.70));
        }
        return result;
    }

    private List<TestcaseProjection> parseMarkdownTestcases(String baselineId, String content) {
        List<TestcaseProjection> result = new ArrayList<>();
        for (List<String> cells : markdownRows(content, Pattern.compile("^TC-[A-Z0-9]+-\\d{3}$"))) {
            String key = cells.get(0);
            String title = cells.size() > 1 ? cells.get(1) : key;
            String steps = cells.size() > 3 ? cells.get(3) : "";
            String expected = cells.size() > 4 ? cells.get(4) : "";
            result.add(new TestcaseProjection(UUID.randomUUID().toString(), baselineId, key, truncate(title, 512),
                    cells.size() > 2 ? truncate(cells.get(2), 2000) : "", truncate(steps, 4000), "",
                    truncate(expected, 4000), "", "测试用例文档"));
        }
        return result;
    }

    private List<List<String>> markdownRows(String content, Pattern keyPattern) {
        List<List<String>> result = new ArrayList<>();
        if (!StringUtils.hasText(content)) return result;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) continue;
            List<String> cells = new ArrayList<>();
            for (String raw : trimmed.substring(1, trimmed.length() - 1).split("\\|")) cells.add(raw.trim());
            if (cells.isEmpty() || !keyPattern.matcher(cells.get(0)).matches()) continue;
            result.add(cells);
        }
        return result;
    }

    private List<TraceLink> addDocumentTraceLinks(String baselineId, List<AcceptanceCriterion> criteria,
                                                   List<TestcaseProjection> testcases, List<TraceLink> links) {
        List<TraceLink> result = new ArrayList<>(links);
        for (TestcaseProjection testcase : testcases) {
            String requirementKey = testcase.externalKey().startsWith("TC-")
                    ? testcase.externalKey().substring(3) : testcase.externalKey();
            AcceptanceCriterion criterion = criteria.stream()
                    .filter(item -> sameKey(item.requirementKey(), requirementKey)
                            || sameKey(item.acKey(), "AC-" + requirementKey))
                    .findFirst().orElse(null);
            if (criterion == null || result.stream().anyMatch(link -> criterion.id().equals(link.sourceId())
                    && testcase.id().equals(link.targetId()))) continue;
            result.add(new TraceLink(UUID.randomUUID().toString(), baselineId, "AC", criterion.id(), "TESTCASE",
                    testcase.id(), "VERIFIED_BY", "DOCUMENT_KEY_MATCH", 0.85, EvidenceLevel.E1,
                    ReviewStatus.PENDING, Map.of("reason", "需求编号与测试用例编号匹配")));
        }
        return result;
    }

    private List<TraceLink> addSourceIndexTraceLinks(String baselineId, List<AcceptanceCriterion> criteria,
                                                     List<TraceLink> links, List<StaticSourceInfo> sources) {
        List<TraceLink> result = new ArrayList<>(links);
        if (sources == null || sources.isEmpty()) return result;
        for (AcceptanceCriterion criterion : criteria) {
            if (result.stream().anyMatch(link -> criterion.id().equals(link.sourceId())
                    && "SOURCE_SYMBOL".equals(link.targetType()))) continue;
            String requirement = searchableCriterionText(criterion);
            List<String> endpoints = endpointsOf(criterion);
            for (StaticSourceInfo source : sources) {
                if (source == null || source.getClassInfo() == null) continue;
                String className = value(source.getClassInfo().getClassName());
                String sourceCode = value(source.getClassInfo().getSourceCode());
                SourceEndpoint endpointEvidence = parseSourceEndpoints(sourceCode).stream()
                        .filter(endpoint -> criterionEndpointsMatch(endpoints, endpoint))
                        .findFirst()
                        .orElse(null);
                if (endpointEvidence != null) {
                    result.add(new TraceLink(UUID.randomUUID().toString(), baselineId, "AC", criterion.id(),
                            "SOURCE_SYMBOL", truncate(endpointEvidence.symbol(), 512), "IMPLEMENTED_BY",
                            "STATIC_INDEX_ENDPOINT_MATCH", 0.92, EvidenceLevel.E2, ReviewStatus.PENDING,
                            Map.of("reason", "静态源码索引解析到与验收标准完全匹配的 Spring 接口映射",
                                    "endpoint", endpointEvidence.endpoint(), "httpMethod", endpointEvidence.httpMethod(),
                                    "locator", endpointEvidence.locator())));
                    break;
                }
                String matchedSymbol = null;
                if (StringUtils.hasText(className) && containsToken(requirement, className)) {
                    matchedSymbol = className;
                }
                boolean sourceTermMatch = sourceContainsCriterionTerms(sourceCode, criterion);
                if (source.getClassInfo().getMethodMaps() != null) {
                    for (String methodName : source.getClassInfo().getMethodMaps().keySet()) {
                        if (StringUtils.hasText(methodName) && (containsToken(requirement, methodName)
                                || endpoints.stream().anyMatch(endpoint -> methodMatchesEndpoint(methodName, endpoint))
                                || sourceContainsEndpoint(sourceCode, endpoints)
                                || (sourceTermMatch && sourceContainsMethod(sourceCode, methodName)))) {
                            matchedSymbol = className + "#" + methodName;
                            break;
                        }
                    }
                }
                if (!StringUtils.hasText(matchedSymbol) && (sourceContainsEndpoint(sourceCode, endpoints) || sourceTermMatch)) {
                    matchedSymbol = StringUtils.hasText(className) ? className : endpoints.isEmpty() ? "" : endpoints.get(0);
                }
                if (!StringUtils.hasText(matchedSymbol)) continue;
                result.add(new TraceLink(UUID.randomUUID().toString(), baselineId, "AC", criterion.id(),
                        "SOURCE_SYMBOL", truncate(matchedSymbol, 512), "IMPLEMENTED_BY", "STATIC_INDEX_MATCH",
                        0.72, EvidenceLevel.E2, ReviewStatus.PENDING,
                        Map.of("reason", "验收标准中的接口或方法名与静态源码索引匹配", "reviewRequired", true)));
                break;
            }
        }
        return result;
    }

    private List<TraceLink> addSourceAssetTraceLinks(String baselineId, List<AcceptanceCriterion> criteria,
                                                     List<TraceLink> links, String sourceContent) {
        List<TraceLink> result = new ArrayList<>(links);
        if (!StringUtils.hasText(sourceContent)) return result;
        List<SourceEndpoint> endpoints = parseSourceEndpoints(sourceContent);
        for (AcceptanceCriterion criterion : criteria) {
            if (hasLink(result, criterion.id(), "SOURCE_SYMBOL")) continue;
            SourceEndpoint matched = endpoints.stream()
                    .filter(endpoint -> criterionEndpointsMatch(endpointsOf(criterion), endpoint))
                    .findFirst()
                    .orElse(null);
            if (matched == null) continue;
            result.add(new TraceLink(UUID.randomUUID().toString(), baselineId, "AC", criterion.id(),
                    "SOURCE_SYMBOL", truncate(matched.symbol(), 512), "IMPLEMENTED_BY", "SOURCE_ENDPOINT_MATCH",
                    0.90, EvidenceLevel.E2, ReviewStatus.PENDING,
                    Map.of("reason", "从源码快照解析到与验收标准完全匹配的 Spring 接口映射",
                            "endpoint", matched.endpoint(), "httpMethod", matched.httpMethod(),
                            "locator", matched.locator(), "file", matched.filePath())));
        }
        return result;
    }

    private List<SourceEndpoint> parseSourceEndpoints(String sourceContent) {
        List<SourceEndpoint> result = new ArrayList<>();
        String filePath = "源码快照";
        String className = "";
        String classPath = "";
        String pendingHttpMethod = "";
        String pendingPath = "";
        int lineNumber = 0;
        for (String line : sourceContent.split("\\R")) {
            lineNumber++;
            Matcher fileMatcher = SOURCE_FILE_MARKER.matcher(line);
            if (fileMatcher.matches()) {
                filePath = fileMatcher.group(1).trim();
                className = "";
                classPath = "";
                pendingHttpMethod = "";
                pendingPath = "";
                continue;
            }
            Matcher mappingMatcher = SPRING_MAPPING.matcher(line);
            if (mappingMatcher.find()) {
                String mappingType = mappingMatcher.group(1);
                String path = mappingMatcher.group(2);
                if ("Request".equals(mappingType)) {
                    classPath = normalizeEndpoint(path);
                } else {
                    pendingHttpMethod = mappingType.toUpperCase(Locale.ROOT);
                    pendingPath = path;
                }
                continue;
            }
            Matcher classMatcher = JAVA_CLASS.matcher(line);
            if (classMatcher.find()) {
                className = classMatcher.group(1);
                continue;
            }
            Matcher methodMatcher = JAVA_METHOD.matcher(line);
            if (!methodMatcher.find() || !StringUtils.hasText(pendingHttpMethod) || !StringUtils.hasText(className)) continue;
            String methodName = methodMatcher.group(1);
            String endpoint = joinEndpoint(classPath, pendingPath);
            result.add(new SourceEndpoint(endpoint, pendingHttpMethod, className, methodName, filePath,
                    filePath + ":" + lineNumber));
            pendingHttpMethod = "";
            pendingPath = "";
        }
        return result;
    }

    private boolean criterionEndpointsMatch(List<String> criterionEndpoints, SourceEndpoint sourceEndpoint) {
        return criterionEndpoints.stream()
                .map(this::normalizeEndpoint)
                .anyMatch(criterionEndpoint -> criterionEndpoint.equals(sourceEndpoint.endpoint()));
    }

    private String joinEndpoint(String basePath, String methodPath) {
        String base = normalizeEndpoint(basePath);
        String method = normalizeEndpoint(methodPath);
        if ("/".equals(base)) return method;
        if ("/".equals(method)) return base;
        return normalizeEndpoint(base + "/" + method.substring(1));
    }

    private String normalizeEndpoint(String endpoint) {
        String normalized = value(endpoint).trim().replaceAll("/{2,}", "/");
        if (!StringUtils.hasText(normalized)) return "/";
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private record SourceEndpoint(String endpoint, String httpMethod, String className, String methodName,
                                  String filePath, String locator) {
        private String symbol() {
            return className + "#" + methodName;
        }
    }

    private List<TraceLink> addTextEvidenceTraceLinks(String baselineId, List<AcceptanceCriterion> criteria,
                                                      List<TraceLink> links, String content, String targetType,
                                                      String relationType, EvidenceLevel level, String sourceName) {
        List<TraceLink> result = new ArrayList<>(links);
        if (!StringUtils.hasText(content)) return result;
        String haystack = content.toLowerCase(Locale.ROOT);
        for (AcceptanceCriterion criterion : criteria) {
            if (hasLink(result, criterion.id(), targetType)) continue;
            String matched = firstEvidenceMatch(criterion, haystack);
            if (!StringUtils.hasText(matched)) continue;
            result.add(new TraceLink(UUID.randomUUID().toString(), baselineId, "AC", criterion.id(),
                    targetType, truncate(matched, 512), relationType, sourceName + "_TEXT_MATCH",
                    0.66, level, ReviewStatus.PENDING,
                    Map.of("reason", sourceName + "中出现需求编号、验收标准编号或接口路径", "matched", matched)));
        }
        return result;
    }

    private String firstEvidenceMatch(AcceptanceCriterion criterion, String haystack) {
        for (String key : List.of(criterion.requirementKey(), criterion.acKey())) {
            if (StringUtils.hasText(key) && haystack.contains(key.toLowerCase(Locale.ROOT))) return key;
        }
        for (String endpoint : endpointsOf(criterion)) {
            if (haystack.contains(endpoint.toLowerCase(Locale.ROOT))) return endpoint;
        }
        return "";
    }

    private boolean hasLink(List<TraceLink> links, String sourceId, String targetType) {
        return links.stream().anyMatch(link -> sourceId.equals(link.sourceId()) && targetType.equals(link.targetType()));
    }

    private String searchableCriterionText(AcceptanceCriterion criterion) {
        return value(criterion.requirementKey()) + " " + value(criterion.acKey()) + " "
                + value(criterion.title()) + " " + value(criterion.content()) + " " + value(criterion.sourceLocator());
    }

    private List<String> endpointsOf(AcceptanceCriterion criterion) {
        List<String> result = new ArrayList<>();
        Matcher matcher = HTTP_ENDPOINT.matcher(searchableCriterionText(criterion));
        while (matcher.find()) {
            String endpoint = matcher.group(1);
            if (StringUtils.hasText(endpoint) && !result.contains(endpoint)) result.add(endpoint);
        }
        return result;
    }

    private boolean sourceContainsEndpoint(String sourceCode, List<String> endpoints) {
        if (!StringUtils.hasText(sourceCode) || endpoints.isEmpty()) return false;
        String normalizedSource = sourceCode.toLowerCase(Locale.ROOT);
        return endpoints.stream().anyMatch(endpoint -> normalizedSource.contains(endpoint.toLowerCase(Locale.ROOT))
                || normalizedSource.contains(endpointLastSegment(endpoint).toLowerCase(Locale.ROOT)));
    }

    private boolean methodMatchesEndpoint(String methodName, String endpoint) {
        String method = value(methodName).toLowerCase(Locale.ROOT);
        String segment = endpointLastSegment(endpoint).toLowerCase(Locale.ROOT);
        String normalizedMethod = identifierKey(method);
        String normalizedSegment = identifierKey(segment);
        return StringUtils.hasText(segment) && (method.equals(segment) || method.contains(segment) || segment.contains(method)
                || normalizedMethod.equals(normalizedSegment)
                || normalizedMethod.contains(normalizedSegment)
                || normalizedSegment.contains(normalizedMethod));
    }

    private String endpointLastSegment(String endpoint) {
        String safe = value(endpoint);
        int index = safe.lastIndexOf('/');
        return index >= 0 && index < safe.length() - 1 ? safe.substring(index + 1).replaceAll("[{}]", "") : safe;
    }

    private boolean containsToken(String text, String token) {
        String normalizedToken = value(token).toLowerCase(Locale.ROOT).trim();
        if (normalizedToken.length() < 4) return false;
        return value(text).toLowerCase(Locale.ROOT).contains(normalizedToken);
    }

    private boolean sourceContainsMethod(String sourceCode, String methodName) {
        if (!StringUtils.hasText(sourceCode) || !StringUtils.hasText(methodName)) return false;
        String source = sourceCode.toLowerCase(Locale.ROOT);
        String method = methodName.toLowerCase(Locale.ROOT);
        return source.contains(method + "(") || source.contains(" " + method + "(");
    }

    private boolean sourceContainsCriterionTerms(String sourceCode, AcceptanceCriterion criterion) {
        if (!StringUtils.hasText(sourceCode)) return false;
        String normalizedSource = value(sourceCode).toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String token : criterionTokens(criterion)) {
            if (normalizedSource.contains(token.toLowerCase(Locale.ROOT))) {
                hits++;
                if (hits >= 2) return true;
            }
        }
        return false;
    }

    private List<String> criterionTokens(AcceptanceCriterion criterion) {
        List<String> result = new ArrayList<>();
        Matcher matcher = IDENTIFIER_TOKEN.matcher(searchableCriterionText(criterion));
        while (matcher.find()) {
            String token = matcher.group();
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.length() < 4 || lower.startsWith("http") || lower.startsWith("req") || lower.startsWith("ac")) {
                continue;
            }
            if (!result.contains(token)) result.add(token);
        }
        return result;
    }

    private String identifierKey(String value) {
        return value(value).replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private boolean sameKey(String left, String right) {
        return normalKey(left).equals(normalKey(right));
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
                                         List<TestcaseProjection> testcases, List<TraceLink> traceLinks,
                                         List<Finding> findings, List<StaticSourceInfo> staticSources) {
        List<Finding> result = normalizeMissingImplementationFindings(findings);
        for (AcceptanceCriterion criterion : criteria) {
            boolean hasTestcase = traceLinks.stream()
                    .anyMatch(link -> criterion.id().equals(link.sourceId()) && "TESTCASE".equals(link.targetType()));
            boolean hasImplementation = traceLinks.stream()
                    .anyMatch(link -> criterion.id().equals(link.sourceId()) && "SOURCE_SYMBOL".equals(link.targetType()));
            boolean hasExecutionOrCoverage = traceLinks.stream()
                    .anyMatch(link -> criterion.id().equals(link.sourceId())
                            && ("EXECUTION".equals(link.targetType()) || "COVERAGE".equals(link.targetType())));
            boolean hasCoverage = traceLinks.stream()
                    .anyMatch(link -> criterion.id().equals(link.sourceId()) && "COVERAGE".equals(link.targetType()));
            Verdict verdict = hasTestcase && hasImplementation && hasExecutionOrCoverage
                    ? Verdict.SATISFIED
                    : hasTestcase || hasImplementation ? Verdict.PARTIAL : Verdict.NOT_VERIFIABLE;
            Severity severity = verdict == Verdict.SATISFIED ? Severity.INFO : Severity.MEDIUM;
            EvidenceLevel level = hasCoverage ? EvidenceLevel.E4 : hasExecutionOrCoverage ? EvidenceLevel.E3
                    : hasImplementation ? EvidenceLevel.E2 : hasTestcase ? EvidenceLevel.E1 : EvidenceLevel.E0;
            result.removeIf(finding -> isContradictedMissingFinding(criterion, finding,
                    hasTestcase, hasImplementation, hasExecutionOrCoverage));
            List<Finding> existing = result.stream()
                    .filter(finding -> criterion.id().equals(finding.acId()))
                    .toList();
            if (verdict == Verdict.SATISFIED) {
                if (existing.isEmpty()) {
                    result.add(autoFinding(baselineId, criterion, "SATISFIED_SUMMARY", Perspective.CROSS,
                            Severity.INFO, "依据链已覆盖验收标准",
                            "已找到测试、源码、执行或覆盖率中的多类依据，可作为当前验收标准的满足结论。",
                            "人工抽查关键依据是否引用准确，确认后可关闭该发现。",
                            0.55, level, verdict));
                }
                continue;
            }

            if (existing.stream().noneMatch(finding -> finding.perspective() == Perspective.PRODUCT)) {
                result.add(autoFinding(baselineId, criterion, "REQUIREMENT_CONFIRMATION", Perspective.PRODUCT,
                        severity, criterion.acKey() + " 确认验收口径和优先级",
                        "需求 " + criterion.requirementKey() + " / " + criterion.acKey()
                                + " 尚未形成完整依据链。产品需要确认该标准是否属于本轮验收范围、验收口径是否清晰，以及是否需要拆分更具体的验收条件。验收标准：" + criterion.content(),
                        "补充验收边界、业务规则、优先级和不满足时的业务影响，再重新运行分析。",
                        0.55, level, verdict));
            }
            if ((!hasTestcase || !hasExecutionOrCoverage)
                    && existing.stream().noneMatch(finding -> finding.perspective() == Perspective.TEST)) {
                String title = !hasTestcase ? "补充覆盖该验收标准的测试用例" : "补充测试执行或覆盖率依据";
                String description = !hasTestcase
                        ? "需求 " + criterion.requirementKey() + " / " + criterion.acKey()
                        + " 没有对应测试用例。测试人员需要补充用例编号、前置条件、步骤、测试数据和明确预期结果。验收标准：" + criterion.content()
                        : "需求 " + criterion.requirementKey() + " / " + criterion.acKey()
                        + " 已有关联用例或静态依据，但缺少测试执行结果或覆盖率记录。测试人员需要补充最近一次执行状态、报告链接或覆盖率依据。验收标准：" + criterion.content();
                String suggestion = !hasTestcase
                        ? "新增或关联测试用例，并在预期结果中写清可判断的验收条件。"
                        : "导入测试执行报告或覆盖率报告，确保记录能关联到该验收标准或对应测试用例。";
                result.add(autoFinding(baselineId, criterion, !hasTestcase ? "MISSING_TESTCASE" : "MISSING_RUNTIME_EVIDENCE",
                        Perspective.TEST, severity, criterion.acKey() + " " + title, description, suggestion, 0.55, level, verdict));
            }
            if (!hasImplementation && existing.stream().noneMatch(finding -> finding.perspective() == Perspective.DEVELOPMENT)) {
                result.add(autoFinding(baselineId, criterion, "MISSING_IMPLEMENTATION_EVIDENCE", Perspective.DEVELOPMENT,
                        severity, criterion.acKey() + " 未识别到源码实现关联依据",
                        "需求 " + criterion.requirementKey() + " / " + criterion.acKey()
                                + " 尚未关联到本次导入范围内的静态源码类、方法或接口依据。这不等同于代码尚未实现；可能是源码快照、应用静态索引或接口定位未覆盖该实现。验收标准：" + criterion.content(),
                        "确认已选择正确的源码版本和应用静态索引；补充源码包、源码工程版本、接口/类/方法定位后重新分析。只有确认源码范围完整且仍无实现时，才创建开发任务。",
                        0.55, level, verdict));
            }
        }
        addOrphanTestcaseFindings(baselineId, testcases, traceLinks, result);
        addOrphanSourceFindings(baselineId, staticSources, traceLinks, result);
        return result;
    }

    private List<Finding> normalizeMissingImplementationFindings(List<Finding> findings) {
        List<Finding> result = new ArrayList<>();
        for (Finding finding : findings) {
            if (!"MISSING_IMPLEMENTATION".equalsIgnoreCase(finding.findingType())) {
                result.add(finding);
                continue;
            }
            result.add(new Finding(finding.id(), finding.baselineId(), finding.acId(), "MISSING_IMPLEMENTATION_EVIDENCE",
                    finding.perspective(), finding.severity(), "未识别到源码实现关联依据",
                    "当前分析未关联到足以证明该验收标准的源码实现依据。这不等同于代码尚未实现；请确认源码快照、应用静态索引和接口/类/方法定位是否覆盖实现位置。原始分析说明：" + finding.description(),
                    "补充或选择正确的源码版本和静态索引后重新分析；仅在确认源码范围完整且仍无实现时创建开发任务。",
                    finding.confidence(), finding.evidenceLevel(), Verdict.NOT_VERIFIABLE, finding.reviewStatus(),
                    finding.evidence(), finding.externalWorkItemUrl(), finding.reviewedBy(), finding.reviewReason()));
        }
        return result;
    }

    private boolean isContradictedMissingFinding(AcceptanceCriterion criterion, Finding finding,
                                                 boolean hasTestcase, boolean hasImplementation,
                                                 boolean hasExecutionOrCoverage) {
        if (!criterion.id().equals(finding.acId())) return false;
        String type = value(finding.findingType()).toUpperCase(Locale.ROOT);
        return (hasTestcase && "MISSING_TESTCASE".equals(type))
                || (hasImplementation && ("MISSING_IMPLEMENTATION".equals(type)
                || "MISSING_IMPLEMENTATION_EVIDENCE".equals(type)))
                || (hasExecutionOrCoverage && "MISSING_RUNTIME_EVIDENCE".equals(type));
    }

    private void addOrphanTestcaseFindings(String baselineId, List<TestcaseProjection> testcases,
                                           List<TraceLink> traceLinks, List<Finding> result) {
        for (TestcaseProjection testcase : testcases) {
            boolean linked = traceLinks.stream().anyMatch(link -> "TESTCASE".equals(link.targetType())
                    && (testcase.id().equals(link.targetId()) || testcase.externalKey().equals(link.targetId())));
            if (linked) continue;
            result.add(new Finding(UUID.randomUUID().toString(), baselineId, null, "ORPHAN_TESTCASE",
                    Perspective.TEST, Severity.MEDIUM,
                    testcase.externalKey() + " 测试用例未关联需求和代码依据",
                    "测试用例 " + testcase.externalKey() + " / " + testcase.title()
                            + " 没有追溯到任何需求验收标准，也没有形成对应源码、执行或覆盖率依据链。",
                    "确认该测试用例覆盖哪个需求；如是无效或过期用例，请清理或标记；如有效，请补充需求编号和代码/执行依据关联。",
                    0.60, EvidenceLevel.E1, Verdict.NOT_VERIFIABLE, ReviewStatus.PENDING,
                    List.of(Map.of("type", "TESTCASE", "id", testcase.externalKey(),
                            "locator", value(testcase.sourceLocator()),
                            "summary", truncate(testcase.title() + " " + testcase.steps() + " " + testcase.expected(), 500))),
                    null, null, null));
        }
    }

    private void addOrphanSourceFindings(String baselineId, List<StaticSourceInfo> staticSources,
                                         List<TraceLink> traceLinks, List<Finding> result) {
        if (staticSources == null || staticSources.isEmpty()) return;
        int count = 0;
        for (StaticSourceInfo source : staticSources) {
            if (source == null || source.getClassInfo() == null) continue;
            String className = value(source.getClassInfo().getClassName());
            if (!StringUtils.hasText(className)) continue;
            if (source.getClassInfo().getMethodMaps() == null || source.getClassInfo().getMethodMaps().isEmpty()) {
                if (isSourceSymbolLinked(traceLinks, className)) continue;
                result.add(orphanSourceFinding(baselineId, className, className, "源码类未关联需求或测试用例"));
                if (++count >= 20) return;
                continue;
            }
            for (String methodName : source.getClassInfo().getMethodMaps().keySet()) {
                String symbol = className + "#" + methodName;
                if (isSourceSymbolLinked(traceLinks, symbol) || isSourceSymbolLinked(traceLinks, className)) continue;
                result.add(orphanSourceFinding(baselineId, symbol, className, "源码方法未关联需求或测试用例"));
                if (++count >= 20) return;
            }
        }
    }

    private boolean isSourceSymbolLinked(List<TraceLink> traceLinks, String symbol) {
        String normalized = value(symbol).toLowerCase(Locale.ROOT);
        return traceLinks.stream().anyMatch(link -> "SOURCE_SYMBOL".equals(link.targetType())
                && value(link.targetId()).toLowerCase(Locale.ROOT).contains(normalized));
    }

    private Finding orphanSourceFinding(String baselineId, String symbol, String locator, String title) {
        return new Finding(UUID.randomUUID().toString(), baselineId, null, "ORPHAN_SOURCE",
                Perspective.DEVELOPMENT, Severity.MEDIUM, symbol + " " + title,
                "代码 " + symbol + " 没有追溯到任何需求验收标准或测试用例。需要确认该代码是否属于本次分析范围，或补充需求/用例关联。",
                "补充需求编号、测试用例编号、接口路径或覆盖率/执行记录；如该代码不在本次范围，可标记豁免。",
                0.55, EvidenceLevel.E2, Verdict.NOT_VERIFIABLE, ReviewStatus.PENDING,
                List.of(Map.of("type", "SOURCE", "id", symbol, "locator", locator,
                        "summary", "未关联需求和测试用例的静态源码符号")),
                null, null, null);
    }

    private Finding autoFinding(String baselineId, AcceptanceCriterion criterion, String type, Perspective perspective,
                                Severity severity, String title, String description, String suggestion,
                                double confidence, EvidenceLevel level, Verdict verdict) {
        List<Map<String, Object>> evidence = List.of(Map.of(
                "type", "REQUIREMENT",
                "id", criterion.acKey(),
                "locator", value(criterion.sourceLocator()),
                "summary", truncate(criterion.content(), 300)
        ));
        return new Finding(UUID.randomUUID().toString(), baselineId, criterion.id(), type, perspective,
                severity, title, description, suggestion, confidence, level, verdict,
                ReviewStatus.PENDING, evidence, null, null, null);
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
        if (prompt.length() >= MAX_TOTAL_PROMPT_CHARS) {
            prompt.append("(因输入总长度限制未附加；系统仍会用静态索引建立确定性源码追溯)\n");
        } else if (input.staticSources() == null || input.staticSources().isEmpty()) {
            prompt.append("(无静态源码索引)\n");
        } else {
            List<StaticSourceInfo> ranked = rankSourcesByRelevance(input.staticSources(), input.requirementContent(), input.testcaseContent());
            int count = 0;
            for (StaticSourceInfo source : ranked) {
                if (source == null || source.getClassInfo() == null) continue;
                if (count++ >= MAX_SOURCE_CLASSES || prompt.length() >= MAX_TOTAL_PROMPT_CHARS) break;
                String className = source.getClassInfo().getClassName();
                prompt.append("类: ").append(value(className)).append('\n');
                if (source.getClassInfo().getMethodMaps() != null && !source.getClassInfo().getMethodMaps().isEmpty()) {
                    prompt.append("方法: ").append(source.getClassInfo().getMethodMaps().keySet()).append('\n');
                }
                int remaining = Math.max(0, MAX_TOTAL_PROMPT_CHARS - prompt.length());
                if (remaining > 0) prompt.append(truncate(value(source.getClassInfo().getSourceCode()), Math.min(MAX_SOURCE_CLASS_CHARS, remaining)));
                prompt.append("\n\n");
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

    /**
     * Rank static sources by how many AC/testcase tokens they match, so the most relevant classes
     * fill the prompt budget first (minimal subgraph principle, plan §15).
     * Sources with zero token hits are appended last so they still appear if budget allows.
     */
    private List<StaticSourceInfo> rankSourcesByRelevance(List<StaticSourceInfo> sources,
                                                           String requirementText, String testcaseText) {
        if (sources == null || sources.isEmpty()) return List.of();
        String haystack = (value(requirementText) + " " + value(testcaseText)).toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        Matcher m = IDENTIFIER_TOKEN.matcher(haystack);
        while (m.find()) {
            String t = m.group().toLowerCase(Locale.ROOT);
            if (t.length() >= 4 && !tokens.contains(t)) tokens.add(t);
        }
        if (tokens.isEmpty()) return sources;
        return sources.stream()
                .filter(java.util.Objects::nonNull)
                .sorted((a, b) -> Integer.compare(relevanceScore(b, tokens), relevanceScore(a, tokens)))
                .toList();
    }

    private int relevanceScore(StaticSourceInfo source, List<String> tokens) {
        if (source.getClassInfo() == null) return 0;
        String className = value(source.getClassInfo().getClassName()).toLowerCase(Locale.ROOT);
        String sourceCode = value(source.getClassInfo().getSourceCode()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : tokens) {
            if (className.contains(token)) score += 3;
            if (sourceCode.contains(token)) score += 1;
        }
        if (source.getClassInfo().getMethodMaps() != null) {
            for (String method : source.getClassInfo().getMethodMaps().keySet()) {
                String lower = value(method).toLowerCase(Locale.ROOT);
                for (String token : tokens) {
                    if (lower.contains(token)) score += 2;
                }
            }
        }
        return score;
    }

    /**
     * Strip TraceLinks and Findings whose SOURCE_SYMBOL targetId references a class or method name
     * that does not actually appear anywhere in the input (hallucination guard, plan §15).
     * Links to TESTCASE, EXECUTION, COVERAGE, DEFECT are not stripped — those IDs are opaque keys.
     */
    private AiVerificationResult validateAndStripHallucinatedRefs(AiVerificationResult result,
                                                                    AiVerificationInput input) {
        Set<String> knownSymbols = buildKnownSymbolSet(input);
        if (knownSymbols.isEmpty()) return result;

        List<TraceLink> validLinks = result.traceLinks().stream()
                .filter(link -> {
                    if (!"SOURCE_SYMBOL".equals(link.targetType())) return true;
                    boolean valid = isSymbolKnown(link.targetId(), knownSymbols);
                    if (!valid) logger.debug("Stripped hallucinated SOURCE_SYMBOL ref: {}", link.targetId());
                    return valid;
                })
                .toList();

        int stripped = result.traceLinks().size() - validLinks.size();
        if (stripped > 0) logger.warn("Stripped {} hallucinated SOURCE_SYMBOL TraceLinks", stripped);

        return new AiVerificationResult(result.criteria(), result.testcases(), validLinks, result.findings());
    }

    /** Build the set of all class and method names actually present in the input. */
    private Set<String> buildKnownSymbolSet(AiVerificationInput input) {
        Set<String> known = new java.util.LinkedHashSet<>();
        if (input.staticSources() != null) {
            for (StaticSourceInfo source : input.staticSources()) {
                if (source == null || source.getClassInfo() == null) continue;
                String className = value(source.getClassInfo().getClassName());
                if (StringUtils.hasText(className)) known.add(className.toLowerCase(Locale.ROOT));
                if (source.getClassInfo().getMethodMaps() != null) {
                    for (String method : source.getClassInfo().getMethodMaps().keySet()) {
                        if (StringUtils.hasText(method)) {
                            known.add((className + "#" + method).toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        }
        // Also accept symbols mentioned in the source asset text (free-form source snapshots)
        if (StringUtils.hasText(input.sourceAssetContent())) {
            Matcher cm = JAVA_CLASS.matcher(input.sourceAssetContent());
            while (cm.find()) known.add(cm.group(1).toLowerCase(Locale.ROOT));
            Matcher mm = JAVA_METHOD.matcher(input.sourceAssetContent());
            while (mm.find()) known.add(mm.group(1).toLowerCase(Locale.ROOT));
        }
        return known;
    }

    private boolean isSymbolKnown(String targetId, Set<String> knownSymbols) {
        if (!StringUtils.hasText(targetId)) return false;
        String lower = targetId.toLowerCase(Locale.ROOT);
        // Full match or prefix/suffix containment covers "ClassName#method" and bare class names
        if (knownSymbols.contains(lower)) return true;
        for (String known : knownSymbols) {
            if (lower.contains(known) || known.contains(lower)) return true;
        }
        return false;
    }

    private void appendSection(StringBuilder prompt, String title, String content, int maxChars) {
        prompt.append("【").append(title).append("】\n");
        if (StringUtils.hasText(content)) {
            int remaining = Math.max(0, MAX_TOTAL_PROMPT_CHARS - prompt.length());
            int allowed = Math.min(maxChars, remaining);
            if (allowed == 0) {
                prompt.append("(因输入总长度限制未附加；系统仍会通过确定性追溯处理该资料)\n\n");
                return;
            }
            prompt.append(truncate(content, allowed));
            if (content.length() > allowed) prompt.append("\n[内容已截断]");
            prompt.append("\n\n");
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
