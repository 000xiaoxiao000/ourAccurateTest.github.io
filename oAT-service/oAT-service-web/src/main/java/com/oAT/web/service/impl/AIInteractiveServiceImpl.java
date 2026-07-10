package com.oAT.web.service.impl;

import com.oAT.ai.agent.AgentContext;
import com.oAT.ai.agent.AIAgentService;
import com.oAT.web.api.ai.AIInteractiveNavigationService;
import com.oAT.web.api.ai.AIInteractivePageComposerService;
import com.oAT.web.api.ai.AIInteractiveRouteService;
import com.oAT.web.api.ai.AIInteractiveRouteService.RouteContext;
import com.oAT.web.api.ai.AIInteractiveSessionMemoryService;
import com.oAT.web.service.AIInteractiveService;
import com.oAT.web.service.AppService;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.entity.AIInteractivePageVo;
import com.oAT.web.service.entity.AIInteractiveReplyVo;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.ProjectVo;
import com.oAT.web.service.entity.UserVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.oAT.web.api.ai.AIInteractiveSessionMemoryService.MEMORY_SCOPE_WORKBENCH;

@Service
public class AIInteractiveServiceImpl implements AIInteractiveService {

    private static final Logger logger = LoggerFactory.getLogger(AIInteractiveServiceImpl.class);

    @Autowired
    private ProjectService projectService;

    @Autowired
    private AppService appService;

    @Autowired(required = false)
    private AIAgentService aiAgentService;

    @Autowired
    private AIInteractiveSessionMemoryService sessionMemoryService;

    @Autowired
    private AIInteractivePageComposerService pageComposerService;

    @Autowired
    private AIInteractiveRouteService routeService;

    @Autowired
    private AIInteractiveNavigationService navigationService;

    @Value("${ai.llm.timeout:300}")
    private int aiTimeout;

    @Override
    public AIInteractivePageVo buildPage(String projectId, UserVo user) {
        ProjectVo project = projectService.getProject(projectId);
        List<AppVo> apps = loadApps(projectId);
        Map<String, String> mascot = pageComposerService.buildMascot(project);

        AIInteractivePageVo page = new AIInteractivePageVo();
        page.setProjectId(projectId);
        page.setProjectName(project.getName());
        page.setProjectSummary(pageComposerService.buildProjectSummary(project, apps));
        page.setWelcomeMessage(pageComposerService.buildWelcomeMessage(user, project, apps, mascot.get("mascotName")));
        page.setStarterQuestions(pageComposerService.buildStarterQuestions(apps));
        page.setAbilityCards(pageComposerService.buildAbilityCards(apps));
        page.setOnlineAppCount(pageComposerService.countOnlineApps(apps));
        page.setAppCount(apps.size());
        page.setAppNames(apps.stream().map(AppVo::getName).collect(Collectors.toList()));
        page.setMascotHint(pageComposerService.buildMascotHint(apps));
        page.setQuickLinks(navigationService.buildQuickLinks(projectId, apps, new RouteContext("project", "project.overview")));
        page.setSessionState(sessionMemoryService.loadSessionState(projectId, user, MEMORY_SCOPE_WORKBENCH));
        page.setMascot(mascot);
        page.setAiTimeout(aiTimeout);
        return page;
    }

