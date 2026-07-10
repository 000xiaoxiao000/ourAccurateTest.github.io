package com.oAT.ai.agent.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AI工具调用缓存
 * 用于缓存频繁查询且变化不频繁的数据，提升响应速度
 */
public class ToolCallCache {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallCache.class);
    
    private static final ToolCallCache INSTANCE = new ToolCallCache();
    
    /** 缓存存储：key -> CacheEntry */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    
    /** 定时清理过期缓存 */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    /** 默认缓存时间：5分钟 */
    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000;
    
    /** 最大缓存条目数 */
    private static final int MAX_CACHE_SIZE = 1000;

    private ToolCallCache() {
        // 每分钟清理一次过期缓存
        scheduler.scheduleAtFixedRate(this::cleanExpiredEntries, 1, 1, TimeUnit.MINUTES);
    }

    public static ToolCallCache getInstance() {
        return INSTANCE;
    }

    /**
     * 获取缓存
     */
    public String get(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        
        if (entry.isExpired()) {
            cache.remove(key);
            logger.debug("Cache expired for key: {}", key);
            return null;
        }
        
        logger.debug("Cache hit for key: {}", key);
        entry.incrementHitCount();
        return entry.getValue();
    }

    /**
     * 设置缓存（使用默认TTL）
     */
    public void put(String key, String value) {
        put(key, value, DEFAULT_TTL_MS);
    }

    /**
     * 设置缓存（自定义TTL）
     */
    public void put(String key, String value, long ttlMs) {
        // 如果缓存已满，清理一部分
        if (cache.size() >= MAX_CACHE_SIZE) {
            evictSomeEntries();
        }
        
        cache.put(key, new CacheEntry(value, ttlMs));
        logger.debug("Cache put for key: {}, ttl: {}ms", key, ttlMs);
    }

    /**
     * 删除缓存
     */
    public void remove(String key) {
        cache.remove(key);
    }

    /**
     * 清空所有缓存
     */
    public void clear() {
        cache.clear();
        logger.info("Cache cleared");
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        int totalEntries = cache.size();
        int expiredEntries = 0;
        long totalHits = 0;
        
        for (CacheEntry entry : cache.values()) {
            if (entry.isExpired()) {
                expiredEntries++;
            }
            totalHits += entry.getHitCount();
        }
        
        return new CacheStats(totalEntries, expiredEntries, totalHits);
    }

    /**
     * 清理过期条目
     */
    private void cleanExpiredEntries() {
        int cleaned = 0;
        for (ConcurrentHashMap.Entry<String, CacheEntry> entry : cache.entrySet()) {
            if (entry.getValue().isExpired()) {
                cache.remove(entry.getKey());
                cleaned++;
            }
        }
        if (cleaned > 0) {
            logger.debug("Cleaned {} expired cache entries", cleaned);
        }
    }

    /**
     * 驱逐部分缓存（当缓存满时）
     * 策略：驱逐最早过期的50%条目
     */
    private void evictSomeEntries() {
        int targetSize = MAX_CACHE_SIZE / 2;
        int toRemove = cache.size() - targetSize;
        
        if (toRemove <= 0) return;
        
        // 简单策略：移除前toRemove个条目
        cache.entrySet().stream()
            .limit(toRemove)
            .forEach(entry -> cache.remove(entry.getKey()));
        
        logger.info("Evicted {} cache entries", toRemove);
    }

    /**
     * 缓存条目
     */
    private static class CacheEntry {
        private final String value;
        private final long expireTime;
        private final long createTime;
        private long hitCount;

        public CacheEntry(String value, long ttlMs) {
            this.value = value;
            this.createTime = System.currentTimeMillis();
            this.expireTime = this.createTime + ttlMs;
            this.hitCount = 0;
        }

        public String getValue() {
            return value;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expireTime;
        }

        public void incrementHitCount() {
            this.hitCount++;
        }

        public long getHitCount() {
            return hitCount;
        }

        public long getCreateTime() {
            return createTime;
        }
    }

    /**
     * 缓存统计信息
     */
    public static class CacheStats {
        private final int totalEntries;
        private final int expiredEntries;
        private final long totalHits;

        public CacheStats(int totalEntries, int expiredEntries, long totalHits) {
            this.totalEntries = totalEntries;
            this.expiredEntries = expiredEntries;
            this.totalHits = totalHits;
        }

        public int getTotalEntries() {
            return totalEntries;
        }

        public int getExpiredEntries() {
            return expiredEntries;
        }

        public long getTotalHits() {
            return totalHits;
        }

        @Override
        public String toString() {
            return "CacheStats{" +
                    "totalEntries=" + totalEntries +
                    ", expiredEntries=" + expiredEntries +
                    ", totalHits=" + totalHits +
                    '}';
        }
    }

    /**
     * 关闭缓存服务（应用关闭时调用）
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("ToolCallCache shutdown");
    }
}
