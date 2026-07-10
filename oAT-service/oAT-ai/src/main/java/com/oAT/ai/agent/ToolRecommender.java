package com.oAT.ai.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能工具推荐器
 * 根据用户问题内容，分析用户意图并推荐最可能需要的工具组合
 *
 * <p>功能：
 * 1. 意图识别：通过关键词匹配和模式识别判断用户意图
 * 2. 工具排序：根据意图对工具进行相关性评分
 * 3. 组合推荐：复杂问题可能需要多个工具协作
 * 4. 学习优化：根据工具调用成功率动态调整推荐权重
 * </p>
 */
public class ToolRecommender {

    private static final Logger logger = LoggerFactory.getLogger(ToolRecommender.class);

    /** 工具元数据 */
    public static class ToolMeta {
        final String toolName;          // 工具方法名
        final String displayName;       // 显示名称
        final String description;       // 功能描述
        final String[] keywords;        // 关联关键词
        final String[] relatedIntents;  // 相关意图标签
        double successRate = 1.0;       // 成功率（用于学习调整）
        int callCount = 0;              // 调用次数
        volatile long lastCalledTime = 0;

        public ToolMeta(String toolName, String displayName, String description,
                       String[] keywords, String[] relatedIntents) {
            this.toolName = toolName;
            this.displayName = displayName;
            this.description = description;
            this.keywords = keywords;
            this.relatedIntents = relatedIntents;
        }

        public String getToolName() { return toolName; }
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    /** 推荐结果 */
    public static class Recommendation {
        public final String primaryTool;           // 首选工具
        public final List<String> secondaryTools;  // 辅助工具列表
        public final String detectedIntent;        // 检测到的意图
        public final double confidence;            // 置信度 (0-1)
        public final String reasoning;             // 推荐理由

        public Recommendation(String primaryTool, List<String> secondaryTools,
                            String intent, double confidence, String reasoning) {
            this.primaryTool = primaryTool;
            this.secondaryTools = secondaryTools != null ? secondaryTools : Collections.emptyList();
            this.detectedIntent = intent;
            this.confidence = confidence;
            this.reasoning = reasoning;
        }
    }

    /** 已注册的工具 */
    private final Map<String, ToolMeta> tools = new ConcurrentHashMap<>();

    /** 意图 → 关键词映射 */
    private static final Map<String, Set<String>> INTENT_KEYWORDS = new LinkedHashMap<>();

