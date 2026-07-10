package com.oAT.web.api.app;

import com.oAT.web.api.common.ApiSummaries.*;
import com.oAT.web.collector.CollectorSource;

import java.util.List;

public final class ApplicationCenterApiPayloads {

    private ApplicationCenterApiPayloads() {
    }

    public static class CollectorSourcesPayload {
        private List<CollectorSource> sources;
        private int total;
        private String currentUserRole;

        public List<CollectorSource> getSources() { return sources; }
        public void setSources(List<CollectorSource> sources) { this.sources = sources; }
        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
        public String getCurrentUserRole() { return currentUserRole; }
        public void setCurrentUserRole(String currentUserRole) { this.currentUserRole = currentUserRole; }
    }

    public static class AppSettingsPayload {
        private AppSettingsSummary app;
        private List<AppSummary> apps;
        private String currentUserRole;

        public AppSettingsSummary getApp() { return app; }
        public void setApp(AppSettingsSummary app) { this.app = app; }
        public List<AppSummary> getApps() { return apps; }
        public void setApps(List<AppSummary> apps) { this.apps = apps; }
        public String getCurrentUserRole() { return currentUserRole; }
        public void setCurrentUserRole(String currentUserRole) { this.currentUserRole = currentUserRole; }
    }

    public static class AppSettingsSummary {
        private String id;
        private String name;
        private String srcName;
        private String language;
        private String languageConfig;
        private String range;
        private String describe;
        private String properties;
        private String currentVersion;
        private String currentBranch;
        private String currentCommitId;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSrcName() { return srcName; }
        public void setSrcName(String srcName) { this.srcName = srcName; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        public String getLanguageConfig() { return languageConfig; }
        public void setLanguageConfig(String languageConfig) { this.languageConfig = languageConfig; }
        public String getRange() { return range; }
        public void setRange(String range) { this.range = range; }
        public String getDescribe() { return describe; }
        public void setDescribe(String describe) { this.describe = describe; }
        public String getProperties() { return properties; }
        public void setProperties(String properties) { this.properties = properties; }
        public String getCurrentVersion() { return currentVersion; }
        public void setCurrentVersion(String currentVersion) { this.currentVersion = currentVersion; }
        public String getCurrentBranch() { return currentBranch; }
        public void setCurrentBranch(String currentBranch) { this.currentBranch = currentBranch; }
        public String getCurrentCommitId() { return currentCommitId; }
        public void setCurrentCommitId(String currentCommitId) { this.currentCommitId = currentCommitId; }
    }

    public static class SaveAppSettingsRequest {
        private String name;
        private String srcName;
        private String language;
        private String languageConfig;
        private String range;
        private String describe;
        private String properties;
        private String currentVersion;
        private String currentBranch;
        private String currentCommitId;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSrcName() { return srcName; }
        public void setSrcName(String srcName) { this.srcName = srcName; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        public String getLanguageConfig() { return languageConfig; }
        public void setLanguageConfig(String languageConfig) { this.languageConfig = languageConfig; }
        public String getRange() { return range; }
        public void setRange(String range) { this.range = range; }
        public String getDescribe() { return describe; }
        public void setDescribe(String describe) { this.describe = describe; }
        public String getProperties() { return properties; }
        public void setProperties(String properties) { this.properties = properties; }
        public String getCurrentVersion() { return currentVersion; }
        public void setCurrentVersion(String currentVersion) { this.currentVersion = currentVersion; }
        public String getCurrentBranch() { return currentBranch; }
        public void setCurrentBranch(String currentBranch) { this.currentBranch = currentBranch; }
        public String getCurrentCommitId() { return currentCommitId; }
        public void setCurrentCommitId(String currentCommitId) { this.currentCommitId = currentCommitId; }
    }

    public static class RepositoryConfigPayload {
        private RepositorySummary app;
        private String currentUserRole;

        public RepositorySummary getApp() { return app; }
        public void setApp(RepositorySummary app) { this.app = app; }
        public String getCurrentUserRole() { return currentUserRole; }
        public void setCurrentUserRole(String currentUserRole) { this.currentUserRole = currentUserRole; }
    }

    public static class RepositorySummary {
        private String appId;
        private String appName;
        private String repoAddress;
        private String repoUserName;
        private String repoPassword;
        private boolean configured;

        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getAppName() { return appName; }
        public void setAppName(String appName) { this.appName = appName; }
        public String getRepoAddress() { return repoAddress; }
        public void setRepoAddress(String repoAddress) { this.repoAddress = repoAddress; }
        public String getRepoUserName() { return repoUserName; }
        public void setRepoUserName(String repoUserName) { this.repoUserName = repoUserName; }
        public String getRepoPassword() { return repoPassword; }
        public void setRepoPassword(String repoPassword) { this.repoPassword = repoPassword; }
        public boolean isConfigured() { return configured; }
        public void setConfigured(boolean configured) { this.configured = configured; }
    }

    public static class SaveRepositoryRequest {
        private String repoAddress;
        private String repoUserName;
        private String repoPassword;

        public String getRepoAddress() { return repoAddress; }
        public void setRepoAddress(String repoAddress) { this.repoAddress = repoAddress; }
        public String getRepoUserName() { return repoUserName; }
        public void setRepoUserName(String repoUserName) { this.repoUserName = repoUserName; }
        public String getRepoPassword() { return repoPassword; }
        public void setRepoPassword(String repoPassword) { this.repoPassword = repoPassword; }
    }
}
