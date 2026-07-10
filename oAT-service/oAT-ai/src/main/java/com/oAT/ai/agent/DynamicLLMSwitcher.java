package com.oAT.ai.agent;

import com.oAT.ai.config.AIConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 动态 LLM 切换器
 * 支持运行时动态切换 LLM 提供商和模型，实现：
 * 1. 多模型配置管理
 * 2. 基于任务类型自动选择最优模型
 * 3. 模型健康检查和自动故障转移
 * 4. A/B 测试和模型对比评估
 *
 * <p>使用场景：</p>
 * <ul>
 *   <li>简单问答 → 快速小模型（如 gemma4:e2b）</li>
 *   <li>复杂分析 → 强力大模型（如 qwen2.5:72b）</li>
 *   <li>代码生成 → 专业代码模型</li>
 *   <li>长文档理解 → 大上下文窗口模型</li>
 * </ul>
 */
public class DynamicLLMSwitcher {

    private static final Logger logger = LoggerFactory.getLogger(DynamicLLMSwitcher.class);

    /** 模型配置条目 */
    public static class ModelConfig {
        private final String name;           // 模型显示名称
        private final String provider;       // ollama/openai/deepseek/custom
        private final String model;          // 模型名称
        private final String baseUrl;        // API 地址
        private final String apiKey;         // API Key
        private final int maxTokens;
        private final double temperature;
        private final int timeoutSeconds;
        private final String[] strengths;    // 擅长领域: simple/complex/code/long-context/vision
        private final int priority;          // 优先级（数字越小优先越高）
        private Object instance;  // 运行时实例

        public ModelConfig(String name, String provider, String model, String baseUrl,
                          String apiKey, int maxTokens, double temperature,
                          int timeoutSeconds, String[] strengths, int priority) {
            this.name = name;
            this.provider = provider;
            this.model = model;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.maxTokens = maxTokens;
            this.temperature = temperature;
            this.timeoutSeconds = timeoutSeconds;
            this.strengths = strengths;
            this.priority = priority;
        }

        public boolean isGoodFor(String taskType) {
            if (strengths == null || strengths.length == 0) return true;
            for (String s : strengths) {
                if (s.equalsIgnoreCase(taskType)) return true;
            }
            return false;
        }

        // Getters
        public String getName() { return name; }
        public String getProvider() { return provider; }
        public String getModel() { return model; }
        public String getBaseUrl() { return baseUrl; }
        public String getApiKey() { return apiKey; }
        public int getMaxTokens() { return maxTokens; }
        public double getTemperature() { return temperature; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public String[] getStrengths() { return strengths; }
        public int getPriority() { return priority; }
        public Object getInstance() { return instance; }
        public void setInstance(Object instance) { this.instance = instance; }
    }

    /** 任务复杂度分类 */
    public enum TaskComplexity {
        SIMPLE,      // 简单问答、状态查询
        COMPLEX,     // 数据分析、综合报告
        CODE,        // 代码生成/审查
        LONG_CONTEXT,// 长文本理解
        VISION       // 图片/多模态
    }

    /** 模型状态 */
    public enum ModelStatus {
        HEALTHY,
        DEGRADED,   // 响应慢但可用
        UNAVAILABLE // 不可用
    }

    /** 已注册的模型配置 */
    private final List<ModelConfig> models = new CopyOnWriteArrayList<>();

    /** 模型状态跟踪 */
    private final ConcurrentHashMap<String, ModelStatus> modelStatuses = new ConcurrentHashMap<>();

    /** 模型调用计数（用于负载均衡） */
    private final ConcurrentHashMap<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();

    /** 模型成功/失败计数 */
    private final ConcurrentHashMap<String, AtomicInteger> successCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> failureCounts = new ConcurrentHashMap<>();

    /** 当前活动模型索引 */
    private final AtomicInteger activeModelIndex = new AtomicInteger(0);

    /** 默认模型名称 */
    private volatile String defaultModelName;

    /** AI 配置引用 */
    private AIConfig aiConfig;

    public DynamicLLMSwitcher(AIConfig aiConfig) {
        this.aiConfig = aiConfig;
        // 从当前 AIConfig 注册默认模型作为第一个模型
        registerDefaultModel();
    }

    /**
     * 从 AIConfig 注册默认模型
     */
    private void registerDefaultModel() {
        if (aiConfig == null) return;

        ModelConfig defaultModel = new ModelConfig(
                "default",
                aiConfig.getProvider(),
                aiConfig.getModel(),
                aiConfig.getBaseUrl(),
                aiConfig.getApiKey(),
                aiConfig.getMaxTokens(),
                aiConfig.getTemperature(),
                aiConfig.getTimeout(),
                new String[]{"simple", "complex", "code", "vision"},
                1
        );
        models.add(defaultModel);
        defaultModelName = "default";
        modelStatuses.put("default", ModelStatus.HEALTHY);
        callCounts.put("default", new AtomicInteger(0));
        successCounts.put("default", new AtomicInteger(0));
        failureCounts.put("default", new AtomicInteger(0));

        logger.info("Registered default LLM model: {} ({})", aiConfig.getModel(), aiConfig.getProvider());
    }

    /**
     * 注册新的模型配置
     */
    public void registerModel(ModelConfig config) {
        models.add(config);
        modelStatuses.put(config.getName(), ModelStatus.HEALTHY);
        callCounts.put(config.getName(), new AtomicInteger(0));
        successCounts.put(config.getName(), new AtomicInteger(0));
        failureCounts.put(config.getName(), new AtomicInteger(0));
        logger.info("Registered LLM model: {} ({}/{})", config.getName(), config.getProvider(), config.getModel());
    }

