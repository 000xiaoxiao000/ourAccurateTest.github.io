package com.oAT.ai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;

/**
 * AI 大模型配置类
 * 基于 LangChain4j 框架，支持多种 LLM 提供商
 */
@Configuration
@ConfigurationProperties(prefix = "ai.llm")
public class AIConfig implements AIConfigProperties {

    private static final Logger logger = LoggerFactory.getLogger(AIConfig.class);

    /**
     * LLM 提供商类型
     */
    public enum Provider {
        AUTO,
        OLLAMA,
        OPENAI,
        DEEPSEEK,
        CUSTOM
    }

    /**
     * 是否启用 AI 大模型功能
     */
    private boolean enabled = false;

    /**
     * LLM 提供商 (auto, ollama, openai, deepseek, custom)
     */
    private String provider = "auto";

    /**
     * API 基础 URL
     */
    private String baseUrl = "http://localhost:11434";

    /**
     * API Key
     */
    private String apiKey;

    /**
     * 模型名称
     */
    private String model = "gemma4:e2b";

    /**
     * 最大 token 数
     */
    private int maxTokens = 4096;

    /**
     * 温度参数 (0-2, 值越低输出越确定)
     */
    private double temperature = 0.7;

    /**
     * 请求超时时间(秒)
     */
    private int timeout = 300;

    /**
     * 是否记录 AI HTTP 请求日志
     */
    private boolean logRequests = false;

    /**
     * 是否记录 AI HTTP 响应日志
     */
    private boolean logResponses = false;

    /**
     * 系统提示词前缀
     */
    private String systemPromptPrefix = "你是一个专业的代码覆盖率分析助手，专注于帮助用户进行链路分析、问题排查和数据洞察。";

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public boolean isLogRequests() {
        return logRequests;
    }

    public void setLogRequests(boolean logRequests) {
        this.logRequests = logRequests;
    }

    public boolean isLogResponses() {
        return logResponses;
    }

    public void setLogResponses(boolean logResponses) {
        this.logResponses = logResponses;
    }

    @Override
    public String getSystemPromptPrefix() {
        return systemPromptPrefix;
    }

    public void setSystemPromptPrefix(String systemPromptPrefix) {
        this.systemPromptPrefix = systemPromptPrefix;
    }

    /**
     * 创建 Chat Model Bean
     * 根据 provider 配置创建对应的模型实例
     */
    @Bean(name = "chatLanguageModel")
    @ConditionalOnProperty(prefix = "ai.llm", name = "enabled", havingValue = "true")
    public ChatModel chatLanguageModel() {
        Provider providerType = resolveProvider();
        logger.info("Initializing AI LLM with provider: {}, model: {}, baseUrl: {}", providerType, model, resolveApiBaseUrl(providerType));

        switch (providerType) {
            case OLLAMA:
                return createOllamaModel();
            case OPENAI:
            case DEEPSEEK:
            case CUSTOM:
            case AUTO:
            default:
                return createOpenAiCompatibleModel(providerType);
        }
    }

    private Provider parseProvider(String providerStr) {
        if (!StringUtils.hasText(providerStr)) {
            return Provider.AUTO;
        }
        try {
            return Provider.valueOf(providerStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown provider: {}, using AUTO mode", providerStr);
            return Provider.AUTO;
        }
    }

    private Provider resolveProvider() {
        Provider configuredProvider = parseProvider(this.provider);
        if (configuredProvider == Provider.AUTO) {
            return detectProviderFromConnectionSettings();
        }

        if (configuredProvider == Provider.OLLAMA && shouldUseOpenAiCompatibleMode()) {
            Provider detectedProvider = detectProviderFromConnectionSettings();
            logger.info("Provider is set to OLLAMA, but baseUrl/apiKey indicate {}. Switching automatically.", detectedProvider);
            return detectedProvider;
        }

        return configuredProvider;
    }

    private Provider detectProviderFromConnectionSettings() {
        if (looksLikeOllama(baseUrl) && !StringUtils.hasText(apiKey)) {
            return Provider.OLLAMA;
        }
        if (looksLikeDeepSeek(baseUrl)) {
            return Provider.DEEPSEEK;
        }
        if (looksLikeDashScope(baseUrl)) {
            return Provider.CUSTOM;
        }
        return StringUtils.hasText(apiKey) ? Provider.CUSTOM : Provider.OLLAMA;
    }

    /**
     * 创建 Ollama 模型
     */
    private ChatModel createOllamaModel() {
        return OllamaChatModel.builder()
                .baseUrl(normalizeOllamaBaseUrl(baseUrl))
                .modelName(model)
                .numPredict(maxTokens)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeout))
                .build();
    }

    /**
     * 创建 OpenAI 兼容模型 (OpenAI, DeepSeek, 自定义)
     */
    private ChatModel createOpenAiCompatibleModel(Provider providerType) {
        return OpenAiChatModel.builder()
                .baseUrl(resolveApiBaseUrl(providerType))
                .apiKey(StringUtils.hasText(apiKey) ? apiKey : "none")
                .modelName(model)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .parallelToolCalls(false)
                .responseFormat("json_object")
                .logRequests(logRequests)
                .logResponses(logResponses)
                .timeout(Duration.ofSeconds(timeout))
                .build();
    }

    private String resolveApiBaseUrl(Provider providerType) {
        if (providerType == Provider.DEEPSEEK) {
            return "https://api.deepseek.com/v1";
        }

        if (!StringUtils.hasText(baseUrl)) {
            return "https://api.openai.com/v1";
        }

        if (looksLikeDashScope(baseUrl)) {
            return normalizeOpenAiCompatibleBaseUrl(appendPathIfMissing(baseUrl, "/compatible-mode/v1"));
        }

        if (providerType == Provider.OPENAI) {
            return normalizeOpenAiCompatibleBaseUrl(appendPathIfMissing(baseUrl, "/v1"));
        }

        return normalizeOpenAiCompatibleBaseUrl(baseUrl);
    }

    /**
     * 标准化 Ollama 基础 URL
     */
    private String normalizeOllamaBaseUrl(String url) {
        return trimTrailingSlash(url);
    }

    /**
     * 标准化 OpenAI 兼容基础 URL
     */
    private String normalizeOpenAiCompatibleBaseUrl(String url) {
        return trimTrailingSlash(url);
    }

    private String appendPathIfMissing(String url, String suffix) {
        String normalizedUrl = trimTrailingSlash(url);
        if (!StringUtils.hasText(normalizedUrl)) {
            return normalizedUrl;
        }
        if (normalizedUrl.endsWith(suffix)) {
            return normalizedUrl;
        }
        return normalizedUrl + suffix;
    }

    private String trimTrailingSlash(String url) {
        if (!StringUtils.hasText(url)) {
            return url;
        }
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    private boolean looksLikeOllama(String url) {
        return containsIgnoreCase(url, "localhost:11434") || containsIgnoreCase(url, "127.0.0.1:11434");
    }

    private boolean looksLikeDeepSeek(String url) {
        return containsIgnoreCase(url, "deepseek.com");
    }

    private boolean looksLikeDashScope(String url) {
        return containsIgnoreCase(url, "dashscope.aliyuncs.com");
    }

    private boolean shouldUseOpenAiCompatibleMode() {
        return StringUtils.hasText(apiKey)
                || looksLikeDashScope(baseUrl)
                || looksLikeDeepSeek(baseUrl)
                || containsIgnoreCase(baseUrl, "/v1")
                || containsIgnoreCase(baseUrl, "/compatible-mode");
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return StringUtils.hasText(value)
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