    static {
        INTENT_KEYWORDS.put("coverage", Set.of("覆盖", "coverage", "行覆盖", "分支覆盖",
                "mcdc", "测试率", "代码覆盖率", "覆盖率", "未覆盖", "漏测", "覆盖率是多少", "这个项目的代码覆盖率", "项目覆盖率",
                "整体覆盖率", "覆盖率概览", "覆盖率最低", "哪个模块的覆盖率最低", "低覆盖模块", "低覆盖类", "覆盖率最高",
                "覆盖率趋势", "历史覆盖率", "最近覆盖率", "覆盖率报告"));
        INTENT_KEYWORDS.put("performance", Set.of("性能", "performance", "慢接口",
                "响应时间", "平均响应", "p50", "p95", "p99", "延迟", "耗时", "吞吐量", "调用频率", "回归", "退化", "基线", "性能回归", "性能退化"));
        INTENT_KEYWORDS.put("defect", Set.of("缺陷", "defect", "错误", "error",
                "异常", "exception", "bug", "故障", "HTTP错误", "5xx", "4xx", "定位", "根因", "线上", "异常定位", "根因定位", "线上缺陷"));
        INTENT_KEYWORDS.put("bug_detect", Set.of("bug", "可能存在", "潜在bug", "潜在问题", "代码缺陷",
                "源码缺陷", "类bug", "方法bug", "空指针", "资源泄漏", "并发问题", "逻辑错误", "代码风险", "方法风险",
                "方法可能存在", "方法有什么问题", "这个方法有问题吗", "这个类有问题吗", "批量检测", "批量扫描", "多个类", "这些类"));
        INTENT_KEYWORDS.put("testcase", Set.of("测试", "test", "用例", " testcase",
                "测试建议", "补充测试", "补测", "回归", "回归测试", "精准回归", "高风险", "补测建议", "测试用例推荐"));
        INTENT_KEYWORDS.put("app_status", Set.of("应用", "application", "app",
                "在线", "offline", "运行状态", "状态", "部署", "启动", "应用列表", "在线应用", "应用详情"));
        INTENT_KEYWORDS.put("trace", Set.of("链路", "trace", "调用链", "请求链路",
                "span", "上下游", "依赖关系", "最近调用链", "最近链路", "链路详情", "调用链详情", "traceid"));
        INTENT_KEYWORDS.put("snapshot", Set.of("快照", "snapshot", "版本比对",
                "历史数据", "快照详情", "我的快照", "个人快照", "版本", "上线", "发布", "发布前", "上线前"));
        INTENT_KEYWORDS.put("code_relation", Set.of("代码", "code", "类依赖",
                "调用关系", "callgraph", "接口关系", "影响分析", "类调用图", "方法调用链", "类关系",
                "调用图", "调用链图", "调用关系图", "类调用关系", "方法调用关系", "上下游",
                "谁调用", "调用了谁", "引用关系", "依赖关系", "搜索代码", "查找类", "查找方法", "找源码", "找类"));
        INTENT_KEYWORDS.put("business_logic", Set.of("业务需求", "业务逻辑", "业务规则", "业务场景",
                "处理什么业务", "需求分析", "功能逻辑", "方法逻辑", "方法职责",
                "业务含义", "功能需求", "类职责", "实现什么", "干什么", "做什么", "分析业务", "梳理业务"));
        INTENT_KEYWORDS.put("project_info", Set.of("项目", "project", "概览",
                "overview", "统计", "汇总", "总览", "项目概览", "项目总览"));
        INTENT_KEYWORDS.put("code_quality", Set.of("质量", "quality", "复杂度",
                "圈复杂度", "代码审查", "规范", "技术债", "高复杂度", "复杂度高", "代码质量", "质量报告"));
        INTENT_KEYWORDS.put("coverage_workflow", Set.of("生成覆盖率", "自动生成", "拉取代码",
                "生成报告", "覆盖率生成", "自动拉取", "生成覆盖率报告", "下载报告", "导出报告",
                "任务进度", "生成好了吗", "任务状态", "查询任务", "检查配置", "验证git",
                "git配置", "仓库配置", "自动化流程", "一键生成"));
    }

    /**
     * 注册工具到推荐器
     */
    public void registerTool(ToolMeta meta) {
        tools.put(meta.getToolName(), meta);
        logger.debug("Registered tool recommender: {} ({})", meta.toolName, meta.displayName);
    }

    /**
     * 分析问题并返回推荐结果
     */
    public Recommendation recommend(String question) {
        if (question == null || question.trim().isEmpty()) {
            return new Recommendation("getProjectOverview", Collections.emptyList(),
                    "general", 0.3, "空问题，使用默认工具");
        }

        String lowerQ = question.toLowerCase();

        Recommendation forcedRecommendation = recommendSourceAnalysisTool(lowerQ);
        if (forcedRecommendation != null) {
            return forcedRecommendation;
        }
        Map<String, Double> intentScores = scoreIntents(lowerQ);
        applyScenarioBoosts(lowerQ, intentScores);
        String topIntent = getTopIntent(intentScores);

        if (topIntent == null) {
            return new Recommendation("getProjectOverview", Collections.emptyList(),
                    "general", 0.2, "无法识别明确意图，使用项目概览");
        }

        // 根据意图找最佳工具
        List<ToolRanking> rankedTools = rankToolsByIntent(topIntent, intentScores.getOrDefault(topIntent, 0.0), lowerQ);

        if (rankedTools.isEmpty()) {
            return new Recommendation("getProjectOverview", Collections.emptyList(),
                    topIntent, 0.3, "无匹配工具，回退到概览");
        }

        String primary = rankedTools.get(0).meta.getToolName();
        List<String> secondary = new ArrayList<>();
        StringBuilder reason = new StringBuilder();

        for (int i = 0; i < Math.min(rankedTools.size(), 3); i++) {
            ToolRanking tr = rankedTools.get(i);
            if (i == 0) {
                reason.append("首选「").append(tr.meta.displayName).append("」")
                      .append(String.format("(相关度%.0f%%)", tr.score * 100));
            } else {
                secondary.add(tr.meta.getToolName());
                reason.append(", 辅助「").append(tr.meta.displayName).append("」");
            }
        }

        double confidence = rankedTools.get(0).score;
        return new Recommendation(primary, secondary, topIntent, confidence, reason.toString());
    }

