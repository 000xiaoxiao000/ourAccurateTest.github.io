package com.oAT.ai.agent;

import com.oAT.agent.AISelfLearningService;
import com.oAT.ai.agent.fallback.FallbackArgumentBinder;
import com.oAT.ai.agent.fallback.FallbackReport;
import com.oAT.ai.agent.fallback.FallbackToolExecutionService;
import com.oAT.ai.agent.fallback.FallbackToolMethodBinding;
import com.oAT.ai.agent.fallback.FallbackToolMethodSchema;
import com.oAT.ai.agent.fallback.FallbackToolParameterSchema;
import com.oAT.ai.agent.cache.SemanticCacheService;
import com.oAT.ai.agent.tools.*;
import com.oAT.ai.config.AIConfig;
import com.oAT.ai.config.AIConfigProperties;
import com.oAT.ai.config.AIEnhancedConfig;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.*;

/**
 * AI Agent 服务
 * 整合所有工具，提供智能对话能力
 *
 * <p>当 LLM 模型不支持/不兼容结构化 Tool Calling 时（如部分 Ollama 模型将函数调用作为纯文本返回），
 * 本服务会自动识别原始工具调用格式并直接执行对应工具，确保用户总能获得数据结果。</p>
 */
@Service
public class AIAgentService {

    private static final Logger logger = LoggerFactory.getLogger(AIAgentService.class);

    private static final int MAX_SEQUENTIAL_TOOL_INVOCATIONS = 8;

    private static final int MAX_RELEVANT_TOOLS_PER_REQUEST = 8;

    private static final String DEFAULT_MEMORY_SCOPE = "default";

    private final AIAgent aiAgent;
    private final AIConfigProperties configProperties;
    private final AgentDataProvider dataProvider;
    private final String initializationStatus;
    private final FallbackArgumentBinder fallbackArgumentBinder = new FallbackArgumentBinder();

    /** 工具方法映射：兼容别名/规范化名称 → 方法绑定 */
    private final Map<String, FallbackToolMethodBinding> toolMethodBindings = new LinkedHashMap<>();

    /** 工具 schema 缓存：规范化工具名 → 参数结构信息 */
    private final Map<String, FallbackToolMethodSchema> toolMethodSchemas = new LinkedHashMap<>();

    /** LangChain4j 动态工具提供器缓存：规范化工具名 → 工具定义/执行器 */
    private final Map<String, AIToolProviderEntry> toolProviderEntries = new LinkedHashMap<>();
    private final AIToolProviderRouter toolProviderRouter;
    private final FallbackToolExecutionService fallbackToolExecutionService;

    /** 语义缓存服务（用于相似问题命中） */
    private final SemanticCacheService semanticCacheService;

    /** 是否启用语义缓存 */
    private final boolean semanticCacheEnabled;

    /** 是否启用 AI 自主学习 */
    private final boolean selfLearningEnabled;

    /** 是否启用优质 Q&A 知识库命中 */
    private final boolean knowledgeHitEnabled;

    /** 是否注入动态学习约束 */
    private final boolean dynamicGuideEnabled;

    /** 反馈持久化（与 Web 层共享） */
    private FeedbackPersistenceService feedbackPersistenceService;

    /** 智能工具推荐器 */
    private final ToolRecommender toolRecommender;
    private final AIPromptGuideBuilder promptGuideBuilder;

    /** 动态LLM切换器 */
    private final DynamicLLMSwitcher llmSwitcher;

    /** 多轮对话记忆服务 */
    private final ConversationMemoryService conversationMemory;

    /** 自主学习服务 */
    private volatile AISelfLearningService selfLearningService;

