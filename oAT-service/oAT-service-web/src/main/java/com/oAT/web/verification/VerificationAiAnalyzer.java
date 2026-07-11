package com.oAT.web.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.oAT.ai.service.LLMService;
import com.oAT.web.common.UtilJson;
import com.oAT.web.esDao.entity.StaticSourceInfo;
import com.oAT.web.verification.model.VerificationModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class VerificationAiAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(VerificationAiAnalyzer.class);
    private static final Set<String> TYPES = Set.of("MISSING_TESTCASE", "MISSING_NEGATIVE_CASE",
            "MISSING_BOUNDARY_CASE", "WRONG_EXPECTATION", "WEAK_ASSERTION", "INVALID_PRECONDITION",
            "MISSING_IMPLEMENTATION", "LOGIC_MISMATCH", "INCOMPLETE_BRANCH", "MISSING_VALIDATION",
            "ERROR_BEHAVIOR_MISMATCH", "FALSE_PASS_RISK", "AMBIGUOUS_REQUIREMENT", "ADVISORY");
    private static final String SYSTEM_PROMPT = """
            你是需求、测试与代码一致性验证器。只能根据提供的真实证据判断，禁止创造需求、用例、类、方法或行号。
            需求未明确规定的安全/性能建议只能标记 ADVISORY。资料不足时不要输出缺陷。
            只返回严格 JSON，不要 Markdown：
            {"findings":[{"type":"枚举","perspective":"PRODUCT|TEST|DEVELOPMENT|CROSS",
            "severity":"CRITICAL|HIGH|MEDIUM|LOW|INFO","title":"简短标题","description":"证据化说明",
            "suggestion":"可执行建议","confidence":0.0,"verdict":"PARTIAL|NOT_SATISFIED|AMBIGUOUS|NOT_VERIFIABLE"}]}
            """;

    private final LLMService llmService;

    public VerificationAiAnalyzer(LLMService llmService) {
        this.llmService = llmService;
    }

    public List<Finding> analyze(String baselineId, AcceptanceCriterion ac, List<TestcaseProjection> tests,
                                 List<StaticSourceInfo> sources, String sourceAssetEvidence) {
        if (!llmService.isAvailable()) return List.of();
        String response;
        try {
            response = llmService.chat(SYSTEM_PROMPT, buildEvidence(ac, tests, sources, sourceAssetEvidence));
        } catch (RuntimeException e) {
            logger.warn("AI verification failed for {}: {}", ac.acKey(), e.getMessage());
            return List.of();
        }
        if (!StringUtils.hasText(response)) return List.of();
        try {
            JsonNode root = UtilJson.getObjectMapper().readTree(stripFence(response));
            JsonNode items = root.path("findings");
            if (!items.isArray()) return List.of();
            List<Finding> result = new ArrayList<>();
            for (JsonNode item : items) {
                String type = item.path("type").asText("").toUpperCase(Locale.ROOT);
                if (!TYPES.contains(type)) continue;
                Perspective perspective = enumValue(Perspective.class, item.path("perspective").asText(), Perspective.CROSS);
                Severity severity = enumValue(Severity.class, item.path("severity").asText(), Severity.MEDIUM);
                Verdict verdict = enumValue(Verdict.class, item.path("verdict").asText(), Verdict.PARTIAL);
                double confidence = Math.max(0, Math.min(1, item.path("confidence").asDouble(0.5)));
                String title = truncate(item.path("title").asText(type), 512);
                String description = truncate(item.path("description").asText(""), 4000);
                if (!StringUtils.hasText(description)) continue;
                result.add(new Finding(UUID.randomUUID().toString(), baselineId, ac.id(), type, perspective,
                        severity, title, description, truncate(item.path("suggestion").asText(""), 4000),
                        confidence, sources.isEmpty() && !StringUtils.hasText(sourceAssetEvidence) ? EvidenceLevel.E1 : EvidenceLevel.E2, verdict,
                        ReviewStatus.PENDING, evidence(ac, tests, sources), null, null, null));
            }
            return result;
        } catch (Exception e) {
            logger.warn("Invalid AI verification JSON for {}: {}", ac.acKey(), e.getMessage());
            return List.of();
        }
    }

    private String buildEvidence(AcceptanceCriterion ac, List<TestcaseProjection> tests, List<StaticSourceInfo> sources,
                                 String sourceAssetEvidence) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("验收标准ID: ").append(ac.acKey()).append("\n需求ID: ").append(ac.requirementKey())
                .append("\n需求原文: ").append(ac.content()).append("\n\n真实测试用例:\n");
        if (tests.isEmpty()) prompt.append("(无)\n");
        for (TestcaseProjection test : tests.stream().limit(8).toList()) {
            prompt.append("- ").append(test.externalKey()).append(" | ").append(test.title())
                    .append(" | 前置:").append(value(test.preconditions())).append(" | 步骤:")
                    .append(truncate(value(test.steps()), 1000)).append(" | 预期:")
                    .append(truncate(value(test.expected()), 1000)).append('\n');
        }
        prompt.append("\n真实源码证据:\n");
        if (sources.isEmpty() && !StringUtils.hasText(sourceAssetEvidence))
            prompt.append("(当前未召回源码，不能据此断言一定未实现)\n");
        for (StaticSourceInfo source : sources.stream().limit(5).toList()) {
            prompt.append("- 类:").append(source.getClassInfo().getClassName()).append(" 方法:")
                    .append(source.getClassInfo().getMethodMaps() == null ? "[]" : source.getClassInfo().getMethodMaps().keySet())
                    .append("\n").append(truncate(value(source.getClassInfo().getSourceCode()), 3000)).append('\n');
        }
        if (StringUtils.hasText(sourceAssetEvidence)) {
            prompt.append("- 源码快照证据:\n").append(truncate(sourceAssetEvidence, 3000)).append('\n');
        }
        return prompt.toString();
    }

    private List<Map<String, Object>> evidence(AcceptanceCriterion ac, List<TestcaseProjection> tests,
                                               List<StaticSourceInfo> sources) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(Map.of("type", "AC", "id", ac.acKey(), "locator", ac.sourceLocator(), "content", ac.content()));
        for (TestcaseProjection test : tests.stream().limit(8).toList())
            result.add(Map.of("type", "TESTCASE", "id", test.externalKey(), "locator", test.sourceLocator()));
        for (StaticSourceInfo source : sources.stream().limit(5).toList())
            result.add(Map.of("type", "SOURCE_SYMBOL", "id", source.getClassInfo().getClassName()));
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

    private <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
        try { return Enum.valueOf(type, value.toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return fallback; }
    }

    private String value(String value) { return value == null ? "" : value; }
    private String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
