package com.oAT.web.service.entity;

import java.io.Serializable;
import java.util.Date;

/**
 * 项目应用Vo
 */
public class AppVo implements Serializable {
    private String id;
    // 应用名称
    private String name;
    // 应用工程名称
    private String srcName;
    private String language;
    private String languageConfig;
    // 作用范围
    private String range;
    //  创建用户ID
    private String createUserId;
    // 创建项目
    private String createProjectId;
    private String describe;
    private int onlineCount;
    private Date createTime;
    private Date updateTime;
    // 属性配置
    private String properties;

    private String currentVersion;
    private String currentBranch;
    private String currentCommitId;

    // 代码仓库配置
    private String repoAddress;
    private String repoUserName;
    private String repoPassword;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRange() {
        return range;
    }

    public void setRange(String range) {
        this.range = range;
    }

    public String getCreateUserId() {
        return createUserId;
    }

    public void setCreateUserId(String createUserId) {
        this.createUserId = createUserId;
    }

    public String getCreateProjectId() {
        return createProjectId;
    }

    public void setCreateProjectId(String createProjectId) {
        this.createProjectId = createProjectId;
    }

    public String getDescribe() {
        return describe;
    }

    public void setDescribe(String describe) {
        this.describe = describe;
    }

    public int getOnlineCount() {
        return onlineCount;
    }

    public void setOnlineCount(int onlineCount) {
        this.onlineCount = onlineCount;
    }

    public Date getCreateTime() {
        return createTime;
    }


    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getSrcName() {
        return srcName;
    }

    public void setSrcName(String srcName) {
        this.srcName = srcName;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getLanguageConfig() {
        return languageConfig;
    }

    public void setLanguageConfig(String languageConfig) {
        this.languageConfig = languageConfig;
    }

    public String getProperties() {
        return properties;
    }

    public void setProperties(String properties) {
        this.properties = properties;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
    }

    public String getCurrentBranch() {
        return currentBranch;
    }

    public void setCurrentBranch(String currentBranch) {
        this.currentBranch = currentBranch;
    }

    public String getCurrentCommitId() {
        return currentCommitId;
    }

    public void setCurrentCommitId(String currentCommitId) {
        this.currentCommitId = currentCommitId;
    }

    public String getRepoAddress() {
        return repoAddress;
    }

    public void setRepoAddress(String repoAddress) {
        this.repoAddress = repoAddress;
    }

    public String getRepoUserName() {
        return repoUserName;
    }

    public void setRepoUserName(String repoUserName) {
        this.repoUserName = repoUserName;
    }

    public String getRepoPassword() {
        return repoPassword;
    }

    public void setRepoPassword(String repoPassword) {
        this.repoPassword = repoPassword;
    }

}