    @Autowired
    public AIAgentService(@Qualifier("chatLanguageModel") ObjectProvider<ChatModel> chatLanguageModelProvider,
                          AIConfigProperties configProperties,
                          AgentDataProvider dataProvider,
                          AIConfig aiConfig,
                          AIEnhancedConfig enhancedConfig) {
        this.configProperties = configProperties;
        this.dataProvider = dataProvider;

        ChatModel chatLanguageModel = chatLanguageModelProvider == null ? null : chatLanguageModelProvider.getIfAvailable();

        // 初始化增强服务
        this.semanticCacheEnabled = enhancedConfig.getSemanticCache().isEnabled();
        this.selfLearningEnabled = enhancedConfig.getSelfLearning().isEnabled();
        this.knowledgeHitEnabled = enhancedConfig.getSelfLearning().isKnowledgeHitEnabled();
        this.dynamicGuideEnabled = enhancedConfig.getSelfLearning().isDynamicGuideEnabled();
        this.semanticCacheService = new SemanticCacheService(enhancedConfig.getSemanticCache().getThreshold(), 500);
        this.toolRecommender = new ToolRecommender();
        this.promptGuideBuilder = new AIPromptGuideBuilder(toolRecommender);
        this.toolProviderRouter = new AIToolProviderRouter(toolRecommender,
                toolProviderEntries, MAX_RELEVANT_TOOLS_PER_REQUEST);
        this.fallbackToolExecutionService = new FallbackToolExecutionService(
                toolMethodBindings, toolMethodSchemas, fallbackArgumentBinder);
        this.llmSwitcher = new DynamicLLMSwitcher(aiConfig);
        this.conversationMemory = new ConversationMemoryService(enhancedConfig.getConversation().getMaxRounds(),
                Math.max(1, enhancedConfig.getConversation().getMaxRounds() / 2));

        // 注册所有内置工具到推荐器
        toolRecommender.registerAllBuiltInTools();

        if (chatLanguageModel == null) {
            this.initializationStatus = buildUnavailableReason("chat model bean was not created", aiConfig);
            logger.warn("{}", this.initializationStatus);
            this.aiAgent = null;
        } else {
            List<Object> tools = createTools();
            this.aiAgent = AiServices.builder(AIAgent.class)
                    .chatModel(chatLanguageModel)
                    .toolProvider(this::provideRelevantTools)
                    .maxSequentialToolsInvocations(MAX_SEQUENTIAL_TOOL_INVOCATIONS)
                    .build();
            this.initializationStatus = String.format(
                    "AI Agent initialized successfully with LangChain4j %s and %d tools",
                    AiServices.class.getPackage().getImplementationVersion(), tools.size());
            logger.info("{}", this.initializationStatus);
        }
    }

    @Autowired(required = false)
    public void setFeedbackPersistenceService(FeedbackPersistenceService feedbackPersistenceService) {
        this.feedbackPersistenceService = feedbackPersistenceService;
    }

    private List<Object> createTools() {
        List<Object> tools = new ArrayList<>();

        // 创建工具实例并注册到映射表（用于兜底执行）
        ProjectInfoTool projectInfoTool = new ProjectInfoTool(dataProvider);
        AppStatusTool appStatusTool = new AppStatusTool(dataProvider);
        TraceQueryTool traceQueryTool = new TraceQueryTool(dataProvider);
        CodeRelationTool codeRelationTool = new CodeRelationTool(dataProvider);
        PerformanceAnalysisTool performanceTool = new PerformanceAnalysisTool(dataProvider);
        DefectStatisticsTool defectTool = new DefectStatisticsTool(dataProvider);
        BugDetectTool bugDetectTool = new BugDetectTool(dataProvider);
        CallChainAnalysisTool callChainAnalysisTool = new CallChainAnalysisTool(dataProvider);

        tools.add(projectInfoTool);
        tools.add(appStatusTool);
        tools.add(traceQueryTool);
        tools.add(codeRelationTool);
        tools.add(performanceTool);
        tools.add(defectTool);
        tools.add(bugDetectTool);
        tools.add(callChainAnalysisTool);

        // 注册工具实例，用于兜底执行
        registerTool(projectInfoTool);
        registerTool(appStatusTool);
        registerTool(traceQueryTool);
        registerTool(codeRelationTool);
        registerTool(performanceTool);
        registerTool(defectTool);
        registerTool(testcaseTool);
        registerTool(codeQualityTool);
        registerTool(bugDetectTool);
        registerTool(callChainAnalysisTool);
        registerTool(callChainCompareTool);

        return tools;
    }

    /**
     * 注册工具实例到映射表
     * 扫描工具类中所有带 @Tool 注解的方法，建立 方法名/别名→工具绑定 的映射
     */
    private void registerTool(Object toolInstance) {
        for (Method method : toolInstance.getClass().getDeclaredMethods()) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation == null) {
                continue;
            }

            FallbackToolMethodBinding binding = new FallbackToolMethodBinding(toolInstance, method);
            FallbackToolMethodSchema schema = buildFallbackToolMethodSchema(method);
            registerToolBinding(method.getName(), binding, schema);
            registerToolBinding(toolInstance.getClass().getSimpleName() + "." + method.getName(), binding, schema);
            registerToolProviderEntry(toolInstance, method);

