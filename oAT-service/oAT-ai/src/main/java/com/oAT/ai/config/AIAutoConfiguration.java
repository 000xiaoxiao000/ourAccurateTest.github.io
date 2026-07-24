package com.oAT.ai.config;

import com.oAT.ai.service.impl.LLMServiceImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Import;

/**
 * AI 模块自动配置
 * 仅在 LangChain4j 类存在时激活 (JDK 21+ 环境)
 */
@AutoConfiguration
@ConditionalOnClass(name = {"dev.langchain4j.model.openai.OpenAiChatModel", "dev.langchain4j.model.ollama.OllamaChatModel"})
@Import({AIConfig.class, LLMServiceImpl.class})
public class AIAutoConfiguration {
}
