package com.oAT.web.service.entity;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class AIInteractivePageVo implements Serializable {
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

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getProjectSummary() {
        return projectSummary;
    }

    public void setProjectSummary(String projectSummary) {
        this.projectSummary = projectSummary;
    }

    public String getWelcomeMessage() {
        return welcomeMessage;
    }

    public void setWelcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage;
    }

    public String getMascotHint() {
        return mascotHint;
    }

    public void setMascotHint(String mascotHint) {
        this.mascotHint = mascotHint;
    }

    public int getOnlineAppCount() {
        return onlineAppCount;
    }

    public void setOnlineAppCount(int onlineAppCount) {
        this.onlineAppCount = onlineAppCount;
    }

    public int getAppCount() {
        return appCount;
    }

    public void setAppCount(int appCount) {
        this.appCount = appCount;
    }

    public List<String> getAppNames() {
        return appNames;
    }

    public void setAppNames(List<String> appNames) {
        this.appNames = appNames;
    }

    public List<String> getStarterQuestions() {
        return starterQuestions;
    }

    public void setStarterQuestions(List<String> starterQuestions) {
        this.starterQuestions = starterQuestions;
    }

    public List<AIAbilityCardVo> getAbilityCards() {
        return abilityCards;
    }

    public void setAbilityCards(List<AIAbilityCardVo> abilityCards) {
        this.abilityCards = abilityCards;
    }

    public List<AIQuickLinkVo> getQuickLinks() {
        return quickLinks;
    }

    public void setQuickLinks(List<AIQuickLinkVo> quickLinks) {
        this.quickLinks = quickLinks;
    }

    public Map<String, String> getMascot() {
        return mascot;
    }

    public void setMascot(Map<String, String> mascot) {
        this.mascot = mascot;
    }

    public int getAiTimeout() {
        return aiTimeout;
    }

    public void setAiTimeout(int aiTimeout) {
        this.aiTimeout = aiTimeout;
    }

    public String getSessionState() {
        return sessionState;
    }

    public void setSessionState(String sessionState) {
        this.sessionState = sessionState;
    }
}
