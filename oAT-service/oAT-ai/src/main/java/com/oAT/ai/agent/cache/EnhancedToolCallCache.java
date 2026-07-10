package com.oAT.ai.agent.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 增强版工具调用缓存
 * 支持任意对象的缓存（通过JSON序列化）
 */
public class EnhancedToolCallCache {

    private static final Logger logger = LoggerFactory.getLogger(EnhancedToolCallCache.class);
    
    private static final EnhancedToolCallCache INSTANCE = new EnhancedToolCallCache();
    
    /** 缓存存储：key -> CacheEntry */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    
    /** JSON序列化器 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    /** 定时清理过期缓存 */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    /** 默认缓存时间：5分钟 */
    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000;
    
    /** 最大缓存条目数 */
    private static final int MAX_CACHE_SIZE = 2000;
    
    /** 缓存统计 */
    private volatile long totalHits = 0;
    private volatile long totalMisses = 0;

    private EnhancedToolCallCache() {
        // 每分钟清理一次过期缓存
        scheduler.scheduleAtFixedRate(this::cleanExpiredEntries, 1, 1, TimeUnit.MINUTES);
        // 每5分钟打印统计信息
        scheduler.scheduleAtFixedRate(this::printStats, 5, 5, TimeUnit.MINUTES);
    }

    public static EnhancedToolCallCache getInstance() {
        return INSTANCE;
    }

    /**
     * 获取缓存对象
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            totalMisses++;
            logger.debug("Cache miss for key: {}", key);
            return null;
        }
        
        if (entry.isExpired()) {
            cache.remove(key);
            totalMisses++;
            logger.debug("Cache expired for key: {}", key);
            return null;
        }
        
        totalHits++;
        entry.incrementHitCount();
        
        try {
            // 反序列化对象
            return OBJECT_MAPPER.readValue(entry.getSerializedValue(), clazz);
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize cached object for key: {}", key, e);
            cache.remove(key);
            return null;
        }
    }

    /**
     * 设置缓存对象（使用默认TTL）
     */
    public void put(String key, Object value) {
        put(key, value, DEFAULT_TTL_MS);
    }

    /**
     * 设置缓存对象（自定义TTL）
     */
    public void put(String key, Object value, long ttlMs) {
        if (value == null) {
            return;
        }
        
        // 如果缓存已满，清理一部分
        if (cache.size() >= MAX_CACHE_SIZE) {
            evictSomeEntries();
        }
        
        try {
            // 序列化对象
            String serializedValue = OBJECT_MAPPER.writeValueAsString(value);
            cache.put(key, new CacheEntry(serializedValue, ttlMs));
            logger.debug("Cache put for key: {}, size: {} bytes, ttl: {}ms", 
                key, serializedValue.length(), ttlMs);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize object for cache key: {}", key, e);
        }
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
        totalHits = 0;
        totalMisses = 0;
        logger.info("Cache cleared");
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        int totalEntries = cache.size();
        int expiredEntries = 0;
        long totalHitsLocal = 0;
        long totalSize = 0;
        
        for (CacheEntry entry : cache.values()) {
            if (entry.isExpired()) {
                expiredEntries++;
            }
            totalHitsLocal += entry.getHitCount();
            totalSize += entry.getSize();
        }
        
        long totalRequests = this.totalHits + this.totalMisses;
        double hitRate = totalRequests > 0 ? (double) this.totalHits / totalRequests * 100 : 0;
        
        return new CacheStats(
            totalEntries, 
            expiredEntries, 
            totalHitsLocal,
            totalSize,
            this.totalHits,
            this.totalMisses,
            hitRate
        );
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
     * 策略：驱逐最早创建且未命中的条目
     */
    private void evictSomeEntries() {
        int targetSize = MAX_CACHE_SIZE / 2;
        int toRemove = cache.size() - targetSize;
        
        if (toRemove <= 0) return;
        
        // 找到命中次数最少的条目进行驱逐
        cache.entrySet().stream()
            .sorted((a, b) -> Long.compare(a.getValue().getHitCount(), b.getValue().getHitCount()))
            .limit(toRemove)
            .forEach(entry -> cache.remove(entry.getKey()));
        
        logger.info("Evicted {} cache entries (LRU strategy)", toRemove);
    }

    /**
     * 打印统计信息
     */
    private void printStats() {
        CacheStats stats = getStats();
        logger.info("Cache Stats: entries={}, expired={}, hits={}, misses={}, hitRate={}%, size={}KB",
            stats.getTotalEntries(),
            stats.getExpiredEntries(),
            stats.getTotalHits(),
            stats.getGlobalMisses(),
            String.format("%.2f", stats.getHitRate()),
            stats.getTotalSize() / 1024);
    }

    /**
     * 缓存条目
     */
    private static class CacheEntry {
        private final String serializedValue;
        private final long expireTime;
        private final long createTime;
        private final int size;
        private volatile long hitCount;

        public CacheEntry(String serializedValue, long ttlMs) {
            this.serializedValue = serializedValue;
            this.createTime = System.currentTimeMillis();
            this.expireTime = this.createTime + ttlMs;
            this.size = serializedValue.length();
            this.hitCount = 0;
        }

        public String getSerializedValue() {
            return serializedValue;
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

        public int getSize() {
            return size;
        }

        public long getCreateTime() {
            return createTime;
        }
    }

    /**
     * 缓存统计信息
     */
    public static class CacheStats implements Serializable {
        private final int totalEntries;
        private final int expiredEntries;
        private final long totalHits;
        private final long totalSize;
        private final long globalHits;
        private final long globalMisses;
        private final double hitRate;

        public CacheStats(int totalEntries, int expiredEntries, long totalHits, 
                         long totalSize, long globalHits, long globalMisses, double hitRate) {
            this.totalEntries = totalEntries;
            this.expiredEntries = expiredEntries;
            this.totalHits = totalHits;
            this.totalSize = totalSize;
            this.globalHits = globalHits;
            this.globalMisses = globalMisses;
            this.hitRate = hitRate;
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

        public long getTotalSize() {
            return totalSize;
        }

        public long getGlobalHits() {
            return globalHits;
        }

        public long getGlobalMisses() {
            return globalMisses;
        }

        public double getHitRate() {
            return hitRate;
        }

        @Override
        public String toString() {
            return "CacheStats{" +
                    "totalEntries=" + totalEntries +
                    ", expiredEntries=" + expiredEntries +
                    ", totalHits=" + totalHits +
                    ", totalSize=" + totalSize +
                    ", globalHits=" + globalHits +
                    ", globalMisses=" + globalMisses +
                    ", hitRate=" + String.format("%.2f%%", hitRate) +
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
        logger.info("EnhancedToolCallCache shutdown");
    }
}
