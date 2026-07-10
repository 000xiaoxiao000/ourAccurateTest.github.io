package com.oAT.web.collector;

import java.io.Serializable;

public class CollectorSource implements Serializable {
    public enum CollectorType {
        RESIDENT,
        BATCH
    }

    public enum Health {
        ONLINE,
        SILENT,
        OFFLINE,
        UNKNOWN
    }

    private String sourceId;
    private String projectId;
    private String appId;
    private String appName;
    private String language;
    private CollectorType collectorType;
    private Long lastSeenTime;
    private Long reportIntervalMillis;
    private Health health;
    private String sessionId;
    private String addressIp;
    private String pid;

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public CollectorType getCollectorType() { return collectorType; }
    public void setCollectorType(CollectorType collectorType) { this.collectorType = collectorType; }
    public Long getLastSeenTime() { return lastSeenTime; }
    public void setLastSeenTime(Long lastSeenTime) { this.lastSeenTime = lastSeenTime; }
    public Long getReportIntervalMillis() { return reportIntervalMillis; }
    public void setReportIntervalMillis(Long reportIntervalMillis) { this.reportIntervalMillis = reportIntervalMillis; }
    public Health getHealth() { return health; }
    public void setHealth(Health health) { this.health = health; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getAddressIp() { return addressIp; }
    public void setAddressIp(String addressIp) { this.addressIp = addressIp; }
    public String getPid() { return pid; }
    public void setPid(String pid) { this.pid = pid; }
}