            logger.debug("Registered fallback tool: {} -> {}", method.getName(), toolInstance.getClass().getSimpleName());
        }
    }

    private void registerToolProviderEntry(Object toolInstance, Method method) {
        ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
        ToolExecutor executor = new DefaultToolExecutor(toolInstance, method);
        AIToolProviderEntry entry = new AIToolProviderEntry(specification, executor);
        registerToolProviderEntry(method.getName(), entry);
        registerToolProviderEntry(specification.name(), entry);
    }

    private void registerToolProviderEntry(String alias, AIToolProviderEntry entry) {
        String normalizedAlias = normalizeName(alias);
        if (!normalizedAlias.isEmpty()) {
            toolProviderEntries.put(normalizedAlias, entry);
        }
    }

    private ToolProviderResult provideRelevantTools(ToolProviderRequest request) {
        return toolProviderRouter.provideRelevantTools(request);
    }

    private void registerToolBinding(String alias, FallbackToolMethodBinding binding, FallbackToolMethodSchema schema) {
        String normalizedAlias = normalizeName(alias);
        if (normalizedAlias.isEmpty()) {
            return;
        }
        toolMethodBindings.put(normalizedAlias, binding);
        toolMethodSchemas.put(normalizedAlias, schema);
    }

    private FallbackToolMethodSchema buildFallbackToolMethodSchema(Method method) {
        List<FallbackToolParameterSchema> parameters = new ArrayList<>();
        for (java.lang.reflect.Parameter parameter : method.getParameters()) {
            if (fallbackArgumentBinder.isInjectableParameter(parameter)) {
                continue;
            }
            String annotatedName = fallbackArgumentBinder.getAnnotatedParameterName(parameter);
            List<String> candidates = fallbackArgumentBinder.buildCandidateNames(parameter.getName(), annotatedName);
            parameters.add(new FallbackToolParameterSchema(
                    parameter.getName(),
                    annotatedName,
                    parameter.getType(),
                    parameter.getParameterizedType(),
                    candidates
            ));
        }
        return new FallbackToolMethodSchema(method.getName(), parameters);
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

    public boolean isAvailable() {
        return aiAgent != null && configProperties.isEnabled();
    }

    public String getInitializationStatus() {
        return initializationStatus;
    }

    private String buildUnavailableReason(String detail, AIConfig aiConfig) {
        if (!configProperties.isEnabled()) {
            return "AI Agent initialization skipped: ai.llm.enabled=false";
        }
        return String.format(
                "AI Agent initialization failed: %s (provider=%s, model=%s, baseUrl=%s)",
                detail,
                safeValue(aiConfig.getProvider()),
                safeValue(aiConfig.getModel()),
                safeValue(aiConfig.getBaseUrl()));
    }

    private String safeValue(String value) {
        return value == null || value.trim().isEmpty() ? "<empty>" : value;
    }

    /**
     * 与AI Agent对话（带工具调用兜底）
     * 集成语义缓存、智能工具推荐、多轮对话记忆
     */
    public String chat(AgentContext context, String question) {
        if (isToolCatalogQuestion(question)) {
            return buildToolCatalogResponse();
        }
        if (!isAvailable()) {
            return null;
        }
        try {
            AgentContext.setContext(context);
            String earlyAnswer = resolveEarlyAnswer(question);
            if (earlyAnswer != null) {
                recordAssistantResponse(context, earlyAnswer);
                return earlyAnswer;
            }

            ToolRecommender.Recommendation recommendation = toolRecommender.recommend(question);
            logger.debug("Tool recommendation: primary={}, intent={}, confidence={}",
                    recommendation.primaryTool, recommendation.detectedIntent, recommendation.confidence);

            String enhancedQuestion = buildEnhancedQuestion(context, question, recommendation);
            long startTime = System.currentTimeMillis();
            String response = aiAgent.chat(enhancedQuestion, context.getProjectId(), context.getUserName());
            return finalizeAgentResponse(context, question, recommendation, startTime, response);
        } catch (Exception e) {
            return handleChatException(e);
        } finally {
            AgentContext.clearContext();
        }
    }

    /**
     * 带页面上下文的对话（同样带兜底）
     */
    public String chatWithContext(AgentContext context, String question, String pageContext) {
        if (isToolCatalogQuestion(question)) {
            return buildToolCatalogResponse();
        }
        if (!isAvailable()) {
            return null;
        }
        try {
            context.setPageContext(pageContext);
            AgentContext.setContext(context);
            String earlyAnswer = resolveEarlyAnswer(question);
            if (earlyAnswer != null) {
                recordAssistantResponse(context, earlyAnswer);
                return earlyAnswer;
            }

            ToolRecommender.Recommendation recommendation = toolRecommender.recommend(question);
            String enhancedQuestion = buildEnhancedQuestion(context, question, recommendation);
            long startTime = System.currentTimeMillis();
            String response = aiAgent.chatWithContext(enhancedQuestion, context.getProjectId(),
                    context.getUserName(), pageContext);
            return finalizeAgentResponse(context, question, recommendation, startTime, response);
        } catch (Exception e) {
            return handleChatException(e);
        } finally {
            AgentContext.clearContext();
        }
    }

    /**
     * 带图片的多模态对话（同样带兜底）
     */
    public String chatWithImage(AgentContext context, String question, String pageContext, String imageData) {
        if (isToolCatalogQuestion(question)) {
            return buildToolCatalogResponse();
        }
        if (!isAvailable()) {
            return null;
        }
        try {
            context.setPageContext(pageContext);
            AgentContext.setContext(context);
            String truncatedImage = imageData != null && imageData.length() > 680000
                    ? imageData.substring(0, 680000) + "... [图片数据已截断]"
                    : imageData;

            ToolRecommender.Recommendation recommendation = toolRecommender.recommend(question);
            String enhancedQuestion = buildEnhancedQuestion(context, question, recommendation);
            long startTime = System.currentTimeMillis();
            String response = aiAgent.chatWithImage(enhancedQuestion, context.getProjectId(), context.getUserName(),
                    pageContext, truncatedImage);
            return finalizeAgentResponse(context, question, recommendation, startTime, response);
        } catch (Exception e) {
            return handleChatException(e);
        } finally {
            AgentContext.clearContext();
        }
    }

    private String resolveEarlyAnswer(String question) {
        if (semanticCacheEnabled) {
            SemanticCacheService.CachedResponse cached = semanticCacheService.get(question);
            if (cached != null && cached.isFromCache() && cached.getAnswer() != null) {
                logger.info("Semantic cache hit for question: {}",
                        question.length() > 50 ? question.substring(0, 50) + "..." : question);
                return cached.getAnswer();
            }
        }

        if (selfLearningEnabled && knowledgeHitEnabled) {
            try {
                AISelfLearningService selfLearning = getSelfLearningService();
                String learnedAnswer = selfLearning.findBestPracticeAnswer(question);
                if (learnedAnswer != null && !learnedAnswer.isEmpty()) {
                    logger.info("Knowledge base hit for question: {}",
                            question.length() > 50 ? question.substring(0, 50) + "..." : question);
                    if (semanticCacheEnabled) {
                        semanticCacheService.put(question, learnedAnswer, null);
                    }
                    return learnedAnswer;
                }
            } catch (Exception e) {
                logger.debug("Knowledge base lookup failed (non-critical): {}", e.getMessage());
            }
        }
        return null;
    }

    private String finalizeAgentResponse(AgentContext context,
                                         String question,
                                         ToolRecommender.Recommendation recommendation,
                                         long startTime,
                                         String response) {
        long responseTime = System.currentTimeMillis() - startTime;
        String fallbackResult = tryFallbackToolExecution(response);
        if (fallbackResult != null) {
            logger.info("Detected raw tool call in AI response, executed via fallback");
            response = fallbackResult;
        }

        boolean success = response != null && !response.isEmpty();
        if (success) {
            if (semanticCacheEnabled) {
                semanticCacheService.put(question, response, null);
            }
            toolRecommender.recordToolCall(recommendation.primaryTool, true);
            llmSwitcher.recordResult(llmSwitcher.getDefaultModelName(), true, responseTime);
            recordAssistantResponse(context, response);
        } else {
            toolRecommender.recordToolCall(recommendation.primaryTool, false);
            llmSwitcher.recordResult(llmSwitcher.getDefaultModelName(), false, responseTime);
        }

        logger.info("AI Agent response: {}ms, length={}, success={}",
                responseTime, response != null ? response.length() : 0, success);
        return response;
    }

    private String handleChatException(Exception e) {
        if (isToolInvocationLoopLimit(e)) {
            logger.warn("AI Agent stopped because tool invocation loop limit was reached: {}", e.getMessage());
            return "抱歉，AI 助手连续调用工具次数过多，已自动停止以避免无效循环。请把问题缩小到一个具体目标（例如覆盖率概览、某个应用的慢接口、某条调用链详情），我会重新查询。";
        }
        logger.error("AI Agent chat failed", e);
        return null;
    }

    private boolean isToolInvocationLoopLimit(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("exceeded") && message.contains("sequential tool invocations")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public String getProjectOverview(AgentContext context) {
        if (!isAvailable()) {
            return null;
        }
        try {
            AgentContext.setContext(context);
            return aiAgent.getProjectOverview(context.getProjectId(), context.getUserName());
        } catch (Exception e) {
            logger.error("AI Agent get project overview failed", e);
            return null;
        } finally {
            AgentContext.clearContext();
        }
    }

    private String tryFallbackToolExecution(String response) {
        return fallbackToolExecutionService.tryExecute(response);
    }

    public FallbackReport getLatestFallbackReport() {
        return fallbackToolExecutionService.getLatestFallbackReport();
    }

    public List<FallbackReport> getRecentFallbackReports(int limit) {
        return fallbackToolExecutionService.getRecentFallbackReports(limit);
    }

    // ==================== 增强功能方法 ====================

    /**
     * 构建增强的问题（包含多轮对话上下文摘要）
     */
    private String buildEnhancedQuestion(AgentContext context, String question) {
        return buildEnhancedQuestion(context, question, toolRecommender.recommend(question));
    }

    private String buildEnhancedQuestion(AgentContext context, String question,
                                         ToolRecommender.Recommendation recommendation) {
        StringBuilder enhanced = new StringBuilder();

        // 尝试获取会话上下文
        ConversationMemoryService.ConversationSession activeSession = conversationMemory.getActiveSession(
                context.getUserId(), context.getProjectId(), getMemoryScope(context));
        if (activeSession != null && activeSession.getMessageCount() > 2) {
            String ctxSummary = conversationMemory.buildContextForLLM(activeSession.getSessionId(), 5);
            if (ctxSummary != null && !ctxSummary.isEmpty()) {
                enhanced.append("[之前的对话上下文]\n").append(ctxSummary).append("\n\n");
                logger.debug("Added conversation context for user {} project {} scope {}",
                        context.getUserId(), context.getProjectId(), getMemoryScope(context));
            }
        }

        String scenarioGuide = buildScenarioGuide(question, recommendation);
        if (scenarioGuide != null && !scenarioGuide.isEmpty()) {
            enhanced.append(scenarioGuide).append("\n\n");
        }

        if (selfLearningEnabled && dynamicGuideEnabled) {
            try {
                AISelfLearningService selfLearning = getSelfLearningService();
                String learningGuide = selfLearning.buildDynamicLearningGuide(question, context.getProjectId());
                if (learningGuide != null && !learningGuide.isEmpty()) {
                    enhanced.append(learningGuide).append("\n\n");
                }
            } catch (Exception e) {
                logger.debug("Dynamic learning guide injection failed (non-critical): {}", e.getMessage());
            }
        }

        if (context != null && context.getPageContext() != null && !context.getPageContext().trim().isEmpty()) {
            enhanced.append("[当前页面上下文]\n").append(context.getPageContext()).append("\n");
            enhanced.append("[页面上下文使用要求]\n");
            enhanced.append("如果当前问题只给出方法名或部分类名，必须优先从当前页面上下文、覆盖率详情、代码关系页面或上一轮对话中识别真实类名/方法名；仍无法唯一确定时先说明无法确定，不要使用示例类、示例方法或猜测包名。\n\n");
            if (context.getPageContext().contains("当前源码片段")) {
                enhanced.append("[源码页上下文使用要求]\n");
                enhanced.append("当前页面上下文已经包含用户正在查看的真实源码片段。遇到源码 Bug 检测、业务逻辑分析、方法调用或覆盖率问题时，必须优先基于该源码片段分析；工具无法读取源码时，不要声称完全无法分析，也不要编造源码之外的类名或方法名。\n\n");
                if (isSourceMethodBugContext(question, context.getPageContext())) {
                    enhanced.append("[当前方法 Bug 分析强制要求]\n");
                    enhanced.append("用户正在覆盖率源码页询问当前/目标方法可能存在的 Bug。当前页面上下文已提供 当前类、目标方法、方法覆盖信息 和 当前源码片段；必须直接基于这些真实源码内容输出潜在 bug、触发条件、影响和修复建议。不要改查调用链，不要回答“未找到调用链数据”，也不要要求用户再次提供方法名。\n\n");
                }
            }
        }

        enhanced.append("[当前问题]\n").append(question);

        // 记录到对话记忆（异步）
        conversationMemory.addUserMessage(
                activeSession != null ? activeSession.getSessionId() : createOrGetSession(context),
                question);

        return enhanced.toString();
    }

    private boolean isSourceMethodBugContext(String question, String pageContext) {
        return promptGuideBuilder.isSourceMethodBugContext(question, pageContext);
    }

    private String buildScenarioGuide(String question, ToolRecommender.Recommendation recommendation) {
        return promptGuideBuilder.buildScenarioGuide(question, recommendation);
    }

    private boolean isToolCatalogQuestion(String question) {
        return promptGuideBuilder.isToolCatalogQuestion(question);
    }

    private String buildToolCatalogResponse() {
        return promptGuideBuilder.buildToolCatalogResponse();
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

    private String createOrGetSession(AgentContext context) {
        ConversationMemoryService.ConversationSession existing = conversationMemory.getActiveSession(
                context.getUserId(), context.getProjectId(), getMemoryScope(context));
        if (existing != null) return existing.getSessionId();
        return conversationMemory.createSession(context.getUserId(), context.getProjectId(), getMemoryScope(context));
    }

    /**
     * 记录AI回复到对话记忆
     */
    public void recordAssistantResponse(AgentContext context, String answer) {
        ConversationMemoryService.ConversationSession session = conversationMemory.getActiveSession(
                context.getUserId(), context.getProjectId(), getMemoryScope(context));
        if (session != null) {
            conversationMemory.addAssistantMessage(session.getSessionId(), answer, null);
        }
    }

    public String clearConversationMemory(String userId, String projectId) {
        return conversationMemory.clearUserProjectSessions(userId, projectId);
    }

    public String clearConversationMemory(String userId, String projectId, String memoryScope) {
        return conversationMemory.clearUserProjectSessions(userId, projectId, normalizeMemoryScope(memoryScope));
    }

    private String getMemoryScope(AgentContext context) {
        return context == null ? DEFAULT_MEMORY_SCOPE : normalizeMemoryScope(context.getMemoryScope());
    }

    private String normalizeMemoryScope(String memoryScope) {
        if (memoryScope == null || memoryScope.trim().isEmpty()) {
            return DEFAULT_MEMORY_SCOPE;
        }
        return memoryScope.trim().toLowerCase(Locale.ROOT);
    }

    // ==================== 公开访问接口 ====================

    public com.oAT.ai.agent.ToolRecommender getToolRecommender() { return toolRecommender; }
    public DynamicLLMSwitcher getLlmSwitcher() { return llmSwitcher; }

    /**
     * 获取自主学习服务（懒加载）
     */
    public AISelfLearningService getSelfLearningService() {
        if (!selfLearningEnabled) {
            return null;
        }
        if (selfLearningService == null) {
            synchronized (this) {
                if (selfLearningService == null) {
                    FeedbackPersistenceService fps = feedbackPersistenceService;
                    if (fps == null) {
                        String dataPath = System.getProperty("oat.data.path",
                                System.getProperty("user.home") + "/oAT/codeData");
                        fps = new FeedbackPersistenceService(dataPath);
                    }
                    double knowledgeThreshold = Double.parseDouble(
                            System.getProperty("ai.enhanced.self-learning.knowledge-hit-threshold", "0.7"));
                    selfLearningService = new AISelfLearningService(fps,
                            Math.max(1, Integer.getInteger("ai.enhanced.self-learning.interval-hours", 6)),
                            knowledgeThreshold);
                    selfLearningService.setToolRecommender(toolRecommender);
                }
            }
        }
        return selfLearningService;
    }

    /**
     * 获取 AI 增强服务综合统计
     */
    public Map<String, Object> getEnhancedStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("semanticCache", semanticCacheService.getStats());
        stats.put("toolRecommender", toolRecommender.getStats());
        stats.put("llmSwitcher", llmSwitcher.getStats());
        stats.put("conversationMemory", conversationMemory.getStats());
        stats.put("fallbackReports", getRecentFallbackReports(5));
        stats.put("agentAvailable", isAvailable());
        stats.put("selfLearningEnabled", selfLearningEnabled);
        stats.put("knowledgeHitEnabled", knowledgeHitEnabled);
        stats.put("dynamicGuideEnabled", dynamicGuideEnabled);
        if (selfLearningEnabled) {
            try {
                AISelfLearningService selfLearning = getSelfLearningService();
                if (selfLearning != null) {
                    stats.put("selfLearning", selfLearning.getStatus());
                }
            } catch (Exception ignored) {
            }
        }
        return stats;
    }
}
