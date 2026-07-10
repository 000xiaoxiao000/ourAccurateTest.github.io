package com.oAT.web.api.ai;

import com.oAT.web.service.entity.UserVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AIInteractiveSessionMemoryService {
    public static final String MEMORY_SCOPE_WORKBENCH = "workbench";

    private static final Logger logger = LoggerFactory.getLogger(AIInteractiveSessionMemoryService.class);
    private static final String SESSION_STORE_KEY_PREFIX = "oAT:ai-interactive:v1:sessions:";
    private static final String LEGACY_SESSION_STORE_KEY_PREFIX = "oAT:ai-interactive:sessions:";
    private static final long SESSION_STORE_TTL_DAYS = 30L;
    private static final String SESSION_LOG_PREFIX = "[AI-INTERACTIVE-SESSION]";
    private static final AtomicLong SESSION_RESTORE_COUNTER = new AtomicLong();
    private static final AtomicLong SESSION_SAVE_COUNTER = new AtomicLong();
    private static final AtomicLong SESSION_MISS_COUNTER = new AtomicLong();

    private final Map<String, String> sessionStore = new ConcurrentHashMap<>();

    public AIInteractiveSessionMemoryService() {
    }

    public String saveSessionState(String projectId, UserVo user, String sessionState) {
        if (!StringUtils.hasText(sessionState)) {
            logger.info("{} action=save status=skipped projectId={} userId={} reason=empty_payload", SESSION_LOG_PREFIX,
                    projectId, user.getId());
            return loadSessionState(projectId, user, MEMORY_SCOPE_WORKBENCH);
        }
        String cacheKey = buildSessionStoreKey(projectId, user.getId(), MEMORY_SCOPE_WORKBENCH);
        sessionStore.put(cacheKey, sessionState);
        long saveCount = SESSION_SAVE_COUNTER.incrementAndGet();
        logger.info("{} action=save status=success projectId={} userId={} payloadLength={} ttlDays={} saveCount={}",
                SESSION_LOG_PREFIX, projectId, user.getId(), sessionState.length(), SESSION_STORE_TTL_DAYS, saveCount);
        return sessionState;
    }

    public void clearWorkbenchState(String projectId, UserVo user, String memoryScope) {
        String normalizedMemoryScope = normalizeMemoryScope(memoryScope);
        if (MEMORY_SCOPE_WORKBENCH.equals(normalizedMemoryScope)) {
            String cacheKey = buildSessionStoreKey(projectId, user.getId(), normalizedMemoryScope);
            String legacyCacheKey = buildLegacySessionStoreKey(projectId, user.getId());
            sessionStore.remove(cacheKey);
            sessionStore.remove(legacyCacheKey);
        }
        logger.info("{} action=clear status=success projectId={} userId={} scope={}",
                SESSION_LOG_PREFIX, projectId, user.getId(), normalizedMemoryScope);
    }

    public String loadSessionState(String projectId, UserVo user) {
        return loadSessionState(projectId, user, MEMORY_SCOPE_WORKBENCH);
    }

    public String loadSessionState(String projectId, UserVo user, String memoryScope) {
        String normalizedMemoryScope = normalizeMemoryScope(memoryScope);
        String cacheKey = buildSessionStoreKey(projectId, user.getId(), normalizedMemoryScope);
        String stored = sessionStore.get(cacheKey);
        if (StringUtils.hasText(stored)) {
            long restoreCount = SESSION_RESTORE_COUNTER.incrementAndGet();
            logger.info("{} action=restore status=hit source=current projectId={} userId={} scope={} payloadLength={} restoreCount={}",
                    SESSION_LOG_PREFIX, projectId, user.getId(), normalizedMemoryScope, stored.length(), restoreCount);
            return stored;
        }

        String legacyCacheKey = buildLegacySessionStoreKey(projectId, user.getId());
        String legacyStored = MEMORY_SCOPE_WORKBENCH.equals(normalizedMemoryScope)
                ? sessionStore.get(legacyCacheKey)
                : null;
        if (StringUtils.hasText(legacyStored)) {
            String migratedPayload = legacyStored;
            sessionStore.put(cacheKey, migratedPayload);
            long restoreCount = SESSION_RESTORE_COUNTER.incrementAndGet();
            logger.info("{} action=restore status=hit source=legacy_migrated projectId={} userId={} scope={} payloadLength={} restoreCount={} ttlDays={}",
                    SESSION_LOG_PREFIX, projectId, user.getId(), normalizedMemoryScope, migratedPayload.length(), restoreCount, SESSION_STORE_TTL_DAYS);
            return migratedPayload;
        }

        long missCount = SESSION_MISS_COUNTER.incrementAndGet();
        logger.info("{} action=restore status=miss source=all projectId={} userId={} scope={} missCount={}",
                SESSION_LOG_PREFIX, projectId, user.getId(), normalizedMemoryScope, missCount);
        return "";
    }

    public String normalizeMemoryScope(String memoryScope) {
        if (!StringUtils.hasText(memoryScope)) {
            return MEMORY_SCOPE_WORKBENCH;
        }
        String normalized = memoryScope.trim().toLowerCase();
        String sanitized = normalized.replaceAll("[^a-z0-9_-]", "");
        return StringUtils.hasText(sanitized) ? sanitized : MEMORY_SCOPE_WORKBENCH;
    }

    private String buildSessionStoreKey(String projectId, String userId, String memoryScope) {
        String normalizedMemoryScope = normalizeMemoryScope(memoryScope);
        if (MEMORY_SCOPE_WORKBENCH.equals(normalizedMemoryScope)) {
            return SESSION_STORE_KEY_PREFIX + projectId + ":" + userId;
        }
        return SESSION_STORE_KEY_PREFIX + projectId + ":" + userId + ":" + normalizedMemoryScope;
    }

    private String buildLegacySessionStoreKey(String projectId, String userId) {
        return LEGACY_SESSION_STORE_KEY_PREFIX + projectId + ":" + userId;
    }
}
