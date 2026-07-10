package com.oAT.web.service.entity;

import java.io.Serializable;
import java.util.Date;

/**
 * AI回答反馈实体
 * 用于收集用户对AI回答的反馈，支持自主学习
 */
public class AIFeedbackVo implements Serializable {
    
    /**
     * 反馈ID
     */
    private String feedbackId;
    
    /**
     * 项目ID
     */
    private String projectId;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 用户问题
     */
    private String question;
    
    /**
     * AI回答
     */
    private String answer;
    
    /**
     * 用户评分（1-5星）
     */
    private Integer rating;
    
    /**
     * 反馈类型：helpful-有帮助, not_helpful-无帮助, incorrect-不正确, incomplete-不完整
     */
    private String feedbackType;
    
    /**
     * 用户评论
     */
    private String comment;
    
    /**
     * 使用的工具列表
     */
    private String usedTools;
    
    /**
     * 回答耗时（毫秒）
     */
    private Long responseTime;
    
    /**
     * 创建时间
     */
    private Date createTime;

    public String getFeedbackId() {
        return feedbackId;
    }

    public void setFeedbackId(String feedbackId) {
        this.feedbackId = feedbackId;
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

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getFeedbackType() {
        return feedbackType;
    }

    public void setFeedbackType(String feedbackType) {
        this.feedbackType = feedbackType;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getUsedTools() {
        return usedTools;
    }

    public void setUsedTools(String usedTools) {
        this.usedTools = usedTools;
    }

    public Long getResponseTime() {
        return responseTime;
    }

    public void setResponseTime(Long responseTime) {
        this.responseTime = responseTime;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
