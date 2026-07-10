package com.oAT.web.api.ai;

import com.oAT.web.service.entity.AIAbilityCardVo;
import com.oAT.web.service.entity.AIQuickLinkVo;

import java.util.List;
import java.util.Map;


public final class AIInteractiveApiPayloads {

    private AIInteractiveApiPayloads() {
    }

    public static class AIInteractivePagePayload {
        private String projectId;
        private String projectName;
        private String projectSummary;
        private String welcomeMessage;
        private String mascotHint;
        private int onlineAppCount;
        private int appCount;
        private List<String> appNames;
        private List<String> starterQuestions;
        private List<AIAbilityCardVo> abilityCards;
        private List<AIQuickLinkVo> quickLinks;
        private Map<String, String> mascot;
        private int aiTimeout;
        private String sessionState;

        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getProjectName() { return projectName; }
        public void setProjectName(String projectName) { this.projectName = projectName; }
        public String getProjectSummary() { return projectSummary; }
        public void setProjectSummary(String projectSummary) { this.projectSummary = projectSummary; }
        public String getWelcomeMessage() { return welcomeMessage; }
        public void setWelcomeMessage(String welcomeMessage) { this.welcomeMessage = welcomeMessage; }
        public String getMascotHint() { return mascotHint; }
        public void setMascotHint(String mascotHint) { this.mascotHint = mascotHint; }
        public int getOnlineAppCount() { return onlineAppCount; }
        public void setOnlineAppCount(int onlineAppCount) { this.onlineAppCount = onlineAppCount; }
        public int getAppCount() { return appCount; }
        public void setAppCount(int appCount) { this.appCount = appCount; }
        public List<String> getAppNames() { return appNames; }
        public void setAppNames(List<String> appNames) { this.appNames = appNames; }
        public List<String> getStarterQuestions() { return starterQuestions; }
        public void setStarterQuestions(List<String> starterQuestions) { this.starterQuestions = starterQuestions; }
        public List<AIAbilityCardVo> getAbilityCards() { return abilityCards; }
        public void setAbilityCards(List<AIAbilityCardVo> abilityCards) { this.abilityCards = abilityCards; }
        public List<AIQuickLinkVo> getQuickLinks() { return quickLinks; }
        public void setQuickLinks(List<AIQuickLinkVo> quickLinks) { this.quickLinks = quickLinks; }
        public Map<String, String> getMascot() { return mascot; }
        public void setMascot(Map<String, String> mascot) { this.mascot = mascot; }
        public int getAiTimeout() { return aiTimeout; }
        public void setAiTimeout(int aiTimeout) { this.aiTimeout = aiTimeout; }
        public String getSessionState() { return sessionState; }
        public void setSessionState(String sessionState) { this.sessionState = sessionState; }
    }

    public static class AskRequest {
        private String question;
        private String pageContext;
        private String imageData;
        private String sessionState;
        private String activeSessionId;
        private String sessionSortMode;
        private Boolean timelineExpanded;
        private String memoryScope;

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getPageContext() { return pageContext; }
        public void setPageContext(String pageContext) { this.pageContext = pageContext; }
        public String getImageData() { return imageData; }
        public void setImageData(String imageData) { this.imageData = imageData; }
        public String getSessionState() { return sessionState; }
        public void setSessionState(String sessionState) { this.sessionState = sessionState; }
        public String getActiveSessionId() { return activeSessionId; }
        public void setActiveSessionId(String activeSessionId) { this.activeSessionId = activeSessionId; }
        public String getSessionSortMode() { return sessionSortMode; }
        public void setSessionSortMode(String sessionSortMode) { this.sessionSortMode = sessionSortMode; }
        public Boolean getTimelineExpanded() { return timelineExpanded; }
        public void setTimelineExpanded(Boolean timelineExpanded) { this.timelineExpanded = timelineExpanded; }
        public String getMemoryScope() { return memoryScope; }
        public void setMemoryScope(String memoryScope) { this.memoryScope = memoryScope; }
    }

    public static class SessionStateRequest {
        private String sessionState;
        private String memoryScope;

        public String getSessionState() { return sessionState; }
        public void setSessionState(String sessionState) { this.sessionState = sessionState; }
        public String getMemoryScope() { return memoryScope; }
        public void setMemoryScope(String memoryScope) { this.memoryScope = memoryScope; }
    }
}
