package com.oAT.web.persistence.entity;


import java.io.Serializable;

public class App implements Serializable {

    /**
     * 名称
     */
    private String name;

    /**
     * 范围
     */
    private String range;

    /**
     * 源码工程名称
     */
    private String srcName;

    /**
     * 应用主语言。存量数据默认 JAVA。
     */
    private String language;

    /**
     * 按语言差异化的配置 JSON。旧字段继续保留为兼容别名。
     */
    private String languageConfig;

    /**
     * 创建用户ID
     */
    private String createUserId;

    /**
     * 创建项目
     */
    private String createProjectId;

    /**
     * 描述
     */
    private String describe;

    /**
     * 属性配置
     */
    private String properties;

    /**
     * 当前版本号
     */
    private String currentVersion;

    /**
     * 当前分支
     */
    private String currentBranch;

    /**
     * 当前CommitId
     */
    private String currentCommitId;


    /**
     * 代码仓库配置
     */
    private String repoAddress;

    private String repoUserName;

    private String repoPassword;


    // ============ getter/setter ============

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

    public enum Range {
        only,   //只属于指定项目
        all // 属于所有项目
    }

}
