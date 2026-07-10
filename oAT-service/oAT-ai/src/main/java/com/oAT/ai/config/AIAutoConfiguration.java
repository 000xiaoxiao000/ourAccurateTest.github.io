package com.oAT.ai.config;

import com.oAT.ai.agent.AIAgentService;
import com.oAT.ai.agent.FeedbackPersistenceService;
import com.oAT.ai.config.AIEnhancedConfig;
import com.oAT.ai.service.impl.LLMServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * AI 模块自动配置
 * 仅在 LangChain4j 类存在时激活 (JDK 17+ 环境)
 */
@Configuration
@ConditionalOnClass(name = {"dev.langchain4j.model.openai.OpenAiChatModel", "dev.langchain4j.model.ollama.OllamaChatModel"})
@Import({AIConfig.class, AIEnhancedConfig.class, LLMServiceImpl.class, AIAgentService.class})
public class AIAutoConfiguration {

    @Bean
    public FeedbackPersistenceService feedbackPersistenceService(
            @Value("${oat.data.path:${user.home}/oAT/codeData}") String oatDataPath,
            AIEnhancedConfig enhancedConfig) {
        FeedbackPersistenceService.setDefaultRetentionDays(enhancedConfig.getFeedback().getRetentionDays());
        System.setProperty("ai.enhanced.self-learning.interval-hours",
                String.valueOf(enhancedConfig.getSelfLearning().getIntervalHours()));
        System.setProperty("ai.enhanced.self-learning.knowledge-hit-threshold",
                String.valueOf(enhancedConfig.getSelfLearning().getKnowledgeHitThreshold()));
        return new FeedbackPersistenceService(oatDataPath);
    }
}
