package com.oAT.web.control;

import com.oAT.ai.agent.AgentContext;
import com.oAT.ai.agent.AIAgentService;
import com.oAT.ai.agent.ToolRecommender;
import com.oAT.web.api.ai.AIStreamingSseService;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.entity.ProjectVo;
import com.oAT.web.service.entity.UserVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI流式输出控制器
 * 支持SSE（Server-Sent Events）实现打字机效果 + 可视化数据推送
 *
 * <p>SSE 事件类型：</p>
 * <ul>
 *   <li>start - 开始事件，包含问题信息</li>
 *   <li>thinking - AI思考中状态</li>
 *   <li>tool_call - 工具调用通知（显示正在查询的数据）</li>
 *   <li>content - 流式内容分块</li>
 *   <li>visualization - 可视化数据（图表配置）</li>
 *   <li>suggestion - 后续建议</li>
 *   <li>complete - 完成事件</li>
 *   <li>error - 错误事件</li>
 * </ul>
 */
@Controller
@RequestMapping("/api/projects/{projectId}/ai")
public class AIStreamingControl {

    private static final Logger logger = LoggerFactory.getLogger(AIStreamingControl.class);

    private static final Long SSE_TIMEOUT = 300_000L; // 5分钟
    private static final String DEFAULT_STREAM_MEMORY_SCOPE = "workbench";

    private final ExecutorService sseExecutor = Executors.newFixedThreadPool(10);

    @Autowired
    private AIAgentService aiAgentService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private AIStreamingSseService aiStreamingSseService;

