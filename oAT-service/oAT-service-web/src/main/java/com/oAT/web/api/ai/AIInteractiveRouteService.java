package com.oAT.web.api.ai;

import com.oAT.web.config.AIInteractiveRouteConfig;
import com.oAT.web.service.entity.AppVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class AIInteractiveRouteService {

    @Autowired(required = false)
    private AIInteractiveRouteConfig routeConfig;

    public RouteContext detectRoute(String question, String contextSummary) {
        String text = ((question == null ? "" : question) + " " + (contextSummary == null ? "" : contextSummary)).toLowerCase();

        if (isBugInspectionQuestion(text)) {
            if (containsAny(text, "方法", "method", "函数")) {
                return new RouteContext("bug_detect", "bug.method");
            }
            return new RouteContext("bug_detect", "bug.class");
        }

        if (isBusinessLogicQuestion(text)) {
            if (containsAny(text, "方法", "method", "函数")) {
                return new RouteContext("business_logic", "business_logic.method");
            }
            return new RouteContext("business_logic", "business_logic.class");
        }

        if (isMethodCallGraphQuestion(text)) {
            return new RouteContext("code_relation", "code_relation.callGraph");
        }

        String pageRoute = detectPageRoute(contextSummary);
        if (StringUtils.hasText(pageRoute)) {
            return routeByPageFirst(pageRoute, text);
        }

        if (containsAny(text, "高复杂度", "圈复杂度", "复杂度高", "代码复杂度", "复杂度")) {
            return new RouteContext("quality", "quality.lowComplexity");
        }
        if (containsAny(text, "代码质量", "质量报告", "代码质量分析")) {
            return new RouteContext("quality", "quality.report");
        }
        if (containsAny(text, "根因", "异常定位", "线上缺陷", "故障定位", "异常分析", "出错链路")) {
            return new RouteContext("defect", "defect.rootCause");
        }
        if (containsAny(text, "慢接口", "性能回归", "性能退化", "耗时", "p95", "p99", "平均响应", "性能")) {
            return new RouteContext("performance", "performance.overview");
        }
        if (containsAny(text, "调用链", "链路", "trace", "请求链", "上下游")) {
            if (isMethodCallGraphQuestion(text)) {
                return new RouteContext("code_relation", "code_relation.callGraph");
            }
            if (containsAny(text, "最近", "最新", "列表")) {
                return new RouteContext("trace", "trace.recent");
            }
            if (containsAny(text, "应用", "app")) {
                return new RouteContext("trace", "trace.app");
            }
            return new RouteContext("trace", "trace.detail");
        }
        if (containsAny(text, "应用", "app", "在线", "状态", "列表")) {
            return new RouteContext("app", "app.status");
        }
        if (containsAny(text, "测试", "test", "补测", "回归")) {
            return new RouteContext("testcase", "testcase.recommend");
        }
        if (containsAny(text, "项目", "project", "概览", "统计", "总览")) {
            return new RouteContext("project", "project.overview");
        }
        if (containsAny(text, "代码", "关系", "依赖", "调用关系", "类关系")) {
            return new RouteContext("code_relation", "code_relation.search");
        }
        return new RouteContext("general", "general");
    }

    public List<String> buildFollowUpSuggestions(List<AppVo> apps, RouteContext routeContext) {
        List<String> suggestions = new ArrayList<>();
        switch (routeContext.getRouteKey()) {
            case "bug.class":
                suggestions.add("请分析这个类的空指针和逻辑风险");
                suggestions.add("继续分析这个类的高风险方法");
                break;
            case "bug.method":
                suggestions.add("请逐行分析这个方法的潜在 bug");
                suggestions.add("给出这个方法的修复建议");
                break;
            case "business_logic.class":
                suggestions.add("继续分析这个类的核心业务规则");
                suggestions.add("这个类有哪些潜在 bug？");
                break;
            case "business_logic.method":
                suggestions.add("继续分析这个方法的分支含义");
                suggestions.add("显示这个方法的真实调用关系");
                break;
            case "quality.lowComplexity":
                suggestions.add("哪些方法复杂度最高？");
                suggestions.add("哪些高复杂度类风险最高？");
                break;
            case "quality.report":
                suggestions.add("找出高复杂度模块");
                suggestions.add("分析代码风险模块");
                break;
            case "trace.recent":
            case "trace.detail":
                suggestions.add("分析这条链路的慢点和异常根因");
                suggestions.add("对比最近两条链路差异");
                break;
            case "trace.app":
                suggestions.add("查看最近的调用链路");
                suggestions.add("分析慢接口");
                break;
            case "app.status":
                suggestions.add("查看应用详情");
                suggestions.add("分析在线应用状态");
                break;
            case "testcase.recommend":
                suggestions.add("基于变更代码推荐测试用例");
                suggestions.add("查看用例补充建议");
                break;
            case "project.overview":
                suggestions.add("查看项目应用概览");
                suggestions.add("有哪些代码风险？");
                break;
            case "code_relation.search":
                suggestions.add("查看这个类的调用关系图");
                suggestions.add("搜索相关接口或方法");
                break;
            case "code_relation.callGraph":
                suggestions.add("继续分析这个方法的上下游影响面");
                suggestions.add("分析这个方法可能存在的 bug");
                break;
            default:
                suggestions.add("帮我分析项目状态");
                suggestions.add("查看代码关系");
                suggestions.add("有哪些应用？");
        }
        return suggestions;
    }

    public boolean isRoute(RouteContext routeContext, String topicKey, String... routeKeys) {
        if (routeContext == null) {
            return false;
        }
        if (topicKey != null && topicKey.equals(routeContext.getTopicKey())) {
            return true;
        }
        if (routeKeys != null) {
            for (String routeKey : routeKeys) {
                if (routeKey != null && routeKey.equals(routeContext.getRouteKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        String normalized = text.toLowerCase();
        for (String keyword : keywords) {
            if (keyword != null && normalized.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean isBugInspectionQuestion(String text) {
        return containsAny(text, "bug", "缺陷", "代码缺陷", "源码缺陷", "潜在问题", "可能存在", "风险", "空指针", "资源泄漏", "并发问题", "逻辑错误")
                && containsAny(text, "类", "方法", "method", "函数", "源码", "代码", "controller", "service", "branch");
    }

    private boolean isBusinessLogicQuestion(String text) {
        return containsAny(text, "业务需求", "业务逻辑", "业务规则", "业务场景", "业务含义", "需求分析",
                "功能逻辑", "功能需求", "方法职责", "类职责", "实现什么", "干什么", "做什么",
                "处理什么业务", "分析业务", "梳理业务");
    }

    private boolean isMethodCallGraphQuestion(String text) {
        return containsAny(text, "调用链", "调用关系", "上下游", "谁调用", "调用了谁", "依赖关系", "关系图", "调用图")
                && containsAny(text, "方法", "method", "函数");
    }

    private String detectPageRoute(String pageContext) {
        if (!StringUtils.hasText(pageContext)) {
            return null;
        }
        String text = pageContext.toLowerCase();
        if (matchesRoute(text, routeConfig != null ? routeConfig.getTrace().getKeywords() : null,
                "监控页", "页面类型:监控页", "实时监控", "monitor")) {
            return "trace";
        }
        if (matchesRoute(text, routeConfig != null ? routeConfig.getApp().getKeywords() : null,
                "应用页", "页面类型:应用页", "应用中心", "app/list", "app/online")) {
            return "app";
        }
        if (matchesRoute(text, routeConfig != null ? routeConfig.getCodeRelation().getKeywords() : null,
                "代码关系", "类关系", "callgraph", "关系图")) {
            return "code_relation";
        }
        return null;
    }

    private boolean matchesRoute(String text, List<String> configuredKeywords, String... fallbackKeywords) {
        if (configuredKeywords != null) {
            for (String keyword : configuredKeywords) {
                if (StringUtils.hasText(keyword) && text.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        return containsAny(text, fallbackKeywords);
    }

    private RouteContext routeByPageFirst(String pageRoute, String text) {
        if ("trace".equals(pageRoute)) {
            if (isMethodCallGraphQuestion(text)) {
                return new RouteContext("code_relation", "code_relation.callGraph");
            }
            if (containsAny(text, "最近", "最新", "列表")) {
                return new RouteContext("trace", "trace.recent");
            }
            if (containsAny(text, "应用", "app")) {
                return new RouteContext("trace", "trace.app");
            }
            if (containsAny(text, "对比", "差异", "正常", "异常")) {
                return new RouteContext("trace", "trace.detail");
            }
            return new RouteContext("trace", "trace.detail");
        }
        if ("app".equals(pageRoute)) {
            if (containsAny(text, "在线", "运行中")) {
                return new RouteContext("app", "app.status");
            }
            if (containsAny(text, "详情")) {
                return new RouteContext("app", "app.detail");
            }
            return new RouteContext("app", "app.status");
        }
        if ("code_relation".equals(pageRoute)) {
            if (isMethodCallGraphQuestion(text)) {
                return new RouteContext("code_relation", "code_relation.callGraph");
            }
            return new RouteContext("code_relation", "code_relation.search");
        }
        return new RouteContext("general", "general");
    }

    public static final class RouteContext {
        private final String topicKey;
        private final String routeKey;

        public RouteContext(String topicKey, String routeKey) {
            this.topicKey = topicKey;
            this.routeKey = routeKey;
        }

        public String getTopicKey() {
            return topicKey;
        }

        public String getRouteKey() {
            return routeKey;
        }
    }
}
