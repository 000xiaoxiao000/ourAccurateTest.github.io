package com.oAT.ai.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oAT.ai.agent.AgentContext;
import com.oAT.ai.agent.AgentDataProvider;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 应用状态查询工具
 * 提供应用列表、在线状态、应用详情等查询能力
 */
public class AppStatusTool {

    private static final Logger logger = LoggerFactory.getLogger(AppStatusTool.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentDataProvider dataProvider;

    public AppStatusTool(AgentDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    @Tool("获取项目下的所有应用列表，包括应用名称、状态等信息")
    public String getApps() {
        String projectId = AgentContext.getCurrentProjectId();
        if (projectId == null) {
            return "错误：未找到项目上下文，请先选择一个项目";
        }
        try {
            List<Map<String, Object>> apps = dataProvider.getApps(projectId);
            if (apps == null || apps.isEmpty()) {
                return "当前项目下没有应用";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("共找到 ").append(apps.size()).append(" 个应用：\n\n");
            for (int i = 0; i < apps.size(); i++) {
                Map<String, Object> app = apps.get(i);
                sb.append(i + 1).append(". ").append(app.getOrDefault("name", "未知"));
                if (Boolean.TRUE.equals(app.get("online"))) {
                    sb.append(" [在线]");
                } else {
                    sb.append(" [离线]");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logger.error("获取应用列表失败", e);
            return "获取应用列表失败：" + e.getMessage();
        }
    }

    @Tool("获取当前在线的应用列表")
    public String getOnlineApps() {
        String projectId = AgentContext.getCurrentProjectId();
        if (projectId == null) {
            return "错误：未找到项目上下文，请先选择一个项目";
        }
        try {
            List<Map<String, Object>> apps = dataProvider.getOnlineApps(projectId);
            if (apps == null || apps.isEmpty()) {
                return "当前没有在线应用";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("当前有 ").append(apps.size()).append(" 个应用在线：\n\n");
            for (int i = 0; i < apps.size(); i++) {
                Map<String, Object> app = apps.get(i);
                sb.append(i + 1).append(". ").append(app.getOrDefault("name", "未知"));
                sb.append(" (ID: ").append(app.getOrDefault("id", "")).append(")\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logger.error("获取在线应用失败", e);
            return "获取在线应用失败：" + e.getMessage();
        }
    }

    @Tool("获取指定应用的详细信息")
    public String getAppDetail(@P("应用ID") String appId) {
        if (appId == null || appId.trim().isEmpty()) {
            return "错误：请提供应用ID";
        }
        try {
            Map<String, Object> app = dataProvider.getAppDetail(appId);
            if (app == null || app.isEmpty()) {
                return "未找到应用信息，ID: " + appId;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("## 应用详情\n\n");
            sb.append("- 应用名称：").append(app.getOrDefault("name", "未知")).append("\n");
            sb.append("- 应用ID：").append(app.getOrDefault("id", "")).append("\n");
            sb.append("- 状态：").append(Boolean.TRUE.equals(app.get("online")) ? "在线" : "离线").append("\n");
            if (app.containsKey("describe")) {
                sb.append("- 描述：").append(app.get("describe")).append("\n");
            }
            if (app.containsKey("createTime")) {
                sb.append("- 创建时间：").append(app.get("createTime")).append("\n");
            }
            if (app.containsKey("lastHeartbeat")) {
                sb.append("- 最后心跳：").append(app.get("lastHeartbeat")).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logger.error("获取应用详情失败", e);
            return "获取应用详情失败：" + e.getMessage();
        }
    }

    @Tool("根据应用名称搜索应用")
    public String searchAppByName(@P("应用名称关键词") String keyword) {
        String projectId = AgentContext.getCurrentProjectId();
        if (projectId == null) {
            return "错误：未找到项目上下文，请先选择一个项目";
        }
        if (keyword == null || keyword.trim().isEmpty()) {
            return "错误：请提供搜索关键词";
        }
        try {
            List<Map<String, Object>> apps = dataProvider.getApps(projectId);
            if (apps == null) {
                return "未找到匹配的应用";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("搜索结果：\n\n");
            int count = 0;
            for (Map<String, Object> app : apps) {
                String name = (String) app.getOrDefault("name", "");
                if (name.toLowerCase().contains(keyword.toLowerCase())) {
                    count++;
                    sb.append(count).append(". ").append(name);
                    sb.append(" (ID: ").append(app.getOrDefault("id", "")).append(")");
                    if (Boolean.TRUE.equals(app.get("online"))) {
                        sb.append(" [在线]");
                    }
                    sb.append("\n");
                }
            }
            if (count == 0) {
                return "未找到包含 \"" + keyword + "\" 的应用";
            }
            sb.append("\n共找到 ").append(count).append(" 个匹配的应用");
            return sb.toString();
        } catch (Exception e) {
            logger.error("搜索应用失败", e);
            return "搜索应用失败：" + e.getMessage();
        }
    }
}
