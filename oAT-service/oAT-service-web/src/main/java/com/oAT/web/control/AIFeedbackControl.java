package com.oAT.web.control;

import com.oAT.ai.agent.AIAgentService;
import com.oAT.ai.agent.ToolRecommender;
import com.oAT.agent.AISelfLearningService;
import com.oAT.ai.agent.FeedbackPersistenceService;
import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.service.entity.AIFeedbackVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI反馈控制器
 * 收集和处理用户对AI回答的反馈
 */
@Controller
@RequestMapping("/api/ai/feedback")
public class AIFeedbackControl {

    private static final Logger logger = LoggerFactory.getLogger(AIFeedbackControl.class);

    /** 持久化服务 */
    @Autowired
    private FeedbackPersistenceService feedbackPersistence;

    /** AI Agent 服务（用于触发自主学习） */
    @Autowired(required = false)
    private AIAgentService aiAgentService;

    @Value("${oat.data.path:${user.home}/oAT/codeData}")
    private String oatDataPath;

    /**
     * 提交反馈
     */
    @PostMapping("/submit")
    @ResponseBody
    public ResultNotified<String> submitFeedback(@RequestBody AIFeedbackVo feedback) {
        try {
            // 构建持久化记录
            FeedbackPersistenceService.FeedbackRecord record = new FeedbackPersistenceService.FeedbackRecord();
            record.setProjectId(feedback.getProjectId());
            record.setUserId(feedback.getUserId());
            record.setQuestion(feedback.getQuestion());
            record.setAnswer(feedback.getAnswer());
            record.setRating(feedback.getRating());
            record.setFeedbackType(feedback.getFeedbackType());
            record.setComment(feedback.getComment());
            record.setUsedTools(feedback.getUsedTools());
            record.setResponseTime(feedback.getResponseTime());
            if (aiAgentService != null && aiAgentService.getToolRecommender() != null && record.getQuestion() != null) {
                ToolRecommender.Recommendation recommendation = aiAgentService.getToolRecommender().recommend(record.getQuestion());
                if (recommendation != null && recommendation.detectedIntent != null) {
                    record.setTopic(recommendation.detectedIntent);
                }
            }

            // 持久化到文件
            record = feedbackPersistence.submit(record);

            // 触发自主学习（异步）
            if (aiAgentService != null) {
                try {
                    AISelfLearningService selfLearning = aiAgentService.getSelfLearningService();
                    if (selfLearning != null) {
                        selfLearning.onNewFeedback(record);
                    }
                } catch (Exception e) {
                    logger.warn("Self-learning trigger failed (non-critical): {}", e.getMessage());
                }
            }

            // 同时保留内存映射用于快速查询（兼容性）

            logger.info("Feedback persisted: id={}, type={}, rating={}",
                record.getFeedbackId(), feedback.getFeedbackType(), feedback.getRating());

            return new ResultNotified<>(true, "感谢您的反馈！反馈ID: " + record.getFeedbackId());
        } catch (Exception e) {
            logger.error("Failed to submit feedback", e);
            return new ResultNotified<>(false, "提交反馈失败：" + e.getMessage());
        }
    }

