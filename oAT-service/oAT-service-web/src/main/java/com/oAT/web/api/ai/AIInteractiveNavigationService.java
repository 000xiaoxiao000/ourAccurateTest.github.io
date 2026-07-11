package com.oAT.web.api.ai;

import com.oAT.web.api.ai.AIInteractiveRouteService.RouteContext;
import com.oAT.web.service.entity.AIActionVo;
import com.oAT.web.service.entity.AIQuickLinkVo;
import com.oAT.web.service.entity.AppVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AIInteractiveNavigationService {

    @Autowired
    private AIInteractiveRouteService routeService;

    public List<AIQuickLinkVo> buildQuickLinks(String projectId, List<AppVo> apps, RouteContext routeContext) {
        List<AIQuickLinkVo> links = new ArrayList<>();
        links.add(new AIQuickLinkVo("项目主页", "回到项目整体概况", "/p/" + projectId + "/home"));
        links.add(new AIQuickLinkVo("搜索中心", "搜索用例和项目内容", "/p/" + projectId + "/search"));
        if (isRoute(routeContext, "trace", "trace.recent", "trace.detail", "trace.app")) {
            links.add(new AIQuickLinkVo("链路地图", "查看需求、用例、Bug、源码的双向追溯", "/p/" + projectId + "/map/home"));
        }
        AppVo targetApp = findTargetApp(apps, "");
        if (targetApp != null && StringUtils.hasText(targetApp.getId())) {
            links.add(new AIQuickLinkVo("版本中心", "查看应用版本和比对报告", "/p/" + projectId + "/apps/" + targetApp.getId() + "/versions"));
        }
        return links;
    }

    public List<AIActionVo> buildActions(String projectId, List<AppVo> apps, String question, String pageContext, RouteContext routeContext) {
        return buildHeaderNavigationActions(projectId, apps, question == null ? "" : question.toLowerCase());
    }

    private List<AIActionVo> buildHeaderNavigationActions(String projectId, List<AppVo> apps, String questionText) {
        List<AIActionVo> actions = new ArrayList<>();
        if (!containsAny(questionText, "跳转", "打开", "进入", "去", "切换到", "访问", "查看")) {
            return actions;
        }

        AppVo targetApp = findTargetApp(apps, questionText);
        String appId = targetApp == null ? null : targetApp.getId();

        if (containsAny(questionText, "项目主页", "首页", "项目首页", "概览")) {
            actions.add(buildAutoNavigateAction("打开项目主页", "/p/" + projectId + "/home"));
        } else if (containsAny(questionText, "搜索")) {
            actions.add(buildAutoNavigateAction("打开搜索", "/p/" + projectId + "/search"));
        } else if (containsAny(questionText, "ai", "工作台")) {
            actions.add(buildAutoNavigateAction("打开 AI 工作台", "/p/" + projectId + "/ai"));
        } else if (containsAny(questionText, "应用中心", "应用列表")) {
            actions.add(buildAutoNavigateAction("打开应用中心", "/p/" + projectId + "/apps"));
        } else if (containsAny(questionText, "添加应用", "新增应用", "创建应用")) {
            actions.add(buildAutoNavigateAction("打开添加应用", "/p/" + projectId + "/apps?create=1"));
        } else if (containsAny(questionText, "创建新项目", "新建项目", "创建项目")) {
            actions.add(buildAutoNavigateAction("打开创建新项目", "/projects?create=1"));
        } else if (containsAny(questionText, "项目设置", "设置")) {
            actions.add(buildAutoNavigateAction("打开项目设置", "/projects?edit=" + projectId));
        } else if (containsAny(questionText, "我的项目", "项目列表", "切换项目")) {
            actions.add(buildAutoNavigateAction("打开我的项目列表", "/projects"));
        } else if (containsAny(questionText, "用户设置", "个人设置", "账号设置")) {
            actions.add(buildAutoNavigateAction("打开用户设置", "/account"));
        } else if (containsAny(questionText, "注销", "退出登录", "登出")) {
            actions.add(new AIActionVo("logout", "注销退出", "退出当前账号", "/api/auth/logout", true, "确认要注销退出吗？"));
        } else if (appId != null && containsAny(questionText, "版本比对", "版本比较")) {
            actions.add(buildAutoNavigateAction("打开版本比对", "/p/" + projectId + "/apps/" + appId + "/compare"));
        } else if (appId != null) {
            actions.add(buildAutoNavigateAction("打开应用设置", "/p/" + projectId + "/apps/" + appId + "/settings"));
        }
        return actions;
    }

    private AIActionVo buildAutoNavigateAction(String title, String url) {
        AIActionVo action = new AIActionVo("navigate", title, "正在跳转。", url, false, null);
        Map<String, Object> payload = new HashMap<>();
        payload.put("autoExecute", true);
        action.setPayload(payload);
        return action;
    }

    private AppVo findTargetApp(List<AppVo> apps, String questionText) {
        if (apps == null || apps.isEmpty()) {
            return null;
        }
        String normalizedQuestion = questionText == null ? "" : questionText.toLowerCase();
        for (AppVo app : apps) {
            if (app != null && StringUtils.hasText(app.getName()) && normalizedQuestion.contains(app.getName().toLowerCase())) {
                return app;
            }
        }
        return apps.get(0);
    }

    private boolean isRoute(RouteContext routeContext, String topicKey, String... routeKeys) {
        return routeService.isRoute(routeContext, topicKey, routeKeys);
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
