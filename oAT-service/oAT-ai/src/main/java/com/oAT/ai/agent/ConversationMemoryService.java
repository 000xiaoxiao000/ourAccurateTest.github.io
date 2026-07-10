package com.oAT.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮对话记忆服务
 * 管理用户与 AI 的完整对话历史，支持上下文压缩和摘要生成
 *
 * <p>功能：
 * 1. 会话管理：创建/获取/删除会话，每个会话独立的消息列表
 * 2. 上下文窗口：自动维护最近 N 轮对话（默认20轮）
 * 3. 摘要生成：当消息超过阈值时，自动将早期对话压缩为摘要
 * 4. 意图追踪：记录每轮对话的意图主题，辅助理解用户目标
 * </p>
 */
public class ConversationMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationMemoryService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 会话存储：sessionId -> ConversationSession */
    private final ConcurrentHashMap<String, ConversationSession> sessions = new ConcurrentHashMap<>();

    /** 用户活跃会话：userId:projectId:memoryScope -> sessionId（最近一次使用的会话） */
    private final ConcurrentHashMap<String, String> userActiveSession = new ConcurrentHashMap<>();

    /** 最大消息轮数 */
    private final int maxMessageRounds;

    /** 触发摘要的消息轮数阈值 */
    private final int summaryThreshold;

    public ConversationMemoryService() {
        this.maxMessageRounds = 20;
        this.summaryThreshold = 10;
        logger.info("ConversationMemoryService initialized: maxRounds={}, summaryThreshold={}",
                maxMessageRounds, summaryThreshold);
    }

    public ConversationMemoryService(int maxMessageRounds, int summaryThreshold) {
        this.maxMessageRounds = maxMessageRounds;
        this.summaryThreshold = summaryThreshold;
    }

    /**
     * 创建新会话
     *
     * @param userId   用户ID
     * @param projectId 项目ID
     * @return 新会话ID
     */
    public String createSession(String userId, String projectId) {
        return createSession(userId, projectId, null);
    }

    /**
     * 创建新会话
     *
     * @param userId      用户ID
     * @param projectId   项目ID
     * @param memoryScope 记忆作用域
     * @return 新会话ID
     */
    public String createSession(String userId, String projectId, String memoryScope) {
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ConversationSession session = new ConversationSession(sessionId, userId, projectId, normalizeMemoryScope(memoryScope));
        sessions.put(sessionId, session);
        userActiveSession.put(buildActiveSessionKey(userId, projectId, memoryScope), sessionId);
        logger.info("Created conversation session: {} for user:{} project:{} scope:{}",
                sessionId, userId, projectId, session.getMemoryScope());
        return sessionId;
    }

    /**
     * 获取用户的活跃会话
     */
    public ConversationSession getActiveSession(String userId) {
        if (userId == null) {
            return null;
        }
        String userPrefix = safeKeyPart(userId) + ":";
        for (Map.Entry<String, String> entry : userActiveSession.entrySet()) {
            if (entry.getKey().startsWith(userPrefix)) {
                ConversationSession session = sessions.get(entry.getValue());
                if (session != null) {
                    return session;
                }
            }
        }
        return null;
    }

    /**
     * 获取指定用户、项目和作用域的活跃会话
     */
    public ConversationSession getActiveSession(String userId, String projectId, String memoryScope) {
        String sessionId = userActiveSession.get(buildActiveSessionKey(userId, projectId, memoryScope));
        return sessionId != null ? sessions.get(sessionId) : null;
    }

    /**
     * 获取指定会话
     */
    public ConversationSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 添加用户消息到会话
     */
    public void addUserMessage(String sessionId, String message) {
        ConversationSession session = sessions.get(sessionId);
        if (session == null) return;

        session.addMessage(new MessageRecord("user", message, System.currentTimeMillis()));
        session.detectIntent(message);

        // 检查是否需要摘要
        if (session.getMessageCount() >= summaryThreshold) {
            summarizeEarlyMessages(session);
        }
    }

    /**
     * 添加 AI 回复到会话
     */
    public void addAssistantMessage(String sessionId, String message, String usedTools) {
        ConversationSession session = sessions.get(sessionId);
        if (session == null) return;

        session.addMessage(new MessageRecord("assistant", message, System.currentTimeMillis(), usedTools));
    }

    /**
     * 构建用于 LLM 的对话上下文（包含摘要 + 最近 N 轮）
     *
     * @return 格式化的对话历史文本
     */
    public String buildContextForLLM(String sessionId, int recentRoundCount) {
        ConversationSession session = sessions.get(sessionId);
        if (session == null) return "";

        StringBuilder context = new StringBuilder();

        // 添加摘要（如果有）
        if (session.getSummary() != null && !session.getSummary().isEmpty()) {
            context.append("[之前的对话摘要]\n").append(session.getSummary()).append("\n\n");
        }

        // 添加最近的消息
        List<MessageRecord> messages = session.getRecentMessages(recentRoundCount * 2);
        for (MessageRecord msg : messages) {
            String role = "user".equals(msg.getRole()) ? "用户" : "AI助手";
            context.append(role).append(": ").append(msg.getContent()).append("\n");
        }

        // 追加当前意图信息
        List<String> recentIntents = session.getRecentIntents(3);
        if (!recentIntents.isEmpty()) {
            context.append("\n[近期讨论话题]: ").append(String.join(" → ", recentIntents)).append("\n");
        }

        return context.toString();
    }

    /**
     * 获取会话的完整消息列表（JSON格式）
     */
    public String getMessagesAsJson(String sessionId) {
        ConversationSession session = sessions.get(sessionId);
        if (session == null) return "[]";
        try {
            return OBJECT_MAPPER.writeValueAsString(session.getAllMessages());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * 删除指定会话
     */
    public void deleteSession(String sessionId) {
        ConversationSession removed = sessions.remove(sessionId);
        if (removed != null) {
            // 清理用户活跃会话映射
            userActiveSession.entrySet().removeIf(e -> e.getValue().equals(sessionId));
            logger.info("Deleted conversation session: {}", sessionId);
        }
    }

    /**
     * 清空指定用户在指定项目下的全部会话记忆，并返回新的空会话
     */
    public synchronized String clearUserProjectSessions(String userId, String projectId) {
        return clearUserProjectSessions(userId, projectId, null);
    }

    /**
     * 清空指定用户在指定项目和作用域下的全部会话记忆，并返回新的空会话
     */
    public synchronized String clearUserProjectSessions(String userId, String projectId, String memoryScope) {
        if (userId == null || projectId == null) {
            return null;
        }

        String normalizedScope = memoryScope == null ? null : normalizeMemoryScope(memoryScope);

        List<String> sessionIdsToRemove = new ArrayList<>();
        for (Map.Entry<String, ConversationSession> entry : sessions.entrySet()) {
            ConversationSession session = entry.getValue();
            if (session == null) {
                continue;
            }
            if (userId.equals(session.getUserId())
                    && projectId.equals(session.getProjectId())
                    && (normalizedScope == null || normalizedScope.equals(session.getMemoryScope()))) {
                sessionIdsToRemove.add(entry.getKey());
            }
        }

        for (String sessionId : sessionIdsToRemove) {
            deleteSession(sessionId);
        }

        return createSession(userId, projectId, normalizedScope);
    }

    /**
     * 清理过期会话（超过24小时无活动的）
     */
    public int cleanExpiredSessions(long maxInactiveMs) {
        long now = System.currentTimeMillis();
        int cleaned = 0;

        Iterator<Map.Entry<String, ConversationSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ConversationSession> entry = it.next();
            if (now - entry.getValue().getLastActiveTime() > maxInactiveMs) {
                it.remove();
                cleaned++;
            }
        }

        if (cleaned > 0) {
            logger.info("Cleaned {} expired conversation sessions", cleaned);
        }
        return cleaned;
    }

    /**
     * 获取所有会话统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSessions", sessions.size());
        stats.put("activeUsers", userActiveSession.size());

        int totalMessages = 0;
        for (ConversationSession session : sessions.values()) {
            totalMessages += session.getMessageCount();
        }
        stats.put("totalMessages", totalMessages);

        // 意图分布
        Map<String, Integer> intentDist = new HashMap<>();
        for (ConversationSession session : sessions.values()) {
            for (String intent : session.getIntents()) {
                intentDist.merge(intent, 1, Integer::sum);
            }
        }
        stats.put("intentDistribution", intentDist);

        return stats;
    }

    // ==================== 内部方法 ====================

    /**
     * 对早期消息进行摘要压缩
     */
    private void summarizeEarlyMessages(ConversationSession session) {
        List<MessageRecord> allMessages = session.getAllMessages();
        if (allMessages.size() <= summaryThreshold) return;

        // 取出需要摘要的部分
        int toSummarize = allMessages.size() - maxMessageRounds;
        if (toSummarize <= 0) return;

        List<MessageRecord> earlyMessages = allMessages.subList(0, Math.min(toSummarize, allMessages.size()));

        // 生成简单摘要：提取关键信息点
        StringBuilder summaryBuilder = new StringBuilder();
        Set<String> intentsInSummary = new LinkedHashSet<>();
        for (MessageRecord msg : earlyMessages) {
            if ("user".equals(msg.getRole())) {
                summaryBuilder.append("- 用户询问了关于「")
                        .append(truncate(msg.getContent(), 50))
                        .append("」的问题\n");
                intentsInSummary.addAll(detectSimpleIntents(msg.getContent()));
            }
        }

        // 补充意图总结
        if (!intentsInSummary.isEmpty()) {
            summaryBuilder.append("(主要涉及: ").append(String.join("、", intentsInSummary)).append(")");
        }

        session.setSummary(summaryBuilder.toString());
        session.trimToRecent(maxMessageRounds);

        logger.debug("Generated summary for session {}, compressed {} messages",
                session.getSessionId(), toSummarize);
    }

    private static List<String> detectSimpleIntents(String text) {
        List<String> intents = new ArrayList<>();
        String[][] keywords = {
                {"覆盖", "coverage"}, {"链路", "trace"},
                {"应用", "app"}, {"测试", "test"},
                {"性能", "performance"}, {"缺陷", "defect"},
                {"快照", "snapshot"}, {"代码", "code"}
        };
        String[] labels = {"覆盖率分析", "调用链路", "应用管理", "测试推荐",
                           "性能分析", "缺陷排查", "快照数据", "代码关系"};

        for (int i = 0; i < keywords.length; i++) {
            for (String kw : keywords[i]) {
                if (text.contains(kw)) {
                    intents.add(labels[i]);
                    break;
                }
            }
        }
        return intents;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    private String buildActiveSessionKey(String userId, String projectId, String memoryScope) {
        return safeKeyPart(userId) + ":" + safeKeyPart(projectId) + ":" + normalizeMemoryScope(memoryScope);
    }

    private String normalizeMemoryScope(String memoryScope) {
        if (memoryScope == null || memoryScope.trim().isEmpty()) {
            return "default";
        }
        return memoryScope.trim().toLowerCase(Locale.ROOT);
    }

    private String safeKeyPart(String value) {
        return value == null || value.trim().isEmpty() ? "default" : value.trim();
    }

    // ==================== 内部类 ====================

    /**
     * 对话会话
     */
    public static class ConversationSession {
        private final String sessionId;
        private final String userId;
        private final String projectId;
        private final String memoryScope;
        private final List<MessageRecord> messages = new ArrayList<>();
        private final List<String> intents = new ArrayList<>(10); // 最近意图（环形缓冲）
        private volatile String summary;
        private final long createTime;
        private volatile long lastActiveTime;

        ConversationSession(String sessionId, String userId, String projectId) {
            this(sessionId, userId, projectId, "default");
        }

        ConversationSession(String sessionId, String userId, String projectId, String memoryScope) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.projectId = projectId;
            this.memoryScope = memoryScope;
            this.createTime = System.currentTimeMillis();
            this.lastActiveTime = this.createTime;
        }

        synchronized void addMessage(MessageRecord record) {
            messages.add(record);
            lastActiveTime = System.currentTimeMillis();
        }

    void detectIntent(String question) {
        List<String> detected = ConversationMemoryService.detectSimpleIntents(question);
        for (String intent : detected) {
            addIntent(intent);
        }
    }

        synchronized void addIntent(String intent) {
            if (intents.size() >= 10) {
                intents.remove(0);
            }
            intents.add(intent);
        }

        synchronized List<MessageRecord> getAllMessages() { return new ArrayList<>(messages); }

        synchronized List<MessageRecord> getRecentMessages(int count) {
            int fromIndex = Math.max(0, messages.size() - count);
            return new ArrayList<>(messages.subList(fromIndex, messages.size()));
        }

        synchronized void trimToRecent(int keepCount) {
            int removeCount = messages.size() - keepCount;
            if (removeCount > 0) {
                messages.subList(0, removeCount).clear();
            }
        }

        synchronized int getMessageCount() { return messages.size(); }
        List<String> getIntents() { return new ArrayList<>(intents); }
        synchronized List<String> getRecentIntents(int count) {
            int fromIndex = Math.max(0, intents.size() - count);
            return new ArrayList<>(intents.subList(fromIndex, intents.size()));
        }

        String getSummary() { return summary; }
        synchronized void setSummary(String summary) { this.summary = summary; }
        String getSessionId() { return sessionId; }
        String getUserId() { return userId; }
        String getProjectId() { return projectId; }
        String getMemoryScope() { return memoryScope; }
        long getLastActiveTime() { return lastActiveTime; }
        long getCreateTime() { return createTime; }
    }

    /**
     * 消息记录
     */
    public static class MessageRecord implements Serializable {
        private final String role; // "user" or "assistant"
        private final String content;
        private final long timestamp;
        private final String usedTools;

        public MessageRecord(String role, String content, long timestamp) {
            this(role, content, timestamp, null);
        }

        public MessageRecord(String role, String content, long timestamp, String usedTools) {
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
            this.usedTools = usedTools;
        }

        public String getRole() { return role; }
        public String getContent() { return content; }
        public long getTimestamp() { return timestamp; }
        public String getUsedTools() { return usedTools; }
    }
}