    /**
     * 流式AI对话（SSE）
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter askStreaming(@PathVariable String projectId,
                                   @SessionAttribute UserVo user,
                                   @RequestParam String question,
                                   @RequestParam(required = false) String pageContext,
                                   @RequestParam(required = false) String imageData,
                                   @RequestParam(required = false) String memoryScope) {

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        AtomicBoolean completed = new AtomicBoolean(false);

        // 异步处理
        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                // 1. 发送开始事件
                sendJsonEvent(emitter, "start", Map.of(
                        "question", question,
                        "timestamp", startTime
                ));

                // 2. 获取项目信息
                ProjectVo project = projectService.getProject(projectId);
                if (project == null) {
                    sendJsonEvent(emitter, "error", Map.of("message", "项目不存在"));
                    completeEmitter(emitter, completed);
                    return;
                }

                // 3. 设置上下文
                AgentContext context = new AgentContext(projectId, user.getId(), user.getName());
                context.setMemoryScope(normalizeMemoryScope(memoryScope));
                if (pageContext != null) {
                    context.setPageContext(pageContext);
                }

                // 4. 智能工具推荐（发送给前端显示）
                ToolRecommender.Recommendation recommendation = null;
                try {
                    if (aiAgentService.getToolRecommender() != null) {
                        recommendation = aiAgentService.getToolRecommender().recommend(question);
                        if (recommendation != null && recommendation.detectedIntent != null) {
                            Map<String, Object> toolInfo = new HashMap<>();
                            toolInfo.put("intent", recommendation.detectedIntent);
                            toolInfo.put("primaryTool", recommendation.primaryTool);
                            toolInfo.put("confidence", Math.round(recommendation.confidence * 100));
                            toolInfo.put("secondaryTools", recommendation.secondaryTools);
                            toolInfo.put("reasoning", recommendation.reasoning);
                            sendJsonEvent(emitter, "tool_call", toolInfo);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Tool recommendation failed (non-critical): {}", e.getMessage());
                }

                // 5. 发送思考中事件
                sendJsonEvent(emitter, "thinking", Map.of(
                        "message", "AI正在分析您的问题...",
                        "intent", recommendation != null ? recommendation.detectedIntent : "general"
                ));
                sendProcessingStatus(emitter, "analyzing", "AI正在分析您的问题...");

                // 6. 调用 AI 服务
                String response;
                if (imageData != null && !imageData.isEmpty()) {
                    response = aiAgentService.chatWithImage(context, question, pageContext, imageData);
                } else if (pageContext != null && !pageContext.isEmpty()) {
                    response = aiAgentService.chatWithContext(context, question, pageContext);
                } else {
                    response = aiAgentService.chat(context, question);
                }

                long responseTime = System.currentTimeMillis() - startTime;
                emitFallbackToolEvents(emitter);

                // 7. 流式发送响应（模拟打字机效果）
                if (response != null && !response.isEmpty()) {
                    sendTypingEffect(emitter, response);

                    // 8. 发送可视化建议数据（基于回答内容推断）
                    Map<String, Object> vizData = inferVisualizationData(question, response, projectId);
                    if (!vizData.isEmpty()) {
                        sendJsonEvent(emitter, "visualization", vizData);
                    }
                } else {
                    sendJsonEvent(emitter, "error", Map.of("message", "AI服务暂时无法响应"));
                }

                // 10. 发送完成事件（含元数据）
                Map<String, Object> completeData = new HashMap<>();
                completeData.put("responseTimeMs", responseTime);
                completeData.put("responseLength", response != null ? response.length() : 0);
                completeData.put("model", aiAgentService.getLlmSwitcher() != null ?
                        aiAgentService.getLlmSwitcher().getDefaultModelName() : "default");
                sendJsonEvent(emitter, "complete", completeData);
                completeEmitter(emitter, completed);

            } catch (Exception e) {
                logger.error("SSE streaming failed", e);
                trySendJsonEvent(emitter, "error", Map.of("message", errorMessage(e)));
                completeEmitterWithError(emitter, completed, e);
            }
        }, sseExecutor);

        // 设置回调
        emitter.onCompletion(() -> logger.debug("SSE connection completed for project: {}", projectId));
        emitter.onTimeout(() -> {
            logger.warn("SSE connection timeout for project: {}", projectId);
            completeEmitter(emitter, completed);
        });
        emitter.onError(throwable -> {
            logger.error("SSE connection error for project {}: {}", projectId, errorMessage(throwable));
            completeEmitterWithError(emitter, completed, throwable);
        });

        return emitter;
    }

    /**
     * AI代码Bug检测 - SSE流式分析
     * 用户传入类名，AI获取源码并进行智能Bug检测
     */
    @PostMapping(value = "/analyze/bugs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter analyzeBugs(@PathVariable String projectId,
                                  @SessionAttribute UserVo user,
                                  @RequestParam String className,
                                  @RequestParam(required = false) String methodName,
                                  @RequestParam(required = false) String sourceCodeSnippet) {

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        AtomicBoolean completed = new AtomicBoolean(false);

        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                sendJsonEvent(emitter, "start", Map.of(
                        "type", "bug-detect",
                        "target", className != null ? className : "",
                        "timestamp", startTime
                ));

                ProjectVo project = projectService.getProject(projectId);
                if (project == null) {
                    sendJsonEvent(emitter, "error", Map.of("message", "项目不存在"));
                    completeEmitter(emitter, completed);
                    return;
                }

                AgentContext context = new AgentContext(projectId, user.getId(), user.getName());
                sendJsonEvent(emitter, "thinking", Map.of("message", "正在获取源码并分析..."));
                sendProcessingStatus(emitter, "analyzing", "正在获取源码并分析...");

                // 构建检测问题
                StringBuilder questionBuilder = new StringBuilder("请对以下代码进行AI Bug检测：");
                questionBuilder.append("\n- 类名: ").append(className != null ? className : "");
                if (methodName != null && !methodName.isEmpty()) {
                    questionBuilder.append("\n- 方法: ").append(methodName);
                }
                if (sourceCodeSnippet != null && !sourceCodeSnippet.isEmpty()) {
                    questionBuilder.append("\n- 源码片段已提供");
                }
                questionBuilder.append("\n\n请使用 detectBugs 或 detectBugsInMethod 工具进行深度分析。");

                String response = aiAgentService.chat(context, questionBuilder.toString());
                long responseTime = System.currentTimeMillis() - startTime;
                emitFallbackToolEvents(emitter);

                if (response != null && !response.isEmpty()) {
                    sendTypingEffect(emitter, response);

                    // 推送检测结果可视化
                    Map<String, Object> bugViz = new HashMap<>();
                    bugViz.put("chartType", "table");
                    bugViz.put("title", className + " - Bug检测结果");
                    bugViz.put("analysisType", "bug-detect");
                    bugViz.put("targetClass", className);
                    bugViz.put("responseTimeMs", responseTime);
                    sendJsonEvent(emitter, "visualization", bugViz);
                } else {
                    sendJsonEvent(emitter, "error", Map.of("message", "Bug检测失败，请重试"));
                }

                sendJsonEvent(emitter, "complete", Map.of(
                        "responseTimeMs", responseTime,
                        "responseLength", response != null ? response.length() : 0,
                        "type", "bug-detect"
                ));
                completeEmitter(emitter, completed);
            } catch (Exception e) {
                logger.error("Bug detect SSE failed", e);
                trySendJsonEvent(emitter, "error", Map.of("message", errorMessage(e)));
                completeEmitterWithError(emitter, completed, e);
            }
        }, sseExecutor);

        emitter.onCompletion(() -> logger.debug("Bug detect completed for: {}", className));
        emitter.onTimeout(() -> {
            logger.warn("Bug detect timeout for: {}", className);
            completeEmitter(emitter, completed);
        });
        emitter.onError(t -> {
            logger.error("Bug detect error for {}: {}", className, errorMessage(t));
            completeEmitterWithError(emitter, completed, t);
        });
        return emitter;
    }

    /**
     * AI调用链路分析 - SSE流式分析
     * 传入TraceID，生成完整的调用拓扑和性能瓶颈分析
     */
    @PostMapping(value = "/analyze/callchain", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter analyzeCallChain(@PathVariable String projectId,
                                        @SessionAttribute UserVo user,
                                        @RequestParam String traceId) {

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        AtomicBoolean completed = new AtomicBoolean(false);

        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                sendJsonEvent(emitter, "start", Map.of(
                        "type", "callchain-analysis",
                        "traceId", traceId,
                        "timestamp", startTime
                ));

                ProjectVo project = projectService.getProject(projectId);
                if (project == null) {
                    sendJsonEvent(emitter, "error", Map.of("message", "项目不存在"));
                    completeEmitter(emitter, completed);
                    return;
                }

                AgentContext context = new AgentContext(projectId, user.getId(), user.getName());
                sendJsonEvent(emitter, "thinking", Map.of("message", "正在分析调用链路..."));
                sendProcessingStatus(emitter, "analyzing", "正在分析调用链路...");

                String question = "请对TraceID为 " + traceId + " 的调用链进行深度AI分析。\n" +
                        "使用 analyzeCallChain 工具获取完整链路数据，生成Mermaid拓扑图、性能瓶颈定位和异常根因分析。";

                String response = aiAgentService.chat(context, question);
                long responseTime = System.currentTimeMillis() - startTime;
                emitFallbackToolEvents(emitter);

                if (response != null && !response.isEmpty()) {
                    sendTypingEffect(emitter, response);

                    // 推送链路拓扑可视化数据
                    Map<String, Object> chainViz = new HashMap<>();
                    chainViz.put("chartType", "mermaid");
                    chainViz.put("title", "调用链路拓扑图 - " + traceId.substring(0, Math.min(12, traceId.length())));
                    chainViz.put("analysisType", "callchain");
                    chainViz.put("traceId", traceId);
                    chainViz.put("responseTimeMs", responseTime);
                    sendJsonEvent(emitter, "visualization", chainViz);
                } else {
                    sendJsonEvent(emitter, "error", Map.of("message", "调用链分析失败"));
                }

                sendJsonEvent(emitter, "complete", Map.of(
                        "responseTimeMs", responseTime,
                        "responseLength", response != null ? response.length() : 0,
                        "type", "callchain-analysis"
                ));
                completeEmitter(emitter, completed);
            } catch (Exception e) {
                logger.error("CallChain analysis SSE failed", e);
                trySendJsonEvent(emitter, "error", Map.of("message", errorMessage(e)));
                completeEmitterWithError(emitter, completed, e);
            }
        }, sseExecutor);

        emitter.onCompletion(() -> logger.debug("CallChain analysis completed: {}", traceId));
        emitter.onTimeout(() -> {
            logger.warn("CallChain analysis timeout: {}", traceId);
            completeEmitter(emitter, completed);
        });
        emitter.onError(t -> {
            logger.error("CallChain analysis error {}: {}", traceId, errorMessage(t));
            completeEmitterWithError(emitter, completed, t);
        });
        return emitter;
    }

    /**
     * AI调用链差异比对 - SSE流式比对
     * 对比两条调用链的差异，用于正常vs异常的根因定位或性能回归分析
     */
    @PostMapping(value = "/compare/callchains", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter compareCallChains(@PathVariable String projectId,
                                        @SessionAttribute UserVo user,
                                        @RequestParam String baselineTraceId,
                                        @RequestParam String compareTraceId,
                                        @RequestParam(defaultValue = "diff") String mode) {

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        AtomicBoolean completed = new AtomicBoolean(false);

        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                sendJsonEvent(emitter, "start", Map.of(
                        "type", "callchain-compare",
                        "mode", mode,
                        "baselineTraceId", baselineTraceId,
                        "compareTraceId", compareTraceId,
                        "timestamp", startTime
                ));

                ProjectVo project = projectService.getProject(projectId);
                if (project == null) {
                    sendJsonEvent(emitter, "error", Map.of("message", "项目不存在"));
                    completeEmitter(emitter, completed);
                    return;
                }

                AgentContext context = new AgentContext(projectId, user.getId(), user.getName());
                sendJsonEvent(emitter, "thinking", Map.of(
                        "message", "正在对比两条调用链...",
                        "mode", mode));
                sendProcessingStatus(emitter, "analyzing", "正在对比两条调用链...");

                String question;
                switch (mode.toLowerCase()) {
                    case "rootcause":
                        question = "异常根因定位：\n" +
                                "- 正常请求TraceID: " + baselineTraceId + "\n" +
                                "- 异常请求TraceID: " + compareTraceId + "\n\n" +
                                "请使用 locateRootCause 工具进行智能根因分析，找出导致异常的根本原因。";
                        break;
                    case "regression":
                        question = "性能回归分析：\n" +
                                "- 早期版本TraceID: " + baselineTraceId + "\n" +
                                "- 近期版本TraceID: " + compareTraceId + "\n\n" +
                                "请使用 compareCallChains 工具对比两条链路的性能变化，判断是否存在性能回归。";
                        break;
                    default:
                        question = "调用链差异对比：\n" +
                                "- 基准链路（通常为正常请求）: " + baselineTraceId + "\n" +
                                "- 对比链路（通常有问题）: " + compareTraceId + "\n\n" +
                                "请使用 compareCallChains 工具进行全面的差异对比分析，包括调用路径、性能、状态的变化。";
                        break;
                }

                String response = aiAgentService.chat(context, question);
                long responseTime = System.currentTimeMillis() - startTime;
                emitFallbackToolEvents(emitter);

                if (response != null && !response.isEmpty()) {
                    sendTypingEffect(emitter, response);

                    // 推送对比可视化数据
                    Map<String, Object> compViz = new HashMap<>();
                    compViz.put("chartType", "diff-table");
                    compViz.put("title", "调用链差异比对结果");
                    compViz.put("analysisType", "compare-" + mode);
                    compViz.put("baselineTraceId", baselineTraceId);
                    compViz.put("compareTraceId", compareTraceId);
                    compViz.put("responseTimeMs", responseTime);
                    sendJsonEvent(emitter, "visualization", compViz);
                } else {
                    sendJsonEvent(emitter, "error", Map.of("message", "调用链对比失败"));
                }

                sendJsonEvent(emitter, "complete", Map.of(
                        "responseTimeMs", responseTime,
                        "responseLength", response != null ? response.length() : 0,
                        "type", "callchain-compare",
                        "mode", mode
                ));
                completeEmitter(emitter, completed);
            } catch (Exception e) {
                logger.error("CallChain compare SSE failed", e);
                trySendJsonEvent(emitter, "error", Map.of("message", errorMessage(e)));
                completeEmitterWithError(emitter, completed, e);
            }
        }, sseExecutor);

        emitter.onCompletion(() -> logger.debug("CallChain compare completed"));
        emitter.onTimeout(() -> {
            logger.warn("CallChain compare timeout");
            completeEmitter(emitter, completed);
        });
        emitter.onError(t -> {
            logger.error("CallChain compare error: {}", errorMessage(t));
            completeEmitterWithError(emitter, completed, t);
        });
        return emitter;
    }

    private void trySendJsonEvent(SseEmitter emitter, String event, Object data) {
        aiStreamingSseService.trySendJsonEvent(emitter, event, data);
    }

    private void completeEmitter(SseEmitter emitter, AtomicBoolean completed) {
        aiStreamingSseService.completeEmitter(emitter, completed);
    }

    private void completeEmitterWithError(SseEmitter emitter, AtomicBoolean completed, Throwable error) {
        aiStreamingSseService.completeEmitterWithError(emitter, completed, error);
    }

    private String errorMessage(Throwable error) {
        return aiStreamingSseService.errorMessage(error);
    }

    /**
     * 发送 SSE JSON 事件（自动序列化）
     */
    private void sendJsonEvent(SseEmitter emitter, String event, Object data) throws IOException {
        aiStreamingSseService.sendJsonEvent(emitter, event, data);
    }

    private void sendProcessingStatus(SseEmitter emitter, String phase, String message) throws IOException {
        aiStreamingSseService.sendProcessingStatus(emitter, phase, message);
    }

    private String normalizeMemoryScope(String memoryScope) {
        if (memoryScope == null || memoryScope.trim().isEmpty()) {
            return DEFAULT_STREAM_MEMORY_SCOPE;
        }
        String sanitized = memoryScope.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "");
        return sanitized.isEmpty() ? DEFAULT_STREAM_MEMORY_SCOPE : sanitized;
    }

    private void emitFallbackToolEvents(SseEmitter emitter) throws IOException {
        aiStreamingSseService.emitFallbackToolEvents(emitter);
    }

    /**
     * 根据问答内容推断可视化数据（推荐图表类型和配置）
     * 前端收到此事件后可自动渲染对应图表
     */
    private Map<String, Object> inferVisualizationData(String question, String response, String projectId) {
        return aiStreamingSseService.inferVisualizationData(question, response, projectId);
    }

    /**
     * 模拟打字机效果发送响应
     * 按字符分批发送，创造打字机效果
     */
    private void sendTypingEffect(SseEmitter emitter, String response) throws IOException, InterruptedException {
        aiStreamingSseService.sendTypingEffect(emitter, response);
    }

}