    private Recommendation recommendSourceAnalysisTool(String lowerQuestion) {
        if (containsAny(lowerQuestion, "业务需求", "业务逻辑", "业务规则", "业务场景", "业务含义", "处理什么业务",
                "需求分析", "功能逻辑", "功能需求", "方法逻辑", "方法职责", "类职责", "实现什么", "干什么", "做什么", "分析业务", "梳理业务")) {
            return sourceAnalysisRecommendation("analyzeBusinessRequirement",
                    Arrays.asList("searchCodeRelation", "getClassCallGraph", "detectBugsInMethod"),
                    "business_logic", "业务需求/业务逻辑问题必须优先使用真实源码业务逻辑分析工具");
        }
        if (containsAny(lowerQuestion, "批量", "多个类", "这些类", "一批", "批量检测", "批量扫描", "批量分析" )
                && containsAny(lowerQuestion, "bug", "缺陷", "问题", "风险", "可能存在", "潜在", "代码缺陷", "源码缺陷")) {
            return sourceAnalysisRecommendation("batchDetectBugs",
                    Arrays.asList("searchCodeRelation", "detectBugs", "detectBugsInMethod"),
                    "bug_detect", "批量 Bug/缺陷问题必须优先使用批量源码检测工具");
        }
        if (containsAny(lowerQuestion, "方法", "method", "函数")
                && containsAny(lowerQuestion, "bug", "缺陷", "问题", "风险", "可能存在", "潜在", "空指针", "npe", "异常", "错误", "逻辑错误")) {
            return sourceAnalysisRecommendation("detectBugsInMethod",
                    Arrays.asList("searchCodeRelation", "detectBugs", "getCodeQualityReport"),
                    "bug_detect", "方法级 Bug/风险问题必须优先使用方法级源码检测工具");
        }
        return null;
    }

    private Recommendation sourceAnalysisRecommendation(String primaryTool, List<String> secondaryTools, String intent, String reason) {
        List<String> availableSecondary = new ArrayList<>();
        for (String toolName : secondaryTools) {
            if (!toolName.equals(primaryTool) && tools.containsKey(toolName)) {
                availableSecondary.add(toolName);
            }
        }
        return new Recommendation(primaryTool, availableSecondary, intent, 0.95, reason);
    }

    /**
     * 记录工具调用结果（用于学习优化）
     */
    public void recordToolCall(String toolName, boolean success) {
        ToolMeta meta = resolveToolMeta(toolName);
        if (meta == null) return;

        synchronized (meta) {
            meta.callCount++;
            meta.lastCalledTime = System.currentTimeMillis();
            if (success) {
                meta.successRate = meta.successRate * 0.9 + 0.1;
            } else {
                meta.successRate = meta.successRate * 0.9;
            }
        }
    }

    /**
     * 记录用户显式反馈对工具的评价（权重高于普通调用结果）
     */
    public void recordFeedback(String toolName, boolean positive) {
        ToolMeta meta = resolveToolMeta(toolName);
        if (meta == null) {
            return;
        }

        synchronized (meta) {
            meta.callCount++;
            meta.lastCalledTime = System.currentTimeMillis();
            if (positive) {
                meta.successRate = meta.successRate * 0.7 + 0.3;
            } else {
                meta.successRate = meta.successRate * 0.7;
            }
        }
        logger.debug("Recorded {} feedback for tool '{}', successRate={}",
                positive ? "positive" : "negative", meta.toolName, meta.successRate);
    }