    @Override
    public AIInteractiveReplyVo ask(String projectId, UserVo user, String question, String pageContext, String imageData,
                                    String sessionState, String activeSessionId, String sessionSortMode,
                                    Boolean timelineExpanded, String memoryScope) {
        long startTime = System.currentTimeMillis();
        ProjectVo project = projectService.getProject(projectId);
        List<AppVo> apps = loadApps(projectId);
        String cleanQuestion = question == null ? "" : question.trim();
        if (!StringUtils.hasText(cleanQuestion) && StringUtils.hasText(imageData)) {
            cleanQuestion = "[图片提问] 请分析这张图片";
        }
        String contextSummary = normalizePageContext(pageContext);
        RouteContext routeContext = routeService.detectRoute(cleanQuestion, contextSummary);

        AIInteractiveReplyVo reply = new AIInteractiveReplyVo();
        reply.setQuestion(cleanQuestion);
        reply.setTopic(routeContext.getTopicKey());

        String normalizedMemoryScope = sessionMemoryService.normalizeMemoryScope(memoryScope);
        String answer = callAIAgent(project, apps, user, cleanQuestion, contextSummary, imageData, normalizedMemoryScope);
        reply.setAnswer(answer);

        long responseTime = System.currentTimeMillis() - startTime;
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("responseTime", responseTime);
        metadata.put("topic", routeContext.getTopicKey());
        metadata.put("route", routeContext.getRouteKey());
        reply.setMetadata(metadata);
        reply.setSuggestions(routeService.buildFollowUpSuggestions(apps, routeContext));
        reply.setQuickLinks(navigationService.buildQuickLinks(projectId, apps, routeContext));
        reply.setActions(navigationService.buildActions(projectId, apps, cleanQuestion, contextSummary, routeContext));
        if (MEMORY_SCOPE_WORKBENCH.equals(normalizedMemoryScope)) {
            reply.setSessionState(sessionMemoryService.loadSessionState(projectId, user, normalizedMemoryScope));
        }
        return reply;
    }

    @Override
    public String saveSessionState(String projectId, UserVo user, String sessionState) {
        return sessionMemoryService.saveSessionState(projectId, user, sessionState);
    }

    @Override
    public String clearSessionMemory(String projectId, UserVo user) {
        return clearSessionMemory(projectId, user, MEMORY_SCOPE_WORKBENCH);
    }

    @Override
    public String clearSessionMemory(String projectId, UserVo user, String memoryScope) {
        if (user == null || !StringUtils.hasText(projectId)) {
            return "";
        }
        String normalizedMemoryScope = sessionMemoryService.normalizeMemoryScope(memoryScope);
        if (aiAgentService != null) {
            aiAgentService.clearConversationMemory(user.getId(), projectId, normalizedMemoryScope);
        }
        sessionMemoryService.clearWorkbenchState(projectId, user, normalizedMemoryScope);
        return "";
    }

    private String callAIAgent(ProjectVo project, List<AppVo> apps, UserVo user,
                               String question, String pageContext, String imageData, String memoryScope) {
        if (aiAgentService == null) {
            logger.warn("AI Agent bean is not available in Spring context");
            return "AI 服务暂不可用：AI Agent Bean 未加载，请检查模块装配与 Spring 配置。";
        }
        if (!aiAgentService.isAvailable()) {
            logger.warn("AI Agent is unavailable: {}", aiAgentService.getInitializationStatus());
            return "AI 服务暂不可用：" + aiAgentService.getInitializationStatus();
        }

        try {
            AgentContext context = new AgentContext(project.getId(), user.getId(), user.getName());
            context.setMemoryScope(memoryScope);
            logger.info("Calling AI Agent for question: {} (hasImage: {})", question, StringUtils.hasText(imageData));

            if (StringUtils.hasText(imageData)) {
                return aiAgentService.chatWithImage(context, question, pageContext, imageData);
            }
            if (StringUtils.hasText(pageContext)) {
                return aiAgentService.chatWithContext(context, question, pageContext);
            }
            return aiAgentService.chat(context, question);
        } catch (Exception e) {
            logger.error("Failed to call AI Agent", e);
            return "AI 服务调用失败：" + e.getMessage();
        }
    }

    private List<AppVo> loadApps(String projectId) {
        List<AppVo> apps = appService.getAppList(projectId);
        for (AppVo app : apps) {
            app.setOnlineCount(clientSessionService.getOnlineSessionsByAppId(app.getId()).size());
        }
        return apps;
    }

    private String normalizePageContext(String pageContext) {
        return pageContext == null ? "" : pageContext.trim();
    }
}
