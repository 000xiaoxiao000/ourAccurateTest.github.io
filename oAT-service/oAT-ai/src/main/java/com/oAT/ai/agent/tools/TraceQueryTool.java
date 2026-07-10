package com.oAT.ai.agent.tools;

import com.oAT.ai.agent.AgentContext;
import com.oAT.ai.agent.AgentDataProvider;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 调用链路查询工具
 * 提供调用链列表、链路详情等查询能力
 */
public class TraceQueryTool {

    private static final Logger logger = LoggerFactory.getLogger(TraceQueryTool.class);

    private final AgentDataProvider dataProvider;

    public TraceQueryTool(AgentDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    @Tool("获取最近的调用链列表")
    public String getRecentTraces(@P("应用ID，可选") String appId, @P("返回数量限制，默认20") int limit) {
        String projectId = AgentContext.getCurrentProjectId();
        if (projectId == null) {
            return "错误：未找到项目上下文，请先选择一个项目";
        }
        if (limit <= 0) {
            limit = 20;
        }
        try {
            List<Map<String, Object>> traces = dataProvider.getTraceList(projectId, appId, limit);
            if (traces == null || traces.isEmpty()) {
                return "暂无调用链数据";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("最近 ").append(traces.size()).append(" 条调用链：\n\n");
            int count = 0;
            for (Map<String, Object> trace : traces) {
                count++;
                sb.append(count).append(". TraceID: ").append(trace.getOrDefault("traceId", ""));
                sb.append("\n   - URL: ").append(trace.getOrDefault("url", ""));
                sb.append("\n   - 应用: ").append(trace.getOrDefault("appName", ""));
                sb.append("\n   - 时间: ").append(trace.getOrDefault("time", ""));
                sb.append("\n");
                if (count >= 20) {
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            logger.error("获取调用链列表失败", e);
            return "获取调用链列表失败：" + e.getMessage();
        }
    }

    @Tool("获取调用链的详细信息，包括调用节点和依赖关系")
    public String getTraceDetail(@P("调用链ID") String traceId) {
        if (traceId == null || traceId.trim().isEmpty()) {
            return "错误：请提供调用链ID";
        }
        try {
            Map<String, Object> trace = dataProvider.getTraceDetail(traceId);
            if (trace == null || trace.isEmpty()) {
                return "未找到调用链信息，ID: " + traceId;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("## 调用链详情\n\n");
            sb.append("- TraceID: ").append(trace.getOrDefault("traceId", "")).append("\n");
            sb.append("- 入口URL: ").append(trace.getOrDefault("url", "")).append("\n");
            sb.append("- 应用: ").append(trace.getOrDefault("appName", "")).append("\n");
            sb.append("- 客户端IP: ").append(trace.getOrDefault("clientIp", "")).append("\n");
            sb.append("- 服务端IP: ").append(trace.getOrDefault("serverIp", "")).append("\n");
            sb.append("- 开始时间: ").append(trace.getOrDefault("startTime", "")).append("\n");
            sb.append("- 耗时: ").append(trace.getOrDefault("duration", "-")).append("ms\n");
            
            // 调用节点信息
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) trace.get("nodes");
            if (nodes != null && !nodes.isEmpty()) {
                sb.append("\n### 调用节点 (共").append(nodes.size()).append("个)\n\n");
                for (Map<String, Object> node : nodes) {
                    String type = (String) node.getOrDefault("type", "unknown");
                    sb.append("- [").append(type).append("] ");
                    sb.append(node.getOrDefault("name", ""));
                    sb.append(" (耗时: ").append(node.getOrDefault("duration", "-")).append("ms)\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            logger.error("获取调用链详情失败", e);
            return "获取调用链详情失败：" + e.getMessage();
        }
    }

    @Tool("按应用筛选调用链")
    public String getTracesByApp(@P("应用ID") String appId) {
        return getRecentTraces(appId, 20);
    }

    @Tool("根据应用名称获取该应用的调用链列表。当用户想看某个应用的调用链时使用。")
    public String getTracesByAppName(@P("应用名称") String appName, @P("返回数量限制，默认20") int limit) {
        if (appName == null || appName.trim().isEmpty()) {
            return getRecentTraces(null, limit > 0 ? limit : 20);
        }
        if (limit <= 0) {
            limit = 20;
        }
        try {
            String projectId = AgentContext.getCurrentProjectId();
            if (projectId == null) {
                return "错误：未找到项目上下文";
            }

            // 根据应用名称找到应用ID
            List<Map<String, Object>> apps = dataProvider.getApps(projectId);
            String appId = null;
            if (apps != null) {
                for (Map<String, Object> app : apps) {
                    String name = (String) app.getOrDefault("name", "");
                    if (name.equalsIgnoreCase(appName.trim()) ||
                        name.toLowerCase().contains(appName.trim().toLowerCase())) {
                        appId = (String) app.get("id");
                        break;
                    }
                }
            }
            if (appId == null) {
                return "未找到应用：" + appName + "。请检查应用名称是否正确。";
            }

            return getRecentTraces(appId, limit);
        } catch (Exception e) {
            logger.error("获取应用调用链失败", e);
            return "获取应用调用链失败：" + e.getMessage();
        }
    }
}