    private ToolMeta resolveToolMeta(String toolName) {
        if (toolName == null || toolName.trim().isEmpty()) {
            return null;
        }
        String normalized = normalizeToolLookupKey(toolName);
        ToolMeta direct = tools.get(normalized);
        if (direct != null) {
            return direct;
        }
        for (ToolMeta meta : tools.values()) {
            if (normalized.equals(normalizeToolLookupKey(meta.toolName))
                    || normalized.equals(normalizeToolLookupKey(meta.displayName))) {
                return meta;
            }
        }
        return null;
    }

    private String normalizeToolLookupKey(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    /**
     * 获取所有已注册工具的列表
     */
    public Collection<ToolMeta> getAllTools() { return tools.values(); }

    public Optional<ToolMeta> getToolMeta(String toolName) {
        return Optional.ofNullable(tools.get(toolName));
    }

    /**
     * 获取推荐器统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("registeredTools", tools.size());

        int totalCalls = 0;
        List<Map<String, Object>> toolList = new java.util.ArrayList<>();
        for (ToolMeta meta : tools.values()) {
            totalCalls += meta.callCount;
            Map<String, Object> t = new java.util.HashMap<>();
            t.put("name", meta.toolName);
            t.put("displayName", meta.displayName);
            t.put("calls", meta.callCount);
            t.put("successRate", String.format("%.2f", meta.successRate));
            toolList.add(t);
        }
        stats.put("totalToolCalls", totalCalls);
        stats.put("toolDetails", toolList);

        return stats;
    }

    // ==================== 内部方法 ====================

    /**
     * 对各意图进行评分
     */
    private Map<String, Double> scoreIntents(String lowerQuestion) {
        Map<String, Double> scores = new HashMap<>();

        for (Map.Entry<String, Set<String>> entry : INTENT_KEYWORDS.entrySet()) {
            String intent = entry.getKey();
            Set<String> keywords = entry.getValue();

            double score = 0;
            for (String kw : keywords) {
                if (lowerQuestion.contains(kw)) {
                    // 完全匹配加分更多
                    score += kw.length() > 3 ? 1.5 : 0.8;
                }
            }
            // 归一化：按关键词命中比例计算
            if (!keywords.isEmpty()) {
                long hitCount = keywords.stream().filter(lowerQuestion::contains).count();
                score += (double) hitCount / keywords.size() * 2.0;
            }

            if (score > 0) {
                scores.put(intent, score);
            }
        }

        return scores;
    }

    private void applyScenarioBoosts(String lowerQuestion, Map<String, Double> scores) {
        if (containsAny(lowerQuestion, "bug", "可能存在", "潜在bug", "潜在问题", "代码缺陷", "源码缺陷", "空指针", "资源泄漏", "并发问题", "逻辑错误")) {
            boost(scores, "bug_detect", 5.0);
            boost(scores, "code_quality", 2.0);
        }
        if (containsAny(lowerQuestion, "业务需求", "业务逻辑", "业务规则", "业务场景", "处理什么业务", "需求分析", "功能逻辑", "方法职责")) {
            boost(scores, "business_logic", 5.0);
            boost(scores, "bug_detect", 2.0);
            boost(scores, "code_relation", 1.0);
        }
        if (containsAny(lowerQuestion, "调用关系", "调用图", "调用链图", "调用关系图", "类调用图", "方法调用链", "方法调用关系", "上下游", "谁调用", "调用了谁", "依赖关系")) {
            boost(scores, "code_relation", 5.5);
            boost(scores, "trace", 1.5);
        }
        if (containsAny(lowerQuestion, "搜索代码", "查找类", "查找方法", "找源码", "找类", "找方法", "在哪", "在哪里", "定位类", "定位方法")) {
            boost(scores, "code_relation", 4.0);
        }
        if (containsAny(lowerQuestion, "版本上线", "上线前", "发布前", "精准回归", "回归范围", "回归策略")) {
            boost(scores, "snapshot", 3.0);
            boost(scores, "coverage", 2.5);
            boost(scores, "testcase", 2.5);
            boost(scores, "code_relation", 1.5);
        }
        if (containsAny(lowerQuestion, "线上缺陷", "快速定位", "故障定位", "根因定位", "异常定位")) {
            boost(scores, "defect", 3.5);
            boost(scores, "trace", 3.0);
            boost(scores, "performance", 1.0);
        }
        if (containsAny(lowerQuestion, "低覆盖", "高风险", "补测", "测试盲区", "覆盖缺口")) {
            boost(scores, "coverage", 3.0);
            boost(scores, "testcase", 3.0);
            boost(scores, "code_quality", 1.5);
        }
        if (containsAny(lowerQuestion, "性能回归", "性能退化", "耗时变慢", "基线对比", "回归分析")) {
            boost(scores, "performance", 3.5);
            boost(scores, "trace", 2.5);
        }
    }

    private void boost(Map<String, Double> scores, String intent, double delta) {
        scores.merge(intent, delta, Double::sum);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取得分最高的意图
     */
    private String getTopIntent(Map<String, Double> scores) {
        return scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * 根据意图对工具进行排名
     */
    private List<ToolRanking> rankToolsByIntent(String topIntent, double intentScore, String lowerQuestion) {
        List<ToolRanking> rankings = new ArrayList<>();

        for (ToolMeta meta : tools.values()) {
            double score = 0;

            // 基于意图匹配
            if (meta.relatedIntents != null) {
                for (String ri : meta.relatedIntents) {
                    if (ri.equals(topIntent)) {
                        score += 10.0;
                        break;
                    }
                }
            }

            // 基于关键词匹配
            if (meta.keywords != null) {
                for (String keyword : meta.keywords) {
                    if (keyword != null && lowerQuestion.contains(keyword.toLowerCase())) {
                        score += keyword.length() > 3 ? 2.0 : 1.0;
                    }
                }
            }

            score += scenarioToolBoost(meta.toolName, lowerQuestion);

            // 基于历史成功率微调
            score *= (0.7 + meta.successRate * 0.3);

            if (score > 0) {
                rankings.add(new ToolRanking(meta, score));
            }
        }

        rankings.sort((a, b) -> Double.compare(b.score, a.score));
        return rankings;
    }

    private double scenarioToolBoost(String toolName, String lowerQuestion) {
        if (containsAny(lowerQuestion, "bug", "可能存在", "潜在bug", "潜在问题", "代码缺陷", "源码缺陷", "空指针", "资源泄漏", "并发问题", "逻辑错误")) {
            if (containsAny(lowerQuestion, "方法", "method", "函数") && "detectBugsInMethod".equals(toolName)) {
                return 20.0;
            }
            if (containsAny(lowerQuestion, "批量", "多个类", "这些类", "一批") && "batchDetectBugs".equals(toolName)) {
                return 20.0;
            }
            if (Set.of("detectBugs", "detectBugsInMethod", "batchDetectBugs", "getCodeQualityReport", "searchCodeRelation").contains(toolName)) {
                return 12.0;
            }
        }
        if (containsAny(lowerQuestion, "业务需求", "业务逻辑", "业务规则", "业务场景", "处理什么业务", "需求分析", "功能逻辑", "方法职责")) {
            if (Set.of("analyzeBusinessRequirement", "searchCodeRelation", "getClassCallGraph", "getCallGraph", "detectBugsInMethod", "detectBugs").contains(toolName)) {
                return 12.0;
            }
        }
        if (containsAny(lowerQuestion, "调用关系", "调用图", "调用链图", "调用关系图", "类调用图", "方法调用链", "方法调用关系", "上下游", "谁调用", "调用了谁", "依赖关系")) {
            if (Set.of("getClassCallGraph", "getCallGraph", "searchCodeRelation", "analyzeMethodCallChain").contains(toolName)) {
                return 16.0;
            }
        }
        if (containsAny(lowerQuestion, "搜索代码", "查找类", "查找方法", "找源码", "找类", "找方法", "在哪", "在哪里", "定位类", "定位方法")) {
            if ("searchCodeRelation".equals(toolName)) {
                return 16.0;
            }
            if (Set.of("getClassCallGraph", "getCallGraph").contains(toolName)) {
                return 8.0;
            }
        }
        if (containsAny(lowerQuestion, "版本上线", "上线前", "发布前", "精准回归", "回归范围", "回归策略")) {
            if (Set.of("getProjectCoverageOverview", "getLowCoverageClasses", "recommendTestcases", "compareCoverage", "searchCodeRelation", "getCallGraph", "getSnapshots").contains(toolName)) {
                return 8.0;
            }
        }
        if (containsAny(lowerQuestion, "线上缺陷", "快速定位", "故障定位", "根因定位", "异常定位")) {
            if (Set.of("getDefectOverview", "getRecentExceptions", "getAppErrorDetails", "getRecentTraces", "locateRootCause", "analyzeCallChain").contains(toolName)) {
                return 8.0;
            }
        }
        if (containsAny(lowerQuestion, "低覆盖", "高风险", "补测", "测试盲区", "覆盖缺口")) {
            if (Set.of("getLowCoverageClasses", "recommendTestcases", "getCoverageImprovementSuggestions", "compareCoverage", "getHighComplexityMethods").contains(toolName)) {
                return 8.0;
            }
        }
        if (containsAny(lowerQuestion, "性能回归", "性能退化", "耗时变慢", "基线对比", "回归分析")) {
            if (Set.of("compareOverTime", "getAppPerformanceOverview", "getSlowEndpoints", "getEndpointCallFrequency", "analyzeUrlCallPattern").contains(toolName)) {
                return 8.0;
            }
        }
        if (containsAny(lowerQuestion, "生成覆盖率", "拉取代码", "自动生成", "生成报告", "覆盖率生成")) {
            if ("generateCoverageReport".equals(toolName)) return 20.0;
            if (Set.of("checkGitConfiguration", "queryJobStatus").contains(toolName)) return 8.0;
        }
        if (containsAny(lowerQuestion, "任务进度", "生成好了吗", "任务状态", "查询任务")) {
            if ("queryJobStatus".equals(toolName)) return 20.0;
        }
        if (containsAny(lowerQuestion, "下载报告", "导出报告", "获取报告")) {
            if ("downloadCoverageReport".equals(toolName)) return 20.0;
        }
        if (containsAny(lowerQuestion, "检查配置", "git配置", "验证git", "仓库配置")) {
            if ("checkGitConfiguration".equals(toolName)) return 20.0;
        }
        return 0.0;
    }

    private static class ToolRanking {
        final ToolMeta meta;
        final double score;
        ToolRanking(ToolMeta meta, double score) {
            this.meta = meta;
            this.score = score;
        }
    }

    // ==================== 预定义工具元数据 ====================

    /**
     * 创建所有内置工具的元数据并注册
     * 在 AIAgentService 初始化时调用
     */
    public void registerAllBuiltInTools() {
        registerTool(new ToolMeta("getProjectInfo", "项目信息查询",
                "获取项目基本信息和配置", new String[]{"项目", "project", "信息"}, new String[]{"project_info"}));

        registerTool(new ToolMeta("getProjectStatistics", "项目统计",
                "获取项目的整体统计数据", new String[]{"统计", "statistics", "数据"}, new String[]{"project_info"}));

        registerTool(new ToolMeta("getApps", "应用列表",
                "获取项目下所有应用的列表", new String[]{"应用", "app", "列表"}, new String[]{"app_status"}));

        registerTool(new ToolMeta("getOnlineApps", "在线应用",
                "获取当前在线运行的应用", new String[]{"在线", "online", "运行中"}, new String[]{"app_status"}));

        registerTool(new ToolMeta("getAppDetail", "应用详情",
                "获取指定应用的详细信息", new String[]{"应用详情", "detail", "appId"}, new String[]{"app_status"}));

        registerTool(new ToolMeta("getRecentTraces", "最近调用链",
                "查询最近的调用链记录", new String[]{"最近链路", "recent", "traces"}, new String[]{"trace"}));

        registerTool(new ToolMeta("getTraceDetail", "调用链详情",
                "查看单条调用链的详细信息", new String[]{"链路详情", "traceDetail", "span"}, new String[]{"trace"}));

        registerTool(new ToolMeta("getTracesByAppName", "按应用查链路",
                "查询指定应用的调用链", new String[]{"应用链路", "tracesByApp"}, new String[]{"trace", "app_status"}));

        registerTool(new ToolMeta("getTracesByApp", "按应用ID查链路",
                "按应用ID获取调用链列表", new String[]{"应用ID链路", "appId链路", "trace app"}, new String[]{"trace", "app_status"}));

        registerTool(new ToolMeta("searchCodeRelation", "代码关系搜索",
                "搜索代码中的调用/引用关系", new String[]{"代码关系", "searchRelation"}, new String[]{"code_relation"}));

        registerTool(new ToolMeta("getCallGraph", "调用图",
                "获取方法的完整调用图", new String[]{"调用图", "callGraph", "依赖"}, new String[]{"code_relation"}));

        registerTool(new ToolMeta("getAppPerformanceOverview", "性能概览",
                "获取应用的整体性能指标", new String[]{"性能", "performance", "概览"}, new String[]{"performance"}));

        registerTool(new ToolMeta("getSlowEndpoints", "慢接口分析",
                "找出响应时间最长的接口", new String[]{"慢接口", "slow", "endpoint"}, new String[]{"performance"}));

        registerTool(new ToolMeta("getEndpointCallFrequency", "接口调用频次",
                "统计各接口的调用次数", new String[]{"调用频率", "frequency", "热点"}, new String[]{"performance"}));

        registerTool(new ToolMeta("getDefectOverview", "缺陷概览",
                "获取缺陷和错误的总体情况", new String[]{"缺陷", "defect", "概览"}, new String[]{"defect"}));

        registerTool(new ToolMeta("getAppErrorDetails", "错误详情",
                "查看具体的 HTTP 错误请求", new String[]{"错误", "error", "details"}, new String[]{"defect"}));

        registerTool(new ToolMeta("getRecentExceptions", "最近异常",
                "获取最近发生的异常列表", new String[]{"异常", "exception", "最近"}, new String[]{"defect"}));

        registerTool(new ToolMeta("getEndpointTestSuggestions", "接口测试建议",
                "针对特定接口给出测试场景建议", new String[]{"接口测试", "suggestions"}, new String[]{"testcase"}));

        registerTool(new ToolMeta("compareOverTime", "性能回归分析",
                "对比同一接口在不同时间段的调用链，识别性能退化", new String[]{"性能回归", "退化", "基线"}, new String[]{"performance", "trace"}));

        registerTool(new ToolMeta("compareCallChains", "调用链差异比对",
                "对比两条调用链的路径、性能和错误差异", new String[]{"链路对比", "调用链差异", "正常异常对比"}, new String[]{"trace", "defect", "performance"}));

        registerTool(new ToolMeta("locateRootCause", "异常根因定位",
                "对比正常和异常调用链，定位线上缺陷根因", new String[]{"根因", "线上缺陷", "异常定位"}, new String[]{"defect", "trace"}));

        registerTool(new ToolMeta("analyzeCallChain", "单链路深度分析",
                "分析单条调用链的拓扑、性能瓶颈和异常根因", new String[]{"链路分析", "trace", "瓶颈", "根因"}, new String[]{"trace", "defect", "performance"}));

        registerTool(new ToolMeta("analyzeRecentCallChains", "最近调用链汇总分析",
                "对最近多条调用链做汇总分析，发现共性问题和模式", new String[]{"最近链路", "链路汇总", "批量分析"}, new String[]{"trace", "performance", "defect"}));

        registerTool(new ToolMeta("analyzeMethodCallChain", "方法调用链分析",
                "基于真实调用图/Trace 分析类或方法的上下游调用关系",
                new String[]{"方法调用链", "类调用链", "调用关系", "调用图", "上下游", "谁调用", "调用了谁"},
                new String[]{"trace", "code_relation"}));

        registerTool(new ToolMeta("analyzeUrlCallPattern", "URL调用模式分析",
                "分析接口URL的典型路径、慢请求规律和异常情况", new String[]{"URL", "接口", "调用模式", "慢请求"}, new String[]{"trace", "performance"}));

        registerTool(new ToolMeta("getCodeQualityReport", "代码质量报告",
                "评估代码的整体质量状况", new String[]{"质量报告", "quality", "report"}, new String[]{"code_quality"}));

        registerTool(new ToolMeta("getProjectOverview", "项目概览",
                "获取项目基本信息、应用状态和覆盖率概览", new String[]{"项目概览", "项目总览", "overview"}, new String[]{"project_info"}));

        registerTool(new ToolMeta("getHighComplexityMethods", "高复杂度方法",
                "找出圈复杂度过高的方法", new String[]{"复杂度", "complexity", "高复杂"}, new String[]{"code_quality"}));

        registerTool(new ToolMeta("detectBugs", "类级别 Bug 检测",
                "分析指定 Java 类源码中的空指针、资源泄漏、并发和逻辑风险", new String[]{"bug", "可能存在", "Bug检测", "源码缺陷", "代码缺陷", "空指针", "类风险"}, new String[]{"bug_detect", "code_quality"}));

        registerTool(new ToolMeta("detectBugsInMethod", "方法级深度 Bug 检测",
                "对指定方法或代码片段进行逐行级缺陷分析；用户问某个方法可能存在的 Bug/风险时优先使用",
                new String[]{"bug", "可能存在", "方法Bug", "方法风险", "方法可能存在", "这个方法有问题吗", "代码审查", "逐行分析", "空指针", "逻辑错误"},
                new String[]{"bug_detect", "code_quality", "business_logic"}));

        registerTool(new ToolMeta("analyzeBusinessRequirement", "源码业务逻辑分析",
                "基于真实源码分析类或方法承载的业务需求和业务规则；用户问业务需求/业务逻辑时优先使用",
                new String[]{"业务需求", "业务逻辑", "业务规则", "业务场景", "业务含义", "方法职责", "类职责", "需求分析", "功能逻辑", "实现什么", "干什么", "做什么"},
                new String[]{"business_logic", "bug_detect", "code_quality", "code_relation"}));

        registerTool(new ToolMeta("batchDetectBugs", "批量 Bug 检测",
                "对多个 Java 类进行批量缺陷扫描并生成汇总报告；用户问多个类/批量扫描时优先使用",
                new String[]{"批量Bug", "批量扫描", "批量检测", "批量分析", "多个类", "这些类", "一批", "缺陷报告"},
                new String[]{"bug_detect", "code_quality"}));

        registerTool(new ToolMeta("searchAppByName", "搜索应用",
                "按名称搜索应用", new String[]{"搜索应用", "searchApp"}, new String[]{"app_status"}));

        registerTool(new ToolMeta("getClassCallGraph", "类调用图",
                "获取类级别的真实调用关系图",
                new String[]{"类调用图", "类调用关系", "类依赖", "类关系", "调用关系图", "调用图", "上下游"},
                new String[]{"code_relation", "trace", "business_logic"}));

        registerTool(new ToolMeta("checkGitConfiguration", "检查 Git 配置",
                "验证应用的 Git 仓库地址和访问权限是否正确",
                new String[]{"检查配置", "git配置", "验证git", "仓库配置", "git权限"},
                new String[]{"coverage_workflow"}));

        logger.info("Registered {} built-in tools in recommender", tools.size());
    }
}
