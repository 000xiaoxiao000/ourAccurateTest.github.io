package com.oAT.ai.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AIPromptGuideBuilder {
    private final ToolRecommender toolRecommender;

    public AIPromptGuideBuilder(ToolRecommender toolRecommender) {
        this.toolRecommender = toolRecommender;
    }

    public boolean isSourceMethodBugContext(String question, String pageContext) {
        String text = ((question == null ? "" : question) + " " + (pageContext == null ? "" : pageContext)).toLowerCase(Locale.ROOT);
        return text.contains("当前源码片段")
                && (text.contains("目标方法") || text.contains("当前选中行") || text.contains("方法覆盖信息"))
                && containsAny(text, "bug", "可能存在", "潜在bug", "潜在问题", "代码缺陷", "源码缺陷", "空指针", "资源泄漏", "并发问题", "逻辑错误", "风险")
                && containsAny(text, "方法", "method", "函数", "此方法", "这个方法", "目标方法");
    }

    public String buildScenarioGuide(String question, ToolRecommender.Recommendation recommendation) {
        if (question == null || question.trim().isEmpty() || recommendation == null) {
            return "";
        }

        String normalized = question.toLowerCase(Locale.ROOT);
        List<String> preferredTools = new ArrayList<>();
        String scenario = null;
        String answerFocus = null;

        if (containsAny(normalized, "业务需求", "业务逻辑", "业务规则", "业务场景", "处理什么业务", "需求分析", "功能逻辑", "方法职责")) {
            scenario = "源码业务逻辑分析";
            preferredTools.addAll(Arrays.asList("searchCodeRelation", "analyzeBusinessRequirement", "getClassCallGraph", "detectBugsInMethod", "detectBugs"));
            answerFocus = "必须先用 searchCodeRelation 定位源码中的真实类/方法；再用 analyzeBusinessRequirement 分析真实源码，必要时用 getClassCallGraph 补充真实上下游。只能基于源码中的真实类名、方法名、参数、分支和返回值分析业务规则。无法从源码确认的需求要明确说明，禁止使用示例类名、示例链接或猜测的业务流程。";
        } else if (containsAny(normalized, "bug", "可能存在", "潜在bug", "潜在问题", "代码缺陷", "源码缺陷", "空指针", "资源泄漏", "并发问题", "逻辑错误")) {
            scenario = "源码 Bug 检测";
            preferredTools.addAll(Arrays.asList("searchCodeRelation", "detectBugsInMethod", "batchDetectBugs", "detectBugs", "getCodeQualityReport"));
            answerFocus = "必须先用 searchCodeRelation 定位源码中的真实类/方法；用户问方法必须优先调用 detectBugsInMethod，用户问多个类/批量扫描必须优先调用 batchDetectBugs，用户问单个类再调用 detectBugs。直接输出潜在 bug、触发条件、影响和修复建议；不要改查调用链，也不要回答“未找到调用链数据”。";
        } else if (containsAny(normalized, "版本上线", "上线前", "发布前", "精准回归", "回归范围", "回归策略")) {
            scenario = "版本上线前的精准回归";
            preferredTools.addAll(Arrays.asList("searchCodeRelation", "getClassCallGraph", "getRecentTraces", "getApps"));
            answerFocus = "输出变更影响面、高风险模块、推荐回归路径和上线前准入风险；缺少版本/应用/接口信息时，先用源码关系、应用和调用链数据给出可执行排查路径，不要只回答方法论。";
        } else if (containsAny(normalized, "调用链", "调用关系", "上下游", "谁调用", "调用了谁", "依赖关系")
                && containsAny(normalized, "方法", "method", "函数")) {
            scenario = "方法真实调用关系分析";
            preferredTools.addAll(Arrays.asList("searchCodeRelation", "getClassCallGraph", "getCallGraph", "analyzeMethodCallChain", "analyzeCallChain"));
            answerFocus = "必须先用 searchCodeRelation 根据类名/方法名定位真实代码对象；类级调用图优先用 getClassCallGraph，方法级调用关系用 getCallGraph 或 analyzeMethodCallChain。只输出工具返回的真实调用方、被调用方和 Trace 数据；如果工具返回无真实调用关系数据，要明确说明暂无真实数据，禁止生成 example.com 链接、示意图链接、methodA/helperMethod 或任何源码中不存在的方法。";
        } else if (containsAny(normalized, "复杂度", "高复杂度", "圈复杂度", "代码复杂度", "复杂度高")) {
            scenario = "高复杂度分析";
            preferredTools.addAll(Arrays.asList("getHighComplexityMethods", "getCodeQualityReport", "searchCodeRelation"));
            answerFocus = "输出高复杂度模块或方法清单、复杂度值和重构优先级；如果用户问的是模块则按模块回答。";
        } else if (containsAny(normalized, "线上缺陷", "快速定位", "故障定位", "根因定位", "异常定位")) {
            scenario = "线上缺陷快速定位";
            preferredTools.addAll(Arrays.asList("getDefectOverview", "getRecentExceptions", "getAppErrorDetails", "getRecentTraces", "locateRootCause", "analyzeCallChain"));
            answerFocus = "输出错误现象、异常分布、相关调用链、疑似根因、验证步骤和下一步处置建议；无法拿到成功/失败 TraceID 时，先用最近异常和错误请求缩小范围。";
        } else if (containsAny(normalized, "低覆盖", "高风险", "补测", "测试盲区", "覆盖缺口")) {
            scenario = "低覆盖高风险模块补测";
            preferredTools.addAll(Arrays.asList("getHighComplexityMethods", "searchCodeRelation", "getRecentTraces"));
            answerFocus = "输出模块优先级、风险原因、缺失场景和补测建议；优先结合复杂度、调用关系和缺陷数据排序。";
        } else if (containsAny(normalized, "性能回归", "性能退化", "耗时变慢", "基线对比", "回归分析")) {
            scenario = "性能回归分析";
            preferredTools.addAll(Arrays.asList("compareOverTime", "getAppPerformanceOverview", "getSlowEndpoints", "getEndpointCallFrequency", "analyzeUrlCallPattern"));
            answerFocus = "输出基线对比、退化接口、P95/P99/平均耗时变化、慢链路节点、可能原因和验证建议；缺少接口时先找慢接口和性能概览。";
        }

        if (scenario == null) {
            return "[AI工具推荐]\n" + formatToolRecommendation(recommendation)
                    + "\n请优先调用最相关的1个工具获取实时数据；如果已经足够回答，立即停止工具调用并生成最终答案。最多连续调用3个工具；如果数据缺失，说明缺失项并给出下一步，不要重复调用相同或相似工具。";
        }

        LinkedHashSet<String> tools = new LinkedHashSet<>();
        tools.add(recommendation.primaryTool);
        tools.addAll(recommendation.secondaryTools);
        tools.addAll(preferredTools);

        StringBuilder guide = new StringBuilder();
        guide.append("[识别到的精准测试场景]\n").append(scenario).append('\n');
        guide.append("[AI工具推荐]\n").append(formatToolRecommendation(recommendation)).append('\n');
        guide.append("[建议优先尝试的工具]\n");
        int count = 0;
        for (String toolName : tools) {
            if (toolName == null || toolName.trim().isEmpty()) {
                continue;
            }
            guide.append("- ").append(describeTool(toolName)).append('\n');
            if (++count >= 6) {
                break;
            }
        }
        guide.append("[回答要求]\n").append(answerFocus).append('\n');
        guide.append("请按『结论摘要 / 关键数据 / 风险排序 / 建议动作』组织回答；优先使用最相关的1个工具，最多连续调用3个工具；拿到足够数据后必须立即停止工具调用并回答；不要要求用户提供内部ID、JSON或工具参数。");
        return guide.toString();
    }

    public String formatToolRecommendation(ToolRecommender.Recommendation recommendation) {
        StringBuilder sb = new StringBuilder();
        sb.append("意图=").append(recommendation.detectedIntent)
                .append("，首选=").append(describeTool(recommendation.primaryTool));
        if (recommendation.secondaryTools != null && !recommendation.secondaryTools.isEmpty()) {
            sb.append("，辅助=");
            for (int i = 0; i < recommendation.secondaryTools.size(); i++) {
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(describeTool(recommendation.secondaryTools.get(i)));
            }
        }
        if (recommendation.reasoning != null && !recommendation.reasoning.isEmpty()) {
            sb.append("，原因=").append(recommendation.reasoning);
        }
        return sb.toString();
    }

    public String describeTool(String toolName) {
        return toolRecommender.getToolMeta(toolName)
                .map(meta -> meta.getDisplayName() + "：" + meta.getDescription())
                .orElse(toolName);
    }

    public boolean isToolCatalogQuestion(String question) {
        if (question == null || question.trim().isEmpty()) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return containsAny(normalized,
                "支持哪些工具", "有哪些工具", "有什么工具", "工具列表", "工具清单",
                "支持什么工具", "能用哪些工具", "可以用哪些工具", "你有哪些工具",
                "你有什么工具", "ai助手有哪些工具", "平台助手有哪些工具");
    }

    public String buildToolCatalogResponse() {
        Map<String, String> categoryNames = new LinkedHashMap<>();
        categoryNames.put("project_info", "项目与应用");
        categoryNames.put("app_status", "项目与应用");
        categoryNames.put("coverage", "覆盖率分析");
        categoryNames.put("trace", "调用链路");
        categoryNames.put("performance", "性能分析");
        categoryNames.put("defect", "缺陷与异常");
        categoryNames.put("testcase", "测试推荐");
        categoryNames.put("code_relation", "代码关系");
        categoryNames.put("code_quality", "代码质量");
        categoryNames.put("snapshot", "快照数据");
        categoryNames.put("bug_detect", "AI Bug检测");
        categoryNames.put("business_logic", "源码业务逻辑");

        Map<String, List<String>> groupedTools = new LinkedHashMap<>();
        for (String category : new LinkedHashSet<>(categoryNames.values())) {
            groupedTools.put(category, new ArrayList<>());
        }
        groupedTools.put("其他能力", new ArrayList<>());

        for (ToolRecommender.ToolMeta meta : toolRecommender.getAllTools()) {
            String category = "其他能力";
            if (meta.relatedIntents != null) {
                for (String intent : meta.relatedIntents) {
                    if (categoryNames.containsKey(intent)) {
                        category = categoryNames.get(intent);
                        break;
                    }
                }
            }
            String item = meta.getDisplayName() + "：" + meta.getDescription();
            List<String> items = groupedTools.get(category);
            if (!items.contains(item)) {
                items.add(item);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("我支持以下工具能力（展示的是工具名称，不是内部方法名）：\n");
        for (Map.Entry<String, List<String>> entry : groupedTools.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            sb.append("\n**").append(entry.getKey()).append("**\n");
            for (String item : entry.getValue()) {
                sb.append("- ").append(item).append('\n');
            }
        }
        return sb.toString().trim();
    }

    public boolean containsAny(String text, String... keywords) {
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