    /**
     * 根据任务复杂度选择最佳模型
     */
    public ModelConfig selectBestModel(TaskComplexity complexity) {
        String taskType = complexity.name().toLowerCase();

        // 1. 找到适合该任务的且健康的模型
        ModelConfig best = null;
        int bestScore = Integer.MAX_VALUE;

        for (ModelConfig model : models) {
            ModelStatus status = modelStatuses.getOrDefault(model.getName(), ModelStatus.HEALTHY);
            if (status == ModelStatus.UNAVAILABLE) continue;

            int score = model.getPriority();
            if (!model.isGoodFor(taskType)) {
                score += 100; // 不适合的模型降低优先级
            }
            if (status == ModelStatus.DEGRADED) {
                score += 50; // 降级的模型进一步降低优先级
            }

            if (score < bestScore) {
                bestScore = score;
                best = model;
            }
        }

        if (best != null) {
            callCounts.get(best.getName()).incrementAndGet();
            logger.debug("Selected model '{}' for task '{}'", best.getName(), taskType);
            return best;
        }

        // Fallback: 返回任何健康模型
        for (ModelConfig model : models) {
            if (modelStatuses.getOrDefault(model.getName(), ModelStatus.HEALTHY) != ModelStatus.UNAVAILABLE) {
                return model;
            }
        }

        logger.warn("No healthy model available!");
        return models.isEmpty() ? null : models.get(0);
    }

    /**
     * 根据问题内容推断任务复杂度
     */
    public TaskComplexity inferComplexity(String question) {
        if (question == null) return TaskComplexity.SIMPLE;

        // 包含图片标记
        if (question.contains("data:image") || question.contains("[图片提问]")) {
            return TaskComplexity.VISION;
        }

        // 复杂分析关键词
        String[] complexKeywords = {
                "分析", "报告", "对比", "趋势", "优化建议",
                "为什么", "如何提高", "综合", "全面", "深入",
                "analyze", "report", "compare", "trend", "optimization"
        };
        for (String kw : complexKeywords) {
            if (question.toLowerCase().contains(kw.toLowerCase())) {
                return TaskComplexity.COMPLEX;
            }
        }

        // 代码相关
        String[] codeKeywords = {"代码", "函数", "方法", "类", "接口", "code", "function", "method"};
        for (String kw : codeKeywords) {
            if (question.toLowerCase().contains(kw.toLowerCase())) {
                return TaskComplexity.CODE;
            }
        }

        // 长文本
        if (question.length() > 200) {
            return TaskComplexity.LONG_CONTEXT;
        }

        return TaskComplexity.SIMPLE;
    }

    /**
     * 记录模型调用结果（用于健康检查）
     */
    public void recordResult(String modelName, boolean success, long responseTimeMs) {
        if (modelName == null) return;

        if (success) {
            successCounts.get(modelName).incrementAndGet();
            modelStatuses.put(modelName, ModelStatus.HEALTHY);
        } else {
            failureCounts.get(modelName).incrementAndGet();
            int failures = failureCounts.get(modelName).get();

            // 连续失败3次标记为不可用
            if (failures >= 3) {
                modelStatuses.put(modelName, ModelStatus.UNAVAILABLE);
                logger.warn("Model '{}' marked as UNAVAILABLE after {} failures", modelName, failures);
            } else if (failures >= 1) {
                modelStatuses.put(modelName, ModelStatus.DEGRADED);
            }
        }
    }

    /**
     * 重置模型状态（手动恢复或定时恢复检查）
     */
    public void resetModelStatus(String modelName) {
        modelStatuses.put(modelName, ModelStatus.HEALTHY);
        if (failureCounts.containsKey(modelName)) {
            failureCounts.get(modelName).set(0);
        }
        logger.info("Model '{}' status reset to HEALTHY", modelName);
    }

    /**
     * 获取当前活动模型实例
     * 如果有自定义实例则使用，否则返回 null（由外部创建）
     */
    public Object getActiveModelInstance() {
        if (activeModelIndex.get() < models.size()) {
            return models.get(activeModelIndex.get()).getInstance();
        }
        return null;
    }

    /**
     * 设置活动模型
     */
    public void setActiveModel(String modelName) {
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).getName().equals(modelName)) {
                activeModelIndex.set(i);
                defaultModelName = modelName;
                logger.info("Switched active model to: {}", modelName);
                return;
            }
        }
        logger.warn("Model '{}' not found, cannot switch", modelName);
    }

    // ==================== 统计信息 ====================

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.HashMap<>();
        List<Map<String, Object>> modelStats = new java.util.ArrayList<>();

        for (ModelConfig model : models) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("name", model.getName());
            m.put("provider", model.getProvider());
            m.put("model", model.getModel());
            m.put("status", modelStatuses.getOrDefault(model.getName(), ModelStatus.HEALTHY).name());
            m.put("calls", callCounts.getOrDefault(model.getName(), new AtomicInteger(0)).get());
            m.put("successes", successCounts.getOrDefault(model.getName(), new AtomicInteger(0)).get());
            m.put("failures", failureCounts.getOrDefault(model.getName(), new AtomicInteger(0)).get());
            m.put("priority", model.getPriority());
            modelStats.add(m);
        }

        stats.put("models", modelStats);
        stats.put("activeModel", defaultModelName);
        stats.put("totalModels", models.size());

        return stats;
    }

    public List<ModelConfig> getRegisteredModels() { return new java.util.ArrayList<>(models); }
    public String getDefaultModelName() { return defaultModelName; }
}
