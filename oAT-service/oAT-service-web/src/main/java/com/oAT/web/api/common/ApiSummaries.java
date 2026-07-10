package com.oAT.web.api.common;

public final class ApiSummaries {

    private ApiSummaries() {
    }

    public static class UserSummary {
        private String id;
        private String name;
        private String nickname;
        private String email;
        private String header;
        private String phone;
        private String readme;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getHeader() { return header; }
        public void setHeader(String header) { this.header = header; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getReadme() { return readme; }
        public void setReadme(String readme) { this.readme = readme; }
    }

    public static class AppSummary {
        private String id;
        private String name;
        private String srcName;
        private String language;
        private String languageConfig;
        private String describe;
        private String range;
        private int onlineCount;
        private String currentVersion;
        private String currentBranch;
        private String currentCommitId;
        private boolean repoConfigured;
        private String sourceType;

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
        public String getDescribe() { return describe; }
        public void setDescribe(String describe) { this.describe = describe; }
        public String getRange() { return range; }
        public void setRange(String range) { this.range = range; }
        public int getOnlineCount() { return onlineCount; }
        public void setOnlineCount(int onlineCount) { this.onlineCount = onlineCount; }
        public String getCurrentVersion() { return currentVersion; }
        public void setCurrentVersion(String currentVersion) { this.currentVersion = currentVersion; }
        public String getCurrentBranch() { return currentBranch; }
        public void setCurrentBranch(String currentBranch) { this.currentBranch = currentBranch; }
        public String getCurrentCommitId() { return currentCommitId; }
        public void setCurrentCommitId(String currentCommitId) { this.currentCommitId = currentCommitId; }
        public boolean isRepoConfigured() { return repoConfigured; }
        public void setRepoConfigured(boolean repoConfigured) { this.repoConfigured = repoConfigured; }
        public String getSourceType() { return sourceType; }
        public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    }
}
