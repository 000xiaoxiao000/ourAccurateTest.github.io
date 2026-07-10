package com.oAT.ai.config;

/**
 * AI 配置属性接口
 * 提供获取 AI 配置的方法
 */
public interface AIConfigProperties {

    /**
     * 是否启用 AI 大模型功能
     */
    boolean isEnabled();

    /**
     * 获取系统提示词前缀
     */
    String getSystemPromptPrefix();

    /**
     * 获取 LLM 提供商
     */
    String getProvider();

    /**
     * 获取 API 基础 URL
     */
    String getBaseUrl();

    /**
     * 获取模型名称
     */
    String getModel();
}
