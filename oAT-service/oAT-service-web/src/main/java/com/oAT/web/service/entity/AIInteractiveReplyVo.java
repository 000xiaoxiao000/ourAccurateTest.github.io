package com.oAT.web.service.entity;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class AIInteractiveReplyVo implements Serializable {
    private String question;
    private String answer;
    private String topic;
    private List<String> suggestions;
    private List<AIQuickLinkVo> quickLinks;
    private List<AIActionVo> actions;

    /**
     * 数据可视化建议（前端可根据此字段渲染图表）
     * 例如: {"type": "bar", "title": "代码风险趋势", "data": {...}}
     */
    private List<Map<String, Object>> visualizationSuggestions;

    /**
     * AI回答的置信度 (0-1之间)
     */
    private Double confidence;

    /**
     * 使用的工具列表（用于调试和统计）
     */
    private List<String> usedTools;

    /**
     * 是否需要加载更多数据
     */
    private Boolean needMoreData;

    /**
     * 额外的元数据
     */
    private Map<String, Object> metadata;

    /**
     * 服务端持久化后的会话状态，供前端重启后恢复显示
     */
    private String sessionState;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    public List<AIQuickLinkVo> getQuickLinks() {
        return quickLinks;
    }

    public void setQuickLinks(List<AIQuickLinkVo> quickLinks) {
        this.quickLinks = quickLinks;
    }

    public List<AIActionVo> getActions() {
        return actions;
    }

    public void setActions(List<AIActionVo> actions) {
        this.actions = actions;
    }

    public List<Map<String, Object>> getVisualizationSuggestions() {
        return visualizationSuggestions;
    }

    public void setVisualizationSuggestions(List<Map<String, Object>> visualizationSuggestions) {
        this.visualizationSuggestions = visualizationSuggestions;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public List<String> getUsedTools() {
        return usedTools;
    }

    public void setUsedTools(List<String> usedTools) {
        this.usedTools = usedTools;
    }

    public Boolean getNeedMoreData() {
        return needMoreData;
    }

    public void setNeedMoreData(Boolean needMoreData) {
        this.needMoreData = needMoreData;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public String getSessionState() {
        return sessionState;
    }

    public void setSessionState(String sessionState) {
        this.sessionState = sessionState;
    }
}
