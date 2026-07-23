package com.oAT.web.persistence.entity;


import java.io.Serializable;

public class VersionItem implements Serializable {
    private String appId;
    private String projectId;
    private String versionNumber;
    private String describe;
    private String programFile;
    private String[] configFile;
    private String[] databaseFile;

    private String sourceType;

    private String repoBranch;

    private String repoCommitId;

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(String versionNumber) {
        this.versionNumber = versionNumber;
    }

    public String getDescribe() {
        return describe;
    }

    public void setDescribe(String describe) {
        this.describe = describe;
    }

    public String getProgramFile() {
        return programFile;
    }

    public void setProgramFile(String programFile) {
        this.programFile = programFile;
    }

    public String[] getConfigFile() {
        return configFile;
    }

    public void setConfigFile(String[] configFile) {
        this.configFile = configFile;
    }

    public String[] getDatabaseFile() {
        return databaseFile;
    }

    public void setDatabaseFile(String[] databaseFile) {
        this.databaseFile = databaseFile;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getRepoBranch() {
        return repoBranch;
    }

    public void setRepoBranch(String repoBranch) {
        this.repoBranch = repoBranch;
    }

    public String getRepoCommitId() {
        return repoCommitId;
    }

    public void setRepoCommitId(String repoCommitId) {
        this.repoCommitId = repoCommitId;
    }
}
