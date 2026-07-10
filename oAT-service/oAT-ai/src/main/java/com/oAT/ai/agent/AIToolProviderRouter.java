package com.oAT.ai.agent;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AIToolProviderRouter {
    private static final Logger logger = LoggerFactory.getLogger(AIToolProviderRouter.class);

    private final ToolRecommender toolRecommender;
    private final Map<String, AIToolProviderEntry> toolProviderEntries;
    private final int maxRelevantToolsPerRequest;

    AIToolProviderRouter(ToolRecommender toolRecommender,
                         Map<String, AIToolProviderEntry> toolProviderEntries,
                         int maxRelevantToolsPerRequest) {
        this.toolRecommender = toolRecommender;
        this.toolProviderEntries = toolProviderEntries;
        this.maxRelevantToolsPerRequest = maxRelevantToolsPerRequest;
    }

    ToolProviderResult provideRelevantTools(ToolProviderRequest request) {
        String userMessage = extractUserMessageText(request.userMessage());
        String question = extractCurrentQuestionForRouting(userMessage);
        if (question == null || question.trim().isEmpty()) {
            question = userMessage;
        }
        ToolRecommender.Recommendation recommendation = toolRecommender.recommend(question);
        LinkedHashSet<String> relevantToolNames = resolveRelevantToolNames(question, recommendation);

        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        int added = 0;
        for (String toolName : relevantToolNames) {
            AIToolProviderEntry entry = toolProviderEntries.get(normalizeName(toolName));
            if (entry == null) {
                continue;
            }
            builder.add(entry.specification, entry.executor);
            added++;
            if (added >= maxRelevantToolsPerRequest) {
                break;
            }
        }

        if (added == 0) {
            addToolIfPresent(builder, "getProjectOverview");
            addToolIfPresent(builder, "getProjectInfo");
            addToolIfPresent(builder, "getProjectStatistics");
            added = 3;
        }

        logger.debug("Provided {} relevant AI tools for intent={}, primary={}, question={}",
                added, recommendation.detectedIntent, recommendation.primaryTool, abbreviate(question, 80));
        return builder.build();
    }

    private void addToolIfPresent(ToolProviderResult.Builder builder, String toolName) {
        AIToolProviderEntry entry = toolProviderEntries.get(normalizeName(toolName));
        if (entry != null) {
            builder.add(entry.specification, entry.executor);
        }
    }

    private LinkedHashSet<String> resolveRelevantToolNames(String question, ToolRecommender.Recommendation recommendation) {
        LinkedHashSet<String> toolNames = new LinkedHashSet<>();
        if (recommendation != null) {
            addIfNotBlank(toolNames, recommendation.primaryTool);
            if (recommendation.secondaryTools != null) {
                recommendation.secondaryTools.forEach(toolName -> addIfNotBlank(toolNames, toolName));
            }
        }

        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "业务需求", "业务逻辑", "业务规则", "业务场景", "处理什么业务", "需求分析", "功能逻辑", "方法职责")) {
            addAll(toolNames, "searchCodeRelation", "analyzeBusinessRequirement", "getClassCallGraph", "detectBugsInMethod");
        } else if (containsAny(normalized, "bug", "可能存在", "潜在bug", "潜在问题", "代码缺陷", "源码缺陷", "空指针", "资源泄漏", "并发问题", "逻辑错误")) {
            addAll(toolNames, "searchCodeRelation", "detectBugsInMethod", "detectBugs", "batchDetectBugs", "getCodeQualityReport");
        } else if (containsAny(normalized, "性能", "performance", "慢接口", "响应时间", "p95", "p99", "耗时", "退化")) {
            addAll(toolNames, "getAppPerformanceOverview", "getSlowEndpoints", "getEndpointCallFrequency", "compareOverTime", "getApps", "searchAppByName");
        } else if (containsAny(normalized, "缺陷", "defect", "错误", "error", "异常", "exception", "根因")) {
            addAll(toolNames, "getDefectOverview", "getRecentExceptions", "getAppErrorDetails", "getRecentTraces", "locateRootCause", "getApps");
        } else if (containsAny(normalized, "链路", "trace", "调用链", "span", "链路详情")) {
            addAll(toolNames, "getRecentTraces", "getTracesByAppName", "getTraceDetail", "analyzeCallChain", "analyzeMethodCallChain", "getClassCallGraph", "getCallGraph", "getApps", "searchAppByName");
        } else if (containsAny(normalized, "版本", "上线", "发布")) {
            addAll(toolNames, "searchCodeRelation", "getClassCallGraph", "getRecentTraces", "getApps");
        } else if (containsAny(normalized, "应用", "app", "在线", "运行状态", "状态")) {
            addAll(toolNames, "getApps", "getOnlineApps", "searchAppByName", "getAppDetail");
        } else if (containsAny(normalized, "代码", "类", "方法", "调用关系", "调用图", "上下游", "依赖")) {
            addAll(toolNames, "searchCodeRelation", "getClassCallGraph", "getCallGraph", "analyzeMethodCallChain");
        } else {
            addAll(toolNames, "getProjectOverview", "getProjectInfo", "getProjectStatistics", "getApps");
        }

        if (question != null) {
            String loweredQuestion = question.toLowerCase(Locale.ROOT);
            if (containsAny(loweredQuestion, "调用链", "调用关系", "上下游", "调用图", "链路")
                    && containsAny(loweredQuestion, "方法", "method", "函数")) {
                addAll(toolNames, "getCallGraph", "analyzeMethodCallChain", "getClassCallGraph");
            }
            if (containsAny(loweredQuestion, "bug", "缺陷", "风险", "可能存在", "潜在")
                    && containsAny(loweredQuestion, "方法", "method", "函数")) {
                addAll(toolNames, "detectBugsInMethod", "searchCodeRelation");
            }
        }

        return toolNames;
    }

    private void addAll(Set<String> target, String... values) {
        for (String value : values) {
            addIfNotBlank(target, value);
        }
    }

    private void addIfNotBlank(Set<String> target, String value) {
        if (value != null && !value.trim().isEmpty()) {
            target.add(value.trim());
        }
    }

    private String extractUserMessageText(UserMessage userMessage) {
        if (userMessage == null) {
            return "";
        }
        try {
            if (userMessage.hasSingleText()) {
                return userMessage.singleText();
            }
        } catch (Exception ignored) {
        }
        return userMessage.toString();
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private String extractCurrentQuestionForRouting(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return "";
        }
        int markerIndex = userMessage.lastIndexOf("[当前问题]");
        if (markerIndex < 0) {
            return userMessage.trim();
        }
        String question = userMessage.substring(markerIndex + "[当前问题]".length()).trim();
        return question.isEmpty() ? userMessage.trim() : question;
    }


    private String normalizeName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String normalized = rawName.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
