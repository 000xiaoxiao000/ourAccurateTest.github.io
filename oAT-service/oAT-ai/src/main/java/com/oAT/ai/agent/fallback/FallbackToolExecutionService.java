package com.oAT.ai.agent.fallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FallbackToolExecutionService {
    private static final Logger logger = LoggerFactory.getLogger(FallbackToolExecutionService.class);
    private static final int MAX_FALLBACK_REPORTS = 20;

    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "(?:```\\s*json\\s*)?\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{.*?})\\s*\\}(?:```)?",
            Pattern.DOTALL);

    private static final Map<String, List<String>> SAFE_FALLBACK_TOOL_CANDIDATES = createSafeFallbackToolCandidates();

    private final Map<String, FallbackToolMethodBinding> toolMethodBindings;
    private final Map<String, FallbackToolMethodSchema> toolMethodSchemas;
    private final FallbackArgumentBinder fallbackArgumentBinder;
    private final Deque<FallbackReport> recentFallbackReports = new ArrayDeque<>();

    public FallbackToolExecutionService(Map<String, FallbackToolMethodBinding> toolMethodBindings,
                                        Map<String, FallbackToolMethodSchema> toolMethodSchemas,
                                        FallbackArgumentBinder fallbackArgumentBinder) {
        this.toolMethodBindings = toolMethodBindings;
        this.toolMethodSchemas = toolMethodSchemas;
        this.fallbackArgumentBinder = fallbackArgumentBinder;
    }

    public String tryExecute(String response) {
        FallbackReport report = new FallbackReport();
        report.startedAt = System.currentTimeMillis();
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        Matcher matcher = TOOL_CALL_PATTERN.matcher(response.trim());
        if (!matcher.find()) {
            return null;
        }

        String toolName = matcher.group(1);
        String argsJson = matcher.group(2);
        String normalizedToolName = normalizeName(toolName);
        report.toolName = toolName;

        FallbackToolMethodBinding binding = toolMethodBindings.get(normalizedToolName);
        if (binding == null) {
            logger.debug("Response contains tool-like format but '{}' is not a registered tool, treating as normal response", toolName);
            return null;
        }

        FallbackToolMethodSchema schema = toolMethodSchemas.get(normalizedToolName);
        report.schemaDescription = schema != null ? schema.describe() : null;

        logger.info("Fallback: executing tool '{}' with raw arguments: {}", toolName, argsJson);

        try {
            Map<String, Object> args = parseArgsJson(argsJson);
            if (schema != null) {
                logger.debug("Fallback schema [{}]: {}", toolName, schema.describe());
            }
            FallbackExecutionResult executionResult = fallbackArgumentBinder.execute(binding, args, schema, false, report);
            if (!executionResult.success && isBindingFailure(executionResult.error)) {
                FallbackExecutionResult candidateResult = tryFallbackCandidateTools(toolName, args, report);
                if (candidateResult.success) {
                    executionResult = candidateResult;
                }
            }
            if (!executionResult.success) {
                report.retried = true;
                logger.warn("Fallback strict binding failed for tool '{}', retry with relaxed strategy: {}",
                        toolName, executionResult.errorMessage);
                executionResult = fallbackArgumentBinder.execute(binding, args, schema, true, report);
            }
            if (!executionResult.success) {
                throw executionResult.error != null ? executionResult.error
                        : new IllegalArgumentException(executionResult.errorMessage);
            }

            String resultStr = executionResult.result != null ? executionResult.result.toString() : "（无返回数据）";
            report.success = true;
            report.resultLength = resultStr.length();
            report.finishedAt = System.currentTimeMillis();
            logger.info("Fallback: tool '{}' executed successfully, result length: {}", toolName, resultStr.length());
            storeFallbackReport(report);
            logger.debug("Fallback report: {}", report.describe());
            return resultStr;

        } catch (Exception e) {
            report.success = false;
            report.errorMessage = e.getMessage();
            report.finishedAt = System.currentTimeMillis();
            logger.error("Fallback: failed to execute tool '{}': {}", toolName, e.getMessage(), e);
            storeFallbackReport(report);
            logger.debug("Fallback report: {}", report.describe());
            return "抱歉，AI 助手在查询数据时遇到了错误：" + e.getMessage();
        }
    }

    public FallbackReport getLatestFallbackReport() {
        synchronized (recentFallbackReports) {
            FallbackReport latest = recentFallbackReports.peekFirst();
            return latest != null ? latest.copy() : null;
        }
    }

    public List<FallbackReport> getRecentFallbackReports(int limit) {
        synchronized (recentFallbackReports) {
            List<FallbackReport> reports = new ArrayList<>();
            int count = 0;
            for (FallbackReport report : recentFallbackReports) {
                if (limit > 0 && count >= limit) {
                    break;
                }
                reports.add(report.copy());
                count++;
            }
            return reports;
        }
    }

    private FallbackExecutionResult tryFallbackCandidateTools(String originalToolName, Map<String, Object> args,
                                                             FallbackReport report) {
        List<String> candidates = SAFE_FALLBACK_TOOL_CANDIDATES.get(normalizeName(originalToolName));
        if (candidates == null || candidates.isEmpty()) {
            return FallbackExecutionResult.failure(new IllegalArgumentException("no fallback candidate"));
        }
        for (String candidateToolName : candidates) {
            FallbackToolMethodBinding candidateBinding = toolMethodBindings.get(normalizeName(candidateToolName));
            FallbackToolMethodSchema candidateSchema = toolMethodSchemas.get(normalizeName(candidateToolName));
            if (candidateBinding == null) {
                continue;
            }
            FallbackReport candidateReport = report != null ? report.copy() : null;
            if (candidateReport != null) {
                candidateReport.candidateToolName = candidateToolName;
            }
            FallbackExecutionResult candidateResult = fallbackArgumentBinder.execute(candidateBinding, args,
                    candidateSchema, false, candidateReport);
            if (candidateResult.success) {
                if (report != null && candidateReport != null) {
                    report.candidateToolName = candidateToolName;
                    report.strategy = candidateReport.strategy;
                    report.parameterReports.clear();
                    report.parameterReports.addAll(candidateReport.parameterReports);
                }
                return candidateResult;
            }
        }
        return FallbackExecutionResult.failure(new IllegalArgumentException("fallback candidates failed"));
    }

    private boolean isBindingFailure(Throwable error) {
        if (error == null) {
            return false;
        }
        Throwable current = error;
        while (current != null) {
            if (current instanceof IllegalArgumentException || current instanceof NumberFormatException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgsJson(String argsJson) {
        if (argsJson == null || argsJson.trim().isEmpty() || "{}".equals(argsJson.trim())) {
            return new LinkedHashMap<>();
        }
        Object parsed = new LightweightJsonParser(argsJson).parseValue();
        if (!(parsed instanceof Map<?, ?> parsedMap)) {
            throw new IllegalArgumentException("工具参数必须是 JSON 对象");
        }
        return (Map<String, Object>) parsedMap;
    }

    private void storeFallbackReport(FallbackReport report) {
        if (report == null) {
            return;
        }
        synchronized (recentFallbackReports) {
            recentFallbackReports.addFirst(report.copy());
            while (recentFallbackReports.size() > MAX_FALLBACK_REPORTS) {
                recentFallbackReports.removeLast();
            }
        }
    }

    private static Map<String, List<String>> createSafeFallbackToolCandidates() {
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        candidates.put(normalizeStaticName("getProjectOverview"), Arrays.asList("getProjectInfo", "getProjectStatistics"));
        candidates.put(normalizeStaticName("getAppCoverageReport"), Collections.singletonList("getAppCoverageTrend"));
        candidates.put(normalizeStaticName("getAppCoverageTrend"), Collections.singletonList("getAppCoverageReport"));
        candidates.put(normalizeStaticName("getTracesByApp"), Collections.singletonList("getRecentTraces"));
        candidates.put(normalizeStaticName("getTracesByAppName"), Collections.singletonList("getRecentTraces"));
        candidates.put(normalizeStaticName("getMySnapshots"), Collections.singletonList("getSnapshots"));
        return candidates;
    }

    private static String normalizeStaticName(String rawName) {
        if (rawName == null) {
            return "";
        }
        return rawName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    }

    private String normalizeName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String normalized = rawName.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    }
}
