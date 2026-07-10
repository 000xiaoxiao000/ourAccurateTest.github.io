package com.oAT.agent;

import com.oAT.ai.agent.FeedbackPersistenceService;
import com.oAT.ai.agent.ToolRecommender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AI 自主学习服务
 * 通过分析用户反馈数据持续优化 AI 的回答质量
 *
 * <p>学习维度：</p>
 * <ol>
 *   <li><strong>回答质量评估</strong>：基于评分/点赞/踩识别高质量回答模式</li>
 *   <li><strong>问题-回答对库</strong>：积累优质 Q&A 对，用于语义缓存命中</li>
 *   <li><strong>系统提示词优化</strong>：根据高频错误类型自动调整提示词</li>
 *   <li><strong>工具调用效率</strong>：识别低效工具链路并推荐更优方案</li>
 *   <li><strong>知识缺口发现</strong>：找出用户常问但AI回答不佳的领域</li>
 * </ol>
 */
public class AISelfLearningService {

    private static final Logger logger = LoggerFactory.getLogger(AISelfLearningService.class);

    /** 学习报告周期（小时） */
    private final int learningReportIntervalHours;

    /** 知识库相似度阈值 */
    private final double knowledgeHitThreshold;

    /** 知识库条目最大数量 */
    private static final int MAX_KNOWLEDGE_ENTRIES = 200;

    /** 优质回答模板库：问题模式 → 推荐的回答结构 */
    private final ConcurrentHashMap<String, KnowledgeEntry> knowledgeBase = new ConcurrentHashMap<>();

    /** 问题分类统计：topic → 统计信息 */
    private final ConcurrentHashMap<TopicKey, TopicStats> topicStatsMap = new ConcurrentHashMap<>();

    /** 常见失败模式 */
    private final ConcurrentHashMap<String, Integer> failurePatterns = new ConcurrentHashMap<>();

    /** 优化建议队列 */
    private final List<OptimizationSuggestion> suggestions = Collections.synchronizedList(new ArrayList<>());

    /** 已被用户清空的建议 ID：同一批反馈下刷新报告不再重新生成 */
    private final Set<String> clearedSuggestionIds = ConcurrentHashMap.newKeySet();

    /** 反馈版本号：收到新反馈后解除清空抑制 */
    private volatile long feedbackRevision = 0;

    /** 最近一次清空建议时的反馈版本号 */
    private volatile long suggestionsClearedRevision = -1;