    /**
     * 快速评分（点赞/点踩）
     */
    @PostMapping("/rate")
    @ResponseBody
    public ResultNotified<String> quickRate(@RequestParam String feedbackId,
                                           @RequestParam boolean helpful) {
        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("rating", helpful ? 5 : 1);
            updates.put("feedbackType", helpful ? "helpful" : "not_helpful");

            boolean updated = feedbackPersistence.update(feedbackId, updates);
            if (!updated) {
                return new ResultNotified<>(false, "反馈记录不存在");
            }

            if (aiAgentService != null) {
                try {
                    FeedbackPersistenceService.FeedbackRecord record = feedbackPersistence.get(feedbackId);
                    AISelfLearningService selfLearning = aiAgentService.getSelfLearningService();
                    if (selfLearning != null && record != null) {
                        selfLearning.onNewFeedback(record);
                    }
                } catch (Exception e) {
                    logger.warn("Self-learning trigger on quick rate failed (non-critical): {}", e.getMessage());
                }
            }

            logger.info("Quick rate: id={}, helpful={}", feedbackId, helpful);

            return new ResultNotified<>(true, "感谢评分！");
        } catch (Exception e) {
            logger.error("Failed to rate feedback", e);
            return new ResultNotified<>(false, "评分失败：" + e.getMessage());
        }
    }

    /**
     * 获取反馈统计（使用持久化数据）
     */
    @GetMapping("/stats")
    @ResponseBody
    public ResultNotified<?> getFeedbackStats(@RequestParam(required = false) String projectId) {
        try {
            Map<String, Object> stats = feedbackPersistence.getStats(projectId);

            // 追加自主学习状态（如果有）
            if (aiAgentService != null) {
                try {
                    AISelfLearningService sl = aiAgentService.getSelfLearningService();
                    if (sl != null) {
                        stats.put("selfLearning", sl.getStatus());
                    }
                } catch (Exception ignored) {}
            }

            return new ResultNotified<>(true, "获取反馈统计成功", (Serializable) stats);
        } catch (Exception e) {
            logger.error("Failed to get feedback stats", e);
            return new ResultNotified<>(false, "获取统计失败：" + e.getMessage());
        }
    }

    /**
     * 获取用户的反馈历史（使用持久化数据）
     */
    @GetMapping("/my")
    @ResponseBody
    public ResultNotified<?> getMyFeedback(@RequestParam String userId) {
        try {
            List<FeedbackPersistenceService.FeedbackRecord> myFeedbacks =
                    feedbackPersistence.getUserFeedbacks(userId, 50);

            // 转换为前端友好的格式
            List<Map<String, Object>> result = new ArrayList<>();
            for (FeedbackPersistenceService.FeedbackRecord fb : myFeedbacks) {
                Map<String, Object> item = new HashMap<>();
                item.put("feedbackId", fb.getFeedbackId());
                item.put("question", fb.getQuestion());
                item.put("rating", fb.getRating());
                item.put("feedbackType", fb.getFeedbackType());
                item.put("topic", fb.getTopic());
                item.put("createTime", fb.getCreateTime());
                item.put("responseTime", fb.getResponseTime());
                item.put("usedTools", fb.getUsedTools());
                result.add(item);
            }

            return new ResultNotified<>(true, "获取反馈历史成功", (Serializable) (Serializable) result);
        } catch (Exception e) {
            logger.error("Failed to get user feedback", e);
            return new ResultNotified<>(false, "获取反馈历史失败：" + e.getMessage());
        }
    }

    /**
     * 获取 AI 自主学习报告
     */
    @GetMapping("/learning-report")
    @ResponseBody
    public ResultNotified<?> getLearningReport() {
        try {
            if (aiAgentService == null) {
                return new ResultNotified<>(false, "AI服务不可用");
            }
            AISelfLearningService selfLearning = aiAgentService.getSelfLearningService();
            if (selfLearning == null) {
                return new ResultNotified<>(false, "自主学习服务未初始化");
            }

            AISelfLearningService.LearningReport report = selfLearning.runLearningCycle();

            return new ResultNotified<>(true, "学习报告已刷新", (Serializable) report);
        } catch (Exception e) {
            logger.error("Failed to generate learning report", e);
            return new ResultNotified<>(false, "生成学习报告失败：" + e.getMessage());
        }
    }

    /**
     * 清空 AI 自主学习优化建议（不删除反馈记录）
     */
    @RequestMapping(value = "/learning-suggestions/clear", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public ResultNotified<?> clearLearningSuggestions() {
        try {
            if (aiAgentService == null) {
                return new ResultNotified<>(false, "AI服务不可用");
            }
            AISelfLearningService selfLearning = aiAgentService.getSelfLearningService();
            if (selfLearning == null) {
                return new ResultNotified<>(false, "自主学习服务未初始化");
            }
            int cleared = selfLearning.clearSuggestions();
            Map<String, Object> result = new HashMap<>();
            result.put("cleared", cleared);
            return new ResultNotified<>(true, "优化建议已清空", (Serializable) result);
        } catch (Exception e) {
            logger.error("Failed to clear learning suggestions", e);
            return new ResultNotified<>(false, "清空优化建议失败：" + e.getMessage());
        }
    }

    /**
     * 获取 AI 服务增强状态
     */
    @GetMapping("/ai-status")
    @ResponseBody
    public ResultNotified<?> getAIStatus() {
        try {
            Map<String, Object> status = new HashMap<>();
            status.put("timestamp", System.currentTimeMillis());

            if (aiAgentService != null && aiAgentService.isAvailable()) {
                status.put("available", true);
                status.put("enhancedStats", aiAgentService.getEnhancedStats());
                status.put("recentFallbackReports", aiAgentService.getRecentFallbackReports(10));
            } else {
                status.put("available", false);
            }

            // 本地缓存状态
            status.put("feedbackStorage", "persistent");
            status.put("feedbackCount", feedbackPersistence.getStats(null).getOrDefault("total", 0));

            return new ResultNotified<>(true, "获取AI状态成功", (Serializable) status);
        } catch (Exception e) {
            return new ResultNotified<>(false, "获取AI状态失败: " + e.getMessage());
        }
    }
}
