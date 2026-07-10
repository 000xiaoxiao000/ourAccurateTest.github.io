package com.oAT.ai.agent;

import java.io.Serializable;

/**
 * AI Agent 会话上下文
 * 保存当前会话的关键信息，用于工具查询时获取上下文
 */
public class AgentContext implements Serializable {

    private static final ThreadLocal<AgentContext> CONTEXT_HOLDER = new ThreadLocal<>();

    /**
     * 项目ID
     */
    private String projectId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 用户名称
     */
    private String userName;

    /**
     * 页面上下文（用户当前所在页面的信息）
     * 例如: "覆盖率报告页面", "调用链监控页面", "应用详情页面"
     */
    private String pageContext;

    /**
     * 对话记忆作用域，用于隔离不同 AI 入口的上下文记忆。
     */
    private String memoryScope;

    /**
     * 当前页面数据摘要（用于增强AI理解）
     * 例如: "当前查看的是user-service应用的覆盖率报告，行覆盖率75%"
     */
    private String pageDataSummary;

    /**
     * 用户选中的应用或实体ID
     */
    private String selectedEntityId;

    /**
     * 用户选中的应用或实体名称
     */
    private String selectedEntityName;

    public AgentContext() {
    }

    public AgentContext(String projectId, String userId, String userName) {
        this.projectId = projectId;
        this.userId = userId;
        this.userName = userName;
    }

    /**
     * 设置当前线程的上下文
     */
    public static void setContext(AgentContext context) {
        CONTEXT_HOLDER.set(context);
    }

    /**
     * 获取当前线程的上下文
     */
    public static AgentContext getContext() {
        return CONTEXT_HOLDER.get();
    }

    /**
     * 清除当前线程的上下文
     */
    public static void clearContext() {
        CONTEXT_HOLDER.remove();
    }

    /**
     * 获取当前项目ID
     */
    public static String getCurrentProjectId() {
        AgentContext context = getContext();
        return context != null ? context.getProjectId() : null;
    }

    /**
     * 获取当前用户ID
     */
    public static String getCurrentUserId() {
        AgentContext context = getContext();
        return context != null ? context.getUserId() : null;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPageContext() {
        return pageContext;
    }

    public void setPageContext(String pageContext) {
        this.pageContext = pageContext;
    }

    public String getMemoryScope() {
        return memoryScope;
    }

    public void setMemoryScope(String memoryScope) {
        this.memoryScope = memoryScope;
    }

    public String getPageDataSummary() {
        return pageDataSummary;
    }

    public void setPageDataSummary(String pageDataSummary) {
        this.pageDataSummary = pageDataSummary;
    }

    public String getSelectedEntityId() {
        return selectedEntityId;
    }

    public void setSelectedEntityId(String selectedEntityId) {
        this.selectedEntityId = selectedEntityId;
    }

    public String getSelectedEntityName() {
        return selectedEntityName;
    }

    public void setSelectedEntityName(String selectedEntityName) {
        this.selectedEntityName = selectedEntityName;
    }

    @Override
    public String toString() {
        return "AgentContext{" +
                "projectId='" + projectId + '\'' +
                ", userId='" + userId + '\'' +
                ", userName='" + userName + '\'' +
                ", pageContext='" + pageContext + '\'' +
                ", memoryScope='" + memoryScope + '\'' +
                ", pageDataSummary='" + pageDataSummary + '\'' +
                ", selectedEntityId='" + selectedEntityId + '\'' +
                ", selectedEntityName='" + selectedEntityName + '\'' +
                '}';
    }
}
