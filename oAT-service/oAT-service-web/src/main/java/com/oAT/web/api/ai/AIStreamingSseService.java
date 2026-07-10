package com.oAT.web.api.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oAT.ai.agent.AIAgentService;
import com.oAT.ai.agent.fallback.FallbackParameterReport;
import com.oAT.ai.agent.fallback.FallbackReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AIStreamingSseService {

    private static final Logger logger = LoggerFactory.getLogger(AIStreamingSseService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AIAgentService aiAgentService;

    public AIStreamingSseService(AIAgentService aiAgentService) {
        this.aiAgentService = aiAgentService;
    }

    public void trySendJsonEvent(SseEmitter emitter, String event, Object data) {
        try {
            sendJsonEvent(emitter, event, data);
        } catch (Exception e) {
            logger.debug("Failed to send SSE {} event: {}", event, errorMessage(e));
        }
    }

    public void completeEmitter(SseEmitter emitter, AtomicBoolean completed) {
        if (completed.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (Exception e) {
                logger.debug("SSE complete ignored: {}", errorMessage(e));
            }
        }
    }

    public void completeEmitterWithError(SseEmitter emitter, AtomicBoolean completed, Throwable error) {
        if (completed.compareAndSet(false, true)) {
            try {
                emitter.completeWithError(error);
            } catch (Exception e) {
                logger.debug("SSE completeWithError ignored: {}", errorMessage(e));
            }
        }
    }

    public String errorMessage(Throwable error) {
        if (error == null) {
            return "内部错误";
        }
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    public void sendJsonEvent(SseEmitter emitter, String event, Object data) throws IOException {
        try {
            String jsonStr = JSON.writeValueAsString(data);
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(jsonStr)
                    .reconnectTime(3000));
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(jsonStr)
                    .id(buildEventId(event))
                    .reconnectTime(3000));
        } catch (JsonProcessingException e) {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data("{\"error\":\"serialization_error\"}")
                    .reconnectTime(3000));
        }
    }

    public void sendProcessingStatus(SseEmitter emitter, String phase, String message) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "status");
        payload.put("phase", phase);
        payload.put("message", message);
        sendJsonEvent(emitter, "status", payload);
    }

    public void emitFallbackToolEvents(SseEmitter emitter) throws IOException {
        FallbackReport report = aiAgentService.getLatestFallbackReport();
        if (report == null || !report.isSuccess()) {
            return;
        }

        Map<String, Object> toolCallPayload = new LinkedHashMap<>();
        toolCallPayload.put("toolName", report.getEffectiveToolName());
        toolCallPayload.put("strategy", report.getStrategy());
        toolCallPayload.put("retried", report.isRetried());
        toolCallPayload.put("durationMs", report.getDurationMs());
        toolCallPayload.put("parameterCount", report.getParameterReports().size());
        sendJsonEvent(emitter, "tool_call", toolCallPayload);

        Map<String, Object> execPayload = new LinkedHashMap<>();
        execPayload.put("type", "exec");
        execPayload.put("tool", report.getEffectiveToolName());
        execPayload.put("success", true);
        execPayload.put("durationMs", report.getDurationMs());
        execPayload.put("strategy", report.getStrategy());
        execPayload.put("resultLength", report.getResultLength());
        execPayload.put("parameters", buildExecParameterSummary(report));
        sendJsonEvent(emitter, "exec", execPayload);
    }

    public Map<String, Object> inferVisualizationData(String question, String response, String projectId) {
        Map<String, Object> viz = new HashMap<>();
        String lowerQ = question.toLowerCase();

        if (containsAny(lowerQ, "趋势", "trend", "变化", "历史")) {
            viz.put("chartType", "line");
            viz.put("title", "数据趋势图");
            viz.put("suggestedApi", "/p/" + projectId + "/map/code");
        } else if (containsAny(lowerQ, "对比", "compare", "分布", "比例", "占比")) {
            viz.put("chartType", "pie");
            viz.put("title", "数据分布图");
            viz.put("suggestedApi", "/p/" + projectId + "/map/code");
        } else if (containsAny(lowerQ, "排名", "top", "最差", "最低", "最高", "排序")) {
            viz.put("chartType", "bar");
            viz.put("title", "排行榜");
            viz.put("suggestedApi", "/p/" + projectId + "/map/code");
        } else if (containsAny(lowerQ, "性能", "响应时间", "慢", "延迟", "p95", "p99")) {
            viz.put("chartType", "bar");
            viz.put("title", "性能指标");
            viz.put("suggestedApi", "/p/" + projectId + "/map/home");
        } else if (containsAny(lowerQ, "错误率", "异常", "缺陷", "bug", "失败")) {
            viz.put("chartType", "heatmap");
            viz.put("title", "错误热力图");
            viz.put("suggestedApi", "/p/" + projectId + "/map/home");
        }

        if (response != null && (response.contains("|") && response.contains("---") ||
                response.contains("<table"))) {
            return viz;
        }

        if (!viz.isEmpty()) {
            viz.put("actionHint", "点击查看详细图表");
            viz.put("autoRender", true);
        }
        return viz;
    }

    public void sendTypingEffect(SseEmitter emitter, String response) throws IOException, InterruptedException {
        int chunkSize = 10;
        int length = response.length();

        for (int i = 0; i < length; i += chunkSize) {
            int end = Math.min(i + chunkSize, length);
            String chunk = response.substring(i, end);

            SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .name("content")
                    .data(chunk)
                    .reconnectTime(3000);

            emitter.send(event);
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(chunk)
                    .reconnectTime(3000));

            Thread.sleep(50);
        }
    }

    private Map<String, Object> buildExecParameterSummary(FallbackReport report) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (FallbackParameterReport parameterReport : report.getParameterReports()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("matchedKey", parameterReport.getMatchedKey());
            item.put("strategy", parameterReport.getStrategy());
            item.put("targetType", parameterReport.getTargetType());
            item.put("usedDefault", parameterReport.isUsedDefault());
            item.put("explicitNull", parameterReport.isExplicitNull());
            item.put("message", parameterReport.getMessage());
            payload.put(parameterReport.getParameterName(), item);
        }
        return payload;
    }

    private String buildEventId(String event) {
        return event + "-" + System.currentTimeMillis();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