    /** 定时任务调度器 */
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "ai-self-learning");
                t.setDaemon(true);
                return t;
            });

    /** 动态话题约束：topic@project → 追加提示 */
    private final ConcurrentHashMap<TopicKey, String> topicGuidanceMap = new ConcurrentHashMap<>();

    /** 数据源 */
    private final FeedbackPersistenceService feedbackPersistence;

    /** 工具推荐器引用（用于学习工具调用模式） */
    private ToolRecommender toolRecommender;

    public AISelfLearningService(FeedbackPersistenceService feedbackPersistence,
                                 int learningReportIntervalHours,
                                 double knowledgeHitThreshold) {
        this.feedbackPersistence = feedbackPersistence;
        this.learningReportIntervalHours = learningReportIntervalHours;
        this.knowledgeHitThreshold = Math.max(0.5, Math.min(0.95, knowledgeHitThreshold));
        bootstrapFromHistoricalFeedback();
        startPeriodicLearning();
        logger.info("AISelfLearningService initialized, intervalHours={}, knowledgeHitThreshold={}",
                learningReportIntervalHours, this.knowledgeHitThreshold);
    }

    public void setToolRecommender(ToolRecommender recommender) {
        this.toolRecommender = recommender;
    }

    /**
     * 处理新的反馈数据（实时学习入口）
     */
    public void onNewFeedback(FeedbackPersistenceService.FeedbackRecord record) {
        if (record == null || record.getQuestion() == null) return;

        feedbackRevision++;
        clearedSuggestionIds.clear();

        if (record.getTopic() == null || record.getTopic().trim().isEmpty()) {
            inferTopic(record);
        }

        // 1. 如果是好评，加入知识库
        if (isPositiveFeedback(record)) {
            addToKnowledgeBase(record);
        }

        // 2. 更新话题统计
        updateTopicStats(record);

        // 3. 分析负面反馈的模式
        if (isNegativeFeedback(record)) {
            analyzeFailurePattern(record);
        }

        // 4. 根据反馈调整工具推荐权重
        applyFeedbackToToolRecommender(record);
    }

    /**
     * 启动时从历史反馈中恢复学习状态
     */
    private void bootstrapFromHistoricalFeedback() {
        try {
            FeedbackPersistenceService.SelfLearningDataset dataset = feedbackPersistence.getLearningDataset();
            learnFromPositives(dataset.positiveSamples);
            learnFromNegatives(dataset.negativeSamples);
            for (FeedbackPersistenceService.FeedbackRecord record : feedbackPersistence.getAllRecords()) {
                if (record.getTopic() == null || record.getTopic().trim().isEmpty()) {
                    inferTopic(record);
                }
                updateTopicStats(record);
            }
            learnToolWeightsFromRecords(feedbackPersistence.getAllRecords());
            generateOptimizationSuggestions();
            refreshTopicGuidance();
            logger.info("Bootstrapped self-learning from {} historical feedback records",
                    feedbackPersistence.getAllRecords().size());
        } catch (Exception e) {
            logger.warn("Failed to bootstrap self-learning from historical feedback: {}", e.getMessage());
        }
    }

    /**
     * 根据用户反馈更新工具推荐权重
     */
    public void applyFeedbackToToolRecommender(FeedbackPersistenceService.FeedbackRecord record) {
        if (toolRecommender == null || record == null) {
            return;
        }
        boolean positive = isPositiveFeedback(record);
        boolean negative = isNegativeFeedback(record);
        if (!positive && !negative) {
            return;
        }

        List<String> tools = parseUsedTools(record.getUsedTools());
        if (tools.isEmpty() && record.getTopic() != null) {
            ToolRecommender.Recommendation recommendation = toolRecommender.recommend(record.getQuestion());
            if (recommendation != null && recommendation.primaryTool != null) {
                tools = Collections.singletonList(recommendation.primaryTool);
            }
        }

        for (String toolName : tools) {
            toolRecommender.recordFeedback(toolName, positive);
        }
    }

    /**
     * 构建动态学习约束，注入到 LLM 上下文
     */
    public String buildDynamicLearningGuide(String question, String projectId) {
        if (question == null || question.trim().isEmpty()) {
            return null;
        }

        StringBuilder guide = new StringBuilder();
        String pattern = extractPattern(question);

        Integer failureCount = failurePatterns.get(pattern);
        if (failureCount != null && failureCount >= 2) {
            guide.append("- 类似问题曾收到 ")
                    .append(failureCount)
                    .append(" 条负面反馈：必须优先调用最相关工具获取实时数据，直接回答用户问题，禁止泛泛建议或编造数据。\n");
        }

        for (Map.Entry<TopicKey, TopicStats> entry : topicStatsMap.entrySet()) {
            TopicKey key = entry.getKey();
            if (projectId != null && key.projectId != null && !projectId.equals(key.projectId)) {
                continue;
            }
            TopicStats stats = entry.getValue();
            if (stats.totalFeedbacks < 3 || stats.satisfactionRate() >= 0.6) {
                continue;
            }
            if (!questionMatchesTopic(question, key.topic)) {
                continue;
            }
            String topicGuide = topicGuidanceMap.get(key);
            if (topicGuide != null && !topicGuide.isEmpty()) {
                guide.append("- ").append(topicGuide).append("\n");
            } else {
                guide.append("- 主题「")
                        .append(key.topic)
                        .append("」历史满意度仅 ")
                        .append(String.format("%.0f%%", stats.satisfactionRate() * 100))
                        .append("：请基于工具返回的数据给出具体结论，不要改答成通用提升建议。\n");
            }
        }

        for (OptimizationSuggestion suggestion : getSuggestions()) {
            if (suggestion.priority != OptimizationSuggestion.Priority.HIGH) {
                continue;
            }
            if (questionMatchesSuggestion(question, suggestion)) {
                guide.append("- ").append(suggestion.description.replace('\n', ' ')).append("\n");
            }
        }

        if (guide.length() == 0) {
            return null;
        }
        return "[AI自主学习约束]\n" + guide;
    }

    /**
     * 执行一轮完整学习
     */
    public LearningReport runLearningCycle() {
        logger.info("--- Starting learning cycle ---");

        FeedbackPersistenceService.SelfLearningDataset dataset = feedbackPersistence.getLearningDataset();

        // 重建派生统计，避免每次刷新报告时重复累计历史反馈
        failurePatterns.clear();
        topicStatsMap.clear();
        for (FeedbackPersistenceService.FeedbackRecord record : feedbackPersistence.getAllRecords()) {
            if (record.getTopic() == null || record.getTopic().trim().isEmpty()) {
                inferTopic(record);
            }
            updateTopicStats(record);
        }

        // 1. 从正面样本中提炼知识
        learnFromPositives(dataset.positiveSamples);

        // 2. 从负面样本中发现改进点
        learnFromNegatives(dataset.negativeSamples);

        // 3. 生成优化建议
        generateOptimizationSuggestions();

        // 4. 刷新动态话题约束
        refreshTopicGuidance();

        LearningReport report = buildReport();
        logger.info("--- Learning cycle completed: {} knowledge entries, {} suggestions ---",
                knowledgeBase.size(), suggestions.size());

        return report;
    }

    /**
     * 获取当前学习报告，不触发新一轮学习，供前端刷新面板使用。
     */
    public LearningReport getCurrentReport() {
        return buildReport();
    }

    private void learnToolWeightsFromRecords(List<FeedbackPersistenceService.FeedbackRecord> records) {
        if (toolRecommender == null || records == null) {
            return;
        }
        for (FeedbackPersistenceService.FeedbackRecord record : records) {
            applyFeedbackToToolRecommender(record);
        }
    }

    /**
     * 检查是否有相似的历史优质回答
     *
     * @return 匹配到的优质回答；未匹配返回 null
     */
    public String findBestPracticeAnswer(String question) {
        if (question == null || knowledgeBase.isEmpty()) return null;

        String normalizedQ = normalizeQuestion(question);
        double bestScore = knowledgeHitThreshold;
        String bestAnswer = null;

        for (KnowledgeEntry entry : knowledgeBase.values()) {
            double score = computeSimilarity(normalizedQ, entry.normalizedQuestionPattern);
            if (score > bestScore) {
                bestScore = score;
                bestAnswer = entry.recommendedAnswerTemplate;
            }
        }

        if (bestAnswer != null) {
            logger.debug("Knowledge base hit for question (score={}): {}", bestScore,
                    question.length() > 50 ? question.substring(0, 50) + "..." : question);
        }

        return bestAnswer;
    }

    /**
     * 获取当前所有优化建议
     */
    public List<OptimizationSuggestion> getSuggestions() {
        synchronized (suggestions) {
            return new ArrayList<>(suggestions);
        }
    }

    /**
     * 清空当前优化建议缓存，不删除用户反馈与学习统计。
     */
    public int clearSuggestions() {
        synchronized (suggestions) {
            int count = suggestions.size();
            clearedSuggestionIds.clear();
            for (OptimizationSuggestion suggestion : suggestions) {
                if (suggestion != null && suggestion.id != null) {
                    clearedSuggestionIds.add(suggestion.id);
                }
            }
            suggestionsClearedRevision = feedbackRevision;
            suggestions.clear();
            return count;
        }
    }

    /**
     * 获取学习状态概览
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("knowledgeBaseSize", knowledgeBase.size());
        status.put("trackedTopics", topicStatsMap.size());
        status.put("failurePatterns", failurePatterns.size());
        status.put("pendingSuggestions", suggestions.size());

        // 各主题满意度分布
        Map<String, Object> topicHealth = new HashMap<>();
        for (Map.Entry<TopicKey, TopicStats> entry : topicStatsMap.entrySet()) {
            TopicStats ts = entry.getValue();
            double rate = ts.totalFeedbacks > 0 ? (double) ts.positiveCount / ts.totalFeedbacks : 0;
            topicHealth.put(entry.getKey().toString(),
                    String.format("满意度%.0f%%(%d评)", rate * 100, ts.totalFeedbacks));
        }
        status.put("topicHealth", topicHealth);
        status.put("topicGuidanceCount", topicGuidanceMap.size());

        return status;
    }

    private void inferTopic(FeedbackPersistenceService.FeedbackRecord record) {
        if (toolRecommender == null || record.getQuestion() == null) {
            record.setTopic("general");
            return;
        }
        ToolRecommender.Recommendation recommendation = toolRecommender.recommend(record.getQuestion());
        record.setTopic(recommendation != null && recommendation.detectedIntent != null
                ? recommendation.detectedIntent
                : "general");
    }

    private void refreshTopicGuidance() {
        topicGuidanceMap.clear();
        for (Map.Entry<TopicKey, TopicStats> entry : topicStatsMap.entrySet()) {
            TopicStats stats = entry.getValue();
            if (stats.totalFeedbacks < 3 || stats.satisfactionRate() >= 0.6) {
                continue;
            }
            TopicKey key = entry.getKey();
            topicGuidanceMap.put(key, String.format(
                    "主题「%s」历史满意度仅 %.0f%%（%d/%d）：请优先调用最相关工具并直接回答用户问题。",
                    key.topic, stats.satisfactionRate() * 100, stats.positiveCount, stats.totalFeedbacks));
        }
    }

    private List<String> parseUsedTools(String usedTools) {
        if (usedTools == null || usedTools.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> tools = new ArrayList<>();
        for (String part : usedTools.split("[,，、;；]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                tools.add(trimmed);
            }
        }
        return tools;
    }

    private boolean questionMatchesTopic(String question, String topic) {
        if (question == null || topic == null) {
            return false;
        }
        String normalizedQuestion = normalizeQuestion(question);
        if ("general".equalsIgnoreCase(topic)) {
            return true;
        }
        if (normalizedQuestion.contains(normalizeQuestion(topic))) {
            return true;
        }
        Map<String, Set<String>> topicKeywords = defaultTopicKeywords();
        Set<String> keywords = topicKeywords.get(topic);
        if (keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (normalizedQuestion.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean questionMatchesSuggestion(String question, OptimizationSuggestion suggestion) {
        if (question == null || suggestion == null || suggestion.title == null) {
            return false;
        }
        String normalizedQuestion = normalizeQuestion(question);
        String normalizedTitle = normalizeQuestion(suggestion.title);
        for (String token : normalizedTitle.split("\\s+")) {
            if (token.length() >= 2 && normalizedQuestion.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Set<String>> defaultTopicKeywords() {
        Map<String, Set<String>> map = new HashMap<>();
        map.put("coverage", Set.of("覆盖", "coverage", "覆盖率", "低覆盖", "未覆盖"));
        map.put("performance", Set.of("性能", "慢接口", "响应时间", "延迟", "p95", "p99"));
        map.put("defect", Set.of("缺陷", "异常", "错误", "bug", "根因", "故障"));
        map.put("trace", Set.of("链路", "trace", "调用链", "上下游"));
        map.put("testcase", Set.of("测试", "用例", "补测", "回归"));
        map.put("code_relation", Set.of("调用关系", "调用图", "依赖", "代码关系"));
        map.put("bug_detect", Set.of("bug", "缺陷", "空指针", "源码"));
        map.put("business_logic", Set.of("业务", "需求", "逻辑", "职责"));
        map.put("project_info", Set.of("项目", "概览", "统计"));
        map.put("code_quality", Set.of("质量", "复杂度", "审查"));
        map.put("snapshot", Set.of("快照", "版本", "上线", "发布"));
        map.put("app_status", Set.of("应用", "在线", "状态"));
        return map;
    }

    // ==================== 内部学习方法 ====================

    private void learnFromPositives(List<FeedbackPersistenceService.SelfLearningSample> positives) {
        for (FeedbackPersistenceService.SelfLearningSample sample : positives) {
            addToKnowledgeBase(sample.question, sample.answer, sample.topic);
        }
    }

    private void learnFromNegatives(List<FeedbackPersistenceService.SelfLearningSample> negatives) {
        for (FeedbackPersistenceService.SelfLearningSample sample : negatives) {
            // 分析负面样本的共同特征
            String pattern = extractPattern(sample.question);
            failurePatterns.merge(pattern, 1, Integer::sum);
        }
    }

    private void addToKnowledgeBase(FeedbackPersistenceService.FeedbackRecord record) {
        addToKnowledgeBase(record.getQuestion(), record.getAnswer(), record.getTopic());
    }

    private void addToKnowledgeBase(String question, String answer, String topic) {
        if (question == null || answer == null) return;

        String pattern = extractPattern(question);
        String normalizedQ = normalizeQuestion(question);

        KnowledgeEntry existing = knowledgeBase.get(pattern);
        if (existing == null) {
            if (knowledgeBase.size() >= MAX_KNOWLEDGE_ENTRIES) {
                evictWeakestEntry();
            }

            knowledgeBase.put(pattern, new KnowledgeEntry(pattern, normalizedQ,
                    truncateAnswer(answer), topic, 1, System.currentTimeMillis()));
        } else {
            // 增加置信度
            existing.confirmations++;
            existing.lastUpdated = System.currentTimeMillis();
            // 保留更好的回答模板
            if (answer.length() > existing.recommendedAnswerTemplate.length()) {
                existing.recommendedAnswerTemplate = truncateAnswer(answer);
            }
        }
    }

    private void updateTopicStats(FeedbackPersistenceService.FeedbackRecord record) {
        TopicKey key = new TopicKey(
                record.getTopic() != null ? record.getTopic() : "general",
                record.getProjectId() != null ? record.getProjectId() : "unknown"
        );

        topicStatsMap.computeIfAbsent(key, k -> new TopicStats()).update(record);
    }

    private void analyzeFailurePattern(FeedbackPersistenceService.FeedbackRecord record) {
        String pattern = extractPattern(record.getQuestion());
        failurePatterns.merge(pattern, 1, Integer::sum);

        // 如果某模式频繁出现负面反馈，生成建议
        int count = failurePatterns.getOrDefault(pattern, 0);
        if (count >= 3) {
            String patternId = "failure_pattern_" + pattern.hashCode();
            addSuggestion(OptimizationSuggestion.Priority.HIGH,
                    "高频负面反馈模式检测到",
                    "问题模式「" + truncate(pattern, 50) + "」已收到 " + count + " 条负面反馈。"
                            + "\n建议检查该类问题的处理逻辑，或补充相关工具数据。",
                    patternId);
            refreshTopicGuidance();
        }
    }

    private void generateOptimizationSuggestions() {
        synchronized (suggestions) {
            suggestions.removeIf(s ->
                s.id.startsWith("topic_quality_") ||
                s.id.startsWith("pattern_hash_") ||
                s.id.startsWith("failure_pattern_") ||
                s.id.startsWith("pattern_"));
        }
        
        // 1. 低满意度主题检测
        for (Map.Entry<TopicKey, TopicStats> entry : topicStatsMap.entrySet()) {
            TopicStats ts = entry.getValue();
            if (ts.totalFeedbacks >= 5 && ts.satisfactionRate() < 0.6) {
                addSuggestion(OptimizationSuggestion.Priority.MEDIUM,
                        "主题「" + entry.getKey().topic + "」满意度偏低",
                        String.format("该主题满意度仅 %.0f%% (%d/%d)，建议优化相关提示词或增强数据获取能力。",
                                ts.satisfactionRate() * 100, ts.positiveCount, ts.totalFeedbacks),
                        "topic_quality_" + entry.getKey().topic);
            }
        }

        // 2. 高频失败模式（按模式文本hash而非计数生成id，避免重复累积）
        failurePatterns.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> {
                    if (e.getValue() >= 1) {
                        String patternId = "pattern_hash_" + e.getKey().hashCode();
                        addSuggestion(OptimizationSuggestion.Priority.MEDIUM,
                                "需要关注的失败模式",
                                "「" + truncate(e.getKey(), 40) + "」出现 " + e.getValue() + " 次负面反馈",
                                patternId);
                    }
                });
    }

    private LearningReport buildReport() {
        LearningReport report = new LearningReport();
        report.timestamp = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        report.knowledgeBaseEntries = knowledgeBase.size();
        report.trackedTopics = topicStatsMap.size();
        report.failurePatternsAnalyzed = failurePatterns.size();
        report.suggestionsGenerated = suggestions.size();

        // 主题健康度
        report.topicHealthScores = new HashMap<>();
        for (Map.Entry<TopicKey, TopicStats> entry : topicStatsMap.entrySet()) {
            report.topicHealthScores.put(entry.getKey().topic,
                    entry.getValue().satisfactionRate());
        }

        // 清理旧建议（保留最近20条）并按 ID 去重，避免旧版本 pattern_N 建议残留
        report.suggestions = normalizeSuggestions();
        report.suggestionsGenerated = report.suggestions.size();

        return report;
    }

    private List<OptimizationSuggestion> normalizeSuggestions() {
        synchronized (suggestions) {
            LinkedHashMap<String, OptimizationSuggestion> unique = new LinkedHashMap<>();
            for (OptimizationSuggestion suggestion : suggestions) {
                if (suggestion == null || suggestion.id == null) {
                    continue;
                }
                unique.put(suggestion.id, suggestion);
            }
            suggestions.clear();
            suggestions.addAll(unique.values());
            suggestions.sort(Comparator.comparingLong((OptimizationSuggestion s) -> s.createdTime).reversed());
            while (suggestions.size() > 20) {
                suggestions.remove(suggestions.size() - 1);
            }
            return new ArrayList<>(suggestions);
        }
    }

    private String extractPattern(String question) {
        if (question == null) return "";
        // 提取关键词作为模式（去除数字、停用词等）
        return normalizeQuestion(question).replaceAll("\\d+", "#")
                .replaceAll("(\\s+)\\1+", "$1").trim();
    }

    private String normalizeQuestion(String q) {
        return q.toLowerCase()
                .replaceAll("[\\p{Punct}&&[^?]]+", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private String truncateAnswer(String answer) {
        if (answer == null) return "";
        // 截取核心部分（前500字符），用于模板参考
        return answer.length() > 500 ? answer.substring(0, 500) + "..." : answer;
    }

    private String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private double computeSimilarity(String a, String b) {
        if (a == null || b == null) return 0;
        if (a.equals(b)) return 1.0;

        // 简化的 Jaccard 相似度
        Set<String> wordsA = new HashSet<>(Arrays.asList(a.split("\\s+")));
        Set<String> wordsB = new HashSet<>(Arrays.asList(b.split("\\s+")));

        Set<String> intersection = new HashSet<>(wordsA);
        intersection.retainAll(wordsB);

        Set<String> union = new HashSet<>(wordsA);
        union.addAll(wordsB);

        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private boolean isPositiveFeedback(FeedbackPersistenceService.FeedbackRecord record) {
        return "helpful".equals(record.getFeedbackType()) ||
               (record.getRating() != null && record.getRating() >= 4);
    }

    private boolean isNegativeFeedback(FeedbackPersistenceService.FeedbackRecord record) {
        return "not_helpful".equals(record.getFeedbackType()) ||
               "incorrect".equals(record.getFeedbackType()) ||
               "incomplete".equals(record.getFeedbackType()) ||
               (record.getRating() != null && record.getRating() <= 2);
    }

    private void evictWeakestEntry() {
        String weakest = null;
        long oldest = Long.MAX_VALUE;
        int lowestConfirmations = Integer.MAX_VALUE;

        for (Map.Entry<String, KnowledgeEntry> entry : knowledgeBase.entrySet()) {
            KnowledgeEntry v = entry.getValue();
            if (v.confirmations < lowestConfirmations ||
                    (v.confirmations == lowestConfirmations && v.lastUpdated < oldest)) {
                weakest = entry.getKey();
                lowestConfirmations = v.confirmations;
                oldest = v.lastUpdated;
            }
        }
        if (weakest != null) {
            knowledgeBase.remove(weakest);
        }
    }

    private void addSuggestion(OptimizationSuggestion.Priority priority,
                              String title, String description, String id) {
        if (isSuggestionCleared(id)) {
            return;
        }
        synchronized (suggestions) {
            suggestions.removeIf(s -> id.equals(s.id));
            suggestions.add(new OptimizationSuggestion(priority, title, description, id));
        }
    }

    private boolean isSuggestionCleared(String id) {
        return id != null
                && suggestionsClearedRevision == feedbackRevision
                && clearedSuggestionIds.contains(id);
    }

    private void startPeriodicLearning() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                runLearningCycle();
            } catch (Exception e) {
                logger.error("Periodic learning cycle failed", e);
            }
        }, learningReportIntervalHours, learningReportIntervalHours, TimeUnit.HOURS);
    }

    // ==================== 公开数据结构 ====================

    /**
     * 学习报告
     */
    public static class LearningReport implements Serializable {
        public String timestamp;
        public int knowledgeBaseEntries;
        public int trackedTopics;
        public int failurePatternsAnalyzed;
        public int suggestionsGenerated;
        public Map<String, Double> topicHealthScores;
        public List<OptimizationSuggestion> suggestions;
    }

    /**
     * 优化建议
     */
    public static class OptimizationSuggestion implements Serializable {
        public enum Priority { HIGH, MEDIUM, LOW }

        public final Priority priority;
        public final String title;
        public final String description;
        public final String id;
        public final long createdTime;

        public OptimizationSuggestion(Priority priority, String title, String description, String id) {
            this.priority = priority;
            this.title = title;
            this.description = description;
            this.id = id;
            this.createdTime = System.currentTimeMillis();
        }
    }

    /**
     * 关闭服务
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("AISelfLearningService shutdown");
    }
}
