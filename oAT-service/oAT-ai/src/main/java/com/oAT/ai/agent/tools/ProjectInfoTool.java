package com.oAT.ai.agent.tools;

import com.oAT.ai.agent.AgentContext;
import com.oAT.ai.agent.AgentDataProvider;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目信息查询工具
 * 提供项目基本信息、统计数据等查询能力
 */
public class ProjectInfoTool {

    private static final Logger logger = LoggerFactory.getLogger(ProjectInfoTool.class);

    private final AgentDataProvider dataProvider;

    public ProjectInfoTool(AgentDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    @Tool("获取当前项目的基本信息，包括项目名称、描述、创建时间等")
    public String getProjectInfo() {
        String projectId = AgentContext.getCurrentProjectId();
        if (projectId == null) {
            return "错误：未找到项目上下文，请先选择一个项目";
        }
        try {
            Map<String, Object> projectInfo = dataProvider.getProjectInfo(projectId);
            if (projectInfo == null || projectInfo.isEmpty()) {
                return "未找到项目信息";
            }
            return formatProjectInfo(projectInfo);
        } catch (Exception e) {
            logger.error("获取项目信息失败", e);
            return "获取项目信息失败：" + e.getMessage();
        }
    }

    @Tool("获取项目统计数据，包括应用数量、在线数量、覆盖率概况等")
    public String getProjectStatistics() {
        String projectId = AgentContext.getCurrentProjectId();
        if (projectId == null) {
            return "错误：未找到项目上下文，请先选择一个项目";
        }
        try {
            Map<String, Object> stats = dataProvider.getProjectStatistics(projectId);
            if (stats == null || stats.isEmpty()) {
                return "未找到项目统计数据";
            }
            return formatStatistics(stats);
        } catch (Exception e) {
            logger.error("获取项目统计失败", e);
            return "获取项目统计失败：" + e.getMessage();
        }
    }

    @Tool("获取项目概览，整合项目信息、应用状态和覆盖率情况")
    public String getProjectOverview() {
        String projectId = AgentContext.getCurrentProjectId();
        if (projectId == null) {
            return "错误：未找到项目上下文，请先选择一个项目";
        }
        try {
            StringBuilder result = new StringBuilder();
            
            // 项目基本信息
            Map<String, Object> projectInfo = dataProvider.getProjectInfo(projectId);
            if (projectInfo != null && !projectInfo.isEmpty()) {
                result.append("## 项目信息\n");
                result.append(formatProjectInfo(projectInfo)).append("\n\n");
            }
            
            // 项目统计
            Map<String, Object> stats = dataProvider.getProjectStatistics(projectId);
            if (stats != null && !stats.isEmpty()) {
                result.append("## 项目统计\n");
                result.append(formatStatistics(stats)).append("\n");
            }
            
            return result.toString();
        } catch (Exception e) {
            logger.error("获取项目概览失败", e);
            return "获取项目概览失败：" + e.getMessage();
        }
    }

    private String formatProjectInfo(Map<String, Object> info) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 项目名称：").append(info.getOrDefault("name", "未知")).append("\n");
        sb.append("- 项目ID：").append(info.getOrDefault("id", "未知")).append("\n");
        if (info.containsKey("describe")) {
            sb.append("- 项目描述：").append(info.get("describe")).append("\n");
        }
        if (info.containsKey("createTime")) {
            sb.append("- 创建时间：").append(info.get("createTime")).append("\n");
        }
        return sb.toString();
    }

    private String formatStatistics(Map<String, Object> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 应用总数：").append(stats.getOrDefault("appCount", 0)).append("\n");
        sb.append("- 在线应用数：").append(stats.getOrDefault("onlineAppCount", 0)).append("\n");
        sb.append("- 快照总数：").append(stats.getOrDefault("snapshotCount", 0)).append("\n");
        sb.append("- 调用链总数：").append(stats.getOrDefault("traceCount", 0)).append("\n");
        if (stats.containsKey("avgCoverage")) {
            sb.append("- 平均覆盖率：").append(String.format("%.2f%%", stats.get("avgCoverage"))).append("\n");
        }
        return sb.toString();
    }
}
