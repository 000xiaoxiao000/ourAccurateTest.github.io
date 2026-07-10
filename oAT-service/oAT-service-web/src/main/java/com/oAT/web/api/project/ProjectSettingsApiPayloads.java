package com.oAT.web.api.project;

import com.oAT.web.api.common.ApiSummaries.*;

import java.util.List;

public final class ProjectSettingsApiPayloads {

    private ProjectSettingsApiPayloads() {
    }

    public static class AddMembersRequest {
        private List<String> userIds;

        public List<String> getUserIds() {
            return userIds;
        }

        public void setUserIds(List<String> userIds) {
            this.userIds = userIds;
        }
    }

    public static class UpdateMemberRoleRequest {
        private String role;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }

    public static class UpsertLabelRequest {
        private String type;
        private String name;
        private String color;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getColor() {
            return color;
        }

        public void setColor(String color) {
            this.color = color;
        }
    }

    public static class DeleteLabelRequest {
        private String type;
        private String name;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class SaveAppRequest {
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

    public static class DeleteAppRequest {
        private String password;

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class ProjectMembersPayload {
        private List<ProjectMemberSummary> members;
        private List<UserSummary> availableUsers;
        private String currentUserRole;

        public List<ProjectMemberSummary> getMembers() {
            return members;
        }

        public void setMembers(List<ProjectMemberSummary> members) {
            this.members = members;
        }

        public List<UserSummary> getAvailableUsers() {
            return availableUsers;
        }

        public void setAvailableUsers(List<UserSummary> availableUsers) {
            this.availableUsers = availableUsers;
        }

        public String getCurrentUserRole() {
            return currentUserRole;
        }

        public void setCurrentUserRole(String currentUserRole) {
            this.currentUserRole = currentUserRole;
        }
    }

    public static class ProjectMemberSummary {
        private String id;
        private String projectId;
        private String memberId;
        private String memberName;
        private String memberEmail;
        private String role;
        private boolean star;
        private boolean defaultProject;
        private java.util.Date createTime;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        public String getMemberId() {
            return memberId;
        }

        public void setMemberId(String memberId) {
            this.memberId = memberId;
        }

        public String getMemberName() {
            return memberName;
        }

        public void setMemberName(String memberName) {
            this.memberName = memberName;
        }

        public String getMemberEmail() {
            return memberEmail;
        }

        public void setMemberEmail(String memberEmail) {
            this.memberEmail = memberEmail;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public boolean isStar() {
            return star;
        }

        public void setStar(boolean star) {
            this.star = star;
        }

        public boolean isDefaultProject() {
            return defaultProject;
        }

        public void setDefaultProject(boolean defaultProject) {
            this.defaultProject = defaultProject;
        }

        public java.util.Date getCreateTime() {
            return createTime;
        }

        public void setCreateTime(java.util.Date createTime) {
            this.createTime = createTime;
        }
    }

    public static class ProjectLabelsPayload {
        private List<LabelSummary> usecaseLabels;
        private String currentUserRole;

        public List<LabelSummary> getUsecaseLabels() {
            return usecaseLabels;
        }

        public void setUsecaseLabels(List<LabelSummary> usecaseLabels) {
            this.usecaseLabels = usecaseLabels;
        }

        public String getCurrentUserRole() {
            return currentUserRole;
        }

        public void setCurrentUserRole(String currentUserRole) {
            this.currentUserRole = currentUserRole;
        }
    }

    public static class LabelSummary {
        private String name;
        private String color;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getColor() {
            return color;
        }

        public void setColor(String color) {
            this.color = color;
        }
    }
}
