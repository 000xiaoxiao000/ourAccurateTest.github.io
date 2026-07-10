package com.oAT.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 反馈持久化服务
 * 将用户反馈数据持久化到文件系统（支持未来切换到数据库）
 *
 * <p>数据格式：JSON Lines (.jsonl)，每行一个反馈记录</p>
 * <p>存储路径：${oat.data.path}/ai-feedback/feedback_{yyyyMMdd}.jsonl</p>
 */
public class FeedbackPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(FeedbackPersistenceService.class);
    private static volatile int defaultRetentionDays = 30;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String FEEDBACK_DIR = "ai-feedback";

    /** 内存索引：feedbackId -> 记录 */
    private final ConcurrentHashMap<String, FeedbackRecord> index = new ConcurrentHashMap<>();

    /** ID 生成器 */
    private final AtomicLong idGenerator = new AtomicLong(System.currentTimeMillis());

    /** 数据存储根目录 */
    private final String dataPath;

    public FeedbackPersistenceService(String oatDataPath) {
        this.dataPath = oatDataPath != null ? oatDataPath : System.getProperty("user.home") + "/oAT/codeData";
        loadRecentFeedback(defaultRetentionDays);
        cleanup(defaultRetentionDays);
        logger.info("FeedbackPersistenceService initialized, path={}, retentionDays={}, loadedRecords={}",
                getTodayFilePath(), defaultRetentionDays, index.size());
    }

    public static void setDefaultRetentionDays(int retentionDays) {
        defaultRetentionDays = retentionDays;
    }

    /**
     * 提交反馈
     */
    public FeedbackRecord submit(FeedbackRecord record) {
        // 生成ID
        if (record.getFeedbackId() == null || record.getFeedbackId().isEmpty()) {
            record.setFeedbackId(generateId());
        }
        if (record.getCreateTime() == null) {
            record.setCreateTime(new Date());
        }

        // 写入文件
        appendToFile(record);

        // 更新内存索引
        index.put(record.getFeedbackId(), record);

        logger.info("Feedback submitted: id={}, type={}, rating={}",
                record.getFeedbackId(), record.getFeedbackType(), record.getRating());

        return record;
    }

    /**
     * 获取单条反馈
     */
    public FeedbackRecord get(String feedbackId) {
        return index.get(feedbackId);
    }

    /**
     * 更新反馈（如快速评分）
     */
    public boolean update(String feedbackId, Map<String, Object> updates) {
        FeedbackRecord record = index.get(feedbackId);
        if (record == null) return false;

        if (updates.containsKey("rating")) {
            record.setRating((Integer) updates.get("rating"));
        }
        if (updates.containsKey("feedbackType")) {
            record.setFeedbackType((String) updates.get("feedbackType"));
        }
        if (updates.containsKey("comment")) {
            record.setComment((String) updates.get("comment"));
        }

        // 重写文件
        rewriteTodayFile();

        return true;
    }

    /**
     * 获取用户的反馈历史
     */
    public List<FeedbackRecord> getUserFeedbacks(String userId, int limit) {
        return index.values().stream()
                .filter(f -> userId.equals(f.getUserId()))
                .sorted((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 获取反馈统计
     */
    public Map<String, Object> getStats(String projectIdFilter) {
        List<FeedbackRecord> all = projectIdFilter != null
                ? index.values().stream().filter(f -> projectIdFilter.equals(f.getProjectId())).collect(Collectors.toList())
                : new ArrayList<>(index.values());

        long total = all.size();
        long positive = all.stream().filter(f -> "helpful".equals(f.getFeedbackType()) ||
                (f.getRating() != null && f.getRating() >= 4)).count();
        long negative = all.stream().filter(f -> "not_helpful".equals(f.getFeedbackType()) ||
                (f.getRating() != null && f.getRating() <= 2)).count();
        long neutral = total - positive - negative;

        // 意图分布统计
        Map<String, Long> intentDist = new LinkedHashMap<>();
        Map<String, Long> topicDist = new LinkedHashMap<>();
        for (FeedbackRecord f : all) {
            if (f.getTopic() != null) topicDist.merge(f.getTopic(), 1L, Long::sum);
            if (f.getFeedbackType() != null) intentDist.merge(f.getFeedbackType(), 1L, Long::sum);
        }

        // 满意度趋势（最近7天）
        Map<String, Double> satisfactionTrend = calculateSatisfactionTrend(all);

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("positive", positive);
        stats.put("negative", negative);
        stats.put("neutral", neutral);
        stats.put("satisfactionRate", total > 0 ? String.format("%.2f%%", (double) positive / total * 100) : "0%");
        stats.put("intentDistribution", intentDist);
        stats.put("topicDistribution", topicDist);
        stats.put("satisfactionTrend", satisfactionTrend);
        return stats;
    }

    /**
     * 获取所有反馈记录（内存索引）
     */
    public List<FeedbackRecord> getAllRecords() {
        return new ArrayList<>(index.values());
    }

    /**
     * 获取用于自主学习的训练数据
     * 返回正面和负面样本对，供 AI 自我优化使用
     */
    public SelfLearningDataset getLearningDataset() {
        List<SelfLearningSample> positiveSamples = new ArrayList<>();
        List<SelfLearningSample> negativeSamples = new ArrayList<>();

        for (FeedbackRecord record : index.values()) {
            if (record.getQuestion() == null || record.getAnswer() == null) continue;

            SelfLearningSample sample = new SelfLearningSample(
                    record.getQuestion(),
                    record.getAnswer(),
                    record.getTopic(),
                    record.getPageContext()
            );

            if ("helpful".equals(record.getFeedbackType()) ||
                    (record.getRating() != null && record.getRating() >= 4)) {
                positiveSamples.add(sample);
            } else if ("not_helpful".equals(record.getFeedbackType()) ||
                       "incorrect".equals(record.getFeedbackType()) ||
                       "incomplete".equals(record.getFeedbackType()) ||
                       (record.getRating() != null && record.getRating() <= 2)) {
                negativeSamples.add(sample);
            }
        }

        logger.info("Learning dataset: {} positive, {} negative samples", positiveSamples.size(), negativeSamples.size());
        return new SelfLearningDataset(positiveSamples, negativeSamples);
    }

    /**
     * 清除过期数据（默认保留30天）
     */
    public int cleanup(int retainDays) {
        Path dir = Paths.get(dataPath, FEEDBACK_DIR);
        if (!Files.exists(dir)) return 0;

        int deleted = 0;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retainDays);

        try {
            for (Path file : Files.newDirectoryStream(dir, "feedback_*.jsonl")) {
                String fileName = file.getFileName().toString();
                try {
                    String dateStr = fileName.replace("feedback_", "").replace(".jsonl", "");
                    LocalDate fileDate = LocalDate.parse(dateStr, DATE_FORMAT);
                    if (fileDate.isBefore(cutoff.toLocalDate())) {
                        Files.delete(file);
                        deleted++;
                        logger.debug("Deleted old feedback file: {}", fileName);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse date from file {}: {}", fileName, e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.error("Failed to cleanup old feedback files", e);
        }

        return deleted;
    }

    // ==================== 内部方法 ====================

    private String generateId() {
        return "fb" + idGenerator.incrementAndGet() + "_" +
                UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private void appendToFile(FeedbackRecord record) {
        try {
            Path dir = Paths.get(dataPath, FEEDBACK_DIR);
            Files.createDirectories(dir);

            Path file = getTodayFilePath();
            String json = OBJECT_MAPPER.writeValueAsString(record) + "\n";
            Files.write(file, json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.error("Failed to write feedback to file: {}", e.getMessage());
        }
    }

    private Path getTodayFilePath() {
        return Paths.get(dataPath, FEEDBACK_DIR,
                "feedback_" + LocalDateTime.now().format(DATE_FORMAT) + ".jsonl");
    }

    private void loadRecentFeedback(int retainDays) {
        Path dir = Paths.get(dataPath, FEEDBACK_DIR);
        if (!Files.exists(dir)) {
            return;
        }

        LocalDate cutoff = LocalDate.now().minusDays(Math.max(1, retainDays));
        int loadedFiles = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "feedback_*.jsonl")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                try {
                    String dateStr = fileName.replace("feedback_", "").replace(".jsonl", "");
                    LocalDate fileDate = LocalDate.parse(dateStr, DATE_FORMAT);
                    if (fileDate.isBefore(cutoff)) {
                        continue;
                    }
                    loadedFiles++;
                    loadFeedbackFile(file);
                } catch (Exception e) {
                    logger.warn("Failed to parse feedback file {}: {}", fileName, e.getMessage());
                }
            }
            logger.info("Loaded {} feedback records from {} recent files", index.size(), loadedFiles);
        } catch (IOException e) {
            logger.error("Failed to load recent feedback files", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFeedbackFile(Path file) {
        if (!Files.exists(file)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    Map<String, Object> map = OBJECT_MAPPER.readValue(line, Map.class);
                    FeedbackRecord record = mapToRecord(map);
                    if (record.getFeedbackId() != null) {
                        index.put(record.getFeedbackId(), record);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse feedback line in {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load feedback file {}", file, e);
        }
    }

    private void rewriteTodayFile() {
        Path file = getTodayFilePath();
        if (!Files.exists(file)) return;

        try {
            StringBuilder sb = new StringBuilder();
            for (FeedbackRecord record : index.values()) {
                sb.append(OBJECT_MAPPER.writeValueAsString(record)).append("\n");
            }
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.error("Failed to rewrite feedback file: {}", e.getMessage());
        }
    }

    private Map<String, Double> calculateSatisfactionTrend(List<FeedbackRecord> all) {
        Map<String, Double> trend = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            String key = date.toString();
            final String dayStr = date.format(DATE_FORMAT);

            List<FeedbackRecord> dayRecords = all.stream()
                    .filter(r -> r.getCreateTime() != null &&
                            dayStr.equals(new java.text.SimpleDateFormat("yyyyMMdd").format(r.getCreateTime())))
                    .collect(Collectors.toList());

            if (!dayRecords.isEmpty()) {
                long pos = dayRecords.stream().filter(r ->
                        "helpful".equals(r.getFeedbackType()) || (r.getRating() != null && r.getRating() >= 4))
                        .count();
                trend.put(key, (double) pos / dayRecords.size() * 100);
            } else {
                trend.put(key, null);
            }
        }
        return trend;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private FeedbackRecord mapToRecord(Map<String, Object> map) {
        FeedbackRecord r = new FeedbackRecord();
        r.setFeedbackId((String) map.getOrDefault("feedbackId", null));
        r.setProjectId((String) map.getOrDefault("projectId", null));
        r.setUserId((String) map.getOrDefault("userId", null));
        r.setQuestion((String) map.getOrDefault("question", null));
        r.setAnswer((String) map.getOrDefault("answer", null));
        Number rating = (Number) map.get("rating");
        r.setRating(rating != null ? rating.intValue() : null);
        r.setFeedbackType((String) map.getOrDefault("feedbackType", null));
        r.setComment((String) map.getOrDefault("comment", null));
        r.setUsedTools((String) map.getOrDefault("usedTools", null));
        r.setTopic((String) map.getOrDefault("topic", null));
        r.setPageContext((String) map.getOrDefault("pageContext", null));

        Number responseTime = (Number) map.get("responseTime");
        if (responseTime != null) {
            r.setResponseTime(responseTime.longValue());
        }
        Object ct = map.get("createTime");
        if (ct instanceof Long) r.setCreateTime(new Date((Long) ct));
        else if (ct instanceof Integer) r.setCreateTime(new Date(((Integer) ct).longValue()));
        else if (ct instanceof String) r.setCreateDateFromISO((String) ct);
        return r;
    }

    // ==================== 数据实体类 ====================

    /**
     * 反馈记录
     */
    public static class FeedbackRecord implements Serializable {
        private String feedbackId;
        private String projectId;
        private String userId;
        private String question;
        private String answer;
        private Integer rating;
        private String feedbackType;
        private String comment;
        private String usedTools;
        private String topic;
        private String pageContext;
        private Long responseTime;
        private Date createTime;

        // Getters and Setters
        public String getFeedbackId() { return feedbackId; }
        public void setFeedbackId(String feedbackId) { this.feedbackId = feedbackId; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
        public Integer getRating() { return rating; }
        public void setRating(Integer rating) { this.rating = rating; }
        public String getFeedbackType() { return feedbackType; }
        public void setFeedbackType(String feedbackType) { this.feedbackType = feedbackType; }
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
        public String getUsedTools() { return usedTools; }
        public void setUsedTools(String usedTools) { this.usedTools = usedTools; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getPageContext() { return pageContext; }
        public void setPageContext(String pageContext) { this.pageContext = pageContext; }
        public Long getResponseTime() { return responseTime; }
        public void setResponseTime(Long responseTime) { this.responseTime = responseTime; }
        public Date getCreateTime() { return createTime; }
        public void setCreateTime(Date createTime) { this.createTime = createTime; }
        public void setCreateDateFromISO(String iso) {
            try { this.createTime = Date.from(java.time.Instant.parse(iso)); } catch (Exception ignored) {}
        }
    }

    /**
     * 自学习数据集
     */
    public static class SelfLearningDataset implements Serializable {
        public final List<SelfLearningSample> positiveSamples;
        public final List<SelfLearningSample> negativeSamples;

        public SelfLearningDataset(List<SelfLearningSample> positiveSamples,
                                   List<SelfLearningSample> negativeSamples) {
            this.positiveSamples = positiveSamples != null ? positiveSamples : Collections.emptyList();
            this.negativeSamples = negativeSamples != null ? negativeSamples : Collections.emptyList();
        }
    }

    /**
     * 自学习样本
     */
    public static class SelfLearningSample implements Serializable {
        public final String question;
        public final String answer;
        public final String topic;
        public final String context;

        public SelfLearningSample(String question, String answer, String topic, String context) {
            this.question = question;
            this.answer = answer;
            this.topic = topic;
            this.context = context;
        }
    }
}
