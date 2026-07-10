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
 * 代码关系查询工具
 * 提供接口调用关系、类依赖关系等查询能力
 */
public class CodeRelationTool {

    private static final Logger logger = LoggerFactory.getLogger(CodeRelationTool.class);

    private final AgentDataProvider dataProvider;

    public CodeRelationTool(AgentDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    @Tool("搜索代码关系，根据关键词查找相关的接口、类、方法")
    public String searchCodeRelation(@P("搜索关键词") String keyword) {
        String projectId = AgentContext.getCurrentProjectId();
        if (projectId == null) {
            return "错误：未找到项目上下文，请先选择一个项目";
        }
        if (keyword == null || keyword.trim().isEmpty()) {
            return "错误：请提供搜索关键词";
        }
        try {
            Map<String, Object> result = dataProvider.searchCodeRelation(projectId, keyword);
            if (result == null || result.isEmpty()) {
                return "未找到与 \"" + keyword + "\" 相关的代码关系";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("搜索结果：\"").append(keyword).append("\"\n\n");
            
            // 接口列表
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> interfaces = (List<Map<String, Object>>) result.get("interfaces");
            if (interfaces != null && !interfaces.isEmpty()) {
                sb.append("### 相关接口 (").append(interfaces.size()).append("个)\n\n");
                for (Map<String, Object> iface : interfaces) {
                    sb.append("- ").append(iface.getOrDefault("name", ""));
                    sb.append(" (").append(iface.getOrDefault("method", "")).append(")\n");
                }
            }
            
            // 类列表
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> classes = (List<Map<String, Object>>) result.get("classes");
            if (classes != null && !classes.isEmpty()) {
                sb.append("\n### 相关类 (").append(classes.size()).append("个)\n\n");
                for (Map<String, Object> cls : classes) {
                    sb.append("- ").append(cls.getOrDefault("name", ""));
                    if (cls.containsKey("package")) {
                        sb.append("\n  包: ").append(cls.get("package"));
                    }
                    sb.append("\n");
                }
            }
            
            return sb.toString();
        } catch (Exception e) {
            logger.error("搜索代码关系失败", e);
            return "搜索代码关系失败：" + e.getMessage();
        }
    }

    @Tool("获取接口或类的真实调用关系图；如果没有静态调用边或运行时调用链证据，必须明确返回暂无真实调用关系，不能根据覆盖率节点或相邻方法猜测调用方/被调用方")
    public String getCallGraph(@P("类名（全限定名或简单名）") String className, @P("方法名（可选）") String methodName) {
        if (className == null || className.trim().isEmpty()) {
            return "错误：请提供类名";
        }
        try {
            Map<String, Object> graph = dataProvider.getCallGraph(className, methodName);
            if (graph == null || graph.isEmpty()) {
                return "未找到类 " + className + " 的调用关系";
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> callers = (List<Map<String, Object>>) graph.get("callers");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> callees = (List<Map<String, Object>>) graph.get("callees");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> traces = (List<Map<String, Object>>) graph.get("traces");
            if ((callers == null || callers.isEmpty())
                    && (callees == null || callees.isEmpty())
                    && (traces == null || traces.isEmpty())) {
                StringBuilder empty = new StringBuilder();
                empty.append("未找到 `").append(className);
                if (methodName != null && !methodName.isEmpty()) {
                    empty.append(".").append(methodName);
                }
                empty.append("` 的真实调用关系数据。\n\n");
                empty.append("请不要使用示例链接、示例类名或猜测的调用方法回答；如果需要分析，请先确认该类/方法已有静态扫描数据或运行时调用链数据。\n");
                return empty.toString();
            }
            StringBuilder sb = new StringBuilder();
            sb.append("## ").append(className);
            if (methodName != null && !methodName.isEmpty()) {
                sb.append(".").append(methodName);
            }
            sb.append(" 的调用关系\n\n");
            
            // 调用方（谁调用了这个类/方法）
            if (callers != null && !callers.isEmpty()) {
                sb.append("### 调用方 (谁调用了它)\n\n");
                for (Map<String, Object> caller : callers) {
                    sb.append("- ").append(caller.getOrDefault("className", ""));
                    sb.append(".").append(caller.getOrDefault("methodName", ""));
                    sb.append(" (").append(caller.getOrDefault("type", "")).append(")\n");
                }
            }
            
            // 被调用方（这个类/方法调用了谁）
            if (callees != null && !callees.isEmpty()) {
                sb.append("\n### 被调用方 (它调用了谁)\n\n");
                for (Map<String, Object> callee : callees) {
                    sb.append("- ").append(callee.getOrDefault("className", ""));
                    sb.append(".").append(callee.getOrDefault("methodName", ""));
                    sb.append(" (").append(callee.getOrDefault("type", "")).append(")\n");
                }
            }
            
            // 关联的调用链
            if (traces != null && !traces.isEmpty()) {
                sb.append("\n### 关联调用链 (最近").append(traces.size()).append("条)\n\n");
                for (Map<String, Object> trace : traces) {
                    sb.append("- TraceID: ").append(trace.getOrDefault("traceId", ""));
                    sb.append(" - ").append(trace.getOrDefault("url", "")).append("\n");
                }
            }
            
            return sb.toString();
        } catch (Exception e) {
            logger.error("获取调用关系图失败", e);
            return "获取调用关系图失败：" + e.getMessage();
        }
    }

    @Tool("获取类的真实调用关系图（仅类级别）；如果没有真实调用关系数据，必须明确说明暂无数据")
    public String getClassCallGraph(@P("类名（全限定名或简单名）") String className) {
        return getCallGraph(className, null);
    }
}
