package com.oAT.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 增强能力配置
 */
@Configuration
@ConfigurationProperties(prefix = "ai.enhanced")
public class AIEnhancedConfig {

    private final SemanticCache semanticCache = new SemanticCache();
    private final Conversation conversation = new Conversation();
    private final SelfLearning selfLearning = new SelfLearning();
    private final Feedback feedback = new Feedback();

    public SemanticCache getSemanticCache() {
        return semanticCache;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public SelfLearning getSelfLearning() {
        return selfLearning;
    }

    public Feedback getFeedback() {
        return feedback;
    }

    public static class SemanticCache {
        private boolean enabled = true;
        private double threshold = 0.85;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getThreshold() {
            return threshold;
        }

        public void setThreshold(double threshold) {
            this.threshold = threshold;
        }
    }

    public static class Conversation {
        private int maxRounds = 20;

        public int getMaxRounds() {
            return maxRounds;
        }

        public void setMaxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
        }
    }

    public static class SelfLearning {
        private boolean enabled = true;
        private int intervalHours = 6;
        private boolean knowledgeHitEnabled = true;
        private double knowledgeHitThreshold = 0.7;
        private boolean dynamicGuideEnabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getIntervalHours() {
            return intervalHours;
        }

        public void setIntervalHours(int intervalHours) {
            this.intervalHours = intervalHours;
        }

        public boolean isKnowledgeHitEnabled() {
            return knowledgeHitEnabled;
        }

        public void setKnowledgeHitEnabled(boolean knowledgeHitEnabled) {
            this.knowledgeHitEnabled = knowledgeHitEnabled;
        }

        public double getKnowledgeHitThreshold() {
            return knowledgeHitThreshold;
        }

        public void setKnowledgeHitThreshold(double knowledgeHitThreshold) {
            this.knowledgeHitThreshold = knowledgeHitThreshold;
        }

        public boolean isDynamicGuideEnabled() {
            return dynamicGuideEnabled;
        }

        public void setDynamicGuideEnabled(boolean dynamicGuideEnabled) {
            this.dynamicGuideEnabled = dynamicGuideEnabled;
        }
    }

    public static class Feedback {
        private int retentionDays = 30;

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }
    }
}
