package com.oAT.ai.service;

/**
 * LLM 大模型服务接口
 * 提供与大模型交互的统一接口
 */
public interface LLMService {

    /**
     * 发送聊天请求到大模型
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @return 大模型返回的回复内容
     */
    String chat(String systemPrompt, String userMessage);

    /**
     * 检查 LLM 服务是否可用
     *
     * @return true 表示已配置且可用
     */
    boolean isAvailable();
}
