package com.oAT.ai.agent.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语义缓存服务
 * 基于向量相似度的智能缓存层，用于识别语义相似的问题并复用之前的回答
 *
 * <p>工作原理：
 * 1. 对用户问题生成 embedding（或使用简化的关键词提取作为 fallback）
 * 2. 计算与历史缓存的相似度
 * 3. 如果相似度超过阈值（默认0.85），直接返回缓存的回答
 * 4. 否则调用 LLM 并将结果存入缓存
 * </p>
 *
 * <p>注意：完整版需要接入向量数据库（如 Milvus/Pinecone/Chroma）。
 * 本实现提供基于关键词匹配的轻量级 fallback 方案。</p>
 */
public class SemanticCacheService {

    private static final Logger logger = LoggerFactory.getLogger(SemanticCacheService.class);

    /** 相似度阈值（0-1），超过此值认为问题语义相同 */
    private final double similarityThreshold;

    /** 最大缓存条目数 */
    private final int maxCacheSize;

    /** 缓存存储：questionHash -> CacheEntry */
    private final ConcurrentHashMap<String, SemanticCacheEntry> cache = new ConcurrentHashMap<>();

    /** 关键词索引：keyword -> questionHash（用于快速检索） */
    private final ConcurrentHashMap<String, List<String>> keywordIndex = new ConcurrentHashMap<>();

    public SemanticCacheService() {
        this.similarityThreshold = 0.85;
        this.maxCacheSize = 500;
        logger.info("SemanticCacheService initialized (standalone mode, threshold={}, maxSize={})",
                similarityThreshold, maxCacheSize);
    }

    public SemanticCacheService(double similarityThreshold, int maxCacheSize) {
        this.similarityThreshold = similarityThreshold;
        this.maxCacheSize = maxCacheSize;
        logger.info("SemanticCacheService initialized, threshold={}, maxSize={}",
                similarityThreshold, maxCacheSize);
    }

    /**
     * 查询语义缓存
     *
     * @param question 用户问题
     * @return 缓存的回答；如果未命中返回 null
     */
    public CachedResponse get(String question) {
        if (question == null || question.trim().isEmpty()) {
            return null;
        }

        String cleanQuestion = question.trim();
        String questionHash = hash(cleanQuestion);

        // 1. 精确匹配
        SemanticCacheEntry exactMatch = cache.get(questionHash);
        if (exactMatch != null && !exactMatch.isExpired()) {
            exactMatch.recordHit();
            logger.debug("Semantic cache exact hit for hash: {}", questionHash);
            return new CachedResponse(exactMatch.getAnswer(), exactMatch.getUsedTools(), true);
        }

        // 2. 语义相似度匹配
        String bestMatchKey = findBestMatch(cleanQuestion);
        if (bestMatchKey != null) {
            SemanticCacheEntry entry = cache.get(bestMatchKey);
            if (entry != null && !entry.isExpired()) {
                entry.recordHit();
                logger.debug("Semantic cache similarity hit: {} -> {}", questionHash, bestMatchKey);
                return new CachedResponse(entry.getAnswer(), entry.getUsedTools(), true);
            }
        }

        return null;
    }

    /**
     * 将问答结果存入语义缓存
     */
    public void put(String question, String answer, String usedTools) {
        if (question == null || answer == null) {
            return;
        }

        String cleanQuestion = question.trim();
        String questionHash = hash(cleanQuestion);

        // 容量控制
        if (cache.size() >= maxCacheSize) {
            evictOldest();
        }

        SemanticCacheEntry entry = new SemanticCacheEntry(
                cleanQuestion, answer, usedTools, System.currentTimeMillis()
        );
        cache.put(questionHash, entry);

        // 构建关键词索引
        buildKeywordIndex(cleanQuestion, questionHash);

        logger.debug("Put to semantic cache: hash={}", questionHash);
    }

    /**
     * 获取缓存统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.HashMap<>();
        long totalHits = 0;
        long totalMisses = 0;
        int expiredCount = 0;

        for (SemanticCacheEntry entry : cache.values()) {
            totalHits += entry.getHitCount();
            if (entry.isExpired()) {
                expiredCount++;
            } else {
                totalMisses++;
            }
        }

        stats.put("totalEntries", cache.size());
        stats.put("activeEntries", cache.size() - expiredCount);
        stats.put("expiredEntries", expiredCount);
        stats.put("totalHits", totalHits);
        stats.put("keywordIndexSize", keywordIndex.size());
        stats.put("hitRate", totalHits + totalMisses > 0
                ? String.format("%.2f%%", (double) totalHits / (totalHits + totalMisses) * 100)
                : "0%");
        return stats;
    }

    /**
     * 清除所有缓存
     */
    public void clear() {
        cache.clear();
        keywordIndex.clear();
        logger.info("Semantic cache cleared");
    }

