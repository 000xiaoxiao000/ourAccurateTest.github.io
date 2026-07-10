package com.oAT.ai.service.impl;

import com.oAT.ai.config.AIConfig;
import com.oAT.ai.service.LLMService;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * LLM 大模型服务实现类
 * 基于 LangChain4j 框架，支持多种 LLM 提供商
 */
@Service
public class LLMServiceImpl implements LLMService {

    private static final Logger logger = LoggerFactory.getLogger(LLMServiceImpl.class);

    @Autowired
    private AIConfig aiConfig;

    @Autowired
    @Qualifier("chatLanguageModel")
    private ObjectProvider<ChatModel> chatLanguageModelProvider;

    @Override
    public String chat(String systemPrompt, String userMessage) {
        if (!isAvailable()) {
            logger.warn("LLM service is not available, please check configuration");
            return null;
        }

        logger.warn("LLM direct chat is temporarily disabled for LangChain4j 1.12.2 compatibility migration");
        return null;
    }

    @Override
    public boolean isAvailable() {
        return aiConfig.isEnabled() && resolveChatLanguageModel() != null;
    }

    private ChatModel resolveChatLanguageModel() {
        return chatLanguageModelProvider == null ? null : chatLanguageModelProvider.getIfAvailable();
    }
}
