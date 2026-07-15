package com.oAT.web.api.context;

import com.oAT.web.api.common.ApiSummaries.*;
import com.oAT.web.service.entity.SystemLogVo;

import java.util.List;

public final class FrontendContextPayloads {

    private FrontendContextPayloads() {
    }

    public static class LoginRequest {
        private String nameOrEmail;
        private String password;

        public String getNameOrEmail() {
            return nameOrEmail;
        }

        public void setNameOrEmail(String nameOrEmail) {
            this.nameOrEmail = nameOrEmail;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class ProjectSummary {
        private String id;
        private String name;
        private String describe;
        private String create;
        private String createDisplayName;
        private int memberCount;
        private java.util.Date createTime;
        private java.util.Date updateTime;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescribe() { return describe; }
        public void setDescribe(String describe) { this.describe = describe; }
        public String getCreate() { return create; }
        public void setCreate(String create) { this.create = create; }
        public String getCreateDisplayName() { return createDisplayName; }
        public void setCreateDisplayName(String createDisplayName) { this.createDisplayName = createDisplayName; }
        public int getMemberCount() { return memberCount; }
        public void setMemberCount(int memberCount) { this.memberCount = memberCount; }
        public java.util.Date getCreateTime() { return createTime; }
        public void setCreateTime(java.util.Date createTime) { this.createTime = createTime; }
        public java.util.Date getUpdateTime() { return updateTime; }
        public void setUpdateTime(java.util.Date updateTime) { this.updateTime = updateTime; }
    }

    public static class SaveProjectRequest {
        private String name;
        private String describe;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescribe() {
            return describe;
        }

        public void setDescribe(String describe) {
            this.describe = describe;
        }
    }

    public static class DeleteProjectRequest {
        private String password;

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }



    public static class ProjectContext {
        private UserSummary currentUser;
        private ProjectSummary project;
        private List<AppSummary> apps;
        private List<SystemLogVo> recentLogs;
        private String currentUserRole;
        private int onlineAppCount;
        private int appCount;

        public UserSummary getCurrentUser() { return currentUser; }
        public void setCurrentUser(UserSummary currentUser) { this.currentUser = currentUser; }
        public ProjectSummary getProject() { return project; }
        public void setProject(ProjectSummary project) { this.project = project; }
        public List<AppSummary> getApps() { return apps; }
        public void setApps(List<AppSummary> apps) { this.apps = apps; }
        public List<SystemLogVo> getRecentLogs() { return recentLogs; }
        public void setRecentLogs(List<SystemLogVo> recentLogs) { this.recentLogs = recentLogs; }
        public String getCurrentUserRole() { return currentUserRole; }
        public void setCurrentUserRole(String currentUserRole) { this.currentUserRole = currentUserRole; }
        public int getOnlineAppCount() { return onlineAppCount; }
        public void setOnlineAppCount(int onlineAppCount) { this.onlineAppCount = onlineAppCount; }
        public int getAppCount() { return appCount; }
        public void setAppCount(int appCount) { this.appCount = appCount; }
    }
}