    // ==================== 内部方法 ====================

    /**
     * 生成问题的哈希值（MD5）
     */
    private String hash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            // Fallback: 使用 Java hashCode
            return String.valueOf(text.hashCode());
        }
    }

    /**
     * 提取中文和英文关键词并建立索引
     */
    private void buildKeywordIndex(String text, String questionHash) {
        // 简单分词：按空格、标点、中文字符分割
        String[] tokens = text.split("[\\s\\p{Punct}]+");
        for (String token : tokens) {
            token = token.toLowerCase().trim();
            if (token.length() < 2) continue;

            keywordIndex.computeIfAbsent(token, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(questionHash);
        }
    }

    /**
     * 基于关键词重叠度找到最佳匹配
     */
    private String findBestMatch(String question) {
        String[] queryTokens = question.split("[\\s\\p{Punct}]+");

        // 统计每个候选的匹配分数
        Map<String, Integer> scores = new java.util.HashMap<>();
        for (String token : queryTokens) {
            token = token.toLowerCase().trim();
            if (token.length() < 2) continue;

            List<String> candidates = keywordIndex.get(token);
            if (candidates != null) {
                for (String candidateHash : candidates) {
                    scores.merge(candidateHash, 1, Integer::sum);
                }
            }
        }

        // 找到最高分的候选
        double maxScore = 0;
        String bestMatch = null;
        int queryTokenCount = 0;
        for (String t : queryTokens) { if (t.trim().length() >= 2) queryTokenCount++; }

        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            double score = (double) entry.getValue() / Math.max(queryTokenCount, 1);
            if (score > maxScore && score >= similarityThreshold) {
                maxScore = score;
                bestMatch = entry.getKey();
            }
        }

        return bestMatch;
    }

    /**
     * 驱逐最老的条目
     */
    private void evictOldest() {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;

        for (Map.Entry<String, SemanticCacheEntry> entry : cache.entrySet()) {
            if (entry.getValue().getCreateTime() < oldestTime) {
                oldestTime = entry.getValue().getCreateTime();
                oldestKey = entry.getKey();
            }
        }

        if (oldestKey != null) {
            SemanticCacheEntry removed = cache.remove(oldestKey);
            if (removed != null) {
                // 清理关键词索引
                String[] tokens = removed.getOriginalQuestion().split("[\\s\\p{Punct}]+");
                for (String token : tokens) {
                    List<String> list = keywordIndex.get(token.toLowerCase());
                    if (list != null) {
                        list.remove(oldestKey);
                        if (list.isEmpty()) {
                            keywordIndex.remove(token.toLowerCase());
                        }
                    }
                }
            }
            logger.debug("Evicted oldest semantic cache entry: {}", oldestKey);
        }
    }

    // ==================== 内部类 ====================

    /**
     * 语义缓存条目
     */
    static class SemanticCacheEntry {
        private final String originalQuestion;
        private final String answer;
        private final String usedTools;
        private final long createTime;
        private final AtomicLong hitCount = new AtomicLong(0);
        private static final long TTL_MS = 5 * 60 * 1000; // 5分钟

        SemanticCacheEntry(String originalQuestion, String answer, String usedTools, long createTime) {
            this.originalQuestion = originalQuestion;
            this.answer = answer;
            this.usedTools = usedTools;
            this.createTime = createTime;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createTime > TTL_MS;
        }

        void recordHit() {
            this.hitCount.incrementAndGet();
        }

        long getHitCount() { return hitCount.get(); }
        String getAnswer() { return answer; }
        String getUsedTools() { return usedTools; }
        long getCreateTime() { return createTime; }
        String getOriginalQuestion() { return originalQuestion; }
    }

    /**
     * 缓存的响应结果
     */
    public static class CachedResponse {
        private final String answer;
        private final String usedTools;
        private final boolean fromCache;

        public CachedResponse(String answer, String usedTools, boolean fromCache) {
            this.answer = answer;
            this.usedTools = usedTools;
            this.fromCache = fromCache;
        }

        public String getAnswer() { return answer; }
        public String getUsedTools() { return usedTools; }
        public boolean isFromCache() { return fromCache; }
    }
}
